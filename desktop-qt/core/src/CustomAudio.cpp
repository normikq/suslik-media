#include "suslik/core/CustomAudio.h"
#include "suslik/core/MirrorLink.h"
#include "JsonOpt.h"

#include <QJsonArray>
#include <QJsonDocument>

namespace suslik::core {

using namespace json;

QString CustomTrack::menuName() const {
    QString base = title;
    if (lang) {
        const QString tag = QStringLiteral(" [%1]").arg(*lang);
        if (title.endsWith(tag, Qt::CaseInsensitive)) base = title.left(title.size() - tag.size()).trimmed();
    }
    if (base.trimmed().isEmpty()) base = audioPath.mid(audioPath.lastIndexOf(QLatin1Char('/')) + 1);
    return base + (lang ? QStringLiteral(" [%1]").arg(*lang) : QString());
}

namespace {
QList<QJsonObject> objects(const QJsonObject &r, const char *key) {
    QList<QJsonObject> out;
    for (const QJsonValue &v : r.value(QLatin1String(key)).toArray())
        if (v.isObject()) out << v.toObject();
    return out;
}
std::optional<qint64> longOrNull(const QJsonObject &o, const QString &k) {
    return hasValue(o, k) ? std::optional<qint64>(optLong(o, k)) : std::nullopt;
}
} // namespace

QString CustomAudio::tracksPath(qint64 magnetId) { return QStringLiteral("/api/custom-audio/%1").arg(magnetId); }
QString CustomAudio::trackPath(const QString &trackId) { return QStringLiteral("/api/custom-audio/") + trackId; }
QString CustomAudio::offsetPath(const QString &trackId) { return QStringLiteral("/api/custom-audio/%1/offset").arg(trackId); }

bool CustomAudio::isSupported(const QString &path) { return path.endsWith(QLatin1String(".mka"), Qt::CaseInsensitive); }

QList<CustomTrack> CustomAudio::parseTracks(const QJsonObject &r) {
    QList<CustomTrack> out;
    for (const QJsonObject &t : objects(r, "tracks")) {
        const QString url = optString(t, QStringLiteral("url"));
        if (url.trimmed().isEmpty()) continue;
        if (!isSupported(optString(t, QStringLiteral("audioPath")))) continue;
        CustomTrack c;
        c.id = optString(t, QStringLiteral("id"));
        c.videoPath = optString(t, QStringLiteral("videoPath"));
        c.audioPath = optString(t, QStringLiteral("audioPath"));
        c.title = optString(t, QStringLiteral("title"));
        if (const QString l = optString(t, QStringLiteral("lang")); !l.trimmed().isEmpty() && l != QLatin1String("und")) c.lang = l;
        c.offsetMs = optLong(t, QStringLiteral("offsetMs"));
        c.mine = optBool(t, QStringLiteral("mine"));
        c.url = url;
        if (const qint64 id = optLong(t, QStringLiteral("audioMagnetId")); id > 0) c.audioMagnetId = id;
        out << c;
    }
    return out;
}

QList<CustomTrack> CustomAudio::forVideo(const QList<CustomTrack> &tracks, const std::optional<QString> &watchKey) {
    if (!watchKey) return {};
    const auto bar = watchKey->indexOf(QLatin1Char('|'));
    bool ok = false;
    (bar < 0 ? *watchKey : watchKey->left(bar)).toLongLong(&ok);
    if (!ok) return {};
    const QString path = bar < 0 ? *watchKey : watchKey->mid(bar + 1);
    QList<CustomTrack> out;
    for (const CustomTrack &t : tracks)
        if (t.videoPath == path) out << t;
    return out;
}

std::optional<CustomTrack> CustomAudio::byMenuName(const QList<CustomTrack> &tracks, const QString &name) {
    const auto cut = name.lastIndexOf(QLatin1String(" ["));
    const QString bare = cut < 0 ? name : name.left(cut);
    for (const CustomTrack &t : tracks)
        if (t.menuName() == name || (!t.lang && bare == t.menuName())) return t;
    return std::nullopt;
}

QJsonObject CustomAudio::checkBody(qint64 audioMagnetId, std::optional<qint64> movieMagnetId) {
    QJsonObject o{{QStringLiteral("audioMagnetId"), audioMagnetId}};
    if (movieMagnetId) o.insert(QStringLiteral("movieMagnetId"), *movieMagnetId);
    return o;
}

QJsonObject CustomAudio::offsetBody(qint64 offsetMs) { return QJsonObject{{QStringLiteral("offsetMs"), offsetMs}}; }

CustomCheck CustomAudio::parseCheck(const QJsonObject &r) {
    CustomCheck c;
    c.movieMagnetId = longOrNull(r, QStringLiteral("movieMagnetId"));
    c.attachable = optBool(r, QStringLiteral("attachable"), true);
    for (const QJsonObject &p : objects(r, "pairs")) {
        CustomPair pair{optString(p, QStringLiteral("videoPath")), optString(p, QStringLiteral("audioPath")), optString(p, QStringLiteral("voice")),
                        std::nullopt, std::nullopt, longOrNull(p, QStringLiteral("offsetMs"))};
        if (const auto s = longOrNull(p, QStringLiteral("season"))) pair.season = int(*s);
        if (const auto e = longOrNull(p, QStringLiteral("episode"))) pair.episode = int(*e);
        c.pairs << pair;
    }
    return c;
}

QList<QJsonObject> CustomAudio::parseAttached(const QJsonObject &r) {
    QList<QJsonObject> out = objects(r, "created");
    for (QJsonObject o : objects(r, "existing")) {
        o.insert(QStringLiteral("existing"), true);
        out << o;
    }
    return out;
}

std::optional<QString> CustomAudio::infoJson(const QList<CustomTrack> &tracks) {
    if (tracks.isEmpty()) return std::nullopt;
    QJsonObject out;
    for (const CustomTrack &t : tracks)
        out.insert(t.url, QJsonObject{{QStringLiteral("title"), t.menuName()}, {QStringLiteral("offsetMs"), t.offsetMs}});
    return QString::fromUtf8(QJsonDocument(out).toJson(QJsonDocument::Compact));
}

void CustomAudioIndex::read(const std::optional<QString> &text) {
    QMutexLocker l(&m_lock);
    m_byKey.clear();
    m_byWrapped.clear();
    if (!text) return;
    const QJsonObject o = QJsonDocument::fromJson(text->toUtf8()).object();
    for (auto it = o.begin(); it != o.end(); ++it) {
        if (!it.value().isObject()) continue;
        const QJsonObject t = it.value().toObject();
        m_byKey.insert(MirrorLink::fileKey(it.key()), Info{optString(t, QStringLiteral("title")), optLong(t, QStringLiteral("offsetMs"))});
    }
}

std::optional<CustomAudioIndex::Info> CustomAudioIndex::of(const QString &url) const {
    QMutexLocker l(&m_lock);
    if (const auto it = m_byWrapped.constFind(url); it != m_byWrapped.cend()) return *it;
    if (const auto it = m_byKey.constFind(MirrorLink::fileKey(url)); it != m_byKey.cend()) return *it;
    return std::nullopt;
}

void CustomAudioIndex::noteWrapped(const QString &url, const QString &wrapped) {
    const auto info = of(url);
    if (!info) return;
    QMutexLocker l(&m_lock);
    m_byWrapped.insert(wrapped, *info);
}

} // namespace suslik::core

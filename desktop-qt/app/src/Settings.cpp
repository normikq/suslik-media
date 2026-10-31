#include "Settings.h"
#include "AppDir.h"
#include "Updater.h"

#include "suslik/core/ApiClient.h"
#include "suslik/core/MarkersDetector.h"
#include "suslik/core/PlaybackPresets.h"
#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"
#include "suslik/core/Versions.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QLocale>
#include <QPointer>
#include <QThreadPool>

namespace suslik::app {

using namespace suslik::core;

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
QString tx(const QString &key, const QHash<QString, QString> &p = {}) {
    QString s = Strings::get(key);
    for (auto it = p.cbegin(); it != p.cend(); ++it) s.replace(QLatin1Char('{') + it.key() + QLatin1Char('}'), it.value());
    return s;
}
Store &store() { return Store::of(S("settings")); }
std::optional<int> intOf(const std::optional<QString> &v) {
    bool ok = false;
    const int n = v ? v->toInt(&ok) : 0;
    return ok ? std::optional<int>(n) : std::nullopt;
}
QString bytesText(qint64 b) {
    static const QLocale ru(QLocale::Russian);
    if (b >= (1LL << 30)) return ru.toString(double(b) / (1LL << 30), 'f', 1) + S(" ГБ");
    if (b >= (1LL << 20)) return ru.toString(double(b) / (1LL << 20), 'f', 0) + S(" МБ");
    return ru.toString(double(b) / 1024, 'f', 0) + S(" КБ");
}
qint64 folderBytes(const QString &path) {
    qint64 n = 0;
    for (const QFileInfo &f : QDir(path).entryInfoList(QDir::Files | QDir::Dirs | QDir::NoDotAndDotDot | QDir::Hidden))
        n += f.isDir() ? folderBytes(f.filePath()) : f.size();
    return n;
}
struct Sizes {
    qint64 reserve = 0, posters = 0, other = 0;
    qint64 total() const { return reserve + posters + other; }
};
/// Медленно (обходит папки): не в главном потоке.
Sizes measure() {
    return {folderBytes(reserveDir()), folderBytes(AppDir::file(S("posters"))),
            Updater::tempBytes() + QFileInfo(AppDir::file(S("player.old.log"))).size() + folderBytes(AppDir::file(S("smtc")))};
}
} // namespace

QString reserveDir() { return QDir::temp().filePath(S("SuslikMediaQt-stream")); }

namespace PlaybackSettings {
int burstMb() { return PlaybackPresets::clampBurst(intOf(store().get(S("burst_mb"))).value_or(PlaybackPresets::BURST_DEFAULT)); }
int cacheMs() {
    if (auto v = intOf(store().get(S("cache_ms")))) return *v;
    return intOf(store().get(S("vlc_cache_ms"))).value_or(PlaybackPresets::CACHE_DEFAULT);
}
bool customAudioOn() { return store().get(S("custom_audio_off")) != S("1"); }
bool autoSkipIntro() { return store().get(S("auto_skip_intro")) == S("1"); }
bool offerNextOnCredits() { return store().get(S("next_on_credits_off")) != S("1"); }
int nextCountdown() {
    const auto v = intOf(store().get(S("next_countdown")));
    return v && MarkersDetector::COUNTDOWN_CHOICES.contains(*v) ? *v : MarkersDetector::COUNTDOWN_DEFAULT;
}
bool smartSubs() { return store().get(S("smart_subs_off")) != S("1"); }
bool discordOn() { return store().get(S("discord_rpc")) != S("0"); }
QString betaCode() { return store().get(S("beta_code")).value_or(QString()); }
bool betaOn() { return store().get(S("beta_off")) != S("1"); }
std::optional<QString> sentBetaCode() {
    const QString c = betaCode();
    return !c.isEmpty() && betaOn() ? std::optional<QString>(c) : std::nullopt;
}
bool betaChannel() { return sentBetaCode().has_value(); }
} // namespace PlaybackSettings

Prefs::Prefs(ApiClient &api, Updater &updater, QObject *parent) : QObject(parent), m_api(api), m_updater(updater) {
    m_api.setBetaCodeProvider([] { return PlaybackSettings::sentBetaCode(); });
}

QVariantList Prefs::burstPresets() const {
    QVariantList out;
    for (const auto &p : PlaybackPresets::burst(tx(S("settings.na_diske"))))
        out << QVariantMap{{S("value"), p.value}, {S("title"), p.title}, {S("hint"), p.hint}};
    return out;
}

QVariantList Prefs::cachePresets() const {
    QVariantList out;
    for (const auto &p : PlaybackPresets::cache()) out << QVariantMap{{S("value"), p.value}, {S("title"), p.title}, {S("hint"), p.hint}};
    return out;
}

QVariantList Prefs::countdownChoices() const {
    QVariantList out;
    for (int v : MarkersDetector::COUNTDOWN_CHOICES)
        out << QVariantMap{{S("value"), v}, {S("title"), v == 0 ? tx(S("settings.srazu")) : tx(S("settings.n_sek"), {{S("n"), QString::number(v)}})}};
    return out;
}

void Prefs::setBurstMb(int v) { store().put(S("burst_mb"), QString::number(v)); emit changed(); }
void Prefs::setCacheMs(int v) { store().put(S("cache_ms"), QString::number(v)); emit changed(); }
void Prefs::setCustomAudioOn(bool on) { store().put(S("custom_audio_off"), on ? std::nullopt : std::optional<QString>(S("1"))); emit changed(); }
void Prefs::setAutoSkipIntro(bool on) { store().put(S("auto_skip_intro"), on ? std::optional<QString>(S("1")) : std::nullopt); emit changed(); }
void Prefs::setOfferNextOnCredits(bool on) { store().put(S("next_on_credits_off"), on ? std::nullopt : std::optional<QString>(S("1"))); emit changed(); }
void Prefs::setNextCountdown(int v) { store().put(S("next_countdown"), QString::number(v)); emit changed(); }
void Prefs::setSmartSubs(bool on) { store().put(S("smart_subs_off"), on ? std::nullopt : std::optional<QString>(S("1"))); emit changed(); }
void Prefs::setDiscordOn(bool on) { store().put(S("discord_rpc"), on ? S("1") : S("0")); emit changed(); }

QString Prefs::mpvPath() const {
    // libmpv-2.dll кладёт установщик рядом с приложением (шаг 4); скачанный — в папке данных.
    for (const QString &dir : {QCoreApplication::applicationDirPath(), AppDir::dir()}) {
        const QString f = QDir(dir).filePath(S("libmpv-2.dll"));
        if (QFile::exists(f)) return QDir::toNativeSeparators(f);
    }
    return {};
}

void Prefs::measureStorage() {
    m_storageText.clear();
    emit storageChanged();
    QPointer<Prefs> self(this);
    QThreadPool::globalInstance()->start([self] {
        const Sizes s = measure();
        QMetaObject::invokeMethod(qApp, [self, s] {
            if (!self) return;
            self->m_lastTotal = s.total();
            self->m_storageText = tx(S("settings.zapas_filmov_p_postery"),
                                     {{S("reserve"), bytesText(s.reserve)}, {S("posters"), bytesText(s.posters)}, {S("other"), bytesText(s.other)}});
            emit self->storageChanged();
        });
    });
}

void Prefs::clearCache() {
    if (m_clearing) return;
    m_clearing = true;
    emit storageChanged();
    const qint64 before = m_lastTotal;
    QPointer<Prefs> self(this);
    QThreadPool::globalInstance()->start([self, before] {
        // Настройки открываются только без фильма: весь запас на диске — от закрытых фильмов.
        QDir(reserveDir()).removeRecursively();
        QDir posters(AppDir::file(S("posters")));
        for (const QString &f : posters.entryList(QDir::Files)) posters.remove(f);
        Updater::cleanup();
        QFile::remove(AppDir::file(S("player.old.log")));
        QDir(AppDir::file(S("smtc"))).removeRecursively();
        const Sizes after = measure();
        QMetaObject::invokeMethod(qApp, [self, before, after] {
            if (!self) return;
            self->m_clearing = false;
            self->m_lastTotal = after.total();
            self->m_storageText = tx(S("settings.zapas_filmov_p_postery"), {{S("reserve"), bytesText(after.reserve)},
                                                                            {S("posters"), bytesText(after.posters)},
                                                                            {S("other"), bytesText(after.other)}});
            self->m_storageState = before >= 0 ? tx(S("settings.osvobozhdeno_p"), {{S("p"), bytesText(std::max<qint64>(0, before - after.total()))}})
                                               : tx(S("settings.gotovo"));
            emit self->storageChanged();
        });
    });
}

void Prefs::resetState() {
    m_channelState.clear();
    m_storageState.clear();
    emit channelStateChanged();
    emit storageChanged();
}

void Prefs::checkUpdates() {
    setChannelState(tx(S("settings.proveryaem_obnovleniya")));
    QPointer<Prefs> self(this);
    m_updater.latest([self](std::optional<Updater::Info> info) {
        if (!self) return;
        const QString installed = self->m_updater.current();
        const QString remote = info ? info->version : QString();
        const auto v = UpdateChannel::verdict(remote, installed, !PlaybackSettings::betaChannel());
        QString text = UpdateChannel::message(v, remote, installed, info && info->beta);
        if (v == UpdateChannel::Verdict::Offer) text += tx(S("settings.knopka_obnovit_v_shapke"));
        else if (v == UpdateChannel::Verdict::Replace) text += tx(S("settings.knopka_obnovit_v_shapke_2"));
        self->setChannelState(text);
        self->m_updater.setAvailable(UpdateChannel::offers(v) ? info : std::nullopt);
    });
}

void Prefs::saveBetaCode(const QString &code) {
    const QString c = code.trimmed();
    store().put(S("beta_code"), c.isEmpty() ? std::nullopt : std::optional<QString>(c));
    emit changed();
    if (c.isEmpty()) { setChannelState(tx(S("settings.vvedite_kod_testirovschika"))); return; }
    setChannelState(tx(S("settings.proveryaem_kod")));
    QPointer<Prefs> self(this);
    m_api.get(S("/api/app/beta"), [self](const ApiResult &r) {
        if (!self) return;
        if (!r.ok()) self->setChannelState(tx(S("settings.ne_udalos_proverit_kod")));
        else if (r.json.value(S("tester")).toBool()) self->checkUpdates();
        else self->setChannelState(tx(S("settings.server_ne_prinyal_etot")));
    });
}

void Prefs::pickChannel(bool beta) {
    store().put(S("beta_off"), beta ? std::nullopt : std::optional<QString>(S("1")));
    emit changed();
    if (!beta) checkUpdates();
    else if (PlaybackSettings::betaCode().isEmpty()) setChannelState(tx(S("settings.vvedite_kod_testirovschika_i")));
    else saveBetaCode(PlaybackSettings::betaCode());
}

} // namespace suslik::app

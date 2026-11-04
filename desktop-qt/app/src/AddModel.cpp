#include "AddModel.h"
#include "Library.h"

#include "suslik/core/ApiClient.h"
#include "suslik/core/Strings.h"
#include "suslik/core/TorrentInspector.h"
#include "suslik/core/UrlCoding.h"

#include <QDateTime>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QPointer>
#include <QRegularExpression>
#include <QTimer>

namespace suslik::app {

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
qint64 nowMs() { return QDateTime::currentMSecsSinceEpoch(); }
} // namespace

void rejectIfNoVideo(Library &lib, qint64 id, std::function<void(std::optional<QString>)> done) {
    ApiClient *api = &lib.api();
    api->get(S("/api/magnets/%1/files").arg(id), [api, id, done](const ApiResult &r) {
        if (!r.ok() || !r.json.value(S("files")).isArray()) { done(std::nullopt); return; }
        QStringList names;
        for (const auto &v : r.json.value(S("files")).toArray()) names << v.toObject().value(S("name")).toString();
        const auto rejected = TorrentInspector::rejectionForPaths(names);
        if (!rejected) { done(std::nullopt); return; }
        api->post(S("/api/magnets/%1/cancel").arg(id), {}, [rejected, done](const ApiResult &c) {
            done(*rejected + (c.ok() ? QString() : tx(S("add.rejected_cleanup_note"))));
        });
    });
}

QVariantList AddModel::added() const {
    QVariantList out;
    for (const Added &a : m_added)
        out << QVariantMap{{S("key"), a.key}, {S("name"), a.name}, {S("note"), a.note}, {S("ok"), a.ok}, {S("done"), a.done}, {S("progress"), a.progress}};
    return out;
}

AddModel::Added *AddModel::find(qint64 key) {
    for (Added &a : m_added)
        if (a.key == key) return &a;
    return nullptr;
}

qint64 AddModel::push(const QString &name) {
    Added a;
    a.key = m_next++;
    a.name = name;
    a.note = tx(S("add.otpravlyayu"));
    m_added.prepend(a);
    emit changed();
    return a.key;
}

void AddModel::update(qint64 key, const std::function<void(Added &)> &f) {
    if (Added *a = find(key)) { f(*a); emit changed(); }
}

void AddModel::submit(const QString &text) {
    const QStringList links = core::parseMagnetLinks(text);
    if (links.isEmpty()) {
        setStatus(tx(text.trimmed().isEmpty() ? S("add.vstavte_magnet_ssylku") : S("add.vstavte_ssylku_kotoraya_nachin")), false);
        return;
    }
    m_status.clear();
    m_statusOk = true;
    for (const QString &link : links) {
        static const QRegularExpression dnRe(S("[?&]dn=([^&]+)"));
        const auto m = dnRe.match(link);
        const QString dn = m.hasMatch() ? core::formDecode(m.captured(1)).value_or(tx(S("add.magnet_ssylka"))) : tx(S("add.magnet_ssylka"));
        const qint64 key = push(dn);
        // сразу видно, что это не видео (по имени раздачи)
        if (const auto reason = TorrentInspector::rejectionForMagnetName(dn)) {
            update(key, [&](Added &a) { a.note = *reason; a.ok = false; });
            continue;
        }
        QPointer<AddModel> self(this);
        m_lib->api().post(S("/api/magnets"), QJsonObject{{S("magnet"), link}}, [self, key](const ApiResult &r) {
            if (!self) return;
            if (!r.ok()) { self->update(key, [&](Added &a) { a.note = ApiClient::describe(r.error); a.ok = false; }); return; }
            self->follow(key, r.json.value(S("id")).toInteger(), r.json.value(S("ready")).toBool());
        });
    }
    emit changed();
}

void AddModel::addTorrents(const QList<QUrl> &files) {
    QStringList tooBig;
    QList<QString> ok;
    for (const QUrl &u : files) {
        const QString path = u.isLocalFile() ? u.toLocalFile() : u.toString();
        if (!path.endsWith(S(".torrent"), Qt::CaseInsensitive)) continue;
        if (QFileInfo(path).size() > 1024 * 1024) tooBig << QFileInfo(path).fileName();
        else ok << path;
    }
    if (ok.isEmpty() && tooBig.isEmpty()) { setStatus(tx(S("add.vyberite_fayl_torrent")), false); return; }
    if (!tooBig.isEmpty()) setStatus(tx(S("add.bolshe_mb_p"), {{"p", tooBig.join(S(", "))}}), false);
    else setStatus(QString(), true);
    for (const QString &path : ok) {
        const QString name = QFileInfo(path).fileName();
        const qint64 key = push(name.left(name.size() - 8));
        QFile f(path);
        if (!f.open(QIODevice::ReadOnly)) { update(key, [](Added &a) { a.note = tx(S("add.vyberite_fayl_torrent")); a.ok = false; }); continue; }
        const QByteArray bytes = f.readAll();
        if (bytes.size() > 1024 * 1024) { update(key, [](Added &a) { a.note = tx(S("add.fayl_bolshe_mb")); a.ok = false; }); continue; }
        // .torrent без видео не загружаем вовсе
        if (const auto rejected = TorrentInspector::rejectionForTorrent(bytes)) { update(key, [&](Added &a) { a.note = *rejected; a.ok = false; }); continue; }
        QPointer<AddModel> self(this);
        m_lib->api().postTorrent(bytes, name, [self, key](const ApiResult &r) {
            if (!self) return;
            if (!r.ok()) { self->update(key, [&](Added &a) { a.note = ApiClient::describe(r.error); a.ok = false; }); return; }
            self->follow(key, r.json.value(S("id")).toInteger(), r.json.value(S("ready")).toBool());
        });
    }
}

void AddModel::ready(qint64 key, qint64 id, const QString &note) {
    QPointer<AddModel> self(this);
    rejectIfNoVideo(*m_lib, id, [self, key, note](std::optional<QString> rejected) {
        if (!self) return;
        self->update(key, [&](Added &a) {
            if (rejected) { a.note = *rejected; a.ok = false; } else { a.note = note; a.progress = 1; }
            a.done = true;
        });
        self->m_lib->refresh();
    });
}

// Состояние AllDebrid до готовности (тогда медиатека обновляется) или ошибки: первую минуту раз в 4 с, потом раз в 15 с.
void AddModel::follow(qint64 key, qint64 id, bool readyNow, int round, qint64 started) {
    if (round == 0) {
        if (readyNow) { ready(key, id, tx(S("add.gotovo_uzhe_v_biblioteke"))); return; }
        update(key, [](Added &a) { a.note = tx(S("add.dobavleno_alldebrid_kachaet")); });
        started = nowMs();
    }
    if (round >= 120) return;
    QPointer<AddModel> self(this);
    QTimer::singleShot(nowMs() - started < 60000 ? 4000 : 15000, this, [self, key, id, round, started] {
        if (!self) return;
        self->m_lib->api().get(S("/api/magnets/%1").arg(id), [self, key, id, round, started](const ApiResult &r) {
            if (!self) return;
            if (!r.ok()) { self->follow(key, id, false, round + 1, started); return; }
            const QJsonObject &st = r.json;
            const qint64 total = st.value(S("size")).toInteger(), got = st.value(S("downloaded")).toInteger();
            const int code = st.value(S("statusCode")).toInt();
            if (code == 4 || st.value(S("ready")).toBool()) { self->ready(key, id, tx(S("add.gotovo_fayly_v_biblioteke"))); return; }
            if (code >= 5) { self->update(key, [&](Added &a) { a.note = tx(S("add.oshibka_p"), {{"p", st.value(S("status")).toString()}}); a.ok = false; }); return; }
            self->update(key, [&](Added &a) {
                a.progress = total > 0 ? std::clamp(double(got) / double(total), 0.0, 1.0) : -1;
                const QString s = st.value(S("status")).toString();
                a.note = (s.trimmed().isEmpty() ? tx(S("add.obrabotka")) : s) + (total > 0 ? S(" · %1%").arg(got * 100 / total) : QString());
            });
            self->follow(key, id, false, round + 1, started);
        });
    });
}

} // namespace suslik::app

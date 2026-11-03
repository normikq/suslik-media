#include "RuTrackerClient.h"
#include "BrowserBridge.h"
#include "../player/PlayerLog.h"

#include "suslik/core/Strings.h"
#include "suslik/core/Trackers.h"

#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QPointer>
#include <QRegularExpression>
#include <QUrl>
#include <memory>

namespace suslik::app::rutracker {

using namespace suslik::core;

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }

const QByteArray kUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0";

QNetworkAccessManager &nam() {
    static QNetworkAccessManager *m = new QNetworkAccessManager();
    return *m;
}

/// GET без переадресаций (как у Kotlin-версии: вход уводит на форму, это видно по странице).
void get(const QString &url, const QString &cookie, int timeoutMs, const QList<std::pair<QByteArray, QByteArray>> &headers,
         QObject *ctx, std::function<void(int code, QByteArray body, QString error)> cb) {
    QNetworkRequest req{QUrl(url)};
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
    req.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);
    req.setTransferTimeout(timeoutMs);
    req.setRawHeader("User-Agent", kUserAgent);
    req.setRawHeader("Cookie", cookie.toUtf8());
    for (const auto &[k, v] : headers) req.setRawHeader(k, v);
    QNetworkReply *reply = nam().get(req);
    QPointer<QObject> guard(ctx);
    QObject::connect(reply, &QNetworkReply::finished, reply, [reply, guard, cb] {
        reply->deleteLater();
        if (!guard) return;
        const int code = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QByteArray body = reply->readAll();
        QString err;
        if (code == 0) err = reply->errorString().isEmpty() ? tx(S("trackers.connection_error")) : reply->errorString();
        cb(code, body, err);
    });
}

QString userFromPage(const QString &html) {
    static const QRegularExpression re1(S(R"(logged-in-username[^>]*>([^<]+))"));
    static const QRegularExpression re2(S(R"(user-name[^>]*>([^<]+))"));
    static const QRegularExpression re3(S(R"(href="profile\.php\?mode=viewprofile[^>]*><b>([^<]+)</b>)"));
    for (const auto *re : {&re1, &re2, &re3})
        if (const auto m = re->match(html); m.hasMatch()) return m.captured(1).trimmed();
    return {};
}

/// Проверка прямым запросом (браузера нет или его не пустил Cloudflare).
void testDirect(const QString &base, const QString &cookie, QObject *ctx, std::function<void(bool, QString)> cb) {
    get(base + S("/forum/index.php"), cookie, 10000, {{"Accept-Language", "ru,en;q=0.9"}}, ctx,
        [cb](int code, const QByteArray &body, const QString &err) {
            if (code == 0) { cb(false, err); return; }
            const QString html = Cp1251::decode(body);
            if (html.contains(S("Just a moment")) || html.contains(S("challenge-platform"))) {
                const QString tip = BrowserBridge::isAnyAvailable()
                                        ? tx(S("trackers.tip_login_button"), {{"browser", BrowserBridge::activeBrowserName()}}) : QString();
                cb(false, tx(S("trackers.cloudflare_check"), {{"tip", tip}}));
                return;
            }
            if (const QString user = userFromPage(html); !user.isEmpty()) {
                cb(true, tx(S("trackers.authorized_as"), {{"user", user}, {"note", browserNote()}}));
                return;
            }
            if (html.contains(S("name=\"login_username\""))) { cb(false, tx(S("trackers.session_rejected"))); return; }
            cb(false, tx(S("trackers.failed_check_response")));
        });
}

/// Один запрос поиска: фоновый браузер, потом прямой HTTP.
void query(const QString &q, const QString &base, QObject *ctx, std::function<void(QList<SearchHit>, QString)> cb) {
    auto direct = [q, base, ctx, cb] {
        const QString c = RuTracker::normalizeCookie(RuTracker::cookie());
        get(base + S("/forum/tracker.php?nm=") + QString::fromLatin1(Cp1251::urlEncode(q)), c, 15000,
            {{"Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"},
             {"Accept-Language", "ru,en;q=0.9"},
             {"Referer", (base + S("/forum/tracker.php")).toUtf8()}},
            ctx, [cb](int code, const QByteArray &body, const QString &err) {
                if (code == 0) { cb({}, err); return; }
                if (code == 403) { cb({}, S("Cloudflare 403 (защита от ботов)")); return; }
                if (code != 200) { cb({}, S("HTTP %1").arg(code)); return; }
                const QString html = Cp1251::decode(body);
                if (html.contains(S("Just a moment"), Qt::CaseInsensitive) || html.contains(S("challenge-platform"), Qt::CaseInsensitive)) {
                    cb({}, S("Cloudflare Turnstile проверка"));
                    return;
                }
                // Без входа tracker.php уводит на форму входа: это не «ничего не найдено»
                if (html.contains(S("name=\"login_username\"")) && !html.contains(S("id=\"tor-tbl\""))) {
                    cb({}, S("куки не приняты — обновите bb_session в «Трекеры»"));
                    return;
                }
                cb(RuTracker::parseHtml(html), {});
            });
    };
    if (!BrowserBridge::isAvailable()) { direct(); return; }
    BrowserBridge::instance().searchHtml(base, q, RuTracker::parseCookieMap(RuTracker::cookie()), ctx,
        [cb, direct](std::optional<QString> json, const QString &error) {
            if (json) {
                const QString t = json->trimmed();
                cb(t.isEmpty() || t == S("[]") ? QList<SearchHit>{} : RuTracker::parseJsonResults(t), {});
                return;
            }
            if (!error.isEmpty()) { cb({}, error); return; }
            direct(); // браузер не ответил — прямой запрос
        });
}

} // namespace

QString browserNote() {
    const QString via = BrowserBridge::isAnyAvailable() ? BrowserBridge::activeBrowserName() : QString();
    const auto src = Browsers::fromKey(RuTracker::cookieSource());
    const QString from = src ? Browsers::displayName(*src) : QString();
    if (!from.isEmpty() && !via.isEmpty() && from != via) return tx(S("trackers.note_from_via"), {{"from", from}, {"via", via}});
    if (!from.isEmpty()) return S(" · ") + from;
    if (!via.isEmpty()) return S(" · ") + via;
    return {};
}

void testConnection(QObject *ctx, std::function<void(bool, QString)> cb) {
    const QString c = RuTracker::normalizeCookie(RuTracker::cookie());
    if (c.trimmed().isEmpty()) { cb(false, tx(S("trackers.no_cookies"))); return; }
    QString err;
    const QString base = RuTracker::validateUrl(RuTracker::url(), &err);
    if (base.isEmpty()) { cb(false, err.isEmpty() ? tx(S("trackers.invalid_url")) : err); return; }

    // 1. Через фоновый браузер: он проходит Cloudflare Turnstile
    if (BrowserBridge::isAnyAvailable()) {
        QPointer<QObject> guard(ctx);
        BrowserBridge::instance().checkConnection(base, RuTracker::parseCookieMap(c), ctx,
            [guard, base, c, cb](bool ok, const QString &message, const QString &updated) {
                if (ok) {
                    if (!updated.isEmpty()) RuTracker::setCookie(updated);
                    cb(true, tx(S("trackers.authorized_as"), {{"user", message}, {"note", browserNote()}}));
                    return;
                }
                const bool rejected = message.contains(S("Сессия не принята")) || message.contains(tx(S("trackers.session_rejected")));
                const bool retry = message.contains(S("Cloudflare")) || message.contains(S("таймаут")) || message.contains(tx(S("trackers.timeout")));
                if (rejected || !retry || !guard) { cb(false, message); return; }
                testDirect(base, c, guard, cb); // 2. запасной прямой запрос
            });
        return;
    }
    testDirect(base, c, ctx, cb);
}

void search(const QString &query_, QObject *ctx, std::function<void(QList<SearchHit>, QString)> cb) {
    const QString q = query_.trimmed();
    if (q.size() < 2 || !RuTracker::isReady()) { cb({}, {}); return; }
    QString err;
    const QString base = RuTracker::validateUrl(RuTracker::url(), &err);
    if (base.isEmpty()) { cb({}, err.isEmpty() ? tx(S("trackers.invalid_url")) : err); return; }
    QPointer<QObject> guard(ctx);
    query(q, base, ctx, [q, base, guard, cb](const QList<SearchHit> &hits, const QString &error) {
        if (!error.isEmpty() || !hits.isEmpty()) { cb(hits, error); return; }
        // Ничего, а в запросе год: повтор без года, раздачи этого года — первыми (только они, если есть)
        const auto year = RuTracker::yearOf(q);
        const QString clean = RuTracker::withoutYear(q);
        if (!year || clean.size() < 2 || !guard) { cb(hits, error); return; }
        query(clean, base, guard, [year, cb](const QList<SearchHit> &more, const QString &error2) {
            if (!error2.isEmpty()) { cb({}, error2); return; }
            QList<SearchHit> same;
            for (const auto &h : more)
                if (h.title.contains(*year)) same << h;
            cb(same.isEmpty() ? more : same, {});
        });
    });
}

void downloadTorrent(const QString &topicId, QObject *ctx, std::function<void(QByteArray)> cb) {
    static const QRegularExpression digits(S("^\\d+$"));
    // почему не скачалось — в журнал (без cookie): начало ответа показывает страницу входа, капчу или Cloudflare
    auto head = [](const QByteArray &b) {
        QString t = QString::fromUtf8(b.left(160)).simplified();
        static const QRegularExpression tags(S("<[^>]*>"));
        return t.replace(tags, S(" ")).simplified().left(100);
    };
    if (!digits.match(topicId).hasMatch() || !RuTracker::isReady()) {
        PlayerLog::write(S("[trackers] torrent t=%1: RuTracker не настроен (вход не выполнен)").arg(topicId));
        cb({});
        return;
    }
    const QString base = RuTracker::validateUrl(RuTracker::url());
    if (base.isEmpty()) { PlayerLog::write(S("[trackers] torrent t=%1: неверный адрес RuTracker").arg(topicId)); cb({}); return; }
    QPointer<QObject> guard(ctx);
    auto direct = [base, topicId, guard, cb, head] {
        if (!guard) return;
        const QString c = RuTracker::normalizeCookie(RuTracker::cookie());
        if (c.isEmpty()) { PlayerLog::write(S("[trackers] torrent t=%1: нет cookie").arg(topicId)); cb({}); return; }
        get(base + S("/forum/dl.php?t=") + topicId, c, 20000,
            {{"Referer", (base + S("/forum/viewtopic.php?t=") + topicId).toUtf8()}}, guard,
            [cb, topicId, head](int code, const QByteArray &body, const QString &err) {
                const bool ok = code == 200 && body.startsWith('d'); // bencode начинается с 'd'
                if (!ok)
                    PlayerLog::write(S("[trackers] torrent t=%1 напрямую: HTTP %2, %3 байт%4, начало: %5")
                                         .arg(topicId).arg(code).arg(body.size())
                                         .arg(err.isEmpty() ? QString() : S(", ") + err).arg(head(body)));
                cb(ok ? body : QByteArray());
            });
    };
    if (!BrowserBridge::isAvailable()) { direct(); return; }
    BrowserBridge::instance().downloadTorrent(base, topicId, RuTracker::parseCookieMap(RuTracker::cookie()), ctx,
        [cb, direct, topicId, head](const QByteArray &b) {
            if (b.startsWith('d')) { cb(b); return; }
            PlayerLog::write(S("[trackers] torrent t=%1 через браузер: %2 байт, начало: %3 — пробую напрямую")
                                 .arg(topicId).arg(b.size()).arg(head(b)));
            direct();
        });
}

} // namespace suslik::app::rutracker

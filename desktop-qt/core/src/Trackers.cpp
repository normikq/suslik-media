// Перенос desktop/.../trackers: TrackerSettings.kt, LocalTrackers.kt (RuTrackerTracker), разбор cookie из
// BrowserDetector.kt и EdgeBrowserBridge.kt.
#include "suslik/core/Trackers.h"

#include "JsonOpt.h"
#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"

#include <QDateTime>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QUrl>

namespace suslik::core {

#define S QStringLiteral

namespace {
Store &store() { return Store::of(S("local_trackers")); }
QString get(const QString &k) { return store().get(k).value_or(QString()); }

QRegularExpression ci(const QString &p) {
    return QRegularExpression(p, QRegularExpression::CaseInsensitiveOption | QRegularExpression::UseUnicodePropertiesOption);
}
QString stripTags(QString s) {
    static const QRegularExpression tag(S("<[^>]+>"));
    return s.replace(tag, QString());
}
} // namespace

// ---- какие источники включены ----

const QList<TrackerSourceInfo> &TrackerSettings::sources() {
    // Тексты те же, что у Kotlin-версии; ключи строк названы по своему трекеру (у Kotlin они были перепутаны)
    static const QList<TrackerSourceInfo> list = {
        {S("rutor"), S("Rutor"), S("trackers.desc_rutor"), S("trackers.badge_rutor"), false},
        {S("nnmclub"), S("NNM-Club"), S("trackers.desc_nnmclub"), S("trackers.badge_nnmclub"), false},
        {S("tpb"), S("The Pirate Bay"), S("trackers.desc_tpb"), S("trackers.badge_tpb"), false},
        {S("nyaa"), S("Nyaa"), S("trackers.desc_nyaa"), S("trackers.badge_nyaa"), false},
        {S("rutracker"), S("RuTracker (ПК)"), S("trackers.desc_rutracker"), S("trackers.badge_rutracker"), true},
    };
    return list;
}

bool TrackerSettings::isEnabled(const QString &id) {
    if (id == S("rutracker")) return RuTracker::enabled();
    return get(S("tracker.%1.enabled").arg(id)) != S("false");
}

void TrackerSettings::setEnabled(const QString &id, bool on) {
    if (id == S("rutracker")) RuTracker::setEnabled(on);
    else store().put(S("tracker.%1.enabled").arg(id), on ? S("true") : S("false"));
}

void TrackerSettings::setAll(bool on) {
    for (const auto &s : sources()) setEnabled(s.id, on);
}

void TrackerSettings::setOnlyRussian() {
    setEnabled(S("rutor"), true);
    setEnabled(S("nnmclub"), true);
    setEnabled(S("rutracker"), true);
    setEnabled(S("tpb"), false);
    setEnabled(S("nyaa"), false);
}

bool TrackerSettings::isProviderEnabled(const QString &providerName) {
    const QString p = providerName.toLower();
    if (p.contains(S("rutor"))) return isEnabled(S("rutor"));
    if (p.contains(S("nnm"))) return isEnabled(S("nnmclub"));
    if (p.contains(S("tpb")) || p.contains(S("pirate"))) return isEnabled(S("tpb"));
    if (p.contains(S("nyaa"))) return isEnabled(S("nyaa"));
    if (p.contains(S("rutracker"))) return isEnabled(S("rutracker"));
    return true;
}

bool TrackerSettings::isSourceEnabledForHit(const QString &source) {
    const QString s = source.toLower();
    const bool rutor = s.contains(S("rutor")), nnm = s.contains(S("nnm")), tpb = s.contains(S("tpb")) || s.contains(S("pirate")),
               nyaa = s.contains(S("nyaa")), rt = s.contains(S("rutracker"));
    if (rutor || nnm || tpb || nyaa || rt)
        return (rutor && isEnabled(S("rutor"))) || (nnm && isEnabled(S("nnmclub"))) || (tpb && isEnabled(S("tpb"))) ||
               (nyaa && isEnabled(S("nyaa"))) || (rt && isEnabled(S("rutracker")));
    return true;
}

int TrackerSettings::enabledCount() {
    int n = 0;
    for (const auto &s : sources()) n += isEnabled(s.id) ? 1 : 0;
    return n;
}

// ---- RuTracker: настройки ----

QString RuTracker::url() {
    QString u = get(S("rutracker.url")).trimmed();
    while (u.endsWith(QLatin1Char('/'))) u.chop(1);
    return u.trimmed().isEmpty() ? defaultUrl() : u;
}
void RuTracker::setUrl(const QString &u) {
    QString v = u.trimmed();
    if (v.endsWith(QLatin1Char('/'))) v.chop(1);
    store().put(S("rutracker.url"), v);
}
bool RuTracker::enabled() { return get(S("rutracker.enabled")) == S("true"); }
void RuTracker::setEnabled(bool on) { store().put(S("rutracker.enabled"), on ? S("true") : S("false")); }
bool RuTracker::partyAllowed() { return get(S("rutracker.party")) != S("false"); }
void RuTracker::setPartyAllowed(bool on) { store().put(S("rutracker.party"), on ? S("true") : S("false")); }
QString RuTracker::cookie() { return get(S("rutracker.cookie")); }
void RuTracker::setCookie(const QString &c) { store().put(S("rutracker.cookie"), c.trimmed()); }
QString RuTracker::cookieSource() { return get(S("rutracker.cookie_source")); }
void RuTracker::setCookieSource(const QString &type) { store().put(S("rutracker.cookie_source"), type); }

// ---- RuTracker: разбор ----

QString RuTracker::validateUrl(const QString &urlStr, QString *error) {
    QString trimmed = urlStr.trimmed();
    if (trimmed.endsWith(QLatin1Char('/'))) trimmed.chop(1);
    const QUrl u(trimmed);
    if (u.scheme() != S("https")) {
        if (error) *error = tx(S("trackers.only_https"));
        return {};
    }
    const QString host = u.host().toLower();
    const bool ok = host == S("rutracker.org") || host.endsWith(S(".rutracker.org")) || host == S("rutracker.net") ||
                    host.endsWith(S(".rutracker.net")) || host == S("rutracker.nl") || host.endsWith(S(".rutracker.nl"));
    if (!ok) {
        if (error) *error = tx(S("trackers.allowed_mirrors"));
        return {};
    }
    return trimmed;
}

QString RuTracker::unescapeHtml(const QString &s) {
    if (!s.contains(QLatin1Char('&'))) return s;
    QString r = s;
    r.replace(S("&quot;"), S("\"")).replace(S("&amp;"), S("&")).replace(S("&lt;"), S("<")).replace(S("&gt;"), S(">"))
        .replace(S("&apos;"), S("'")).replace(S("&#039;"), S("'")).replace(S("&nbsp;"), S(" "));
    if (r.contains(S("&#"))) {
        static const QRegularExpression dec(S(R"(&#(\d+);)")), hex(S(R"(&#x([0-9a-fA-F]+);)"));
        for (const auto &[rx, base] : {std::pair{&dec, 10}, std::pair{&hex, 16}}) {
            QString out;
            qsizetype last = 0;
            auto it = rx->globalMatch(r);
            while (it.hasNext()) {
                const auto m = it.next();
                out += r.mid(last, m.capturedStart() - last);
                bool ok = false;
                const uint code = m.captured(1).toUInt(&ok, base);
                // Kotlin: toChar() — только 16 бит
                out += ok ? QString(QChar(char16_t(code & 0xFFFF))) : m.captured(0);
                last = m.capturedEnd();
            }
            out += r.mid(last);
            r = out;
        }
    }
    return r;
}

QString RuTracker::normalizeCookie(const QString &raw) {
    QString s = raw.trimmed();
    if (s.startsWith(S("Cookie:"), Qt::CaseInsensitive)) s = s.mid(7).trimmed();
    if (!s.contains(S("bb_ssl=")) && s.contains(S("bb_session="))) s += S("; bb_ssl=1");
    return s;
}

CookieList RuTracker::parseCookieMap(const QString &raw) {
    CookieList out;
    auto put = [&out](const QString &k, const QString &v) {
        for (auto &p : out)
            if (p.first == k) { p.second = v; return; }
        out.append({k, v});
    };
    for (const auto &part : raw.split(QLatin1Char(';'))) {
        const QString p = part.trimmed();
        const qsizetype eq = p.indexOf(QLatin1Char('='));
        if (eq > 0) {
            const QString k = p.left(eq).trimmed(), v = p.mid(eq + 1).trimmed();
            if (!k.isEmpty() && !v.isEmpty()) put(k, v);
        }
    }
    bool ssl = false;
    for (const auto &p : out) ssl = ssl || p.first == S("bb_ssl");
    if (!ssl) out.append({S("bb_ssl"), S("1")});
    return out;
}

QString RuTracker::cookieHeader(const CookieList &c) {
    QStringList parts;
    for (const auto &p : c) parts << p.first + QLatin1Char('=') + p.second;
    return parts.join(S("; "));
}

QString RuTracker::cookieDomain(const QString &baseUrl) {
    QString host = QUrl(baseUrl).host();
    if (host.isEmpty()) host = S("rutracker.org");
    if (host.startsWith(S("www."))) host = host.mid(4);
    const auto parts = host.split(QLatin1Char('.'));
    return parts.size() >= 2 ? parts[parts.size() - 2] + QLatin1Char('.') + parts.last() : host;
}

QString RuTracker::kindOf(const QString &forum, const QString &title) {
    static const QRegularExpression sEp(S(R"(\bs\d{1,2}\b)"));
    const QString f = forum.toLower(), t = title.toLower();
    if (f.contains(S("мульт")) || f.contains(S("аниме")) || t.contains(S("мультсериал")) || t.contains(S("мультфильм")) || t.contains(S("аниме")))
        return S("cartoon");
    if (f.contains(S("сериал")) || t.contains(S("сезон")) || t.contains(S("сериал")) || sEp.match(t).hasMatch()) return S("series");
    return S("movie");
}

namespace {
SearchHit rtHit(const QString &topicId, const QString &title, qint64 size, int seeds, int leeches, const QString &kind,
                const QString &date, const std::optional<QString> &magnet) {
    SearchHit h = SearchHit::make(S("rt_") + topicId, title, size, seeds, leeches, true, kind, S("RuTracker"), date);
    h.magnet = magnet;
    h.topicId = topicId;
    h.localTrackerId = S("rutracker");
    return h;
}

std::optional<QString> buildMagnet(const QString &row, const QString &title) {
    static const QRegularExpression h1(S(R"rx(data-topic_hash="([0-9a-fA-F]{40})")rx")), h2(S(R"(btih:([0-9a-fA-F]{40}))"));
    auto m = h1.match(row);
    if (!m.hasMatch()) m = h2.match(row);
    if (!m.hasMatch()) return std::nullopt;
    const QString dn = QString::fromLatin1(QUrl::toPercentEncoding(title.left(120), "*", "~")).replace(S("%20"), S("+"));
    return S("magnet:?xt=urn:btih:") + m.captured(1) + S("&dn=") + dn;
}
} // namespace

QList<SearchHit> RuTracker::parseHtml(const QString &html) {
    static const auto row = ci(S(R"rx(<tr\b([^>]*)>([\s\S]*?)</tr>)rx"));
    static const auto link1 = ci(S(R"rx(<a\b([^>]*\btLink\b[^>]*)>([\s\S]*?)</a>)rx"));
    static const auto link2 = ci(S(R"rx(<a\b([^>]*href="[^"]*viewtopic\.php\?t=\d+[^"]*"[^>]*)>([\s\S]*?)</a>)rx"));
    static const QRegularExpression topic(S(R"(viewtopic\.php\?t=(\d+))"));
    static const auto forum1 = ci(S(R"rx(<a\b[^>]*\bclass="gen f"[^>]*>([\s\S]*?)</a>)rx"));
    static const auto forum2 = ci(S(R"rx(<a\b[^>]*href="[^"]*tracker\.php\?f=\d+[^"]*"[^>]*>([\s\S]*?)</a>)rx"));
    static const auto magnetRx = ci(S(R"rx(href="(magnet:\?xt=urn:btih:[^"]+)")rx"));
    static const auto torSize = ci(S(R"rx(class="[^"]*\btor-size\b[^"]*"[^>]*data-ts_text="(\d+)")rx"));
    static const QRegularExpression anySize(S(R"rx(data-ts_text="(\d+)")rx"));
    static const auto seedRx = ci(S(R"rx(class="[^"]*\bseedmed\b[^"]*"[^>]*>([\s\S]*?)</td>)rx"));
    static const auto leechRx = ci(S(R"rx(class="[^"]*\bleechmed\b[^"]*"[^>]*>([\s\S]*?)</td>)rx"));
    static const QRegularExpression stampRx(S(R"rx(data-ts_text="(\d{9,11})")rx"));
    static const QRegularExpression nonDigit(S(R"(\D)"));

    QList<SearchHit> out;
    auto rows = row.globalMatch(html);
    while (rows.hasNext()) {
        const auto m = rows.next();
        const QString attrs = m.captured(1), r = m.captured(2);
        if (!attrs.contains(S("tCenter"), Qt::CaseInsensitive) && !attrs.contains(S("trs-tr-"), Qt::CaseInsensitive) &&
            !attrs.contains(S("tor_"), Qt::CaseInsensitive))
            continue;
        auto lm = link1.match(r);
        if (!lm.hasMatch()) lm = link2.match(r);
        if (!lm.hasMatch()) continue;
        const auto tm = topic.match(lm.captured(1));
        if (!tm.hasMatch()) continue;
        const QString topicId = tm.captured(1);
        const QString title = unescapeHtml(stripTags(lm.captured(2)).trimmed());
        if (title.trimmed().isEmpty()) continue;

        auto fm = forum1.match(r);
        if (!fm.hasMatch()) fm = forum2.match(r);
        const QString forum = unescapeHtml(fm.hasMatch() ? stripTags(fm.captured(1)).trimmed() : QString());
        if (ReleaseParser::isNonVideo(title, forum)) continue; // игры, программы, музыка, книги
        const QString kind = kindOf(forum, title);

        std::optional<QString> magnet;
        if (const auto mm = magnetRx.match(r); mm.hasMatch()) magnet = mm.captured(1);
        else magnet = buildMagnet(r, title);

        qint64 size = 0;
        if (const auto sm = torSize.match(r); sm.hasMatch()) size = sm.captured(1).toLongLong();
        else if (const auto am = anySize.match(r); am.hasMatch()) size = am.captured(1).toLongLong();

        const auto seedM = seedRx.match(r), leechM = leechRx.match(r);
        const int seeds = seedM.hasMatch() ? QString(seedM.captured(1)).remove(nonDigit).toInt() : 0;
        const int leeches = leechM.hasMatch() ? QString(leechM.captured(1)).remove(nonDigit).toInt() : 0;

        qint64 stamp = 0;
        auto st = stampRx.globalMatch(r);
        while (st.hasNext()) stamp = st.next().captured(1).toLongLong();
        const QString date = stamp > 0 ? QDateTime::fromSecsSinceEpoch(stamp).toString(S("dd.MM.yyyy")) : QString();

        out << rtHit(topicId, title, size, seeds, leeches, kind, date, magnet);
    }
    return out;
}

QList<SearchHit> RuTracker::parseJsonResults(const QString &jsonStr) {
    static const QRegularExpression digits(S(R"(^\d+$)"));
    QList<SearchHit> out;
    const auto doc = QJsonDocument::fromJson(jsonStr.toUtf8());
    for (const auto &v : doc.array()) {
        const QJsonObject o = v.toObject();
        const QString topicId = json::str(o, S("topicId")).trimmed();
        if (topicId.isEmpty() || !digits.match(topicId).hasMatch()) continue;
        const QString title = json::str(o, S("title")).trimmed();
        if (title.isEmpty()) continue;
        const QString forum = json::str(o, S("forum")).trimmed();
        if (ReleaseParser::isNonVideo(title, forum)) continue;
        const QString magnet = json::str(o, S("magnet"));
        out << rtHit(topicId, title, json::optLong(o, S("sizeBytes")), int(json::optLong(o, S("seeds"))), int(json::optLong(o, S("leeches"))),
                     kindOf(forum, title), QString(), magnet.trimmed().isEmpty() ? std::nullopt : std::optional<QString>(magnet));
    }
    return out;
}

std::optional<QString> RuTracker::formatTrackerCookies(const QJsonArray &cookies, const QString &baseUrl) {
    QString domain = QUrl(baseUrl).host();
    if (domain.isEmpty()) domain = S("rutracker.org");
    if (domain.startsWith(S("www."))) domain = domain.mid(4);
    const QString pattern = domain.contains(S("rutracker")) ? S("rutracker") : domain;
    CookieList map;
    for (const auto &v : cookies) {
        const QJsonObject c = v.toObject();
        const QString d = json::str(c, S("domain")), name = json::str(c, S("name")), value = json::str(c, S("value"));
        if (d.contains(pattern, Qt::CaseInsensitive) && !name.trimmed().isEmpty() && !value.trimmed().isEmpty()) {
            bool replaced = false;
            for (auto &p : map)
                if (p.first == name) { p.second = value; replaced = true; }
            if (!replaced) map.append({name, value});
        }
    }
    bool session = false, ssl = false;
    for (const auto &p : map) { session = session || p.first == S("bb_session"); ssl = ssl || p.first == S("bb_ssl"); }
    if (!session) return std::nullopt;
    if (!ssl) map.append({S("bb_ssl"), S("1")});
    return cookieHeader(map);
}

std::optional<QString> RuTracker::extractFirefoxCookies(const QByteArray &bytes, const QString &domainKeyword, QString *error) {
    const QString text = QString::fromUtf8(bytes);
    if (!text.contains(domainKeyword, Qt::CaseInsensitive)) {
        if (error) *error = S("В этом профиле Firefox не найдено кук для %1").arg(domainKeyword);
        return std::nullopt;
    }
    CookieList cookies;
    static const QRegularExpression session(S(R"(bb_session[\x00-\x1F]*([0-9a-zA-Z\-_]{15,80}))"));
    static const QRegularExpression guid(S(R"(bb_guid[\x00-\x1F]*([0-9a-zA-Z]{6,30}))"));
    static const QRegularExpression clearance(S(R"(cf_clearance[\x00-\x1F]*([0-9a-zA-Z._\-]{80,800}))"));
    if (const auto m = session.match(text); m.hasMatch()) cookies.append({S("bb_session"), m.captured(1)});
    if (const auto m = guid.match(text); m.hasMatch()) cookies.append({S("bb_guid"), m.captured(1)});
    if (const auto m = clearance.match(text); m.hasMatch()) cookies.append({S("cf_clearance"), m.captured(1)});
    cookies.append({S("bb_ssl"), S("1")});
    if (cookies.first().first != S("bb_session")) {
        if (error) *error = S("В профиле найден домен %1, но bb_session отсутствует").arg(domainKeyword);
        return std::nullopt;
    }
    return cookieHeader(cookies);
}

std::optional<QString> RuTracker::yearOf(const QString &q) {
    static const QRegularExpression year(S(R"(\b(19\d{2}|20\d{2})\b)"));
    const auto m = year.match(q);
    return m.hasMatch() ? std::optional<QString>(m.captured(1)) : std::nullopt;
}

QString RuTracker::withoutYear(const QString &q) {
    static const QRegularExpression year(S(R"(\b(19\d{2}|20\d{2})\b)")), ws(S(R"(\s+)"));
    return QString(q).replace(year, QString()).replace(ws, S(" ")).trimmed();
}

// ---- windows-1251 ----

namespace {
// 0x80–0xBF; 0xC0–0xFF — подряд А…я (U+0410…U+044F). 0x98 в кодировке нет.
const char16_t CP1251_HIGH[64] = {
    0x0402, 0x0403, 0x201A, 0x0453, 0x201E, 0x2026, 0x2020, 0x2021, 0x20AC, 0x2030, 0x0409, 0x2039, 0x040A, 0x040C, 0x040B, 0x040F,
    0x0452, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0xFFFD, 0x2122, 0x0459, 0x203A, 0x045A, 0x045C, 0x045B, 0x045F,
    0x00A0, 0x040E, 0x045E, 0x0408, 0x00A4, 0x0490, 0x00A6, 0x00A7, 0x0401, 0x00A9, 0x0404, 0x00AB, 0x00AC, 0x00AD, 0x00AE, 0x0407,
    0x00B0, 0x00B1, 0x0406, 0x0456, 0x0491, 0x00B5, 0x00B6, 0x00B7, 0x0451, 0x2116, 0x0454, 0x00BB, 0x0458, 0x0405, 0x0455, 0x0457};
} // namespace

QString Cp1251::decode(const QByteArray &bytes) {
    QString out;
    out.reserve(bytes.size());
    for (const char ch : bytes) {
        const auto b = uchar(ch);
        if (b < 0x80) out += QChar(b);
        else if (b < 0xC0) out += QChar(CP1251_HIGH[b - 0x80]);
        else out += QChar(char16_t(0x0410 + (b - 0xC0)));
    }
    return out;
}

QByteArray Cp1251::encode(const QString &s) {
    QByteArray out;
    out.reserve(s.size());
    for (const QChar c : s) {
        const char16_t u = c.unicode();
        if (u < 0x80) { out += char(u); continue; }
        if (u >= 0x0410 && u <= 0x044F) { out += char(0xC0 + (u - 0x0410)); continue; }
        char b = '?';
        for (int i = 0; i < 64; ++i)
            if (CP1251_HIGH[i] == u && u != 0xFFFD) { b = char(0x80 + i); break; }
        out += b;
    }
    return out;
}

QByteArray Cp1251::urlEncode(const QString &s) {
    return encode(s).toPercentEncoding("*", "~").replace("%20", "+");
}

} // namespace suslik::core

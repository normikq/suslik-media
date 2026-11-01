#include "suslik/core/MarkersDetector.h"
#include "suslik/core/UrlCoding.h"
#include "JsonOpt.h"

#include <QRegularExpression>
#include <algorithm>
#include <climits>

namespace suslik::core::MarkersDetector {

namespace {
using RE = QRegularExpression;
RE ci(const QString &p) { return RE(p, RE::CaseInsensitiveOption); }

// Кириллица тоже («Заставка», «ТИТРЫ»); \b — только латиница, он лишь вокруг «op» / «ed»
const RE INTRO = ci(QStringLiteral(R"((intro|opening|заставк|опенинг|вступлен|начальн|main\s*titles?|^op\b|\bop\s?\d*$))"));
const RE CREDITS = ci(QStringLiteral(R"((credits|outro|ending|эндинг|титр|конечн|^ed\b|\bed\s?\d*$))"));
constexpr qint64 INTRO_SANE_MS = 5 * 60000;

const RE RU_LANG = ci(QStringLiteral(R"(\A(?:ru|rus|russian|русский)\z)"));
const RE OTHER_LANG = ci(QStringLiteral(R"(\A(?:[a-z]{2,3}|english|japanese|korean|chinese|german|french|spanish|italian)\z)"));
const RE RU_WORDS = ci(QStringLiteral(R"((рус|russian|дубляж|дублир|закадр|многоголос|двухголос|одноголос|авторск|\b(mvo|dvo|avo)\b))"));
const RE FOREIGN_WORDS = ci(QStringLiteral(R"((оригинал|original|english|англ|japanese|япон|korean|корей|\b(eng|jpn|jap|kor)\b))"));
const RE FORCED_WORDS = ci(QStringLiteral(R"((forced|форсир|надпис|\bsigns\b|только надписи))"));
const RE FULL_WORDS = ci(QStringLiteral(R"((полн|full))"));
const RE CYRILLIC(QStringLiteral(R"(\p{Cyrillic})"));
const RE TITLE_SPLIT(QStringLiteral(R"(\s*[/|]\s*|\s+[-–—]\s+)"));

bool has(const RE &re, const QString &s) { return re.match(s).hasMatch(); }
} // namespace

bool isIntroTitle(const QString &title) { return has(INTRO, title); }
bool isCreditsTitle(const QString &title) { return has(CREDITS, title); }

MediaMarkers fromChapters(const QList<Chapter> &chapters, qint64 lengthMs) {
    QList<Chapter> list = chapters;
    std::stable_sort(list.begin(), list.end(), [](const Chapter &a, const Chapter &b) { return a.timeMs < b.timeMs; });
    const qint64 half = lengthMs > 0 ? lengthMs / 2 : LLONG_MAX;
    MediaMarkers out;
    for (qsizetype i = 0; i < list.size(); ++i) {
        const Chapter &c = list[i];
        if (c.timeMs >= half || !isIntroTitle(c.title) || isCreditsTitle(c.title)) continue;
        const std::optional<qint64> next = i + 1 < list.size() ? std::optional<qint64>(list[i + 1].timeMs) : std::nullopt;
        qint64 end = next && *next > c.timeMs && *next - c.timeMs <= INTRO_SANE_MS ? *next : c.timeMs + INTRO_MAX_MS;
        if (lengthMs > 0) end = std::min(end, lengthMs);
        if (end > c.timeMs) { out.intro = TimeRange{c.timeMs, end}; break; }
    }
    if (lengthMs > 0) {
        // «Credits», потом «Preview»: титры всё равно идут до конца — после них только следующая серия
        for (qsizetype i = list.size() - 1; i >= 0; --i) {
            const Chapter &c = list[i];
            if (c.timeMs >= half && c.timeMs < lengthMs && isCreditsTitle(c.title) && !isIntroTitle(c.title)) {
                out.credits = TimeRange{c.timeMs, lengthMs};
                break;
            }
        }
    }
    return out;
}

MediaMarkers merge(const MediaMarkers &file, const std::optional<MediaMarkers> &server, qint64 lengthMs) {
    auto fit = [lengthMs](const std::optional<TimeRange> &r, bool toEnd) -> std::optional<TimeRange> {
        if (!r) return std::nullopt;
        if (lengthMs <= 0) return r->endMs > r->startMs ? r : std::nullopt;
        if (r->startMs >= lengthMs) return std::nullopt;
        const TimeRange out{std::max<qint64>(r->startMs, 0), toEnd ? lengthMs : std::min(r->endMs, lengthMs)};
        return out.endMs > out.startMs ? std::optional<TimeRange>(out) : std::nullopt;
    };
    MediaMarkers m;
    m.intro = file.intro ? file.intro : fit(server ? server->intro : std::nullopt, false);
    m.credits = file.credits ? file.credits : fit(server ? server->credits : std::nullopt, true);
    return m;
}

std::optional<qint64> nextOfferAt(const MediaMarkers &m, qint64 lengthMs) {
    if (!m.credits) return std::nullopt;
    if (lengthMs <= 0 || lengthMs - m.credits->startMs < CREDITS_MIN_MS) return std::nullopt;
    return m.credits->startMs;
}

std::optional<TimeRange> introAt(const MediaMarkers &m, qint64 timeMs) {
    if (m.intro && m.intro->contains(timeMs) && m.intro->endMs - timeMs > 2000) return m.intro;
    return std::nullopt;
}

std::optional<QString> serverPath(const std::optional<QString> &title, const std::optional<QString> &alt, std::optional<int> season,
                                  std::optional<int> episode, qint64 durationMs) {
    QStringList titles;
    for (const auto &opt : {title, alt}) {
        if (!opt) continue;
        const QString t = opt->trimmed();
        if (t.size() < 2) continue;
        if (!titles.contains(t)) titles << t;
        for (const QString &p : t.split(TITLE_SPLIT)) {
            const QString pt = p.trimmed();
            if (pt.size() >= 2 && !titles.contains(pt)) titles << pt;
        }
    }
    if (titles.isEmpty()) return std::nullopt;
    QString out = QStringLiteral("/api/markers?title=") + formEncode(titles[0]);
    if (titles.size() > 1) out += QStringLiteral("&alt=") + formEncode(titles[1]);
    if (episode && *episode > 0) {
        out += QStringLiteral("&season=%1").arg(season && *season > 0 ? *season : 1);
        out += QStringLiteral("&episode=%1").arg(*episode);
    }
    if (durationMs > 0) out += QStringLiteral("&duration=%1").arg(durationMs / 1000);
    return out;
}

std::optional<QString> filmTitle(const QString &fileName, const std::optional<QString> &parsed) {
    const QString name = parsed && !parsed->trimmed().isEmpty() ? cleanCollectionFilmTitle(*parsed) : cleanCollectionFilmTitle(fileName);
    if (name.size() < 2 || std::all_of(name.begin(), name.end(), [](QChar c) { return c.isDigit(); })) return std::nullopt;
    if (const auto y = extractYear(fileName)) return name.contains(QString::number(*y)) ? name : QStringLiteral("%1 (%2)").arg(name).arg(*y);
    return name;
}

std::optional<MediaMarkers> parseServer(const QJsonObject *answer) {
    if (!answer) return std::nullopt;
    auto range = [answer](const char *key) -> std::optional<TimeRange> {
        const QJsonValue v = answer->value(QLatin1String(key));
        if (!v.isObject()) return std::nullopt;
        const QJsonObject o = v.toObject();
        const qint64 s = json::optLong(o, QStringLiteral("startMs"), -1), e = json::optLong(o, QStringLiteral("endMs"), -1);
        return s >= 0 && e > s ? std::optional<TimeRange>(TimeRange{s, e}) : std::nullopt;
    };
    return MediaMarkers{range("intro"), range("credits")};
}

std::optional<bool> isRussianAudio(const Track &t) {
    const QString lang = t.lang.value_or(QString()).trimmed();
    if (!lang.isEmpty() && has(RU_LANG, lang)) return true;
    const QString title = t.title.value_or(QString());
    if (has(FOREIGN_WORDS, title)) return false;
    if (has(RU_WORDS, title)) return true;
    if (!lang.isEmpty() && lang.compare(QLatin1String("und"), Qt::CaseInsensitive) != 0 && has(OTHER_LANG, lang)) return false;
    // Студия кириллицей («Кубик в Кубе», «Пифагор») — русская озвучка
    if (has(CYRILLIC, title)) return true;
    return std::nullopt;
}

bool isForced(const Track &t) { return t.forced || has(FORCED_WORDS, t.title.value_or(QString())); }

bool isRussianSub(const Track &t) {
    const QString lang = t.lang.value_or(QString()).trimmed();
    if (!lang.isEmpty() && lang.compare(QLatin1String("und"), Qt::CaseInsensitive) != 0) return has(RU_LANG, lang);
    const QString title = t.title.value_or(QString());
    return has(RU_WORDS, title) || has(CYRILLIC, title);
}

SubChoice pickSubtitle(const std::optional<Track> &audio, const QList<Track> &subs) {
    if (!audio) return SubChoice::keep();
    const auto ru = isRussianAudio(*audio);
    if (!ru) return SubChoice::keep();
    QList<Track> russian;
    for (const Track &s : subs)
        if (s.id >= 0 && isRussianSub(s)) russian << s;
    if (*ru) {
        for (const Track &s : russian)
            if (isForced(s)) return SubChoice::pick(s.id);
        return SubChoice::off();
    }
    QList<Track> full;
    for (const Track &s : russian)
        if (!isForced(s)) full << s;
    for (const Track &s : full)
        if (has(FULL_WORDS, s.title.value_or(QString()))) return SubChoice::pick(s.id);
    return full.isEmpty() ? SubChoice::keep() : SubChoice::pick(full.first().id);
}

} // namespace suslik::core::MarkersDetector

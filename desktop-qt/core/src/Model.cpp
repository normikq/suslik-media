#include "suslik/core/Model.h"
#include "JsonOpt.h"

#include <QDate>
#include <QJsonArray>
#include <QJsonValue>
#include <QRegularExpression>
#include <QSet>
#include <algorithm>
#include <climits>
#include <map>

// Регулярные выражения — те же, что в Model.kt. PCRE2 без UCP ведёт себя как Java здесь: \d, \s, \b — только ASCII,
// а [\p{L}\p{N}] и регистр без учёта (в UTF-режиме) — по всему Юникоду, как Kotlin с IGNORE_CASE (UNICODE_CASE).

namespace suslik::core {

using namespace json;

namespace {

using RE = QRegularExpression;

RE rx(const QString &pattern, bool ic = false) {
    RE re(pattern, ic ? RE::CaseInsensitiveOption : RE::NoPatternOption);
    re.optimize();
    return re;
}

bool contains(const RE &re, const QString &s) { return re.match(s).hasMatch(); }

QString replaced(QString s, const RE &re, const QString &with) { return s.replace(re, with); }

bool isBlank(const QString &s) { return s.trimmed().isEmpty(); }
QString ifBlank(const QString &s, const QString &other) { return isBlank(s) ? other : s; }

QString substringAfterLast(const QString &s, QChar c) {
    const auto i = s.lastIndexOf(c);
    return i < 0 ? s : s.mid(i + 1);
}
QString substringBeforeLast(const QString &s, QChar c, const QString &missing) {
    const auto i = s.lastIndexOf(c);
    return i < 0 ? missing : s.left(i);
}
QString substringBefore(const QString &s, QChar c) {
    const auto i = s.indexOf(c);
    return i < 0 ? s : s.left(i);
}

QString basename(const QString &path) { return substringAfterLast(QString(path).replace(QLatin1Char('\\'), QLatin1Char('/')), QLatin1Char('/')); }

QString pad2(int n) { return QString::number(n).rightJustified(2, QLatin1Char('0')); }

// ---- те же правила, что на сайте (buildPlaylist / parseEpisode / escapeTitle) ----

const QString NB = QStringLiteral(R"((?<![\p{L}\p{N}]))");
const QString NA = QStringLiteral(R"((?![\p{L}\p{N}]))");

const RE VIDEO_EXT = rx(QStringLiteral(R"(\.(mkv|mp4|avi|mov|webm|m4v|m2ts|ts|iso)$)"), true);
const RE SAMPLE = rx(QStringLiteral(R"((^|[/._ -])sample([/._ -]|$))"), true);
const RE NAT_TOKEN = rx(QStringLiteral(R"(\d+|\D+)"));
const RE DIR_SEASON = rx(QStringLiteral(R"((?:^|/)(?:season|сезон)[ ._-]?(\d{1,2})(?:/|$))"), true);
const RE DIR_S = rx(QStringLiteral(R"((?:^|/)S(\d{1,2})(?:/|$))"), true);
const RE ANY_SEASON = rx(QStringLiteral(R"((?:season|сезон)[ ._-]?(\d{1,2}))"), true);
const RE SXXEYY = rx(QStringLiteral(R"(S(\d{1,2})[ ._-]?E(\d{1,3}))"), true);
const RE NXNN = rx(QStringLiteral(R"((?:^|[^\d])(\d{1,2})x(\d{2,3})(?!\d))"), true);
const RE EP_WORD = rx(QStringLiteral(R"((?:^|[^a-z0-9а-я])(?:e|ep|episode|серия)[ ._-]?(\d{1,3})(?!\d))"), true);
const RE LEAD_NUM = rx(QStringLiteral(R"(^\s*(\d{1,3})(?=[ ._-]))"));
const RE NAME_SEASON_RANGE = rx(QStringLiteral(
    R"((?:season|сезон)[a-zа-яё]*[ ._:]*\d{1,2}[ ._]*[-–][ ._]*\d|\d[ ._]*[-–][ ._]*\d{1,2}[ ._]*(?:-?[a-zа-яё]{1,2}[ ._]*)?(?:season|сезон)|(?:^|[^a-zа-яё0-9])s\d{1,2}[ ._]*[-–][ ._]*s?\d)"), true);
const RE NAME_SEASON = rx(QStringLiteral(
    R"((?:^|[^a-zа-яё0-9])s(\d{1,2})(?=e\d|[^a-zа-яё0-9]|$)|(?:season|сезон)[ ._:]*(\d{1,2})(?!\d)|(?:^|\D)(\d{1,2})[ ._]*(?:-?(?:й|ый|ий|st|nd|rd|th))?[ ._]*(?:season|сезон)(?![a-zа-яё0-9]))"), true);
const RE DOTS = rx(QStringLiteral("[._]+"));
const RE BRACKETS = rx(QStringLiteral(R"([\[\](){}])"));
const RE SPACES = rx(QStringLiteral(R"(\s+)"));

const QString COLLECTION_STRONG_P = NB + QStringLiteral(
    R"((?:трилоги[яи]|дилоги[яи]|тетралоги[яи]|квадр[ио]логи[яи]|пенталоги[яи]|гексалоги[яи]|франшиз[аеы]|киносаг[аеиы]|trilogy|duology|dilogy|tetralogy|quadrilogy|franchise|\d{1,2}\s*[-–—]?\s*(?:в|in)\s*1|все\s+части|все\s+фильмы|\d{1,2}\s+фильм(?:а|ов)))") + NA;
const QString COLLECTION_WEAK_P = NB + QStringLiteral(R"((?:коллекци[яи]|сборник|антологи[яи]|саг[аеиы]|boxset|box[- ]set|collection|anthology|saga))") + NA;
const RE COLLECTION_STRONG = rx(COLLECTION_STRONG_P, true);
const RE COLLECTION_WEAK = rx(COLLECTION_WEAK_P, true);
const RE COLLECTION_WORDS = rx(COLLECTION_STRONG_P + QLatin1Char('|') + COLLECTION_WEAK_P, true);

const RE KIND_TRILOGY = rx(NB + QStringLiteral(R"((?:трилоги[яи]|trilogy))") + NA, true);
const RE KIND_DILOGY = rx(NB + QStringLiteral(R"((?:дилоги[яи]|duology|dilogy))") + NA, true);
const RE KIND_TETRALOGY = rx(NB + QStringLiteral(R"((?:квадр[ио]логи[яи]|тетралоги[яи]|quadrilogy|tetralogy))") + NA, true);
const RE KIND_COMPILATION = rx(NB + QStringLiteral("сборник") + NA, true);
const RE KIND_ANTHOLOGY = rx(NB + QStringLiteral(R"((?:антологи[яи]|anthology))") + NA, true);
const RE KIND_COLLECTION = rx(NB + QStringLiteral(R"((?:коллекци[яи]|collection|boxset|box[- ]set))") + NA, true);

const RE TRACK_PREFIX = rx(QStringLiteral(
    R"(^\s*(?:\[[^\]]*\]\s*|\([^\)]*\)\s*|(?:фильм|серия|часть|part|movie|vol|cd|диск)?\s*\d{1,3}[\s._-]*)+)"), true);
const RE FILM_COUNT_NOISE = rx(NB + QStringLiteral(R"(\d+\s*(?:фильм\p{L}*|сери\p{L}*|част\p{L}*))") + NA, true);

const RE NUM_NOISE = rx(QStringLiteral(
    R"((?:2160|1080|720|480|576)[pi]|[xh][ .]?26[45]|\d+(?:[.,]\d+)?\s?(?:fps|kbps|mbps|bit|gb|mb)|(?<!\d)(?:19|20)\d{2}(?!\d)|(?<!\d)[1-9]\.[01](?!\d))"), true);
const RE NUM = rx(QStringLiteral(R"((?<!\d)\d{1,3}(?!\d))"));
const RE SERIES_HINT = rx(QStringLiteral(
    R"((?<![\p{L}\p{N}])(?:сезон\p{L}*|season\p{L}*|серии|серия|series|episodes?|эпизод\p{L}*|s\d{1,2}|ova|ona)(?![\p{L}\p{N}]))"), true);
const RE EXTRAS = rx(QStringLiteral(
    R"((?:^|/)(?:extras?|bonus|featurettes?|behind[ ._-]?the[ ._-]?scenes|trailers?|доп[^/]*|бонус[^/]*|допы)(?:/|$))"), true);
const RE BLURAY_DIR = rx(QStringLiteral(R"((?:^|/)(?:BDMV|STREAM|VIDEO_TS)(?:/|$))"), true);

const RE EXT = rx(QStringLiteral(R"(\.[a-z0-9]{2,5}$)"), true);
const RE TAGS = rx(QStringLiteral(
    R"(\b(2160p|1080p|720p|480p|4k|8k|web[- ]?dl|web[- ]?rip|bluray|blu[- ]?ray|bdrip|bdremux|remux|hdr10?\+?|dv|dovi|hevc|h\.?265|x265|h\.?264|x264|aac|dts|ddp?5?\.?1|proper|repack|extended|limited|internal|amzn|nf|dsnp|hmax|yts|rarbg)\b.*$)"), true);
const RE YEAR_TAIL = rx(QStringLiteral(R"(\b(19|20)\d{2}\b.*$)"));

const RE SEASON_NOISE = rx(QStringLiteral(
    R"((?<![\p{L}\p{N}])(?:\d{1,2}(?:\s*[-–]\s*\d{1,2})?\s*(?:сезон\p{L}*|season\p{L}*)|(?:сезон\p{L}*|season\p{L}*|серии|серия|series|episodes?)\s*\d{1,3}(?:\s*[-–]\s*\d{1,3})?|s\d{1,2}(?:\s*[-–]?\s*[se]\d{1,3})?|complete|полный|полностью|(?:сезон\p{L}*|season\p{L}*|серии|episodes))(?![\p{L}\p{N}]))"), true);
const RE NON_WORD = rx(QStringLiteral(R"([^\p{L}\p{N}]+)"));

const RE YEAR_IN_NAME = rx(QStringLiteral(R"(\b(19\d{2}|20\d{2})\b)"));
const RE YEAR_BRACKETED = rx(QStringLiteral(R"([(\[{](19\d{2}|20\d{2})[)\]}])"));
const RE SEASON_RANGE_REGEX = rx(QStringLiteral(
    R"((?:season|сезон)[s\p{L}]*[\s._-]*(\d{1,2})[\s._-]*[-–][\s._-]*(\d{1,2})|(\d{1,2})[\s._-]*[-–][\s._-]*(\d{1,2})[\s._-]*(?:season|сезон)[s\p{L}]*)"), true);
const RE PART_EXPLICIT = rx(NB + QStringLiteral(R"((?:часть|ч\.|фильм|film|part|pt\.?|глава|chapter)\s*(\d{1,2}|[ivx]{1,4}))") + NA, true);
const RE PART_ORDINAL = rx(NB + QStringLiteral(R"((\d{1,2})\s*[-–]?\s*(?:я|ая|ья|ей|й|ый|ой)?\s*(?:часть|фильм))") + NA, true);

const RE SUB_EXT = rx(QStringLiteral(R"(\.(srt|ass|ssa|vtt|sub)$)"), true);
const RE AUDIO_EXT = rx(QStringLiteral(R"(\.(mka|ac3|eac3|dts|aac|flac|mp3|m4a|opus|thd|wav)$)"), true);

constexpr int MAX_FILM_QUERIES = 6;

int latestYear() { return QDate::currentDate().year() + 2; }

/// Естественный порядок: «Episode 2» раньше «Episode 10» (как localeCompare с numeric: true).
int naturalCompare(const QString &a, const QString &b) {
    auto tokens = [](const QString &s) {
        QStringList out;
        auto it = NAT_TOKEN.globalMatch(s.toLower());
        while (it.hasNext()) out << it.next().captured(0);
        return out;
    };
    const QStringList ra = tokens(a), rb = tokens(b);
    for (qsizetype i = 0; i < std::min(ra.size(), rb.size()); ++i) {
        const QString &x = ra[i], &y = rb[i];
        int c;
        if (x[0].isDigit() && y[0].isDigit()) {
            QString nx = x, ny = y;
            while (nx.startsWith(QLatin1Char('0'))) nx.remove(0, 1);
            while (ny.startsWith(QLatin1Char('0'))) ny.remove(0, 1);
            c = nx.size() != ny.size() ? int(nx.size() - ny.size()) : nx.compare(ny);
        } else {
            c = x.compare(y);
        }
        if (c != 0) return c;
    }
    return int(ra.size() - rb.size());
}

QList<MFile> sortedByName(QList<MFile> files) {
    std::stable_sort(files.begin(), files.end(), [](const MFile &a, const MFile &b) { return naturalCompare(basename(a.path), basename(b.path)) < 0; });
    return files;
}

std::optional<int> seasonOf(const QString &path) {
    const QString p = QString(path).replace(QLatin1Char('\\'), QLatin1Char('/'));
    auto m = DIR_SEASON.match(p);
    if (!m.hasMatch()) m = DIR_S.match(p);
    if (!m.hasMatch()) return std::nullopt;
    return m.captured(1).toInt();
}

std::optional<std::pair<int, int>> parseEpisode(const QString &path, int defaultSeason = 1) {
    const QString p = QString(path).replace(QLatin1Char('\\'), QLatin1Char('/'));
    const QString name = substringAfterLast(p, QLatin1Char('/'));
    std::optional<int> folderSeason;
    {
        auto m = ANY_SEASON.match(p);
        if (!m.hasMatch()) m = DIR_S.match(p);
        if (m.hasMatch()) folderSeason = m.captured(1).toInt();
    }
    auto m = SXXEYY.match(name);
    if (!m.hasMatch()) m = SXXEYY.match(p);
    if (m.hasMatch()) return std::pair{m.captured(1).toInt(), m.captured(2).toInt()};
    m = NXNN.match(name);
    if (m.hasMatch()) return std::pair{m.captured(1).toInt(), m.captured(2).toInt()};
    m = EP_WORD.match(name);
    if (m.hasMatch()) return std::pair{folderSeason.value_or(defaultSeason), m.captured(1).toInt()};
    if (folderSeason) {
        m = LEAD_NUM.match(name);
        if (m.hasMatch()) return std::pair{*folderSeason, m.captured(1).toInt()};
    }
    return std::nullopt;
}

QList<MFile> videosOf(const Magnet &m) {
    QList<MFile> out;
    for (const MFile &f : m.files)
        if (!f.isFolder && contains(VIDEO_EXT, f.path) && !contains(SAMPLE, f.path)) out << f;
    return out;
}

QList<MFile> withoutExtras(const QList<MFile> &vids) {
    QList<MFile> out;
    for (const MFile &f : vids)
        if (!contains(EXTRAS, f.path)) out << f;
    return out;
}

bool anyBluray(const QList<MFile> &vids) {
    return std::any_of(vids.begin(), vids.end(), [](const MFile &f) { return contains(BLURAY_DIR, f.path); });
}

/// У каждого файла в имени есть год, и годы разные: фильмы франшизы, не серии.
bool filmsByYear(const QList<MFile> &files) {
    QSet<QString> years;
    for (const MFile &f : files) {
        const auto m = YEAR_IN_NAME.match(basename(f.path));
        if (!m.hasMatch()) return false;
        years.insert(m.captured(0));
    }
    return years.size() >= 2;
}

bool namedCollection(const Magnet &m, const QList<MFile> &files) {
    return !contains(SERIES_HINT, m.name) &&
           (contains(COLLECTION_STRONG, m.name) || (contains(COLLECTION_WEAK, m.name) && filmsByYear(files)));
}

std::optional<QString> firstTitle(const Playable &p) {
    if (p.seasons.isEmpty() || p.seasons.first().items.isEmpty()) return std::nullopt;
    return p.seasons.first().items.first().title;
}

/// Файлы, отличающиеся одним числом («Show - 01», «Show - 02»…) — серии, если раздача так говорит или похожа на сезон.
std::optional<Playable> numberedEpisodesOf(const Magnet &m, const QList<MFile> &vids, int season) {
    if (vids.size() < 2 || anyBluray(vids)) return std::nullopt;
    const QList<MFile> main = withoutExtras(vids);
    if (main.size() < 2) return std::nullopt;
    if (namedCollection(m, main)) return std::nullopt;
    struct Parsed {
        QString base;
        QList<QRegularExpressionMatch> nums;
    };
    QList<Parsed> parsed;
    qsizetype slotCount = LLONG_MAX;
    for (const MFile &f : main) {
        QString base = basename(f.path).replace(EXT, QString()).replace(DOTS, QStringLiteral(" ")).replace(NUM_NOISE, QStringLiteral(" "));
        Parsed p{base, {}};
        auto it = NUM.globalMatch(base);
        while (it.hasNext()) p.nums << it.next();
        slotCount = std::min(slotCount, p.nums.size());
        parsed << p;
    }
    for (qsizetype slot = 0; slot < slotCount; ++slot) {
        QList<int> nums;
        QSet<int> uniq;
        QSet<QString> templates;
        for (const Parsed &p : parsed) {
            const auto &mm = p.nums[slot];
            nums << mm.captured(0).toInt();
            uniq.insert(nums.last());
            templates.insert(QString(p.base).replace(mm.capturedStart(0), mm.capturedLength(0), QStringLiteral("#")));
        }
        if (uniq.size() != nums.size()) continue;
        if (templates.size() != 1) continue;
        QList<int> sorted = nums;
        std::sort(sorted.begin(), sorted.end());
        bool consecutive = true;
        for (qsizetype i = 1; i < sorted.size(); ++i)
            if (sorted[i] - sorted[i - 1] != 1) consecutive = false;
        qint64 mx = 0, mn = LLONG_MAX;
        for (const MFile &f : main) { mx = std::max(mx, f.size); mn = std::min(mn, f.size); }
        const bool similar = mx <= mn * 2;
        const bool looksLikeSeason = main.size() >= 5 && consecutive && similar;
        if (!contains(SERIES_HINT, m.name) && !looksLikeSeason) continue;
        QList<Episode> eps;
        for (qsizetype i = 0; i < main.size(); ++i) eps << Episode{season, nums[i], main[i], std::nullopt};
        std::stable_sort(eps.begin(), eps.end(), [](const Episode &a, const Episode &b) { return a.episode < b.episode; });
        return Playable::series({Season{season, eps}});
    }
    return std::nullopt;
}

/// Несколько полнометражных файлов (каждый не меньше 1/4 самого большого и 300 МБ) вне папок Blu-ray/DVD.
std::optional<Playable> collectionOf(const QList<MFile> &vids) {
    if (vids.size() < 2 || anyBluray(vids)) return std::nullopt;
    const QList<MFile> main = withoutExtras(vids);
    if (main.isEmpty()) return std::nullopt;
    qint64 largest = 0;
    for (const MFile &f : main) largest = std::max(largest, f.size);
    QList<MFile> films;
    for (const MFile &f : main)
        if (f.size >= 300LL * 1024 * 1024 && f.size >= largest / 4) films << f;
    if (films.size() < 2) return std::nullopt;
    QList<Episode> items;
    const QList<MFile> sorted = sortedByName(films);
    for (qsizetype i = 0; i < sorted.size(); ++i)
        items << Episode{1, int(i + 1), sorted[i], ifBlank(cleanTitle(basename(sorted[i].path)), QStringLiteral("Фильм %1").arg(i + 1))};
    return Playable::series({Season{1, items}});
}

Playable playableByFiles(const Magnet &m) {
    const QList<MFile> vids = videosOf(m);
    // Сначала папки: многие раздачи называют серии без SxxEyy.
    std::map<int, QList<MFile>> bySeason;
    for (const MFile &f : vids)
        if (const auto s = seasonOf(f.path)) bySeason[*s] << f;
    if (bySeason.size() >= 2 || (bySeason.size() == 1 && vids.size() > 1)) {
        QList<Season> seasons;
        for (const auto &[s, files] : bySeason) {
            QList<Episode> eps;
            const QList<MFile> sorted = sortedByName(files);
            for (qsizetype i = 0; i < sorted.size(); ++i) eps << Episode{s, int(i + 1), sorted[i], std::nullopt};
            seasons << Season{s, eps};
        }
        return Playable::series(seasons);
    }
    // Файлы без своей отметки сезона получают сезон из названия раздачи
    const int named = nameSeason(m.name).value_or(1);
    QList<Episode> eps;
    for (const MFile &f : vids)
        if (const auto se = parseEpisode(f.path, named)) eps << Episode{se->first, se->second, f, std::nullopt};
    if (eps.size() >= 2) {
        std::map<int, QList<Episode>> groups;
        for (const Episode &e : eps) groups[e.season] << e;
        QList<Season> seasons;
        for (auto &[s, items] : groups) {
            std::stable_sort(items.begin(), items.end(), [](const Episode &a, const Episode &b) {
                return a.episode != b.episode ? a.episode < b.episode : a.file.path < b.file.path;
            });
            seasons << Season{s, items};
        }
        return Playable::series(seasons);
    }
    if (auto p = numberedEpisodesOf(m, vids, named)) return *p;
    if (auto p = collectionOf(vids)) return *p;
    if (auto v = mainVideo(m)) return Playable::movie(*v);
    return Playable::none();
}

QStringList collectionBases(const QString &baseTitle) {
    QStringList out;
    for (const QString &part : replaced(baseTitle, COLLECTION_WORDS, QStringLiteral(" ")).split(QLatin1Char('/'))) {
        QString s = substringBefore(cleanTitle(part), QLatin1Char(':'));
        s = replaced(replaced(s, FILM_COUNT_NOISE, QString()), SPACES, QStringLiteral(" ")).trimmed();
        if (!isBlank(s)) out << s;
    }
    return out;
}

QString collectionBase(const QString &baseTitle) {
    const QStringList b = collectionBases(baseTitle);
    return b.isEmpty() ? QString() : b.first();
}

QString fileRawName(const MFile &file) { return ifBlank(file.name, substringAfterLast(file.path, QLatin1Char('/'))); }

/// Что ищет поиск медиатеки: название раздачи, файлы и, если принято, название ИИ и оригинальное.
QString searchTextOf(const Magnet &m) {
    QStringList paths;
    for (const MFile &f : m.files) paths << f.path;
    QString s = m.name + QLatin1Char('\n') + paths.join(QLatin1Char('\n'));
    if (const AiTitle *ai = m.acceptedAi()) s += QLatin1Char('\n') + ai->base + QLatin1Char('\n') + ai->original.value_or(QString());
    return s.toLower();
}

QString titleOf(const Magnet &m) {
    if (const AiTitle *ai = m.acceptedAi()) return ai->base;
    return cleanTitle(m.name);
}

QStringList posterQueriesOf(const Magnet &m, const QString &base) {
    QStringList out;
    if (const AiTitle *ai = m.acceptedAi())
        if (auto q = ai->posterQuery()) out << *q;
    out << base;
    if (auto main = mainVideo(m)) out << cleanTitle(fileRawName(*main));
    out.removeDuplicates();
    return out;
}

QString episodesRu(int n) { return QString::number(n) + QLatin1Char(' ') + pluralRu(n, QStringLiteral("серия"), QStringLiteral("серии"), QStringLiteral("серий")); }

std::optional<int> parseRoman(const QString &s) {
    static const QStringList romans{QStringLiteral("I"), QStringLiteral("II"), QStringLiteral("III"), QStringLiteral("IV"), QStringLiteral("V"),
                                    QStringLiteral("VI"), QStringLiteral("VII"), QStringLiteral("VIII"), QStringLiteral("IX"), QStringLiteral("X")};
    const auto i = romans.indexOf(s.toUpper());
    return i < 0 ? std::nullopt : std::optional<int>(int(i + 1));
}

std::optional<int> numberOrRoman(const QString &raw) {
    bool ok = false;
    const int n = raw.toInt(&ok);
    return ok ? std::optional<int>(n) : parseRoman(raw);
}

/// По чему версии сортируются и делятся на разделы: считается один раз на версию.
struct VersionKey {
    LibraryItem item;
    std::optional<std::pair<int, int>> seasons;
    std::optional<int> part;
    std::optional<int> year;
    int quality;
    explicit VersionKey(const LibraryItem &i)
        : item(i), seasons(itemSeasons(i)), part(itemPart(i)), year(releaseYear(i)), quality(smartQualityScore(i.m.name)) {}
};

/// Сезоны по порядку (спецвыпуски после, потом без сезона), часть, год, лучшее качество, больший размер. Порядок полный.
bool versionLess(const VersionKey &a, const VersionKey &b) {
    auto k1 = [](const VersionKey &k) -> qint64 { return k.seasons ? (k.seasons->first == 0 ? INT_MAX - 1 : k.seasons->first) : INT_MAX; };
    auto k2 = [](const VersionKey &k) -> qint64 { return k.seasons ? k.seasons->second - k.seasons->first : 0; };
    auto k3 = [](const VersionKey &k) -> qint64 { return k.part.value_or(INT_MAX); };
    auto k4 = [](const VersionKey &k) -> qint64 { return k.year.value_or(INT_MAX); };
    for (auto key : {+k1, +k2, +k3, +k4})
        if (key(a) != key(b)) return key(a) < key(b);
    if (a.quality != b.quality) return a.quality > b.quality;
    if (a.item.m.size != b.item.m.size) return a.item.m.size > b.item.m.size;
    if (const int c = a.item.m.name.compare(b.item.m.name); c != 0) return c < 0;
    return a.item.m.id < b.item.m.id;
}

QList<VersionKey> sortedKeys(const QList<LibraryItem> &versions) {
    QList<VersionKey> keys;
    for (const LibraryItem &v : versions) keys << VersionKey(v);
    std::stable_sort(keys.begin(), keys.end(), versionLess);
    return keys;
}

/// name (в нижнем регистре, без папки) — это base плюс хвост, и base кончается там, где кончается слово.
bool sameBase(const QString &name, const QString &base) {
    if (!name.startsWith(base)) return false;
    if (name.size() == base.size()) return true;
    const QChar c = name.at(base.size());
    return !(c.isLetter() || c.isDigit());
}

QString norm(const QString &p) { return QString(p).replace(QLatin1Char('\\'), QLatin1Char('/')); }

QString videoBase(const MFile &video) {
    const QString name = substringAfterLast(norm(video.path), QLatin1Char('/'));
    return substringBeforeLast(name, QLatin1Char('.'), name).toLower();
}

QList<MFile> take8(const QList<MFile> &l) { return l.mid(0, 8); }

} // namespace

// ---------------------------------------------------------------------------------------------------------------

std::optional<QString> AiTitle::posterQuery() const {
    if (!original || isBlank(*original)) return std::nullopt;
    return year ? QStringLiteral("%1 (%2)").arg(*original).arg(*year) : *original;
}

float Magnet::progress() const {
    if (size <= 0) return 0.f;
    return std::clamp(float(downloaded) / float(size), 0.f, 1.f);
}

float WatchInfo::fraction() const {
    if (len <= 0) return 0.f;
    return std::clamp(float(pos) / float(len), 0.f, 1.f);
}

std::optional<AiTitle> parseAiTitle(const QJsonObject *a) {
    if (!a) return std::nullopt;
    static const QStringList statuses{QStringLiteral("suggested"), QStringLiteral("accepted"), QStringLiteral("rejected"), QStringLiteral("none")};
    static const QStringList kinds{QStringLiteral("movie"), QStringLiteral("series"), QStringLiteral("collection")};
    const QString status = optString(*a, QStringLiteral("status"));
    if (!statuses.contains(status)) return std::nullopt;
    AiTitle t;
    t.base = optString(*a, QStringLiteral("base")).trimmed();
    if (hasValue(*a, QStringLiteral("season"))) t.season = int(optLong(*a, QStringLiteral("season")));
    t.model = optString(*a, QStringLiteral("model"));
    t.at = optLong(*a, QStringLiteral("at"));
    t.status = status;
    if (const QString k = optString(*a, QStringLiteral("kind")); kinds.contains(k)) t.kind = k;
    t.stale = optBool(*a, QStringLiteral("stale"), false);
    if (hasValue(*a, QStringLiteral("original")))
        if (const QString o = optString(*a, QStringLiteral("original")).trimmed(); !o.isEmpty()) t.original = o;
    if (hasValue(*a, QStringLiteral("year")))
        if (const int y = int(optLong(*a, QStringLiteral("year"))); y >= 1880 && y <= 2100) t.year = y;
    if (hasValue(*a, QStringLiteral("edition")))
        if (const QString ed = optString(*a, QStringLiteral("edition")).trimmed(); !ed.isEmpty()) t.edition = ed;
    if (hasValue(*a, QStringLiteral("canonical_id")))
        if (const QString cid = optString(*a, QStringLiteral("canonical_id")).trimmed(); !cid.isEmpty()) t.canonicalId = cid;
    return t;
}

qint64 aiTitleReadyAt(const QJsonObject &json) { return optLong(json, QStringLiteral("aiTitleReadyAt")); }

QList<Magnet> parseLibrary(const QJsonObject &json) {
    const QJsonValue arrV = json.value(QLatin1String("magnets"));
    if (!arrV.isArray()) return {};
    QList<Magnet> out;
    for (const QJsonValue &mv : arrV.toArray()) {
        if (!mv.isObject()) continue;
        const QJsonObject m = mv.toObject();
        Magnet g;
        const QJsonValue filesV = m.value(QLatin1String("files"));
        if (filesV.isArray()) {
            const QJsonArray fa = filesV.toArray();
            for (qsizetype j = 0; j < fa.size(); ++j) {
                if (!fa[j].isObject()) continue;
                const QJsonObject f = fa[j].toObject();
                MFile file;
                file.index = int(j);
                file.name = optString(f, QStringLiteral("name"));
                file.path = optString(f, QStringLiteral("path"));
                file.isFolder = optString(f, QStringLiteral("type")) == QLatin1String("folder");
                file.size = optLong(f, QStringLiteral("size"));
                if (const QString l = optString(f, QStringLiteral("sourceLink")); l.startsWith(QLatin1String("https://"))) file.link = l;
                g.files << file;
            }
        }
        g.id = optLong(m, QStringLiteral("id"));
        g.name = ifBlank(optString(m, QStringLiteral("name")), QStringLiteral("Magnet %1").arg(g.id));
        g.status = optString(m, QStringLiteral("status"));
        g.statusCode = int(optLong(m, QStringLiteral("statusCode")));
        g.size = optLong(m, QStringLiteral("size"));
        g.downloaded = optLong(m, QStringLiteral("downloaded"));
        g.canRemove = optBool(m, QStringLiteral("canRemove"), true);
        g.favorite = optBool(m, QStringLiteral("favorite"), false);
        g.mine = optBool(m, QStringLiteral("mine"), false);
        const QJsonValue ai = m.value(QLatin1String("aiTitle"));
        if (ai.isObject()) {
            const QJsonObject aiObj = ai.toObject();
            g.aiTitle = parseAiTitle(&aiObj);
        }
        g.canAiTitle = optBool(m, QStringLiteral("canAiTitle"), false);
        out << g;
    }
    return out;
}

std::optional<int> nameSeason(const QString &name) {
    if (contains(NAME_SEASON_RANGE, name)) return std::nullopt;
    QSet<int> nums;
    auto it = NAME_SEASON.globalMatch(name);
    while (it.hasNext()) {
        const auto m = it.next();
        for (int g = 1; g <= m.lastCapturedIndex(); ++g)
            if (!m.captured(g).isEmpty()) { nums.insert(m.captured(g).toInt()); break; }
    }
    if (nums.size() != 1) return std::nullopt;
    const int n = *nums.begin();
    return n > 0 ? std::optional<int>(n) : std::nullopt;
}

std::optional<std::pair<int, int>> episodePair(const QString &path, int defaultSeason) {
    return parseEpisode(path, defaultSeason);
}

QString parseEdition(const QString &name) {
    if (isBlank(name)) return {};
    static const RE reExt = rx(NB + QStringLiteral(R"((?:extended|ext[\s._-]?cut|расширенн\p{L}*))") + NA, true);
    static const RE reDc = rx(NB + QStringLiteral(R"((?:director'?s?[\s._-]?cut|dc|режиссерск\p{L}*))") + NA, true);
    static const RE reUnr = rx(NB + QStringLiteral(R"((?:unrated|uncut|без[\s._-]?цензур\p{L}*))") + NA, true);
    static const RE reRem = rx(NB + QStringLiteral(R"((?:remaster(?:ed)?|ремастер\p{L}*))") + NA, true);
    static const RE reTh = rx(NB + QStringLiteral(R"((?:theatrical|театральн\p{L}*))") + NA, true);
    if (contains(reExt, name)) return QStringLiteral("extended");
    if (contains(reDc, name)) return QStringLiteral("directors_cut");
    if (contains(reUnr, name)) return QStringLiteral("unrated");
    if (contains(reRem, name)) return QStringLiteral("remastered");
    if (contains(reTh, name)) return QStringLiteral("theatrical");
    return {};
}

bool isDiscImage(const QString &nameOrUrl) {
    const QString path = nameOrUrl.section(QLatin1Char('?'), 0, 0).section(QLatin1Char('#'), 0, 0);
    return path.endsWith(QLatin1String(".iso"), Qt::CaseInsensitive);
}

std::optional<MFile> mainVideo(const Magnet &m) {
    QList<MFile> vids, real;
    for (const MFile &f : m.files)
        if (!f.isFolder && contains(VIDEO_EXT, f.path)) vids << f;
    for (const MFile &f : vids)
        if (!isDiscImage(f.path)) real << f;
    const QList<MFile> &pool = real.isEmpty() ? vids : real;
    if (pool.isEmpty()) return std::nullopt;
    const MFile *best = &pool.first();
    for (const MFile &f : pool)
        if (f.size > best->size) best = &f;
    return *best;
}

Playable buildPlayable(const Magnet &m) { return applyAiTitle(playableByFiles(m), m); }

bool isCollection(const Playable &p, const Magnet *m) {
    if (m)
        if (const AiTitle *ai = m->acceptedAi(); ai && ai->kind) return *ai->kind == QLatin1String("collection");
    if (!p.isSeries()) return false;
    if (firstTitle(p)) return true;
    return m && contains(COLLECTION_STRONG, m->name) && !contains(SERIES_HINT, m->name);
}

QString collectionKindLabel(CollectionKind k) {
    switch (k) {
    case CollectionKind::Trilogy: return QStringLiteral("Кинотрилогия");
    case CollectionKind::Dilogy: return QStringLiteral("Дилогия");
    case CollectionKind::Tetralogy: return QStringLiteral("Кинотетралогия");
    case CollectionKind::Compilation: return QStringLiteral("Сборник");
    case CollectionKind::Anthology: return QStringLiteral("Антология");
    case CollectionKind::Collection: return QStringLiteral("Коллекция");
    case CollectionKind::Franchise: break;
    }
    return QStringLiteral("Кинофраншиза");
}

QString collectionKindTrKey(CollectionKind k) {
    switch (k) {
    case CollectionKind::Trilogy: return QStringLiteral("library.kinotrilogiya");
    case CollectionKind::Dilogy: return QStringLiteral("library.dilogiya");
    case CollectionKind::Tetralogy: return QStringLiteral("library.kinotetralogiya");
    case CollectionKind::Compilation: return QStringLiteral("library.sbornik");
    case CollectionKind::Anthology: return QStringLiteral("library.antologiya");
    case CollectionKind::Collection: return QStringLiteral("library.kollektsiya");
    case CollectionKind::Franchise: break;
    }
    return QStringLiteral("library.kinofranshiza");
}

CollectionKind collectionKind(int filmCount, const QString &name) {
    if (contains(KIND_TRILOGY, name)) return CollectionKind::Trilogy;
    if (contains(KIND_DILOGY, name)) return CollectionKind::Dilogy;
    if (contains(KIND_TETRALOGY, name)) return CollectionKind::Tetralogy;
    if (filmCount == 2) return CollectionKind::Dilogy;
    if (filmCount == 3) return CollectionKind::Trilogy;
    if (filmCount == 4) return CollectionKind::Tetralogy;
    if (contains(KIND_COMPILATION, name)) return CollectionKind::Compilation;
    if (contains(KIND_ANTHOLOGY, name)) return CollectionKind::Anthology;
    if (contains(KIND_COLLECTION, name)) return CollectionKind::Collection;
    return CollectionKind::Franchise;
}

QString pluralRu(int n, const QString &one, const QString &few, const QString &many) {
    if (n % 10 == 1 && n % 100 != 11) return one;
    if (n % 10 >= 2 && n % 10 <= 4 && !(n % 100 >= 12 && n % 100 <= 14)) return few;
    return many;
}

QString filmCountRu(int count) {
    return QString::number(count) + QLatin1Char(' ') + pluralRu(count, QStringLiteral("фильм"), QStringLiteral("фильма"), QStringLiteral("фильмов"));
}

QList<Episode> collectionFilms(const LibraryItem &item) {
    QList<Episode> out;
    if (!isCollection(item.playable, &item.m) || !item.playable.isSeries()) return out;
    for (const Season &s : item.playable.seasons) out << s.items;
    return out;
}

QString cleanCollectionFilmTitle(const QString &raw) {
    const QString withoutExt = replaced(raw, EXT, QString());
    const QString stripped = replaced(withoutExt, TRACK_PREFIX, QString()).trimmed();
    const QString cleaned = cleanTitle(ifBlank(stripped, withoutExt));
    return ifBlank(cleaned, cleanTitle(raw));
}

QString collectionFileTitle(const Magnet &m, const MFile &file, const std::optional<QString> &baseTitleOpt) {
    const QString baseTitle = baseTitleOpt.value_or(cleanTitle(m.name));
    const QString raw = fileRawName(file);
    const QString cleaned = cleanCollectionFilmTitle(raw);
    const QString coreBase = collectionBase(baseTitle);
    if (!isBlank(cleaned) && !isBlank(coreBase) && !cleaned.contains(coreBase, Qt::CaseInsensitive)) return coreBase + QStringLiteral(": ") + cleaned;
    if (!isBlank(cleaned)) return cleaned;
    return cleanTitle(raw);
}

QStringList collectionFilePosterQueries(const Magnet &m, const MFile &file, const std::optional<QString> &baseTitleOpt) {
    const QString baseTitle = baseTitleOpt.value_or(cleanTitle(m.name));
    const QString raw = fileRawName(file);
    const QString withoutExt = replaced(raw, EXT, QString());
    std::optional<QString> year;
    if (auto y = YEAR_IN_NAME.match(withoutExt); y.hasMatch()) year = y.captured(0);
    else if (auto y2 = YEAR_IN_NAME.match(file.path); y2.hasMatch()) year = y2.captured(0);
    QString film;
    for (const QString &part : cleanTitle(ifBlank(replaced(withoutExt, TRACK_PREFIX, QString()).trimmed(), withoutExt)).split(QLatin1Char('/')))
        if (!isBlank(part.trimmed())) { film = part.trimmed(); break; }
    const QString base = collectionBase(baseTitle);
    auto withYear = [&](const QString &q) { return year ? QStringLiteral("%1 (%2)").arg(q, *year) : q; };
    QStringList out;
    auto add = [&](const QString &q) { if (!out.contains(q)) out << q; };
    if (!isBlank(film)) { add(withYear(film)); add(film); }
    if (base.size() >= 2 && !isBlank(film) && !film.contains(base, Qt::CaseInsensitive)) {
        add(withYear(base + QStringLiteral(": ") + film));
        add(base + QStringLiteral(": ") + film);
    }
    for (const QString &b : collectionBases(baseTitle))
        if (b.size() >= 2) add(b);
    add(cleanTitle(m.name));
    return out.mid(0, MAX_FILM_QUERIES);
}

QStringList continueItemPosterQueries(const LibraryItem &item, const MFile &file) {
    if (isCollection(item.playable, &item.m)) return collectionFilePosterQueries(item.m, file, item.baseTitle);
    return item.posterQueries;
}

QString continueItemTitle(const LibraryItem &item, const MFile &file) {
    if (isCollection(item.playable, &item.m)) return collectionFileTitle(item.m, file, item.baseTitle);
    return item.baseTitle;
}

Playable applyAiTitle(const Playable &p, const Magnet &m) {
    const AiTitle *ai = m.acceptedAi();
    if (!ai) return p;
    const QList<MFile> vids = videosOf(m);
    QList<MFile> main = withoutExtras(vids);
    if (main.isEmpty()) main = vids;
    Playable out = p;
    const QString kind = ai->kind.value_or(QString());
    if (kind == QLatin1String("movie")) {
        if (p.isSeries())
            if (auto v = mainVideo(m)) out = Playable::movie(*v);
    } else if (kind == QLatin1String("series")) {
        if ((p.isMovie() || isCollection(p)) && main.size() >= 2) {
            const int s = ai->season && *ai->season > 0 ? *ai->season : 1;
            QList<Episode> eps;
            const QList<MFile> sorted = sortedByName(main);
            for (qsizetype i = 0; i < sorted.size(); ++i) eps << Episode{s, int(i + 1), sorted[i], std::nullopt};
            out = Playable::series({Season{s, eps}});
        }
    } else if (kind == QLatin1String("collection")) {
        if ((p.isMovie() || (p.isSeries() && !isCollection(p))) && main.size() >= 2) {
            QList<Episode> eps;
            const QList<MFile> sorted = sortedByName(main);
            for (qsizetype i = 0; i < sorted.size(); ++i)
                eps << Episode{1, int(i + 1), sorted[i], ifBlank(cleanTitle(basename(sorted[i].path)), QStringLiteral("Фильм %1").arg(i + 1))};
            out = Playable::series({Season{1, eps}});
        }
    }
    if (ai->season && *ai->season > 0 && out.isSeries() && out.seasons.size() == 1 && !isCollection(out) && out.seasons.first().season != *ai->season) {
        const int s = *ai->season;
        QList<Episode> items = out.seasons.first().items;
        for (Episode &e : items) e.season = s;
        out = Playable::series({Season{s, items}});
    }
    return out;
}

QString cleanTitle(const QString &value) {
    QString s = replaced(replaced(ifBlank(value, QStringLiteral("Untitled")), EXT, QString()), DOTS, QStringLiteral(" "));
    s = replaced(s, TAGS, QString());
    s = replaced(replaced(replaced(s, YEAR_TAIL, QStringLiteral(" ")), BRACKETS, QStringLiteral(" ")), SPACES, QStringLiteral(" ")).trimmed();
    return ifBlank(s, replaced(value, DOTS, QStringLiteral(" ")).trimmed());
}

QString titleKey(const QString &baseTitle) {
    const QString plain = baseTitle.toLower().replace(QChar(u'ё'), QChar(u'е'));
    const QString key = replaced(replaced(plain, SEASON_NOISE, QStringLiteral(" ")), NON_WORD, QStringLiteral(" ")).trimmed();
    return ifBlank(key, replaced(plain, NON_WORD, QStringLiteral(" ")).trimmed());
}

QString formatBytes(qint64 value) {
    if (value <= 0) return QStringLiteral("—");
    static const char *units[] = {"B", "KB", "MB", "GB", "TB"};
    double v = double(value);
    int i = 0;
    while (v >= 1024 && i < 4) { v /= 1024; ++i; }
    if (i == 0) return QStringLiteral("%1 B").arg(value);
    return QString::number(v, 'f', 1) + QLatin1Char(' ') + QLatin1String(units[i]);
}

QString statusRu(const Magnet &m) {
    if (m.ready()) return QStringLiteral("Готово");
    if (m.failed()) return ifBlank(m.status, QStringLiteral("Ошибка"));
    if (m.status.compare(QLatin1String("Downloading"), Qt::CaseInsensitive) == 0) return QStringLiteral("Загрузка");
    if (isBlank(m.status)) return QStringLiteral("Обработка");
    return m.status;
}

QString tileTitle(const LibraryItem &item, const std::function<std::optional<QString>(const QString &)> &ruTitleOf) {
    if (item.m.acceptedAi() || isCollection(item.playable, &item.m)) return item.baseTitle;
    for (const QString &q : item.posterQueries)
        if (auto t = ruTitleOf(q.toLower()); t && !isBlank(*t)) return *t;
    return item.baseTitle;
}

QString episodeLabel(const QString &base, const Season &s, const Episode &e) {
    if (e.title) return *e.title;
    return base + QStringLiteral(" · ") + (s.season == 0 ? QStringLiteral("Спец.") : QStringLiteral("S") + pad2(s.season)) + QStringLiteral("E") + pad2(e.episode);
}

std::optional<Season> watchedSeason(const LibraryItem &item, const QString &path) {
    if (!item.playable.isSeries() || isCollection(item.playable, &item.m)) return std::nullopt;
    for (const Season &s : item.playable.seasons)
        for (const Episode &e : s.items)
            if (e.file.path == path) return s;
    return std::nullopt;
}

LibraryItem LibraryItem::withAiTitle(const std::optional<AiTitle> &ai) const {
    LibraryItem out = *this;
    out.m.aiTitle = ai;
    out.baseTitle = titleOf(out.m);
    out.playable = buildPlayable(out.m);
    out.posterQueries = posterQueriesOf(out.m, out.baseTitle);
    out.searchText = searchTextOf(out.m);
    return out;
}

QList<LibraryItem> prepareItems(const QList<Magnet> &list) {
    QList<LibraryItem> out;
    out.reserve(list.size());
    for (const Magnet &m : list) {
        const QString base = titleOf(m);
        out << LibraryItem{m, buildPlayable(m), base, posterQueriesOf(m, base), searchTextOf(m)};
    }
    return out;
}

QString seriesLine(const Playable &p) {
    QList<int> nums;
    int eps = 0;
    for (const Season &s : p.seasons) {
        if (s.season > 0) nums << s.season;
        eps += int(s.items.size());
    }
    if (firstTitle(p)) return filmCountRu(eps);
    QString range;
    if (nums.size() == 1) range = QStringLiteral("Сезон %1 · ").arg(nums.first());
    else if (nums.size() > 1)
        range = QStringLiteral("Сезоны %1–%2 · ").arg(*std::min_element(nums.begin(), nums.end())).arg(*std::max_element(nums.begin(), nums.end()));
    return range + episodesRu(eps);
}

std::optional<int> extractYear(const QString &name) {
    if (const auto m = YEAR_BRACKETED.match(name); m.hasMatch())
        if (const int y = m.captured(1).toInt(); y <= latestYear()) return y;
    auto it = YEAR_IN_NAME.globalMatch(name);
    while (it.hasNext())
        if (const int y = it.next().captured(1).toInt(); y <= latestYear()) return y;
    return std::nullopt;
}

std::optional<int> releaseYear(const LibraryItem &item) {
    if (const AiTitle *ai = item.m.acceptedAi(); ai && ai->year) return ai->year;
    if (auto y = extractYear(item.m.name)) return y;
    if (auto y = extractYear(item.baseTitle)) return y;
    for (const MFile &f : item.m.files)
        if (auto y = extractYear(ifBlank(f.name, f.path))) return y;
    return std::nullopt;
}

std::optional<int> itemPart(const LibraryItem &item) {
    const QString &text = item.m.name;
    if (const auto m = PART_EXPLICIT.match(text); m.hasMatch())
        if (auto n = numberOrRoman(m.captured(1))) return n;
    if (const auto m = PART_ORDINAL.match(text); m.hasMatch()) {
        bool ok = false;
        const int n = m.captured(1).toInt(&ok);
        if (ok) return n;
    }
    if (!isBlank(item.baseTitle)) {
        const RE afterBase = rx(NB + RE::escape(item.baseTitle) + QStringLiteral(R"(\s+(\d{1,2}|[IVX]{1,4}))") + NA, true);
        if (const auto m = afterBase.match(text); m.hasMatch())
            if (auto n = numberOrRoman(m.captured(1))) return n;
    }
    return std::nullopt;
}

int smartQualityScore(const QString &name) {
    // Вызывается на каждую раздачу при каждой группировке версий: шаблоны компилируются один раз, а не на каждый вызов
    static const auto rx = [](const char *pat) {
        return QRegularExpression(QString::fromUtf8(pat), QRegularExpression::CaseInsensitiveOption);
    };
    static const QRegularExpression re4k = rx(R"((?:^|[^a-z0-9])(?:2160p|4k|uhd)(?![a-z])|(?:^|\D)3840(?!\d))");
    static const QRegularExpression re1080 = rx(R"((?:^|\D)1080[pi](?![a-z])|(?:^|\D)1920(?!\d))");
    static const QRegularExpression re720 = rx(R"((?:^|\D)720p(?![a-z])|(?:^|\D)1280(?!\d))");
    static const QRegularExpression reDv = rx(R"(dolby[\s._-]?vision|dovi|(?:^|[^a-z])dv(?![a-z]))");
    static const QRegularExpression reHdr10p = rx(R"(hdr10\+|hdr10plus)");
    static const QRegularExpression reHdr = rx(R"(hdr10|(?:^|[^a-z])hdr(?![a-z]))");
    static const QRegularExpression re10bit = rx(R"(10[\s._-]?bit|hi10p)");
    static const QRegularExpression reAtmosDtsx = rx(R"(atmos(?![a-z])|dts[:._-]?x(?![a-z0-9]))");
    static const QRegularExpression reTruehd = rx(R"(truehd)");
    static const QRegularExpression reDtshd = rx(R"(dts[\s._-]?hd)");
    static const QRegularExpression reDtsDdp = rx(R"((?:^|[^a-z])dts(?![a-z])|dd\+|(?:^|[^a-z])ddp(?![a-z])|(?:^|[^a-z])e[\s._-]?ac[\s._-]?3(?!\d))");
    static const QRegularExpression reAc3 = rx(R"((?:^|[^a-z])ac[\s._-]?3(?!\d)|(?:^|[^a-z])dd[\s._-]?5\.1|(?:^|[^\d.])5\.1(?!\d))");
    static const QRegularExpression reRemux = rx(R"(remux)");
    static const QRegularExpression reBluray = rx(R"(bdrip|brrip|blu[\s._-]?ray)");
    static const QRegularExpression reWeb = rx(R"((?:^|[^a-z])web(?:[\s._-]?(?:dl|rip))?(?![a-z]))");

    const QString l = name.toLower();

    int rScore = 100;
    if (l.contains(re4k)) rScore = 400;
    else if (l.contains(re1080)) rScore = 300;
    else if (l.contains(re720)) rScore = 200;

    int vScore = 0;
    if (l.contains(reDv)) vScore = 40;
    else if (l.contains(reHdr10p)) vScore = 30;
    else if (l.contains(reHdr)) vScore = 20;
    else if (l.contains(re10bit)) vScore = 10;

    int aScore = 0;
    if (l.contains(reAtmosDtsx)) aScore = 40;
    else if (l.contains(reTruehd)) aScore = 35;
    else if (l.contains(reDtshd)) aScore = 30;
    else if (l.contains(reDtsDdp)) aScore = 20;
    else if (l.contains(reAc3)) aScore = 10;

    int sScore = 0;
    if (l.contains(reRemux)) sScore = 25;
    else if (l.contains(reBluray)) sScore = 15;
    else if (l.contains(reWeb)) sScore = 10;

    return rScore + vScore + aScore + sScore;
}

int qualityScore(const QString &name) {
    const QString l = name.toLower();
    auto has = [&](const char *s) { return l.contains(QLatin1String(s)); };
    if (has("2160p") || has("4k") || has("3840")) return 4;
    if (has("1080p") || has("1080i") || has("1920")) return 3;
    if (has("720p") || has("1280")) return 2;
    if (has("480p") || has("576p") || has("dvd")) return 1;
    return 0;
}

std::optional<std::pair<int, int>> itemSeasons(const LibraryItem &item) {
    if (const AiTitle *ai = item.m.acceptedAi(); ai && ai->season) return std::pair{*ai->season, *ai->season};
    if (item.playable.isSeries()) {
        if (firstTitle(item.playable)) return std::nullopt; // сборник фильмов, не сериал
        if (!item.playable.seasons.isEmpty()) {
            int mn = INT_MAX, mx = INT_MIN;
            for (const Season &s : item.playable.seasons) { mn = std::min(mn, s.season); mx = std::max(mx, s.season); }
            return std::pair{mn, mx};
        }
    }
    if (const auto r = SEASON_RANGE_REGEX.match(item.m.name); r.hasMatch()) {
        bool ok1 = false, ok2 = false;
        const int mn = ifBlank(r.captured(1), r.captured(3)).toInt(&ok1);
        const int mx = ifBlank(r.captured(2), r.captured(4)).toInt(&ok2);
        if (ok1 && ok2) return std::pair{mn, mx};
    }
    auto single = ANY_SEASON.match(item.m.name);
    if (!single.hasMatch()) single = SXXEYY.match(item.m.name);
    if (single.hasMatch()) {
        bool ok = false;
        const int s = single.captured(1).toInt(&ok);
        if (ok) return std::pair{s, s};
    }
    if (item.playable.isMovie()) {
        if (auto s = seasonOf(item.playable.file.path)) return std::pair{*s, *s};
        if (auto e = parseEpisode(item.playable.file.path)) return std::pair{e->first, e->first};
    }
    return std::nullopt;
}

QList<LibraryItem> sortVersions(const QList<LibraryItem> &versions) {
    if (versions.size() <= 1) return versions;
    QList<LibraryItem> out;
    for (const VersionKey &k : sortedKeys(versions)) out << k.item;
    return out;
}

QList<VersionSection> versionSections(const QList<LibraryItem> &versions) {
    if (versions.isEmpty()) return {};
    const QList<VersionKey> keys = sortedKeys(versions);
    QList<LibraryItem> sorted;
    for (const VersionKey &k : keys) sorted << k.item;
    QStringList titles;
    const bool anySeasons = std::any_of(keys.begin(), keys.end(), [](const VersionKey &k) { return k.seasons.has_value(); });
    if (anySeasons) {
        for (const VersionKey &k : keys) {
            if (!k.seasons) titles << QStringLiteral("Дополнительно");
            else if (k.seasons->first == 0) titles << QStringLiteral("Спецвыпуски");
            else if (k.seasons->first == k.seasons->second) titles << QStringLiteral("Сезон %1").arg(k.seasons->first);
            else titles << QStringLiteral("Сезоны %1–%2").arg(k.seasons->first).arg(k.seasons->second);
        }
    } else {
        QSet<int> years, parts;
        for (const VersionKey &k : keys) {
            if (k.year) years.insert(*k.year);
            if (k.part) parts.insert(*k.part);
        }
        if (years.size() <= 1 && parts.size() <= 1) return {VersionSection{std::nullopt, sorted}};
        for (const VersionKey &k : keys) {
            if (k.year && k.part) titles << QStringLiteral("%1 год · Часть %2").arg(*k.year).arg(*k.part);
            else if (k.year) titles << QStringLiteral("%1 год").arg(*k.year);
            else if (k.part) titles << QStringLiteral("Часть %1").arg(*k.part);
            else titles << QStringLiteral("Другая версия");
        }
    }
    QStringList distinct = titles;
    distinct.removeDuplicates();
    if (distinct.size() <= 1) return {VersionSection{std::nullopt, sorted}};
    QList<VersionSection> out;
    for (const QString &t : distinct) {
        VersionSection s{t, {}};
        for (qsizetype i = 0; i < sorted.size(); ++i)
            if (titles[i] == t) s.items << sorted[i];
        out << s;
    }
    return out;
}

QString versionLine(const LibraryItem &v, bool inSection) {
    const QList<Episode> films = collectionFilms(v);
    if (!films.isEmpty()) return collectionKindLabel(collectionKind(int(films.size()), v.m.name)) + QStringLiteral(" • ") + filmCountRu(int(films.size()));
    if (v.playable.isSeries()) {
        if (!inSection) return seriesLine(v.playable);
        const auto range = itemSeasons(v);
        int count = 0;
        for (const Season &s : v.playable.seasons) count += int(s.items.size());
        const QString eps = episodesRu(count);
        return range && range->first != range->second ? QStringLiteral("Сезоны %1–%2 · ").arg(range->first).arg(range->second) + eps : eps;
    }
    if (inSection) return {};
    if (auto y = releaseYear(v)) return QStringLiteral("%1 год").arg(*y);
    return {};
}

std::pair<QList<LibraryItem>, QHash<qint64, QList<LibraryItem>>> groupVersions(const QList<LibraryItem> &list, const PosterOf &posterOf) {
    QHash<QString, int> byKey;       // ключ → номер группы
    QList<QList<LibraryItem>> groups;
    QList<LibraryItem> shown;
    shown.reserve(list.size());
    for (const LibraryItem &it : list) {
        const QString &base = it.baseTitle;
        if (it.playable.isNone() || !it.m.ready() || isBlank(base)) { shown << it; continue; }
        const QString kind = it.playable.isMovie() ? QStringLiteral("m") : QStringLiteral("s");
        QString ed;
        if (const AiTitle *ai = it.m.acceptedAi(); ai && ai->edition && !ai->edition->isEmpty()) {
            const QString e = *ai->edition;
            if (e == QLatin1String("theatrical") || e == QLatin1String("extended") || e == QLatin1String("directors_cut") ||
                e == QLatin1String("unrated") || e == QLatin1String("remastered"))
                ed = e;
        }
        if (ed.isEmpty()) ed = parseEdition(it.m.name);
        const QString edKey = ed.isEmpty() ? QString() : (QLatin1Char('|') + ed);
        QStringList keys;
        if (posterOf)
            if (auto p = posterOf(it)) keys << kind + QStringLiteral("|poster|") + *p + edKey;
        if (kind == QLatin1String("m")) {
            QString year;
            if (const AiTitle *ai = it.m.acceptedAi(); ai && ai->year) year = QString::number(*ai->year);
            else if (const auto y = YEAR_IN_NAME.match(it.m.name); y.hasMatch()) year = y.captured(0);
            keys << QStringLiteral("m|") + titleKey(base) + QLatin1Char('|') + year + edKey;
        } else {
            keys << QStringLiteral("s|") + titleKey(base) + edKey;
        }
        int group = -1;
        for (const QString &k : keys)
            if (byKey.contains(k)) { group = byKey.value(k); break; }
        if (group < 0) {
            groups << QList<LibraryItem>{it};
            shown << it;
            for (const QString &k : keys) byKey.insert(k, int(groups.size() - 1));
        } else {
            groups[group] << it;
            for (const QString &k : keys)
                if (!byKey.contains(k)) byKey.insert(k, group);
        }
    }
    QHash<qint64, QList<LibraryItem>> versions;
    for (const auto &g : groups)
        if (g.size() > 1) versions.insert(g.first().m.id, sortVersions(g));
    return {shown, versions};
}

QList<MFile> subtitlesFor(const Magnet &m, const MFile &video) {
    QList<MFile> subs;
    for (const MFile &f : m.files)
        if (!f.isFolder && f.link && contains(SUB_EXT, f.path)) subs << f;
    if (subs.isEmpty()) return {};
    const QString vPath = norm(video.path);
    const QString vDir = substringBeforeLast(vPath, QLatin1Char('/'), QString());
    const QString vBase = videoBase(video);
    QList<MFile> byName;
    for (const MFile &f : subs)
        if (sameBase(substringAfterLast(norm(f.path), QLatin1Char('/')).toLower(), vBase)) byName << f;
    if (!byName.isEmpty()) return take8(byName);
    if (videosOf(m).size() <= 1) return take8(subs);
    QList<MFile> near;
    for (const MFile &f : subs) {
        const QString d = substringBeforeLast(norm(f.path), QLatin1Char('/'), QString());
        if (d == vDir || (d.startsWith(vDir + QLatin1Char('/')) && substringAfterLast(d, QLatin1Char('/')).toLower().startsWith(QLatin1String("sub"))))
            near << f;
    }
    return take8(near);
}

QList<MFile> externalAudioFor(const Magnet &m, const MFile &video) {
    QList<MFile> audio;
    for (const MFile &f : m.files)
        if (!f.isFolder && f.link && contains(AUDIO_EXT, f.path)) audio << f;
    if (audio.isEmpty()) return {};
    const QString vBase = videoBase(video);
    QList<MFile> byName;
    for (const MFile &f : audio)
        if (sameBase(substringAfterLast(norm(f.path), QLatin1Char('/')).toLower(), vBase)) byName << f;
    if (!byName.isEmpty()) return take8(byName);
    return videosOf(m).size() <= 1 ? take8(audio) : QList<MFile>{};
}

QString watchKey(qint64 magnetId, const MFile &file) { return QString::number(magnetId) + QLatin1Char('|') + file.path; }

} // namespace suslik::core

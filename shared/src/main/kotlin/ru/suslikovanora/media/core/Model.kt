package ru.suslikovanora.media.core

import org.json.JSONObject
import java.util.Locale

/** One entry of a torrent's file tree, flattened by the server (folders included, so indexes match the server). */
data class MFile(
    val index: Int,
    val name: String,
    val path: String,
    val isFolder: Boolean,
    val size: Long,
    val link: String?
)

data class Magnet(
    val id: Long,
    val name: String,
    val status: String,
    val statusCode: Int,
    val size: Long,
    val downloaded: Long,
    val files: List<MFile>,
    val canRemove: Boolean,
    val favorite: Boolean = false, // per device, shown first in the library
    val mine: Boolean = false, // in the owner's own list «Мои фильмы» (server: services/shelf.js)
    val aiTitle: AiTitle? = null, // suggested by the server's AI (services/aititle.js)
    val canAiTitle: Boolean = false // this user added the ready torrent (or is admin): may ask the AI and accept / reject
) {
    val ready get() = statusCode == 4
    val downloading get() = statusCode < 4
    val failed get() = statusCode >= 5
    val progress: Float get() = if (size > 0) (downloaded.toFloat() / size).coerceIn(0f, 1f) else 0f
}

/**
 * Title and season the server's AI suggested for a torrent (services/aititle.js). [status]: "suggested", "accepted",
 * "rejected" or "none" (the answer failed the server's check). Only an accepted one is used for grouping; the regexes
 * stay the main way.
 */
data class AiTitle(
    val base: String,
    val season: Int?,
    val model: String,
    val at: Long,
    val status: String,
    val kind: String? = null, // "movie" | "series" | "collection": what the release is, applied once accepted
    val stale: Boolean = false, // answered by an older server version: may be asked again
    val original: String? = null, // original title (English when not in Latin letters) — for the TMDB poster
    val year: Int? = null // the film's year / the show's first season
) {
    val accepted get() = status == "accepted" && base.isNotBlank()
    /** «Dune (2021)»: the server picks the TMDB result of that year. Null without an original title. */
    val posterQuery: String? get() = original?.takeIf { it.isNotBlank() }?.let { o -> if (year != null) "$o ($year)" else o }
}

private val AI_STATUSES = setOf("suggested", "accepted", "rejected", "none")
private val AI_KINDS = setOf("movie", "series", "collection")

fun parseAiTitle(a: JSONObject?): AiTitle? {
    if (a == null) return null
    val status = a.optString("status")
    if (status !in AI_STATUSES) return null
    return AiTitle(
        base = a.optString("base").trim(),
        season = if (a.has("season") && !a.isNull("season")) a.optInt("season") else null,
        model = a.optString("model"),
        at = a.optLong("at", 0L),
        status = status,
        kind = a.optString("kind").takeIf { it in AI_KINDS },
        stale = a.optBoolean("stale", false),
        original = a.optString("original").trim().takeIf { it.isNotEmpty() && !a.isNull("original") },
        year = if (a.has("year") && !a.isNull("year")) a.optInt("year").takeIf { it in 1880..2100 } else null
    )
}

/** When this user may ask the AI again (server ms, 0 — now): top-level `aiTitleReadyAt` of /api/library. */
fun aiTitleReadyAt(json: JSONObject): Long = json.optLong("aiTitleReadyAt", 0L)

fun parseLibrary(json: JSONObject): List<Magnet> {
    val arr = json.optJSONArray("magnets") ?: return emptyList()
    val out = ArrayList<Magnet>(arr.length())
    for (i in 0 until arr.length()) {
        val m = arr.optJSONObject(i) ?: continue
        val filesArr = m.optJSONArray("files")
        val files = ArrayList<MFile>()
        if (filesArr != null) for (j in 0 until filesArr.length()) {
            val f = filesArr.optJSONObject(j) ?: continue
            files += MFile(
                index = j,
                name = f.optString("name"),
                path = f.optString("path"),
                isFolder = f.optString("type") == "folder",
                size = f.optLong("size", 0L),
                link = f.optString("sourceLink").takeIf { it.startsWith("https://") }
            )
        }
        out += Magnet(
            id = m.optLong("id"),
            name = m.optString("name").ifBlank { "Magnet ${m.optLong("id")}" },
            status = m.optString("status"),
            statusCode = m.optInt("statusCode", 0),
            size = m.optLong("size", 0L),
            downloaded = m.optLong("downloaded", 0L),
            files = files,
            canRemove = m.optBoolean("canRemove", true),
            favorite = m.optBoolean("favorite", false),
            mine = m.optBoolean("mine", false),
            aiTitle = parseAiTitle(m.optJSONObject("aiTitle")),
            canAiTitle = m.optBoolean("canAiTitle", false)
        )
    }
    return out
}

// ---- the same rules as the website (buildPlaylist / parseEpisode / escapeTitle) ----------------

private val VIDEO_EXT = Regex("""\.(mkv|mp4|avi|mov|webm|m4v|m2ts|ts|iso)$""", RegexOption.IGNORE_CASE)
private val SAMPLE = Regex("""(^|[/._ -])sample([/._ -]|$)""", RegexOption.IGNORE_CASE)
// Compiled once: building playlists for a few hundred torrents must not recompile patterns per file.
private val IC = RegexOption.IGNORE_CASE
private val NAT_TOKEN = Regex("""\d+|\D+""")
private val DIR_SEASON = Regex("""(?:^|/)(?:season|сезон)[ ._-]?(\d{1,2})(?:/|$)""", IC)
private val DIR_S = Regex("""(?:^|/)S(\d{1,2})(?:/|$)""", IC)
private val ANY_SEASON = Regex("""(?:season|сезон)[ ._-]?(\d{1,2})""", IC)
private val SXXEYY = Regex("""S(\d{1,2})[ ._-]?E(\d{1,3})""", IC)
private val NXNN = Regex("""(?:^|[^\d])(\d{1,2})x(\d{2,3})(?!\d)""", IC)
private val EP_WORD = Regex("""(?:^|[^a-z0-9а-я])(?:e|ep|episode|серия)[ ._-]?(\d{1,3})(?!\d)""", IC)
private val LEAD_NUM = Regex("""^\s*(\d{1,3})(?=[ ._-])""")
// One season in the release's name: «Show.S02.1080p», «Сезон: 2», «2-й сезон», «Season 2»; ranges («S01-S03», «Сезон: 1-3»,
// «1-3 сезон») say nothing. Without lookbehind and \p{L}: the website and the TV (Chromium 56) use the same patterns.
private val NAME_SEASON_RANGE = Regex(
    """(?:season|сезон)[a-zа-яё]*[ ._:]*\d{1,2}[ ._]*[-–][ ._]*\d|\d[ ._]*[-–][ ._]*\d{1,2}[ ._]*(?:-?[a-zа-яё]{1,2}[ ._]*)?(?:season|сезон)|(?:^|[^a-zа-яё0-9])s\d{1,2}[ ._]*[-–][ ._]*s?\d""", IC
)
private val NAME_SEASON = Regex(
    """(?:^|[^a-zа-яё0-9])s(\d{1,2})(?=e\d|[^a-zа-яё0-9]|$)|(?:season|сезон)[ ._:]*(\d{1,2})(?!\d)|(?:^|\D)(\d{1,2})[ ._]*(?:-?(?:й|ый|ий|st|nd|rd|th))?[ ._]*(?:season|сезон)(?![a-zа-яё0-9])""", IC
)
private val DOTS = Regex("[._]+")
private val BRACKETS = Regex("""[\[\](){}]""")
private val SPACES = Regex("""\s+""")

data class Episode(val season: Int, val episode: Int, val file: MFile, val title: String? = null) // title: film of a collection
data class Season(val season: Int, val items: List<Episode>)

sealed class Playable {
    data class Movie(val file: MFile) : Playable()
    data class Series(val seasons: List<Season>) : Playable()
    object None : Playable()
}

private fun basename(path: String) = path.replace('\\', '/').substringAfterLast('/')

/** Natural order: "Episode 2" before "Episode 10" (like localeCompare with numeric: true). */
private val natural = Comparator<String> { a, b ->
    val ra = NAT_TOKEN.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
    val rb = NAT_TOKEN.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
    for (i in 0 until minOf(ra.size, rb.size)) {
        val x = ra[i]; val y = rb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            val nx = x.trimStart('0'); val ny = y.trimStart('0')
            if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
        } else x.compareTo(y)
        if (c != 0) return@Comparator c
    }
    ra.size - rb.size
}

private fun seasonOf(path: String): Int? {
    val p = path.replace('\\', '/')
    val m = DIR_SEASON.find(p) ?: DIR_S.find(p)
    return m?.groupValues?.get(1)?.toInt()
}

/**
 * The season the release's name gives, if it names exactly one ([NAME_SEASON]). Episodes whose files do not say their
 * season («Show.S02/Show.E01.mkv», «Сериал (2 сезон)/01 [1080p].mkv») take it instead of season 1, or a merged show
 * would call its second season «Сезон 1» on the banner and in the version picker.
 */
fun nameSeason(name: String): Int? {
    if (NAME_SEASON_RANGE.containsMatchIn(name)) return null
    val nums = NAME_SEASON.findAll(name).mapNotNull { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toInt() }.toSet()
    return nums.singleOrNull()?.takeIf { it > 0 }
}

private fun parseEpisode(path: String, defaultSeason: Int = 1): Pair<Int, Int>? {
    val p = path.replace('\\', '/')
    val name = p.substringAfterLast('/')
    val folderSeason = (ANY_SEASON.find(p) ?: DIR_S.find(p))?.groupValues?.get(1)?.toInt()
    (SXXEYY.find(name) ?: SXXEYY.find(p))
        ?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    NXNN.find(name)
        ?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    EP_WORD.find(name)
        ?.let { return (folderSeason ?: defaultSeason) to it.groupValues[1].toInt() }
    if (folderSeason != null) {
        LEAD_NUM.find(name)?.let { return folderSeason to it.groupValues[1].toInt() }
    }
    return null
}

fun mainVideo(m: Magnet): MFile? {
    val vids = m.files.filter { !it.isFolder && VIDEO_EXT.containsMatchIn(it.path) }
    val real = vids.filter { !it.path.endsWith(".iso", ignoreCase = true) }
    return (real.ifEmpty { vids }).maxByOrNull { it.size }
}

/** What to play: from the files, then the kind and season the owner accepted from the AI ([applyAiTitle]). */
fun buildPlayable(m: Magnet): Playable = applyAiTitle(playableByFiles(m), m)

// Word boundaries by letters and digits of any script. Java's \b (JDK 19+) treats Cyrillic as non-word, so
// "\bтрилогия\b" never matched on the PC while Android (ICU) matched it: the same torrent was a show on one, a trilogy on the other.
private const val NB = """(?<![\p{L}\p{N}])"""
private const val NA = """(?![\p{L}\p{N}])"""
/** Words that name a set of films («трилогия», «Trilogy», «3 в 1», «все части», «8 фильмов»): a collection unless the name says season. */
private val COLLECTION_STRONG = Regex(
    NB + """(?:трилоги[яи]|дилоги[яи]|тетралоги[яи]|квадр[ио]логи[яи]|пенталоги[яи]|гексалоги[яи]|франшиз[аеы]|киносаг[аеиы]|trilogy|duology|dilogy|tetralogy|quadrilogy|franchise|\d{1,2}\s*[-–—]?\s*(?:в|in)\s*1|все\s+части|все\s+фильмы|\d{1,2}\s+фильм(?:а|ов))""" + NA,
    IC
)
/**
 * Words that name a set of films or a whole show («Сага о Винланде», «Complete Collection», an anthology series): they count
 * only when the files are films of different years.
 */
private val COLLECTION_WEAK = Regex(NB + """(?:коллекци[яи]|сборник|антологи[яи]|саг[аеиы]|boxset|box[- ]set|collection|anthology|saga)""" + NA, IC)
/** Both kinds of words: stripped from a collection's name when its films are titled. */
private val COLLECTION_WORDS = Regex(COLLECTION_STRONG.pattern + "|" + COLLECTION_WEAK.pattern, IC)

/** Every file has a year in its name and the years differ: films of a franchise, not episodes. */
private fun filmsByYear(files: List<MFile>): Boolean {
    val years = files.map { YEAR_IN_NAME.find(basename(it.path))?.value ?: return false }
    return years.toSet().size >= 2
}

/** The torrent's name says it is a set of films ([COLLECTION_STRONG], or a weak word with films by year) and not a season. */
private fun namedCollection(m: Magnet, files: List<MFile>): Boolean =
    !SERIES_HINT.containsMatchIn(m.name) &&
        (COLLECTION_STRONG.containsMatchIn(m.name) || (COLLECTION_WEAK.containsMatchIn(m.name) && filmsByYear(files)))

/**
 * A set of films played as a list: the owner accepted «сборник» from the AI (and «сериал» / «фильм» from it rule it out),
 * the films are titled ([collectionOf]), or the name says so ([COLLECTION_STRONG] without season marks).
 */
fun isCollection(p: Playable, m: Magnet? = null): Boolean {
    m?.aiTitle?.takeIf { it.accepted }?.kind?.let { return it == "collection" }
    if (p !is Playable.Series) return false
    if (p.seasons.firstOrNull()?.items?.firstOrNull()?.title != null) return true
    return m != null && COLLECTION_STRONG.containsMatchIn(m.name) && !SERIES_HINT.containsMatchIn(m.name)
}

/** What kind of a set of films a collection is; [trKey] — its string in the apps' strings/ru.json. */
enum class CollectionKind(val label: String, val trKey: String) {
    Trilogy("Кинотрилогия", "library.kinotrilogiya"),
    Dilogy("Дилогия", "library.dilogiya"),
    Tetralogy("Кинотетралогия", "library.kinotetralogiya"),
    Compilation("Сборник", "library.sbornik"),
    Anthology("Антология", "library.antologiya"),
    Collection("Коллекция", "library.kollektsiya"),
    Franchise("Кинофраншиза", "library.kinofranshiza")
}

private val KIND_TRILOGY = Regex(NB + """(?:трилоги[яи]|trilogy)""" + NA, IC)
private val KIND_DILOGY = Regex(NB + """(?:дилоги[яи]|duology|dilogy)""" + NA, IC)
private val KIND_TETRALOGY = Regex(NB + """(?:квадр[ио]логи[яи]|тетралоги[яи]|quadrilogy|tetralogy)""" + NA, IC)
private val KIND_COMPILATION = Regex(NB + """сборник""" + NA, IC)
private val KIND_ANTHOLOGY = Regex(NB + """(?:антологи[яи]|anthology)""" + NA, IC)
private val KIND_COLLECTION = Regex(NB + """(?:коллекци[яи]|collection|boxset|box[- ]set)""" + NA, IC)

/** The words of the name first («Дилогия» with three files is still a dilogy), then the number of films. */
fun collectionKind(filmCount: Int, name: String = ""): CollectionKind = when {
    KIND_TRILOGY.containsMatchIn(name) -> CollectionKind.Trilogy
    KIND_DILOGY.containsMatchIn(name) -> CollectionKind.Dilogy
    KIND_TETRALOGY.containsMatchIn(name) -> CollectionKind.Tetralogy
    filmCount == 2 -> CollectionKind.Dilogy
    filmCount == 3 -> CollectionKind.Trilogy
    filmCount == 4 -> CollectionKind.Tetralogy
    KIND_COMPILATION.containsMatchIn(name) -> CollectionKind.Compilation
    KIND_ANTHOLOGY.containsMatchIn(name) -> CollectionKind.Anthology
    KIND_COLLECTION.containsMatchIn(name) -> CollectionKind.Collection
    else -> CollectionKind.Franchise
}

/** Russian plural: pluralRu(3, "фильм", "фильма", "фильмов") = "фильма". */
fun pluralRu(n: Int, one: String, few: String, many: String): String = when {
    n % 10 == 1 && n % 100 != 11 -> one
    n % 10 in 2..4 && n % 100 !in 12..14 -> few
    else -> many
}

fun filmCountRu(count: Int): String = "$count " + pluralRu(count, "фильм", "фильма", "фильмов")

/** Films of a collection, in play order (empty for anything else). */
fun collectionFilms(item: LibraryItem): List<Episode> =
    if (isCollection(item.playable, item.m)) (item.playable as? Playable.Series)?.seasons?.flatMap { it.items }.orEmpty() else emptyList()

val TRACK_PREFIX = Regex(
    """^\s*(?:\[[^\]]*\]\s*|\([^\)]*\)\s*|(?:фильм|серия|часть|part|movie|vol|cd|диск)?\s*\d{1,3}[\s._-]*)+""",
    RegexOption.IGNORE_CASE
)

fun cleanCollectionFilmTitle(raw: String): String {
    val withoutExt = raw.replace(EXT, "")
    val stripped = withoutExt.replace(TRACK_PREFIX, "").trim()
    val cleaned = cleanTitle(stripped.ifBlank { withoutExt })
    return cleaned.ifBlank { cleanTitle(raw) }
}

private val FILM_COUNT_NOISE = Regex(NB + """\d+\s*(?:фильм\p{L}*|сери\p{L}*|част\p{L}*)""" + NA, IC)

/** The collection's own titles («Гарри Поттер», «Harry Potter») without «Трилогия», «8 фильмов» and the part after «:». */
private fun collectionBases(baseTitle: String): List<String> =
    baseTitle.replace(COLLECTION_WORDS, " ").split('/').map {
        cleanTitle(it).substringBefore(':').replace(FILM_COUNT_NOISE, "").replace(SPACES, " ").trim()
    }.filter { it.isNotBlank() }

private fun collectionBase(baseTitle: String): String = collectionBases(baseTitle).firstOrNull().orEmpty()

fun collectionFileTitle(m: Magnet, file: MFile, baseTitle: String = cleanTitle(m.name)): String {
    val raw = file.name.ifBlank { file.path.substringAfterLast('/') }
    val cleaned = cleanCollectionFilmTitle(raw)
    val coreBase = collectionBase(baseTitle)
    return when {
        cleaned.isNotBlank() && coreBase.isNotBlank() && !cleaned.contains(coreBase, ignoreCase = true) ->
            "$coreBase: $cleaned"
        cleaned.isNotBlank() -> cleaned
        else -> cleanTitle(raw)
    }
}

/**
 * Poster lookups for one film of a collection, best first and at most [MAX_FILM_QUERIES]: every miss is a few more
 * TMDB requests on the server, so no long list of combinations. The film with its year, the film, the franchise and film,
 * the franchise (each of its titles), the torrent.
 */
fun collectionFilePosterQueries(m: Magnet, file: MFile, baseTitle: String = cleanTitle(m.name)): List<String> {
    val raw = file.name.ifBlank { file.path.substringAfterLast('/') }
    val withoutExt = raw.replace(EXT, "")
    val year = YEAR_IN_NAME.find(withoutExt)?.value ?: YEAR_IN_NAME.find(file.path)?.value
    val film = cleanTitle(withoutExt.replace(TRACK_PREFIX, "").trim().ifBlank { withoutExt }).split('/').map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    val base = collectionBase(baseTitle)
    val withYear = { q: String -> if (year != null) "$q ($year)" else q }
    val out = LinkedHashSet<String>()
    if (film.isNotBlank()) { out += withYear(film); out += film }
    if (base.length >= 2 && film.isNotBlank() && !film.contains(base, ignoreCase = true)) { out += withYear("$base: $film"); out += "$base: $film" }
    collectionBases(baseTitle).filter { it.length >= 2 }.forEach { out += it }
    out += cleanTitle(m.name)
    return out.take(MAX_FILM_QUERIES)
}

private const val MAX_FILM_QUERIES = 6

fun continueItemPosterQueries(item: LibraryItem, file: MFile): List<String> {
    if (isCollection(item.playable, item.m)) {
        return collectionFilePosterQueries(item.m, file, item.baseTitle)
    }
    return item.posterQueries
}

fun continueItemTitle(item: LibraryItem, file: MFile): String {
    if (isCollection(item.playable, item.m)) {
        return collectionFileTitle(item.m, file, item.baseTitle)
    }
    return item.baseTitle
}

/**
 * The kind and season the owner accepted from the AI (server services/aititle.js) over what the files say:
 * "series" — the videos in name order are the episodes of one season, "collection" — separate films, "movie" — the main
 * file. The season is applied only when the release has one season (a show with season folders keeps its own numbers).
 */
fun applyAiTitle(p: Playable, m: Magnet): Playable {
    val ai = m.aiTitle?.takeIf { it.accepted } ?: return p
    val vids = m.files.filter { !it.isFolder && VIDEO_EXT.containsMatchIn(it.path) && !SAMPLE.containsMatchIn(it.path) }
    val main = vids.filter { !EXTRAS.containsMatchIn(it.path) }.ifEmpty { vids }
    val sorted = { main.sortedWith(compareBy(natural) { basename(it.path) }) }
    var out = p
    when (ai.kind) {
        "movie" -> if (p is Playable.Series) mainVideo(m)?.let { out = Playable.Movie(it) }
        "series" -> if ((p is Playable.Movie || isCollection(p)) && main.size >= 2) {
            val s = ai.season?.takeIf { it > 0 } ?: 1
            out = Playable.Series(listOf(Season(s, sorted().mapIndexed { i, f -> Episode(s, i + 1, f) })))
        }
        "collection" -> if ((p is Playable.Movie || (p is Playable.Series && !isCollection(p))) && main.size >= 2) {
            out = Playable.Series(listOf(Season(1, sorted().mapIndexed { i, f ->
                Episode(1, i + 1, f, cleanTitle(basename(f.path)).ifBlank { "Фильм ${i + 1}" })
            })))
        }
    }
    val s = ai.season
    val one = (out as? Playable.Series)?.seasons?.singleOrNull()
    if (s != null && s > 0 && one != null && !isCollection(out) && one.season != s) {
        out = Playable.Series(listOf(Season(s, one.items.map { it.copy(season = s) })))
    }
    return out
}

private fun playableByFiles(m: Magnet): Playable {
    val vids = m.files.filter { !it.isFolder && VIDEO_EXT.containsMatchIn(it.path) && !SAMPLE.containsMatchIn(it.path) }
    // Directory structure first: many releases name episodes without SxxEyy.
    val bySeason = LinkedHashMap<Int, MutableList<MFile>>()
    for (f in vids) { val s = seasonOf(f.path) ?: continue; bySeason.getOrPut(s) { ArrayList() }.add(f) }
    if (bySeason.size >= 2 || (bySeason.size == 1 && vids.size > 1)) {
        val seasons = bySeason.entries.sortedBy { it.key }.map { (s, files) ->
            Season(s, files.sortedWith(compareBy(natural) { basename(it.path) })
                .mapIndexed { i, f -> Episode(s, i + 1, f) })
        }
        return Playable.Series(seasons)
    }
    // Files without their own season mark get the one the release's name gives
    val named = nameSeason(m.name) ?: 1
    val eps = vids.mapNotNull { f -> parseEpisode(f.path, named)?.let { (s, e) -> Episode(s, e, f) } }
    if (eps.size >= 2) {
        val seasons = eps.groupBy { it.season }.entries.sortedBy { it.key }.map { (s, items) ->
            Season(s, items.sortedWith(compareBy<Episode> { it.episode }.thenBy { it.file.path }))
        }
        return Playable.Series(seasons)
    }
    // Episodes named only by a number ("Show - 01 [1080p].mkv"): a series, not a collection of films.
    numberedEpisodesOf(m, vids, named)?.let { return it }
    // Collection (trilogy, several films / parts in one torrent, no episode numbers): every full-size film is listed.
    collectionOf(vids)?.let { return it }
    return mainVideo(m)?.let { Playable.Movie(it) } ?: Playable.None
}

private val NUM_NOISE = Regex("""(?:2160|1080|720|480|576)[pi]|[xh][ .]?26[45]|\d+(?:[.,]\d+)?\s?(?:fps|kbps|mbps|bit|gb|mb)|(?<!\d)(?:19|20)\d{2}(?!\d)|(?<!\d)[1-9]\.[01](?!\d)""", IC)
private val NUM = Regex("""(?<!\d)\d{1,3}(?!\d)""")
private val SERIES_HINT = Regex("""(?<![\p{L}\p{N}])(?:сезон\p{L}*|season\p{L}*|серии|серия|series|episodes?|эпизод\p{L}*|s\d{1,2}|ova|ona)(?![\p{L}\p{N}])""", IC)

/**
 * Files that differ only by one number ("Show - 01", "Show - 02" …) are episodes when the torrent says so (season / series
 * in its name) or looks like a season: five or more files of similar size numbered one after another.
 * Without that a trilogy ("Film 1", "Film 2") stays a collection.
 */
private fun numberedEpisodesOf(m: Magnet, vids: List<MFile>, season: Int): Playable.Series? {
    if (vids.size < 2 || vids.any { BLURAY_DIR.containsMatchIn(it.path) }) return null
    val main = vids.filter { !EXTRAS.containsMatchIn(it.path) }
    if (main.size < 2) return null
    // «Пила: Трилогия», «Коллекция 1996-2018» with films by year: films, not episodes ([collectionOf] takes them)
    if (namedCollection(m, main)) return null
    val parsed = main.map { f ->
        val base = basename(f.path).replace(EXT, "").replace(DOTS, " ").replace(NUM_NOISE, " ")
        base to NUM.findAll(base).toList()
    }
    val slots = parsed.minOf { it.second.size }
    for (slot in 0 until slots) {
        val nums = parsed.map { it.second[slot].value.toInt() }
        if (nums.toSet().size != nums.size) continue
        val templates = parsed.map { (b, ms) -> b.replaceRange(ms[slot].range, "#") }
        if (templates.toSet().size != 1) continue
        val sorted = nums.sorted()
        val consecutive = sorted.zipWithNext().all { (a, b) -> b - a == 1 }
        val sizes = main.map { it.size }
        val similar = sizes.max() <= sizes.min() * 2
        val looksLikeSeason = main.size >= 5 && consecutive && similar
        if (!SERIES_HINT.containsMatchIn(m.name) && !looksLikeSeason) continue
        val eps = main.mapIndexed { i, f -> Episode(season, nums[i], f) }.sortedBy { it.episode }
        return Playable.Series(listOf(Season(season, eps)))
    }
    return null
}

private val EXTRAS = Regex("""(?:^|/)(?:extras?|bonus|featurettes?|behind[ ._-]?the[ ._-]?scenes|trailers?|доп[^/]*|бонус[^/]*|допы)(?:/|$)""", RegexOption.IGNORE_CASE)
private val BLURAY_DIR = Regex("""(?:^|/)(?:BDMV|STREAM|VIDEO_TS)(?:/|$)""", RegexOption.IGNORE_CASE)

/** Several feature-length files (each at least 1/4 of the largest and 300 MB) outside Blu-ray/DVD folders. */
private fun collectionOf(vids: List<MFile>): Playable.Series? {
    if (vids.size < 2 || vids.any { BLURAY_DIR.containsMatchIn(it.path) }) return null
    val main = vids.filter { !EXTRAS.containsMatchIn(it.path) }
    val largest = main.maxOfOrNull { it.size } ?: return null
    val films = main.filter { it.size >= 300L * 1024 * 1024 && it.size >= largest / 4 }
    if (films.size < 2) return null
    val sorted = films.sortedWith(compareBy(natural) { basename(it.path) })
    val items = sorted.mapIndexed { i, f -> Episode(1, i + 1, f, cleanTitle(basename(f.path)).ifBlank { "Фильм ${i + 1}" }) }
    return Playable.Series(listOf(Season(1, items)))
}

private val EXT = Regex("""\.[a-z0-9]{2,5}$""", RegexOption.IGNORE_CASE)
private val TAGS = Regex(
    """\b(2160p|1080p|720p|480p|4k|8k|web[- ]?dl|web[- ]?rip|bluray|blu[- ]?ray|bdrip|bdremux|remux|hdr10?\+?|dv|dovi|hevc|h\.?265|x265|h\.?264|x264|aac|dts|ddp?5?\.?1|proper|repack|extended|limited|internal|amzn|nf|dsnp|hmax|yts|rarbg)\b.*$""",
    RegexOption.IGNORE_CASE
)
private val YEAR_TAIL = Regex("""\b(19|20)\d{2}\b.*$""")

/** Release name -> plain title for the poster search (same as the site's escapeTitle). */
fun cleanTitle(value: String): String {
    var s = value.ifBlank { "Untitled" }.replace(EXT, "").replace(DOTS, " ")
    s = s.replace(TAGS, "")
    s = s.replace(YEAR_TAIL, " ").replace(BRACKETS, " ").replace(SPACES, " ").trim()
    return s.ifBlank { value.replace(DOTS, " ").trim() }
}

private val SEASON_NOISE = Regex(
    """(?<![\p{L}\p{N}])(?:\d{1,2}(?:\s*[-–]\s*\d{1,2})?\s*(?:сезон\p{L}*|season\p{L}*)|(?:сезон\p{L}*|season\p{L}*|серии|серия|series|episodes?)\s*\d{1,3}(?:\s*[-–]\s*\d{1,3})?|s\d{1,2}(?:\s*[-–]?\s*[se]\d{1,3})?|complete|полный|полностью|(?:сезон\p{L}*|season\p{L}*|серии|episodes))(?![\p{L}\p{N}])""",
    IC
)
private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

/**
 * Key of «the same title»: the release's plain title without season / episode marks, so "Show S01", "Show Season 2"
 * and "Show [1-3 сезон]" are one show. Different seasons and releases of it share the key.
 */
fun titleKey(baseTitle: String): String {
    val plain = baseTitle.lowercase(Locale.ROOT).replace('ё', 'е')
    val key = plain.replace(SEASON_NOISE, " ").replace(NON_WORD, " ").trim()
    return key.ifBlank { plain.replace(NON_WORD, " ").trim() }
}

fun formatBytes(value: Long): String {
    if (value <= 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = value.toDouble(); var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (i == 0) "$value B" else String.format(Locale.US, "%.1f %s", v, units[i])
}

fun statusRu(m: Magnet): String = when {
    m.ready -> "Готово"
    m.failed -> m.status.ifBlank { "Ошибка" }
    m.status.equals("Downloading", true) -> "Загрузка"
    m.status.isBlank() -> "Обработка"
    else -> m.status
}

/**
 * The title under a tile: the AI's one once the user accepted it, else the Russian title TMDB gave for a poster query
 * ([ruTitleOf] takes the lower-case query; the server's `title_ru`), else the release name. A collection keeps its own:
 * TMDB finds one film of it.
 */
fun tileTitle(item: LibraryItem, ruTitleOf: (String) -> String?): String {
    if (item.m.aiTitle?.accepted == true || isCollection(item.playable, item.m)) return item.baseTitle
    return item.posterQueries.firstNotNullOfOrNull { q -> ruTitleOf(q.lowercase())?.takeIf { it.isNotBlank() } } ?: item.baseTitle
}

fun episodeLabel(base: String, s: Season, e: Episode): String = e.title ?:
    base + " · " + (if (s.season == 0) "Спец." else "S" + s.season.toString().padStart(2, '0')) + "E" + e.episode.toString().padStart(2, '0')

/** Everything a tile needs, computed once per library load off the main thread (not while scrolling). */
data class LibraryItem(
    val m: Magnet,
    val playable: Playable,
    val baseTitle: String,
    val posterQueries: List<String>,
    val searchText: String
) {
    /** Same item with the AI's answer changed (accepted / rejected): title and poster queries follow, the rest stays. */
    fun withAiTitle(ai: AiTitle?): LibraryItem {
        val nm = m.copy(aiTitle = ai)
        val base = titleOf(nm)
        return copy(m = nm, playable = buildPlayable(nm), baseTitle = base, posterQueries = posterQueriesOf(nm, base), searchText = searchTextOf(nm))
    }
}

/** What the library search looks in: the release name, its files and, once accepted, the AI's title and original title. */
private fun searchTextOf(m: Magnet): String {
    val ai = m.aiTitle?.takeIf { it.accepted }
    return (m.name + "\n" + m.files.joinToString("\n") { it.path } + (ai?.let { "\n" + it.base + "\n" + it.original.orEmpty() } ?: "")).lowercase()
}

/** The tile's title: the AI's one once the owner accepted it (services/aititle.js), otherwise the regexes'. */
private fun titleOf(m: Magnet): String = m.aiTitle?.takeIf { it.accepted }?.base ?: cleanTitle(m.name)

/** Poster lookups in order: the AI's original title with year when accepted («Dune (2021)»), the tile's title, the main file. */
private fun posterQueriesOf(m: Magnet, base: String): List<String> {
    val main = mainVideo(m)
    val ai = m.aiTitle?.takeIf { it.accepted }?.posterQuery
    return listOfNotNull(ai, base, main?.let { cleanTitle(it.name.ifBlank { it.path.substringAfterLast('/') }) }).distinct()
}

fun prepareItems(list: List<Magnet>): List<LibraryItem> = list.map { m ->
    val base = titleOf(m)
    LibraryItem(
        m = m,
        playable = buildPlayable(m),
        baseTitle = base,
        posterQueries = posterQueriesOf(m, base),
        searchText = searchTextOf(m)
    )
}

private fun episodesRu(n: Int) = "$n " + pluralRu(n, "серия", "серии", "серий")

/** «Сезоны 1–3 · 24 серии» for a series row of the picker («3 фильма» for a collection). */
fun seriesLine(p: Playable.Series): String {
    val nums = p.seasons.map { it.season }.filter { it > 0 }
    val eps = p.seasons.sumOf { it.items.size }
    if (p.seasons.firstOrNull()?.items?.firstOrNull()?.title != null) return filmCountRu(eps)
    val range = when {
        nums.isEmpty() -> ""
        nums.size == 1 -> "Сезон ${nums.first()} · "
        else -> "Сезоны ${nums.min()}–${nums.max()} · "
    }
    return "$range${episodesRu(eps)}"
}

private val YEAR_IN_NAME = Regex("""\b(19\d{2}|20\d{2})\b""")
private val YEAR_BRACKETED = Regex("""[(\[{](19\d{2}|20\d{2})[)\]}]""")
private val SEASON_RANGE_REGEX = Regex(
    """(?:season|сезон)[s\p{L}]*[\s._-]*(\d{1,2})[\s._-]*[-–][\s._-]*(\d{1,2})|(\d{1,2})[\s._-]*[-–][\s._-]*(\d{1,2})[\s._-]*(?:season|сезон)[s\p{L}]*""",
    IC
)
/** «Part 2», «Часть 3», «Фильм 1», «Chapter II» (not «8 фильмов»). */
private val PART_EXPLICIT = Regex(NB + """(?:часть|ч\.|фильм|film|part|pt\.?|глава|chapter)\s*(\d{1,2}|[ivx]{1,4})""" + NA, IC)
/** «2-я часть», «3 фильм». */
private val PART_ORDINAL = Regex(NB + """(\d{1,2})\s*[-–]?\s*(?:я|ая|ья|ей|й|ый|ой)?\s*(?:часть|фильм)""" + NA, IC)
private val LATEST_YEAR = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 2

data class VersionSection(
    val title: String?,
    val items: List<LibraryItem>
)

/** The release year (1900..next year + 1) from text or a file name: «(2001)» first, then a standalone year. */
fun extractYear(name: String): Int? {
    YEAR_BRACKETED.find(name)?.let { m -> m.groupValues[1].toInt().takeIf { it <= LATEST_YEAR }?.let { return it } }
    return YEAR_IN_NAME.findAll(name).map { it.groupValues[1].toInt() }.firstOrNull { it <= LATEST_YEAR }
}

fun releaseYear(item: LibraryItem): Int? {
    item.m.aiTitle?.takeIf { it.accepted }?.year?.let { return it }
    extractYear(item.m.name)?.let { return it }
    extractYear(item.baseTitle)?.let { return it }
    return item.m.files.firstNotNullOfOrNull { extractYear(it.name.ifBlank { it.path }) }
}

private fun parseRoman(s: String): Int? = when (s.uppercase(Locale.ROOT)) {
    "I" -> 1
    "II" -> 2
    "III" -> 3
    "IV" -> 4
    "V" -> 5
    "VI" -> 6
    "VII" -> 7
    "VIII" -> 8
    "IX" -> 9
    "X" -> 10
    else -> null
}

/**
 * Explicit part or film number: «Part 2», «Часть 3», «2-я часть», or a number / Roman numeral right after the title
 * («Rocky IV», «Тачки 2»). A Roman numeral elsewhere in the name is not a part: «X-Men» is not part 10.
 */
fun itemPart(item: LibraryItem): Int? {
    val text = item.m.name
    PART_EXPLICIT.find(text)?.groupValues?.get(1)?.let { raw -> (raw.toIntOrNull() ?: parseRoman(raw))?.let { return it } }
    PART_ORDINAL.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    if (item.baseTitle.isNotBlank()) {
        val afterBase = Regex(NB + Regex.escape(item.baseTitle) + """\s+(\d{1,2}|[IVX]{1,4})""" + NA, IC).find(text)
        afterBase?.groupValues?.get(1)?.let { raw -> (raw.toIntOrNull() ?: parseRoman(raw))?.let { return it } }
    }
    return null
}

/** Quality score: 4K (4), 1080p (3), 720p (2), SD (1), other (0). */
fun qualityScore(name: String): Int {
    val lower = name.lowercase(Locale.ROOT)
    return when {
        lower.contains("2160p") || lower.contains("4k") || lower.contains("3840") -> 4
        lower.contains("1080p") || lower.contains("1080i") || lower.contains("1920") -> 3
        lower.contains("720p") || lower.contains("1280") -> 2
        lower.contains("480p") || lower.contains("576p") || lower.contains("dvd") -> 1
        else -> 0
    }
}

/** Seasons covered by this item: Pair(minSeason, maxSeason) or null if not a series. */
fun itemSeasons(item: LibraryItem): Pair<Int, Int>? {
    item.m.aiTitle?.takeIf { it.accepted }?.season?.let { return it to it }
    val series = item.playable as? Playable.Series
    if (series != null) {
        val first = series.seasons.firstOrNull()?.items?.firstOrNull()
        if (first?.title != null) return null // film collection (trilogy), not a TV series
        val nums = series.seasons.map { it.season }
        if (nums.isNotEmpty()) return nums.min() to nums.max()
    }
    val rangeMatch = SEASON_RANGE_REGEX.find(item.m.name)
    if (rangeMatch != null) {
        val min = rangeMatch.groupValues[1].ifEmpty { rangeMatch.groupValues[3] }.toIntOrNull()
        val max = rangeMatch.groupValues[2].ifEmpty { rangeMatch.groupValues[4] }.toIntOrNull()
        if (min != null && max != null) return min to max
    }
    val singleMatch = ANY_SEASON.find(item.m.name) ?: SXXEYY.find(item.m.name)
    if (singleMatch != null) {
        val s = singleMatch.groupValues[1].toIntOrNull()
        if (s != null) return s to s
    }
    val file = (item.playable as? Playable.Movie)?.file
    if (file != null) {
        seasonOf(file.path)?.let { return it to it }
        parseEpisode(file.path)?.let { return it.first to it.first }
    }
    return null
}

/** What a version is sorted and sectioned by: computed once per item, not on every comparison. */
private class VersionKey(val item: LibraryItem) {
    val seasons = itemSeasons(item)
    val part = itemPart(item)
    val year = releaseYear(item)
    val quality = qualityScore(item.m.name)
}

/**
 * Seasons in order (specials after them, then what has no season), then part, then year (what lacks one goes after),
 * then the best quality and the biggest size. One key compared field by field, so the order is total: a comparator that
 * skips a field when one side lacks it can loop (A < B < C < A) and TimSort then throws on long lists.
 */
private val VERSION_ORDER: Comparator<VersionKey> = compareBy<VersionKey>(
    { k -> k.seasons?.first?.let { if (it == 0) Int.MAX_VALUE - 1 else it } ?: Int.MAX_VALUE },
    { k -> k.seasons?.let { it.second - it.first } ?: 0 },
    { k -> k.part ?: Int.MAX_VALUE },
    { k -> k.year ?: Int.MAX_VALUE },
    { k -> -k.quality },
    { k -> -k.item.m.size },
    { k -> k.item.m.name },
    { k -> k.item.m.id }
)

private fun sortedKeys(versions: List<LibraryItem>): List<VersionKey> = versions.map(::VersionKey).sortedWith(VERSION_ORDER)

/**
 * Sorts versions: series by season (Season 1 < Season 2; specials at the end), films and trilogies by part and year
 * (Part 1 < Part 2, 2001 < 2002), then the best quality (4K > 1080p > 720p) and the largest size (higher bitrate).
 */
fun sortVersions(versions: List<LibraryItem>): List<LibraryItem> =
    if (versions.size <= 1) versions else sortedKeys(versions).map { it.item }

/**
 * Sorted versions in sections («Сезон 1», «Сезон 2» or «2001 год», «2002 год»). When all of them are the same season
 * or the same film, one section with [VersionSection.title] = null.
 */
fun versionSections(versions: List<LibraryItem>): List<VersionSection> {
    if (versions.isEmpty()) return emptyList()
    val keys = sortedKeys(versions)
    val sorted = keys.map { it.item }
    val titles = if (keys.any { it.seasons != null }) {
        keys.map { k ->
            val s = k.seasons
            when {
                s == null -> "Дополнительно"
                s.first == 0 -> "Спецвыпуски"
                s.first == s.second -> "Сезон ${s.first}"
                else -> "Сезоны ${s.first}–${s.second}"
            }
        }
    } else {
        if (keys.mapNotNull { it.year }.distinct().size <= 1 && keys.mapNotNull { it.part }.distinct().size <= 1) {
            return listOf(VersionSection(null, sorted))
        }
        keys.map { k ->
            when {
                k.year != null && k.part != null -> "${k.year} год · Часть ${k.part}"
                k.year != null -> "${k.year} год"
                k.part != null -> "Часть ${k.part}"
                else -> "Другая версия"
            }
        }
    }
    if (titles.distinct().size <= 1) return listOf(VersionSection(null, sorted))
    val groups = LinkedHashMap<String, MutableList<LibraryItem>>()
    for (i in sorted.indices) groups.getOrPut(titles[i]) { ArrayList() }.add(sorted[i])
    return groups.map { (title, items) -> VersionSection(title, items) }
}

/**
 * First line of a row in the «which version» picker, without quality: «Кинотрилогия • 3 фильма», «Сезоны 1–3 · 24 серии»,
 * «2021 год». Inside a section its title already says the season or the year, so only what it does not say is left.
 */
fun versionLine(v: LibraryItem, inSection: Boolean): String {
    val films = collectionFilms(v)
    if (films.isNotEmpty()) return collectionKind(films.size, v.m.name).label + " • " + filmCountRu(films.size)
    val series = v.playable as? Playable.Series
    if (series != null) {
        if (!inSection) return seriesLine(series)
        val range = itemSeasons(v)
        val eps = episodesRu(series.seasons.sumOf { it.items.size })
        return if (range != null && range.first != range.second) "Сезоны ${range.first}–${range.second} · $eps" else eps
    }
    if (inSection) return ""
    return releaseYear(v)?.let { "$it год" } ?: ""
}

/**
 * Same film or show added more than once (different releases: 720p / 1080p / 4K, other voice-overs, other titles,
 * other seasons of a show): one tile for all of them. Only ready items are merged. Films merge with films, series
 * (and collections) with series: by the same poster (when [posterOf] knows it), otherwise by the title without season
 * marks (a film also by year). The title is [LibraryItem.baseTitle] — the AI's one once the owner accepted it.
 * The first one in the list order stays on screen; [Pair.second] maps its id to every version (itself included), sorted
 * by [sortVersions].
 */
fun groupVersions(list: List<LibraryItem>, posterOf: (LibraryItem) -> String? = { null }): Pair<List<LibraryItem>, Map<Long, List<LibraryItem>>> {
    val byKey = HashMap<String, MutableList<LibraryItem>>()
    val groups = ArrayList<MutableList<LibraryItem>>()
    val shown = ArrayList<LibraryItem>(list.size)
    for (it in list) {
        val base = it.baseTitle
        if (it.playable is Playable.None || !it.m.ready || base.isBlank()) { shown += it; continue }
        val kind = if (it.playable is Playable.Movie) "m" else "s"
        val keys = listOfNotNull(
            posterOf(it)?.let { p -> "$kind|poster|$p" },
            if (kind == "m") "m|" + titleKey(base) + "|" + (it.m.aiTitle?.takeIf { a -> a.accepted }?.year?.toString() ?: YEAR_IN_NAME.find(it.m.name)?.value ?: "") else "s|" + titleKey(base)
        )
        val group = keys.firstNotNullOfOrNull { k -> byKey[k] }
        if (group == null) { val g = mutableListOf(it); groups += g; shown += it; keys.forEach { k -> byKey[k] = g } }
        else { group += it; keys.forEach { k -> byKey.putIfAbsent(k, group) } }
    }
    val versions = HashMap<Long, List<LibraryItem>>()
    for (g in groups) if (g.size > 1) versions[g.first().m.id] = sortVersions(g)
    return shown to versions
}

/**
 * [name] (lower case, no folder) is [base] plus a suffix: "show.s01e01.rus.srt" for "show.s01e01", but not
 * "show.s01e10.srt" — the base must end where a word ends, or episode 1 would take episode 10's files.
 */
private fun sameBase(name: String, base: String): Boolean =
    name.startsWith(base) && (name.length == base.length || !name[base.length].isLetterOrDigit())

private val SUB_EXT = Regex("""\.(srt|ass|ssa|vtt|sub)$""", RegexOption.IGNORE_CASE)

/**
 * Subtitle files that belong to [video]: same base name first ("Show.S01E02.srt", "Show.S01E02.rus.ass"),
 * otherwise files in the same folder or its "Subs" subfolder; for a single-video torrent any subtitle file.
 */
fun subtitlesFor(m: Magnet, video: MFile): List<MFile> {
    val subs = m.files.filter { !it.isFolder && it.link != null && SUB_EXT.containsMatchIn(it.path) }
    if (subs.isEmpty()) return emptyList()
    val norm = { p: String -> p.replace('\\', '/') }
    val vPath = norm(video.path)
    val vDir = vPath.substringBeforeLast('/', "")
    val vBase = vPath.substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT)
    val byName = subs.filter { sameBase(norm(it.path).substringAfterLast('/').lowercase(Locale.ROOT), vBase) }
    if (byName.isNotEmpty()) return byName.take(8)
    val videos = m.files.count { !it.isFolder && VIDEO_EXT.containsMatchIn(it.path) && !SAMPLE.containsMatchIn(it.path) }
    if (videos <= 1) return subs.take(8)
    val near = subs.filter {
        val d = norm(it.path).substringBeforeLast('/', "")
        d == vDir || (d.startsWith("$vDir/") && d.substringAfterLast('/').lowercase(Locale.ROOT).startsWith("sub"))
    }
    return near.take(8)
}

fun watchKey(magnetId: Long, file: MFile) = "$magnetId|${file.path}"

/** Saved by the player: position, length, when. */
data class WatchInfo(val pos: Long, val len: Long, val at: Long) {
    val fraction: Float get() = if (len > 0) (pos.toFloat() / len).coerceIn(0f, 1f) else 0f
    val watched: Boolean get() = len > 0 && pos >= len - 15_000L
}

private val AUDIO_EXT = Regex("""\.(mka|ac3|eac3|dts|aac|flac|mp3|m4a|opus|thd|wav)$""", RegexOption.IGNORE_CASE)

/**
 * External voice-overs: Russian releases often keep dubs as separate files ("RUS Sound/Show.S01E02.mka").
 * Same base name as the video first; for a single-video torrent any audio file.
 */
fun externalAudioFor(m: Magnet, video: MFile): List<MFile> {
    val audio = m.files.filter { !it.isFolder && it.link != null && AUDIO_EXT.containsMatchIn(it.path) }
    if (audio.isEmpty()) return emptyList()
    val vBase = video.path.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT)
    val byName = audio.filter { sameBase(it.path.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT), vBase) }
    if (byName.isNotEmpty()) return byName.take(8)
    val videos = m.files.count { !it.isFolder && VIDEO_EXT.containsMatchIn(it.path) && !SAMPLE.containsMatchIn(it.path) }
    return if (videos <= 1) audio.take(8) else emptyList()
}

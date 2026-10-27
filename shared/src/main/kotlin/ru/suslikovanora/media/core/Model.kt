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

private fun parseEpisode(path: String): Pair<Int, Int>? {
    val p = path.replace('\\', '/')
    val name = p.substringAfterLast('/')
    val folderSeason = (ANY_SEASON.find(p) ?: DIR_S.find(p))?.groupValues?.get(1)?.toInt()
    (SXXEYY.find(name) ?: SXXEYY.find(p))
        ?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    NXNN.find(name)
        ?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    EP_WORD.find(name)
        ?.let { return (folderSeason ?: 1) to it.groupValues[1].toInt() }
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

val COLLECTION_REGEX = Regex(
    """\b(трилоги[яи]|дилоги[яи]|тетралоги[яи]|квадрологи[яи]|пенталоги[яи]|гексалоги[яи]|франшиз[аеы]|коллекци[яи]|антологи[яи]|саг[аеы]|киносаг[аеы]|сборник|боксет|boxset|box-set|trilogy|duology|dilogy|tetralogy|quadrilogy|anthology|collection|franchise)\b|\b\d+\s*[-–—]\s*(?:в|in)\s*1\b|\bвсе\s+части\b|\bвсе\s+фильмы\b""",
    RegexOption.IGNORE_CASE
)

fun isCollection(p: Playable, m: Magnet? = null): Boolean {
    if (m?.aiTitle?.takeIf { it.accepted }?.kind == "collection") return true
    if (p !is Playable.Series) return false
    if (p.seasons.firstOrNull()?.items?.firstOrNull()?.title != null) return true
    if (m != null && COLLECTION_REGEX.containsMatchIn(m.name)) return true
    return false
}

fun collectionTypeLabel(filmCount: Int, name: String = ""): String = when {
    Regex("""\b(трилоги[яи]|trilogy)\b""", RegexOption.IGNORE_CASE).containsMatchIn(name) || filmCount == 3 -> "Кинотрилогия"
    Regex("""\b(дилоги[яи]|duology|dilogy)\b""", RegexOption.IGNORE_CASE).containsMatchIn(name) || filmCount == 2 -> "Дилогия"
    Regex("""\b(квадрологи[яи]|тетралоги[яи]|quadrilogy|tetralogy)\b""", RegexOption.IGNORE_CASE).containsMatchIn(name) || filmCount == 4 -> "Кинотетралогия"
    Regex("""\b(сборник)\b""", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "Сборник"
    Regex("""\b(антологи[яи]|anthology)\b""", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "Антология"
    Regex("""\b(коллекци[яи]|collection|boxset)\b""", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "Коллекция"
    else -> "Кинофраншиза"
}

fun filmCountRu(count: Int): String = when {
    count % 10 == 1 && count % 100 != 11 -> "$count фильм"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "$count фильма"
    else -> "$count фильмов"
}

fun seriesCountRu(seasons: Int, episodes: Int): String {
    val sWord = when {
        seasons % 10 == 1 && seasons % 100 != 11 -> "$seasons сезон"
        seasons % 10 in 2..4 && seasons % 100 !in 12..14 -> "$seasons сезона"
        else -> "$seasons сезонов"
    }
    val eWord = when {
        episodes % 10 == 1 && episodes % 100 != 11 -> "$episodes серия"
        episodes % 10 in 2..4 && episodes % 100 !in 12..14 -> "$episodes серии"
        else -> "$episodes серий"
    }
    return "$sWord • $eWord"
}

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

fun collectionFileTitle(m: Magnet, file: MFile, baseTitle: String = cleanTitle(m.name)): String {
    val raw = file.name.ifBlank { file.path.substringAfterLast('/') }
    val cleaned = cleanCollectionFilmTitle(raw)
    val rawBase = baseTitle.replace(COLLECTION_REGEX, " ")
    val cleanBase = cleanTitle(rawBase.split('/').firstOrNull().orEmpty())
    val coreBase = cleanBase.substringBefore(':')
        .replace(Regex("""\b\d+\s*(?:фильм\w*|сери\w*|част\w*)\b""", RegexOption.IGNORE_CASE), "")
        .trim()
    return when {
        cleaned.isNotBlank() && coreBase.isNotBlank() && !cleaned.contains(coreBase, ignoreCase = true) ->
            "$coreBase: $cleaned"
        cleaned.isNotBlank() -> cleaned
        else -> cleanTitle(raw)
    }
}

fun collectionFilePosterQueries(m: Magnet, file: MFile, baseTitle: String = cleanTitle(m.name)): List<String> {
    val out = LinkedHashSet<String>()
    val raw = file.name.ifBlank { file.path.substringAfterLast('/') }
    val withoutExt = raw.replace(EXT, "")
    val stripped = withoutExt.replace(TRACK_PREFIX, "").trim()
    val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(withoutExt)?.groupValues?.get(1)
        ?: Regex("""\b(19\d{2}|20\d{2})\b""").find(file.path)?.groupValues?.get(1)
    val cleaned = cleanTitle(stripped.ifBlank { withoutExt })

    val parts = cleaned.split('/').map { it.trim() }.filter { it.isNotBlank() }
    val candidates = if (parts.isNotEmpty()) parts else listOf(cleaned)

    for (c in candidates) {
        if (year != null) out += "$c ($year)"
        out += c
    }

    val rawBase = baseTitle.replace(COLLECTION_REGEX, " ")
    val cleanBases = rawBase.split('/').map { cleanTitle(it) }.filter { it.length >= 2 }

    for (cb in cleanBases) {
        for (c in candidates) {
            if (!c.contains(cb, ignoreCase = true)) {
                if (year != null) out += "$cb: $c ($year)"
                out += "$cb: $c"
                if (year != null) out += "$cb $c ($year)"
                out += "$cb $c"
            }
        }
    }

    // Franchise fallbacks (with year if known)
    for (cb in cleanBases) {
        if (year != null) out += "$cb ($year)"
        out += cb
    }

    out += cleanTitle(m.name)
    return out.toList()
}

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
    val eps = vids.mapNotNull { f -> parseEpisode(f.path)?.let { (s, e) -> Episode(s, e, f) } }
    if (eps.size >= 2) {
        val seasons = eps.groupBy { it.season }.entries.sortedBy { it.key }.map { (s, items) ->
            Season(s, items.sortedWith(compareBy<Episode> { it.episode }.thenBy { it.file.path }))
        }
        return Playable.Series(seasons)
    }
    // Episodes named only by a number ("Show - 01 [1080p].mkv"): a series, not a collection of films.
    numberedEpisodesOf(m, vids)?.let { return it }
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
private fun numberedEpisodesOf(m: Magnet, vids: List<MFile>): Playable.Series? {
    if (vids.size < 2 || vids.any { BLURAY_DIR.containsMatchIn(it.path) }) return null
    val main = vids.filter { !EXTRAS.containsMatchIn(it.path) }
    if (main.size < 2) return null
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
        if (COLLECTION_REGEX.containsMatchIn(m.name) && !SERIES_HINT.containsMatchIn(m.name)) continue
        if (!SERIES_HINT.containsMatchIn(m.name) && !looksLikeSeason) continue
        val eps = main.mapIndexed { i, f -> Episode(1, nums[i], f) }.sortedBy { it.episode }
        return Playable.Series(listOf(Season(1, eps)))
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

/** «Сезоны 1–3 · 24 серии» for a series row of the picker. */
fun seriesLine(p: Playable.Series): String {
    val nums = p.seasons.map { it.season }.filter { it > 0 }
    val eps = p.seasons.sumOf { it.items.size }
    val first = p.seasons.firstOrNull()?.items?.firstOrNull()
    val isFilms = first?.title != null
    val epWord = if (isFilms) "фильм." else if (eps % 10 in 2..4 && eps % 100 !in 12..14) "серии" else if (eps % 10 == 1 && eps % 100 != 11) "серия" else "серий"
    val range = when {
        isFilms || nums.isEmpty() -> ""
        nums.size == 1 -> "Сезон ${nums.first()} · "
        else -> "Сезоны ${nums.min()}–${nums.max()} · "
    }
    return "$range$eps $epWord"
}

private val YEAR_IN_NAME = Regex("""\b(19\d{2}|20\d{2})\b""")
private val SEASON_RANGE_REGEX = Regex(
    """(?:season|сезон)[s\p{L}]*[\s._-]*(\d{1,2})[\s._-]*[-–][\s._-]*(\d{1,2})|(\d{1,2})[\s._-]*[-–][\s._-]*(\d{1,2})[\s._-]*(?:season|сезон)[s\p{L}]*""",
    IC
)

data class VersionSection(
    val title: String?,
    val items: List<LibraryItem>
)

/** Extracts the 4-digit release year (1900..2028) from text or file names. */
fun extractYear(name: String): Int? {
    val bracketed = Regex("""[\(\[\{](19\d\d|20[0-2]\d)[\)\]\}]""").find(name)
    if (bracketed != null) return bracketed.groupValues[1].toInt()
    val matches = Regex("""\b(19\d\d|20[0-2]\d)\b""").findAll(name)
    for (m in matches) {
        val y = m.groupValues[1].toInt()
        if (y in 1900..2028) return y
    }
    return null
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

/** Explicit part or film number (e.g. Part 2, Часть 3, Фильм 1, Chapter 2). */
fun itemPart(item: LibraryItem): Int? {
    val text = item.m.name
    val explicit = Regex(
        """\b(?:часть|ч|фильм|film|part|pt|глава|chapter)\s*(\d+|[ivx]+)\b""",
        IC
    ).find(text)
    if (explicit != null) {
        val raw = explicit.groupValues[1]
        raw.toIntOrNull()?.let { return it }
        parseRoman(raw)?.let { return it }
    }
    val ord = Regex("""\b(\d+)\s*[-–]?\s*(?:я|ая|ья|ей)?\s*(?:часть|серия|фильм)\b""", IC).find(text)
    if (ord != null) {
        ord.groupValues[1].toIntOrNull()?.let { return it }
    }
    val roman = Regex("""\b([IVX]{1,4})\b""").findAll(text)
    for (r in roman) {
        val num = parseRoman(r.groupValues[1])
        if (num != null && num in 1..10) return num
    }
    if (item.baseTitle.isNotBlank()) {
        val afterBase = Regex("""(?i)\b${Regex.escape(item.baseTitle)}\s+(\d+)\b""").find(text)
        if (afterBase != null) {
            afterBase.groupValues[1].toIntOrNull()?.let { return it }
        }
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
        if (nums.isNotEmpty()) {
            return (nums.minOrNull() ?: 1) to (nums.maxOrNull() ?: 1)
        }
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

/**
 * Sorts versions intelligently:
 * 1. For series: chronologically by season (Season 1 < Season 2 < Season 3; specials at the end).
 * 2. For films/trilogies: chronologically by part number / release year (Part 1 < Part 2, 2001 < 2002 < 2003).
 * 3. Within the same season or film: best quality first (4K > 1080p > 720p), then largest file size (higher bitrate).
 */
fun sortVersions(versions: List<LibraryItem>): List<LibraryItem> {
    if (versions.size <= 1) return versions
    val hasSeries = versions.any { itemSeasons(it) != null }
    return versions.sortedWith(Comparator { a, b ->
        if (hasSeries) {
            val sa = itemSeasons(a)
            val sb = itemSeasons(b)
            if (sa != null && sb != null) {
                val amin = if (sa.first == 0) Int.MAX_VALUE else sa.first
                val bmin = if (sb.first == 0) Int.MAX_VALUE else sb.first
                if (amin != bmin) return@Comparator amin.compareTo(bmin)
                val aRange = sa.second - sa.first
                val bRange = sb.second - sb.first
                if (aRange != bRange) return@Comparator aRange.compareTo(bRange)
            } else if (sa != null) {
                return@Comparator -1
            } else if (sb != null) {
                return@Comparator 1
            }
        } else {
            val pa = itemPart(a)
            val pb = itemPart(b)
            if (pa != null && pb != null && pa != pb) {
                return@Comparator pa.compareTo(pb)
            }
            val ya = releaseYear(a)
            val yb = releaseYear(b)
            if (ya != null && yb != null && ya != yb) {
                return@Comparator ya.compareTo(yb)
            }
            if (pa != null && pb == null) return@Comparator -1
            if (pb != null && pa == null) return@Comparator 1
            if (ya != null && yb == null) return@Comparator -1
            if (yb != null && ya == null) return@Comparator 1
        }
        val qa = qualityScore(a.m.name)
        val qb = qualityScore(b.m.name)
        if (qa != qb) return@Comparator qb.compareTo(qa)
        val sDiff = b.m.size.compareTo(a.m.size)
        if (sDiff != 0) return@Comparator sDiff
        a.m.name.compareTo(b.m.name)
    })
}

/**
 * Groups sorted versions into sections (e.g. "Сезон 1", "Сезон 2" or "2001 год", "2002 год").
 * If all versions belong to the same single season or single film, returns a single section with [VersionSection.title] = null.
 */
fun versionSections(versions: List<LibraryItem>): List<VersionSection> {
    val sorted = sortVersions(versions)
    if (sorted.isEmpty()) return emptyList()
    val hasSeries = sorted.any { itemSeasons(it) != null }

    if (hasSeries) {
        val seasonKeys = sorted.map { item ->
            val s = itemSeasons(item)
            when {
                s == null -> "Дополнительно"
                s.first == 0 -> "Спецвыпуски"
                s.first == s.second -> "Сезон ${s.first}"
                else -> "Сезоны ${s.first}–${s.second}"
            }
        }
        val distinctKeys = seasonKeys.distinct()
        if (distinctKeys.size <= 1) {
            return listOf(VersionSection(null, sorted))
        }
        val groups = LinkedHashMap<String, MutableList<LibraryItem>>()
        for (i in sorted.indices) {
            groups.getOrPut(seasonKeys[i]) { ArrayList() }.add(sorted[i])
        }
        return groups.map { (key, items) -> VersionSection(key, items) }
    } else {
        val hasMultiYearOrPart = sorted.mapNotNull { releaseYear(it) }.distinct().size > 1 ||
                sorted.mapNotNull { itemPart(it) }.distinct().size > 1
        if (!hasMultiYearOrPart) {
            return listOf(VersionSection(null, sorted))
        }
        val groups = LinkedHashMap<String, MutableList<LibraryItem>>()
        for (item in sorted) {
            val y = releaseYear(item)
            val p = itemPart(item)
            val key = when {
                y != null && p != null -> "$y год · Часть $p"
                y != null -> "$y год"
                p != null -> "Часть $p"
                else -> "Другая версия"
            }
            groups.getOrPut(key) { ArrayList() }.add(item)
        }
        return groups.map { (key, items) -> VersionSection(key, items) }
    }
}

/** Title the tile is grouped by: the AI's one once the owner accepted it, otherwise the regexes' [LibraryItem.baseTitle]. */
fun groupTitle(it: LibraryItem): String = it.m.aiTitle?.takeIf { a -> a.accepted }?.base ?: it.baseTitle // = baseTitle (prepareItems)

/**
 * Same film or show added more than once (different releases: 720p / 1080p / 4K, other voice-overs, other titles,
 * other seasons of a show): one tile for all of them. Only ready items are merged. Films merge with films, series
 * (and collections) with series: by the same poster (when [posterOf] knows it), otherwise by the title without season
 * marks (a film also by year). The first one in the list order stays on screen; [Pair.second] maps its id to every
 * version (itself included), sorted intelligently.
 */
fun groupVersions(list: List<LibraryItem>, posterOf: (LibraryItem) -> String? = { null }): Pair<List<LibraryItem>, Map<Long, List<LibraryItem>>> {
    val byKey = HashMap<String, MutableList<LibraryItem>>()
    val groups = ArrayList<MutableList<LibraryItem>>()
    val shown = ArrayList<LibraryItem>(list.size)
    for (it in list) {
        val base = groupTitle(it)
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

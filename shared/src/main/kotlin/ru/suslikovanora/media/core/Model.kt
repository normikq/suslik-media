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
    val favorite: Boolean = false // per device, shown first in the library
) {
    val ready get() = statusCode == 4
    val downloading get() = statusCode < 4
    val failed get() = statusCode >= 5
    val progress: Float get() = if (size > 0) (downloaded.toFloat() / size).coerceIn(0f, 1f) else 0f
}

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
            favorite = m.optBoolean("favorite", false)
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

fun buildPlayable(m: Magnet): Playable {
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
    // Collection (trilogy, several films / parts in one torrent, no episode numbers): every full-size film is listed.
    collectionOf(vids)?.let { return it }
    return mainVideo(m)?.let { Playable.Movie(it) } ?: Playable.None
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
)

fun prepareItems(list: List<Magnet>): List<LibraryItem> = list.map { m ->
    val base = cleanTitle(m.name)
    val main = mainVideo(m)
    LibraryItem(
        m = m,
        playable = buildPlayable(m),
        baseTitle = base,
        posterQueries = listOfNotNull(base, main?.let { cleanTitle(it.name.ifBlank { it.path.substringAfterLast('/') }) }).distinct(),
        searchText = (m.name + "\n" + m.files.joinToString("\n") { it.path }).lowercase()
    )
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

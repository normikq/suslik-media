package ru.suslikovanora.media.library

import ru.suslikovanora.media.core.*

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.suslikovanora.media.net.Api
import java.io.File
import java.net.URLEncoder
import ru.suslikovanora.media.tr

enum class StatusKind { Info, Ok, Error }

val MEDIA_UNKNOWN: String get() = tr("library.razreshenie_n_d")

/** Same text as the site's formatMediaInfo(). */
fun formatMediaInfo(m: JSONObject): String {
    val parts = ArrayList<String>()
    val w = m.optInt("width", 0); val h = m.optInt("height", 0)
    if (w > 0 && h > 0) parts += "$w×$h"
    val br = m.optDouble("bitrateMbps", 0.0)
    parts += if (br > 0) java.math.BigDecimal(br).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + tr("library.mbit_s") else tr("library.bitreyt_n_d")
    m.optString("codec").takeIf { it.isNotBlank() }?.let { parts += it.uppercase() }
    val fr = m.optString("frameRate")
    Regex("""^(\d+)/(\d+)$""").find(fr)?.let { mt ->
        val a = mt.groupValues[1].toDouble(); val b = mt.groupValues[2].toDouble()
        if (b > 0 && a / b > 0) parts += java.math.BigDecimal(a / b).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " fps"
    }
    return parts.joinToString(" · ")
}

/**
 * Library state for the native screen. The last good server answer is kept on disk, so the grid shows
 * instantly on start (and offline); the network refresh replaces it when it arrives.
 */
/** A poster picture no tile showed for this long leaves the disk cache (see LibraryStore.prunePosters). */
private const val POSTER_UNUSED_MS = 7L * 86_400_000L

class LibraryStore(context: Context) {
    private val app = context.applicationContext
    private val snapshotFile = File(app.filesDir, "library.json")
    private val posterPrefs = app.getSharedPreferences("posters", Context.MODE_PRIVATE)
    private val episodePrefs = app.getSharedPreferences("last_episode", Context.MODE_PRIVATE)

    var magnets by mutableStateOf<List<Magnet>>(emptyList())
        private set
    /** Tiles with playlists/titles already worked out (prepared on a background thread). */
    var items by mutableStateOf<List<LibraryItem>>(emptyList())
        private set

    private suspend fun publish(list: List<Magnet>) {
        val prepared = withContext(Dispatchers.Default) { prepareItems(list) }
        magnets = list
        items = prepared
    }
    var loading by mutableStateOf(false)
        private set
    var status by mutableStateOf(tr("library.nazhmite_obnovit_chtoby_zagruz"))
        private set
    var statusKind by mutableStateOf(StatusKind.Info)
        private set
    /** null = not checked yet, false = show the login screen. */
    var authenticated by mutableStateOf<Boolean?>(null)
    var loadedOnce = false
        private set
    var lastRefreshAt = 0L
        private set
    /** Tiles that already played their entrance animation (not again when coming back to the tab). */
    private val seen = HashSet<Long>()
    fun seen(id: Long) = id in seen
    fun markSeen(id: Long) { seen += id }

    /** Favourite on/off for this device; the list updates at once, the server keeps it per device cookie. */
    suspend fun setFavorite(m: Magnet, on: Boolean): String? {
        val beforeM = magnets; val beforeI = items
        magnets = magnets.map { if (it.id == m.id) it.copy(favorite = on) else it }
        items = items.map { if (it.m.id == m.id) it.copy(m = it.m.copy(favorite = on)) else it }
        return try {
            Api.post("/api/favorites", JSONObject().put("id", m.id).put("favorite", on))
            withContext(Dispatchers.IO) {
                runCatching {
                    val root = JSONObject(snapshotFile.readText())
                    val arr = root.optJSONArray("magnets")
                    if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.takeIf { it.optLong("id") == m.id }?.put("favorite", on)
                    snapshotFile.writeText(root.toString())
                }
            }
            null
        } catch (e: Exception) { magnets = beforeM; items = beforeI; Api.describe(e) }
    }

    suspend fun restoreSnapshot() {
        if (magnets.isNotEmpty()) return
        val list = withContext(Dispatchers.IO) {
            runCatching { parseLibrary(JSONObject(snapshotFile.readText())) }.getOrNull()
        } ?: return
        publish(list)
        status = tr("library.pokazana_sohranennaya_kopiya_o")
        statusKind = StatusKind.Info
    }

    suspend fun checkSession(): Boolean? = try {
        Api.get("/api/session").optBoolean("authenticated").also { authenticated = it }
    } catch (e: Exception) {
        null // offline: keep whatever we know
    }

    suspend fun login(password: String): String? = try {
        Api.post("/api/login", JSONObject().put("password", password))
        authenticated = true
        null
    } catch (e: Exception) {
        Api.describe(e)
    }

    suspend fun logout() {
        runCatching { Api.post("/api/logout") }
        authenticated = false
        magnets = emptyList()
        items = emptyList()
        loadedOnce = false
        withContext(Dispatchers.IO) { runCatching { snapshotFile.delete() } }
    }

    suspend fun refresh() {
        if (loading) return
        loading = true
        status = tr("library.zagruzka_torrentov_i_faylov"); statusKind = StatusKind.Info
        try {
            val json = Api.get("/api/library")
            val list = withContext(Dispatchers.Default) { parseLibrary(json) }
            publish(list)
            cleanUp(items) // what films that left the library left behind (posters, progress, reserve…)
            prunePosters() // once per start: pictures not shown for 7 days
            loadedOnce = true
            lastRefreshAt = System.currentTimeMillis()
            withContext(Dispatchers.IO) { runCatching { snapshotFile.writeText(json.toString()) } }
            status = if (json.optBoolean("stale")) tr("library.alldebrid_ne_otvetil_pokazana", "size" to list.size)
            else tr("library.zagruzheno_size_torrent_ov", "size" to list.size)
            statusKind = if (json.optBoolean("stale")) StatusKind.Info else StatusKind.Ok
        } catch (e: Exception) {
            if (e is Api.HttpError && e.code == 401) authenticated = false
            status = Api.describe(e); statusKind = StatusKind.Error
        } finally {
            loading = false
        }
    }

    /** "cancel" while downloading, "delete" when ready. Returns an error message or null. */
    suspend fun remove(m: Magnet): String? = try {
        Api.post("/api/magnets/${m.id}/${if (m.downloading) "cancel" else "delete"}")
        magnets = magnets.filter { it.id != m.id }
        items = items.filter { it.m.id != m.id }
        withContext(Dispatchers.IO) {
            runCatching {
                val root = JSONObject(snapshotFile.readText())
                val arr = root.optJSONArray("magnets")
                if (arr != null) for (i in arr.length() - 1 downTo 0) if (arr.optJSONObject(i)?.optLong("id") == m.id) arr.remove(i)
                snapshotFile.writeText(root.toString())
            }
        }
        null
    } catch (e: Exception) {
        Api.describe(e)
    }

    // ---- posters: looked up only for tiles on screen, remembered for 30 days (3 days for "no poster") ----
    private val posterGate = Semaphore(4)
    private val posterInflight = HashMap<String, CompletableDeferred<String?>>()
    private val posterMemory = HashMap<String, String?>()

    fun cachedPoster(queries: List<String>): String? {
        for (q in queries) {
            posterMemory[q.lowercase()]?.let { return Api.BASE + it }
        }
        return null
    }

    suspend fun poster(queries: List<String>): String? {
        for (q in queries) {
            val url = posterFor(q)
            if (url != null) return (Api.BASE + url).also { markShown(it) }
        }
        return null
    }

    // ---- poster pictures not shown for 7 days are deleted (Coil's own cache only has a size limit) ----
    private val posterShown = app.getSharedPreferences("posters_shown", Context.MODE_PRIVATE)
    private var postersPruned = false

    private fun markShown(url: String) {
        val now = System.currentTimeMillis()
        if (now - posterShown.getLong(url, 0L) > 6 * 3_600_000L) posterShown.edit().putLong(url, now).apply()
    }

    /** Once per start: pictures no tile showed for 7 days leave the disk cache (downloaded again when needed). */
    private suspend fun prunePosters() = withContext(Dispatchers.IO) {
        if (postersPruned) return@withContext
        postersPruned = true
        val cache = runCatching { Posters.loader(app).diskCache }.getOrNull() ?: return@withContext
        val now = System.currentTimeMillis()
        val shown = posterShown.all
        val edit = posterShown.edit()
        // Pictures cached before this version have no mark yet: they count from now.
        posterPrefs.all.values.mapNotNull { (it as? String)?.substringAfter('|', "")?.ifBlank { null } }
            .map { Api.BASE + it }.filter { it !in shown }.forEach { edit.putLong(it, now) }
        var n = 0
        for ((url, at) in shown) {
            if (now - ((at as? Long) ?: now) <= POSTER_UNUSED_MS) continue
            runCatching { cache.remove(url) }; edit.remove(url); n++
        }
        edit.apply()
        if (n > 0) ru.suslikovanora.media.AppLog.write("posters: $n picture(s) not shown for 7 days removed from the cache")
    }

    private suspend fun posterFor(query: String): String? {
        val key = query.lowercase()
        if (posterMemory.containsKey(key)) return posterMemory[key]
        val stored = posterPrefs.getString(key, null)
        if (stored != null) {
            val at = stored.substringBefore('|').toLongOrNull() ?: 0L
            val url = stored.substringAfter('|', "").ifBlank { null }
            val ttl = if (url != null) 30L * 86_400_000L else 3L * 86_400_000L
            if (System.currentTimeMillis() - at < ttl) { posterMemory[key] = url; return url }
        }
        // Called from the main thread only (Compose effects), so the in-flight map needs no locking.
        posterInflight[key]?.let { return it.await() }
        val own = CompletableDeferred<String?>()
        posterInflight[key] = own
        var url: String? = null
        try {
            url = posterGate.withPermit {
                Api.get("/api/poster?q=" + URLEncoder.encode(query, "UTF-8"))
                    .optString("poster").takeIf { it.startsWith("/api/poster/img/") }
            }
            posterPrefs.edit().putString(key, "${System.currentTimeMillis()}|${url.orEmpty()}").apply()
            posterMemory[key] = url
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // network error: not remembered, retried next time
        } finally {
            posterInflight.remove(key)
            own.complete(url)
        }
        return url
    }

    // ---- video info line ("1920×1080 · 12 Мбит/с · HEVC · 23.98 fps"), like on the website ----
    // The server runs ffprobe on the remote file, so analyses go strictly one at a time and only for tiles on
    // screen. A result is kept forever (the file does not change); a server-side failure is not retried for 6 h.
    private val mediaPrefs = app.getSharedPreferences("media_info", Context.MODE_PRIVATE)
    private val probeLock = Mutex()
    private val mediaInflight = HashMap<String, CompletableDeferred<String>>()

    /** Ready line for the tile, or null when it still has to be analysed. */
    private val mediaMemory = HashMap<String, String>()

    fun cachedMediaLine(magnetId: Long, file: MFile): String? {
        mediaMemory["$magnetId|${file.path}"]?.let { return it }
        val raw = mediaPrefs.getString("$magnetId|${file.path}", null) ?: return null
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val failedAt = j.optLong("failedAt", 0L)
        if (failedAt > 0L) return if (System.currentTimeMillis() - failedAt < 6 * 3_600_000L) MEDIA_UNKNOWN else null
        return formatMediaInfo(j).also { mediaMemory["$magnetId|${file.path}"] = it }
    }

    suspend fun mediaLine(magnetId: Long, file: MFile): String {
        cachedMediaLine(magnetId, file)?.let { return it }
        val link = file.link ?: return MEDIA_UNKNOWN
        val key = "$magnetId|${file.path}"
        mediaInflight[key]?.let { return it.await() } // main thread only, like the posters
        val own = CompletableDeferred<String>()
        mediaInflight[key] = own
        var line = MEDIA_UNKNOWN
        try {
            val meta = probeLock.withLock {
                Api.post("/api/media-metadata", JSONObject().put("link", link).put("magnetId", magnetId).put("path", file.path))
                    .optJSONObject("metadata")
            }
            if (meta != null) {
                mediaPrefs.edit().putString(key, meta.toString()).apply()
                line = formatMediaInfo(meta)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Api.HttpError) {
            // The server could not analyse the file: do not retry on every screen open.
            if (e.code >= 500) mediaPrefs.edit().putString(key, JSONObject().put("failedAt", System.currentTimeMillis()).toString()).apply()
        } catch (e: Exception) {
            // no network: not remembered
        } finally {
            mediaInflight.remove(key)
            own.complete(line)
        }
        return line
    }

    // ---- watch progress written by the player (prefs "watch": "magnetId|path" -> "pos|len|at") ----
    private val watchPrefs = app.getSharedPreferences("watch", Context.MODE_PRIVATE)
    /** Bumped when the app comes back from the player, so tiles re-read their progress. */
    var watchVersion by mutableStateOf(0)
        private set
    fun bumpWatch() { watchVersion++ }

    fun watchInfo(key: String): WatchInfo? {
        val v = watchPrefs.getString(key, null) ?: return null
        val parts = v.split('|')
        if (parts.size < 3) return null
        return WatchInfo(parts[0].toLongOrNull() ?: return null, parts[1].toLongOrNull() ?: 0L, parts[2].toLongOrNull() ?: 0L)
    }

    /** Most recently watched file of a torrent (to pre-select the episode and show "Продолжить"). */
    fun lastWatched(m: Magnet): Pair<MFile, WatchInfo>? {
        var best: Pair<MFile, WatchInfo>? = null
        for (f in m.files) {
            if (f.isFolder) continue
            val w = watchInfo(watchKey(m.id, f)) ?: continue
            if (best == null || w.at > best.second.at) best = f to w
        }
        return best
    }

    // ---- cleaning up after films that left the library (after every successful refresh) ----
    /**
     * Posters (and their picture files, unless another tile shows the same one), the video info line, watch
     * progress, the resume place, the last episode, and a reserve still kept on disk — of films no longer in the list.
     */
    private suspend fun cleanUp(list: List<LibraryItem>) = withContext(Dispatchers.IO) {
        val keep = LibraryKeep(list)
        if (!keep.usable) return@withContext
        var n = 0
        fun prune(prefs: android.content.SharedPreferences, keepIt: (String) -> Boolean) {
            val gone = prefs.all.keys.filterNot(keepIt)
            if (gone.isEmpty()) return
            prefs.edit().apply { gone.forEach { remove(it) } }.apply()
            n += gone.size
        }
        fun url(v: Any?) = (v as? String)?.substringAfter('|', "")?.ifBlank { null }
        val posters = posterPrefs.all
        val keptUrls = posters.filterKeys { keep.keepsPoster(it) }.values.mapNotNullTo(HashSet()) { url(it) }
        val goneUrls = posters.filterKeys { !keep.keepsPoster(it) }.values.mapNotNull { url(it) }.filter { it !in keptUrls }
        prune(posterPrefs, keep::keepsPoster)
        runCatching { Posters.loader(app).diskCache?.let { cache -> goneUrls.forEach { u -> cache.remove(Api.BASE + u) } } }
        if (goneUrls.isNotEmpty()) posterShown.edit().apply { goneUrls.forEach { remove(Api.BASE + it) } }.apply()
        prune(mediaPrefs, keep::keepsKey)
        prune(watchPrefs, keep::keepsKey)
        prune(episodePrefs, keep::keepsKey)
        prune(app.getSharedPreferences("positions", Context.MODE_PRIVATE)) { !it.startsWith("w:") || keep.keepsKey(it) }
        val reserves = BurstProxy.forgetRetired { it in keep.links }
        if (n + reserves > 0) ru.suslikovanora.media.AppLog.write(
            "library cleanup: $n entries and ${goneUrls.size} poster(s) of films no longer in the library, $reserves reserve(s) on disk")
    }

    // ---- last chosen episode per torrent ----
    fun lastEpisode(id: Long): String? = episodePrefs.getString(id.toString(), null)
    fun saveEpisode(id: Long, path: String) = episodePrefs.edit().putString(id.toString(), path).apply()
}

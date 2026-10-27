package ru.suslikovanora.media.library

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.core.auth.*

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
    private val animPrefs = app.getSharedPreferences("poster_anim", Context.MODE_PRIVATE)
    private val episodePrefs = app.getSharedPreferences("last_episode", Context.MODE_PRIVATE)

    init {
        loadCachedAnimationFlags()
    }

    private fun loadCachedAnimationFlags() {
        val now = System.currentTimeMillis()
        for ((k, v) in animPrefs.all) {
            val s = v as? String ?: continue
            val at = s.substringBefore('|').toLongOrNull() ?: 0L
            if (now - at < 60L * 86_400_000L) {
                Animation.flags[k] = s.substringAfter('|') == "1"
            }
        }
        Animation.version++
    }

    private fun recordAnimation(key: String, j: JSONObject) {
        if (!j.has("animation")) return
        val a = j.optBoolean("animation")
        animPrefs.edit().putString(key, "${System.currentTimeMillis()}|${if (a) 1 else 0}").apply()
        Animation.flags[key] = a
    }

    var magnets by mutableStateOf<List<Magnet>>(emptyList())
        private set
    /** Tiles with playlists/titles already worked out (prepared on a background thread). */
    var items by mutableStateOf<List<LibraryItem>>(emptyList())
        private set

    private suspend fun publish(all: List<Magnet>) {
        guessedTags.clear()
        val list = all.filterNot { isVoiceOverOnly(it) }
        val prepared = withContext(Dispatchers.Default) { prepareItems(list) }
        magnets = list
        items = prepared
    }

    // A torrent of only .mka is an uploaded voice-over (/api/custom-audio): it plays inside its film, not as a tile (as on the PC).
    private fun isVoiceOverOnly(m: Magnet): Boolean {
        val files = m.files.filter { !it.isFolder }
        return files.isNotEmpty() && files.all { ru.suslikovanora.media.core.audio.CustomAudio.isSupported(it.path) }
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
        } catch (e: CancellationException) { throw e } catch (e: Exception) { magnets = beforeM; items = beforeI; Api.describe(e) }
    }

    /** «Мои фильмы» on/off: only this user's list; the torrent stays in the shared library. */
    suspend fun setMine(m: Magnet, on: Boolean): String? {
        val beforeM = magnets; val beforeI = items
        magnets = magnets.map { if (it.id == m.id) it.copy(mine = on) else it }
        items = items.map { if (it.m.id == m.id) it.copy(m = it.m.copy(mine = on)) else it }
        return try {
            withContext(kotlinx.coroutines.NonCancellable) { Api.post(Shelf.PATH, Shelf.body(m.id, on)) }
            withContext(Dispatchers.IO) {
                runCatching {
                    val root = JSONObject(snapshotFile.readText())
                    val arr = root.optJSONArray("magnets")
                    if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.takeIf { it.optLong("id") == m.id }?.put("mine", on)
                    snapshotFile.writeText(root.toString())
                }
            }
            null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { magnets = beforeM; items = beforeI; Api.describe(e) }
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

    /** Who is signed in (core/auth/Auth.kt); null — the shared password or not known yet. */
    var profile by mutableStateOf<UserProfile?>(null)
        private set
    /** Discord sign-in by code: the code on the screen while the browser confirms it. */
    var pairing by mutableStateOf<DevicePairing?>(null)
        private set

    // The profile is set BEFORE [authenticated]: flipping it closes the login screen (and the coroutine that called us).
    suspend fun checkSession(): Boolean? = try {
        val s = SessionInfo.parse(Api.get(SessionInfo.PATH))
        profile = s.user?.takeIf { !it.isShared }
        CloudSync.userId = if (s.authenticated) profile?.id else null
        authenticated = s.authenticated
        s.authenticated
    } catch (e: Exception) {
        null // offline: keep whatever we know
    }

    suspend fun login(password: String): String? = try {
        Api.post("/api/login", JSONObject().put("password", password))
        if (checkSession() == null) authenticated = true
        null
    } catch (e: Exception) {
        Api.describe(e)
    }

    /** Asks the server for a code; the browser opens the page where it is confirmed (null = fine, else the error). */
    suspend fun startDeviceAuth(name: String): String? = try {
        val res = Api.post(DeviceAuth.START, DeviceAuth.startBody(name, Api.SITE), repeatable = false)
        pairing = DeviceAuth.parsePairing(res, Api.SITE)
        null
    } catch (e: Exception) {
        Api.describe(e)
    }

    /** One poll: true — signed in, false — the code has expired, null — still waiting. */
    suspend fun pollDeviceAuth(): Boolean? {
        val p = pairing ?: return null
        val r = try {
            DeviceAuth.parsePoll(Api.post(DeviceAuth.POLL, DeviceAuth.pollBody(p)))
        } catch (e: Api.HttpError) {
            DeviceAuth.pollFailed(e.code) // 410: the code has expired (10 min)
        } catch (e: Exception) { DevicePoll.Pending }
        return when (r) {
            is DevicePoll.Authorized -> {
                r.token?.let { Api.deviceToken = it }
                profile = r.user?.takeIf { !it.isShared }
                CloudSync.userId = profile?.id
                pairing = null
                authenticated = true
                true
            }
            DevicePoll.Expired -> { pairing = null; false }
            DevicePoll.Pending -> null
        }
    }

    fun cancelDeviceAuth() { pairing = null }

    suspend fun logout() {
        ru.suslikovanora.media.party.Party.leave()
        runCatching { Api.post("/api/logout") }
        Api.deviceToken = null
        profile = null
        CloudSync.userId = null
        pairing = null
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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Api.describe(e)
    }

    // ---- posters: looked up only for tiles on screen, remembered for 30 days (3 days for "no poster") ----
    private val posterGate = Semaphore(4)
    private val posterInflight = HashMap<String, CompletableDeferred<String?>>()
    private val posterMemory = HashMap<String, String?>()

    /** Poster path per query: films with the same poster are one film (versions are merged by it, see groupVersions). */
    private val posterPaths = androidx.compose.runtime.mutableStateMapOf<String, String>()
    var posterVersion by mutableStateOf(0)
        private set

    fun posterPathOf(item: LibraryItem): String? = item.posterQueries.firstNotNullOfOrNull { posterPaths[it.lowercase()] }

    private fun notePoster(key: String, path: String) {
        if (posterPaths[key] != path) { posterPaths[key] = path; posterVersion++ }
    }

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
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
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
            val j = posterGate.withPermit {
                Api.get("/api/poster?q=" + URLEncoder.encode(query, "UTF-8"))
            }
            if (j.has("animation")) recordAnimation(key, j)
            url = j.optString("poster").takeIf { it.startsWith("/api/poster/img/") }
            posterPrefs.edit().putString(key, "${System.currentTimeMillis()}|${url.orEmpty()}").apply()
            posterMemory[key] = url
            url?.let { notePoster(key, it) }
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

    // ---- the TV banner «Продолжить просмотр»: wide picture, title logo, description, year, rating (from /api/poster) ----
    /** Urls are absolute (Coil loads them with the API's HTTP client). */
    class HeroInfo(val backdrop: String?, val logo: String?, val overview: String?, val year: String?, val rating: Double?, val title: String? = null)
    private val heroes = java.util.concurrent.ConcurrentHashMap<String, HeroInfo>()

    fun cachedHero(queries: List<String>): HeroInfo? = queries.firstNotNullOfOrNull { heroes[it.lowercase()] }

    /** Null while the server cannot say (offline, an old server without these fields): the banner then stays plain. */
    suspend fun hero(queries: List<String>): HeroInfo? {
        cachedHero(queries)?.let { return it }
        for (q in queries) {
            try {
                val j = posterGate.withPermit { Api.get("/api/poster?hero=1&q=" + URLEncoder.encode(q, "UTF-8")) }
                if (j.has("animation")) recordAnimation(q.lowercase(), j)
                val abs = { k: String -> j.optString(k).takeIf { it.startsWith("/api/poster/img/") }?.let { Api.BASE + it } }
                val bd = abs("backdrop") ?: abs("poster")
                val ov = j.optString("overview").ifBlank { null }
                val title = j.optString("title").ifBlank { null }
                val rt = j.optDouble("rating", 0.0).takeIf { it > 0 }

                if (bd == null && ov == null && title == null && rt == null) continue

                val info = HeroInfo(bd, abs("logo"), ov, j.optString("year").ifBlank { null }, rt, title)
                heroes[q.lowercase()] = info
                return info
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        return null
    }

    // ---- Dolby Vision / HDR / DTS marks: the server looks into the file (ffprobe, kept until the film is deleted) ----
    private val serverTags = androidx.compose.runtime.mutableStateMapOf<Long, List<String>>()
    // The guess by the names runs regexes over every file of the torrent: once per film, not at every look at a tile
    private val guessedTags = java.util.concurrent.ConcurrentHashMap<Long, List<String>>()
    fun tagsOf(item: LibraryItem): List<String> = serverTags[item.m.id] ?: guessedTags.getOrPut(item.m.id) { itemTags(item) }

    /** The server analyses new films one by one in the background: ask again while it has some left. */
    suspend fun refreshTags() {
        repeat(40) {
            val j = try { Api.get("/api/tags") } catch (e: CancellationException) { throw e } catch (_: Exception) { return }
            val tags = j.optJSONObject("tags")
            if (tags != null) for (k in tags.keys()) {
                val a = tags.optJSONArray(k) ?: continue
                val id = k.toLongOrNull() ?: continue
                serverTags[id] = (0 until a.length()).map { a.getString(it) }
            }
            if (j.optInt("pending", 0) <= 0) return
            kotlinx.coroutines.delay(30_000)
        }
    }

    /**
     * Fills what needs the server's idea of every film: [Animation] (TMDB genre) to classify cartoons.
     * First from the saved answers, then asks about the films it has not seen (slowly, a limited number per run).
     * Films whose release name already says "мульт" / Pixar / anime need no genre lookup.
     */
    suspend fun classifyAnimation() {
        val todo = ArrayList<String>()
        val now = System.currentTimeMillis()
        for (item in items) {
            val needGenre = !(isCartoon(item) || isAnime(item))
            var genreKnown = !needGenre
            var posterKnown = false
            for (q in item.posterQueries) {
                val key = q.lowercase()
                posterPrefs.getString(key, null)?.substringAfter('|', "")?.takeIf { it.startsWith("/api/poster/img/") }?.let { notePoster(key, it); posterKnown = true }
                animPrefs.getString(key, null)?.let { v ->
                    val at = v.substringBefore('|').toLongOrNull() ?: 0L
                    if (now - at < 60L * 86_400_000L) {
                        Animation.flags[key] = v.substringAfter('|') == "1"
                        genreKnown = true
                    }
                }
            }
            if (!genreKnown || !posterKnown) item.posterQueries.firstOrNull()?.let { todo += it }
        }
        Animation.version++
        var asked = 0
        for (q in todo.distinct().take(80)) {
            try {
                val j = posterGate.withPermit { Api.get("/api/poster?q=" + URLEncoder.encode(q, "UTF-8")) }
                val key = q.lowercase()
                val url = j.optString("poster").takeIf { it.startsWith("/api/poster/img/") }
                if (url != null && !posterPrefs.contains(key)) {
                    posterPrefs.edit().putString(key, "${System.currentTimeMillis()}|$url").apply()
                    posterMemory[key] = url
                }
                if (url != null) notePoster(key, url)
                if (j.has("animation")) recordAnimation(key, j)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            if (++asked % 5 == 0) Animation.version++
            kotlinx.coroutines.delay(120)
        }
        Animation.version++
    }

    // ---- video info line ("1920×1080 · 12 Мбит/с · HEVC · 23.98 fps"), like on the website ----
    // The server runs ffprobe on the remote file, so analyses go strictly one at a time and only for tiles on
    // screen. A result is kept forever (the file does not change); a server-side failure is not retried for 6 h.
    private val mediaPrefs = app.getSharedPreferences("media_info", Context.MODE_PRIVATE)
    private val probeLock = Mutex()
    private val mediaInflight = HashMap<String, CompletableDeferred<String>>()

    /** Ready line for the tile, or null when it still has to be analysed. */
    private val mediaMemory = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The line if this start already had it (no disk, no parsing): what a tile may ask for while it is being composed. */
    fun memoryMediaLine(magnetId: Long, file: MFile): String? = mediaMemory["$magnetId|${file.path}"]

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
    private val watchMemory = java.util.concurrent.ConcurrentHashMap<String, WatchInfo>()
    // Files never watched: most of the library. Without this every look read the prefs again for each of them.
    private val watchMissing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Bumped when the app comes back from the player, so tiles re-read their progress. */
    var watchVersion by mutableStateOf(0)
        private set
    fun bumpWatch() {
        watchMemory.clear()
        watchMissing.clear()
        watchVersion++
    }

    fun watchInfo(key: String): WatchInfo? {
        watchMemory[key]?.let { return it }
        if (key in watchMissing) return null
        val v = watchPrefs.getString(key, null) ?: run { watchMissing += key; return null }
        val parts = v.split('|')
        if (parts.size < 3) return null
        val info = WatchInfo(parts[0].toLongOrNull() ?: return null, parts[1].toLongOrNull() ?: 0L, parts[2].toLongOrNull() ?: 0L)
        watchMemory[key] = info
        return info
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

    // ---- continue watching hidden entries ----
    private val continueHiddenPrefs = app.getSharedPreferences("continue_hidden", Context.MODE_PRIVATE)
    fun hideContinue(ids: List<Long>) {
        val now = System.currentTimeMillis()
        continueHiddenPrefs.edit().apply { ids.forEach { putLong(it.toString(), now) } }.apply()
        bumpWatch()
    }
    fun continueHiddenAt(id: Long): Long = continueHiddenPrefs.getLong(id.toString(), 0L)

    // ---- cleaning up after films that left the library (after every successful refresh) ----
    /**
     * Posters (and their picture files, unless another tile shows the same one), the video info line, watch
     * progress, the resume place, the last episode, and a reserve still kept on disk — of films no longer in the list.
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private suspend fun cleanUp(list: List<LibraryItem>) = withContext(Dispatchers.IO) {
        watchMemory.clear()
        episodeMemory.clear()
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
        prune(animPrefs, keep::keepsPoster)
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
    private val episodeMemory = java.util.concurrent.ConcurrentHashMap<Long, String>()
    fun lastEpisode(id: Long): String? = episodeMemory[id] ?: episodePrefs.getString(id.toString(), null)?.also { episodeMemory[id] = it }
    fun saveEpisode(id: Long, path: String) {
        episodeMemory[id] = path
        episodePrefs.edit().putString(id.toString(), path).apply()
    }
}

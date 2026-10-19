package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

enum class StatusKind { Info, Ok, Error }

val MEDIA_UNKNOWN: String get() = tr("library.razreshenie_n_d")

fun formatMediaInfo(m: JSONObject): String {
    val parts = ArrayList<String>()
    val w = m.optInt("width", 0); val h = m.optInt("height", 0)
    if (w > 0 && h > 0) parts += "$w×$h"
    val br = m.optDouble("bitrateMbps", 0.0)
    parts += if (br > 0) java.math.BigDecimal(br).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + tr("library.mbit_s") else tr("library.bitreyt_n_d")
    m.optString("codec").takeIf { it.isNotBlank() }?.let { parts += it.uppercase() }
    Regex("""^(\d+)/(\d+)$""").find(m.optString("frameRate"))?.let { mt ->
        val a = mt.groupValues[1].toDouble(); val b = mt.groupValues[2].toDouble()
        if (b > 0 && a / b > 0) parts += java.math.BigDecimal(a / b).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " fps"
    }
    return parts.joinToString(" · ")
}

/** Poster pictures on disk at most (see Library.prunePosterFiles). */
private const val POSTER_CAP = 100L shl 20
/** A poster picture not shown for this many days is deleted (it is downloaded again when the tile shows up). */
private const val POSTER_UNUSED_DAYS = 7L

/** Same behaviour as the Android LibraryStore, with files in %APPDATA%\SuslikMedia instead of SharedPreferences. */
class Library {
    private val snapshot = AppDir.file("library.json")
    private val libraryEtag = Store.of("library_etag") // version of the list above, for "not modified" answers
    private val posterIndex = Store.of("posters")
    private val animIndex = Store.of("poster_anim") // query -> "time|1" (TMDB genre Animation) or "time|0"
    private val mediaIndex = Store.of("media_info")
    private val episodes = Store.of("last_episode")
    val watch = Store.of("watch")

    var items by mutableStateOf<List<LibraryItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var status by mutableStateOf(tr("library.nazhmite_obnovit_chtoby_zagruz"))
        private set
    var statusKind by mutableStateOf(StatusKind.Info)
        private set
    var authenticated by mutableStateOf<Boolean?>(null)
    var watchVersion by mutableStateOf(0)
        private set
    fun bumpWatch() { watchVersion++ }

    private suspend fun publish(list: List<Magnet>) {
        items = withContext(Dispatchers.Default) { prepareItems(list) }
    }

    suspend fun restoreSnapshot() {
        if (items.isNotEmpty()) return
        val list = withContext(Dispatchers.IO) { runCatching { parseLibrary(JSONObject(snapshot.readText())) }.getOrNull() } ?: return
        publish(list)
        status = tr("library.pokazana_sohranennaya_kopiya_o")
    }

    suspend fun checkSession(): Boolean? = try {
        Api.get("/api/session").optBoolean("authenticated").also { authenticated = it }
    } catch (e: Exception) { null }

    suspend fun login(password: String): String? = try {
        Api.post("/api/login", JSONObject().put("password", password)); authenticated = true; null
    } catch (e: Exception) { Api.describe(e) }

    suspend fun logout() {
        runCatching { Api.post("/api/logout") }
        authenticated = false; items = emptyList()
        withContext(Dispatchers.IO) { runCatching { snapshot.delete() } }
    }

    suspend fun refresh() {
        if (loading) return
        loading = true
        status = tr("library.zagruzka_torrentov_i_faylov"); statusKind = StatusKind.Info
        try {
            // Unchanged since the last load (same ETag) = a tiny 304 instead of the whole list: nothing to redo.
            val known = libraryEtag.get("etag")?.takeIf { items.isNotEmpty() }
            val res = Api.getIfChanged("/api/library", known)
            if (res.code == 304) {
                status = tr("library.zagruzheno_size_torrent_ov", "size" to items.size); statusKind = StatusKind.Ok
                return
            }
            val json = res.json
            val list = withContext(Dispatchers.Default) { parseLibrary(json) }
            publish(list)
            cleanUp(items) // what films that left the library left behind (posters, progress, reserve…)
            withContext(Dispatchers.IO) { runCatching { snapshot.writeText(json.toString()) } }
            val stale = json.optBoolean("stale")
            libraryEtag.put("etag", if (stale) null else res.etag)
            status = if (stale) tr("library.alldebrid_ne_otvetil_pokazana", "size" to list.size) else tr("library.zagruzheno_size_torrent_ov", "size" to list.size)
            statusKind = if (stale) StatusKind.Info else StatusKind.Ok
        } catch (e: CancellationException) {
            status = tr("library.nazhmite_obnovit_chtoby_zagruz"); statusKind = StatusKind.Info
            throw e
        } catch (e: Exception) {
            if (e is Api.HttpError && e.code == 401) authenticated = false
            status = Api.describe(e); statusKind = StatusKind.Error
        } finally { loading = false }
    }

    // ---- cleaning up after films that left the library (after every new list) ----
    /**
     * Posters (and their picture files, unless another tile shows the same one), the video info line, watch progress,
     * the last episode, «Продолжить» hidden marks, and a reserve still kept on disk — of films no longer in the list.
     */
    private suspend fun cleanUp(list: List<LibraryItem>) = withContext(Dispatchers.IO) {
        val keep = LibraryKeep(list)
        if (!keep.usable) return@withContext
        var n = 0
        fun prune(store: Store, keepIt: (String) -> Boolean) { store.keys().filterNot(keepIt).forEach { store.put(it, null); n++ } }
        fun path(v: String?) = v?.substringAfter('|', "")?.ifBlank { null }
        val keys = posterIndex.keys()
        val keptPaths = keys.filter(keep::keepsPoster).mapNotNullTo(HashSet()) { path(posterIndex.get(it)) }
        val gonePaths = keys.filterNot(keep::keepsPoster).mapNotNull { path(posterIndex.get(it)) }.filter { it !in keptPaths }
        prune(posterIndex, keep::keepsPoster)
        gonePaths.forEach { runCatching { posterFile(it).delete() } }
        prune(mediaIndex, keep::keepsKey)
        prune(watch, keep::keepsKey)
        prune(episodes, keep::keepsKey)
        prune(continueHidden, keep::keepsKey)
        val reserves = BurstProxy.forgetRetired { it in keep.links }
        if (n + reserves > 0) PlayerLog.write("library cleanup: $n entries and ${gonePaths.size} poster(s) of films no longer in the library, $reserves reserve(s) on disk")
        prunePosterFiles()
    }

    /**
     * Poster pictures on disk: none that was not shown for 7 days, and at most 100 MB — the least recently shown go
     * first (showing one touches its file, see loadImage). Before, they only piled up.
     */
    fun prunePosterFiles() {
        val files = AppDir.file("posters").listFiles()?.filter { it.isFile }?.toMutableList() ?: return
        val old = System.currentTimeMillis() - POSTER_UNUSED_DAYS * 86_400_000L
        files.filter { it.lastModified() < old }.forEach { if (it.delete()) files.remove(it) }
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= POSTER_CAP) break
            total -= f.length(); f.delete()
        }
    }

    private fun posterFile(path: String): java.io.File {
        val name = MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
        return AppDir.file("posters").apply { mkdirs() }.resolve("$name.img")
    }

    /** Favourite on/off for this device (the server keeps it per device cookie). Updates the list at once. */
    suspend fun setFavorite(m: Magnet, on: Boolean): String? {
        val before = items
        items = items.map { if (it.m.id == m.id) it.copy(m = it.m.copy(favorite = on)) else it }
        return try {
            // The tile may leave the screen (and its scope end) right after the click: the request still has to go out.
            withContext(kotlinx.coroutines.NonCancellable) { Api.post("/api/favorites", JSONObject().put("id", m.id).put("favorite", on)) }; null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { items = before; Api.describe(e) }
    }

    suspend fun remove(m: Magnet): String? = try {
        Api.post("/api/magnets/${m.id}/${if (m.downloading) "cancel" else "delete"}")
        items = items.filter { it.m.id != m.id }
        null
    } catch (e: Exception) { Api.describe(e) }

    // ---- posters: lookup cached 30 days (3 for "none"), images cached as files ----
    private val posterGate = Semaphore(4)
    // Decoded posters are kept small (tile size) and only the last ~80 stay in memory.
    private val images = object : LinkedHashMap<String, ImageBitmap?>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 40
    }
    // An access-ordered map rewires its links even on get(): every touch goes under its lock (tiles read it on the
    // window's thread, posterFileFor for the media controls from elsewhere).
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<ImageBitmap?>>()

    fun cachedPoster(queries: List<String>): ImageBitmap? = synchronized(images) { queries.firstNotNullOfOrNull { images[it.lowercase()] } }

    suspend fun poster(queries: List<String>): ImageBitmap? {
        for (q in queries) posterFor(q)?.let { return it }
        return null
    }

    /**
     * The poster picture as a file with a proper extension (for Windows media controls, which need a file URI and
     * guess the format by name), fetched first if needed. Null when the film has no poster.
     * The image is downscaled to thumbnail size (max 320px) to prevent heavy IPC and UI freezes in Windows Shell.
     */
    suspend fun posterFileFor(queries: List<String>): java.io.File? {
        if (queries.isEmpty() || poster(queries) == null) return null
        val path = queries.firstNotNullOfOrNull { posterIndex.get(it.lowercase())?.substringAfter('|', "")?.ifBlank { null } } ?: return null
        return withContext(Dispatchers.IO) {
            val src = posterFile(path).takeIf { it.exists() } ?: return@withContext null
            val out = ru.suslikovanora.media.desktop.player.MediaControls.thumbFile(src.nameWithoutExtension + ".jpg")
            if (!out.exists() || out.lastModified() < src.lastModified() || out.length() == 0L) {
                val resized = runCatching {
                    val img = javax.imageio.ImageIO.read(src) ?: return@runCatching false
                    val maxDim = 320
                    val w = img.width
                    val h = img.height
                    val (nw, nh) = if (w > h) {
                        if (w > maxDim) maxDim to (h * maxDim / w).coerceAtLeast(1) else w to h
                    } else {
                        if (h > maxDim) (w * maxDim / h).coerceAtLeast(1) to maxDim else w to h
                    }
                    val dst = java.awt.image.BufferedImage(nw, nh, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    val g = dst.createGraphics()
                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
                    g.drawImage(img, 0, 0, nw, nh, null)
                    g.dispose()
                    javax.imageio.ImageIO.write(dst, "jpg", out)
                }.getOrDefault(false)
                if (!resized) {
                    val head = src.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
                    val ext = if (head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte()) "png" else "jpg"
                    val rawOut = if (ext == "jpg") out else ru.suslikovanora.media.desktop.player.MediaControls.thumbFile(src.nameWithoutExtension + "." + ext)
                    runCatching { src.copyTo(rawOut, overwrite = true) }
                    if (rawOut.exists() && rawOut.length() > 0L) return@withContext rawOut
                }
            }
            out.takeIf { it.exists() && it.length() > 0L }
        }
    }

    /** The server tells in the poster answer whether TMDB files the film under Animation; an old server just omits the field. */
    private fun recordAnimation(key: String, j: JSONObject) {
        if (!j.has("animation")) return
        val a = j.optBoolean("animation")
        animIndex.put(key, "${System.currentTimeMillis()}|${if (a) 1 else 0}")
        Animation.flags[key] = a
    }

    // The poster file a film resolved to. Two releases of one film under different names ("Новый Человек-паук…" and
    // "The Amazing Spider-Man 2") get the same TMDB poster: that is how they are recognised as one film.
    private val posterPaths = androidx.compose.runtime.mutableStateMapOf<String, String>()
    var posterVersion by mutableStateOf(0)
        private set

    fun posterPathOf(item: LibraryItem): String? = item.posterQueries.firstNotNullOfOrNull { posterPaths[it.lowercase()] }

    private fun notePoster(key: String, path: String) {
        if (posterPaths[key] != path) { posterPaths[key] = path; posterVersion++ }
    }

    /**
     * Fills what needs the server's idea of every film, not only of the tiles on screen: [Animation] (TMDB genre) and the
     * poster path used to merge versions. First from the saved answers, then asks about the films it has not seen
     * (slowly, a limited number per run). Films whose release name already says "мульт" / Pixar / anime need no genre.
     */
    suspend fun classifyAnimation() {
        var serverSendsGenre = true
        val todo = ArrayList<String>()
        for (item in items) {
            val needGenre = !(isCartoon(item) || isAnime(item))
            var genreKnown = !needGenre
            var posterKnown = false
            for (q in item.posterQueries) {
                val key = q.lowercase()
                animIndex.get(key)?.let { v ->
                    val at = v.substringBefore('|').toLongOrNull() ?: 0L
                    if (System.currentTimeMillis() - at < 60L * 86_400_000L) { Animation.flags[key] = v.substringAfter('|') == "1"; genreKnown = true }
                }
                posterIndex.get(key)?.substringAfter('|', "")?.takeIf { it.startsWith("/api/poster/img/") }?.let { notePoster(key, it); posterKnown = true }
            }
            if (!genreKnown || !posterKnown) item.posterQueries.firstOrNull()?.let { todo += it }
        }
        Animation.version++
        var asked = 0
        for (q in todo.distinct().take(80)) {
            try {
                val j = posterGate.withPermit { Api.get("/api/poster?q=" + URLEncoder.encode(q, "UTF-8")) }
                val key = q.lowercase()
                j.optString("poster").takeIf { it.startsWith("/api/poster/img/") }?.let { notePoster(key, it) }
                if (j.has("animation")) recordAnimation(key, j) else serverSendsGenre = false
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            if (++asked % 5 == 0) Animation.version++
            kotlinx.coroutines.delay(120)
        }
        Animation.version++
    }

    private suspend fun posterFor(query: String): ImageBitmap? {
        val key = query.lowercase()
        synchronized(images) { if (images.containsKey(key)) return images[key] }
        val own = CompletableDeferred<ImageBitmap?>()
        inflight.putIfAbsent(key, own)?.let { return it.await() }
        var img: ImageBitmap? = null
        try {
            img = posterGate.withPermit {
                var path: String? = null
                val stored = posterIndex.get(key)
                val at = stored?.substringBefore('|')?.toLongOrNull() ?: 0L
                val known = stored?.substringAfter('|', "")
                val fresh = stored != null && System.currentTimeMillis() - at < (if (known.isNullOrBlank()) 3L else 30L) * 86_400_000L
                if (fresh) path = known?.ifBlank { null }
                else {
                    val j = Api.get("/api/poster?q=" + URLEncoder.encode(query, "UTF-8"))
                    path = j.optString("poster").takeIf { it.startsWith("/api/poster/img/") }
                    recordAnimation(key, j)
                    posterIndex.put(key, "${System.currentTimeMillis()}|${path.orEmpty()}")
                }
                path?.let { notePoster(key, it); loadImage(it) }
            }
            synchronized(images) { images[key] = img }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
        } finally { inflight.remove(key); own.complete(img) }
        return img
    }

    private suspend fun loadImage(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val f = posterFile(path)
        // shown now: its file counts as recently used (prunePosterFiles deletes the least recently used first)
        val bytes = if (f.exists()) f.also { it.setLastModified(System.currentTimeMillis()) }.readBytes() else Api.bytes(path)?.also { runCatching { f.writeBytes(it) } }
        bytes?.let { runCatching { downscale(org.jetbrains.skia.Image.makeFromEncoded(it), 320) }.getOrNull() }
    }

    /** A 500 px TMDB poster is 1.5 MB decoded; at tile size (320 px) it is ~0.6 MB. */
    private fun downscale(src: org.jetbrains.skia.Image, maxW: Int): ImageBitmap {
        if (src.width <= maxW) return src.toComposeImageBitmap()
        val h = (src.height.toLong() * maxW / src.width).toInt()
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(maxW, h)
        surface.canvas.drawImageRect(
            src, org.jetbrains.skia.Rect.makeWH(src.width.toFloat(), src.height.toFloat()),
            org.jetbrains.skia.Rect.makeWH(maxW.toFloat(), h.toFloat()),
            org.jetbrains.skia.SamplingMode.MITCHELL, null, true
        )
        val out = surface.makeImageSnapshot().toComposeImageBitmap()
        surface.close(); src.close()
        return out
    }

    // ---- "1920×1080 · 12 Мбит/с" line (server ffprobe, one at a time) ----
    private val probeLock = Mutex()
    fun cachedMediaLine(id: Long, f: MFile): String? {
        val raw = mediaIndex.get("$id|${f.path}") ?: return null
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val failedAt = j.optLong("failedAt", 0L)
        if (failedAt > 0) return if (System.currentTimeMillis() - failedAt < 6 * 3_600_000L) MEDIA_UNKNOWN else null
        return formatMediaInfo(j)
    }

    suspend fun mediaLine(id: Long, f: MFile): String {
        cachedMediaLine(id, f)?.let { return it }
        val link = f.link ?: return MEDIA_UNKNOWN
        return try {
            val meta = probeLock.withLock {
                Api.post("/api/media-metadata", JSONObject().put("link", link).put("magnetId", id).put("path", f.path)).optJSONObject("metadata")
            }
            if (meta != null) { mediaIndex.put("$id|${f.path}", meta.toString()); formatMediaInfo(meta) } else MEDIA_UNKNOWN
        } catch (e: CancellationException) { throw e } catch (e: Api.HttpError) {
            if (e.code >= 500) mediaIndex.put("$id|${f.path}", JSONObject().put("failedAt", System.currentTimeMillis()).toString())
            MEDIA_UNKNOWN
        } catch (_: Exception) { MEDIA_UNKNOWN }
    }

    // ---- watch progress ("magnetId|path" -> "pos|len|at"), written by the player ----
    fun watchInfo(key: String): WatchInfo? {
        val p = watch.get(key)?.split('|') ?: return null
        if (p.size < 3) return null
        return WatchInfo(p[0].toLongOrNull() ?: return null, p[1].toLongOrNull() ?: 0L, p[2].toLongOrNull() ?: 0L)
    }

    fun lastWatched(m: Magnet): Pair<MFile, WatchInfo>? {
        var best: Pair<MFile, WatchInfo>? = null
        for (f in m.files) {
            if (f.isFolder) continue
            val w = watchInfo(watchKey(m.id, f)) ?: continue
            if (best == null || w.at > best.second.at) best = f to w
        }
        return best
    }

    // «Продолжить просмотр»: hidden entries. A hidden item comes back by itself once it is watched again (newer time).
    private val continueHidden = Store.of("continue_hidden")
    fun continueHiddenAt(id: Long): Long = continueHidden.get(id.toString())?.toLongOrNull() ?: 0L
    fun hideContinue(ids: List<Long>) {
        val now = System.currentTimeMillis().toString()
        ids.forEach { continueHidden.put(it.toString(), now) }
        bumpWatch()
    }

    fun lastEpisode(id: Long): String? = episodes.get(id.toString())
    fun saveEpisode(id: Long, path: String) = episodes.put(id.toString(), path)
}

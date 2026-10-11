package ru.suslikovanora.media.desktop

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

const val MEDIA_UNKNOWN = "разрешение: н/д"

fun formatMediaInfo(m: JSONObject): String {
    val parts = ArrayList<String>()
    val w = m.optInt("width", 0); val h = m.optInt("height", 0)
    if (w > 0 && h > 0) parts += "$w×$h"
    val br = m.optDouble("bitrateMbps", 0.0)
    parts += if (br > 0) java.math.BigDecimal(br).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " Мбит/с" else "битрейт н/д"
    m.optString("codec").takeIf { it.isNotBlank() }?.let { parts += it.uppercase() }
    Regex("""^(\d+)/(\d+)$""").find(m.optString("frameRate"))?.let { mt ->
        val a = mt.groupValues[1].toDouble(); val b = mt.groupValues[2].toDouble()
        if (b > 0 && a / b > 0) parts += java.math.BigDecimal(a / b).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " fps"
    }
    return parts.joinToString(" · ")
}

/** Same behaviour as the Android LibraryStore, with files in %APPDATA%\SuslikMedia instead of SharedPreferences. */
class Library {
    private val snapshot = AppDir.file("library.json")
    private val posterIndex = Store("posters")
    private val mediaIndex = Store("media_info")
    private val episodes = Store("last_episode")
    val watch = Store("watch")

    var items by mutableStateOf<List<LibraryItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var status by mutableStateOf("Нажмите «Обновить», чтобы загрузить медиатеку.")
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
        status = "Показана сохранённая копия — обновляем…"
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
        status = "Загрузка торрентов и файлов из AllDebrid…"; statusKind = StatusKind.Info
        try {
            val json = Api.get("/api/library")
            val list = withContext(Dispatchers.Default) { parseLibrary(json) }
            publish(list)
            withContext(Dispatchers.IO) { runCatching { snapshot.writeText(json.toString()) } }
            val stale = json.optBoolean("stale")
            status = if (stale) "AllDebrid не ответил — показана последняя копия (${list.size})." else "Загружено ${list.size} торрент(ов)."
            statusKind = if (stale) StatusKind.Info else StatusKind.Ok
        } catch (e: CancellationException) {
            status = "Нажмите «Обновить», чтобы загрузить медиатеку."; statusKind = StatusKind.Info
            throw e
        } catch (e: Exception) {
            if (e is Api.HttpError && e.code == 401) authenticated = false
            status = Api.describe(e); statusKind = StatusKind.Error
        } finally { loading = false }
    }

    /** Favourite on/off for this device (the server keeps it per device cookie). Updates the list at once. */
    suspend fun setFavorite(m: Magnet, on: Boolean): String? {
        val before = items
        items = items.map { if (it.m.id == m.id) it.copy(m = it.m.copy(favorite = on)) else it }
        return try {
            Api.post("/api/favorites", JSONObject().put("id", m.id).put("favorite", on)); null
        } catch (e: Exception) { items = before; Api.describe(e) }
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
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 80
    }
    private val inflight = HashMap<String, CompletableDeferred<ImageBitmap?>>()

    fun cachedPoster(queries: List<String>): ImageBitmap? = queries.firstNotNullOfOrNull { images[it.lowercase()] }

    suspend fun poster(queries: List<String>): ImageBitmap? {
        for (q in queries) posterFor(q)?.let { return it }
        return null
    }

    private suspend fun posterFor(query: String): ImageBitmap? {
        val key = query.lowercase()
        if (images.containsKey(key)) return images[key]
        inflight[key]?.let { return it.await() }
        val own = CompletableDeferred<ImageBitmap?>(); inflight[key] = own
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
                    path = Api.get("/api/poster?q=" + URLEncoder.encode(query, "UTF-8")).optString("poster").takeIf { it.startsWith("/api/poster/img/") }
                    posterIndex.put(key, "${System.currentTimeMillis()}|${path.orEmpty()}")
                }
                path?.let { loadImage(it) }
            }
            images[key] = img
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
        } finally { inflight.remove(key); own.complete(img) }
        return img
    }

    private suspend fun loadImage(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val name = MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
        val f = AppDir.file("posters").apply { mkdirs() }.resolve("$name.img")
        val bytes = if (f.exists()) f.readBytes() else Api.bytes(path)?.also { runCatching { f.writeBytes(it) } }
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
    private val continueHidden = Store("continue_hidden")
    fun continueHiddenAt(id: Long): Long = continueHidden.get(id.toString())?.toLongOrNull() ?: 0L
    fun hideContinue(ids: List<Long>) {
        val now = System.currentTimeMillis().toString()
        ids.forEach { continueHidden.put(it.toString(), now) }
        bumpWatch()
    }

    fun lastEpisode(id: Long): String? = episodes.get(id.toString())
    fun saveEpisode(id: Long, path: String) = episodes.put(id.toString(), path)
}

package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.core.auth.*

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
/** «Тачки 2» · фильм or «Тед Лассо» · сериал, сезон 2 — how a title suggested by the AI is shown. */
fun aiLabel(ai: AiTitle): String {
    val kind = when (ai.kind) { "movie" -> tr("library.ai_tip_film"); "series" -> tr("library.ai_tip_serial"); "collection" -> tr("library.ai_tip_sbornik"); else -> null }
    val title = "«${ai.base}»" + (kind?.let { " · $it" } ?: "")
    return if (ai.season != null) tr("library.ai_sezon", "title" to title, "season" to ai.season) else title
}

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
    /** When this user may ask the AI again (ms, 0 — now): from the library answer, or the server's 429. */
    var aiReadyAt by mutableStateOf(0L)
        private set
    var authenticated by mutableStateOf<Boolean?>(null)
    var watchVersion by mutableStateOf(0)
        private set
    fun bumpWatch() { watchVersion++ }

    /** Раздачи только с озвучкой: в медиатеке их нет, но предпросмотру на экране «Озвучка» нужны их файлы. */
    var voiceOvers: List<Magnet> = emptyList()
        private set

    private suspend fun publish(list: List<Magnet>) {
        voiceOvers = list.filter { isVoiceOverOnly(it) }
        items = withContext(Dispatchers.Default) { prepareItems(list.filterNot { isVoiceOverOnly(it) }) }
        CustomAudio.cleanupPending()
    }

    // Раздача только с аудио — это внешняя озвучка к фильму («Добавить» → «Озвучка»): она играет внутри фильма,
    // отдельной плиткой в медиатеке её не показываем. Такие озвучки — только .mka.
    private fun isVoiceOverOnly(m: Magnet): Boolean {
        val files = m.files.filter { !it.isFolder }
        return files.isNotEmpty() && files.all { ru.suslikovanora.media.core.audio.CustomAudio.isSupported(it.path) }
    }

    suspend fun restoreSnapshot() {
        if (items.isNotEmpty()) return
        val list = withContext(Dispatchers.IO) { runCatching { parseLibrary(JSONObject(snapshot.readText())) }.getOrNull() } ?: return
        publish(list)
        status = tr("library.pokazana_sohranennaya_kopiya_o")
    }

    var profile by mutableStateOf<Profile>(Profile.Shared)

    var pairing by mutableStateOf<DevicePairing?>(null)

    /** The server's user (shared parser: core/auth/Auth.kt); the shared password account is [Profile.Shared]. */
    private fun profileOf(u: UserProfile?): Profile {
        if (u == null || u.isShared) return Profile.Shared
        val note = if (u.isAdmin) tr("profile.admin") else tr("profile.member")
        return Profile(u.name.ifBlank { tr("profile.user") }, note, u.avatarUrl)
    }

    // The profile is set BEFORE [authenticated]: flipping it closes the login screen, and with it the coroutine that
    // called us (the old order lost the Discord name until the next start: «Вход по паролю» stayed in the sidebar).
    suspend fun checkSession(): Boolean? = try {
        val s = SessionInfo.parse(Api.get(SessionInfo.PATH))
        val isAuth = s.authenticated
        profile = if (isAuth) profileOf(s.user) else Profile.Shared
        CloudSync.userId = if (isAuth) s.user?.id else null
        // Discord application of our server: the player shows the film in the Discord profile under it
        s.discordClientId?.let { ru.suslikovanora.media.desktop.discord.DiscordRpc.clientId = it }
        authenticated = isAuth
        isAuth
    } catch (e: Exception) { null }

    suspend fun login(password: String): String? = try {
        Api.post("/api/login", JSONObject().put("password", password))
        if (checkSession() == null) authenticated = true
        null
    } catch (e: Exception) { Api.describe(e) }

    suspend fun startDeviceAuth(): String? = try {
        val res = Api.post(DeviceAuth.START, DeviceAuth.startBody("Suslik Media Windows", Api.SITE))
        // The server builds the link from site_url; ours is the fallback for an older server
        val p = DeviceAuth.parsePairing(res, Api.SITE)
        pairing = p
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(p.url)) }
        null
    } catch (e: Exception) {
        Api.describe(e)
    }

    suspend fun pollDeviceAuth(): Boolean? {
        val p = pairing ?: return null
        val r = try {
            DeviceAuth.parsePoll(Api.post(DeviceAuth.POLL, DeviceAuth.pollBody(p)))
        } catch (e: Api.HttpError) {
            // 410: the code has expired (10 min) — without this the screen kept waiting forever
            DeviceAuth.pollFailed(e.code)
        } catch (e: Exception) { DevicePoll.Pending }
        return when (r) {
            is DevicePoll.Authorized -> {
                // The device token outlives the cookie session (sent with every request, see Api.request).
                r.token?.let { Api.deviceToken = it }
                profile = profileOf(r.user)
                CloudSync.userId = r.user?.id
                pairing = null
                authenticated = true
                true
            }
            DevicePoll.Expired -> { pairing = null; false }
            DevicePoll.Pending -> null
        }
    }

    fun cancelDeviceAuth() {
        pairing = null
    }

    suspend fun logout() {
        ru.suslikovanora.media.desktop.party.Party.leave()
        runCatching { Api.post("/api/logout") }
        Api.deviceToken = null
        authenticated = false; items = emptyList()
        profile = Profile.Shared
        CloudSync.userId = null
        pairing = null
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
            aiReadyAt = aiTitleReadyAt(json)
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

    /** «Мои фильмы» on/off: only this user's list; the torrent stays in the shared library and in AllDebrid. */
    suspend fun setMine(m: Magnet, on: Boolean): String? {
        val before = items
        items = items.map { if (it.m.id == m.id) it.copy(m = it.m.copy(mine = on)) else it }
        return try {
            withContext(kotlinx.coroutines.NonCancellable) { Api.post(Shelf.PATH, Shelf.body(m.id, on)) }; null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { items = before; Api.describe(e) }
    }

    // ---- «Определить через ИИ» (server services/aititle.js): the torrents of one tile in one call, then accept / reject ----
    /** What the AI window shows: these torrents' answers, or just a [message] (cooldown, error). */
    class AiPanel(val ids: List<Long>, val message: String? = null)
    var aiPanel by mutableStateOf<AiPanel?>(null)
    var aiBusy by mutableStateOf(false)

    private fun setAi(id: Long, ai: AiTitle?) {
        items = items.map { if (it.m.id == id) it.withAiTitle(ai) else it }
    }

    suspend fun askAi(list: List<Magnet>) {
        if (list.isEmpty() || aiBusy) return
        aiBusy = true
        status = tr("library.ai_sprashivaem"); statusKind = StatusKind.Info
        try {
            val r = Api.post("/api/library/ai-title", JSONObject().put("ids", org.json.JSONArray(list.map { it.id })))
            aiReadyAt = r.optLong("aiTitleReadyAt", 0L) // у админа кулдауна нет: 0
            val res = r.optJSONArray("results") ?: org.json.JSONArray()
            val ids = ArrayList<Long>()
            for (i in 0 until res.length()) {
                val o = res.optJSONObject(i) ?: continue
                val id = o.optLong("id")
                setAi(id, parseAiTitle(o.optJSONObject("aiTitle")))
                ids += id
            }
            val found = items.count { it.m.id in ids && it.m.aiTitle?.status == "suggested" }
            status = if (found > 0) tr("library.ai_gotovo") else tr("library.ai_ne_smog"); statusKind = StatusKind.Ok
            aiPanel = AiPanel(ids, if (found == 0) tr("library.ai_ne_smog") else null)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val body = (e as? Api.HttpError)?.takeIf { it.code == 429 }?.body
            val sec = body?.optLong("retryAfterSec", 0L) ?: 0L
            val msg = if (sec > 0 && body?.optString("reason") == "cooldown") {
                aiReadyAt = System.currentTimeMillis() + sec * 1000
                tr("library.ai_podozhdite", "min" to (sec + 59) / 60)
            } else Api.describe(e)
            status = msg; statusKind = StatusKind.Error
            aiPanel = AiPanel(emptyList(), msg)
        } finally { aiBusy = false }
    }

    /**
     * id -> take the AI's title (true) or keep the regexes' one (false). Every torrent is saved even if one fails:
     * stopping halfway split a tile (some versions renamed, some not). All changes land in one go at the end.
     */
    suspend fun saveAi(decisions: Map<Long, Boolean>) {
        val done = HashMap<Long, AiTitle?>()
        var error: String? = null
        for ((id, accept) in decisions) {
            val cur = items.firstOrNull { it.m.id == id }?.m?.aiTitle ?: continue
            if (cur.status == (if (accept) "accepted" else "rejected")) continue
            try {
                val r = Api.post("/api/library/$id/ai-title/decision", JSONObject().put("accept", accept))
                done[id] = parseAiTitle(r.optJSONObject("aiTitle"))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = Api.describe(e)
            }
        }
        if (done.isNotEmpty()) items = items.map { if (it.m.id in done) it.withAiTitle(done[it.m.id]) else it }
        if (error != null) { status = error; statusKind = StatusKind.Error }
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
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<PosterAnswer>>()
    /** [known] = the server's answer (with or without a picture); false = the asking tile went away or the network failed. */
    private class PosterAnswer(val img: ImageBitmap?, val known: Boolean)

    fun cachedPoster(queries: List<String>): ImageBitmap? = synchronized(images) { queries.firstNotNullOfOrNull { images[it.lowercase()] } }

    suspend fun poster(queries: List<String>): ImageBitmap? {
        for (q in queries) posterFor(q)?.let { return it }
        return null
    }

    /**
     * Resolves a public poster URL (e.g. TMDB CDN) for Discord Rich Presence from cached index.
     * Instantaneous and non-blocking.
     */
    fun posterUrl(queries: List<String>): String? {
        for (q in queries) {
            val stored = posterIndex.get(q.lowercase()) ?: continue
            val path = stored.substringAfter('|', "").ifBlank { null } ?: continue
            toPublicPosterUrl(path)?.let { return it }
        }
        return null
    }

    /**
     * Resolves a public poster URL, fetching from server if not cached yet.
     */
    suspend fun posterUrlFor(queries: List<String>): String? {
        posterUrl(queries)?.let { return it }
        if (queries.isEmpty()) return null
        poster(queries)
        return posterUrl(queries)
    }

    fun toPublicPosterUrl(path: String): String? {
        if (path.startsWith("https://") || path.startsWith("http://")) return path
        val file = path.substringAfterLast('/').trim()
        if (file.isEmpty() || !file.contains('.')) return null
        return "https://image.tmdb.org/t/p/w500/$file"
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
    /** Dolby Vision / HDR / DTS of a film as ffprobe on the server found them (until it answers — guessed from the names, see [itemTags]). */
    private val serverTags = androidx.compose.runtime.mutableStateMapOf<Long, List<String>>()
    fun tagsOf(item: LibraryItem): List<String> = serverTags[item.m.id] ?: itemTags(item)

    /** Asks the server for the marks; it analyses new films one by one in the background, so ask again while it has some left. */
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
        while (true) {
            synchronized(images) { if (images.containsKey(key)) return images[key] }
            val own = CompletableDeferred<PosterAnswer>()
            val other = inflight.putIfAbsent(key, own)
            if (other != null) {
                // Another tile of the same film is loading it. If that tile was scrolled away (its loading cancelled)
                // or the network failed, this one asks by itself instead of staying without a poster.
                val a = other.await()
                if (a.known) return a.img
                continue
            }
            var img: ImageBitmap? = null
            var known = false
            try {
                img = posterGate.withPermit {
                    var path: String? = null
                    val stored = posterIndex.get(key)
                    val at = stored?.substringBefore('|')?.toLongOrNull() ?: 0L
                    val storedPath = stored?.substringAfter('|', "")
                    val fresh = stored != null && System.currentTimeMillis() - at < (if (storedPath.isNullOrBlank()) 3L else 30L) * 86_400_000L
                    if (fresh) path = storedPath?.ifBlank { null }
                    else {
                        val j = Api.get("/api/poster?q=" + URLEncoder.encode(query, "UTF-8"))
                        path = j.optString("poster").takeIf { it.startsWith("/api/poster/img/") }
                        recordAnimation(key, j)
                        posterIndex.put(key, "${System.currentTimeMillis()}|${path.orEmpty()}")
                    }
                    path?.let { notePoster(key, it); loadImage(it) }
                }
                known = true
                // Only a real answer is remembered: a network failure is asked again next time.
                synchronized(images) { images[key] = img }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
            } finally { inflight.remove(key, own); own.complete(PosterAnswer(img, known)) }
            return img
        }
    }

    /** What the «Продолжить просмотр» banner shows besides the title: TMDB's wide picture, description, year, rating. */
    class HeroInfo(val backdrop: ImageBitmap?, val overview: String?, val year: String?, val rating: Double?, val logo: ImageBitmap? = null)
    private val heroes = java.util.concurrent.ConcurrentHashMap<String, HeroInfo>()

    fun cachedHero(queries: List<String>): HeroInfo? = queries.firstNotNullOfOrNull { heroes[it.lowercase()] }

    /** Null while the server cannot say (offline, an old server without these fields): the banner then shows what it has. */
    suspend fun hero(queries: List<String>): HeroInfo? {
        cachedHero(queries)?.let { return it }
        for (q in queries) {
            try {
                val j = posterGate.withPermit { Api.get("/api/poster?hero=1&q=" + URLEncoder.encode(q, "UTF-8")) }
                if (!j.has("backdrop") && !j.has("overview")) continue
                val bd = j.optString("backdrop").takeIf { it.startsWith("/api/poster/img/") }
                val img = bd?.let { loadHeroImage(it) }
                val logo = j.optString("logo").takeIf { it.startsWith("/api/poster/img/") }?.let { runCatching { loadHeroImage(it, 500) }.getOrNull() }
                val info = HeroInfo(
                    img, j.optString("overview").ifBlank { null }, j.optString("year").ifBlank { null },
                    j.optDouble("rating", 0.0).takeIf { it > 0 }, logo
                )
                heroes[q.lowercase()] = info
                return info
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        return null
    }

    private suspend fun loadHeroImage(path: String, maxW: Int = 1280): ImageBitmap? = withContext(Dispatchers.IO) {
        val f = posterFile(path)
        val bytes = if (f.exists()) f.also { it.setLastModified(System.currentTimeMillis()) }.readBytes() else Api.bytes(path)?.also { runCatching { f.writeBytes(it) } }
        bytes?.let { runCatching { downscale(org.jetbrains.skia.Image.makeFromEncoded(it), maxW) }.getOrNull() }
    }

    private suspend fun loadImage(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val f = posterFile(path)
        // shown now: its file counts as recently used (prunePosterFiles deletes the least recently used first)
        val bytes = if (f.exists()) f.also { it.setLastModified(System.currentTimeMillis()) }.readBytes() else Api.bytes(path)?.also { runCatching { f.writeBytes(it) } }
        bytes?.let { runCatching { downscale(org.jetbrains.skia.Image.makeFromEncoded(it), 320) }.getOrNull() }
    }

    /**
     * The poster at the size it was downloaded (TMDB 500 px), decoded right away, for the player's first moments (the
     * tiles keep a 320 px copy, too soft at the window's height). Null when the film has no poster on disk yet.
     */
    suspend fun posterLarge(queries: List<String>): ImageBitmap? = withContext(Dispatchers.IO) {
        val path = queries.firstNotNullOfOrNull { posterIndex.get(it.lowercase())?.substringAfter('|', "")?.ifBlank { null } }
            ?: return@withContext null
        val f = posterFile(path).takeIf { it.exists() } ?: return@withContext null
        runCatching {
            val src = org.jetbrains.skia.Image.makeFromEncoded(f.readBytes())
            // drawn into a raster picture here, so the window does not decode the file on its first frame
            val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(src.width, src.height)
            surface.canvas.drawImage(src, 0f, 0f)
            val out = surface.makeImageSnapshot().toComposeImageBitmap()
            surface.close(); src.close()
            out
        }.getOrNull()
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

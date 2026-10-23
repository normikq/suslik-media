package ru.suslikovanora.media.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import ru.suslikovanora.media.AppLog
import ru.suslikovanora.media.core.MirrorLink
import ru.suslikovanora.media.net.Api
import ru.suslikovanora.media.tr

/**
 * Manages audio tracks, subtitles (internal and external sidecars),
 * user preferences per series/globally, and track restoration on reconnect.
 */
class PlayerTrackSelector(
    private val context: Context,
    private val handler: Handler,
    private val callback: Callback
) {
    interface Callback {
        fun onAudioTrackChanged()
        fun onSubtitleTrackChanged()
        /** [generation] is still the current player ([PlayerEngine.generation]). */
        fun isPlayerAlive(generation: Int): Boolean
        fun getCurrentTitle(): String
        fun getCurrentPlayer(): PlayerEngine?
        /** "m<magnetId>" фильма из библиотеки (общий с ПК ключ выбора и синхронизации) или null. */
        fun getFilmKey(): String?
    }

    companion object {
        const val SPU_OFF = "__off__"
        private const val SIDECAR_NONE = "__none__"
        private const val PENDING_MS = 30_000L // a picked voice-over file not open by then: «＋» works again
    }

    private val prefs by lazy { context.getSharedPreferences("player_ui", Context.MODE_PRIVATE) }

    private var selectedAudioId: Int? = null
    private var selectedSpuId: Int? = null
    private var restoreTracks = false
    private var prefsAppliedForUrl = ""
    private var prefsUntil = 0L // remembered tracks may still be applied until then

    // External files per episode, by the episode's `link`: signed mirror links (subtitles, voice-overs). The first
    // episode's come signed from the play sheet; the playlist's following ones are signed when they start
    // ([signSidecars]) — before, the next episode played without its voice-overs and subtitles.
    private class EpisodeFiles(val subs: List<String>, val audio: List<String>)
    private val episodeFiles = HashMap<String, EpisodeFiles>()
    private var toSign = JSONObject()
    private var filmUrl = "" // the file being played (names its voice-overs, see [audioLabel])
    private fun keyOf(url: String) = MirrorLink.param(url, "link") ?: url.substringBefore('?')
    private fun filesOf(url: String) = episodeFiles[keyOf(url)]
    private val audioUrls: List<String> get() = filesOf(filmUrl)?.audio.orEmpty()

    // Voice-over files beside the film: only ONE is opened with it (the remembered one, else the first). Opening all of them
    // at once made VLC fetch every file's head and tail before the first frame (16.10: 7 files, ~250 MB, 23 s to start).
    // The rest are added when picked in the audio menu.
    private var wrapUrl: ((String) -> String)? = null
    private val attachedAudio = LinkedHashSet<String>() // in the order they were opened: their tracks come after the film's
    private var activeSidecar: String? = null // label of the picked file; SIDECAR_NONE = an internal track
    private var sidecarsActive = false

    private val prefsRunnable = Runnable {
        val p = callback.getCurrentPlayer() ?: return@Runnable
        applyPreferredTracks(p, callback.getCurrentTitle())
    }

    val selectedAudio: Int? get() = selectedAudioId
    val selectedSpu: Int? get() = selectedSpuId

    fun readSideExtras(i: Intent?) {
        episodeFiles.clear()
        val url = i?.getStringExtra(PlayerExtras.URL).orEmpty()
        val subs = i?.getStringArrayListExtra(PlayerExtras.SUB_URLS)?.filter { it.startsWith("https://") }.orEmpty()
        val audio = i?.getStringArrayListExtra(PlayerExtras.AUDIO_URLS)?.filter { it.startsWith("https://") }.orEmpty()
        if (url.isNotEmpty()) episodeFiles[keyOf(url)] = EpisodeFiles(subs, audio)
        CustomAudioInfo.read(i?.getStringExtra(PlayerExtras.CUSTOM_AUDIO))
        toSign = i?.getStringExtra(PlayerExtras.EPISODE_SIDES)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    }

    /** [url] is a playlist episode with subtitles / voice-overs that are not signed yet ([signSidecars] first). */
    fun needsSidecars(url: String): Boolean = filesOf(url) == null && toSign.has(keyOf(url))

    /** Signs [url]'s subtitles and voice-overs on the mirror; the ones that fail are left out. */
    suspend fun signSidecars(url: String) {
        val key = keyOf(url)
        val sides = toSign.optJSONObject(key) ?: return
        fun links(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }
        val enc = { v: String -> URLEncoder.encode(v, "UTF-8") }
        suspend fun sign(list: List<JSONObject>) = coroutineScope {
            list.map { f ->
                async {
                    runCatching {
                        Api.get("/api/file/mirror-url?link=${enc(f.getString("link"))}&name=${enc(f.optString("name"))}").optString("url")
                            .takeIf { it.startsWith("https://") }
                    }.getOrNull()
                }
            }.mapNotNull { it.await() }
        }
        val signed = sides.optJSONArray("signed")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.startsWith("https://") } }.orEmpty()
        episodeFiles[key] = EpisodeFiles(sign(links(sides.optJSONArray("subs"))), sign(links(sides.optJSONArray("audio"))) + signed)
    }

    fun resetSelectedTracks() {
        selectedAudioId = null
        selectedSpuId = null
    }

    fun prepareForReconnect() {
        restoreTracks = selectedAudioId != null || selectedSpuId != null
    }

    /** What [sidecarsFor] picked for the file being opened: voice-over (through the reserve) and subtitles. */
    class Sidecars(val audio: List<String>, val subtitles: List<String>)

    fun onPlaying(p: PlayerEngine, currentUrl: String, currentTitle: String) {
        if (prefsAppliedForUrl != currentUrl && !restoreTracks) {
            prefsAppliedForUrl = currentUrl
            // Remembered tracks: applied as soon as they are known, and again when VLC reports a new one
            // (voice-over files arrive a little later) — instead of one fixed 1.5 s guess.
            prefsUntil = SystemClock.uptimeMillis() + 8_000L
            handler.removeCallbacks(prefsRunnable)
            handler.postDelayed(prefsRunnable, 300L)
        }
        if (restoreTracks) {
            restoreTracks = false
            val generation = p.generation
            handler.postDelayed({
                if (callback.isPlayerAlive(generation)) restoreSelectedTracks(p)
            }, 700L)
        }
    }

    fun onESAdded() {
        // The file picked as «＋ …» is open once the player lists one track more than before the pick.
        if (pendingAudio != null && (callback.getCurrentPlayer()?.audioTracks()?.size ?: 0) > pendingBase) {
            AppLog.write("[track] voice-over file «$pendingAudio» is open")
            pendingAudio = null
        }
        callback.getCurrentPlayer()?.let { syncAudioDelay(it) }
        if (SystemClock.uptimeMillis() < prefsUntil) {
            handler.removeCallbacks(prefsRunnable)
            handler.postDelayed(prefsRunnable, 400L)
        }
    }

    fun stop() {
        handler.removeCallbacks(prefsRunnable)
    }

    fun fixSound(p: PlayerEngine) {
        val id = p.audioTrack.takeIf { it != -1 } ?: p.audioTracks().firstOrNull { it.id != -1 }?.id ?: return
        runCatching {
            p.setAudioTrack(-1)
            p.setAudioTrack(id)
        } // restarts the audio decoder and output
        selectedAudioId = id
    }

    fun restoreSelectedTracks(p: PlayerEngine) {
        runCatching {
            selectedAudioId?.let { id -> if (p.audioTracks().any { it.id == id }) p.setAudioTrack(id) }
            selectedSpuId?.let { id -> if (id == -1 || p.subtitleTracks().any { it.id == id }) p.setSubtitleTrack(id) }
        }
    }

    private fun extraAudio(): List<String> = if (sidecarsActive) audioUrls.filter { it !in attachedAudio } else emptyList()

    // A voice-over file picked as «＋ …» that the player is still opening (Media3 reopens the film with it: meanwhile it
    // lists no tracks at all). 18.10 journal: each «＋» vanished at once, so all seven were tapped in 3 s — seven reopens
    // of the film, and the sound came back after 16 s on a track nobody picked last.
    private var pendingAudio: String? = null
    private var pendingAt = 0L
    private var pendingBase = 0
    private fun pending(): String? = pendingAudio?.takeIf { SystemClock.uptimeMillis() - pendingAt < PENDING_MS }

    /** The audio sheet: the film's tracks, the file being opened («⏳ rus»), then the files not opened yet («＋ rus»). */
    fun audioChoices(p: PlayerEngine?): List<TrackChoice> {
        val tracks = p?.audioTracks().orEmpty()
        val current = p?.audioTrack ?: -1
        return tracks.map { TrackChoice(it.name, it.id == current) } + listOfNotNull(pending()?.let { TrackChoice("⏳ $it", false) }) +
            extraAudio().map { TrackChoice("＋ " + audioLabel(it), false) }
    }

    /** [which] is a line of [audioChoices]. */
    fun pickAudio(p: PlayerEngine?, which: Int) {
        val tracks = p?.audioTracks().orEmpty()
        val extra = extraAudio()
        if (which >= tracks.size) {
            val busy = pending()
            if (busy != null) {
                AppLog.write("[track] voice-over line $which ignored: «$busy» is still opening")
                return
            }
            val u = extra.getOrNull(which - tracks.size)
            if (u == null) { AppLog.write("[track] voice-over line $which of ${tracks.size}+${extra.size}: no such file"); return }
            attachedAudio += u
            activeSidecar = audioLabel(u)
            pendingAudio = activeSidecar; pendingAt = SystemClock.uptimeMillis(); pendingBase = tracks.size
            AppLog.write("[track] voice-over file «${audioLabel(u)}» added from the menu · film tracks before: " +
                tracks.joinToString { it.name }.ifEmpty { "none" } + " · files left: ${extra.size - 1}")
            selectedAudioId = null
            p?.addAudioFile(wrap(u))
            saveSidecar()
            callback.onAudioTrackChanged()
            return
        }
        val track = tracks.getOrNull(which) ?: return
        selectedAudioId = track.id
        p?.setAudioTrack(track.id)
        p?.let { syncAudioDelay(it) }
        // A track of an opened voice-over file is that file, not the film's own track: before, picking it wrote «__none__»
        // and the next open came without the file (24.10 journal: «OnWave» was not there again).
        val file = p?.audioFileOf(track.id, attachedAudio.size)?.let { attachedAudio.elementAtOrNull(it) }
        if (file != null) {
            activeSidecar = audioLabel(file)
            AppLog.write("[track] voice-over file «$activeSidecar» picked")
            saveSidecar()
            callback.onAudioTrackChanged()
            return
        }
        activeSidecar = SIDECAR_NONE
        prefs.edit().apply { prefKeys().forEach { putString("track:sidecar:$it", SIDECAR_NONE) } }.apply()
        callback.onAudioTrackChanged()
        saveTrackPref("audio", track.name)
    }

    /**
     * The picked voice-over file is remembered for this film both as the file and as the audio name: the PC knows only the
     * name (it sends `audio` and never `sidecar`), so with the name it picks the same voice-over too — and back ([fileNamed]).
     */
    private fun saveSidecar() {
        val label = activeSidecar ?: return
        prefs.edit().apply { prefKeys().forEach { putString("track:sidecar:$it", label).putString("track:audio:$it", label) } }
            .remove("track:audio:*").apply()
        ru.suslikovanora.media.library.CloudSync.noteTrack(callback.getFilmKey(), label, null, label, alsoAny = false)
    }

    /** The subtitles sheet: «off» first, then the tracks. */
    fun subtitleChoices(p: PlayerEngine?): List<TrackChoice> {
        val tracks = p?.subtitleTracks().orEmpty().filter { it.id != -1 }
        val current = p?.subtitleTrack ?: -1
        return listOf(TrackChoice(tr("playertrack.vyklyuchit_subtitry"), current == -1)) + tracks.map { TrackChoice(it.name, it.id == current) }
    }

    /** [which] is a line of [subtitleChoices]. */
    fun pickSubtitle(p: PlayerEngine?, which: Int) {
        if (which == 0) {
            selectedSpuId = -1
            p?.setSubtitleTrack(-1)
            saveTrackPref("spu", SPU_OFF)
        } else {
            val track = p?.subtitleTracks().orEmpty().filter { it.id != -1 }.getOrNull(which - 1) ?: return
            selectedSpuId = track.id
            p?.setSubtitleTrack(track.id)
            saveTrackPref("spu", track.name)
        }
        callback.onSubtitleTrackChanged()
    }

    fun allEpisodeUrls(url: String): List<String> =
        listOf(url) + filesOf(url)?.let { it.audio + it.subs }.orEmpty()

    fun hasExternalAudio(url: String): Boolean = filesOf(url)?.audio?.isNotEmpty() == true

    /** The external files to open with [url]: the one voice-over (see [attachedAudio]) and all subtitles. */
    fun sidecarsFor(url: String, wrapUrl: (String) -> String): Sidecars {
        filmUrl = url
        val files = filesOf(url)
        sidecarsActive = files?.audio?.isNotEmpty() == true
        attachedAudio.clear()
        if (files == null) return Sidecars(emptyList(), emptyList())
        this.wrapUrl = wrapUrl
        // The film's remembered audio naming one of the files (picked on the PC: the server keeps an older «__none__» in
        // `sidecar` then — 24.10 journal, Japanese played instead of «OnWave») wins over `sidecar`.
        val want = activeSidecar ?: fileNamed(pref("audio"))?.let { audioLabel(it) } ?: pref("sidecar")
        pendingAudio = null
        val pick = if (want == SIDECAR_NONE) null
            else audioUrls.firstOrNull { audioLabel(it) == want } ?: audioUrls.firstOrNull { fileLabel(it) == want } ?: audioUrls.firstOrNull()
        val audio = if (pick != null) {
            attachedAudio += pick
            activeSidecar = audioLabel(pick)
            listOf(wrap(pick))
        } else emptyList()
        return Sidecars(audio, files.subs)
    }

    /**
     * "Film.rus.mka" beside "Film.mkv" gives "rus"; several files with the same name (one per studio, each in its own
     * folder) are numbered: "rus 1", "rus 2" — 18.10, four «＋ rus» in a row.
     */
    private fun wrap(u: String): String = (wrapUrl?.invoke(u) ?: u).also { CustomAudioInfo.noteWrapped(u, it) }

    /**
     * VLC: the delay of the voice-over being heard (an uploaded one with `offsetMs`), 0 for the film's own tracks.
     * Tracks of the opened files follow the film's, in [attachedAudio] order. Media3 shifts the file itself.
     */
    fun syncAudioDelay(p: PlayerEngine) {
        if (pending() != null) return
        val file = p.audioFileOf(p.audioTrack, attachedAudio.size)?.let { attachedAudio.elementAtOrNull(it) }
        runCatching { p.setAudioDelayMs(file?.let { CustomAudioInfo.of(it)?.offsetMs } ?: 0L) }
    }

    /** The file whose menu name is [name] (on the PC an uploaded voice-over without a language may lose « [xx]», see CustomAudio.byMenuName). */
    private fun fileNamed(name: String?): String? {
        if (name.isNullOrBlank() || name == SIDECAR_NONE) return null
        return audioUrls.firstOrNull { u -> val l = audioLabel(u); l == name || l.substringBeforeLast(" [") == name || name.substringBeforeLast(" [") == l }
    }

    private fun audioLabel(u: String): String {
        CustomAudioInfo.of(u)?.title?.takeIf { it.isNotBlank() }?.let { return it }
        val name = fileLabel(u)
        val same = audioUrls.filter { fileLabel(it) == name }
        return if (same.size > 1) "$name ${same.indexOf(u) + 1}" else name
    }

    private fun fileLabel(u: String): String {
        val name = (Uri.parse(u).lastPathSegment ?: u).substringBeforeLast('.')
        val film = Uri.parse(filmUrl).lastPathSegment.orEmpty().substringBeforeLast('.')
        val cut = if (film.isNotEmpty() && name.startsWith(film)) name.substring(film.length) else name
        return cut.trim('.', ' ', '_', '-').ifEmpty { name }
    }

    private fun seriesKey(title: String) = title.substringBefore(" · ").trim().lowercase(Locale.ROOT)

    /**
     * Где помнится выбор: фильм библиотеки ("m<id>" — как на ПК, его же синхронизирует [CloudSync] по аккаунту), затем
     * название (так было до синхронизации и для файлов не из библиотеки).
     */
    private fun prefKeys(): List<String> = listOfNotNull(callback.getFilmKey(), seriesKey(callback.getCurrentTitle()))
    private fun pref(kind: String): String? = prefKeys().firstNotNullOfOrNull { prefs.getString("track:$kind:$it", null) }

    private fun saveTrackPref(kind: String, name: String) {
        prefs.edit().apply { prefKeys().forEach { putString("track:$kind:$it", name) } }.putString("track:$kind:*", name).apply()
        val sidecar = if (kind == "audio") SIDECAR_NONE else null // своя дорожка фильма, не файл рядом
        ru.suslikovanora.media.library.CloudSync.noteTrack(callback.getFilmKey(),
            name.takeIf { kind == "audio" }, name.takeIf { kind == "spu" }, sidecar, alsoAny = true)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun applyPreferredTracks(p: PlayerEngine, currentTitle: String) {
        val audio = pref("audio") ?: prefs.getString("track:audio:*", null)
        val spu = pref("spu") ?: prefs.getString("track:spu:*", null)
        // The remembered voice-over file is opened with the film ([sidecarsFor]), but neither engine makes its track the
        // current one by itself: the film's own track played and the file sat unused.
        val sidecar = activeSidecar?.takeIf { it != SIDECAR_NONE }
        val fileIdx = sidecar?.let { s -> attachedAudio.indexOfFirst { audioLabel(it) == s } }?.takeIf { it >= 0 }
        runCatching {
            if (fileIdx != null) {
                val files = attachedAudio.size
                if (p.audioFileOf(p.audioTrack, files) != fileIdx) p.audioFileTrack(fileIdx, files)?.let { id ->
                    p.setAudioTrack(id)
                    selectedAudioId = id
                    syncAudioDelay(p)
                    AppLog.write("[track] voice-over file «$sidecar» made current")
                    callback.onAudioTrackChanged()
                }
            } else pickTrack(p.audioTracks(), audio)?.let { id ->
                if (id != p.audioTrack) {
                    p.setAudioTrack(id)
                    selectedAudioId = id
                    syncAudioDelay(p)
                    callback.onAudioTrackChanged()
                }
            }
            if (spu == SPU_OFF) {
                if (p.subtitleTrack != -1) {
                    p.setSubtitleTrack(-1)
                    selectedSpuId = -1
                }
            } else {
                pickTrack(p.subtitleTracks(), spu)?.let { id ->
                    if (id != p.subtitleTrack) {
                        p.setSubtitleTrack(id)
                        selectedSpuId = id
                    }
                }
            }
        }
    }

    /** Same name first, then the same language tag ("[Russian]"), then a name that contains the old one. */
    private fun pickTrack(tracks: List<EngineTrack>, wanted: String?): Int? {
        if (wanted.isNullOrBlank()) return null
        val list = tracks.filter { it.id != -1 }
        list.firstOrNull { it.name == wanted }?.let { return it.id }
        val lang = Regex("""\[([^\]]+)]""").find(wanted)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        if (lang != null) list.firstOrNull { it.name.lowercase(Locale.ROOT).contains("[$lang]") }?.let { return it.id }
        val core = wanted.lowercase(Locale.ROOT).replace(Regex("""^(track|дорожка)\s*\d+\s*-\s*"""), "").trim()
        if (core.length >= 3) list.firstOrNull { it.name.lowercase(Locale.ROOT).contains(core) }?.let { return it.id }
        return null
    }
}

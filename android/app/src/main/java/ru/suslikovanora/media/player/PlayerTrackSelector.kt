package ru.suslikovanora.media.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.widget.Toast
import java.util.Locale
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
        /** [generation] is still the current player ([PlayerEngine.generation]). */
        fun isPlayerAlive(generation: Int): Boolean
        fun getCurrentTitle(): String
        fun getCurrentPlayer(): PlayerEngine?
    }

    companion object {
        const val SPU_OFF = "__off__"
        const val EXTRA_SUB_URLS = "media_sub_urls"
        const val EXTRA_AUDIO_URLS = "media_audio_urls"
        const val EXTRA_URL = "media_url"
        private const val SIDECAR_NONE = "__none__"
    }

    private val prefs by lazy { context.getSharedPreferences("player_ui", Context.MODE_PRIVATE) }

    private var selectedAudioId: Int? = null
    private var selectedSpuId: Int? = null
    private var restoreTracks = false
    private var prefsAppliedForUrl = ""
    private var prefsUntil = 0L // remembered tracks may still be applied until then

    private var subUrls: List<String> = emptyList()
    private var audioUrls: List<String> = emptyList()
    private var subsForUrl = ""

    // Voice-over files beside the film: only ONE is opened with it (the remembered one, else the first). Opening all of them
    // at once made VLC fetch every file's head and tail before the first frame (16.10: 7 files, ~250 MB, 23 s to start).
    // The rest are added when picked in the audio menu.
    private var wrapUrl: ((String) -> String)? = null
    private val attachedAudio = HashSet<String>()
    private var activeSidecar: String? = null // label of the picked file; SIDECAR_NONE = an internal track
    private var sidecarsActive = false

    private val prefsRunnable = Runnable {
        val p = callback.getCurrentPlayer() ?: return@Runnable
        applyPreferredTracks(p, callback.getCurrentTitle())
    }

    val selectedAudio: Int? get() = selectedAudioId
    val selectedSpu: Int? get() = selectedSpuId

    fun readSideExtras(i: Intent?) {
        subUrls = i?.getStringArrayListExtra(EXTRA_SUB_URLS)?.filter { it.startsWith("https://") }.orEmpty()
        audioUrls = i?.getStringArrayListExtra(EXTRA_AUDIO_URLS)?.filter { it.startsWith("https://") }.orEmpty()
        subsForUrl = i?.getStringExtra(EXTRA_URL).orEmpty()
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

    fun showAudioTracks(activity: Activity, p: PlayerEngine?, onControlsNeeded: () -> Unit) {
        val tracks = p?.audioTracks().orEmpty()
        val extra = if (sidecarsActive) audioUrls.filter { it !in attachedAudio } else emptyList()
        if (tracks.isEmpty() && extra.isEmpty()) {
            Toast.makeText(activity, tr("playertrack.audiodorozhki_poka_ne_opredele"), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity).setTitle(tr("playertrack.audiodorozhka"))
            .setItems((tracks.map { it.name } + extra.map { "＋ " + audioLabel(it) }).toTypedArray()) { _, which ->
                if (which >= tracks.size) {
                    val u = extra[which - tracks.size]
                    attachedAudio += u
                    activeSidecar = audioLabel(u)
                    selectedAudioId = null
                    p?.addAudioFile(wrapUrl?.invoke(u) ?: u)
                    val key = seriesKey(callback.getCurrentTitle())
                    prefs.edit().putString("track:sidecar:$key", activeSidecar)
                        .remove("track:audio:$key").remove("track:audio:*").apply()
                    callback.onAudioTrackChanged()
                    onControlsNeeded()
                    return@setItems
                }
                val track = tracks[which]
                selectedAudioId = track.id
                p?.setAudioTrack(track.id)
                activeSidecar = SIDECAR_NONE
                prefs.edit().putString("track:sidecar:${seriesKey(callback.getCurrentTitle())}", SIDECAR_NONE).apply()
                callback.onAudioTrackChanged()
                saveTrackPref("audio", track.name, callback.getCurrentTitle())
                onControlsNeeded()
            }.setNegativeButton(tr("player.zakryt"), null).show()
    }

    fun showSubtitleTracks(activity: Activity, p: PlayerEngine?, onControlsNeeded: () -> Unit) {
        val tracks = p?.subtitleTracks().orEmpty()
        val labels = listOf(tr("playertrack.vyklyuchit_subtitry")) + tracks.map { it.name }
        AlertDialog.Builder(activity).setTitle(tr("playertrack.subtitry"))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    selectedSpuId = -1
                    p?.setSubtitleTrack(-1)
                    saveTrackPref("spu", SPU_OFF, callback.getCurrentTitle())
                } else {
                    val track = tracks[which - 1]
                    selectedSpuId = track.id
                    p?.setSubtitleTrack(track.id)
                    saveTrackPref("spu", track.name, callback.getCurrentTitle())
                }
                onControlsNeeded()
            }.setNegativeButton(tr("player.zakryt"), null).show()
    }

    fun allEpisodeUrls(url: String): List<String> {
        val sidecars = url == subsForUrl
        return listOf(url) + if (sidecars) audioUrls + subUrls else emptyList()
    }

    fun hasExternalAudio(url: String): Boolean =
        url == subsForUrl && audioUrls.isNotEmpty()

    /** The external files to open with [url]: the one voice-over (see [attachedAudio]) and all subtitles. */
    fun sidecarsFor(url: String, wrapUrl: (String) -> String): Sidecars {
        sidecarsActive = url == subsForUrl && audioUrls.isNotEmpty()
        attachedAudio.clear()
        if (url != subsForUrl) return Sidecars(emptyList(), emptyList())
        this.wrapUrl = wrapUrl
        val want = activeSidecar ?: prefs.getString("track:sidecar:${seriesKey(callback.getCurrentTitle())}", null)
        val pick = if (want == SIDECAR_NONE) null else audioUrls.firstOrNull { audioLabel(it) == want } ?: audioUrls.firstOrNull()
        val audio = if (pick != null) {
            attachedAudio += pick
            activeSidecar = audioLabel(pick)
            listOf(wrapUrl(pick))
        } else emptyList()
        return Sidecars(audio, subUrls)
    }

    /** "Film.rus.mka" beside "Film.mkv" gives "rus". */
    private fun audioLabel(u: String): String {
        val name = (Uri.parse(u).lastPathSegment ?: u).substringBeforeLast('.')
        val film = Uri.parse(subsForUrl).lastPathSegment.orEmpty().substringBeforeLast('.')
        val cut = if (film.isNotEmpty() && name.startsWith(film)) name.substring(film.length) else name
        return cut.trim('.', ' ', '_', '-').ifEmpty { name }
    }

    private fun seriesKey(title: String) = title.substringBefore(" · ").trim().lowercase(Locale.ROOT)

    private fun saveTrackPref(kind: String, name: String, title: String) {
        val key = seriesKey(title)
        prefs.edit().putString("track:$kind:$key", name).putString("track:$kind:*", name).apply()
    }

    private fun applyPreferredTracks(p: PlayerEngine, currentTitle: String) {
        val key = seriesKey(currentTitle)
        val audio = prefs.getString("track:audio:$key", null) ?: prefs.getString("track:audio:*", null)
        val spu = prefs.getString("track:spu:$key", null) ?: prefs.getString("track:spu:*", null)
        runCatching {
            pickTrack(p.audioTracks(), audio)?.let { id ->
                if (id != p.audioTrack) {
                    p.setAudioTrack(id)
                    selectedAudioId = id
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

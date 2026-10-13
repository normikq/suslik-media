package ru.suslikovanora.media.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.SystemClock
import android.widget.Toast
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import java.util.Locale

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
        fun isPlayerAlive(p: MediaPlayer): Boolean
        fun getCurrentTitle(): String
        fun getCurrentPlayer(): MediaPlayer?
    }

    companion object {
        const val SPU_OFF = "__off__"
        const val EXTRA_SUB_URLS = "media_sub_urls"
        const val EXTRA_AUDIO_URLS = "media_audio_urls"
        const val EXTRA_URL = "media_url"
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

    fun onPlaying(p: MediaPlayer, currentUrl: String, currentTitle: String) {
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
            handler.postDelayed({
                if (callback.isPlayerAlive(p)) restoreSelectedTracks(p)
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

    fun fixSound(p: MediaPlayer) {
        val id = p.audioTrack.takeIf { it != -1 } ?: p.audioTracks?.firstOrNull { it.id != -1 }?.id ?: return
        runCatching {
            p.setAudioTrack(-1)
            p.setAudioTrack(id)
        } // restarts the audio decoder and output
        selectedAudioId = id
    }

    fun restoreSelectedTracks(p: MediaPlayer) {
        runCatching {
            selectedAudioId?.let { id -> if (p.audioTracks?.any { it.id == id } == true) p.setAudioTrack(id) }
            selectedSpuId?.let { id -> if (id == -1 || p.spuTracks?.any { it.id == id } == true) p.setSpuTrack(id) }
        }
    }

    fun showAudioTracks(activity: Activity, p: MediaPlayer?, onControlsNeeded: () -> Unit) {
        val tracks = p?.audioTracks.orEmpty()
        if (tracks.isEmpty()) {
            Toast.makeText(activity, "Аудиодорожки пока не определены", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity).setTitle("Аудиодорожка")
            .setItems(tracks.map { it.name }.toTypedArray()) { _, which ->
                val track = tracks[which]
                selectedAudioId = track.id
                p?.setAudioTrack(track.id)
                callback.onAudioTrackChanged()
                saveTrackPref("audio", track.name, callback.getCurrentTitle())
                onControlsNeeded()
            }.setNegativeButton("Закрыть", null).show()
    }

    fun showSubtitleTracks(activity: Activity, p: MediaPlayer?, onControlsNeeded: () -> Unit) {
        val tracks = p?.spuTracks.orEmpty()
        val labels = listOf("Выключить субтитры") + tracks.map { it.name }
        AlertDialog.Builder(activity).setTitle("Субтитры")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    selectedSpuId = -1
                    p?.setSpuTrack(-1)
                    saveTrackPref("spu", SPU_OFF, callback.getCurrentTitle())
                } else {
                    val track = tracks[which - 1]
                    selectedSpuId = track.id
                    p?.setSpuTrack(track.id)
                    saveTrackPref("spu", track.name, callback.getCurrentTitle())
                }
                onControlsNeeded()
            }.setNegativeButton("Закрыть", null).show()
    }

    fun allEpisodeUrls(url: String): List<String> {
        val sidecars = url == subsForUrl
        return listOf(url) + if (sidecars) audioUrls + subUrls else emptyList()
    }

    fun hasExternalAudio(url: String): Boolean =
        url == subsForUrl && audioUrls.isNotEmpty()

    fun attachSidecars(media: Media, url: String, wrapUrl: (String) -> String) {
        if (url != subsForUrl) return
        for (u in audioUrls) {
            runCatching {
                media.addSlave(IMedia.Slave(IMedia.Slave.Type.Audio, 4, wrapUrl(u)))
            }
        }
        for (u in subUrls) {
            runCatching {
                media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, u))
            }
        }
    }

    private fun seriesKey(title: String) = title.substringBefore(" · ").trim().lowercase(Locale.ROOT)

    private fun saveTrackPref(kind: String, name: String, title: String) {
        val key = seriesKey(title)
        prefs.edit().putString("track:$kind:$key", name).putString("track:$kind:*", name).apply()
    }

    private fun applyPreferredTracks(p: MediaPlayer, currentTitle: String) {
        val key = seriesKey(currentTitle)
        val audio = prefs.getString("track:audio:$key", null) ?: prefs.getString("track:audio:*", null)
        val spu = prefs.getString("track:spu:$key", null) ?: prefs.getString("track:spu:*", null)
        runCatching {
            pickTrack(p.audioTracks, audio)?.let { id ->
                if (id != p.audioTrack) {
                    p.setAudioTrack(id)
                    selectedAudioId = id
                    callback.onAudioTrackChanged()
                }
            }
            if (spu == SPU_OFF) {
                if (p.spuTrack != -1) {
                    p.setSpuTrack(-1)
                    selectedSpuId = -1
                }
            } else {
                pickTrack(p.spuTracks, spu)?.let { id ->
                    if (id != p.spuTrack) {
                        p.setSpuTrack(id)
                        selectedSpuId = id
                    }
                }
            }
        }
    }

    /** Same name first, then the same language tag ("[Russian]"), then a name that contains the old one. */
    private fun pickTrack(tracks: Array<MediaPlayer.TrackDescription>?, wanted: String?): Int? {
        if (wanted.isNullOrBlank() || tracks == null) return null
        val list = tracks.filter { it.id != -1 }
        list.firstOrNull { it.name == wanted }?.let { return it.id }
        val lang = Regex("""\[([^\]]+)]""").find(wanted)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        if (lang != null) list.firstOrNull { it.name.lowercase(Locale.ROOT).contains("[$lang]") }?.let { return it.id }
        val core = wanted.lowercase(Locale.ROOT).replace(Regex("""^(track|дорожка)\s*\d+\s*-\s*"""), "").trim()
        if (core.length >= 3) list.firstOrNull { it.name.lowercase(Locale.ROOT).contains(core) }?.let { return it.id }
        return null
    }
}

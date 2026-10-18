package ru.suslikovanora.media.player

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.RequiresApi
import ru.suslikovanora.media.AppLog
import ru.suslikovanora.media.Diag
import ru.suslikovanora.media.core.BurstProxy
import ru.suslikovanora.media.tr
import java.util.Locale

/**
 * Handles playback diagnostics telemetry, watchdog periodic logging,
 * hardware vs software video decoder detection (VC-1, unsupported formats, black screen recovery),
 * and dynamic display refresh rate switching.
 */
class PlayerDiagnostics(
    private val context: Context,
    private val handler: Handler,
    private val callback: Callback
) {
    interface Callback {
        fun getCurrentUrl(): String
        fun getCurrentPlayer(): PlayerEngine?
        fun isUserPaused(): Boolean
        fun isBuffering(): Boolean
        fun isNetworkLost(): Boolean
        fun isCatchingUp(): Boolean
        fun isKeptOnStop(): Boolean
        fun getPerceivedPosition(): Long
        fun onReopenWithoutHardwareDecoder(startAt: Long)
    }

    private val softwareVideo = HashSet<String>() // files played without hardware decoding
    private var decodeCheckedUrl = ""
    private var lastOpenAtTime = 0L

    private var diagPrev: EngineStats? = null
    private var diagAt = 0L

    private val diagRunnable = object : Runnable {
        override fun run() {
            runCatching { sample() }
            handler.postDelayed(this, DIAG_INTERVAL_MS)
        }
    }

    fun isHardwareDecoderAllowed(url: String): Boolean = url !in softwareVideo

    fun onMediaOpened(positionMs: Long) {
        lastOpenAtTime = positionMs
    }

    fun onResume() {
        if (Diag.on) {
            Diag.playerOpen = true
            handler.postDelayed(diagRunnable, 3000L)
        }
    }

    fun onDestroy() {
        handler.removeCallbacks(diagRunnable)
        if (Diag.on) {
            sample()
            Diag.playerOpen = false
            Diag.batterySnapshot("film closed")
        }
    }

    fun sample() {
        val now = SystemClock.elapsedRealtime()
        val span = if (diagAt == 0L) 0L else now - diagAt
        diagAt = now
        val p = callback.getCurrentPlayer()
        val sb = StringBuilder("[${p?.kind?.tag ?: "player"}] ")
        val kept = callback.isKeptOnStop()
        if (p == null) {
            sb.append("no player${if (kept) " (parked)" else ""}")
        } else {
            sb.append("%.1f/%ds ".format(p.timeMs / 1000.0, p.lengthMs / 1000))
            sb.append(when {
                kept -> "parked (screen off / hidden)"
                p.isPlaying -> "playing"
                callback.isUserPaused() -> "paused by user"
                else -> "not playing"
            })
            if (callback.isBuffering()) sb.append(" BUFFERING")
            if (callback.isNetworkLost()) sb.append(" NETWORK-LOST")
            if (callback.isCatchingUp()) sb.append(" CATCHING-UP")
            runCatching { p.rate }.getOrNull()?.takeIf { it != 1f }?.let { sb.append(" speed ${it}x") }
            val st = p.stats()
            val q = diagPrev
            if (st != null) {
                // Counters restart with every new Media (reopen / next episode): then the raw values are shown.
                val fresh = q == null || st.readBytes < q.readBytes || st.displayedPictures < q.displayedPictures
                // One counter alone restarts too: a switch between FFmpeg and the phone's audio decoder makes a new audio renderer.
                fun d(a: Int, b: Int?) = if (fresh || b == null || a < b) a else a - b
                val read = if (fresh) st.readBytes.toLong() else (st.readBytes - q!!.readBytes).toLong()
                sb.append(" · read +%.2f MB%s".format(read / 1048576.0,
                    if (span > 0 && !fresh) " (%.1f Mbit/s)".format(read * 8.0 / 1000 / span) else ""))
                sb.append(" · pictures shown +${d(st.displayedPictures, q?.displayedPictures)} lost +${d(st.lostPictures, q?.lostPictures)}" +
                    if (p.kind == EngineKind.MEDIA3) " skipped +${d(st.skippedPictures, q?.skippedPictures)}" else "")
                sb.append(" · decoded video +${d(st.decodedVideo, q?.decodedVideo)} audio +${d(st.decodedAudio, q?.decodedAudio)}")
                sb.append(" · audio played +${d(st.playedAbuffers, q?.playedAbuffers)} lost +${d(st.lostAbuffers, q?.lostAbuffers)}")
                if (st.demuxCorrupted > 0 || st.demuxDiscontinuity > 0) sb.append(" · demux corrupted ${st.demuxCorrupted} discontinuities ${st.demuxDiscontinuity}")
                if (fresh) sb.append(" (counters since open)")
            }
            diagPrev = st
        }
        if (p?.kind == EngineKind.MEDIA3) p.diagLine()?.let { sb.append(" · ").append(it.removePrefix("[media3] ")) }
        BurstProxy.aheadMb(callback.getCurrentUrl())?.let { sb.append(" · reserve ahead %.1f MB".format(it)) }
        AppLog.write(sb.toString())
        AppLog.write("[proxy] " + BurstProxy.snapshot())
    }

    /**
     * Safety net for a hardware decoder that takes a format but gives no pictures (sound over a black screen, like
     * VC-1 on Samsung): the film plays for 6 s, pictures are decoded, none is shown -> this file without hardware decoding.
     */
    fun checkNoPicture(p: PlayerEngine) {
        val currentUrl = callback.getCurrentUrl()
        val pTime = runCatching { p.timeMs }.getOrDefault(0L)
        if (currentUrl in softwareVideo || p.videoTrackCount <= 0 || pTime - lastOpenAtTime < 6_000L) return
        val st = p.stats() ?: return
        if (st.displayedPictures > 0 || st.decodedVideo < 60) return
        if (p.kind == EngineKind.MEDIA3) {
            // VLC gets the file with its usual hardware-first start (and the same check there).
            AppLog.write("video: ${st.decodedVideo} pictures decoded by Media3, none shown — the file goes to VLC")
            callback.onReopenWithoutHardwareDecoder(callback.getPerceivedPosition())
            return
        }
        softwareVideo += currentUrl
        AppLog.write("video: ${st.decodedVideo} pictures decoded, none shown — reopening with VLC's own decoder")
        callback.onReopenWithoutHardwareDecoder(callback.getPerceivedPosition())
    }

    fun warnIfSoftwareDecoding(p: PlayerEngine) {
        val currentUrl = callback.getCurrentUrl()
        if (decodeCheckedUrl == currentUrl) return
        val t = p.videoTrack ?: return
        decodeCheckedUrl = currentUrl
        if (p.kind == EngineKind.MEDIA3) {
            // Media3 plays only what a decoder takes (the decoder's name is in the journal): VC-1 is tried on the phone's
            // decoder too, and checkNoPicture sends the file to VLC if it shows nothing. AV1 is hardware here.
            AppLog.write("video: ${t.codec} ${t.width}x${t.height}")
            return
        }
        val codec = t.codec?.lowercase(Locale.ROOT)?.trim()
        // VC-1 (old Blu-rays): the phone's own decoder takes it but gives no pictures — sound over a black screen
        // (12.10, Samsung c2.sec.vc1.decoder: «Unsupported mime video/wvc1», dequeueOutputBuffer failed). VLC's own
        // decoder plays it fine: open this file once more without hardware decoding, at the same moment.
        if (codec != null && VC1.any { codec.contains(it) } && currentUrl !in softwareVideo) {
            softwareVideo += currentUrl
            AppLog.write("video: $codec ${t.width}x${t.height} — the hardware decoder gives no picture for VC-1, reopening with VLC's own decoder")
            callback.onReopenWithoutHardwareDecoder(callback.getPerceivedPosition())
            return
        }
        // VLC names the codec by description ("aomedia's av1 video"), so AV1 never matched below and no warning was shown (16.10).
        // libVLC 3.6.0's own MediaCodec module has no AV1 at all (no "video/av01" in libvlc.so): it always goes to software
        // dav1d, even where the phone has an AV1 hardware decoder.
        if (codec != null && (codec.contains("av1") || codec.contains("av01"))) {
            AppLog.write("video: ${t.codec} ${t.width}x${t.height}, VLC has no hardware AV1 decoder (processor will decode)")
            Toast.makeText(context, tr("player.av1_processorom", "w" to t.width, "h" to t.height), Toast.LENGTH_LONG).show()
            return
        }
        val mime = when (codec) {
            "h264", "avc1", "x264" -> "video/avc"
            "hevc", "h265", "hev1", "hvc1" -> "video/hevc"
            "av01", "av1" -> "video/av01"
            "vp90", "vp09", "vp9" -> "video/x-vnd.on2.vp9"
            "vp80", "vp8" -> "video/x-vnd.on2.vp8"
            "mpgv", "mp2v" -> "video/mpeg2"
            else -> { AppLog.write("video: $codec ${t.width}x${t.height}"); return }
        }
        val hw = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                if (info.isEncoder || info.supportedTypes.none { it.equals(mime, true) }) return@any false
                val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
                hardware && runCatching { info.getCapabilitiesForType(mime).videoCapabilities.isSizeSupported(t.width, t.height) }.getOrDefault(true)
            }
        }.getOrDefault(true)
        AppLog.write("video: ${t.codec} ${t.width}x${t.height}, hardware decoder ${if (hw) "available" else "NOT available (processor will decode)"}")
        if (!hw) Toast.makeText(context, "Телефон не умеет аппаратно декодировать этот файл (${t.width}×${t.height}) — " +
            "он воспроизводится процессором и заметно сильнее расходует батарею.", Toast.LENGTH_LONG).show()
    }

    /**
     * Android 11+: tells the system the video's real frame rate. On 90/120 Hz screens the display can then run
     * at 24/30/60 Hz during playback (a large share of the screen's power), without any visual change.
     */
    fun applyVideoFrameRate(owner: PlayerEngine, root: View) {
        if (Build.VERSION.SDK_INT < 30) return
        val track = owner.videoTrack ?: return
        if (track.frameRateNum <= 0 || track.frameRateDen <= 0) return
        val fps = track.frameRateNum.toFloat() / track.frameRateDen
        if (fps < 10f || fps > 121f) return
        for (sv in surfaceViews(root)) {
            val surface = sv.holder?.surface?.takeIf { it.isValid } ?: continue
            try { setSurfaceFrameRate(surface, fps) } catch (_: Exception) { }
        }
    }

    @RequiresApi(30)
    private fun setSurfaceFrameRate(surface: Surface, fps: Float) {
        if (Build.VERSION.SDK_INT >= 31) {
            surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
        } else {
            surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
    }

    companion object {
        private const val DIAG_INTERVAL_MS = 5_000L
        private val VC1 = setOf("wvc1", "vc-1", "vc1", "wmv3")

        fun surfaceViews(v: View, out: MutableList<SurfaceView> = ArrayList()): List<SurfaceView> {
            if (v is SurfaceView) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) surfaceViews(v.getChildAt(i), out)
            return out
        }
    }
}

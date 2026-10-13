package ru.suslikovanora.media.player

import android.os.Handler
import org.videolan.libvlc.MediaPlayer
import ru.suslikovanora.media.AppLog
import ru.suslikovanora.media.core.BurstProxy
import ru.suslikovanora.media.core.TsClock

/**
 * Controller for Blu-ray (.m2ts) and MPEG transport streams over HTTP.
 *
 * Over HTTP, VLC does not read the end of an MPEG transport stream, so the length stays 0 («--:--», no seek bar).
 * TsClock reads both ends of the file (two small requests) before VLC starts — the length and bitrate are known
 * at once, and the "heavy file" dialog can be shown before anything is loaded.
 * Seeks then go by position (share of the file), measured from where VLC is currently playing.
 */
class PlayerBluRayController(
    private val handler: Handler,
    private val callback: Callback
) {
    interface Callback {
        fun isActivityAlive(): Boolean
        fun showStatus(text: String)
        fun onDurationProbed(url: String, durationMs: Long, mbit: Double)
        fun onProbeCompleted()
        fun reconnect(forcePlay: Boolean, startAt: Long)
        fun scheduleSeekWatchdog()
        fun onSeekSettled(target: Long)
        fun clearPendingSeekIfMatches(positionMs: Long)
    }

    enum class SeekResult {
        NOT_TS,
        HANDLED,
        REOPENING
    }

    private var tsLength = 0L          // ms, valid for tsLengthUrl
    private var tsMbit = 0.0           // file size / length, for heavy-file warning before VLC knows anything
    private var tsLengthUrl = ""
    private var tsProbed = ""          // probed (or tried) before opening
    private var tsOpenAt = 0L          // where stream was opened: :start-time is ignored on such a stream
    private var tsOpenFixed = false
    private var tsSeekTarget = -1L     // a position seek in flight
    private var tsSeekFrom = 0L        // VLC's time when it was asked
    private var tsWaits = 0
    private var tsLastPos = 0f         // position last asked for
    private var tsOffset = 0L          // added to VLC's time when it counts a continued Blu-ray from 0
    private var tsOpenJump = false     // jump to saved position is on its way
    private var tsAbsolute = false     // reopened for a seek before VLC's clock
    private var tsOpenPos = 0f

    val isSeeking: Boolean get() = tsSeekTarget >= 0

    fun clearSeekTarget() {
        tsSeekTarget = -1L
    }

    /** The film's length: VLC's length, or for a transport stream the one read from its clock (0 = unknown). */
    fun lengthOf(p: MediaPlayer?, currentUrl: String): Long =
        p?.length?.takeIf { it > 0L } ?: if (tsLengthUrl == currentUrl) tsLength else 0L

    /** The film's time: VLC's time, plus offset when VLC counts a continued Blu-ray from 0. */
    fun timeOf(p: MediaPlayer?, currentUrl: String): Long =
        (p?.time ?: 0L).let { if (it > 0L && tsLengthUrl == currentUrl) it + tsOffset else it }

    fun isTsMode(p: MediaPlayer, currentUrl: String): Boolean =
        p.length <= 0L && tsLengthUrl == currentUrl && tsLength > 0L

    private var lastLearnedTime = 0L

    fun getBitrateMbit(currentUrl: String): Double? =
        tsMbit.takeIf { it > 0 && tsLengthUrl == currentUrl }

    /** Before VLC opens a transport stream: probe its length (once per file). Returns true if waiting for probe. */
    fun probeFirst(currentUrl: String): Boolean {
        if (!TsClock.isTs(currentUrl)) return false
        TsClock.log = { AppLog.write(it) }
        TsClock.cached(currentUrl)?.let { r ->
            tsLength = r.first
            tsLengthUrl = currentUrl
            tsMbit = r.second * 8.0 / r.first / 1000.0
            callback.onDurationProbed(currentUrl, r.first, tsMbit)
            return false
        }
        if (tsProbed == currentUrl) return false
        tsProbed = currentUrl
        callback.showStatus("Открываем…")
        Thread({
            val r = TsClock.probe(currentUrl)
            handler.post {
                if (!callback.isActivityAlive()) return@post
                if (r != null) {
                    tsLength = r.first
                    tsLengthUrl = currentUrl
                    tsMbit = r.second * 8.0 / r.first / 1000.0
                    AppLog.write("[ts] duration from the stream's clock: %s, ≈ %.1f Mbit/s".format(formatTime(r.first), tsMbit))
                    callback.onDurationProbed(currentUrl, r.first, tsMbit)
                } else {
                    tsProbed = "" // Allow retry on subsequent attempts
                    AppLog.write("[ts] no duration in the stream's clock")
                }
                callback.onProbeCompleted()
            }
        }, "ts-clock").apply { isDaemon = true }.start()
        return true
    }

    /**
     * Initializes stream offsets and jump flags when a player is created.
     * Returns true if pending seek should be recorded for a continued Blu-ray stream.
     */
    fun onPlayerCreated(url: String, positionMs: Long): Boolean {
        tsOpenAt = positionMs
        tsOpenFixed = false
        tsSeekTarget = -1L
        tsOffset = 0L
        tsOpenJump = false
        lastLearnedTime = 0L
        return tsLengthUrl == url && positionMs > 10_000L
    }

    /**
     * VLC started playing: give duration to reserve proxy; if opened at saved position, jump there immediately.
     */
    fun onPlaying(p: MediaPlayer, currentUrl: String) {
        if (tsLengthUrl != currentUrl) return
        BurstProxy.setDuration(currentUrl, tsLength)
        if (tsOpenFixed || !isTsMode(p, currentUrl) || tsOpenAt <= 10_000L) return
        if (tsAbsolute) return
        jumpToOpenPlace(p)
    }

    /**
     * Processes time/length updates from MediaPlayer.
     * Handles landing after jump and returns film time (or 0L while initial jump is pending).
     */
    fun handleTimeUpdate(p: MediaPlayer, currentUrl: String): Long {
        if (tsAbsolute && p.time > 0L) {
            tsAbsoluteJump(p, currentUrl)
            return 0L
        }
        if (tsOpenJump && p.time > 0L) tsOpenLanded(p)
        val t = if (tsOpenJump) 0L else timeOf(p, currentUrl)
        if (!tsOpenJump && tsSeekTarget < 0 && p.isPlaying && t > 5_000L && p.position > 0.005f && tsOffset == 0L) {
            if (kotlin.math.abs(t - lastLearnedTime) >= 5_000L) {
                lastLearnedTime = t
                TsClock.learn(currentUrl, t, p.position)
            }
        }
        return t
    }

    /** True if reported time is still the old place from before the seek. */
    fun isSettling(t: Long): Boolean =
        kotlin.math.abs(t - tsSeekFrom) < 1_500L && kotlin.math.abs(tsSeekTarget - tsSeekFrom) > 3_000L

    /**
     * Seeks in a transport stream by position.
     */
    fun seek(p: MediaPlayer, currentUrl: String, targetMs: Long, userPaused: Boolean): SeekResult {
        if (!isTsMode(p, currentUrl)) {
            tsSeekTarget = -1L
            return SeekResult.NOT_TS
        }
        if (seekBeforeClock(targetMs, userPaused)) {
            return SeekResult.REOPENING
        }
        val inFlight = tsSeekTarget >= 0 || tsOpenJump
        val nowT = if (tsSeekTarget >= 0) tsSeekTarget else if (tsOpenJump) tsOpenAt else timeOf(p, currentUrl)
        val nowPos = if (inFlight) tsLastPos else p.position
        if (!inFlight && nowT > 10_000L && nowPos > 0.01f && tsOffset == 0L) {
            TsClock.learn(currentUrl, nowT, nowPos)
        }
        tsSeekTarget = targetMs
        tsSeekFrom = timeOf(p, currentUrl)
        tsWaits = 0
        val pos = TsClock.posFor(currentUrl, targetMs, tsLength)
        tsLastPos = pos
        AppLog.write("[ts] seek %ds → position %.4f (from %ds at %.4f)".format(targetMs / 1000, pos, nowT / 1000, nowPos))
        p.position = pos
        return SeekResult.HANDLED
    }

    /**
     * Watchdog check for a position seek.
     * Returns true if handled here (settled or retry scheduled).
     */
    fun onWatchdog(p: MediaPlayer, currentUrl: String, userPaused: Boolean, target: Long): Boolean {
        if (tsSeekTarget < 0) return false
        val t = timeOf(p, currentUrl)
        if (userPaused || !p.isPlaying || kotlin.math.abs(t - tsSeekFrom) > 1_500L) {
            val landedPos = p.position
            tsSeekTarget = -1L
            callback.onSeekSettled(target)
            if (p.isPlaying && t > 1_000L && landedPos > 0.005f && tsOffset == 0L) {
                TsClock.learn(currentUrl, t, landedPos)
            }
            return true
        }
        if (++tsWaits <= 3) {
            callback.scheduleSeekWatchdog()
            return true
        }
        tsSeekTarget = -1L
        return false // 36s and VLC did not move at all: normal reconnect
    }

    fun onSettled(url: String, t: Long, pos: Float) {
        if (t > 1_000L && pos > 0.005f && tsOffset == 0L) {
            TsClock.learn(url, t, pos)
        }
    }

    private fun jumpToOpenPlace(p: MediaPlayer) {
        tsOpenFixed = true
        tsOpenJump = true
        tsOpenPos = TsClock.posFor(tsLengthUrl, tsOpenAt, tsLength)
        tsLastPos = tsOpenPos
        AppLog.write("[ts] continue at ${tsOpenAt / 1000}s → position %.4f".format(tsOpenPos))
        p.position = tsOpenPos
    }

    private fun seekBeforeClock(t: Long, userPaused: Boolean): Boolean {
        if (tsOffset <= 0L || t >= tsOffset + 2_000L) return false
        AppLog.write("[ts] seek to ${t / 1000}s is before VLC's clock (starts at ${tsOffset / 1000}s): reopening with the real clock")
        tsAbsolute = true
        callback.reconnect(forcePlay = !userPaused, startAt = t)
        return true
    }

    private fun tsAbsoluteJump(p: MediaPlayer, currentUrl: String) {
        val wasAbsolute = tsAbsolute
        tsAbsolute = false
        if (tsOpenFixed || !isTsMode(p, currentUrl)) return
        if (tsOpenAt <= 10_000L) {
            tsOpenFixed = true
            return
        }
        jumpToOpenPlace(p)
        if (wasAbsolute) {
            tsOffset = 0L
        }
    }

    private fun tsOpenLanded(p: MediaPlayer) {
        val raw = p.time
        // Must be genuinely playing at the target position and not still reporting 0 at stream start
        if (raw <= 0L || p.position < tsOpenPos * 0.5f) return
        tsOpenJump = false
        // Reopened with real clock (tsAbsolute) or VLC reports stream PTS (raw >= tsOpenAt / 2): no offset needed
        tsOffset = if (tsAbsolute) 0L else if (raw < tsOpenAt / 2) tsOpenAt - raw else 0L
        callback.clearPendingSeekIfMatches(tsOpenAt)
        AppLog.write("[ts] landed: VLC time ${raw / 1000}s" + if (tsOffset > 0) ", counts from 0 here → +${tsOffset / 1000}s" else "")
        if (tsOffset == 0L && raw > 1_000L && p.position > 0.005f) {
            TsClock.learn(tsLengthUrl, raw, p.position)
        }
    }

    private fun formatTime(ms: Long): String {
        val sec = ms / 1000
        val m = sec / 60
        val s = sec % 60
        val h = m / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s) else "%d:%02d".format(m, s)
    }
}

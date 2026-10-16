package ru.suslikovanora.media.player

import android.os.Handler
import ru.suslikovanora.media.AppLog
import ru.suslikovanora.media.core.BurstProxy
import ru.suslikovanora.media.core.TsClock
import ru.suslikovanora.media.core.TsPlayback
import ru.suslikovanora.media.tr

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
    private var tsWaits = 0
    private var tsAbsolute = false     // reopened for a seek before VLC's clock
    // The decisions shared with the PC (offset, seeks by position, landing): one per opened stream.
    private var ts: TsPlayback? = null

    private fun tsFor(url: String): TsPlayback? =
        ts ?: if (tsLengthUrl == url && tsLength > 0L) TsPlayback(tsLength, url).also { ts = it } else null

    val isSeeking: Boolean get() = ts?.seeking == true

    fun clearSeekTarget() { ts?.seekDone() }

    /** The film's length: VLC's length, or for a transport stream the one read from its clock (0 = unknown). */
    fun lengthOf(p: PlayerEngine?, currentUrl: String): Long =
        p?.lengthMs?.takeIf { it > 0L } ?: if (tsLengthUrl == currentUrl) tsLength else 0L

    /** The film's time: VLC's time, plus offset when VLC counts a continued Blu-ray from 0. */
    fun timeOf(p: PlayerEngine?, currentUrl: String): Long =
        (p?.timeMs ?: 0L).let { if (tsLengthUrl == currentUrl) ts?.filmTime(it) ?: it else it }

    fun isTsMode(p: PlayerEngine, currentUrl: String): Boolean =
        p.lengthMs <= 0L && tsLengthUrl == currentUrl && tsLength > 0L

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
        callback.showStatus(tr("playerbluray.otkryvaem"))
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
     * A player is created for [url] at [positionMs]: a fresh [TsPlayback] for it.
     * Returns true if pending seek should be recorded for a continued Blu-ray stream.
     */
    fun onPlayerCreated(url: String, positionMs: Long): Boolean {
        tsOpenAt = positionMs
        tsOpenFixed = false
        ts = null
        tsFor(url)
        return tsLengthUrl == url && positionMs > 10_000L
    }

    /**
     * VLC started playing: give duration to reserve proxy; if opened at saved position, jump there immediately.
     */
    fun onPlaying(p: PlayerEngine, currentUrl: String) {
        if (tsLengthUrl != currentUrl) return
        BurstProxy.setDuration(currentUrl, tsLength)
        if (tsOpenFixed || !isTsMode(p, currentUrl) || tsOpenAt <= 10_000L) return
        if (tsAbsolute) return
        jumpToOpenPlace(p, currentUrl)
    }

    /**
     * Processes time/length updates from the engine.
     * Handles landing after jump and returns film time (or 0L while initial jump is pending).
     */
    fun handleTimeUpdate(p: PlayerEngine, currentUrl: String): Long {
        if (tsAbsolute && p.timeMs > 0L) {
            tsAbsoluteJump(p, currentUrl)
            return 0L
        }
        val s = ts ?: return timeOf(p, currentUrl)
        if (s.opening && p.timeMs > 0L) {
            val raw = p.timeMs
            if (s.openLanded(raw, p.fraction) != null) {
                callback.clearPendingSeekIfMatches(tsOpenAt)
                AppLog.write("[ts] landed: VLC time ${raw / 1000}s" + if (s.offset > 0) ", counts from 0 here → +${s.offset / 1000}s" else "")
            }
        }
        if (s.opening) return 0L
        if (p.isPlaying) s.tick(p.timeMs, p.fraction) // steady playback: a checkpoint every 5 s
        return timeOf(p, currentUrl)
    }

    /** True if reported time is still the old place from before the seek (false = landed, the seek is over). */
    fun isSettling(t: Long): Boolean = ts?.seekLanded(t) == false

    /**
     * Seeks in a transport stream by position.
     */
    fun seek(p: PlayerEngine, currentUrl: String, targetMs: Long, userPaused: Boolean): SeekResult {
        if (!isTsMode(p, currentUrl)) {
            ts?.seekDone()
            return SeekResult.NOT_TS
        }
        val s = tsFor(currentUrl) ?: return SeekResult.NOT_TS
        if (seekBeforeClock(s, targetMs, userPaused)) {
            return SeekResult.REOPENING
        }
        val nowT = timeOf(p, currentUrl)
        val nowPos = p.fraction
        tsWaits = 0
        val pos = s.seek(targetMs, nowT, nowPos)
        AppLog.write("[ts] seek %ds → position %.4f (from %ds at %.4f)".format(targetMs / 1000, pos, nowT / 1000, nowPos))
        p.seekToFraction(pos)
        return SeekResult.HANDLED
    }

    /**
     * Watchdog check for a position seek.
     * Returns true if handled here (settled or retry scheduled).
     */
    fun onWatchdog(p: PlayerEngine, currentUrl: String, userPaused: Boolean, target: Long): Boolean {
        val s = ts ?: return false
        if (!s.seeking) return false
        val t = timeOf(p, currentUrl)
        if (userPaused || !p.isPlaying || kotlin.math.abs(t - s.seekFrom) > 1_500L) {
            s.seekDone()
            callback.onSeekSettled(target)
            onSettled(currentUrl, t, if (p.isPlaying) p.fraction else 0f)
            return true
        }
        if (++tsWaits <= 3) {
            callback.scheduleSeekWatchdog()
            return true
        }
        s.seekDone()
        return false // 36s and VLC did not move at all: normal reconnect
    }

    fun onSettled(url: String, t: Long, pos: Float) {
        if (t > 1_000L && pos > 0.005f && ts?.offset == 0L) {
            TsClock.learn(url, t, pos)
        }
    }

    private fun jumpToOpenPlace(p: PlayerEngine, url: String) {
        val pos = tsFor(url)?.openJump(tsOpenAt) ?: return
        tsOpenFixed = true
        AppLog.write("[ts] continue at ${tsOpenAt / 1000}s → position %.4f".format(pos))
        p.seekToFraction(pos)
    }

    private fun seekBeforeClock(s: TsPlayback, t: Long, userPaused: Boolean): Boolean {
        if (s.offset <= 0L || t >= s.offset + 2_000L) return false
        AppLog.write("[ts] seek to ${t / 1000}s is before VLC's clock (starts at ${s.offset / 1000}s): reopening with the real clock")
        tsAbsolute = true
        callback.reconnect(forcePlay = !userPaused, startAt = t)
        return true
    }

    private fun tsAbsoluteJump(p: PlayerEngine, currentUrl: String) {
        tsAbsolute = false
        if (tsOpenFixed || !isTsMode(p, currentUrl)) return
        if (tsOpenAt <= 10_000L) {
            tsOpenFixed = true
            return
        }
        jumpToOpenPlace(p, currentUrl) // a fresh TsPlayback: VLC keeps the real clock now, no offset
    }

    private fun formatTime(ms: Long): String = ru.suslikovanora.media.core.clockText(ms)
}

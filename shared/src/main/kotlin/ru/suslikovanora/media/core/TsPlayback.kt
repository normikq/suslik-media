package ru.suslikovanora.media.core

/**
 * Playing a Blu-ray (.m2ts) / MPEG transport stream over HTTP — the decisions both players share. VLC knows no length
 * there (TsClock reads it from the stream's clock), so it is driven by position (share of the file) instead of time,
 * and :start-time is ignored. Learned on Android (12.10–13.10):
 *
 *  - a seek is measured from where VLC is now (its share of the file plus the distance): a disc's uneven bitrate then
 *    only errs by a share of the jump itself — a fraction of a second for ±10 s;
 *  - taps in a row: VLC still reports the old time while its position is already the new one — measure from the jump
 *    in flight instead, or the seeks drift;
 *  - right after a jump VLC keeps reporting the old place for a moment — not a landing;
 *  - opened at a saved place, the jump is made at once (one buffering, not two) and VLC may then count its clock from
 *    0 there: the offset is added to every time it reports.
 *
 * Thread-safe: the PC player seeks on its VLC thread while times come on the window's thread.
 */
class TsPlayback(val lengthMs: Long) {
    /** Added to VLC's time (VLC counts from 0 at the place it jumped to right after opening). */
    var offset = 0L
        private set
    private var openTarget = -1L
    private var openPos = 0f
    private var target = -1L      // a seek in flight (film time)
    private var from = 0L         // VLC's film time when that seek was asked
    private var lastPos = 0f      // the position last asked for

    /** The film's time for a time VLC reports. */
    @Synchronized fun filmTime(vlcTime: Long): Long = if (vlcTime > 0) vlcTime + offset else vlcTime

    private fun posOf(ms: Long) = (ms.toDouble() / lengthMs).toFloat().coerceIn(0f, 0.999f)

    /** VLC started a stream that was opened at [savedMs]: the position to jump to now, or null (from the start). */
    @Synchronized fun openJump(savedMs: Long): Float? {
        if (savedMs <= 10_000L || lengthMs <= 0) return null
        openTarget = savedMs; openPos = posOf(savedMs); lastPos = openPos
        return openPos
    }

    /** The jump of [openJump] is on its way. */
    val opening: Boolean get() = openTarget >= 0

    /**
     * A time from VLC while [opening]: the film's time once VLC is there (the offset is decided then), or null while it
     * still reports the start of the file.
     */
    @Synchronized fun openLanded(vlcTime: Long, vlcPos: Float): Long? {
        if (openTarget < 0) return filmTime(vlcTime)
        if (vlcTime <= 0 || vlcPos < openPos * 0.5f) return null
        offset = if (vlcTime < openTarget / 2) openTarget - vlcTime else 0L
        openTarget = -1
        return filmTime(vlcTime)
    }

    /** The position to give VLC for a seek to [targetMs]; [nowMs] / [nowPos] = VLC's film time and position now. */
    @Synchronized fun seek(targetMs: Long, nowMs: Long, nowPos: Float): Float {
        val (t0, p0) = when {
            target >= 0 -> target to lastPos
            openTarget >= 0 -> openTarget to openPos
            else -> nowMs to nowPos
        }
        val base = if (t0 > 0 && p0 > 0f) p0 - t0.toDouble() / lengthMs else 0.0
        val pos = (base + targetMs.toDouble() / lengthMs).toFloat().coerceIn(0f, 0.999f)
        target = targetMs; from = nowMs; lastPos = pos
        return pos
    }

    /** A seek is in flight. */
    val seeking: Boolean get() = target >= 0

    /** A film time from VLC while [seeking]: false = still the old place (ignore it), true = landed. */
    @Synchronized fun seekLanded(filmMs: Long): Boolean {
        if (target < 0) return true
        if (kotlin.math.abs(filmMs - from) < 1_500L && kotlin.math.abs(target - from) > 3_000L) return false
        target = -1
        return true
    }

    /** The seek is over anyway (paused, given up). */
    @Synchronized fun seekDone() { target = -1 }
}

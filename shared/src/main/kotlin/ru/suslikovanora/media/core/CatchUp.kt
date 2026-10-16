package ru.suslikovanora.media.core

/**
 * «Копим запас» — the same rules in both apps. On a network slower than the film the reserve runs dry, and VLC then
 * stops for a few seconds every 15–30 s; after such a stop the hardware decoder often shows a still or broken picture
 * (journals 12.10 Android, 15.10 PC). Instead, once the reserve is empty, [BurstProxy.hold] keeps the film back until
 * ~30 s of it are downloaded ([BurstProxy.catchUpTargetMb], at most [MAX_MS]); for VLC that is one longer buffering.
 * VLC is never paused for it (a pause in the middle of its buffering froze the picture, 12.10).
 *
 * The app reports what it knows (its own conditions in [maybeStart], a quiet period after an open / seek in [quiet])
 * and asks [progress] once a second while [active]; [end] says whether to restart the decoders at the current place.
 */
class CatchUp(private val log: (String) -> Unit, private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        const val MAX_MS = 45_000L
        /** No new catch-up for this long after the last one. */
        const val AFTER_MS = 20_000L
    }

    /** The film is held back now. */
    var active = false
        private set
    /** When the current (or last) catch-up started ([now]'s clock). */
    var startedAt = 0L
        private set
    private var notBefore = 0L

    /** No catch-up for [ms] (opened, seeked, unlocked): VLC fills its own buffer then anyway. */
    fun quiet(ms: Long) { notBefore = maxOf(notBefore, now() + ms) }

    /**
     * Starts holding the film when the network is slower than it and the reserve is (almost) empty. [canStart]: the
     * app's own conditions (playing, not paused, no seek in flight…). True = started.
     */
    fun maybeStart(url: String, canStart: Boolean): Boolean {
        if (active || !canStart || now() < notBefore) return false
        if (!BurstProxy.isActive(url) || !BurstProxy.slowerThanFilm(url) || (BurstProxy.aheadMb(url) ?: 99.0) >= 2.0) return false
        active = true
        startedAt = now()
        log("network slower than the film, reserve empty: holding the film to gather %.0f MB".format(BurstProxy.catchUpTargetMb(url)))
        BurstProxy.hold(url, MAX_MS + 5_000L)
        return true
    }

    /** While [active]: how much is gathered, 0..99 %, or -1 when it is enough (or took [MAX_MS]): [end] with restart. */
    fun progress(url: String): Int {
        val target = BurstProxy.catchUpTargetMb(url)
        val ahead = BurstProxy.aheadMb(url) ?: 0.0
        if (ahead >= target || now() - startedAt >= MAX_MS) return -1
        return (ahead * 100 / target.coerceAtLeast(1.0)).toInt().coerceIn(0, 99)
    }

    /**
     * Lets the film go. With [restart] (the reserve is there) it returns true when the player should seek to the
     * current moment: after a long buffering the hardware decoder lags behind and VLC drops its pictures as late, and a
     * jump from data already on disk starts it clean. Only with ≥ 8 s kept behind — the jump lands on the last keyframe,
     * a few seconds back (12.10, 4K: with less, that keyframe was gone and VLC fetched it through the mirror again).
     */
    fun end(url: String, restart: Boolean): Boolean {
        if (!active) return false
        active = false
        quiet(AFTER_MS)
        BurstProxy.hold(url, 0L)
        log("reserve gathered: %.0f MB in %d s".format(BurstProxy.aheadMb(url) ?: 0.0, (now() - startedAt) / 1000))
        if (!restart) return false
        val behind = BurstProxy.keptBehindSec(url)
        if (behind >= 8.0) return true
        log("restart skipped: only %.1f s kept behind".format(behind))
        return false
    }
}

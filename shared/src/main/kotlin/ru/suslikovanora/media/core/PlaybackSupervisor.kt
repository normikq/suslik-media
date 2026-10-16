package ru.suslikovanora.media.core

/**
 * One place that decides when playback is unhealthy and what to do about it — the same rules in both apps.
 *
 * Before, every check (silent film, frozen picture, choppy picture, stuck clock) lived on its own timer and could
 * fire on top of another, or at a moment that only looked broken: right after a seek or a resume VLC rebuilds its
 * buffer and plays nothing for a few seconds — that "silence" once made the player reopen the film after every seek.
 *
 * Now the player only reports what happens (events) and what VLC measures (a [tick] every ~2 s); the supervisor
 * keeps the state and allows a check only while the film really plays:
 *
 *     Idle → Opening → Buffering ⇄ Playing ⇄ Paused
 *                         ↑           ↓
 *                         └─ Recovering (one action at a time, then a grace period)
 *
 *  - every check needs the state Playing, a grace period after opening / resuming / seeking / any recovery,
 *    and the film's clock really moving (a stalled clock is a separate problem: «stuck»);
 *  - one action per tick, the gentlest first (restart the video output, re-select the sound) and only then a reopen;
 *  - a budget per episode, so nothing can loop.
 */
class PlaybackSupervisor(
    private val actions: Actions,
    private val now: () -> Long = System::currentTimeMillis,
    /** How long a stuck clock is tolerated before a reopen (longer when a local reserve does the reconnecting). */
    private val stuckLimitMs: () -> Long = { 12_000L },
) {
    enum class State { Idle, Opening, Buffering, Playing, Paused, Recovering, Ended }

    /** What the player can do. All calls come from [tick] / the event methods, on the caller's thread. */
    interface Actions {
        /** New video decoder + output at the current moment (frozen / choppy picture). */
        fun restartVideo()
        /** Make sure a real audio track is selected and the output is not muted. */
        fun fixSound()
        /** Open the film again at the current moment; [fresh] = also drop the local reserve. */
        fun reopen(fresh: Boolean)
        /** Short message for the viewer («Восстанавливаем звук…»). */
        fun notice(text: String)
        fun log(message: String)
    }

    /** What VLC measured, sampled by the player. Counters are cumulative for the current stream. */
    data class Stats(
        val timeMs: Long,            // film clock
        val audioPlayed: Long,       // audio buffers played
        val audioDecoded: Long,      // audio blocks decoded
        val pictures: Long,          // pictures displayed
        val expectedFps: Double,     // the film's frame rate (0 = unknown)
        val audioTracks: Int,        // real audio tracks
        val audioSelected: Boolean,  // one of them is selected
        val videoTracks: Int,
        val userMuted: Boolean,      // muted / volume 0 by the user: silence is expected
    )

    var state = State.Idle
        private set

    private var episode = ""
    private var graceUntil = 0L
    // per-episode budgets
    private var videoFixes = 0
    private var soundFixes = 0
    private var reopens = 0
    private var afterBufferFixes = 0
    private var bufferedAt = -1L       // when the last real rebuffering ended (Buffering → Playing)
    private var lastAction = 0L
    // running measurements
    private var last: Stats? = null
    private var lastAt = 0L
    private var picturesMoved = 0       // the picture did move on this stream (not a still cover image)
    private var silentFor = 0L          // film-clock ms without a played audio buffer
    private var frozenFor = 0L          // film-clock ms without a new picture
    private var choppyTicks = 0
    private var stuckFor = 0L           // wall ms in Playing with the clock not moving
    private var surfaceCheck: Pair<Long, Long>? = null // (at, pictures) after the video surface changed

    // ---- events ----------------------------------------------------------------------------------------------
    /** A stream starts opening. A new [episodeKey] resets the budgets. */
    fun opening(episodeKey: String) {
        if (episodeKey != episode) { episode = episodeKey; videoFixes = 0; soundFixes = 0; reopens = 0; afterBufferFixes = 0 }
        bufferedAt = -1L
        picturesMoved = 0 // a new stream: a still cover must not pass for a film because the last one moved
        enter(State.Opening, grace = 8_000)
    }
    fun buffering(percent: Float) {
        if (state == State.Paused || state == State.Ended) return
        if (percent < 100f) { if (state != State.Opening) enter(State.Buffering, grace = 4_000) }
        else if (state == State.Buffering) { bufferedAt = now(); enter(State.Playing, grace = 2_000) }
        else if (state == State.Opening) enter(State.Playing, grace = 4_000)
    }
    fun playing() { if (state != State.Paused && state != State.Playing) enter(State.Playing, grace = 4_000) }
    fun paused() = enter(State.Paused)
    fun resumed() = enter(State.Playing, grace = 5_000)
    /** Any jump (seek, chapter, track switch that re-seeks): VLC rebuilds its buffer. */
    fun seeked() { if (state != State.Paused) enter(State.Playing, grace = 5_000) else resetMeasurements() }
    fun ended() = enter(State.Ended)
    fun closed() = enter(State.Idle)
    /** Window resized / fullscreen / surface recreated: check soon that the picture still moves. */
    fun surfaceChanged() { surfaceCheck = now() + 1_500 to (last?.pictures ?: -1L) }

    private fun enter(s: State, grace: Long = 0) {
        state = s
        if (grace > 0) graceUntil = maxOf(graceUntil, now() + grace)
        resetMeasurements()
    }
    private fun resetMeasurements() { last = null; silentFor = 0; frozenFor = 0; choppyTicks = 0; stuckFor = 0 }

    // ---- the check -------------------------------------------------------------------------------------------
    fun tick(s: Stats) {
        val t = now()
        val prev = last; val prevAt = lastAt
        last = s; lastAt = t
        if (state == State.Recovering && t >= graceUntil) state = State.Playing
        if (prev == null) return
        if (s.pictures > prev.pictures && prev.pictures > 0) picturesMoved++

        // Right after a surface change: one quick look (also during the grace period after it).
        surfaceCheck?.let { (at, before) ->
            if (t >= at) {
                surfaceCheck = null
                if (state == State.Playing && s.videoTracks > 0 && s.pictures > 0 && s.pictures == prev.pictures &&
                    s.timeMs > prev.timeMs && videoFixes < 3) {
                    act("frozen picture after the video area changed") { videoFixes++; actions.restartVideo() }
                    return
                }
            }
        }

        if (state != State.Playing || t < graceUntil) { silentFor = 0; frozenFor = 0; choppyTicks = 0; stuckFor = 0; return }
        val wall = (t - prevAt).coerceAtLeast(1)
        val clock = s.timeMs - prev.timeMs

        // 1. Stuck: "playing" but the clock does not move (and VLC reports no buffering).
        if (clock <= 50) {
            stuckFor += wall
            if (stuckFor >= stuckLimitMs() && reopens < 3) {
                act("clock stuck for ${stuckFor / 1000} s") { reopens++; actions.reopen(fresh = reopens >= 2) }
            }
            return
        }
        stuckFor = 0
        if (clock < wall / 3) return // barely moving (catching up after a hiccup): not a fair moment to judge

        // 2. Frozen picture: clock runs, no new picture, on a stream whose picture did move before.
        if (s.videoTracks > 0 && s.pictures > 0 && picturesMoved >= 3) {
            if (s.pictures == prev.pictures) frozenFor += clock else frozenFor = 0
            if (frozenFor >= 3_000) {
                // Right after a rebuffering it is VLC's known trouble, not a broken stream: the buffer ran dry, VLC raised
                // its delay and the hardware decoder fell behind — sound plays, the picture stands. A new decoder at this
                // moment fixes it every time; a reopen (as before, after two fixes) only threw the reserve away.
                // (12.10 journal: 8 of 8 freezes came right after "buffer deadlock prevented" at the end of buffering.)
                if (bufferedAt >= 0 && t - bufferedAt < 30_000 && afterBufferFixes < 20) {
                    act("frozen picture after buffering") { afterBufferFixes++; actions.restartVideo() }; return
                }
                if (videoFixes < 2) { act("frozen picture") { videoFixes++; actions.restartVideo() }; return }
                if (reopens < 3) { act("frozen picture again") { reopens++; actions.reopen(fresh = reopens >= 2) }; return }
            }
        }

        // 3. Choppy picture: far fewer pictures than the film has.
        if (s.videoTracks > 0 && s.expectedFps >= 15 && prev.pictures > 0 && picturesMoved >= 3) {
            val shown = (s.pictures - prev.pictures) * 1000.0 / clock
            if (shown < s.expectedFps * 0.45) choppyTicks++ else choppyTicks = 0
            if (choppyTicks >= 3) {
                val msg = "choppy picture: %.1f of %.1f fps".format(shown, s.expectedFps)
                if (videoFixes < 2) { act(msg) { videoFixes++; actions.restartVideo() }; return }
                if (reopens < 3) { act(msg) { reopens++; actions.notice("Восстанавливаем картинку…"); actions.reopen(fresh = true) }; return }
            }
        }

        // 4. Silent film: a real audio track, not muted by the user, clock runs, nothing reaches the speaker.
        if (s.audioTracks > 0 && !s.userMuted && (s.audioDecoded > 0 || s.pictures > 0)) {
            if (!s.audioSelected) {
                if (soundFixes < 3) act("no audio track selected") { soundFixes++; actions.fixSound() }
                return
            }
            if (s.audioPlayed == prev.audioPlayed) silentFor += clock else silentFor = 0
            if (silentFor >= 5_000) {
                if (soundFixes < 1) { act("silent for ${silentFor / 1000} s") { soundFixes++; actions.fixSound() }; return }
                if (reopens < 3) { act("silent again") { soundFixes++; reopens++; actions.notice("Восстанавливаем звук…"); actions.reopen(fresh = false) }; return }
            }
        }
    }

    private inline fun act(reason: String, block: () -> Unit) {
        val t = now()
        if (t - lastAction < 8_000) return // never two recoveries in a row
        lastAction = t
        actions.log("supervisor: $reason → action (video $videoFixes, sound $soundFixes, reopen $reopens)")
        block()
        state = State.Recovering
        graceUntil = t + 6_000
        resetMeasurements()
    }
}

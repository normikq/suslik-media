package ru.suslikovanora.media.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Simulated playback: the clock is ours, a [tick] comes every 2 s like the players send it. */
private class Sim(stuckLimitMs: Long = 12_000L) {
    val reopens = mutableListOf<Boolean>() // the `fresh` flag of each reopen
    var videoRestarts = 0
    var soundFixes = 0
    val notices = mutableListOf<String>()

    var t = 100_000L
    private var time = 0L
    private var pictures = 100L
    private var played = 100L

    var audioTracks = 0
    var audioSelected = true
    var videoTracks = 1
    var userMuted = false
    var fps = 0.0

    val sup = PlaybackSupervisor(
        object : PlaybackSupervisor.Actions {
            override fun restartVideo() { videoRestarts++ }
            override fun fixSound() { soundFixes++ }
            override fun reopen(fresh: Boolean) { reopens += fresh }
            override fun notice(text: String) { notices += text }
            override fun log(message: String) {}
        },
        now = { t },
        stuckLimitMs = { stuckLimitMs },
    )

    /** One sample 2 s later; by default the film runs normally (24 fps, audio flows). */
    fun tick(clock: Long = 2_000, newPictures: Long = 48, newAudio: Long = 100) {
        t += 2_000
        time += clock; pictures += newPictures; played += newAudio
        sup.tick(PlaybackSupervisor.Stats(time, played, played, pictures, fps, audioTracks, audioSelected, videoTracks, userMuted))
    }

    fun ticks(n: Int, clock: Long = 2_000, newPictures: Long = 48, newAudio: Long = 100) =
        repeat(n) { tick(clock, newPictures, newAudio) }

    /** Opened, buffered and playing healthily, past the grace period, pictures have moved. */
    fun warm(episode: String = "a") {
        sup.opening(episode)
        sup.buffering(100f)
        ticks(5)
    }
}

class PlaybackSupervisorTest {
    @Test fun stuckClockReopensAfterTheLimit() {
        val s = Sim()
        s.warm()
        s.ticks(5, clock = 0)
        assertTrue(s.reopens.isEmpty())
        s.ticks(1, clock = 0) // 6 × 2 s = 12 s
        assertEquals(listOf(false), s.reopens)
        assertEquals(PlaybackSupervisor.State.Recovering, s.sup.state)
    }

    @Test fun nothingIsJudgedDuringTheGracePeriodAfterASeek() {
        val s = Sim(stuckLimitMs = 2_000L)
        s.warm()
        s.sup.seeked() // VLC rebuilds its buffer: the clock stands for a few seconds
        s.ticks(2, clock = 0) // first sample, +2 s: still inside the 5 s grace
        assertTrue(s.reopens.isEmpty())
        s.ticks(1, clock = 0)
        assertEquals(1, s.reopens.size)
    }

    @Test fun surfaceChangeChecksThePictureQuickly() {
        val s = Sim()
        s.warm()
        s.sup.surfaceChanged()
        s.tick(newPictures = 0) // the clock moved, the picture did not
        assertEquals(1, s.videoRestarts)
    }

    @Test fun silentFilmFixesSoundThenReopens() {
        val s = Sim().apply { audioTracks = 1; videoTracks = 0 }
        s.warm()
        s.ticks(2, newAudio = 0)
        assertEquals(0, s.soundFixes)
        s.tick(newAudio = 0) // 6 s of film without a played audio buffer
        assertEquals(1, s.soundFixes)
        assertTrue(s.reopens.isEmpty())
        s.ticks(20, newAudio = 0) // still silent after the fix
        assertEquals(false, s.reopens.first()) // the first reopen keeps the local reserve
        assertTrue("Восстанавливаем звук…" in s.notices)
    }

    @Test fun silenceChosenByTheUserIsNotAProblem() {
        val s = Sim().apply { audioTracks = 1; userMuted = true; videoTracks = 0 }
        s.warm()
        s.ticks(30, newAudio = 0)
        assertEquals(0, s.soundFixes)
        assertTrue(s.reopens.isEmpty())
    }

    @Test fun missingAudioSelectionIsFixed() {
        val s = Sim().apply { audioTracks = 1; audioSelected = false; videoTracks = 0 }
        s.warm()
        s.ticks(2)
        assertTrue(s.soundFixes >= 1)
    }

    @Test fun reopenBudgetIsThreePerEpisodeAndResetsOnANewOne() {
        val s = Sim()
        s.warm("a")
        s.ticks(100, clock = 0)
        assertEquals(listOf(false, true, true), s.reopens) // later reopens also drop the local reserve

        s.sup.opening("a") // the same episode reopened: the budget stays spent
        s.sup.buffering(100f)
        s.ticks(60, clock = 0)
        assertEquals(3, s.reopens.size)

        s.sup.opening("b") // a new episode: fresh budget
        s.sup.buffering(100f)
        s.ticks(60, clock = 0)
        assertTrue(s.reopens.size > 3)
    }
}

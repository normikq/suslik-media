package ru.suslikovanora.media.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionsTest {
    @Test fun compare() {
        assertTrue(Versions.isNewer("1.10.0", "1.9.5"))
        assertFalse(Versions.isNewer("1.9.5", "1.9.5"))
        assertTrue(Versions.isNewer("2.0.0", "1.99.99"))
        assertEquals(-1, Versions.compare("1.2", "1.2.1"))
    }

    @Test fun channelVerdict() {
        assertEquals(UpdateChannel.Verdict.Offer, UpdateChannel.verdict("2.0.1", "2.0.0"))
        assertEquals(UpdateChannel.Verdict.Installed, UpdateChannel.verdict("2.0.1", "2.0.1"))
        assertEquals(UpdateChannel.Verdict.Older, UpdateChannel.verdict("2.0.0", "2.0.1"))
        assertEquals(UpdateChannel.Verdict.Nothing, UpdateChannel.verdict(null, "2.0.1"))
    }
}

class ChangelogTest {
    private val json = """[
      {"version":"1.9.0","title":"A","items":["a1"]},
      {"version":"2.0.0","title":"B","items":["b1","b2"]},
      {"version":"1.8.0","title":"C","items":["c1"]},
      {"version":"1.7.0","title":"D","items":[]}
    ]"""
    @Test fun pickAndOrder() {
        val all = Changelog.parse(json)
        assertEquals(3, all.size) // the one without items is dropped
        assertEquals(listOf("1.9.0", "1.8.0"), Changelog.upTo(all, "1.9.5").map { it.version })
        assertEquals(listOf("2.0.0", "1.9.0", "1.8.0"), Changelog.upTo(all, "2.0.0").map { it.version })
    }
    @Test fun showOnlyWhenNewNotes() {
        val list = Changelog.upTo(Changelog.parse(json), "1.9.5")
        assertTrue(Changelog.shouldShow(list, null))
        assertTrue(Changelog.shouldShow(list, "1.8.0"))
        assertFalse(Changelog.shouldShow(list, "1.9.0"))   // 1.9.5 has no own notes: nothing new
        assertFalse(Changelog.shouldShow(emptyList(), null))
    }
    @Test fun brokenJson() = assertEquals(emptyList(), Changelog.parse("{oops"))
}

class SupervisorTest {
    private var clock = 0L
    private val log = ArrayList<String>()
    private val acts = object : PlaybackSupervisor.Actions {
        override fun restartVideo() { log += "restartVideo" }
        override fun fixSound() { log += "fixSound" }
        override fun reopen(fresh: Boolean) { log += "reopen($fresh)" }
        override fun notice(text: String) {}
        override fun log(message: String) {}
    }
    private val sup = PlaybackSupervisor(acts, now = { clock })

    private var film = 0L; private var audio = 0L; private var pics = 0L
    private fun stats(fps: Double = 24.0) = PlaybackSupervisor.Stats(film, audio, audio, pics, fps, 1, true, 1, false)
    /** 2 s of wall time; [filmMs] of film clock; audio / picture counters grow if asked. */
    private fun step(filmMs: Long = 2000, sound: Boolean = true, picturesPerSec: Double = 24.0) {
        clock += 2000; film += filmMs
        if (sound) audio += 40
        pics += (picturesPerSec * filmMs / 1000).toLong()
        sup.tick(stats())
    }
    private fun startPlaying() { sup.opening("ep1"); sup.playing(); repeat(6) { step() } }

    @Test fun healthyPlaybackDoesNothing() { startPlaying(); repeat(30) { step() }; assertEquals(emptyList(), log) }

    @Test fun noFalseSilenceAfterSeek() {
        startPlaying()
        sup.seeked()
        // VLC refills its buffer: clock and sound stand still for a few seconds — not a silent film
        repeat(2) { step(filmMs = 0, sound = false, picturesPerSec = 0.0) }
        repeat(10) { step() }
        assertEquals(emptyList(), log)
    }

    @Test fun silentFilmFixedGently() {
        startPlaying()
        repeat(4) { step(sound = false) }
        assertEquals(listOf("fixSound"), log)
        repeat(8) { step(sound = false) }
        assertEquals(listOf("fixSound", "reopen(false)"), log)
    }

    @Test fun frozenPictureRestartsVideo() {
        startPlaying()
        repeat(3) { step(picturesPerSec = 0.0) }
        assertEquals(listOf("restartVideo"), log)
    }

    @Test fun choppyThenFreshReopen() {
        startPlaying()
        repeat(4) { step(picturesPerSec = 5.0) }
        assertEquals(listOf("restartVideo"), log)
        repeat(6) { step(picturesPerSec = 5.0) }
        assertEquals("restartVideo", log[1])
        repeat(6) { step(picturesPerSec = 5.0) }
        assertEquals("reopen(true)", log.last())
    }

    @Test fun pausedIsNeverJudged() {
        startPlaying(); sup.paused()
        repeat(20) { step(filmMs = 0, sound = false, picturesPerSec = 0.0) }
        assertEquals(emptyList(), log)
    }

    @Test fun stuckClockReopens() {
        startPlaying()
        repeat(7) { step(filmMs = 0, sound = false, picturesPerSec = 0.0) }
        assertEquals(listOf("reopen(false)"), log)
    }

    @Test fun stillCoverImageIsNotFrozen() {
        sup.opening("ep1"); sup.playing()
        repeat(20) { step(picturesPerSec = 0.0) } // audio with one still picture: pictures never move
        assertEquals(emptyList(), log)
    }

    @Test fun budgetsStopLoops() {
        startPlaying()
        repeat(200) { step(picturesPerSec = 0.0) }
        assertTrue(log.size <= 5, log.toString())
    }
}

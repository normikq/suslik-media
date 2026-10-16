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
        assertTrue(Versions.isNewer("2.0.0.1", "2.0.0"))
        assertFalse(Versions.isNewer("2.0.0.1", "2.0.1"))
        assertTrue(Versions.isNewer("2.0.1", "2.0.0.1"))
        assertEquals(-1, Versions.compare("1.2", "1.2.1"))
    }

    @Test fun channelVerdict() {
        assertEquals(UpdateChannel.Verdict.Offer, UpdateChannel.verdict("2.0.1", "2.0.0", release = false))
        assertEquals(UpdateChannel.Verdict.Installed, UpdateChannel.verdict("2.0.1", "2.0.1", release = true))
        assertEquals(UpdateChannel.Verdict.Older, UpdateChannel.verdict("2.0.0", "2.0.1", release = false))
        assertEquals(UpdateChannel.Verdict.Replace, UpdateChannel.verdict("1.9.5", "2.0.3", release = true))
        assertEquals(UpdateChannel.Verdict.Nothing, UpdateChannel.verdict(null, "2.0.1", release = true))
        assertEquals(UpdateChannel.Verdict.Offer, UpdateChannel.verdict("2.0.0.1", "2.0.0", release = false))
        assertEquals(UpdateChannel.Verdict.Replace, UpdateChannel.verdict("2.0.0", "2.0.0.1", release = true))
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

    @Test fun frozenAfterEveryRebufferingNeverReopens() {
        startPlaying()
        // 12.10 journal: a network slower than the film — the buffer runs dry again and again, and after each
        // rebuffering the picture stands while the sound plays. Each time: a new decoder, never a reopen.
        repeat(6) {
            sup.buffering(40f); step(filmMs = 0, sound = false, picturesPerSec = 0.0); sup.buffering(100f)
            repeat(3) { step(picturesPerSec = 0.0) }
            repeat(5) { step() }
        }
        assertEquals(List(6) { "restartVideo" }, log)
    }

    @Test fun frozenAgainReopensKeepingTheReserveFirst() {
        startPlaying()
        repeat(60) { step(picturesPerSec = 0.0) }
        assertEquals(listOf("restartVideo", "restartVideo", "reopen(false)"), log.take(3))
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

    @Test fun stillCoverAfterAMovingEpisodeIsNotFrozen() {
        startPlaying()                           // a film whose picture moves
        sup.opening("ep2"); sup.playing()        // next: an audio file with a still cover
        repeat(20) { step(picturesPerSec = 0.0) }
        assertEquals(emptyList(), log)
    }

    @Test fun budgetsStopLoops() {
        startPlaying()
        repeat(200) { step(picturesPerSec = 0.0) }
        assertTrue(log.size <= 5, log.toString())
    }
}

class LibraryKeepTest {
    private fun magnet(id: Long, name: String, link: String) =
        Magnet(id, name, "Готово", 4, 1, 1, listOf(MFile(0, "$name.mkv", "$name.mkv", false, 1, link)), true)

    @Test fun keepsWhatIsStillInTheLibrary() {
        val keep = LibraryKeep(prepareItems(listOf(magnet(7, "Interstellar.2014.1080p", "https://alld/f/abc"))))
        assertTrue(keep.usable)
        assertTrue(keep.keepsKey("7|Interstellar.2014.1080p.mkv"))
        assertTrue(keep.keepsKey("w:7|Interstellar.2014.1080p.mkv"))
        assertTrue(keep.keepsKey("7"))
        assertFalse(keep.keepsKey("8|Gone.mkv"))        // a film that left the library
        assertFalse(keep.keepsKey("w:8|Gone.mkv"))
        assertTrue(keep.keepsKey("pos:host/some/stream")) // not a library key: kept
        assertTrue(keep.posterKeys.any { keep.keepsPoster(it) })
        assertFalse(keep.keepsPoster("gone"))
        assertTrue("https://alld/f/abc" in keep.links)
    }

    @Test fun anEmptyListIsNeverUsedForCleaning() = assertFalse(LibraryKeep(emptyList()).usable)
}

class PlaybackPresetsTest {
    @Test fun clampAndValues() {
        assertEquals(0, PlaybackPresets.clampBurst(-5))
        assertEquals(0, PlaybackPresets.clampBurst(0))
        assertEquals(96, PlaybackPresets.clampBurst(96))
        assertEquals(160, PlaybackPresets.clampBurst(160))
        assertEquals(256, PlaybackPresets.clampBurst(256))
        assertEquals(256, PlaybackPresets.clampBurst(512))
        val presets = PlaybackPresets.burst("на телефоне")
        assertEquals(listOf(0, 32, 64, 96, 128, 160, 256), presets.map { it.value })
    }
}

class TimeTextTest {
    @Test fun clock() {
        assertEquals("00:00", clockText(-5))
        assertEquals("05:03", clockText(303_999))
        assertEquals("1:05:03", clockText(3_903_000))
    }
}

class CatchUpTest {
    private var clock = 1_000_000L
    private val c = CatchUp({}, { clock })

    @Test fun nothingWithoutAReserve() {
        assertFalse(c.maybeStart("https://x/not-in-the-reserve.mkv", canStart = true))
        assertFalse(c.active)
        assertFalse(c.end("https://x/not-in-the-reserve.mkv", restart = true)) // not active: nothing to end
    }
}

package ru.suslikovanora.media.core

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.suslikovanora.media.core.MarkersDetector.SubChoice
import ru.suslikovanora.media.core.MarkersDetector.Track

class MarkersDetectorTest {
    private val len = 45 * 60_000L

    @Test fun introAndCreditsFromChapters() {
        val m = MarkersDetector.fromChapters(listOf(
            Chapter(0, "Пролог"), Chapter(90_000, "Заставка"), Chapter(180_000, "Эпизод"),
            Chapter(len - 120_000, "Титры"), Chapter(len - 30_000, "Preview")
        ), len)
        assertEquals(TimeRange(90_000, 180_000), m.intro)
        assertEquals(TimeRange(len - 120_000, len), m.credits)
        assertEquals(len - 120_000, MarkersDetector.nextOfferAt(m, len))
    }

    @Test fun englishAndShortNames() {
        val m = MarkersDetector.fromChapters(listOf(
            Chapter(0, "Chapter 1"), Chapter(60_000, "OP"), Chapter(150_000, "Part A"), Chapter(len - 90_000, "ED 2")
        ), len)
        assertEquals(TimeRange(60_000, 150_000), m.intro)
        assertEquals(len - 90_000, m.credits?.startMs)
        // «Edward» and «Opera» are not the opening / ending
        val none = MarkersDetector.fromChapters(listOf(Chapter(0, "Edward"), Chapter(60_000, "Opera"), Chapter(len - 60_000, "Openly")), len)
        assertTrue(none.isEmpty)
    }

    @Test fun introWithoutNextChapterIsCapped() {
        val m = MarkersDetector.fromChapters(listOf(Chapter(0, "Начало"), Chapter(30_000, "Opening Credits")), len)
        // «Opening Credits» has both words: it is neither the intro nor the end
        assertNull(m.credits)
        assertNull(m.intro)
        val m2 = MarkersDetector.fromChapters(listOf(Chapter(0, "Intro")), len)
        assertEquals(TimeRange(0, MarkersDetector.INTRO_MAX_MS), m2.intro)
    }

    @Test fun creditsInTheFirstHalfAreIgnored() {
        val m = MarkersDetector.fromChapters(listOf(Chapter(0, "Credits"), Chapter(60_000, "Story")), len)
        assertNull(m.credits)
    }

    @Test fun serverFillsWhatChaptersLack() {
        val file = MediaMarkers(intro = TimeRange(10_000, 100_000))
        val server = MarkersDetector.parseServer(JSONObject("""{"intro":{"startMs":1,"endMs":2},"credits":{"startMs":2600000,"endMs":2690000}}"""))
        val m = MarkersDetector.merge(file, server, len)
        assertEquals(TimeRange(10_000, 100_000), m.intro)
        assertEquals(TimeRange(2_600_000, len), m.credits)
        assertNull(MarkersDetector.merge(MediaMarkers(), MediaMarkers(credits = TimeRange(len + 1, len + 9)), len).credits)
    }

    @Test fun serverPath() {
        assertEquals("/api/markers?title=Frieren&alt=%D0%A4%D1%80%D0%B8%D1%80%D0%B5%D0%BD&season=1&episode=3&duration=1420",
            MarkersDetector.serverPath("Frieren", "Фрирен", null, 3, 1_420_500))
        assertNull(MarkersDetector.serverPath("Frieren", null, 1, null, 0))
    }

    @Test fun introAtSkipsTheLastSeconds() {
        val m = MediaMarkers(intro = TimeRange(60_000, 150_000))
        assertEquals(m.intro, MarkersDetector.introAt(m, 61_000))
        assertNull(MarkersDetector.introAt(m, 149_000))
        assertNull(MarkersDetector.introAt(m, 10_000))
    }

    private val subs = listOf(
        Track(1, "Полные", "rus"),
        Track(2, "Форсированные", "rus", forced = true),
        Track(3, "Full", "eng")
    )

    @Test fun russianSpeechTakesForcedSubtitles() {
        assertEquals(SubChoice.Pick(2), MarkersDetector.pickSubtitle(Track(1, "Дубляж", "rus"), subs))
        assertEquals(SubChoice.Pick(2), MarkersDetector.pickSubtitle(Track(1, "MVO LostFilm", null), subs))
        // no forced track: subtitles off, not the full ones over the Russian speech
        assertEquals(SubChoice.Off, MarkersDetector.pickSubtitle(Track(1, null, "rus"), subs.filter { it.id != 2 }))
        // «Надписи» without the flag
        assertEquals(SubChoice.Pick(7), MarkersDetector.pickSubtitle(Track(1, "Кубик в Кубе", null), listOf(Track(6, "Полные", null), Track(7, "Надписи", null))))
    }

    @Test fun foreignSpeechTakesFullRussianSubtitles() {
        assertEquals(SubChoice.Pick(1), MarkersDetector.pickSubtitle(Track(5, "Original", "eng"), subs))
        assertEquals(SubChoice.Pick(1), MarkersDetector.pickSubtitle(Track(5, null, "jpn"), subs))
        assertEquals(SubChoice.Pick(9), MarkersDetector.pickSubtitle(Track(5, "Оригинал", null),
            listOf(Track(8, "Russian SDH", "rus"), Track(9, "Полные (Rus)", "rus"))))
        // no Russian ones: nothing changes
        assertEquals(SubChoice.Keep, MarkersDetector.pickSubtitle(Track(5, null, "eng"), listOf(Track(3, "Full", "eng"))))
    }

    @Test fun unknownSpeechChangesNothing() {
        assertEquals(SubChoice.Keep, MarkersDetector.pickSubtitle(Track(1, "Track 1", null), subs))
        assertEquals(SubChoice.Keep, MarkersDetector.pickSubtitle(null, subs))
    }

    @Test fun cyrillicCaseInsensitive() {
        assertTrue(MarkersDetector.INTRO_REGEX.containsMatchIn("ЗАСТАВКА"))
        assertTrue(MarkersDetector.CREDITS_REGEX.containsMatchIn("Финальные ТИТРЫ"))
    }
}

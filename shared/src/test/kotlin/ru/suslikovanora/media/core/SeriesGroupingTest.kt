package ru.suslikovanora.media.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class SeriesGroupingTest {
    private val MB = 1024L * 1024

    private fun magnet(name: String, vararg files: Pair<String, Long>) = Magnet(
        id = 1, name = name, status = "Ready", statusCode = 4, size = files.sumOf { it.second }, downloaded = 0,
        files = files.mapIndexed { i, (p, s) -> MFile(i, p.substringAfterLast('/'), p, false, s, "https://x/$i") },
        canRemove = true
    )

    @Test
    fun seasonMarksDoNotSplitTheTitle() {
        val a = titleKey(cleanTitle("Breaking.Bad.S01.1080p.BluRay"))
        val b = titleKey(cleanTitle("Breaking Bad Season 2 1080p"))
        val c = titleKey(cleanTitle("Breaking Bad [1-3 сезон] 1080p"))
        val d = titleKey(cleanTitle("Breaking Bad Complete 720p"))
        assertEquals("breaking bad", a)
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(a, d)
    }

    @Test
    fun differentShowsKeepDifferentKeys() {
        assertNotEquals(titleKey("Cyberpunk Edgerunners"), titleKey("Cyberpunk 2077"))
    }

    @Test
    fun numberedEpisodesAreASeriesNotACollection() {
        val files = (1..10).map { "Show/Show - ${it.toString().padStart(2, '0')} [1080p].mkv" to 800 * MB }
        val p = buildPlayable(magnet("Show [WEB-DL 1080p]", *files.toTypedArray()))
        assertIs<Playable.Series>(p)
        assertEquals(10, p.seasons.single().items.size)
        assertEquals(1, p.seasons.single().items.first().episode)
        assertEquals(null, p.seasons.single().items.first().title)
    }

    @Test
    fun trilogyStaysACollection() {
        val p = buildPlayable(magnet(
            "Trilogy",
            "Film 1 (1999).mkv" to 4000 * MB, "Film 2 (2003).mkv" to 4200 * MB, "Film 3 (2003).mkv" to 4100 * MB
        ))
        assertIs<Playable.Series>(p)
        assertEquals("Film 1", p.seasons.single().items.first().title)
    }
}

class AiTitleSearchGroupingTest {
    private val MB = 1024L * 1024

    private fun movie(id: Long, name: String, ai: AiTitle?) = Magnet(
        id = id, name = name, status = "Ready", statusCode = 4, size = 4000 * MB, downloaded = 0,
        files = listOf(MFile(0, "$name.mkv", "$name.mkv", false, 4000 * MB, "https://x/$id")),
        canRemove = true, aiTitle = ai
    )

    private val accepted = AiTitle("Дюна", null, "m", 1L, "accepted", kind = "movie", original = "Dune", year = 2021)

    @Test
    fun searchSeesTheAcceptedTitleOnly() {
        val item = prepareItems(listOf(movie(1, "Sousou.no.Frieren", accepted))).single()
        kotlin.test.assertTrue("дюна" in item.searchText)
        kotlin.test.assertTrue("dune" in item.searchText)
        val suggested = prepareItems(listOf(movie(1, "Sousou.no.Frieren", accepted.copy(status = "suggested")))).single()
        kotlin.test.assertFalse("дюна" in suggested.searchText)
        kotlin.test.assertTrue("дюна" in suggested.withAiTitle(accepted).searchText)
    }

    @Test
    fun aiYearJoinsReleasesWithoutYearInName() {
        val items = prepareItems(listOf(movie(1, "Dune.2021.1080p", accepted), movie(2, "Dune.BDRip", accepted)))
        val (shown, versions) = groupVersions(items)
        assertEquals(1, shown.size)
        assertEquals(2, versions.getValue(1L).size)
    }
}

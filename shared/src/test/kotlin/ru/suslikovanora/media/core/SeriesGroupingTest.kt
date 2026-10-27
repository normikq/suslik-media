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

    @Test
    fun versionsSortBySeasonChronologicallyAndQualityAndSize() {
        // The user's exact screenshot case: 4 releases of Cyberpunk Edgerunners
        // Row 1: Season 2, 4K, 26.2 GB
        // Row 2: Season 1, 1080p, 26.0 GB
        // Row 3: Season 2, 4K, 24.8 GB
        // Row 4: Season 1, 1080p, 16.2 GB
        val s2_4k_large = prepareItems(listOf(Magnet(
            id = 1, name = "Cyberpunk Edgerunners 2 [2160p WEB-DL DV HDR10]", status = "Ready", statusCode = 4,
            size = 26_200 * MB, downloaded = 0, canRemove = true,
            files = (1..10).map { i -> MFile(i, "Cyberpunk Edgerunners S02E$i.mkv", "Season 2/E$i.mkv", false, 2620 * MB, null) }
        ))).single()

        val s1_1080p_large = prepareItems(listOf(Magnet(
            id = 2, name = "Cyberpunk - Edgerunners [2022] [WEB-DL] [Dolby Vision] [1080p] [RU...]", status = "Ready", statusCode = 4,
            size = 26_000 * MB, downloaded = 0, canRemove = true,
            files = (1..10).map { i -> MFile(i, "Cyberpunk Edgerunners S01E$i.mkv", "Season 1/E$i.mkv", false, 2600 * MB, null) }
        ))).single()

        val s2_4k_small = prepareItems(listOf(Magnet(
            id = 3, name = "Cyberpunk Edgerunners 2 [2160p WEB-DL DV HDR10]", status = "Ready", statusCode = 4,
            size = 24_800 * MB, downloaded = 0, canRemove = true,
            files = (1..10).map { i -> MFile(i, "Cyberpunk Edgerunners S02E$i.mkv", "Season 2/E$i.mkv", false, 2480 * MB, null) }
        ))).single()

        val s1_1080p_small = prepareItems(listOf(Magnet(
            id = 4, name = "[Deadmau- RAWS] Cyberpunk.Edgerunners.S01.x264.2022.BDRip.1080...", status = "Ready", statusCode = 4,
            size = 16_200 * MB, downloaded = 0, canRemove = true,
            files = (1..10).map { i -> MFile(i, "Cyberpunk Edgerunners S01E$i.mkv", "Season 1/E$i.mkv", false, 1620 * MB, null) }
        ))).single()

        // Pass in jumbled order like in the user's screenshot
        val unsorted = listOf(s2_4k_large, s1_1080p_large, s2_4k_small, s1_1080p_small)
        val sorted = sortVersions(unsorted)

        // Season 1 must be before Season 2!
        // Within Season 1: 26.0 GB before 16.2 GB
        // Within Season 2: 26.2 GB before 24.8 GB
        assertEquals(listOf(s1_1080p_large.m.id, s1_1080p_small.m.id, s2_4k_large.m.id, s2_4k_small.m.id), sorted.map { it.m.id })

        val sections = versionSections(unsorted)
        assertEquals(2, sections.size)
        assertEquals("Сезон 1", sections[0].title)
        assertEquals(listOf(s1_1080p_large.m.id, s1_1080p_small.m.id), sections[0].items.map { it.m.id })
        assertEquals("Сезон 2", sections[1].title)
        assertEquals(listOf(s2_4k_large.m.id, s2_4k_small.m.id), sections[1].items.map { it.m.id })
    }

    @Test
    fun movieTrilogySortsByYearAndPart() {
        val part3 = prepareItems(listOf(Magnet(
            id = 3, name = "The Return of the King (2003) 4K Remux", status = "Ready", statusCode = 4,
            size = 45_000 * MB, downloaded = 0, canRemove = true,
            files = listOf(MFile(1, "The Return of the King (2003).mkv", "The Return of the King (2003).mkv", false, 45_000 * MB, null))
        ))).single()

        val part1_1080p = prepareItems(listOf(Magnet(
            id = 1, name = "The Fellowship of the Ring (2001) 1080p", status = "Ready", statusCode = 4,
            size = 15_000 * MB, downloaded = 0, canRemove = true,
            files = listOf(MFile(1, "The Fellowship of the Ring (2001).mkv", "The Fellowship of the Ring (2001).mkv", false, 15_000 * MB, null))
        ))).single()

        val part2 = prepareItems(listOf(Magnet(
            id = 2, name = "The Two Towers (2002) 4K Remux", status = "Ready", statusCode = 4,
            size = 40_000 * MB, downloaded = 0, canRemove = true,
            files = listOf(MFile(1, "The Two Towers (2002).mkv", "The Two Towers (2002).mkv", false, 40_000 * MB, null))
        ))).single()

        val part1_4k = prepareItems(listOf(Magnet(
            id = 4, name = "The Fellowship of the Ring (2001) 4K Remux", status = "Ready", statusCode = 4,
            size = 42_000 * MB, downloaded = 0, canRemove = true,
            files = listOf(MFile(1, "The Fellowship of the Ring (2001).mkv", "The Fellowship of the Ring (2001).mkv", false, 42_000 * MB, null))
        ))).single()

        val unsorted = listOf(part3, part1_1080p, part2, part1_4k)
        val sorted = sortVersions(unsorted)

        // Chronological: 2001 (4K, then 1080p) -> 2002 -> 2003
        assertEquals(listOf(part1_4k.m.id, part1_1080p.m.id, part2.m.id, part3.m.id), sorted.map { it.m.id })

        val sections = versionSections(unsorted)
        assertEquals(3, sections.size)
        assertEquals("2001 год", sections[0].title)
        assertEquals(listOf(part1_4k.m.id, part1_1080p.m.id), sections[0].items.map { it.m.id })
        assertEquals("2002 год", sections[1].title)
        assertEquals(listOf(part2.m.id), sections[1].items.map { it.m.id })
        assertEquals("2003 год", sections[2].title)
        assertEquals(listOf(part3.m.id), sections[2].items.map { it.m.id })
    }

    @Test
    fun singleSeasonOrMovieHasNullSectionTitle() {
        val v1 = prepareItems(listOf(Magnet(
            id = 1, name = "Inception (2010) 1080p", status = "Ready", statusCode = 4, size = 10_000 * MB, downloaded = 0, canRemove = true,
            files = listOf(MFile(1, "Inception.mkv", "Inception.mkv", false, 10_000 * MB, null))
        ))).single()
        val v2 = prepareItems(listOf(Magnet(
            id = 2, name = "Inception (2010) 4K", status = "Ready", statusCode = 4, size = 30_000 * MB, downloaded = 0, canRemove = true,
            files = listOf(MFile(1, "Inception.mkv", "Inception.mkv", false, 30_000 * MB, null))
        ))).single()

        val sections = versionSections(listOf(v1, v2))
        assertEquals(1, sections.size)
        assertEquals(null, sections.first().title)
        assertEquals(listOf(v2.m.id, v1.m.id), sections.first().items.map { it.m.id })
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

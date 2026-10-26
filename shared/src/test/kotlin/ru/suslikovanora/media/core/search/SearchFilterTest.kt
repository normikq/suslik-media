package ru.suslikovanora.media.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchFilterTest {

    private val hit4kRemux = SearchHit(
        id = "1",
        title = "Dune 2 (2024) BDRemux 2160p | DV | TrueHD",
        size = 60L * 1024 * 1024 * 1024,
        seeders = 100,
        leechers = 10,
        ru = true,
        kind = "movie",
        source = "Rutor",
        date = "2024-03-01"
    )

    private val hit1080pBDRip = SearchHit(
        id = "2",
        title = "Dune 2 (2024) BDRip 1080p | D",
        size = 8L * 1024 * 1024 * 1024,
        seeders = 50,
        leechers = 5,
        ru = true,
        kind = "movie",
        source = "Rutor",
        date = "2024-03-02"
    )

    private val hit720pMobile = SearchHit(
        id = "3",
        title = "Dune 2 (2024) WEB-DL 720p | D | x265",
        size = 2L * 1024 * 1024 * 1024,
        seeders = 30,
        leechers = 2,
        ru = true,
        kind = "movie",
        source = "Rutor",
        date = "2024-03-03"
    )

    private val hitCamRip = SearchHit(
        id = "4",
        title = "Dune 2 (2024) CAMRip | Экранка",
        size = 1500_000_000L,
        seeders = 20,
        leechers = 2,
        ru = true,
        kind = "movie",
        source = "Rutor",
        date = "2024-03-01"
    )

    private val hitNoSeeds = SearchHit(
        id = "5",
        title = "Old Dune WEB-DL 1080p",
        size = 4L * 1024 * 1024 * 1024,
        seeders = 0,
        leechers = 0,
        ru = false,
        kind = "movie",
        source = "TPB",
        date = "2020-01-01"
    )

    private val allHits = listOf(hit4kRemux, hit1080pBDRip, hit720pMobile, hitCamRip, hitNoSeeds)

    @Test
    fun testMobilePreset() {
        val filter = SearchFilterCriteria(preset = FilterPreset.MOBILE)
        val result = filter.filter(allHits)

        // Mobile preset must include the light 720p release
        assertTrue(result.contains(hit720pMobile))
        // Must exclude 4K remux, heavy 8GB BD, and CAMRip
        assertFalse(result.contains(hit4kRemux))
        assertFalse(result.contains(hit1080pBDRip))
        assertFalse(result.contains(hitCamRip))
    }

    @Test
    fun testOnlySeeded() {
        val filter = SearchFilterCriteria(onlySeeded = true)
        val result = filter.filter(allHits)

        assertFalse(result.contains(hitNoSeeds))
        assertEquals(4, result.size)
    }

    @Test
    fun testSortBySizeAscending() {
        val filter = SearchFilterCriteria(sort = SearchSort.SIZE_ASC)
        val result = filter.filter(allHits)

        // Lightest first
        assertEquals(hitCamRip.id, result[0].id)
        assertEquals(hit720pMobile.id, result[1].id)
        assertEquals(hitNoSeeds.id, result[2].id)
        assertEquals(hit1080pBDRip.id, result[3].id)
        assertEquals(hit4kRemux.id, result[4].id)
    }

    @Test
    fun testResolutionFilter() {
        val filter = SearchFilterCriteria(resolution = Resolution.UHD_4K)
        val result = filter.filter(allHits)

        assertEquals(1, result.size)
        assertEquals(hit4kRemux.id, result[0].id)
    }

    @Test
    fun testSearchQueryCleaner() {
        assertEquals("новый человек паук", SearchQueryCleaner.clean("фильмы новый человек паук"))
        assertEquals("во все тяжкие", SearchQueryCleaner.clean("сериал во все тяжкие смотреть"))
        assertEquals("интерстеллар", SearchQueryCleaner.clean("кино интерстеллар скачать в хорошем качестве"))
        assertEquals("король лев", SearchQueryCleaner.clean("мультфильм король лев онлайн"))
        assertEquals("фильмы", SearchQueryCleaner.clean("фильмы")) // fallback if empty
    }

    @Test
    fun testRelevanceRankingSpiderMan() {
        // Hit A: "Новый Человек-паук" (50 seeders)
        val hitAmazing = SearchHit(
            id = "a1",
            title = "Новый Человек-паук / The Amazing Spider-Man (2012) BDRip 1080p | D",
            size = 8L * 1024 * 1024 * 1024,
            seeders = 50,
            leechers = 5,
            ru = true,
            kind = "movie",
            source = "RuTracker"
        )
        // Hit B: Just "Человек-паук" (200 seeders - more seeds, but less relevant title)
        val hitOriginal = SearchHit(
            id = "b1",
            title = "Человек-паук / Spider-Man (2002) BDRip 1080p | D",
            size = 8L * 1024 * 1024 * 1024,
            seeders = 200,
            leechers = 10,
            ru = true,
            kind = "movie",
            source = "RuTracker"
        )
        // Hit C: Teaser release (should be excluded by trailer/teaser filter)
        val hitTeaser = SearchHit(
            id = "c1",
            title = "Человек-паук: Новый день [2026] тизер-трейлер",
            size = 500_000_000L,
            seeders = 300,
            leechers = 10,
            ru = true,
            kind = "movie",
            source = "RuTracker"
        )

        val hits = listOf(hitOriginal, hitTeaser, hitAmazing)
        val filter = SearchFilterCriteria(query = "новый человек паук", sort = SearchSort.DEFAULT)
        val result = filter.filter(hits)

        // 1. Teaser must be completely excluded
        assertFalse(result.any { it.id == "c1" }, "Teasers must be excluded from search results")

        // 2. "Новый Человек-паук" must be first despite fewer seeds
        assertEquals(2, result.size)
        assertEquals("a1", result[0].id, "Exact match 'Новый Человек-паук' must rank first")
        assertEquals("b1", result[1].id, "'Человек-паук' must rank second")
    }

    @Test
    fun testRelevanceRankingWithSpecificYear() {
        // Hit 2012: The Amazing Spider-Man 1 (2012)
        val hit2012 = SearchHit(
            id = "asm1",
            title = "Новый Человек-паук / The Amazing Spider-Man (2012) BDRip 1080p | D",
            size = 8L * 1024 * 1024 * 1024,
            seeders = 30,
            leechers = 5,
            ru = true,
            kind = "movie",
            source = "Rutor"
        )
        // Hit 2014: The Amazing Spider-Man 2 (2014) - higher seeds!
        val hit2014 = SearchHit(
            id = "asm2",
            title = "Новый Человек-паук: Высокое напряжение / The Amazing Spider-Man 2 (2014) BDRip 1080p | D",
            size = 9L * 1024 * 1024 * 1024,
            seeders = 150,
            leechers = 15,
            ru = true,
            kind = "movie",
            source = "Rutor"
        )

        val hits = listOf(hit2014, hit2012)
        val filter = SearchFilterCriteria(query = "новый человек паук 2012", sort = SearchSort.DEFAULT)
        val result = filter.filter(hits)

        assertEquals("asm1", result[0].id, "Release matching requested year 2012 must be ranked first")
        assertEquals("asm2", result[1].id, "Release from 2014 should be ranked below 2012")
    }
}

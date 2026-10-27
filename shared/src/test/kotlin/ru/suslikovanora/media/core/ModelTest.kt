package ru.suslikovanora.media.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {

    // -------------------------------------------------------------------------
    // 1. parseLibrary & Magnet model
    // -------------------------------------------------------------------------

    @Test
    fun parseLibraryEmptyOrInvalidJson() {
        assertEquals(emptyList(), parseLibrary(JSONObject()))
        assertEquals(emptyList(), parseLibrary(JSONObject("""{"magnets": []}""")))
        assertEquals(emptyList(), parseLibrary(JSONObject("""{"magnets": "not-an-array"}""")))
    }

    @Test
    fun parseLibraryValidMagnetsAndFiles() {
        val json = JSONObject("""
        {
          "magnets": [
            {
              "id": 101,
              "name": "Interstellar.2014.1080p",
              "status": "Downloading",
              "statusCode": 2,
              "size": 10000000,
              "downloaded": 5000000,
              "canRemove": false,
              "favorite": true,
              "mine": true,
              "files": [
                {
                  "name": "Interstellar.mkv",
                  "path": "Interstellar/Interstellar.mkv",
                  "type": "file",
                  "size": 9500000,
                  "sourceLink": "https://alldebrid.com/f/xyz"
                },
                {
                  "name": "Subs",
                  "path": "Interstellar/Subs",
                  "type": "folder",
                  "size": 0,
                  "sourceLink": ""
                },
                {
                  "name": "insecure.srt",
                  "path": "Interstellar/Subs/insecure.srt",
                  "type": "file",
                  "size": 5000,
                  "sourceLink": "http://insecure.test/file.srt"
                }
              ]
            },
            {
              "id": 102,
              "name": "",
              "status": "",
              "statusCode": 4
            }
          ]
        }
        """.trimIndent())

        val list = parseLibrary(json)
        assertEquals(2, list.size)

        val m1 = list[0]
        assertEquals(101L, m1.id)
        assertEquals("Interstellar.2014.1080p", m1.name)
        assertEquals("Downloading", m1.status)
        assertEquals(2, m1.statusCode)
        assertEquals(10000000L, m1.size)
        assertEquals(5000000L, m1.downloaded)
        assertFalse(m1.canRemove)
        assertTrue(m1.favorite)
        assertTrue(m1.mine)
        assertFalse(m1.ready)
        assertTrue(m1.downloading)
        assertFalse(m1.failed)
        assertEquals(0.5f, m1.progress, 0.0001f)

        assertEquals(3, m1.files.size)
        assertEquals(0, m1.files[0].index)
        assertEquals("Interstellar.mkv", m1.files[0].name)
        assertEquals("Interstellar/Interstellar.mkv", m1.files[0].path)
        assertFalse(m1.files[0].isFolder)
        assertEquals("https://alldebrid.com/f/xyz", m1.files[0].link)

        assertTrue(m1.files[1].isFolder)
        assertNull(m1.files[1].link)

        // http links are rejected: must start with https://
        assertNull(m1.files[2].link)

        // Fallback name when blank
        val m2 = list[1]
        assertEquals("Magnet 102", m2.name)
        assertTrue(m2.ready)
        assertFalse(m2.downloading)
        assertEquals(0f, m2.progress)
        assertTrue(m2.canRemove) // default true
        assertFalse(m2.favorite) // default false
        assertFalse(m2.mine)
    }

    @Test
    fun magnetStatesAndProgress() {
        val readyMagnet = Magnet(1, "Ready", "Ready", 4, 100, 100, emptyList(), true)
        assertTrue(readyMagnet.ready)
        assertFalse(readyMagnet.downloading)
        assertFalse(readyMagnet.failed)
        assertEquals(1.0f, readyMagnet.progress)

        val failedMagnet = Magnet(2, "Failed", "Error", 5, 100, 10, emptyList(), true)
        assertFalse(failedMagnet.ready)
        assertFalse(failedMagnet.downloading)
        assertTrue(failedMagnet.failed)

        val zeroSizeMagnet = Magnet(3, "Zero", "Init", 1, 0, 0, emptyList(), true)
        assertEquals(0f, zeroSizeMagnet.progress)

        val overDownloadedMagnet = Magnet(4, "Over", "Downloading", 2, 100, 150, emptyList(), true)
        assertEquals(1.0f, overDownloadedMagnet.progress)
    }

    // -------------------------------------------------------------------------
    // 2. cleanTitle
    // -------------------------------------------------------------------------

    @Test
    fun cleanTitleStripsReleaseTagsAndYears() {
        assertEquals("Inception", cleanTitle("Inception.2010.1080p.BluRay.x264-SPARKS.mkv"))
        assertEquals("Breaking Bad S01", cleanTitle("Breaking.Bad.S01.1080p.NF.WEB-DL.DDP5.1.H.264-NTb"))
        assertEquals("Dune Part Two", cleanTitle("Dune.Part.Two.2024.2160p.UHD.Remux.HEVC.DV.HDR10+.mkv"))
        assertEquals("The Matrix", cleanTitle("The Matrix (1999) [BDRip 720p]"))
        assertEquals("Movie Title", cleanTitle("Movie_Title_2021_Proper_Repack"))
        assertEquals("Severance S01E01", cleanTitle("Severance.S01E01.480p.WEBRip.x264.mp4"))
    }

    @Test
    fun cleanTitleEdgeCases() {
        assertEquals("Untitled", cleanTitle(""))
        assertEquals("Untitled", cleanTitle("   "))
        // If everything was stripped by tags, fallback restores name with dots replaced by spaces
        val onlyTag = cleanTitle("1080p")
        assertTrue(onlyTag.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // 3. formatBytes & statusRu
    // -------------------------------------------------------------------------

    @Test
    fun formatBytesFormatting() {
        assertEquals("—", formatBytes(0))
        assertEquals("—", formatBytes(-100))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("10.0 MB", formatBytes(10L * 1024 * 1024))
        assertEquals("4.2 GB", formatBytes((4.2 * 1024 * 1024 * 1024).toLong()))
        assertEquals("1.5 TB", formatBytes((1.5 * 1024 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun statusRuMapping() {
        fun makeM(code: Int, status: String) = Magnet(1, "Test", status, code, 0, 0, emptyList(), true)
        assertEquals("Готово", statusRu(makeM(4, "Uploading")))
        assertEquals("Загрузка", statusRu(makeM(2, "Downloading")))
        assertEquals("Загрузка", statusRu(makeM(2, "downloading")))
        assertEquals("Обработка", statusRu(makeM(1, "")))
        assertEquals("Обработка", statusRu(makeM(1, "   ")))
        assertEquals("Ошибка", statusRu(makeM(5, "")))
        assertEquals("Bad Torrent", statusRu(makeM(5, "Bad Torrent")))
        assertEquals("Queued", statusRu(makeM(1, "Queued")))
    }

    // -------------------------------------------------------------------------
    // 4. mainVideo & Playable detection
    // -------------------------------------------------------------------------

    private fun file(idx: Int, path: String, sizeMb: Long = 1000, link: String = "https://host/f/$idx"): MFile =
        MFile(idx, path.substringAfterLast('/'), path, false, sizeMb * 1024 * 1024, link)

    @Test
    fun mainVideoSelectsLargestAndIgnoresIso() {
        val m = Magnet(1, "Film", "Готово", 4, 0, 0, listOf(
            file(0, "Film.sample.mkv", 50),
            file(1, "Film.iso", 8000),
            file(2, "Film.mkv", 4000)
        ), true)
        val main = mainVideo(m)
        assertNotNull(main)
        assertEquals("Film.mkv", main.name)

        // When only .iso is available, fallback to .iso
        val isoOnly = Magnet(2, "Iso", "Готово", 4, 0, 0, listOf(file(0, "Disc.iso", 4000)), true)
        assertEquals("Disc.iso", mainVideo(isoOnly)?.name)

        // No video files
        val noVid = Magnet(3, "Doc", "Готово", 4, 0, 0, listOf(file(0, "readme.txt", 1)), true)
        assertNull(mainVideo(noVid))
    }

    @Test
    fun buildPlayableSingleMovie() {
        val m = Magnet(1, "Avatar", "Готово", 4, 0, 0, listOf(
            file(0, "Avatar.sample.mkv", 30),
            file(1, "Avatar.mkv", 8000)
        ), true)
        val playable = buildPlayable(m)
        assertIs<Playable.Movie>(playable)
        assertEquals("Avatar.mkv", playable.file.name)
    }

    @Test
    fun buildPlayableSeriesByDirectoryStructure() {
        val m = Magnet(1, "Breaking Bad", "Готово", 4, 0, 0, listOf(
            file(0, "Breaking Bad/Season 1/ep01.mkv", 1000),
            file(1, "Breaking Bad/Season 1/ep02.mkv", 1000),
            file(2, "Breaking Bad/Season 1/ep10.mkv", 1000),
            file(3, "Breaking Bad/Season 2/ep01.mkv", 1000)
        ), true)

        val playable = buildPlayable(m)
        assertIs<Playable.Series>(playable)
        assertEquals(2, playable.seasons.size)

        val s1 = playable.seasons[0]
        assertEquals(1, s1.season)
        assertEquals(3, s1.items.size)
        // Natural sort check: ep01, ep02, ep10
        assertEquals("ep01.mkv", s1.items[0].file.name)
        assertEquals(1, s1.items[0].episode)
        assertEquals("ep02.mkv", s1.items[1].file.name)
        assertEquals(2, s1.items[1].episode)
        assertEquals("ep10.mkv", s1.items[2].file.name)
        assertEquals(3, s1.items[2].episode)

        val s2 = playable.seasons[1]
        assertEquals(2, s2.season)
        assertEquals(1, s2.items.size)
        assertEquals("ep01.mkv", s2.items[0].file.name)
    }

    @Test
    fun buildPlayableSeriesByRussianSeasonDirectory() {
        val m = Magnet(1, "Чернобыль", "Готово", 4, 0, 0, listOf(
            file(0, "Чернобыль/Сезон 1/01.mkv", 1000),
            file(1, "Чернобыль/Сезон 1/02.mkv", 1000)
        ), true)

        val playable = buildPlayable(m)
        assertIs<Playable.Series>(playable)
        assertEquals(1, playable.seasons.size)
        assertEquals(1, playable.seasons[0].season)
        assertEquals(2, playable.seasons[0].items.size)
    }

    @Test
    fun buildPlayableSeriesByFilenamePattern() {
        // Files flat in one folder or without Season dirs: SxxEyy, 1x02, etc.
        val m = Magnet(1, "Sherlock", "Готово", 4, 0, 0, listOf(
            file(0, "Sherlock.S01E01.mkv", 1500),
            file(1, "Sherlock.S01E02.mkv", 1500),
            file(2, "Sherlock.1x03.mkv", 1500)
        ), true)

        val playable = buildPlayable(m)
        assertIs<Playable.Series>(playable)
        assertEquals(1, playable.seasons.size)
        assertEquals(3, playable.seasons[0].items.size)
        assertEquals(1, playable.seasons[0].items[0].episode)
        assertEquals(2, playable.seasons[0].items[1].episode)
        assertEquals(3, playable.seasons[0].items[2].episode)
    }

    @Test
    fun buildPlayableIgnoresSampleFiles() {
        val m = Magnet(1, "Movie with samples", "Готово", 4, 0, 0, listOf(
            file(0, "sample/sample.mkv", 50),
            file(1, "movie.sample.avi", 50),
            file(2, "movie.mkv", 2000)
        ), true)

        val playable = buildPlayable(m)
        assertIs<Playable.Movie>(playable)
        assertEquals("movie.mkv", playable.file.name)
    }

    @Test
    fun buildPlayableFilmCollection() {
        // Trilogy: multiple large files (>300 MB, each >= 1/4 of max), no season/episode marks
        val m = Magnet(1, "Lord of the Rings Trilogy", "Готово", 4, 0, 0, listOf(
            file(0, "1.The.Fellowship.of.the.Ring.2001.1080p.mkv", 8000),
            file(1, "2.The.Two.Towers.2002.1080p.mkv", 8200),
            file(2, "3.The.Return.of.the.King.2003.1080p.mkv", 9000),
            file(3, "Extras/featurette.mkv", 500) // should be filtered out by EXTRAS
        ), true)

        val playable = buildPlayable(m)
        assertIs<Playable.Series>(playable)
        assertEquals(1, playable.seasons.size)
        val films = playable.seasons[0].items
        assertEquals(3, films.size)
        assertEquals("1 The Fellowship of the Ring", films[0].title)
        assertEquals("2 The Two Towers", films[1].title)
        assertEquals("3 The Return of the King", films[2].title)
    }

    @Test
    fun buildPlayableBlurayDirectoriesNotTreatedAsCollection() {
        // Blu-ray BDMV/STREAM files must not be mistaken for a collection of films
        val m = Magnet(1, "Blu-ray Disc", "Готово", 4, 0, 0, listOf(
            file(0, "BDMV/STREAM/00001.m2ts", 20000),
            file(1, "BDMV/STREAM/00002.m2ts", 15000)
        ), true)

        val playable = buildPlayable(m)
        // Should fall back to single Movie (the largest file)
        assertIs<Playable.Movie>(playable)
        assertEquals("00001.m2ts", playable.file.name)
    }

    @Test
    fun buildPlayableNoVideosReturnsNone() {
        val m = Magnet(1, "Music Album", "Готово", 4, 0, 0, listOf(
            file(0, "song1.flac", 30),
            file(1, "song2.flac", 30)
        ), true)
        assertEquals(Playable.None, buildPlayable(m))
    }

    // -------------------------------------------------------------------------
    // 5. episodeLabel
    // -------------------------------------------------------------------------

    @Test
    fun episodeLabelFormatting() {
        val f = file(0, "ep.mkv")
        val s1 = Season(1, listOf(Episode(1, 2, f)))
        assertEquals("Breaking Bad · S01E02", episodeLabel("Breaking Bad", s1, s1.items[0]))

        val s0 = Season(0, listOf(Episode(0, 1, f)))
        assertEquals("Doctor Who · Спец.E01", episodeLabel("Doctor Who", s0, s0.items[0]))

        // Film collection with custom title
        val sCol = Season(1, listOf(Episode(1, 1, f, title = "Alien 1")))
        assertEquals("Alien 1", episodeLabel("Alien Collection", sCol, sCol.items[0]))
    }

    // -------------------------------------------------------------------------
    // 6. subtitlesFor & externalAudioFor
    // -------------------------------------------------------------------------

    @Test
    fun subtitlesMatchedByNameAndFolder() {
        val vid1 = file(0, "Series/S01/Episode.01.1080p.mkv")
        val vid2 = file(1, "Series/S01/Episode.02.1080p.mkv")
        val sub1Rus = file(2, "Series/S01/Episode.01.1080p.rus.srt")
        val sub1Eng = file(3, "Series/S01/Episode.01.1080p.eng.ass")
        val sub2Rus = file(4, "Series/S01/Subs/Episode.02.1080p.srt")
        val orphanSub = file(5, "Series/S01/other.srt")

        val m = Magnet(1, "Series", "Готово", 4, 0, 0, listOf(vid1, vid2, sub1Rus, sub1Eng, sub2Rus, orphanSub), true)

        val s1 = subtitlesFor(m, vid1)
        assertEquals(listOf(sub1Rus, sub1Eng), s1)

        val s2 = subtitlesFor(m, vid2)
        assertEquals(listOf(sub2Rus), s2)
    }

    @Test
    fun subtitlesForSingleVideoAcceptsAnyNearOrInTorrent() {
        val vid = file(0, "Movie/Movie.mkv")
        val sub1 = file(1, "Movie/Subs/rus.srt")
        val sub2 = file(2, "Movie/eng.vtt")
        val m = Magnet(1, "Movie", "Готово", 4, 0, 0, listOf(vid, sub1, sub2), true)

        val subs = subtitlesFor(m, vid)
        assertEquals(2, subs.size)
    }

    @Test
    fun externalAudioForMatchedByName() {
        val vid1 = file(0, "Anime/Ep01.mkv")
        val vid2 = file(1, "Anime/Ep02.mkv")
        val aud1 = file(2, "Anime/Audio/Ep01.rus.mka")
        val aud2 = file(3, "Anime/Audio/Ep02.rus.mka")

        val m = Magnet(1, "Anime", "Готово", 4, 0, 0, listOf(vid1, vid2, aud1, aud2), true)

        assertEquals(listOf(aud1), externalAudioFor(m, vid1))
        assertEquals(listOf(aud2), externalAudioFor(m, vid2))
    }

    @Test
    fun episodeOneDoesNotTakeEpisodeTensFiles() {
        val e1 = file(0, "Show/Show.S01E1.mkv")
        val e10 = file(1, "Show/Show.S01E10.mkv")
        val sub1 = file(2, "Show/Show.S01E1.rus.srt")
        val sub10 = file(3, "Show/Show.S01E10.rus.srt")
        val aud1 = file(4, "Show/Show.S01E1_rus.mka")
        val aud10 = file(5, "Show/Show.S01E10.mka")
        val m = Magnet(1, "Show", "Готово", 4, 0, 0, listOf(e1, e10, sub1, sub10, aud1, aud10), true)

        assertEquals(listOf(sub1), subtitlesFor(m, e1))
        assertEquals(listOf(sub10), subtitlesFor(m, e10))
        assertEquals(listOf(aud1), externalAudioFor(m, e1))
        assertEquals(listOf(aud10), externalAudioFor(m, e10))
    }

    @Test
    fun externalAudioSingleVideoFallback() {
        val vid = file(0, "Film/Film.mkv")
        val dub = file(1, "Film/Dub.ac3")
        val m = Magnet(1, "Film", "Готово", 4, 0, 0, listOf(vid, dub), true)

        assertEquals(listOf(dub), externalAudioFor(m, vid))
    }

    // -------------------------------------------------------------------------
    // 7. WatchInfo and watchKey
    // -------------------------------------------------------------------------

    @Test
    fun watchKeyConstruction() {
        val f = file(5, "folder/movie.mkv")
        assertEquals("42|folder/movie.mkv", watchKey(42, f))
    }

    @Test
    fun watchInfoProgressAndWatchedThreshold() {
        val w1 = WatchInfo(pos = 50_000, len = 100_000, at = 123456789)
        assertEquals(0.5f, w1.fraction, 0.0001f)
        assertFalse(w1.watched)

        // Watched is triggered when pos >= len - 15_000
        val wWatched = WatchInfo(pos = 85_000, len = 100_000, at = 123456789)
        assertTrue(wWatched.watched)

        val wNearEnd = WatchInfo(pos = 99_000, len = 100_000, at = 123456789)
        assertTrue(wNearEnd.watched)

        // Zero length safety
        val wZero = WatchInfo(pos = 0, len = 0, at = 0)
        assertEquals(0f, wZero.fraction)
        assertFalse(wZero.watched)
    }

    // -------------------------------------------------------------------------
    // 8. prepareItems
    // -------------------------------------------------------------------------

    @Test
    fun prepareItemsComputesTileData() {
        val m = Magnet(10, "Inception.2010.1080p", "Готово", 4, 5000, 5000, listOf(
            file(0, "Inception.mkv", 4000)
        ), true)

        val items = prepareItems(listOf(m))
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("Inception", item.baseTitle)
        assertIs<Playable.Movie>(item.playable)
        assertTrue(item.posterQueries.contains("Inception"))
        assertTrue("inception" in item.searchText)
        assertTrue("inception.mkv" in item.searchText)
    }

    // -------------------------------------------------------------------------
    // AI title (server services/aititle.js): parsed, used for grouping only once accepted
    // -------------------------------------------------------------------------

    private fun aiLibrary(status: String) = JSONObject("""
        {
          "aiTitleReadyAt": 1700000000000,
          "magnets": [
            { "id": 1, "name": "Тачки 2 (2011) BDRip", "statusCode": 4,
              "files": [ { "name": "Cars.2.mkv", "path": "Cars.2.mkv", "type": "file", "size": 4000, "sourceLink": "https://host/1" } ] },
            { "id": 2, "name": "Cars.Two.2011.720p", "statusCode": 4, "canAiTitle": true,
              "aiTitle": { "base": " Тачки 2 ", "season": null, "source": "ai", "model": "gemini-3.5-flash-lite", "at": 5, "status": "$status" },
              "files": [ { "name": "Cars2.mkv", "path": "Cars2.mkv", "type": "file", "size": 2000, "sourceLink": "https://host/2" } ] }
          ]
        }
    """.trimIndent())

    @Test
    fun parseLibraryAiTitle() {
        val json = aiLibrary("suggested")
        val list = parseLibrary(json)
        assertNull(list[0].aiTitle)
        assertFalse(list[0].canAiTitle)
        assertEquals(AiTitle("Тачки 2", null, "gemini-3.5-flash-lite", 5L, "suggested"), list[1].aiTitle)
        assertTrue(list[1].canAiTitle)
        assertFalse(list[1].aiTitle!!.accepted)
        assertEquals(1700000000000L, aiTitleReadyAt(json))
        assertEquals(0L, aiTitleReadyAt(JSONObject()))
        val season = parseLibrary(JSONObject("""{"magnets":[{"id":3,"name":"S","aiTitle":{"base":"Тед Лассо","season":2,"status":"accepted"}}]}"""))
        assertEquals(2, season[0].aiTitle?.season)
        assertTrue(season[0].aiTitle!!.accepted)
        val bad = parseLibrary(JSONObject("""{"magnets":[{"id":4,"name":"S","aiTitle":{"base":"X","status":"weird"}}]}"""))
        assertNull(bad[0].aiTitle)
    }

    @Test
    fun groupVersionsUsesOnlyAcceptedAiTitle() {
        val accepted = prepareItems(parseLibrary(aiLibrary("accepted")))
        assertEquals("Тачки 2", groupTitle(accepted[1]))
        val (shown, versions) = groupVersions(accepted)
        assertEquals(listOf(1L), shown.map { it.m.id })
        assertEquals(listOf(1L, 2L), versions[1L]?.map { it.m.id })
        for (status in listOf("suggested", "rejected", "none")) {
            val items = prepareItems(parseLibrary(aiLibrary(status)))
            assertEquals(items[1].baseTitle, groupTitle(items[1]))
            assertEquals(listOf(1L, 2L), groupVersions(items).first.map { it.m.id }, status)
        }
    }

    @Test
    fun acceptedAiTitleNamesTheTileAndItsPoster() {
        val items = prepareItems(parseLibrary(aiLibrary("accepted")))
        assertEquals("Тачки 2", items[1].baseTitle)
        assertEquals("Тачки 2", items[1].posterQueries.first())
        val suggested = prepareItems(parseLibrary(aiLibrary("suggested")))[1]
        assertTrue(suggested.baseTitle != "Тачки 2" || suggested.m.name.contains("Тачки"))
        // «Принять» и «Отклонить» без новой загрузки медиатеки
        val accepted = suggested.withAiTitle(suggested.m.aiTitle!!.copy(status = "accepted"))
        assertEquals("Тачки 2", accepted.baseTitle)
        assertEquals("Тачки 2", accepted.posterQueries.first())
        val rejected = accepted.withAiTitle(accepted.m.aiTitle!!.copy(status = "rejected"))
        assertEquals(cleanTitle(suggested.m.name), rejected.baseTitle)
        assertEquals(suggested.playable, rejected.playable)
    }
}

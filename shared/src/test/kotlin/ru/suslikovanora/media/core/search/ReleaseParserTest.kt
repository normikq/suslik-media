package ru.suslikovanora.media.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReleaseParserTest {

    @Test
    fun parse4kDolbyVisionRemux() {
        val title = "Дюна: Часть вторая / Dune: Part Two (2024) BDRemux 2160p | HDR, Dolby Vision | DDP 5.1, TrueHD 7.1.Atmos | D"
        val size = 58L * 1024 * 1024 * 1024 // 58 GB

        val info = ReleaseParser.parse(title, size, "movie")

        assertEquals("Дюна: Часть вторая / Dune: Part Two (2024)", info.cleanTitle)
        assertEquals(2024, info.year)
        assertEquals(Resolution.UHD_4K, info.resolution)
        assertEquals(RipType.REMUX, info.ripType)
        assertEquals(HdrType.DV, info.hdr)
        assertEquals(VoiceType.DUB, info.voice)
        assertFalse(info.isCam)
        assertEquals(BitrateTier.HEAVY, info.bitrateTier)
        assertTrue(info.estimatedBitrateMbps > 50f)
        assertFalse(info.isMobileFriendly)

        assertTrue(info.badges.contains("4K"))
        assertTrue(info.badges.contains("BDRemux"))
        assertTrue(info.badges.contains("Dolby Vision"))
        assertTrue(info.badges.contains("Дубляж"))
    }

    @Test
    fun parse1080pBDRipDubbed() {
        val title = "Интерстеллар / Interstellar (2014) BDRip 1080p | D, P, A | Лицензия"
        val size = 8L * 1024 * 1024 * 1024 // 8 GB

        val info = ReleaseParser.parse(title, size, "movie")

        assertEquals("Интерстеллар / Interstellar (2014)", info.cleanTitle)
        assertEquals(2014, info.year)
        assertEquals(Resolution.FHD_1080P, info.resolution)
        assertEquals(RipType.BDRIP, info.ripType)
        assertEquals(VoiceType.DUB, info.voice)
        assertFalse(info.isCam)
        assertEquals(BitrateTier.MEDIUM, info.bitrateTier)
        assertTrue(info.estimatedBitrateMbps in 9f..12f)
    }

    @Test
    fun parseMobileFriendly720pWebDl() {
        val title = "Оппенгеймер / Oppenheimer (2023) WEB-DLRip 720p | D"
        val size = 2L * 1024 * 1024 * 1024 + 200L * 1024 * 1024 // ~2.2 GB

        val info = ReleaseParser.parse(title, size, "movie")

        assertEquals("Оппенгеймер / Oppenheimer (2023)", info.cleanTitle)
        assertEquals(2023, info.year)
        assertEquals(Resolution.HD_720P, info.resolution)
        assertEquals(RipType.WEBDL, info.ripType)
        assertEquals(VoiceType.DUB, info.voice)
        assertFalse(info.isCam)
        assertEquals(BitrateTier.LIGHT, info.bitrateTier)
        assertTrue(info.estimatedBitrateMbps in 2.5f..3.2f)
        assertTrue(info.isMobileFriendly)
        assertTrue(info.badges.contains("720p"))
        assertTrue(info.badges.contains("WEB-DL"))
    }

    @Test
    fun parseHevcBDRip() {
        val title = "Мальчик и птица / The Boy and the Heron (2023) BDRip-HEVC 1080p | MVO"
        val size = 3L * 1024 * 1024 * 1024 // 3 GB

        val info = ReleaseParser.parse(title, size, "movie")

        assertEquals(Resolution.FHD_1080P, info.resolution)
        assertEquals(VideoCodec.HEVC, info.codec)
        assertEquals(RipType.BDRIP, info.ripType)
        assertEquals(VoiceType.MVO, info.voice)
        assertTrue(info.isMobileFriendly)
        assertTrue(info.badges.contains("HEVC"))
    }

    @Test
    fun parseCamRipAndTelesync() {
        val title1 = "Гладиатор 2 / Gladiator II (2024) CAMRip | Звук с TS"
        val info1 = ReleaseParser.parse(title1, 1500_000_000L, "movie")
        assertTrue(info1.isCam)
        assertEquals(RipType.CAM, info1.ripType)
        assertFalse(info1.isMobileFriendly)

        val title2 = "Форсаж 10 / Fast X (2023) Telesync [TS]"
        val info2 = ReleaseParser.parse(title2, 1800_000_000L, "movie")
        assertTrue(info2.isCam)
        assertEquals(RipType.CAM, info2.ripType)
        assertFalse(info2.isMobileFriendly)
    }

    @Test
    fun parseSeriesWithEpisodeCount() {
        // 1. "серии 1-9 из 9"
        val t1 = "Одни из нас / The Last of Us (Сезон 1, серии 1-9 из 9) (2023) WEB-DL 1080p | Red Head Sound"
        val info1 = ReleaseParser.parse(t1, 18L * 1024 * 1024 * 1024, "series")
        assertEquals(9, info1.episodesCount)
        assertEquals(Resolution.FHD_1080P, info1.resolution)
        assertEquals(RipType.WEBDL, info1.ripType)
        // 18 GB / 9 eps = 2 GB per 45 min episode -> ~5.9 Mbps
        assertTrue(info1.estimatedBitrateMbps in 5.5f..6.5f)

        // 2. "[01-05x01-62]"
        val t2 = "Во все тяжкие / Breaking Bad [01-05x01-62] (2008-2013) BDRip 720p | LostFilm"
        val info2 = ReleaseParser.parse(t2, 50L * 1024 * 1024 * 1024, "series")
        assertEquals(62, info2.episodesCount)
        assertEquals(Resolution.HD_720P, info2.resolution)
        // 50 GB / 62 eps = ~0.8 GB per episode -> ~2.4 Mbps (Light!)
        assertTrue(info2.estimatedBitrateMbps in 2.0f..2.8f)
        assertEquals(BitrateTier.LIGHT, info2.bitrateTier)
        assertTrue(info2.isMobileFriendly)

        // 3. "[01-08x01-73 из 73]"
        val t3 = "Игра престолов / Game of Thrones [01-08x01-73 из 73] (2011-2019) BDRip 1080p | LostFilm"
        val info3 = ReleaseParser.parse(t3, 120L * 1024 * 1024 * 1024, "series")
        assertEquals(73, info3.episodesCount)
        assertEquals(Resolution.FHD_1080P, info3.resolution)
    }

    @Test
    fun parseEnglishDottedTitle() {
        val title = "Dune.Part.Two.2024.2160p.UHD.BluRay.x265.TrueHD.7.1.Atmos.DV.HDR10-SWTYBLZ"
        val info = ReleaseParser.parse(title, 40L * 1024 * 1024 * 1024, "movie")

        assertEquals("Dune Part Two 2024", info.cleanTitle)
        assertEquals(2024, info.year)
        assertEquals(Resolution.UHD_4K, info.resolution)
        assertEquals(VideoCodec.HEVC, info.codec)
        assertEquals(RipType.BLURAY, info.ripType)
        assertEquals(HdrType.DV, info.hdr)
        assertFalse(info.isCam)
    }

    @Test
    fun parseResidentEvilCollection() {
        val title = "Обитель зла / Resident Evil (2002-2023) BDRip [H.265/1080p] [10-bit] [Коллекция]"
        val size = (83.3 * 1024 * 1024 * 1024).toLong()

        val info = ReleaseParser.parse(title, size, "movie")

        assertTrue(info.isCollection)
        assertEquals(7, info.collectionCount)
        assertEquals(Resolution.FHD_1080P, info.resolution)
        assertEquals(VideoCodec.HEVC, info.codec)
        assertEquals(RipType.BDRIP, info.ripType)
        // 83.3 GB / 7 movies = ~11.9 GB per movie -> ~16.2 Mbps (NOT 113.6 Mbps!)
        assertTrue(info.estimatedBitrateMbps in 14f..18f)
        assertTrue(info.badges.contains("Коллекция (7)"))
    }

    @Test
    fun parseTrilogyCollection() {
        val title = "Властелин колец: Трилогия / The Lord of the Rings: Trilogy (2001-2003) BDRip 1080p"
        val size = 36L * 1024 * 1024 * 1024

        val info = ReleaseParser.parse(title, size, "movie")

        assertTrue(info.isCollection)
        assertEquals(3, info.collectionCount)
        assertEquals(Resolution.FHD_1080P, info.resolution)
        // 36 GB / 3 movies = 12 GB per movie -> ~16.3 Mbps
        assertTrue(info.estimatedBitrateMbps in 14f..18f)
    }

    @Test
    fun testNonVideoDetection() {
        // User's exact screenshot game
        val spiderManGame = "Marvel's Spider-Man 2: Digital Deluxe Edition [RUS text / RUS sound / EUR / v01.005.001 / Лицензия / FPKG]"
        assertTrue(ReleaseParser.isNonVideo(spiderManGame), "Must identify PS5 FPKG game as non-video")

        // PC game repacks
        val pcRepack = "Cyberpunk 2077 [v 2.13] (2020) PC | RePack от FitGirl"
        assertTrue(ReleaseParser.isNonVideo(pcRepack), "Must identify PC FitGirl repack as non-video")

        val gogGame = "The Witcher 3: Wild Hunt - Complete Edition (2015) PC | GOG"
        assertTrue(ReleaseParser.isNonVideo(gogGame), "Must identify GOG release as non-video")

        // Non-video forum categories
        assertTrue(ReleaseParser.isNonVideo("Spider-Man 2", "PS4"))
        assertTrue(ReleaseParser.isNonVideo("Any title", "Игры для ПК"))
        assertTrue(ReleaseParser.isNonVideo("Any title", "Программы и Дизайн"))
        assertTrue(ReleaseParser.isNonVideo("Any title", "Аудиокниги"))
        assertTrue(ReleaseParser.isNonVideo("Any title", "Музыка (lossless)"))

        // Real video releases must NOT be rejected
        val spiderManMovie = "Человек-паук 2 / Spider-Man 2 (2004) BDRip 1080p | DUB"
        assertFalse(ReleaseParser.isNonVideo(spiderManMovie, "Зарубежное кино (HD Video)"))

        val spiderMan4k = "Spider-Man: No Way Home (2021) 2160p UHD Blu-Ray Remux | HDR | Dolby Vision"
        assertFalse(ReleaseParser.isNonVideo(spiderMan4k, "Кино (4K UHD)"))

        val gameOfThrones = "Игра престолов / Game of Thrones [S01-S08] (2011-2019) BDRip 1080p"
        assertFalse(ReleaseParser.isNonVideo(gameOfThrones, "Зарубежные сериалы"))

        val spiderManAnimated = "Человек-паук / Spider-Man: The Animated Series (1994) [01-05x01-65] WEBRip 720p"
        assertFalse(ReleaseParser.isNonVideo(spiderManAnimated, "Мультсериалы"))
    }

    @Test
    fun testOneGameWordDoesNotHideAFilm() {
        // Метка REPACK бывает у обычных релизов фильмов, «Ultimate Edition» — у режиссёрских версий
        assertFalse(ReleaseParser.isNonVideo("Dune.Part.Two.2024.REPACK.1080p.WEB-DL.x264"), "REPACK сам по себе — не игра")
        assertFalse(ReleaseParser.isNonVideo("Бэтмен против Супермена: На заре справедливости [Ultimate Edition] (2016) BDRip 1080p"))
        assertFalse(ReleaseParser.isNonVideo("Хоббит: Нежданное путешествие (2012) Gold Edition BDRemux 1080p"))

        // Две игровые метки рядом — это уже игра
        assertTrue(ReleaseParser.isNonVideo("Elden Ring (2022) PC | RePack"), "PC + RePack — игра")
        assertTrue(ReleaseParser.isNonVideo("Cyberpunk 2077 [v 2.13] (2020) PC | RePack от FitGirl"))
    }

    @Test
    fun testTrailerWordInsideATitleIsNotATrailer() {
        // Слово «трейлер» внутри названия: «Парни из Трейлерного парка» / «Trailer Park Boys»
        assertFalse(ReleaseParser.isTrailerOrTeaser("Парни из Трейлерного парка / Trailer Park Boys (2001) WEBRip 720p"))
        assertFalse(ReleaseParser.isNonVideo("Парни из Трейлерного парка / Trailer Park Boys (2001) WEBRip 720p"))
        assertFalse(ReleaseParser.isTrailerOrTeaser("Трейлерный парк (2019) WEB-DL 1080p"))
    }

    @Test
    fun testTrailerAndTeaserRejection() {
        val teaser1 = "Человек-паук: Новый день [2026] тизер-трейлер"
        assertTrue(ReleaseParser.isTrailerOrTeaser(teaser1), "Must flag тизер-трейлер")
        assertTrue(ReleaseParser.isNonVideo(teaser1), "Must identify teaser as non-video")

        val teaser2 = "Мстители: Секретные войны (2026) Тизер [1080p]"
        assertTrue(ReleaseParser.isTrailerOrTeaser(teaser2), "Must flag Тизер")
        assertTrue(ReleaseParser.isNonVideo(teaser2), "Must identify teaser as non-video")

        val trailer1 = "GTA 6 Official Trailer 1080p"
        assertTrue(ReleaseParser.isTrailerOrTeaser(trailer1), "Must flag Trailer")
        assertTrue(ReleaseParser.isNonVideo(trailer1), "Must identify trailer as non-video")

        val trailer2 = "Бэтмен 2 (2026) Русский трейлер"
        assertTrue(ReleaseParser.isTrailerOrTeaser(trailer2), "Must flag трейлер")
        assertTrue(ReleaseParser.isNonVideo(trailer2), "Must identify trailer as non-video")

        // Real movie title should not be flagged
        assertFalse(ReleaseParser.isTrailerOrTeaser("Новый Человек-паук / The Amazing Spider-Man (2012) BDRip 1080p"))
    }

    @Test
    fun testCyrillicWordBoundariesOnJdk21() {
        // 1. Cyrillic word boundaries for series detection (\bсезон\b)
        val s1 = ReleaseParser.parse("Дом Дракона (Сезон 1) (2022) 1080p", 10L * 1024 * 1024 * 1024, "movie")
        assertEquals(Resolution.FHD_1080P, s1.resolution)
        // Even when tracker category defaults to 'movie', 'Сезон' must trigger series logic on JDK 21!
        assertTrue(s1.estimatedBitrateMbps in 3f..4f, "Bitrate must be calculated as series, not 105-min film")

        // 2. Cyrillic episode range and "из"
        val s2 = ReleaseParser.parse("Слово пацана (серии 1-8) (2023) WEBRip 720p", 4L * 1024 * 1024 * 1024, "series")
        assertEquals(8, s2.episodesCount)

        val s3 = ReleaseParser.parse("Чернобыль (1 из 5) (2019) BDRip 1080p", 3L * 1024 * 1024 * 1024, "series")
        assertEquals(5, s3.episodesCount)

        // 3. Cyrillic voiceovers with \b
        val vDub = ReleaseParser.parse("Начало / Inception (2010) BDRip 1080p | Дубляж")
        assertEquals(VoiceType.DUB, vDub.voice)

        val vMvo = ReleaseParser.parse("Матрица / The Matrix (1999) BDRip 1080p | многоголосый")
        assertEquals(VoiceType.MVO, vMvo.voice)

        val vDvo = ReleaseParser.parse("Бойцовский клуб / Fight Club (1999) BDRip 1080p | двухголосый")
        assertEquals(VoiceType.DVO, vDvo.voice)

        // 4. Cyrillic franchise words (\bтрилоги[яи]\b, \bдилоги[яи]\b, \bквадрологи[яи]\b)
        val tr = ReleaseParser.parse("Хоббит: Трилогия (2012-2014) BDRemux 1080p")
        assertTrue(tr.isCollection)
        assertEquals(3, tr.collectionCount)

        val du = ReleaseParser.parse("Дюна: Дилогия (2021-2024) BDRip 1080p")
        assertTrue(du.isCollection)
        assertEquals(2, du.collectionCount)

        val qd = ReleaseParser.parse("Джон Уик: Квадрология (2014-2023) BDRip 1080p")
        assertTrue(qd.isCollection)
        assertEquals(4, qd.collectionCount)
    }

    @Test
    fun testVideoCategoryWhitelistWithKeywords() {
        // "Музыкальное видео" contains "музык", but is a legitimate video category
        assertFalse(ReleaseParser.isNonVideo("Linkin Park - Live in Texas (2003) 1080p", "Музыкальное видео"))
        assertFalse(ReleaseParser.isNonVideo("Queen - Rock Montreal (1981) BDRip", "Видеоклипы и Концерты"))

        // "Экранизации книг" contains "книг", but is a legitimate video category
        assertFalse(ReleaseParser.isNonVideo("Война и мир (1965) BDRip", "Экранизации зарубежной классики"))
        assertFalse(ReleaseParser.isNonVideo("Преступление и наказание (2007) 1080p", "Экранизации книг"))

        // Non-video music and books should be filtered
        assertTrue(ReleaseParser.isNonVideo("Linkin Park - Meteora (2003) FLAC", "Музыка (lossless)"))
        assertTrue(ReleaseParser.isNonVideo("Стивен Кинг - Сияние FB2, EPUB", "Книги и журналы"))
    }
}

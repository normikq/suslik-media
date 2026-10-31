//! Перенос `ReleaseParserTest.kt`.
use super::*;

const GB: i64 = 1024 * 1024 * 1024;

fn has_badge(i: &ReleaseInfo, b: &str) -> bool {
    i.badges().iter().any(|x| x == b)
}

fn movie(t: &str) -> ReleaseInfo {
    parse(t, 0, "movie")
}

#[test]
fn parse_4k_dolby_vision_remux() {
    let i = parse(
        "Дюна: Часть вторая / Dune: Part Two (2024) BDRemux 2160p | HDR, Dolby Vision | DDP 5.1, TrueHD 7.1.Atmos | D",
        58 * GB,
        "movie",
    );
    assert_eq!(i.clean_title, "Дюна: Часть вторая / Dune: Part Two (2024)");
    assert_eq!(i.year, Some(2024));
    assert_eq!(i.resolution, Resolution::Uhd4k);
    assert_eq!(i.rip_type, RipType::Remux);
    assert_eq!(i.hdr, HdrType::Dv);
    assert_eq!(i.voice, VoiceType::Dub);
    assert!(!i.is_cam);
    assert_eq!(i.bitrate_tier, BitrateTier::Heavy);
    assert!(i.estimated_bitrate_mbps > 50.0);
    assert!(!i.is_mobile_friendly());
    for b in ["4K", "BDRemux", "Dolby Vision", "Дубляж"] {
        assert!(has_badge(&i, b), "{b}");
    }
}

#[test]
fn parse_1080p_bdrip_dubbed() {
    let i = parse("Интерстеллар / Interstellar (2014) BDRip 1080p | D, P, A | Лицензия", 8 * GB, "movie");
    assert_eq!(i.clean_title, "Интерстеллар / Interstellar (2014)");
    assert_eq!(i.year, Some(2014));
    assert_eq!(i.resolution, Resolution::Fhd1080p);
    assert_eq!(i.rip_type, RipType::Bdrip);
    assert_eq!(i.voice, VoiceType::Dub);
    assert!(!i.is_cam);
    assert_eq!(i.bitrate_tier, BitrateTier::Medium);
    assert!((9.0..=12.0).contains(&i.estimated_bitrate_mbps));
}

#[test]
fn parse_mobile_friendly_720p_web_dl() {
    let i = parse("Оппенгеймер / Oppenheimer (2023) WEB-DLRip 720p | D", 2 * GB + 200 * 1024 * 1024, "movie");
    assert_eq!(i.clean_title, "Оппенгеймер / Oppenheimer (2023)");
    assert_eq!(i.year, Some(2023));
    assert_eq!(i.resolution, Resolution::Hd720p);
    assert_eq!(i.rip_type, RipType::Webdl);
    assert_eq!(i.voice, VoiceType::Dub);
    assert_eq!(i.bitrate_tier, BitrateTier::Light);
    assert!((2.5..=3.2).contains(&i.estimated_bitrate_mbps));
    assert!(i.is_mobile_friendly());
    assert!(has_badge(&i, "720p") && has_badge(&i, "WEB-DL"));
}

#[test]
fn parse_hevc_bdrip() {
    let i = parse("Мальчик и птица / The Boy and the Heron (2023) BDRip-HEVC 1080p | MVO", 3 * GB, "movie");
    assert_eq!(i.resolution, Resolution::Fhd1080p);
    assert_eq!(i.codec, VideoCodec::Hevc);
    assert_eq!(i.rip_type, RipType::Bdrip);
    assert_eq!(i.voice, VoiceType::Mvo);
    assert!(i.is_mobile_friendly());
    assert!(has_badge(&i, "HEVC"));
}

#[test]
fn parse_cam_rip_and_telesync() {
    for (t, size) in [
        ("Гладиатор 2 / Gladiator II (2024) CAMRip | Звук с TS", 1_500_000_000),
        ("Форсаж 10 / Fast X (2023) Telesync [TS]", 1_800_000_000),
    ] {
        let i = parse(t, size, "movie");
        assert!(i.is_cam, "{t}");
        assert_eq!(i.rip_type, RipType::Cam);
        assert!(!i.is_mobile_friendly());
    }
}

#[test]
fn parse_series_with_episode_count() {
    let i1 = parse(
        "Одни из нас / The Last of Us (Сезон 1, серии 1-9 из 9) (2023) WEB-DL 1080p | Red Head Sound",
        18 * GB,
        "series",
    );
    assert_eq!(i1.episodes_count, Some(9));
    assert_eq!(i1.resolution, Resolution::Fhd1080p);
    assert_eq!(i1.rip_type, RipType::Webdl);
    assert!((5.5..=6.5).contains(&i1.estimated_bitrate_mbps));

    let i2 = parse("Во все тяжкие / Breaking Bad [01-05x01-62] (2008-2013) BDRip 720p | LostFilm", 50 * GB, "series");
    assert_eq!(i2.episodes_count, Some(62));
    assert_eq!(i2.resolution, Resolution::Hd720p);
    assert!((2.0..=2.8).contains(&i2.estimated_bitrate_mbps));
    assert_eq!(i2.bitrate_tier, BitrateTier::Light);
    assert!(i2.is_mobile_friendly());

    let i3 = parse(
        "Игра престолов / Game of Thrones [01-08x01-73 из 73] (2011-2019) BDRip 1080p | LostFilm",
        120 * GB,
        "series",
    );
    assert_eq!(i3.episodes_count, Some(73));
    assert_eq!(i3.resolution, Resolution::Fhd1080p);
}

#[test]
fn parse_english_dotted_title() {
    let i = parse("Dune.Part.Two.2024.2160p.UHD.BluRay.x265.TrueHD.7.1.Atmos.DV.HDR10-SWTYBLZ", 40 * GB, "movie");
    assert_eq!(i.clean_title, "Dune Part Two 2024");
    assert_eq!(i.year, Some(2024));
    assert_eq!(i.resolution, Resolution::Uhd4k);
    assert_eq!(i.codec, VideoCodec::Hevc);
    assert_eq!(i.rip_type, RipType::Bluray);
    assert_eq!(i.hdr, HdrType::Dv);
    assert!(!i.is_cam);
}

#[test]
fn parse_resident_evil_collection() {
    let i = parse(
        "Обитель зла / Resident Evil (2002-2023) BDRip [H.265/1080p] [10-bit] [Коллекция]",
        (83.3 * GB as f64) as i64,
        "movie",
    );
    assert!(i.is_collection);
    assert_eq!(i.collection_count, Some(7));
    assert_eq!(i.resolution, Resolution::Fhd1080p);
    assert_eq!(i.codec, VideoCodec::Hevc);
    assert_eq!(i.rip_type, RipType::Bdrip);
    assert!((14.0..=18.0).contains(&i.estimated_bitrate_mbps));
    assert!(has_badge(&i, "Коллекция (7)"));
}

#[test]
fn parse_trilogy_collection() {
    let i =
        parse("Властелин колец: Трилогия / The Lord of the Rings: Trilogy (2001-2003) BDRip 1080p", 36 * GB, "movie");
    assert!(i.is_collection);
    assert_eq!(i.collection_count, Some(3));
    assert_eq!(i.resolution, Resolution::Fhd1080p);
    assert!((14.0..=18.0).contains(&i.estimated_bitrate_mbps));
}

#[test]
fn non_video_detection() {
    assert!(is_non_video(
        "Marvel's Spider-Man 2: Digital Deluxe Edition [RUS text / RUS sound / EUR / v01.005.001 / Лицензия / FPKG]",
        ""
    ));
    assert!(is_non_video("Cyberpunk 2077 [v 2.13] (2020) PC | RePack от FitGirl", ""));
    assert!(is_non_video("The Witcher 3: Wild Hunt - Complete Edition (2015) PC | GOG", ""));
    assert!(is_non_video("Spider-Man 2", "PS4"));
    for cat in ["Игры для ПК", "Программы и Дизайн", "Аудиокниги", "Музыка (lossless)"]
    {
        assert!(is_non_video("Any title", cat), "{cat}");
    }
    assert!(!is_non_video("Человек-паук 2 / Spider-Man 2 (2004) BDRip 1080p | DUB", "Зарубежное кино (HD Video)"));
    assert!(!is_non_video(
        "Spider-Man: No Way Home (2021) 2160p UHD Blu-Ray Remux | HDR | Dolby Vision",
        "Кино (4K UHD)"
    ));
    assert!(!is_non_video("Игра престолов / Game of Thrones [S01-S08] (2011-2019) BDRip 1080p", "Зарубежные сериалы"));
    assert!(!is_non_video(
        "Человек-паук / Spider-Man: The Animated Series (1994) [01-05x01-65] WEBRip 720p",
        "Мультсериалы"
    ));
}

#[test]
fn one_game_word_does_not_hide_a_film() {
    assert!(!is_non_video("Dune.Part.Two.2024.REPACK.1080p.WEB-DL.x264", ""));
    assert!(!is_non_video("Бэтмен против Супермена: На заре справедливости [Ultimate Edition] (2016) BDRip 1080p", ""));
    assert!(!is_non_video("Хоббит: Нежданное путешествие (2012) Gold Edition BDRemux 1080p", ""));
    assert!(is_non_video("Elden Ring (2022) PC | RePack", ""));
    assert!(is_non_video("Cyberpunk 2077 [v 2.13] (2020) PC | RePack от FitGirl", ""));
}

#[test]
fn trailer_word_inside_a_title_is_not_a_trailer() {
    let t = "Парни из Трейлерного парка / Trailer Park Boys (2001) WEBRip 720p";
    assert!(!is_trailer_or_teaser(t));
    assert!(!is_non_video(t, ""));
    assert!(!is_trailer_or_teaser("Трейлерный парк (2019) WEB-DL 1080p"));
}

#[test]
fn trailer_and_teaser_rejection() {
    for t in [
        "Человек-паук: Новый день [2026] тизер-трейлер",
        "Мстители: Секретные войны (2026) Тизер [1080p]",
        "GTA 6 Official Trailer 1080p",
        "Бэтмен 2 (2026) Русский трейлер",
    ] {
        assert!(is_trailer_or_teaser(t), "{t}");
        assert!(is_non_video(t, ""), "{t}");
    }
    assert!(!is_trailer_or_teaser("Новый Человек-паук / The Amazing Spider-Man (2012) BDRip 1080p"));
}

#[test]
fn cyrillic_word_boundaries() {
    let s1 = parse("Дом Дракона (Сезон 1) (2022) 1080p", 10 * GB, "movie");
    assert_eq!(s1.resolution, Resolution::Fhd1080p);
    assert!((3.0..=4.0).contains(&s1.estimated_bitrate_mbps), "{}", s1.estimated_bitrate_mbps);
    assert_eq!(parse("Слово пацана (серии 1-8) (2023) WEBRip 720p", 4 * GB, "series").episodes_count, Some(8));
    assert_eq!(parse("Чернобыль (1 из 5) (2019) BDRip 1080p", 3 * GB, "series").episodes_count, Some(5));
    assert_eq!(movie("Начало / Inception (2010) BDRip 1080p | Дубляж").voice, VoiceType::Dub);
    assert_eq!(movie("Матрица / The Matrix (1999) BDRip 1080p | многоголосый").voice, VoiceType::Mvo);
    assert_eq!(movie("Бойцовский клуб / Fight Club (1999) BDRip 1080p | двухголосый").voice, VoiceType::Dvo);
    for (t, n) in [
        ("Хоббит: Трилогия (2012-2014) BDRemux 1080p", 3),
        ("Дюна: Дилогия (2021-2024) BDRip 1080p", 2),
        ("Джон Уик: Квадрология (2014-2023) BDRip 1080p", 4),
    ] {
        let i = movie(t);
        assert!(i.is_collection, "{t}");
        assert_eq!(i.collection_count, Some(n), "{t}");
    }
}

#[test]
fn video_category_whitelist_with_keywords() {
    assert!(!is_non_video("Linkin Park - Live in Texas (2003) 1080p", "Музыкальное видео"));
    assert!(!is_non_video("Queen - Rock Montreal (1981) BDRip", "Видеоклипы и Концерты"));
    assert!(!is_non_video("Война и мир (1965) BDRip", "Экранизации зарубежной классики"));
    assert!(!is_non_video("Преступление и наказание (2007) 1080p", "Экранизации книг"));
    assert!(is_non_video("Linkin Park - Meteora (2003) FLAC", "Музыка (lossless)"));
    assert!(is_non_video("Стивен Кинг - Сияние FB2, EPUB", "Книги и журналы"));
    assert!(is_non_video("Some Game (2020) PC", "Игры для PC: фильм-квесты"));
    assert!(!is_non_video("Silent Hill (2006) 1080p", "Фильмы по играм"));
}

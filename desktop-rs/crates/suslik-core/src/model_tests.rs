//! Перенос `ModelTest.kt`.
use super::*;
use serde_json::{json, Value};

fn j(s: &str) -> Value {
    serde_json::from_str(s).unwrap()
}

pub(super) fn mag(
    id: i64,
    name: &str,
    status: &str,
    code: i32,
    size: i64,
    downloaded: i64,
    files: Vec<MFile>,
) -> Magnet {
    Magnet { status: status.into(), size, downloaded, ..Magnet::new(id, name, code, files) }
}

pub(super) fn file(idx: i32, path: &str, size_mb: i64) -> MFile {
    MFile {
        index: idx,
        name: after_last(path, '/').into(),
        path: path.into(),
        is_folder: false,
        size: size_mb * 1024 * 1024,
        link: Some(format!("https://host/f/{idx}")),
    }
}

fn mf(idx: i32, name: &str, size: i64) -> MFile {
    MFile { index: idx, name: name.into(), path: name.into(), is_folder: false, size, link: Some("http://link".into()) }
}

fn series(p: &Playable) -> &Vec<Season> {
    p.seasons().expect("series")
}

fn movie(p: &Playable) -> &MFile {
    match p {
        Playable::Movie(f) => f,
        other => panic!("not a movie: {other:?}"),
    }
}

#[test]
fn parse_library_empty_or_invalid_json() {
    assert!(parse_library(&json!({})).is_empty());
    assert!(parse_library(&json!({"magnets": []})).is_empty());
    assert!(parse_library(&json!({"magnets": "not-an-array"})).is_empty());
}

#[test]
fn parse_library_valid_magnets_and_files() {
    let v = json!({"magnets": [
        {"id": 101, "name": "Interstellar.2014.1080p", "status": "Downloading", "statusCode": 2, "size": 10000000,
         "downloaded": 5000000, "canRemove": false, "favorite": true, "mine": true, "files": [
            {"name": "Interstellar.mkv", "path": "Interstellar/Interstellar.mkv", "type": "file", "size": 9500000, "sourceLink": "https://alldebrid.com/f/xyz"},
            {"name": "Subs", "path": "Interstellar/Subs", "type": "folder", "size": 0, "sourceLink": ""},
            {"name": "insecure.srt", "path": "Interstellar/Subs/insecure.srt", "type": "file", "size": 5000, "sourceLink": "http://insecure.test/file.srt"}
        ]},
        {"id": 102, "name": "", "status": "", "statusCode": 4}
    ]});
    let list = parse_library(&v);
    assert_eq!(list.len(), 2);
    let m1 = &list[0];
    assert_eq!(
        (m1.id, m1.name.as_str(), m1.status.as_str(), m1.status_code),
        (101, "Interstellar.2014.1080p", "Downloading", 2)
    );
    assert_eq!((m1.size, m1.downloaded), (10000000, 5000000));
    assert!(!m1.can_remove && m1.favorite && m1.mine);
    assert!(!m1.ready() && m1.downloading() && !m1.failed());
    assert!((m1.progress() - 0.5).abs() < 0.0001);
    assert_eq!(m1.files.len(), 3);
    assert_eq!(m1.files[0].index, 0);
    assert_eq!(m1.files[0].name, "Interstellar.mkv");
    assert_eq!(m1.files[0].path, "Interstellar/Interstellar.mkv");
    assert!(!m1.files[0].is_folder);
    assert_eq!(m1.files[0].link.as_deref(), Some("https://alldebrid.com/f/xyz"));
    assert!(m1.files[1].is_folder);
    assert_eq!(m1.files[1].link, None);
    assert_eq!(m1.files[2].link, None); // только https://
    let m2 = &list[1];
    assert_eq!(m2.name, "Magnet 102");
    assert!(m2.ready() && !m2.downloading());
    assert_eq!(m2.progress(), 0.0);
    assert!(m2.can_remove && !m2.favorite && !m2.mine);
}

#[test]
fn magnet_states_and_progress() {
    let r = mag(1, "Ready", "Ready", 4, 100, 100, vec![]);
    assert!(r.ready() && !r.downloading() && !r.failed());
    assert_eq!(r.progress(), 1.0);
    let f = mag(2, "Failed", "Error", 5, 100, 10, vec![]);
    assert!(!f.ready() && !f.downloading() && f.failed());
    assert_eq!(mag(3, "Zero", "Init", 1, 0, 0, vec![]).progress(), 0.0);
    assert_eq!(mag(4, "Over", "Downloading", 2, 100, 150, vec![]).progress(), 1.0);
}

#[test]
fn clean_title_strips_release_tags_and_years() {
    assert_eq!(clean_title("Inception.2010.1080p.BluRay.x264-SPARKS.mkv"), "Inception");
    assert_eq!(clean_title("Breaking.Bad.S01.1080p.NF.WEB-DL.DDP5.1.H.264-NTb"), "Breaking Bad S01");
    assert_eq!(clean_title("Dune.Part.Two.2024.2160p.UHD.Remux.HEVC.DV.HDR10+.mkv"), "Dune Part Two");
    assert_eq!(clean_title("The Matrix (1999) [BDRip 720p]"), "The Matrix");
    assert_eq!(clean_title("Movie_Title_2021_Proper_Repack"), "Movie Title");
    assert_eq!(clean_title("Severance.S01E01.480p.WEBRip.x264.mp4"), "Severance S01E01");
}

#[test]
fn clean_title_edge_cases() {
    assert_eq!(clean_title(""), "Untitled");
    assert_eq!(clean_title("   "), "Untitled");
    assert!(!clean_title("1080p").is_empty());
}

#[test]
fn format_bytes_formatting() {
    assert_eq!(format_bytes(0), "—");
    assert_eq!(format_bytes(-100), "—");
    assert_eq!(format_bytes(512), "512 B");
    assert_eq!(format_bytes(1024), "1.0 KB");
    assert_eq!(format_bytes(1536), "1.5 KB");
    assert_eq!(format_bytes(10 * 1024 * 1024), "10.0 MB");
    assert_eq!(format_bytes((4.2 * 1024.0 * 1024.0 * 1024.0) as i64), "4.2 GB");
    assert_eq!(format_bytes((1.5 * 1024.0 * 1024.0 * 1024.0 * 1024.0) as i64), "1.5 TB");
}

#[test]
fn status_ru_mapping() {
    let m = |code, status: &str| mag(1, "Test", status, code, 0, 0, vec![]);
    assert_eq!(status_ru(&m(4, "Uploading")), "Готово");
    assert_eq!(status_ru(&m(2, "Downloading")), "Загрузка");
    assert_eq!(status_ru(&m(2, "downloading")), "Загрузка");
    assert_eq!(status_ru(&m(1, "")), "Обработка");
    assert_eq!(status_ru(&m(1, "   ")), "Обработка");
    assert_eq!(status_ru(&m(5, "")), "Ошибка");
    assert_eq!(status_ru(&m(5, "Bad Torrent")), "Bad Torrent");
    assert_eq!(status_ru(&m(1, "Queued")), "Queued");
}

#[test]
fn main_video_selects_largest_and_ignores_iso() {
    let m = mag(
        1,
        "Film",
        "Готово",
        4,
        0,
        0,
        vec![file(0, "Film.sample.mkv", 50), file(1, "Film.iso", 8000), file(2, "Film.mkv", 4000)],
    );
    assert_eq!(main_video(&m).unwrap().name, "Film.mkv");
    let iso = mag(2, "Iso", "Готово", 4, 0, 0, vec![file(0, "Disc.iso", 4000)]);
    assert_eq!(main_video(&iso).unwrap().name, "Disc.iso");
    let none = mag(3, "Doc", "Готово", 4, 0, 0, vec![file(0, "readme.txt", 1)]);
    assert_eq!(main_video(&none), None);
}

#[test]
fn build_playable_single_movie() {
    let m = mag(1, "Avatar", "Готово", 4, 0, 0, vec![file(0, "Avatar.sample.mkv", 30), file(1, "Avatar.mkv", 8000)]);
    assert_eq!(movie(&build_playable(&m)).name, "Avatar.mkv");
}

#[test]
fn build_playable_series_by_directory_structure() {
    let m = mag(
        1,
        "Breaking Bad",
        "Готово",
        4,
        0,
        0,
        vec![
            file(0, "Breaking Bad/Season 1/ep01.mkv", 1000),
            file(1, "Breaking Bad/Season 1/ep02.mkv", 1000),
            file(2, "Breaking Bad/Season 1/ep10.mkv", 1000),
            file(3, "Breaking Bad/Season 2/ep01.mkv", 1000),
        ],
    );
    let p = build_playable(&m);
    let s = series(&p);
    assert_eq!(s.len(), 2);
    assert_eq!(s[0].season, 1);
    let names: Vec<(&str, i32)> = s[0].items.iter().map(|e| (e.file.name.as_str(), e.episode)).collect();
    assert_eq!(names, [("ep01.mkv", 1), ("ep02.mkv", 2), ("ep10.mkv", 3)]);
    assert_eq!(s[1].season, 2);
    assert_eq!(s[1].items.len(), 1);
    assert_eq!(s[1].items[0].file.name, "ep01.mkv");
}

#[test]
fn build_playable_series_by_russian_season_directory() {
    let m = mag(
        1,
        "Чернобыль",
        "Готово",
        4,
        0,
        0,
        vec![file(0, "Чернобыль/Сезон 1/01.mkv", 1000), file(1, "Чернобыль/Сезон 1/02.mkv", 1000)],
    );
    let p = build_playable(&m);
    let s = series(&p);
    assert_eq!(s.len(), 1);
    assert_eq!(s[0].season, 1);
    assert_eq!(s[0].items.len(), 2);
}

#[test]
fn build_playable_series_by_filename_pattern() {
    let m = mag(
        1,
        "Sherlock",
        "Готово",
        4,
        0,
        0,
        vec![
            file(0, "Sherlock.S01E01.mkv", 1500),
            file(1, "Sherlock.S01E02.mkv", 1500),
            file(2, "Sherlock.1x03.mkv", 1500),
        ],
    );
    let p = build_playable(&m);
    let s = series(&p);
    assert_eq!(s.len(), 1);
    assert_eq!(s[0].items.iter().map(|e| e.episode).collect::<Vec<_>>(), [1, 2, 3]);
}

#[test]
fn build_playable_ignores_sample_files() {
    let m = mag(
        1,
        "Movie with samples",
        "Готово",
        4,
        0,
        0,
        vec![file(0, "sample/sample.mkv", 50), file(1, "movie.sample.avi", 50), file(2, "movie.mkv", 2000)],
    );
    assert_eq!(movie(&build_playable(&m)).name, "movie.mkv");
}

#[test]
fn build_playable_film_collection() {
    let m = mag(
        1,
        "Lord of the Rings Trilogy",
        "Готово",
        4,
        0,
        0,
        vec![
            file(0, "1.The.Fellowship.of.the.Ring.2001.1080p.mkv", 8000),
            file(1, "2.The.Two.Towers.2002.1080p.mkv", 8200),
            file(2, "3.The.Return.of.the.King.2003.1080p.mkv", 9000),
            file(3, "Extras/featurette.mkv", 500),
        ],
    );
    let p = build_playable(&m);
    let s = series(&p);
    assert_eq!(s.len(), 1);
    let titles: Vec<&str> = s[0].items.iter().map(|e| e.title.as_deref().unwrap()).collect();
    assert_eq!(titles, ["1 The Fellowship of the Ring", "2 The Two Towers", "3 The Return of the King"]);
}

#[test]
fn build_playable_bluray_directories_not_treated_as_collection() {
    let m = mag(
        1,
        "Blu-ray Disc",
        "Готово",
        4,
        0,
        0,
        vec![file(0, "BDMV/STREAM/00001.m2ts", 20000), file(1, "BDMV/STREAM/00002.m2ts", 15000)],
    );
    assert_eq!(movie(&build_playable(&m)).name, "00001.m2ts");
}

#[test]
fn build_playable_no_videos_returns_none() {
    let m = mag(1, "Music Album", "Готово", 4, 0, 0, vec![file(0, "song1.flac", 30), file(1, "song2.flac", 30)]);
    assert_eq!(build_playable(&m), Playable::None);
}

#[test]
fn episode_label_formatting() {
    let f = file(0, "ep.mkv", 1000);
    let s1 = Season { season: 1, items: vec![Episode::new(1, 2, &f)] };
    assert_eq!(episode_label("Breaking Bad", &s1, &s1.items[0]), "Breaking Bad · S01E02");
    let s0 = Season { season: 0, items: vec![Episode::new(0, 1, &f)] };
    assert_eq!(episode_label("Doctor Who", &s0, &s0.items[0]), "Doctor Who · Спец.E01");
    let sc = Season { season: 1, items: vec![Episode { title: Some("Alien 1".into()), ..Episode::new(1, 1, &f) }] };
    assert_eq!(episode_label("Alien Collection", &sc, &sc.items[0]), "Alien 1");
}

#[test]
fn subtitles_matched_by_name_and_folder() {
    let vid1 = file(0, "Series/S01/Episode.01.1080p.mkv", 1000);
    let vid2 = file(1, "Series/S01/Episode.02.1080p.mkv", 1000);
    let sub1_rus = file(2, "Series/S01/Episode.01.1080p.rus.srt", 1000);
    let sub1_eng = file(3, "Series/S01/Episode.01.1080p.eng.ass", 1000);
    let sub2_rus = file(4, "Series/S01/Subs/Episode.02.1080p.srt", 1000);
    let orphan = file(5, "Series/S01/other.srt", 1000);
    let m = mag(
        1,
        "Series",
        "Готово",
        4,
        0,
        0,
        vec![vid1.clone(), vid2.clone(), sub1_rus.clone(), sub1_eng.clone(), sub2_rus.clone(), orphan],
    );
    assert_eq!(subtitles_for(&m, &vid1), [sub1_rus, sub1_eng]);
    assert_eq!(subtitles_for(&m, &vid2), [sub2_rus]);
}

#[test]
fn subtitles_for_single_video_accepts_any() {
    let vid = file(0, "Movie/Movie.mkv", 1000);
    let m = mag(
        1,
        "Movie",
        "Готово",
        4,
        0,
        0,
        vec![vid.clone(), file(1, "Movie/Subs/rus.srt", 1), file(2, "Movie/eng.vtt", 1)],
    );
    assert_eq!(subtitles_for(&m, &vid).len(), 2);
}

#[test]
fn external_audio_for_matched_by_name() {
    let vid1 = file(0, "Anime/Ep01.mkv", 1000);
    let vid2 = file(1, "Anime/Ep02.mkv", 1000);
    let aud1 = file(2, "Anime/Audio/Ep01.rus.mka", 100);
    let aud2 = file(3, "Anime/Audio/Ep02.rus.mka", 100);
    let m = mag(1, "Anime", "Готово", 4, 0, 0, vec![vid1.clone(), vid2.clone(), aud1.clone(), aud2.clone()]);
    assert_eq!(external_audio_for(&m, &vid1), [aud1]);
    assert_eq!(external_audio_for(&m, &vid2), [aud2]);
}

#[test]
fn episode_one_does_not_take_episode_tens_files() {
    let e1 = file(0, "Show/Show.S01E1.mkv", 1000);
    let e10 = file(1, "Show/Show.S01E10.mkv", 1000);
    let sub1 = file(2, "Show/Show.S01E1.rus.srt", 1);
    let sub10 = file(3, "Show/Show.S01E10.rus.srt", 1);
    let aud1 = file(4, "Show/Show.S01E1_rus.mka", 1);
    let aud10 = file(5, "Show/Show.S01E10.mka", 1);
    let m = mag(
        1,
        "Show",
        "Готово",
        4,
        0,
        0,
        vec![e1.clone(), e10.clone(), sub1.clone(), sub10.clone(), aud1.clone(), aud10.clone()],
    );
    assert_eq!(subtitles_for(&m, &e1), [sub1]);
    assert_eq!(subtitles_for(&m, &e10), [sub10]);
    assert_eq!(external_audio_for(&m, &e1), [aud1]);
    assert_eq!(external_audio_for(&m, &e10), [aud10]);
}

#[test]
fn external_audio_single_video_fallback() {
    let vid = file(0, "Film/Film.mkv", 1000);
    let dub = file(1, "Film/Dub.ac3", 100);
    let m = mag(1, "Film", "Готово", 4, 0, 0, vec![vid.clone(), dub.clone()]);
    assert_eq!(external_audio_for(&m, &vid), [dub]);
}

#[test]
fn watch_key_and_info() {
    assert_eq!(watch_key(42, &file(5, "folder/movie.mkv", 1)), "42|folder/movie.mkv");
    let w = |pos, len| WatchInfo { pos, len, at: 0 };
    assert!((w(50_000, 100_000).fraction() - 0.5).abs() < 0.0001);
    assert!(!w(50_000, 100_000).watched());
    assert!(w(85_000, 100_000).watched());
    assert!(w(99_000, 100_000).watched());
    assert_eq!(w(0, 0).fraction(), 0.0);
    assert!(!w(0, 0).watched());
}

#[test]
fn prepare_items_computes_tile_data() {
    let m = mag(10, "Inception.2010.1080p", "Готово", 4, 5000, 5000, vec![file(0, "Inception.mkv", 4000)]);
    let items = prepare_items(vec![m]);
    let item = &items[0];
    assert_eq!(item.base_title, "Inception");
    movie(&item.playable);
    assert!(item.poster_queries.contains(&"Inception".to_string()));
    assert!(item.search_text.contains("inception"));
    assert!(item.search_text.contains("inception.mkv"));
}

fn ai_library(status: &str) -> Value {
    j(&format!(
        r#"{{
      "aiTitleReadyAt": 1700000000000,
      "magnets": [
        {{ "id": 1, "name": "Тачки 2 (2011) BDRip", "statusCode": 4,
          "files": [ {{ "name": "Cars.2.mkv", "path": "Cars.2.mkv", "type": "file", "size": 4000, "sourceLink": "https://host/1" }} ] }},
        {{ "id": 2, "name": "Cars.Two.2011.720p", "statusCode": 4, "canAiTitle": true,
          "aiTitle": {{ "base": " Тачки 2 ", "season": null, "source": "ai", "model": "gemini-3.5-flash-lite", "at": 5, "status": "{status}" }},
          "files": [ {{ "name": "Cars2.mkv", "path": "Cars2.mkv", "type": "file", "size": 2000, "sourceLink": "https://host/2" }} ] }}
      ]
    }}"#
    ))
}

fn ai(base: &str, season: Option<i32>, model: &str, at: i64, status: &str, kind: Option<&str>) -> AiTitle {
    AiTitle {
        base: base.into(),
        season,
        model: model.into(),
        at,
        status: status.into(),
        kind: kind.map(String::from),
        ..Default::default()
    }
}

#[test]
fn parse_library_ai_title() {
    let v = ai_library("suggested");
    let list = parse_library(&v);
    assert_eq!(list[0].ai_title, None);
    assert!(!list[0].can_ai_title);
    assert_eq!(list[1].ai_title, Some(ai("Тачки 2", None, "gemini-3.5-flash-lite", 5, "suggested", None)));
    assert!(list[1].can_ai_title);
    assert!(!list[1].ai_title.as_ref().unwrap().accepted());
    assert_eq!(ai_title_ready_at(&v), 1700000000000);
    assert_eq!(ai_title_ready_at(&json!({})), 0);
    let season = parse_library(&j(
        r#"{"magnets":[{"id":3,"name":"S","aiTitle":{"base":"Тед Лассо","season":2,"status":"accepted"}}]}"#,
    ));
    assert_eq!(season[0].ai_title.as_ref().unwrap().season, Some(2));
    assert!(season[0].ai_title.as_ref().unwrap().accepted());
    let bad = parse_library(&j(r#"{"magnets":[{"id":4,"name":"S","aiTitle":{"base":"X","status":"weird"}}]}"#));
    assert_eq!(bad[0].ai_title, None);
}

fn ids(v: &[LibraryItem]) -> Vec<i64> {
    v.iter().map(|i| i.m.id).collect()
}

#[test]
fn group_versions_uses_only_accepted_ai_title() {
    let accepted = prepare_items(parse_library(&ai_library("accepted")));
    assert_eq!(accepted[1].base_title, "Тачки 2");
    let (shown, versions) = group_versions(&accepted, |_| None);
    assert_eq!(ids(&shown), [1]);
    let mut v = ids(&versions[&1]);
    v.sort();
    assert_eq!(v, [1, 2]);
    for status in ["suggested", "rejected", "none"] {
        let items = prepare_items(parse_library(&ai_library(status)));
        assert_eq!(items[1].base_title, clean_title(&items[1].m.name));
        assert_eq!(ids(&group_versions(&items, |_| None).0), [1, 2], "{status}");
    }
}

fn with_status(item: &LibraryItem, status: &str) -> LibraryItem {
    let mut a = item.m.ai_title.clone().unwrap();
    a.status = status.into();
    item.with_ai_title(Some(a))
}

#[test]
fn accepted_ai_title_names_the_tile_and_its_poster() {
    let items = prepare_items(parse_library(&ai_library("accepted")));
    assert_eq!(items[1].base_title, "Тачки 2");
    assert_eq!(items[1].poster_queries[0], "Тачки 2");
    let suggested = prepare_items(parse_library(&ai_library("suggested")))[1].clone();
    let accepted = with_status(&suggested, "accepted");
    assert_eq!(accepted.base_title, "Тачки 2");
    assert_eq!(accepted.poster_queries[0], "Тачки 2");
    let rejected = with_status(&accepted, "rejected");
    assert_eq!(rejected.base_title, clean_title(&suggested.m.name));
    assert_eq!(rejected.playable, suggested.playable);
}

fn ai_file(i: i32, path: &str, mb: i64) -> MFile {
    MFile { link: Some(format!("https://host/{i}")), ..file(i, path, mb) }
}

fn ai_magnet(files: Vec<MFile>, kind: Option<&str>, season: Option<i32>, status: &str) -> Magnet {
    Magnet { ai_title: Some(ai("Show", season, "m", 1, status, kind)), ..mag(1, "Show", "Ready", 4, 0, 0, files) }
}

fn first_title(p: &Playable) -> Option<String> {
    series(p)[0].items[0].title.clone()
}

#[test]
fn accepted_ai_kind_and_season_change_what_plays() {
    let films = vec![
        ai_file(0, "Show/Show A.mkv", 1500),
        ai_file(1, "Show/Show B.mkv", 1500),
        ai_file(2, "Show/Show C.mkv", 1500),
    ];
    assert!(first_title(&build_playable(&ai_magnet(films.clone(), None, None, "none"))).is_some());
    let p = build_playable(&ai_magnet(films.clone(), Some("series"), Some(3), "accepted"));
    let s = series(&p);
    assert_eq!(s.len(), 1);
    assert_eq!(s[0].season, 3);
    assert_eq!(s[0].items.iter().map(|e| e.episode).collect::<Vec<_>>(), [1, 2, 3]);
    assert!(s[0].items.iter().all(|e| e.title.is_none() && e.season == 3));
    assert!(first_title(&build_playable(&ai_magnet(films.clone(), Some("series"), Some(3), "suggested"))).is_some());

    let eps = vec![ai_file(0, "S/Show.S01E01.mkv", 500), ai_file(1, "S/Show.S01E02.mkv", 500)];
    let p = build_playable(&ai_magnet(eps.clone(), None, Some(2), "accepted"));
    assert_eq!(series(&p).len(), 1);
    assert_eq!(series(&p)[0].items.iter().map(|e| e.season).collect::<Vec<_>>(), [2, 2]);
    assert!(first_title(&build_playable(&ai_magnet(eps, Some("collection"), None, "accepted"))).is_some());

    let p = build_playable(&ai_magnet(
        vec![ai_file(0, "Film.mkv", 4000), ai_file(1, "Film part2.mkv", 3900)],
        Some("movie"),
        None,
        "accepted",
    ));
    assert_eq!(movie(&p).path, "Film.mkv");

    let two = vec![ai_file(0, "Season 1/a.mkv", 500), ai_file(1, "Season 2/b.mkv", 500)];
    let p = build_playable(&ai_magnet(two, Some("series"), Some(5), "accepted"));
    assert_eq!(series(&p).iter().map(|s| s.season).collect::<Vec<_>>(), [1, 2]);

    let item = prepare_items(vec![ai_magnet(films, Some("series"), Some(3), "suggested")]).remove(0);
    assert!(first_title(&item.playable).is_some());
    let accepted = with_status(&item, "accepted");
    assert_eq!(series(&accepted.playable).len(), 1);
    assert_eq!(series(&accepted.playable)[0].season, 3);
    assert!(first_title(&accepted.playable).is_none());
}

#[test]
fn parse_ai_title_kind_and_stale() {
    let a =
        parse_ai_title(Some(&j(r#"{"base":"X","season":null,"kind":"collection","status":"suggested","stale":true}"#)))
            .unwrap();
    assert_eq!(a.kind.as_deref(), Some("collection"));
    assert!(a.stale);
    assert_eq!(parse_ai_title(Some(&j(r#"{"base":"X","kind":"trilogy","status":"suggested"}"#))).unwrap().kind, None);
}

#[test]
fn accepted_ai_original_title_with_year_is_the_first_poster_query() {
    let v = j(r#"{"magnets":[{"id":1,"name":"Дюна (2021) BDRip","statusCode":4,
        "files":[{"name":"Dune.2021.mkv","path":"Dune.2021.mkv","type":"file","size":4000,"sourceLink":"https://host/1"}],
        "aiTitle":{"base":"Дюна","season":null,"kind":"movie","original":"Dune","year":2021,"status":"suggested"}}]}"#);
    let item = prepare_items(parse_library(&v)).remove(0);
    let a = item.m.ai_title.as_ref().unwrap();
    assert_eq!(a.original.as_deref(), Some("Dune"));
    assert_eq!(a.year, Some(2021));
    assert!(!item.poster_queries.contains(&"Dune (2021)".to_string()));
    let accepted = with_status(&item, "accepted");
    assert_eq!(accepted.poster_queries[..2], ["Dune (2021)", "Дюна"]);
    let none = parse_ai_title(Some(&j(r#"{"base":"X","original":null,"year":null,"status":"accepted"}"#))).unwrap();
    assert_eq!(none.original, None);
    assert_eq!(none.poster_query(), None);
}

#[test]
fn collection_detection_and_poster_queries() {
    let f1 = mf(1, "Harry Potter and the Sorcerers Stone (2001).mkv", 2_000_000_000);
    let f2 = mf(2, "Harry Potter and the Chamber of Secrets (2002).mkv", 2_000_000_000);
    let f3 = mf(3, "Harry Potter and the Prisoner of Azkaban (2004).mkv", 2_000_000_000);
    let magnet = Magnet {
        can_remove: false,
        ..mag(
            10,
            "Гарри Поттер: Трилогия / Harry Potter: Trilogy (2001-2004) BDRip",
            "",
            4,
            6_000_000_000,
            6_000_000_000,
            vec![f1.clone(), f2, f3],
        )
    };
    let item = prepare_items(vec![magnet.clone()]).remove(0);
    assert!(is_collection(&item.playable, Some(&magnet)));
    assert_eq!(collection_kind(3, &magnet.name), CollectionKind::Trilogy);
    assert_eq!(film_count_ru(3), "3 фильма");
    let q1 = collection_file_poster_queries(&magnet, &f1, Some(&item.base_title));
    assert!(q1.contains(&"Harry Potter and the Sorcerers Stone (2001)".to_string()));
    assert!(q1.contains(&"Harry Potter and the Sorcerers Stone".to_string()));
    assert_eq!(continue_item_poster_queries(&item, &f1), q1);

    let lotr_file = mf(1, "01. Братство кольца (2001).mkv", 2_000_000_000);
    let lotr =
        mag(11, "Властелин колец: Трилогия (2001-2003)", "", 4, 6_000_000_000, 6_000_000_000, vec![lotr_file.clone()]);
    let lq = collection_file_poster_queries(&lotr, &lotr_file, Some("Властелин колец"));
    assert!(lq.contains(&"Братство кольца (2001)".to_string()), "{lq:?}");
    assert!(lq.contains(&"Властелин колец: Братство кольца (2001)".to_string()), "{lq:?}");
    assert_eq!(collection_file_title(&lotr, &lotr_file, Some("Властелин колец")), "Властелин колец: Братство кольца");

    let hp2 = mf(1, "2 Гарри Поттер и Тайная комната.mkv", 2_000_000_000);
    let hp = mag(
        12,
        "Гарри Поттер: Коллекция 8 фильмов / Harry Potter (2001-2011)",
        "",
        4,
        16_000_000_000,
        16_000_000_000,
        vec![hp2.clone()],
    );
    assert_eq!(collection_file_title(&hp, &hp2, Some(&hp.name)), "Гарри Поттер и Тайная комната");
    let hq = collection_file_poster_queries(&hp, &hp2, Some(&hp.name));
    assert!(hq.contains(&"Гарри Поттер и Тайная комната".to_string()), "{hq:?}");
    assert!(hq.contains(&"Harry Potter".to_string()), "{hq:?}");

    assert_eq!(
        clean_collection_film_title("1 Гарри Поттер и философский камень (2001).mkv"),
        "Гарри Поттер и философский камень"
    );
    assert_eq!(
        clean_collection_film_title("1. 1 Гарри Поттер и философский камень"),
        "Гарри Поттер и философский камень"
    );
    assert_eq!(clean_collection_film_title("Фильм 2 - Гарри Поттер и Тайная комната"), "Гарри Поттер и Тайная комната");
    assert_eq!(clean_collection_film_title("300.mkv"), "300");
}

#[test]
fn tile_title_is_ai_then_tmdb_russian_then_release() {
    for status in ["suggested", "rejected", "none"] {
        let item = prepare_items(parse_library(&ai_library(status))).remove(1);
        let q = item.poster_queries[0].to_lowercase();
        assert_eq!(tile_title(&item, |it| (it == q).then(|| "Русское".to_string())), "Русское", "{status}");
        assert_eq!(tile_title(&item, |_| Some(String::new())), item.base_title, "{status}");
    }
    let accepted = prepare_items(parse_library(&ai_library("accepted"))).remove(1);
    assert_eq!(
        tile_title(&accepted, |it| Some(if it == "тачки 2" { "Тачки 2 (TMDB)" } else { "Другое" }.into())),
        "Тачки 2"
    );
}

// ---- перенос `SeriesGroupingTest.kt` и `AiTitleSearchGroupingTest` ----

const MB: i64 = 1024 * 1024;

fn magnet(name: &str, files: &[(String, i64)]) -> Magnet {
    let fs = files
        .iter()
        .enumerate()
        .map(|(i, (p, s))| MFile {
            index: i as i32,
            name: after_last(p, '/').into(),
            path: p.clone(),
            is_folder: false,
            size: *s,
            link: Some(format!("https://x/{i}")),
        })
        .collect();
    Magnet { size: files.iter().map(|f| f.1).sum(), ..mag(1, name, "Ready", 4, 0, 0, fs) }
}

fn fl(items: &[(&str, i64)]) -> Vec<(String, i64)> {
    items.iter().map(|(p, s)| (p.to_string(), *s)).collect()
}

fn one(m: Magnet) -> LibraryItem {
    prepare_items(vec![m]).remove(0)
}

#[test]
fn season_marks_do_not_split_the_title() {
    let a = title_key(&clean_title("Breaking.Bad.S01.1080p.BluRay"));
    assert_eq!(a, "breaking bad");
    assert_eq!(title_key(&clean_title("Breaking Bad Season 2 1080p")), a);
    assert_eq!(title_key(&clean_title("Breaking Bad [1-3 сезон] 1080p")), a);
    assert_eq!(title_key(&clean_title("Breaking Bad Complete 720p")), a);
}

#[test]
fn different_shows_keep_different_keys() {
    assert_ne!(title_key("Cyberpunk Edgerunners"), title_key("Cyberpunk 2077"));
}

#[test]
fn numbered_episodes_are_a_series_not_a_collection() {
    let files: Vec<_> = (1..=10).map(|i| (format!("Show/Show - {i:02} [1080p].mkv"), 800 * MB)).collect();
    let p = build_playable(&magnet("Show [WEB-DL 1080p]", &files));
    let s = series(&p);
    assert_eq!(s.len(), 1);
    assert_eq!(s[0].items.len(), 10);
    assert_eq!(s[0].items[0].episode, 1);
    assert_eq!(s[0].items[0].title, None);
}

#[test]
fn season_from_the_release_name_when_files_do_not_say_it() {
    assert_eq!(name_season("Show.S02.1080p"), Some(2));
    assert_eq!(name_season("Сериал / Сезон: 2 / Серии: 1-10 из 10"), Some(2));
    assert_eq!(name_season("Сериал (2-й сезон) [2024]"), Some(2));
    assert_eq!(name_season("Show S01-S03"), None);
    assert_eq!(name_season("Сериал [1-3 сезон]"), None);
    assert_eq!(name_season("Сериал (2 сезона)"), None);
    assert_eq!(name_season("Cyberpunk 2077"), None);
    let seasons = |p: &Playable| series(p).iter().map(|s| s.season).collect::<Vec<_>>();
    let ep = build_playable(&magnet(
        "Show.S02.1080p",
        &(1..=3).map(|i| (format!("Show.S02.1080p/Show.E0{i}.mkv"), 800 * MB)).collect::<Vec<_>>(),
    ));
    assert_eq!(seasons(&ep), [2]);
    let num = build_playable(&magnet(
        "Сериал (2 сезон) WEB-DL",
        &(1..=3).map(|i| (format!("Сериал/Сериал - 0{i} [1080p].mkv"), 800 * MB)).collect::<Vec<_>>(),
    ));
    assert_eq!(seasons(&num), [2]);
    assert_eq!(series(&num)[0].items[0].season, 2);
    let own = build_playable(&magnet(
        "Show S02",
        &(1..=2).map(|i| (format!("Show/Show.S01E0{i}.mkv"), 800 * MB)).collect::<Vec<_>>(),
    ));
    assert_eq!(seasons(&own), [1]);
}

#[test]
fn watched_season_is_the_one_holding_the_file() {
    let files: Vec<_> =
        (1..=2).flat_map(|s| (1..=3).map(move |i| (format!("Show/Season {s}/E0{i}.mkv"), 800 * MB))).collect();
    let show = one(magnet("Show", &files));
    assert_eq!(watched_season(&show, "Show/Season 2/E02.mkv").map(|s| s.season), Some(2));
    assert_eq!(watched_season(&show, "Show/Season 2/E02.mkv").map(|s| s.items.len()), Some(3));
    assert_eq!(watched_season(&show, "Show/none.mkv"), None);
    let trilogy = one(magnet("Trilogy", &fl(&[("Film 1 (1999).mkv", 4000 * MB), ("Film 2 (2003).mkv", 4200 * MB)])));
    assert_eq!(watched_season(&trilogy, "Film 1 (1999).mkv"), None);
}

#[test]
fn trilogy_stays_a_collection() {
    let p = build_playable(&magnet(
        "Trilogy",
        &fl(&[("Film 1 (1999).mkv", 4000 * MB), ("Film 2 (2003).mkv", 4200 * MB), ("Film 3 (2003).mkv", 4100 * MB)]),
    ));
    assert_eq!(series(&p)[0].items[0].title.as_deref(), Some("Film 1"));
}

fn release(id: i64, name: &str, size_mb: i64, files: Vec<MFile>) -> LibraryItem {
    one(Magnet { size: size_mb * MB, ..mag(id, name, "Ready", 4, 0, 0, files) })
}

fn eps(season: i32, each_mb: i64) -> Vec<MFile> {
    (1..=10)
        .map(|i| MFile {
            index: i,
            name: format!("Cyberpunk Edgerunners S0{season}E{i}.mkv"),
            path: format!("Season {season}/E{i}.mkv"),
            is_folder: false,
            size: each_mb * MB,
            link: None,
        })
        .collect()
}

fn single(name: &str, size_mb: i64) -> Vec<MFile> {
    vec![MFile { index: 1, name: name.into(), path: name.into(), is_folder: false, size: size_mb * MB, link: None }]
}

#[test]
fn versions_sort_by_season_chronologically_and_quality_and_size() {
    let s2_4k_large = release(1, "Cyberpunk Edgerunners 2 [2160p WEB-DL DV HDR10]", 26_200, eps(2, 2620));
    let s1_1080p_large =
        release(2, "Cyberpunk - Edgerunners [2022] [WEB-DL] [Dolby Vision] [1080p] [RU...]", 26_000, eps(1, 2600));
    let s2_4k_small = release(3, "Cyberpunk Edgerunners 2 [2160p WEB-DL DV HDR10]", 24_800, eps(2, 2480));
    let s1_1080p_small =
        release(4, "[Deadmau- RAWS] Cyberpunk.Edgerunners.S01.x264.2022.BDRip.1080...", 16_200, eps(1, 1620));
    let unsorted = vec![s2_4k_large, s1_1080p_large, s2_4k_small, s1_1080p_small];
    assert_eq!(ids(&sort_versions(&unsorted)), [2, 4, 1, 3]);
    let sections = version_sections(&unsorted);
    assert_eq!(sections.len(), 2);
    assert_eq!(sections[0].title.as_deref(), Some("Сезон 1"));
    assert_eq!(ids(&sections[0].items), [2, 4]);
    assert_eq!(sections[1].title.as_deref(), Some("Сезон 2"));
    assert_eq!(ids(&sections[1].items), [1, 3]);
}

#[test]
fn movie_trilogy_sorts_by_year_and_part() {
    let part3 = release(
        3,
        "The Return of the King (2003) 4K Remux",
        45_000,
        single("The Return of the King (2003).mkv", 45_000),
    );
    let part1_1080p = release(
        1,
        "The Fellowship of the Ring (2001) 1080p",
        15_000,
        single("The Fellowship of the Ring (2001).mkv", 15_000),
    );
    let part2 = release(2, "The Two Towers (2002) 4K Remux", 40_000, single("The Two Towers (2002).mkv", 40_000));
    let part1_4k = release(
        4,
        "The Fellowship of the Ring (2001) 4K Remux",
        42_000,
        single("The Fellowship of the Ring (2001).mkv", 42_000),
    );
    let unsorted = vec![part3, part1_1080p, part2, part1_4k];
    assert_eq!(ids(&sort_versions(&unsorted)), [4, 1, 2, 3]);
    let sections = version_sections(&unsorted);
    let got: Vec<(Option<&str>, Vec<i64>)> = sections.iter().map(|s| (s.title.as_deref(), ids(&s.items))).collect();
    assert_eq!(got, [(Some("2001 год"), vec![4, 1]), (Some("2002 год"), vec![2]), (Some("2003 год"), vec![3])]);
}

#[test]
fn single_season_or_movie_has_null_section_title() {
    let v1 = release(1, "Inception (2010) 1080p", 10_000, single("Inception.mkv", 10_000));
    let v2 = release(2, "Inception (2010) 4K", 30_000, single("Inception.mkv", 30_000));
    let sections = version_sections(&[v1, v2]);
    assert_eq!(sections.len(), 1);
    assert_eq!(sections[0].title, None);
    assert_eq!(ids(&sections[0].items), [2, 1]);
}

fn ai_movie(id: i64, name: &str, a: Option<AiTitle>) -> Magnet {
    let f = MFile {
        index: 0,
        name: format!("{name}.mkv"),
        path: format!("{name}.mkv"),
        is_folder: false,
        size: 4000 * MB,
        link: Some(format!("https://x/{id}")),
    };
    Magnet { size: 4000 * MB, ai_title: a, ..mag(id, name, "Ready", 4, 0, 0, vec![f]) }
}

fn dune_accepted() -> AiTitle {
    AiTitle {
        original: Some("Dune".into()), year: Some(2021), ..ai("Дюна", None, "m", 1, "accepted", Some("movie"))
    }
}

#[test]
fn search_sees_the_accepted_title_only() {
    let item = one(ai_movie(1, "Sousou.no.Frieren", Some(dune_accepted())));
    assert!(item.search_text.contains("дюна") && item.search_text.contains("dune"));
    let suggested =
        one(ai_movie(1, "Sousou.no.Frieren", Some(AiTitle { status: "suggested".into(), ..dune_accepted() })));
    assert!(!suggested.search_text.contains("дюна"));
    assert!(suggested.with_ai_title(Some(dune_accepted())).search_text.contains("дюна"));
}

#[test]
fn ai_year_joins_releases_without_year_in_name() {
    let items = prepare_items(vec![
        ai_movie(1, "Dune.2021.1080p", Some(dune_accepted())),
        ai_movie(2, "Dune.BDRip", Some(dune_accepted())),
    ]);
    let (shown, versions) = group_versions(&items, |_| None);
    assert_eq!(shown.len(), 1);
    assert_eq!(versions[&1].len(), 2);
}

#[test]
fn russian_only_collection_words_work() {
    let lotr = magnet(
        "Властелин колец: Трилогия (2001-2003)",
        &fl(&[
            ("LOTR/Братство кольца (2001).mkv", 4000 * MB),
            ("LOTR/Две крепости (2002).mkv", 4000 * MB),
            ("LOTR/Возвращение короля (2003).mkv", 4000 * MB),
        ]),
    );
    let item = one(lotr.clone());
    assert!(is_collection(&item.playable, Some(&item.m)));
    assert_eq!(collection_kind(3, &lotr.name), CollectionKind::Trilogy);
    assert_eq!(collection_kind(3, "Дилогия + бонус"), CollectionKind::Dilogy);
    assert_eq!(collection_kind(8, "Гарри Поттер: Коллекция 8 фильмов"), CollectionKind::Collection);
    assert_eq!(version_line(&item, false), "Кинотрилогия • 3 фильма");
    let serial = Playable::Series(vec![Season {
        season: 1,
        items: vec![Episode::new(1, 1, &lotr.files[0]), Episode::new(1, 2, &lotr.files[1])],
    }]);
    let named = |n: &str| Magnet { name: n.into(), ..lotr.clone() };
    assert!(is_collection(&serial, Some(&named("Пила все части"))));
    assert!(is_collection(&serial, Some(&named("Матрица 3 в 1"))));
    assert!(!is_collection(&serial, Some(&named("Шоу: трилогия, 2 сезон"))));
}

#[test]
fn a_show_called_saga_or_collection_stays_a_show() {
    let files: Vec<_> = (1..=24).map(|i| (format!("Vinland/Vinland Saga - {i:02} [1080p].mkv"), 800 * MB)).collect();
    for name in ["Сага о Винланде / Vinland Saga [1080p]", "Cowboy Bebop Complete Collection [BD 1080p]"] {
        let m = magnet(name, &files);
        let p = build_playable(&m);
        let s = series(&p);
        assert_eq!(s.len(), 1, "{name}");
        assert_eq!(s[0].items.len(), 24, "{name}");
        assert_eq!(s[0].items[0].title, None, "{name}");
        assert!(!is_collection(&p, Some(&m)), "{name}");
    }
    let mi: Vec<_> = (0..6).map(|i| (format!("MI/Mission {} ({}).mkv", i + 1, 1996 + i * 4), 4000 * MB)).collect();
    assert!(first_title(&build_playable(&magnet("Миссия невыполнима: коллекция", &mi))).is_some());
}

#[test]
fn accepted_ai_kind_decides_whether_it_is_a_collection() {
    let m = magnet("Властелин колец: Трилогия", &fl(&[("A (2001).mkv", 4000 * MB), ("B (2002).mkv", 4000 * MB)]));
    let s = Playable::Series(vec![Season {
        season: 1,
        items: vec![Episode::new(1, 1, &m.files[0]), Episode::new(1, 2, &m.files[1])],
    }]);
    assert!(is_collection(&s, Some(&m)));
    let as_series = Magnet { ai_title: Some(ai("Шоу", Some(1), "m", 1, "accepted", Some("series"))), ..m.clone() };
    assert!(!is_collection(&s, Some(&as_series)));
    let as_coll =
        Magnet {
            name: "Шоу".into(), ai_title: Some(ai("Шоу", None, "m", 1, "accepted", Some("collection"))), ..m
        };
    assert!(is_collection(&s, Some(&as_coll)));
}

#[test]
fn roman_numerals_elsewhere_are_not_parts() {
    assert_eq!(item_part(&one(magnet("X-Men (2000) 1080p", &fl(&[("X-Men.2000.mkv", 4000 * MB)])))), None);
    assert_eq!(item_part(&one(magnet("Дюна: Часть II (2024)", &fl(&[("Dune2.mkv", 4000 * MB)])))), Some(2));
    assert_eq!(item_part(&one(magnet("8 фильмов", &fl(&[("a.mkv", MB)])))), None);
    let second = one(magnet("Тачки 2 (2011)", &fl(&[("Cars2.mkv", 4000 * MB)])));
    assert_eq!(item_part(&LibraryItem { base_title: "Тачки".into(), ..second }), Some(2));
}

#[test]
fn sorting_many_mixed_versions_keeps_a_total_order() {
    let names = ["Film Part 2", "Film (2000)", "Film Part 1 (2005)", "Film", "Film Part 3 (1999)", "Film (2010) 4K"];
    let items: Vec<LibraryItem> = (0..64)
        .map(|i: i64| {
            let n = names[i as usize % names.len()];
            one(Magnet { id: i, ..magnet(n, &[(format!("f{i}.mkv"), (1000 + (i * 37) % 11) * MB)]) })
        })
        .collect();
    // перемешиваем детерминированно (шаг 29 взаимно прост с 64)
    let shuffled: Vec<LibraryItem> = (0..64).map(|k| items[(k * 29) % 64].clone()).collect();
    let sorted = sort_versions(&shuffled);
    assert_eq!(sorted.len(), 64);
    let mut rev = sorted.clone();
    rev.reverse();
    assert_eq!(ids(&sorted), ids(&sort_versions(&rev)));
    assert_eq!(item_part(&sorted[0]), Some(1));
}

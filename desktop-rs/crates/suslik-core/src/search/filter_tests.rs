//! Перенос `SearchFilterTest.kt`.
use super::*;

const GB: i64 = 1024 * 1024 * 1024;

fn hit(id: &str, title: &str, size: i64, seeders: i32, ru: bool) -> SearchHit {
    SearchHit::new(id, title, size, seeders, ru, "movie", "Rutor")
}

fn all() -> Vec<SearchHit> {
    vec![
        hit("1", "Dune 2 (2024) BDRemux 2160p | DV | TrueHD", 60 * GB, 100, true),
        hit("2", "Dune 2 (2024) BDRip 1080p | D", 8 * GB, 50, true),
        hit("3", "Dune 2 (2024) WEB-DL 720p | D | x265", 2 * GB, 30, true),
        hit("4", "Dune 2 (2024) CAMRip | Экранка", 1_500_000_000, 20, true),
        hit("5", "Old Dune WEB-DL 1080p", 4 * GB, 0, false),
    ]
}

fn ids(v: &[SearchHit]) -> Vec<&str> {
    v.iter().map(|h| h.id.as_str()).collect()
}

#[test]
fn mobile_preset() {
    let r = SearchFilterCriteria { preset: FilterPreset::Mobile, ..Default::default() }.filter(&all());
    let ids = ids(&r);
    assert!(ids.contains(&"3"));
    assert!(!ids.contains(&"1") && !ids.contains(&"2") && !ids.contains(&"4"));
}

#[test]
fn only_seeded() {
    let r = SearchFilterCriteria { only_seeded: true, ..Default::default() }.filter(&all());
    assert!(!ids(&r).contains(&"5"));
    assert_eq!(r.len(), 4);
}

#[test]
fn sort_by_size_ascending() {
    let r = SearchFilterCriteria { sort: SearchSort::SizeAsc, ..Default::default() }.filter(&all());
    assert_eq!(ids(&r), ["4", "3", "5", "2", "1"]);
}

#[test]
fn resolution_filter() {
    let r = SearchFilterCriteria { resolution: Some(Resolution::Uhd4k), ..Default::default() }.filter(&all());
    assert_eq!(ids(&r), ["1"]);
}

#[test]
fn search_query_cleaner() {
    assert_eq!(clean_query("фильмы новый человек паук"), "новый человек паук");
    assert_eq!(clean_query("сериал во все тяжкие смотреть"), "во все тяжкие");
    assert_eq!(clean_query("кино интерстеллар скачать в хорошем качестве"), "интерстеллар");
    assert_eq!(clean_query("мультфильм король лев онлайн"), "король лев");
    assert_eq!(clean_query("фильмы"), "фильмы");
}

#[test]
fn relevance_ranking_spider_man() {
    let hits = vec![
        SearchHit::new(
            "b1",
            "Человек-паук / Spider-Man (2002) BDRip 1080p | D",
            8 * GB,
            200,
            true,
            "movie",
            "RuTracker",
        ),
        SearchHit::new(
            "c1",
            "Человек-паук: Новый день [2026] тизер-трейлер",
            500_000_000,
            300,
            true,
            "movie",
            "RuTracker",
        ),
        SearchHit::new(
            "a1",
            "Новый Человек-паук / The Amazing Spider-Man (2012) BDRip 1080p | D",
            8 * GB,
            50,
            true,
            "movie",
            "RuTracker",
        ),
    ];
    let r = SearchFilterCriteria { query: "новый человек паук".into(), ..Default::default() }.filter(&hits);
    assert_eq!(ids(&r), ["a1", "b1"]);
}

#[test]
fn relevance_ranking_with_specific_year() {
    let hits = vec![
        hit(
            "asm2",
            "Новый Человек-паук: Высокое напряжение / The Amazing Spider-Man 2 (2014) BDRip 1080p | D",
            9 * GB,
            150,
            true,
        ),
        hit("asm1", "Новый Человек-паук / The Amazing Spider-Man (2012) BDRip 1080p | D", 8 * GB, 30, true),
    ];
    let r = SearchFilterCriteria { query: "новый человек паук 2012".into(), ..Default::default() }.filter(&hits);
    assert_eq!(ids(&r), ["asm1", "asm2"]);
}

#[test]
fn hit_from_json() {
    let v: serde_json::Value = serde_json::from_str(
        r#"[{"id":"x","title":"Dune 2 (2024) BDRip 1080p","size":100,"seeders":"7","sources":["A","B"],"date":1700000000000,"magnet":"magnet:?xt=1"},
            {"id":"","title":"skip"},
            {"id":"y","title":"T","provider":"P","date":"2024-03-01T10:00","magnet":"http://x"}]"#,
    )
    .unwrap();
    let hits = SearchHit::parse_hits(Some(&v));
    assert_eq!(hits.len(), 2);
    assert_eq!((hits[0].source.as_str(), hits[0].seeders, hits[0].date.as_str()), ("A + B", 7, "14.11.2023"));
    assert_eq!(hits[0].kind, "movie");
    assert_eq!(hits[0].magnet.as_deref(), Some("magnet:?xt=1"));
    assert_eq!((hits[1].source.as_str(), hits[1].date.as_str(), hits[1].magnet.as_ref()), ("P", "2024-03-01", None));
}

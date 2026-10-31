//! Что плитка говорит о раздаче: год, вид («Фильм», «Сериал», «Аниме»…), категория «Коллекции», метки Dolby Vision / HDR / DTS.
//! Повторяет функции из начала `LibraryView.kt` (те же правила, что у сайта, `website/classify.js`).

use crate::strings::tr;
use suslik_core::jrx::{caps, find, group, has};
use suslik_core::model::{collection_films, collection_kind, format_bytes, is_collection, LibraryItem, Playable};
use suslik_core::rx;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Category {
    All,
    Movies,
    Series,
    Anime,
    Cartoons,
    Other,
}

impl Category {
    pub const ALL: [Category; 6] =
        [Category::All, Category::Movies, Category::Series, Category::Anime, Category::Cartoons, Category::Other];

    pub fn label(self) -> String {
        tr(
            match self {
                Category::All => "library.vse",
                Category::Movies => "library.filmy",
                Category::Series => "library.serialy",
                Category::Anime => "library.anime",
                Category::Cartoons => "library.multfilmy",
                Category::Other => "library.drugoe",
            },
            &[],
        )
    }
}

const YEAR: &str = r"\b(19\d{2}|20\d{2})\b";

pub fn item_year(item: &LibraryItem) -> String {
    if let Some(y) = find(rx!(YEAR), &item.m.name) {
        return y.to_string();
    }
    for f in &item.m.files {
        if let Some(y) = find(rx!(YEAR), &f.name) {
            return y.to_string();
        }
    }
    "2024".into()
}

pub fn is_anime(item: &LibraryItem) -> bool {
    let text = format!("{} {}", item.m.name, item.base_title).to_lowercase();
    ["anime", "аниме", "[horriblesubs]", "[erai-raws]", "[subsplease]", "[judas]", "[anilibria]", "anilibria"]
        .iter()
        .any(|w| text.contains(w))
}

// То же правило, по которому сайт отличает мультфильмы от фильмов (website/classify.js).
const CARTOON: &str = r"мульт(?!сериал)|анимаци|\bcartoons?\b|\banimation\b|\bPixar\b|\bDreamWorks\b|\bDisney\b";

/// `animation` — жанр TMDB по запросу постера в нижнем регистре (ответ `/api/poster`, см. `Library`).
pub fn is_cartoon(item: &LibraryItem, animation: &dyn Fn(&str) -> bool) -> bool {
    has(rx!(CARTOON, ic), &item.m.name)
        || has(rx!(CARTOON, ic), &item.base_title)
        || item.poster_queries.iter().any(|q| animation(&q.to_lowercase()))
}

pub fn item_category(item: &LibraryItem, animation: &dyn Fn(&str) -> bool) -> Category {
    if is_anime(item) {
        return Category::Anime;
    }
    if is_cartoon(item, animation) {
        return Category::Cartoons;
    }
    if is_collection(&item.playable, Some(&item.m)) {
        return Category::Movies;
    }
    match item.playable {
        Playable::Series { .. } => Category::Series,
        Playable::Movie { .. } => Category::Movies,
        _ => Category::Other,
    }
}

pub fn item_type_label(item: &LibraryItem, animation: &dyn Fn(&str) -> bool) -> String {
    let films = collection_films(item);
    if !films.is_empty() {
        return tr(collection_kind(films.len(), &item.m.name).tr_key(), &[]);
    }
    tr(
        match item_category(item, animation) {
            Category::Anime => "library.anime",
            Category::Cartoons => "library.multfilm",
            Category::Series => "library.serial",
            Category::Movies => "library.film",
            Category::Other => "library.drugoe",
            Category::All => "library.video",
        },
        &[],
    )
}

const TAG_DV: &str = r"\b(dolby[ ._-]?vision|dovi|dv)\b";
const TAG_HDR: &str = r"\bhdr(10)?(\+|plus)?\b";
const TAG_DTS: &str = r"\bdts(?:[ ._:-]?(hd|x))?(?:[ ._-]?(ma))?\b";

/// Метки раздачи для постера: «Dolby Vision», «HDR», «DTS» (по именам раздачи и файлов), пока сервер не ответил своими.
pub fn item_tags(item: &LibraryItem) -> Vec<String> {
    let mut text = item.m.name.clone();
    for f in &item.m.files {
        text.push('\n');
        text.push_str(&f.path);
    }
    let mut out = Vec::new();
    if has(rx!(TAG_DV, ic), &text) {
        out.push("Dolby Vision".to_string());
    }
    if has(rx!(TAG_HDR, ic), &text) {
        out.push("HDR".into());
    }
    if let Some(c) = caps(rx!(TAG_DTS, ic), &text) {
        let kind = group(&c, 1).to_lowercase();
        let ma = !group(&c, 2).is_empty();
        out.push(
            match kind.as_str() {
                "hd" if ma => "DTS-HD MA",
                "hd" => "DTS-HD",
                "x" => "DTS:X",
                _ => "DTS",
            }
            .into(),
        );
    }
    out
}

/// Одна метка картинки и одна звука: «Dolby Vision» (или «HDR», если его нет) и «DTS».
pub fn collapse_tags(all: &[String]) -> Vec<&'static str> {
    let mut out = Vec::new();
    if all.iter().any(|t| t.starts_with("Dolby")) {
        out.push("Dolby Vision");
    } else if all.iter().any(|t| t == "HDR") {
        out.push("HDR");
    }
    if all.iter().any(|t| t.starts_with("DTS")) {
        out.push("DTS");
    }
    out
}

/// «2 версии», «5 версий».
pub fn versions_word(n: usize) -> String {
    let key = if (2..=4).contains(&(n % 10)) && !(12..=14).contains(&(n % 100)) {
        "library.versii_2"
    } else {
        "library.versiy_5"
    };
    tr(key, &[("n", &n.to_string())])
}

/// Строки характеристик под названием: «1080p • H265 • 23.98 fps» и «≈12 Мбит/с • 8,4 ГБ» (`itemSpecs`).
pub fn item_specs(item: &LibraryItem, media: Option<&str>) -> (String, String) {
    let name = item.m.name.to_lowercase();
    let s = media.unwrap_or("").to_lowercase();
    let any = |words: &[&str], src: &str| words.iter().any(|w| src.contains(w));
    let quality = if any(&["3840", "2160"], &s) || any(&["2160p", "4k"], &name) {
        "4K"
    } else if any(&["1920", "1080"], &s) || name.contains("1080p") {
        "1080p"
    } else if any(&["1280", "720"], &s) || name.contains("720p") {
        "720p"
    } else {
        "1080p"
    };
    let codec = if any(&["hevc", "h.265", "h265"], &s) || any(&["x265", "hevc", "h265", "h.265"], &name) {
        "H265"
    } else if any(&["avc", "h.264", "h264"], &s) || any(&["x264", "h264", "h.264"], &name) {
        "H264"
    } else if s.contains("av1") || name.contains("av1") {
        "AV1"
    } else if quality == "4K" {
        "H265"
    } else {
        "H264"
    };
    const FPS: &str = r"(\d+(\.\d+)?)\s*fps";
    let fps = find(rx!(FPS), &s).or_else(|| find(rx!(FPS), &name)).unwrap_or("23.98 fps").to_string();
    const BR: &str = r"(\d+(\.\d+)?)\s*мбит/с";
    let bitrate = if let Some(b) = find(rx!(BR), &s) {
        b.to_string()
    } else if item.m.size > 0 {
        let approx = (item.m.size as f64 * 8.0 / (7200.0 * 1_000_000.0)).clamp(8.0, 75.0);
        // String.format(Locale.US, "%.1f Мбит/с"): шаблон из строк, точка в дроби
        tr("library.f_mbit_s", &[]).replace("%.1f", &format!("{approx:.1}"))
    } else {
        tr("library.mbit_s_2", &[])
    };
    (format!("{quality} • {codec} • {fps}"), format!("{bitrate} • {}", format_bytes(item.m.size)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use suslik_core::model::{MFile, Magnet};

    fn item(name: &str, files: &[&str]) -> LibraryItem {
        let files = files
            .iter()
            .enumerate()
            .map(|(i, p)| MFile {
                index: i as i32,
                name: p.rsplit('/').next().unwrap().into(),
                path: (*p).into(),
                is_folder: false,
                size: 1 << 30,
                link: Some(format!("https://l/{i}")),
            })
            .collect();
        LibraryItem::of(Magnet::new(1, name, 4, files))
    }
    const NO: &dyn Fn(&str) -> bool = &|_| false;

    #[test]
    fn year_and_kind() {
        let dune = item("Dune.2021.2160p.HDR.DV.DTS-HD.MA", &["Dune.2021.2160p.mkv"]);
        assert_eq!(item_year(&dune), "2021");
        assert_eq!(item_category(&dune, NO), Category::Movies);
        assert_eq!(item_tags(&dune), ["Dolby Vision", "HDR", "DTS-HD MA"]);
        assert_eq!(collapse_tags(&item_tags(&dune)), ["Dolby Vision", "DTS"]);
        let toy = item("Toy Story (Pixar) 1995", &["Toy.Story.1995.mkv"]);
        assert_eq!(item_category(&toy, NO), Category::Cartoons);
        let up = item("Up.2009.1080p", &["Up.2009.1080p.mkv"]);
        assert_eq!(item_category(&up, &|q| q.starts_with("up")), Category::Cartoons);
        // «мульт(?!сериал)»: мультсериал — не мультфильм
        assert_eq!(item_category(&item("Мультсериал", &["a.mkv"]), NO), Category::Movies);
    }

    #[test]
    fn plural_versions() {
        assert!(versions_word(2).contains('2'));
        assert_ne!(versions_word(2), versions_word(5).replace('5', "2"));
        assert_eq!(versions_word(12).replace("12", "5"), versions_word(5));
    }
}

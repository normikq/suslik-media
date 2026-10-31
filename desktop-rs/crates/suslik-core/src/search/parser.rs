//! Разбор названий раздач русских и международных трекеров. Повторяет `ReleaseParser.kt`
//! (шаблоны там с `UNICODE_CHARACTER_CLASS`, поэтому здесь — `rx!(…, unicode)` без перевода классов).

use super::kt_len;
use super::release::*;
use crate::jrx::{caps, find_all, group, has};
use crate::rx;

const RES_4K: &str = r"(?:\b|_)(?:2160p|4k|uhd|3840x2160|4к)(?:\b|_)";
const RES_1080P: &str = r"(?:\b|_)(?:1080[pi]|fhd|full[- ]?hd|1920x1080)(?:\b|_)";
const RES_720P: &str = r"(?:\b|_)(?:720p|1280x720)(?:\b|_)";
const RES_SD: &str = r"(?:\b|_)(?:480[pi]|576[pi]|sd)(?:\b|_)";

const CODEC_HEVC: &str = r"(?:\b|_)(?:hevc|h\.?265|x265)(?:\b|_)";
const CODEC_AVC: &str = r"(?:\b|_)(?:avc|h\.?264|x264)(?:\b|_)";
const CODEC_AV1: &str = r"(?:\b|_)(?:av1)(?:\b|_)";
const CODEC_OTHER: &str = r"(?:\b|_)(?:xvid|divx|mpeg-?2)(?:\b|_)";

const RIP_REMUX: &str = r"(?:\b|_)(?:bd[- ]?remux|remux)(?:\b|_)";
const RIP_CAM: &str = r"(?:\b|_)(?:cam[- ]?rip|cam|hdcam|telesync|hd-?ts|экранка)(?:\b|_)|\[ts\]|\|\s*ts\b|звук\s*с\s*ts|\b(?:dvdscr|scr)\b";
const RIP_BDRIP: &str = r"(?:\b|_)(?:bd[- ]?rip|brrip|bdrip-hevc)(?:\b|_)";
const RIP_BLURAY: &str = r"(?:\b|_)(?:bluray|blu[- ]?ray|bdmv)(?:\b|_)";
const RIP_WEBDL: &str = r"(?:\b|_)(?:web-?dl(?:rip)?|webdl(?:rip)?)(?:\b|_)";
const RIP_WEBRIP: &str = r"(?:\b|_)(?:web-?rip|webrip)(?:\b|_)";
const RIP_HDTV: &str = r"(?:\b|_)(?:hdtv(?:rip)?|tvrip|satrip)(?:\b|_)";
const RIP_DVDRIP: &str = r"(?:\b|_)(?:dvd[- ]?rip|dvd[59])(?:\b|_)";

const HDR_DV: &str = r"(?:\b|_)(?:dolby\s*vision|dovi)(?:\b|_)|(?:\b|_|\|)(?:dv)(?:\b|_|\||\+)";
const HDR_10PLUS: &str = r"(?:\b|_)(?:hdr10\+|hdr10plus)(?:\b|_)";
const HDR_10: &str = r"(?:\b|_)(?:hdr10?)(?:\b|_)";

const VOICE_DUB: &str = r"(?:\b|_)(?:дублированный|дубляж|лицензия|dub)\b|(?<=[|\[(\s])(?:d|дб)\b|\[дубляж\]";
const VOICE_MVO: &str = r"(?:\b|_)(?:многоголосый|профессиональный\s*\(многоголосый\)|пм|mvo)\b";
const VOICE_DVO: &str = r"(?:\b|_)(?:двухголосый|профессиональный\s*\(двухголосый\)|пд|dvo)\b";
const VOICE_AVO: &str = r"(?:\b|_)(?:авторский|одноголосый|avo)\b";
const VOICE_ORIG: &str = r"(?:\b|_)(?:субтитры|оригинал|original)(?:\b|_)|(?:\b|_)(?:eng|jap)(?:\b|_)";

const EP_OF: &str = r"\b\d{1,3}\s*(?:из|of)\s*(\d{1,3})\b";
const EP_RANGE: &str = r"\[\d{1,2}(?:-\d{1,2})?x\d{1,2}-(\d{1,3})\]";
const EP_WORD_RANGE: &str = r"(?:\b(?:сери[йи]|эпизод[оыв]?)\s*\d{1,3}-(\d{1,3})\b)";
const EP_SX_EX: &str = r"S\d{1,2}E\d{1,3}-E?(\d{1,3})";
const SERIES_HINT: &str = r"\b(?:сезон|сериал|season|s\d{1,2}|сери[яи]|эпизод)\b";

const COLLECTION_WORDS: &str = r"\b(коллекци[яи]|collection|boxset|box-set|антологи[яи]|anthology|все\s+части|все\s+фильмы|все\s+выпуски|полная\s+коллекция|полное\s+собрание|франшиза|киносага|сага|franchise)\b|\[коллекция\]|\(коллекция\)|\[антология\]|\(антология\)";
const COLLECTION_COUNT: &str = r"\b(\d{1,2})\s*(?:фильм[а-ов]?|movies?|parts?|частей|части|фильма)\b|\b(?:части|фильмы|parts|movies)\s*1\s*[-–—]\s*(\d{1,2})\b";
const TRILOGY: &str = r"\b(трилоги[яи]|trilogy)\b";
const DUOLOGY: &str = r"\b(дилоги[яи]|duology|dilogy)\b";
const QUADRILOGY: &str = r"\b(квадрологи[яи]|тетралоги[яи]|quadrilogy|tetralogy)\b";
const PENTALOGY: &str = r"\b(пенталоги[яи])\b";
const HEXALOGY: &str = r"\b(гексалоги[яи])\b";

const YEAR_RANGE: &str = r"\b(19\d{2}|20\d{2})\s*[-–—]\s*(19\d{2}|20\d{2})\b";
const YEAR_REGEX: &str = r"\b(19\d{2}|20\d{2})\b";

const TECH_SPLIT: &str = r"(?:\s*[|]\s*|\s+(?:2160p|1080[pi]|720p|480p|4k|bdremux|remux|bluray|blu-ray|bdrip|web-?dl(?:rip)?|web-?rip|hdtv(?:rip)?|dvdrip|camrip|telesync|x26[45]|hevc|avc|h\.?26[45]|aac|dts|ddp?5?\.?1)\b)";
const TRAILING_PUNCTUATION: &str = r"[\s|/\\-]+$";

/// Метки, которых у фильма не бывает: раздача с любой из них — точно не кино.
const GAME_STRONG: &str = concat!(
    r"(?:(?:\b|_)(?:fpkg|xci|nsp|fitgirl|xatab|dodi|decepticon|steam-?rip|gog|cusa\d+|ppsa\d+)(?:\b|_)|",
    r"\b(?:rus\s*text|eng\s*text|rus\s*sound|eng\s*sound)\b|",
    r"\b(?:goty|game\s*of\s*the\s*year)\b|",
    r"(?:таблетка|кряк|crack)\s*[:(]?\s*(?:вшита|вшит|присутствует|не требуется))"
);
/// Метки, которые бывают и у фильмов: отсеиваем, только если их две и больше.
const GAME_WEAK: &str = concat!(
    r"(?:(?:\b|_)(?:repack|рипак|репак|pkg|pc)(?:\b|_)|",
    r"\b(?:digital\s*deluxe|deluxe\s*edition|gold\s*edition|ultimate\s*edition)\b|",
    r"\bv\d+\.\d+(?:\.\d+)?\b)"
);
/// Окончания по одному: «трейлер[а-я]*» ловил «Парни из Трейлерного парка».
const TRAILER_OR_TEASER: &str = r"\b(?:тизер(?:[- ]?трейлер)?(?:ы|а|ов|у|е|ом|ам|ами)?|трейлер(?:ы|а|ов|у|е|ом|ам|ами)?|teasers?|trailers?)\b(?!\s*(?:парк|park))";

const VIDEO_FORUM_WHITELIST: [&str; 7] =
    ["музыкальное видео", "видеоклип", "клип", "концерт", "экранизац", "по играм", "по книг"];
const NON_VIDEO_FORUM_KEYWORDS: [&str; 31] = [
    "игр",
    "game",
    "ps3",
    "ps4",
    "ps5",
    "playstation",
    "xbox",
    "nintendo",
    "switch",
    "программ",
    "софт",
    "soft",
    "windows",
    "macos",
    "linux",
    "драйвер",
    "книги и журналы",
    "аудиокниг",
    "литератур",
    "электронные книги",
    "комикс",
    "музыка (lossless)",
    "дискографи",
    "lossless",
    "flac",
    "саундтрек",
    "музыка mp3",
    "обои",
    "картинк",
    "фотографии",
    "3d модел",
];

macro_rules! u {
    ($p:expr) => {
        rx!($p, unicode)
    };
}

pub fn is_trailer_or_teaser(raw_title: &str) -> bool {
    has(u!(TRAILER_OR_TEASER), raw_title)
}

/// Игры, программы, музыка или книги — по названию раздачи или разделу трекера.
pub fn is_non_video(raw_title: &str, forum_category: &str) -> bool {
    if !forum_category.trim().is_empty() {
        let f = forum_category.to_lowercase();
        let c = |x: &str| f.contains(x);
        let whitelisted = VIDEO_FORUM_WHITELIST.iter().any(|w| c(w));
        if !whitelisted
            && (NON_VIDEO_FORUM_KEYWORDS.iter().any(|w| c(w))
                || ((c("музык") || c("music")) && !c("видео") && !c("клип") && !c("концерт"))
                || ((c("книг") || c("book")) && !c("экранизац")))
        {
            return true;
        }
    }
    if has(u!(GAME_STRONG), raw_title) || is_trailer_or_teaser(raw_title) {
        return true;
    }
    find_all(u!(GAME_WEAK), raw_title).len() >= 2
}

fn int(c: &fancy_regex::Captures, i: usize) -> Option<i32> {
    group(c, i).parse().ok()
}

fn first_int(pattern: &fancy_regex::Regex, s: &str, i: usize) -> Option<i32> {
    caps(pattern, s).and_then(|c| int(&c, i))
}

/// Название и размер раздачи → [`ReleaseInfo`]. `kind`: "movie", "series", "cartoon", "other", "unknown".
pub fn parse(raw_title: &str, size_bytes: i64, kind: &str) -> ReleaseInfo {
    let s = raw_title.trim();
    let is = |p: &fancy_regex::Regex| has(p, s);
    let is_series = kind == "series" || is(u!(SERIES_HINT));

    let resolution = if is(u!(RES_4K)) {
        Resolution::Uhd4k
    } else if is(u!(RES_1080P)) {
        Resolution::Fhd1080p
    } else if is(u!(RES_720P)) {
        Resolution::Hd720p
    } else if is(u!(RES_SD)) {
        Resolution::Sd
    } else {
        Resolution::Unknown
    };

    let codec = if is(u!(CODEC_HEVC)) {
        VideoCodec::Hevc
    } else if is(u!(CODEC_AV1)) {
        VideoCodec::Av1
    } else if is(u!(CODEC_AVC)) {
        VideoCodec::Avc
    } else if is(u!(CODEC_OTHER)) {
        VideoCodec::Other
    } else {
        VideoCodec::Unknown
    };

    let is_cam = is(u!(RIP_CAM));
    let rip_type = if is_cam {
        RipType::Cam
    } else if is(u!(RIP_REMUX)) {
        RipType::Remux
    } else if is(u!(RIP_BDRIP)) {
        RipType::Bdrip
    } else if is(u!(RIP_BLURAY)) {
        RipType::Bluray
    } else if is(u!(RIP_WEBDL)) {
        RipType::Webdl
    } else if is(u!(RIP_WEBRIP)) {
        RipType::Webrip
    } else if is(u!(RIP_HDTV)) {
        RipType::Hdtv
    } else if is(u!(RIP_DVDRIP)) {
        RipType::Dvdrip
    } else {
        RipType::Unknown
    };

    let hdr = if is(u!(HDR_DV)) {
        HdrType::Dv
    } else if is(u!(HDR_10PLUS)) {
        HdrType::Hdr10Plus
    } else if is(u!(HDR_10)) {
        HdrType::Hdr10
    } else {
        HdrType::None
    };

    let voice = if is(u!(VOICE_DUB)) {
        VoiceType::Dub
    } else if is(u!(VOICE_MVO)) {
        VoiceType::Mvo
    } else if is(u!(VOICE_DVO)) {
        VoiceType::Dvo
    } else if is(u!(VOICE_AVO)) {
        VoiceType::Avo
    } else if is(u!(VOICE_ORIG)) {
        VoiceType::Original
    } else {
        VoiceType::Unknown
    };

    let range = caps(u!(YEAR_RANGE), s);
    let year_start = range.as_ref().and_then(|c| int(c, 1));
    let year_end = range.as_ref().and_then(|c| int(c, 2));
    let year_span = match (year_start, year_end) {
        (Some(a), Some(b)) => b - a,
        _ => 0,
    };
    let year = year_start.or_else(|| first_int(u!(YEAR_REGEX), s, 1));

    let specific_count = if is(u!(TRILOGY)) {
        Some(3)
    } else if is(u!(DUOLOGY)) {
        Some(2)
    } else if is(u!(QUADRILOGY)) {
        Some(4)
    } else if is(u!(PENTALOGY)) {
        Some(5)
    } else if is(u!(HEXALOGY)) {
        Some(6)
    } else {
        None
    };
    let explicit_count = caps(u!(COLLECTION_COUNT), s).and_then(|c| int(&c, 1).or_else(|| int(&c, 2)));

    let is_collection = !is_series
        && (is(u!(COLLECTION_WORDS))
            || specific_count.is_some()
            || explicit_count.is_some_and(|n| n > 1)
            || year_span >= 3);

    const GB: i64 = 1024 * 1024 * 1024;
    let collection_count = if is_collection {
        specific_count.or(explicit_count).or(if year_span >= 3 {
            Some((year_span / 3).clamp(2, 10))
        } else if size_bytes > 15 * GB {
            Some(((size_bytes / (12 * GB)) as i32).clamp(2, 8))
        } else {
            None
        })
    } else {
        None
    };

    let episodes_count = if is_series {
        first_int(u!(EP_OF), s, 1)
            .or_else(|| first_int(u!(EP_RANGE), s, 1))
            .or_else(|| first_int(u!(EP_WORD_RANGE), s, 1))
            .or_else(|| first_int(u!(EP_SX_EX), s, 1))
    } else {
        None
    };

    let bitrate = bitrate_mbps(size_bytes, is_series, episodes_count, is_collection, collection_count);
    let bitrate_tier = if bitrate <= 4.0 {
        BitrateTier::Light
    } else if bitrate <= 12.0 {
        BitrateTier::Medium
    } else {
        BitrateTier::Heavy
    };

    ReleaseInfo {
        clean_title: clean_release_title(s),
        year,
        resolution,
        codec,
        rip_type,
        hdr,
        voice,
        is_cam,
        is_collection,
        collection_count,
        episodes_count,
        estimated_bitrate_mbps: bitrate,
        bitrate_tier,
    }
}

/// Битрейт в Мбит/с: фильм ~105 мин, в сборнике — на один фильм, серия ~45 мин.
fn bitrate_mbps(size: i64, is_series: bool, episodes: Option<i32>, is_collection: bool, films: Option<i32>) -> f32 {
    if size <= 0 {
        return 0.0;
    }
    let bits = size as f64 * 8.0;
    let seconds = if is_series {
        let n = episodes.unwrap_or(if size > 5 * 1024 * 1024 * 1024 { 10 } else { 1 });
        n as f64 * 2700.0
    } else if is_collection {
        films.unwrap_or(4) as f64 * 6300.0
    } else {
        6300.0
    };
    let mbps = (bits / (seconds * 1_000_000.0)) as f32;
    // Math.round(float) в Java — floor(x + 0.5).
    (mbps * 10.0 + 0.5).floor() / 10.0
}

/// Название с трекера → читаемое: название (и второе, и год / сезон), без кодеков, рипа, звука и `|`.
pub fn clean_release_title(raw: &str) -> String {
    let mut s = raw.trim().to_string();
    if s.is_empty() {
        return String::new();
    }
    let spaces = s.chars().filter(|c| *c == ' ').count();
    let dots = s.chars().filter(|c| *c == '.').count();
    if dots > 3 && dots > spaces * 2 {
        s = s.replace('.', " ");
    }
    let clean = match u!(TECH_SPLIT).find(&s).ok().flatten() {
        Some(m) if kt_len(&s[..m.start()]) > 3 => s[..m.start()].trim().to_string(),
        _ => s.clone(),
    };
    let mut result = crate::jrx::replace(rx!(TRAILING_PUNCTUATION), &clean, "").trim().to_string();
    if result.ends_with('(') && !result.contains(')') {
        result.pop();
        result = result.trim().to_string();
    }
    if result.ends_with('[') && !result.contains(']') {
        result.pop();
        result = result.trim().to_string();
    }
    if result.trim().is_empty() {
        raw.trim().to_string()
    } else {
        result
    }
}

#[cfg(test)]
#[path = "parser_tests.rs"]
mod tests;

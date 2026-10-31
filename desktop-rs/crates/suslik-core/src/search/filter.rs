//! Фильтры, пресеты и сортировка находок. Повторяет `SearchFilter.kt`.

use super::hit::SearchHit;
use super::kt_len;
use super::parser::is_trailer_or_teaser;
use super::release::*;
use crate::jrx::{caps, group, replace};
use crate::rx;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum FilterPreset {
    #[default]
    All,
    Mobile,
    Balanced,
    MaxQuality,
}

impl FilterPreset {
    pub const ALL: [FilterPreset; 4] = [Self::All, Self::Mobile, Self::Balanced, Self::MaxQuality];
    pub fn title(self) -> &'static str {
        match self {
            Self::All => "Все",
            Self::Mobile => "📱 Мобильная сеть",
            Self::Balanced => "Оптимальные (1080p)",
            Self::MaxQuality => "Макс. качество",
        }
    }
    pub fn description(self) -> &'static str {
        match self {
            Self::All => "Без ограничений по сети и качеству",
            Self::Mobile => "До 4 ГБ, битрейт до 4.5 Мбит/с, без 4K/Remux/экранка",
            Self::Balanced => "До 12 ГБ, 1080p/720p, проверенное качество",
            Self::MaxQuality => "4K, BDRemux, Dolby Vision / HDR",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum SearchSort {
    #[default]
    Default,
    SizeAsc,
    SizeDesc,
    SeedsDesc,
    BitrateAsc,
}

impl SearchSort {
    pub const ALL: [SearchSort; 5] = [Self::Default, Self::SizeAsc, Self::SizeDesc, Self::SeedsDesc, Self::BitrateAsc];
    pub fn title(self) -> &'static str {
        match self {
            Self::Default => "По умолчанию",
            Self::SizeAsc => "Сначала лёгкие (экономия трафика)",
            Self::SizeDesc => "Сначала тяжёлые",
            Self::SeedsDesc => "По сидам (быстрая загрузка)",
            Self::BitrateAsc => "По битрейту (сначала низкий)",
        }
    }
}

const MEDIA_STOP_WORDS: &str = r"\b(?:фильм[ыа-я]*|кино(?:фильм[ыа-я]*)?|сериал[ыа-я]*|мульт(?:фильм[ыа-я]*|ик[иа-я]*)?|аниме|смотреть|скачать|онлайн|в\s*хорошем\s*качестве)\b";

/// Убирает из запроса шум: «фильмы», «сериал», «скачать», «смотреть»…
pub fn clean_query(raw: &str) -> String {
    let s = raw.trim();
    let stripped = replace(rx!(MEDIA_STOP_WORDS, unicode), s, " ");
    let stripped = replace(rx!(r"\s+"), &stripped, " ").trim().to_string();
    if kt_len(&stripped) >= 2 {
        stripped
    } else {
        s.to_string()
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct SearchFilterCriteria {
    pub query: String,
    /// "video", "movie", "series", "cartoon", "all".
    pub kind: String,
    pub preset: FilterPreset,
    pub resolution: Option<Resolution>,
    pub codec: Option<VideoCodec>,
    pub max_size_bytes: Option<i64>,
    pub only_seeded: bool,
    /// Только раздачи русскоязычных трекеров (`SearchHit::ru`).
    pub only_ru: bool,
    pub hide_cam: bool,
    pub refine: String,
    pub sort: SearchSort,
}

impl Default for SearchFilterCriteria {
    fn default() -> Self {
        Self {
            query: String::new(),
            kind: "video".into(),
            preset: FilterPreset::All,
            resolution: None,
            codec: None,
            max_size_bytes: None,
            only_seeded: false,
            only_ru: false,
            hide_cam: false,
            refine: String::new(),
            sort: SearchSort::Default,
        }
    }
}

const GB: i64 = 1024 * 1024 * 1024;

impl SearchFilterCriteria {
    pub fn matches(&self, hit: &SearchHit) -> bool {
        let r = &hit.release;
        if is_trailer_or_teaser(&hit.title) || is_trailer_or_teaser(&r.clean_title) {
            return false;
        }
        let kind_ok = match self.kind.as_str() {
            "all" => true,
            "video" => hit.kind != "other",
            k => hit.kind == k,
        };
        if !kind_ok || (self.only_seeded && hit.seeders <= 0) || (self.only_ru && !hit.ru) {
            return false;
        }
        if (self.hide_cam || matches!(self.preset, FilterPreset::Mobile | FilterPreset::Balanced)) && r.is_cam {
            return false;
        }
        match self.preset {
            FilterPreset::Mobile => {
                if !r.is_mobile_friendly() {
                    return false;
                }
                let multi = hit.kind == "series" || r.episodes_count.is_some() || r.is_collection;
                // Один фильм больше 4 ГБ и битрейтом выше 4,5 Мбит/с съест мобильный трафик.
                if !multi && hit.size > 4 * GB && r.estimated_bitrate_mbps > 4.5 {
                    return false;
                }
            }
            FilterPreset::Balanced => {
                if r.resolution == Resolution::Uhd4k || hit.size > 14 * GB {
                    return false;
                }
            }
            FilterPreset::MaxQuality => {
                let high = r.resolution == Resolution::Uhd4k
                    || r.rip_type == RipType::Remux
                    || r.rip_type == RipType::Bluray
                    || r.hdr != HdrType::None;
                if !high {
                    return false;
                }
            }
            FilterPreset::All => {}
        }
        if self.resolution.is_some_and(|x| x != Resolution::Unknown && r.resolution != x) {
            return false;
        }
        if self.codec.is_some_and(|x| x != VideoCodec::Unknown && r.codec != x) {
            return false;
        }
        if self.max_size_bytes.is_some_and(|m| hit.size > m) {
            return false;
        }
        if !self.refine.trim().is_empty() {
            let t1 = hit.title.to_lowercase();
            let t2 = r.clean_title.to_lowercase();
            let refine = self.refine.to_lowercase();
            if !refine.split(' ').filter(|w| !w.trim().is_empty()).all(|w| t1.contains(w) || t2.contains(w)) {
                return false;
            }
        }
        true
    }

    pub fn filter(&self, hits: &[SearchHit]) -> Vec<SearchHit> {
        let mut out: Vec<SearchHit> = hits.iter().filter(|h| self.matches(h)).cloned().collect();
        match self.sort {
            SearchSort::Default => {
                if self.query.trim().is_empty() {
                    out.sort_by_key(|h| std::cmp::Reverse(h.seeders));
                } else {
                    let mut scored: Vec<(i32, SearchHit)> =
                        out.into_iter().map(|h| (relevance_score(&h, &self.query), h)).collect();
                    scored.sort_by(|(sa, a), (sb, b)| sb.cmp(sa).then_with(|| b.seeders.cmp(&a.seeders)));
                    out = scored.into_iter().map(|(_, h)| h).collect();
                }
            }
            SearchSort::SizeAsc => out.sort_by_key(|h| (if h.size <= 0 { i64::MAX } else { h.size }, -h.seeders)),
            SearchSort::SizeDesc => out.sort_by_key(|h| std::cmp::Reverse(h.size)),
            SearchSort::SeedsDesc => out.sort_by_key(|h| std::cmp::Reverse(h.seeders)),
            SearchSort::BitrateAsc => {
                let rate = |h: &SearchHit| {
                    let b = h.release.estimated_bitrate_mbps;
                    if b <= 0.0 {
                        f32::MAX
                    } else {
                        b
                    }
                };
                out.sort_by(|a, b| rate(a).total_cmp(&rate(b)).then_with(|| b.seeders.cmp(&a.seeders)));
            }
        }
        out
    }
}

/// Насколько находка подходит к запросу: целая фраза, все слова, их порядок, год, сиды.
pub fn relevance_score(hit: &SearchHit, query: &str) -> i32 {
    if query.trim().is_empty() {
        return hit.seeders;
    }
    let norm_query = query.trim().to_lowercase().replace('-', " ");
    let words: Vec<&str> =
        rx!("[^a-zA-Z0-9а-яА-ЯёЁ]+").split(&norm_query).filter_map(Result::ok).filter(|w| kt_len(w) >= 2).collect();
    if words.is_empty() {
        return hit.seeders;
    }
    let title = hit.title.to_lowercase().replace('-', " ");
    let clean = hit.release.clean_title.to_lowercase().replace('-', " ");
    let mut score = 0;

    if clean.starts_with(&norm_query) {
        score += 2000;
    } else if title.starts_with(&norm_query) {
        score += 1600;
    } else if clean.contains(&norm_query) {
        score += 1000;
    } else if title.contains(&norm_query) {
        score += 700;
    }

    let in_clean = words.iter().filter(|w| clean.contains(*w)).count();
    let in_title = words.iter().filter(|w| title.contains(*w)).count();
    let max_matched = in_clean.max(in_title);
    let all = max_matched == words.len();
    score += if all { 500 } else { max_matched as i32 * 80 };

    let mut last: Option<usize> = None;
    let mut in_order = true;
    for w in &words {
        match clean.find(w) {
            Some(i) if last.is_none_or(|l| i >= l) => last = Some(i),
            _ => {
                in_order = false;
                break;
            }
        }
    }
    if in_order && all {
        score += 350;
    }

    if !clean.trim().is_empty() && all {
        let diff = kt_len(&clean).abs_diff(kt_len(&norm_query));
        if diff < 15 {
            score += 200;
        } else if diff < 30 {
            score += 100;
        }
    }

    if let Some(c) = caps(rx!(r"\b(19\d{2}|20\d{2})\b"), &norm_query) {
        let y = group(&c, 1);
        if let Ok(target) = y.parse::<i32>() {
            if hit.release.year == Some(target) || clean.contains(y) || title.contains(y) {
                score += 1500;
            } else if hit.release.year.is_some_and(|ry| ry != target) {
                score -= 1500;
            }
        }
    }

    if hit.seeders > 0 {
        score += hit.seeders.min(50) * 2;
    } else {
        score -= 250;
    }
    score += match hit.release.resolution {
        Resolution::Uhd4k | Resolution::Fhd1080p => 40,
        Resolution::Hd720p => 20,
        _ => 0,
    };
    if hit.release.is_cam {
        score -= 300;
    }
    score
}

#[cfg(test)]
#[path = "filter_tests.rs"]
mod tests;

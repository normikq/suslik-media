//! `LibraryKeep.kt`: что ещё принадлежит медиатеке после обновления. Всё, что приложения хранят о фильмах, которых в ней
//! больше нет (постеры, строка о видео, прогресс, последняя серия, запас на диске), можно удалить. Ключи у обоих приложений
//! одни:
//!
//! - `magnetId|path` (прогресс, сведения о видео), `w:magnetId|path` (место плеера), `magnetId` (последняя серия);
//! - постеры — по запросу плитки в нижнем регистре (`LibraryItem::poster_queries`);
//! - запас — по ссылке AllDebrid файла.
//!
//! Ключ другого вида (без номера раздачи, например поток по простой ссылке) сохраняется.

use crate::model::LibraryItem;
use std::collections::HashSet;

pub struct LibraryKeep {
    pub ids: HashSet<i64>,
    pub poster_keys: HashSet<String>,
    pub links: HashSet<String>,
}

impl LibraryKeep {
    pub fn new(items: &[LibraryItem]) -> Self {
        Self {
            ids: items.iter().map(|i| i.m.id).collect(),
            poster_keys: items.iter().flat_map(|i| i.poster_queries.iter().map(|q| q.to_lowercase())).collect(),
            links: items.iter().flat_map(|i| i.m.files.iter().filter_map(|f| f.link.clone())).collect(),
        }
    }

    /// Пустому списку чистку не доверяем: неудачный или неполный ответ не должен стереть всё.
    pub fn usable(&self) -> bool {
        !self.ids.is_empty()
    }

    /// Ключ фильма (`magnetId|path`, `w:magnetId|path`, `magnetId`): оставить?
    pub fn keeps_key(&self, key: &str) -> bool {
        let rest = key.strip_prefix("w:").unwrap_or(key);
        let id = rest.split('|').next().unwrap_or("");
        match id.parse::<i64>() {
            Ok(id) => self.ids.contains(&id),
            Err(_) => true,
        }
    }

    /// Ключ постера (запрос в нижнем регистре): оставить?
    pub fn keeps_poster(&self, key: &str) -> bool {
        self.poster_keys.contains(key)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{LibraryItem, MFile, Magnet};

    fn file(path: &str, link: &str) -> MFile {
        MFile {
            index: 0,
            name: path.rsplit('/').next().unwrap().into(),
            path: path.into(),
            is_folder: false,
            size: 1,
            link: Some(link.into()),
        }
    }

    #[test]
    fn keeps_what_is_still_in_the_library() {
        let m = Magnet::new(7, "Dune.2021.2160p", 4, vec![file("Dune.2021.2160p.mkv", "https://alld/a")]);
        let keep = LibraryKeep::new(&[LibraryItem::of(m)]);
        assert!(keep.usable());
        assert!(keep.keeps_key("7|Dune.2021.2160p.mkv"));
        assert!(keep.keeps_key("w:7|Dune.2021.2160p.mkv"));
        assert!(keep.keeps_key("7"));
        assert!(!keep.keeps_key("8|Other.mkv"));
        assert!(!keep.keeps_key("w:8|Other.mkv"));
        assert!(keep.keeps_key("https://example/stream.m3u8"), "не наш вид ключа — сохраняем");
        assert!(keep.links.contains("https://alld/a"));
        let q = LibraryItem::of(Magnet::new(7, "Dune.2021.2160p", 4, vec![])).poster_queries[0].to_lowercase();
        assert!(keep.keeps_poster(&q));
        assert!(!keep.keeps_poster("другой фильм"));
    }

    #[test]
    fn empty_list_is_not_trusted() {
        assert!(!LibraryKeep::new(&[]).usable());
    }
}

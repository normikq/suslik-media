//! `torrent/TorrentInspector.kt`: есть ли в раздаче видео. Общая проверка для ПК и Android (сайт повторяет её в
//! `index.html`): `.torrent` разбирается до отправки, у магнета известно только имя `dn`.

use std::collections::HashMap;

pub const MAX_TORRENT_FILE_SIZE: usize = 10 * 1024 * 1024;

pub const VIDEO_EXTENSIONS: [&str; 17] = [
    "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mts", "wmv", "vob", "mpg", "mpeg", "flv", "3gp", "ogv",
    "asf",
];

pub const NON_VIDEO_EXTENSIONS: [&str; 28] = [
    "exe", "msi", "apk", "pkg", "deb", "rpm", "bin", "cue", "dmg", "zip", "rar", "7z", "tar", "gz", "cbr", "cbz",
    "pdf", "epub", "fb2", "mp3", "flac", "wav", "ape", "aac", "ogg", "wma", "doc", "docx",
];

/// `substringAfterLast('.', "")`.
fn after_last_dot(s: &str) -> &str {
    s.rsplit_once('.').map(|(_, e)| e).unwrap_or("")
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TorrentFileInfo {
    pub path: String,
    pub size: i64,
}

impl TorrentFileInfo {
    pub fn extension(&self) -> String {
        after_last_dot(&self.path).to_lowercase()
    }
    pub fn is_video(&self) -> bool {
        VIDEO_EXTENSIONS.contains(&self.extension().as_str())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TorrentInspection {
    pub files: Vec<TorrentFileInfo>,
    pub total_size: i64,
    pub video_size: i64,
    pub has_video: bool,
    pub video_count: usize,
    pub non_video_extensions: Vec<String>,
    pub is_parsed_successfully: bool,
}

pub fn is_video_file_name(name: &str) -> bool {
    VIDEO_EXTENSIONS.contains(&after_last_dot(name).to_lowercase().as_str())
}

/// Текст отказа или `None`, если видео есть либо судить не по чему (пустой список).
pub fn rejection_for_paths(paths: &[String]) -> Option<String> {
    if paths.is_empty() || paths.iter().any(|p| is_video_file_name(p)) {
        return None;
    }
    let mut exts: Vec<String> = Vec::new();
    for p in paths {
        let name = p.rsplit_once('/').map(|(_, n)| n).unwrap_or(p);
        let e = after_last_dot(name).to_lowercase();
        if !e.trim().is_empty() && e.chars().count() <= 5 && !exts.contains(&e) {
            exts.push(e);
        }
    }
    Some(format!("Отклонено: в раздаче нет видеофайлов{}", exts_note(&exts)))
}

/// `.torrent`: список файлов читается из самого файла, до отправки на сервер.
pub fn rejection_for_torrent(bytes: &[u8]) -> Option<String> {
    let paths: Vec<String> = inspect(bytes).files.into_iter().map(|f| f.path).collect();
    rejection_for_paths(&paths)
}

/// Магнет: до готовности известно только имя `dn` — отказ, если у него расширение не видео.
pub fn rejection_for_magnet_name(dn: &str) -> Option<String> {
    let ext = after_last_dot(dn).to_lowercase();
    NON_VIDEO_EXTENSIONS.contains(&ext.as_str()).then(|| format!("Отклонено: не является видеофайлом (.{ext})"))
}

fn exts_note(exts: &[String]) -> String {
    if exts.is_empty() {
        String::new()
    } else {
        let list: Vec<String> = exts.iter().take(4).map(|e| format!(".{e}")).collect();
        format!(" (найдены: {})", list.join(", "))
    }
}

pub fn inspect(bytes: &[u8]) -> TorrentInspection {
    let files = if bytes.is_empty() || bytes.len() > MAX_TORRENT_FILE_SIZE { None } else { extract(bytes) };
    let Some(files) = files else {
        return TorrentInspection {
            files: Vec::new(),
            total_size: 0,
            video_size: 0,
            has_video: false,
            video_count: 0,
            non_video_extensions: Vec::new(),
            is_parsed_successfully: false,
        };
    };
    let video: Vec<&TorrentFileInfo> = files.iter().filter(|f| f.is_video()).collect();
    let mut non_video: Vec<String> = Vec::new();
    for f in files.iter().filter(|f| !f.is_video() && !f.extension().trim().is_empty()) {
        if !non_video.contains(&f.extension()) {
            non_video.push(f.extension());
        }
    }
    TorrentInspection {
        total_size: files.iter().map(|f| f.size).sum(),
        video_size: video.iter().map(|f| f.size).sum(),
        has_video: !video.is_empty(),
        video_count: video.len(),
        non_video_extensions: non_video,
        is_parsed_successfully: true,
        files,
    }
}

pub fn extract_files_from_torrent_bytes(bytes: &[u8]) -> Vec<TorrentFileInfo> {
    if bytes.is_empty() || bytes.len() > MAX_TORRENT_FILE_SIZE {
        return Vec::new();
    }
    extract(bytes).unwrap_or_default()
}

enum B {
    Int(i64),
    Bytes(Vec<u8>),
    List(Vec<B>),
    Dict(HashMap<String, B>),
}

fn text(b: &B) -> Option<String> {
    match b {
        B::Bytes(v) => Some(String::from_utf8_lossy(v).into_owned()),
        _ => None,
    }
}

fn extract(bytes: &[u8]) -> Option<Vec<TorrentFileInfo>> {
    let mut p = Parser { bytes, pos: 0 };
    let Some(B::Dict(root)) = p.parse(0)? else { return None };
    let Some(B::Dict(info)) = root.get("info") else { return None };
    let mut out = Vec::new();
    if let Some(B::List(files)) = info.get("files") {
        for item in files {
            let B::Dict(d) = item else { continue };
            let length = match d.get("length") {
                Some(B::Int(n)) => *n,
                _ => 0,
            };
            let parts = match d.get("path.utf-8").or_else(|| d.get("path")) {
                Some(B::List(l)) => l,
                _ => continue,
            };
            let path: Vec<String> = parts.iter().filter_map(text).collect();
            let path = path.join("/");
            if !path.trim().is_empty() {
                out.push(TorrentFileInfo { path, size: length });
            }
        }
    } else {
        let name =
            info.get("name.utf-8").or_else(|| info.get("name")).and_then(text).unwrap_or_else(|| "unknown".into());
        let length = match info.get("length") {
            Some(B::Int(n)) => *n,
            _ => 0,
        };
        out.push(TorrentFileInfo { path: name, size: length });
    }
    Some(out)
}

struct Parser<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl Parser<'_> {
    /// `Ok(None)` — как `null` у Kotlin (конец или незнакомый байт); `Err` — исключение (разбор не удался).
    fn parse(&mut self, depth: usize) -> Option<Option<B>> {
        if depth > 32 {
            return None;
        }
        let Some(&c) = self.bytes.get(self.pos) else { return Some(None) };
        Some(match c {
            b'i' => Some(self.int()),
            b'l' => Some(self.list(depth + 1)?),
            b'd' => Some(self.dict(depth + 1)?),
            b'0'..=b'9' => Some(B::Bytes(self.byte_string())),
            _ => None,
        })
    }

    fn until(&mut self, stop: u8) -> String {
        let start = self.pos;
        while self.pos < self.bytes.len() && self.bytes[self.pos] != stop {
            self.pos += 1;
        }
        let s = String::from_utf8_lossy(&self.bytes[start..self.pos]).into_owned();
        self.pos += 1;
        s
    }

    fn int(&mut self) -> B {
        self.pos += 1;
        B::Int(self.until(b'e').parse().unwrap_or(0))
    }

    fn byte_string(&mut self) -> Vec<u8> {
        let len: usize = self.until(b':').parse().unwrap_or(0);
        let start = self.pos.min(self.bytes.len());
        let end = (start + len).min(self.bytes.len());
        self.pos = end;
        self.bytes[start..end].to_vec()
    }

    fn list(&mut self, depth: usize) -> Option<B> {
        self.pos += 1;
        let mut out = Vec::new();
        while self.pos < self.bytes.len() {
            if self.bytes[self.pos] == b'e' {
                self.pos += 1;
                return Some(B::List(out));
            }
            match self.parse(depth)? {
                Some(item) => out.push(item),
                None => break,
            }
        }
        None // незакрытый список
    }

    fn dict(&mut self, depth: usize) -> Option<B> {
        self.pos += 1;
        let mut out = HashMap::new();
        while self.pos < self.bytes.len() {
            if self.bytes[self.pos] == b'e' {
                self.pos += 1;
                return Some(B::Dict(out));
            }
            let Some(B::Bytes(key)) = self.parse(depth)? else { break };
            let key = String::from_utf8_lossy(&key).into_owned();
            let value = self.parse(depth)?;
            if let Some(v) = value {
                out.insert(key, v);
            }
        }
        None // незакрытый словарь
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(p: &[&str]) -> Vec<String> {
        p.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn video_in_list_ok() {
        assert_eq!(rejection_for_paths(&v(&["Film/readme.txt", "Film/movie.MKV"])), None);
    }

    #[test]
    fn no_video_rejected_with_extensions() {
        let r = rejection_for_paths(&v(&["Game/setup.exe", "Game/data.bin", "Game/readme.txt"])).unwrap();
        assert!(r.contains(".exe") && r.contains(".bin") && r.contains(".txt"));
        assert_eq!(r, "Отклонено: в раздаче нет видеофайлов (найдены: .exe, .bin, .txt)");
    }

    #[test]
    fn empty_list_not_judged() {
        assert_eq!(rejection_for_paths(&[]), None);
    }

    #[test]
    fn torrent_bytes() {
        let game = b"d4:infod4:name4:game5:filesld6:lengthi9e4:pathl9:setup.exeeeeee";
        assert!(rejection_for_torrent(game).is_some());
        let film = b"d4:infod6:lengthi1e4:name9:video.mkvee";
        assert_eq!(rejection_for_torrent(film), None);
        assert_eq!(rejection_for_torrent("мусор".as_bytes()), None); // не разобрали — не мешаем
        let i = inspect(film);
        assert!(i.is_parsed_successfully && i.has_video);
        assert_eq!((i.video_count, i.total_size), (1, 1));
    }

    #[test]
    fn magnet_name() {
        assert_eq!(
            rejection_for_magnet_name("setup.exe").as_deref(),
            Some("Отклонено: не является видеофайлом (.exe)")
        );
        assert_eq!(rejection_for_magnet_name("Some.Movie.2020.1080p"), None);
    }
}

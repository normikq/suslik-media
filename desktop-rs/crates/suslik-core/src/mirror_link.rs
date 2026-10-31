//! Подписанный адрес зеркала: `https://<site>/api/file/proxy/<name>?link=…&exp=…&sig=…`. Подпись живёт 12 часов,
//! потом зеркало отвечает 401 и нужен новый адрес. Повторяет `MirrorLink.kt`.

use crate::url_codec;
use std::time::{SystemTime, UNIX_EPOCH};

fn query(url: &str) -> &str {
    url.find('?').map(|i| &url[i + 1..]).unwrap_or("")
}

fn before_query(url: &str) -> &str {
    url.split('?').next().unwrap_or(url)
}

/// Ключ файла: у одного фильма каждый раз новый подписанный адрес, поэтому ключ — путь и параметр `link`.
/// Другие адреса — как есть.
pub fn file_key(url: &str) -> String {
    match query(url).split('&').find(|p| p.starts_with("link=")) {
        Some(link) => format!("{}?{}", before_query(url), link),
        None => url.to_string(),
    }
}

/// Параметр `name` адреса `url`, раскодированный; `None`, если его нет.
pub fn param(url: &str, name: &str) -> Option<String> {
    let prefix = format!("{name}=");
    let raw = query(url).split('&').find(|p| p.starts_with(&prefix))?;
    url_codec::decode(&raw[prefix.len()..])
}

/// Когда кончается подпись (секунды unix); `None` для адреса без неё.
pub fn expires_at(url: &str) -> Option<i64> {
    param(url, "exp")?.parse().ok()
}

pub fn now_sec() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

/// Подпись кончилась или кончится в ближайшие `margin_sec`. Адрес без подписи не истекает.
pub fn is_expired(url: &str, now_sec: i64, margin_sec: i64) -> bool {
    expires_at(url).is_some_and(|e| e <= now_sec + margin_sec)
}

/// Имя файла в пути (его сервер ставит в Content-Disposition).
pub fn file_name(url: &str) -> String {
    let last = before_query(url).rsplit('/').next().unwrap_or("");
    url_codec::decode(last).unwrap_or_else(|| last.to_string())
}

/// Путь API, который заново подписывает файл (`/api/file/mirror-url`, нужен вход); `None` без `link`.
pub fn renew_path(url: &str) -> Option<String> {
    let link = param(url, "link").filter(|l| !l.trim().is_empty())?;
    Some(format!("/api/file/mirror-url?link={}&name={}", url_codec::encode(&link), url_codec::encode(&file_name(url))))
}

#[cfg(test)]
mod tests {
    use super::*;

    const URL: &str = "https://media.test/api/file/proxy/Show%20S01E02.mkv?link=https%3A%2F%2Falldebrid.com%2Ff%2Fabc&exp=1000&sig=ff";

    #[test]
    fn reads_the_signed_address() {
        assert_eq!(param(URL, "link").as_deref(), Some("https://alldebrid.com/f/abc"));
        assert_eq!(expires_at(URL), Some(1000));
        assert_eq!(file_name(URL), "Show S01E02.mkv");
        assert_eq!(
            file_key(URL),
            "https://media.test/api/file/proxy/Show%20S01E02.mkv?link=https%3A%2F%2Falldebrid.com%2Ff%2Fabc"
        );
    }

    #[test]
    fn expires_with_a_margin() {
        assert!(is_expired(URL, 1000, 60));
        assert!(is_expired(URL, 950, 60));
        assert!(!is_expired(URL, 900, 60));
        assert!(!is_expired("https://media.test/film.mkv", 5_000, 60));
    }

    #[test]
    fn renew_path_asks_for_the_same_file() {
        assert_eq!(
            renew_path(URL).as_deref(),
            Some("/api/file/mirror-url?link=https%3A%2F%2Falldebrid.com%2Ff%2Fabc&name=Show+S01E02.mkv")
        );
        assert_eq!(renew_path("https://media.test/film.mkv?exp=1"), None);
    }
}

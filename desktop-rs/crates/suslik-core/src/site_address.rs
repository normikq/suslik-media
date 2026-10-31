//! Адрес сервера из одной настройки сборки (секрет `SITE_URL`): домен и, если сервер не в корне, путь —
//! `https://example.org` или `https://example.org/suslik`. Повторяет `SiteAddress.kt`.

use crate::url_codec;

/// `https://host[/path]` без `/` в конце; пусто, если адреса нет. Без схемы — `https://`; запрос и `#` отбрасываются.
/// `http://` остаётся только для отладки на своём ПК (`localhost`, `127.0.0.1`).
pub fn normalize(raw: Option<&str>) -> String {
    let s = raw.unwrap_or("").trim();
    let s = s.split('#').next().unwrap_or("");
    let mut s = s.split('?').next().unwrap_or("");
    let lower = s.to_ascii_lowercase();
    let scheme = if lower.starts_with("https://") {
        s = &s[8..];
        "https"
    } else if lower.starts_with("http://") {
        s = &s[7..];
        "http"
    } else {
        "https"
    };
    let host_raw = s.split('/').next().unwrap_or("");
    let host = host_raw.to_lowercase();
    if host.is_empty() || host.chars().any(|c| c.is_whitespace() || c == '@' || c == '\\') {
        return String::new();
    }
    let mut path = String::new();
    for c in s[host_raw.len()..].chars() {
        if c == '/' && path.ends_with('/') {
            continue;
        }
        path.push(c);
    }
    let path = path.trim_end_matches('/');
    let bare = host.split(':').next().unwrap_or("");
    let local = bare == "localhost" || bare == "127.0.0.1";
    let scheme = if scheme == "http" && local { "http" } else { "https" };
    format!("{scheme}://{host}{path}")
}

fn after_scheme(base: &str) -> &str {
    base.find("://").map(|i| &base[i + 3..]).unwrap_or("")
}

/// Домен (с портом, если он есть) из [`normalize`]-адреса: `example.org`.
pub fn host(base: &str) -> &str {
    let r = after_scheme(base);
    r.split('/').next().unwrap_or("")
}

/// Путь сервера без `/` в конце: `""` в корне, `/suslik` под путём.
pub fn path(base: &str) -> &str {
    let r = after_scheme(base);
    r.find('/').map(|i| &r[i..]).unwrap_or("")
}

/// Адрес на нашем сервере: тот же домен и внутри пути `base`. Сюда (и только сюда) идут cookie сеанса, токен
/// устройства и бета-код. Схема и домен — без учёта регистра, путь — с учётом; адрес с `.`/`..` в пути
/// (и в виде `%2e`, `%2f`) — не наш.
pub fn is_own(url: &str, base: &str) -> bool {
    if base.is_empty() {
        return false;
    }
    let (Some(bi), Some(ui)) = (base.find("://"), url.find("://")) else { return false };
    let (b_scheme, u_scheme) = (&base[..bi], &url[..ui]);
    if b_scheme.is_empty() || !u_scheme.eq_ignore_ascii_case(b_scheme) {
        return false;
    }
    let rest = &url[ui + 3..];
    let host_end = rest.find(['/', '?', '#', '\\']).unwrap_or(rest.len());
    if !rest[..host_end].eq_ignore_ascii_case(host(base)) {
        return false;
    }
    let raw_path = &rest[host_end..];
    let raw_path = raw_path.split('#').next().unwrap_or("");
    let raw_path = raw_path.split('?').next().unwrap_or("");
    let Some(decoded) = url_codec::decode(&raw_path.replace('+', "%2B")) else { return false };
    if decoded.split(['/', '\\']).any(|p| p == "." || p == "..") {
        return false;
    }
    let own = path(base);
    raw_path == own || raw_path.starts_with(&format!("{own}/"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_one_setting() {
        let n = |s: &str| normalize(Some(s));
        assert_eq!(n("https://site.test"), "https://site.test");
        assert_eq!(n(" https://Site.Test/ "), "https://site.test");
        assert_eq!(n("site.test"), "https://site.test");
        assert_eq!(n("https://site.test/suslik/"), "https://site.test/suslik");
        assert_eq!(n("https://site.test//a/b//?x=1#y"), "https://site.test/a/b");
        assert_eq!(n("http://site.test"), "https://site.test");
        assert_eq!(n("http://localhost:3000/"), "http://localhost:3000");
        assert_eq!(n(""), "");
        assert_eq!(normalize(None), "");
        assert_eq!(n("https://"), "");
        assert_eq!(n("https://user@site.test"), "");
    }

    #[test]
    fn host_and_path() {
        assert_eq!(host("https://site.test/suslik"), "site.test");
        assert_eq!(path("https://site.test/suslik"), "/suslik");
        assert_eq!(path("https://site.test"), "");
        assert_eq!(host("http://localhost:3000"), "localhost:3000");
    }

    #[test]
    fn own_server_is_inside_the_path() {
        let base = "https://site.test/suslik";
        assert!(is_own(&format!("{base}/api/library"), base));
        assert!(is_own(base, base));
        assert!(!is_own("https://site.test/other/api", base));
        assert!(!is_own("https://site.test/suslik-evil/x", base));
        assert!(!is_own("https://site.test.evil.com/suslik/x", base));
        assert!(!is_own("https://site.test/x", ""));
        assert!(is_own("https://site.test/api/x", "https://site.test"));
        assert!(is_own("https://site.test", "https://site.test"));
        assert!(is_own(&format!("{base}?x=1"), base));
        assert!(is_own(&format!("{base}/api/file/proxy/Film.Name..2024.mkv?link=a"), base));
    }

    #[test]
    fn path_cannot_leave_the_base() {
        let base = "https://site.test/suslik";
        for tail in [
            "/../other/api",
            "/api/..",
            "/./../other",
            "/%2e%2e/other",
            "/%2E./other",
            "/a%2f..%2fb",
            "/..\\other",
            "/%zz",
        ] {
            assert!(!is_own(&format!("{base}{tail}"), base), "{tail}");
        }
    }

    #[test]
    fn scheme_and_host_ignore_case() {
        let base = "https://site.test/suslik";
        assert!(is_own("HTTPS://Site.Test/suslik/api", base));
        assert!(!is_own("https://site.test/Suslik/api", base));
        assert!(!is_own("http://site.test/suslik/api", base));
        assert!(!is_own("https://site.test@evil.test/suslik/api", base));
        assert!(!is_own("https://evil.test/x?https://site.test/suslik/", base));
        assert!(!is_own("https://site.test:8443/suslik/api", base));
        assert!(!is_own("site.test/suslik/api", base));
    }
}

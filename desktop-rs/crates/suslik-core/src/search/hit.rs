//! Находка поиска, одинаковая на всех платформах. Повторяет `SearchHit.kt`.

use super::parser;
use super::release::ReleaseInfo;
use crate::json::{opt_array, opt_bool, opt_i32, opt_i64, opt_str};
use serde_json::Value;

#[derive(Debug, Clone, PartialEq)]
pub struct SearchHit {
    pub id: String,
    pub title: String,
    pub size: i64,
    pub seeders: i32,
    pub leechers: i32,
    pub ru: bool,
    pub kind: String,
    pub source: String,
    pub date: String,
    pub magnet: Option<String>,
    pub topic_id: Option<String>,
    pub local_tracker_id: Option<String>,
    pub release: ReleaseInfo,
}

impl SearchHit {
    /// Находка с разбором названия, как конструктор Kotlin со значением `release` по умолчанию.
    pub fn new(id: &str, title: &str, size: i64, seeders: i32, ru: bool, kind: &str, source: &str) -> Self {
        Self {
            id: id.into(),
            title: title.into(),
            size,
            seeders,
            leechers: 0,
            ru,
            kind: kind.into(),
            source: source.into(),
            date: String::new(),
            magnet: None,
            topic_id: None,
            local_tracker_id: None,
            release: parser::parse(title, size, kind),
        }
    }

    /// `format_date` — дата из мс; без неё «дд.мм.гггг» по UTC.
    pub fn from_json(r: &Value, format_date: Option<&dyn Fn(i64) -> String>) -> Option<Self> {
        let id = opt_str(r, "id");
        if id.trim().is_empty() {
            return None;
        }
        let source = opt_array(r, "sources")
            .map(|a| {
                a.iter().map(|v| v.as_str().map(String::from).unwrap_or_else(|| v.to_string())).collect::<Vec<_>>()
            })
            .filter(|v| v.len() > 1)
            .map(|v| v.join(" + "))
            .unwrap_or_else(|| opt_str(r, "provider"));
        let date = match r.get("date") {
            Some(Value::Number(n)) => {
                let ms = n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)).unwrap_or(0);
                if ms > 0 {
                    format_date.map(|f| f(ms)).unwrap_or_else(|| date_utc(ms))
                } else {
                    String::new()
                }
            }
            Some(Value::String(s)) => s.chars().take(10).collect(),
            _ => String::new(),
        };
        let title = opt_str(r, "title");
        let size = opt_i64(r, "size", 0);
        let kind = r.get("kind").filter(|v| !v.is_null()).map(|_| opt_str(r, "kind")).unwrap_or_else(|| "movie".into());
        let magnet = Some(opt_str(r, "magnet")).filter(|m| m.starts_with("magnet:?"));
        Some(Self {
            seeders: opt_i32(r, "seeders", 0),
            leechers: opt_i32(r, "leechers", 0),
            ru: opt_bool(r, "ru", false),
            source,
            date,
            magnet,
            release: parser::parse(&title, size, &kind),
            id,
            title,
            size,
            kind,
            topic_id: None,
            local_tracker_id: None,
        })
    }

    pub fn parse_hits(arr: Option<&Value>) -> Vec<Self> {
        arr.and_then(Value::as_array)
            .map(|a| a.iter().filter(|v| v.is_object()).filter_map(|v| Self::from_json(v, None)).collect())
            .unwrap_or_default()
    }

    pub fn parse_providers(arr: Option<&Value>) -> Vec<SearchProviderInfo> {
        arr.and_then(Value::as_array)
            .map(|a| {
                a.iter()
                    .filter(|v| v.is_object())
                    .map(|v| SearchProviderInfo {
                        name: opt_str(v, "name"),
                        ok: opt_bool(v, "ok", false),
                        count: opt_i32(v, "count", 0),
                        error: opt_str(v, "error"),
                    })
                    .collect()
            })
            .unwrap_or_default()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchProviderInfo {
    pub name: String,
    pub ok: bool,
    pub count: i32,
    pub error: String,
}

/// «дд.мм.гггг» по UTC (алгоритм civil_from_days).
fn date_utc(ms: i64) -> String {
    let days = ms.div_euclid(86_400_000);
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{d:02}.{m:02}.{y}")
}

#[cfg(test)]
mod tests {
    #[test]
    fn utc_date() {
        assert_eq!(super::date_utc(0), "01.01.1970");
        assert_eq!(super::date_utc(1_700_000_000_000), "14.11.2023");
    }
}

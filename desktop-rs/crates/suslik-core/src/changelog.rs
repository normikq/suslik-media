//! «Что нового»: `changelog.json` приложения (новые сверху) — разбор и что показать. Повторяет `Changelog.kt`.

use crate::versions;
use serde_json::Value;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Release {
    pub version: String,
    pub title: String,
    pub items: Vec<String>,
}

pub fn parse(text: &str) -> Vec<Release> {
    let Ok(Value::Array(arr)) = serde_json::from_str::<Value>(text) else { return Vec::new() };
    let s = |v: &Value, k: &str| v.get(k).and_then(Value::as_str).unwrap_or("").to_string();
    arr.iter()
        .filter(|r| r.is_object())
        .filter_map(|r| {
            let items: Vec<String> = r
                .get("items")
                .and_then(Value::as_array)
                .map(|a| {
                    a.iter().filter_map(Value::as_str).filter(|x| !x.trim().is_empty()).map(String::from).collect()
                })
                .unwrap_or_default();
            let rel = Release { version: s(r, "version"), title: s(r, "title"), items };
            (!rel.version.trim().is_empty() && !rel.items.is_empty()).then_some(rel)
        })
        .collect()
}

/// Последние `count` выпусков до установленной версии `current`, новые сверху.
pub fn up_to(all: &[Release], current: &str, count: usize) -> Vec<Release> {
    let mut v: Vec<Release> = all.iter().filter(|r| !versions::is_newer(&r.version, current)).cloned().collect();
    v.sort_by(|a, b| versions::compare(&b.version, &a.version));
    v.truncate(count);
    v
}

/// Показывать ли окно после обновления: только если есть заметки новее уже показанных (`last_seen`).
pub fn should_show(releases: &[Release], last_seen: Option<&str>) -> bool {
    match (releases.first(), last_seen) {
        (None, _) => false,
        (Some(_), None) => true,
        (Some(r), Some(seen)) => versions::is_newer(&r.version, seen),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const JSON: &str = r#"[
      {"version":"1.9.0","title":"A","items":["a1"]},
      {"version":"2.0.0","title":"B","items":["b1","b2"]},
      {"version":"1.8.0","title":"C","items":["c1"]},
      {"version":"1.7.0","title":"D","items":[]}
    ]"#;

    fn versions_of(v: &[Release]) -> Vec<&str> {
        v.iter().map(|r| r.version.as_str()).collect()
    }

    #[test]
    fn pick_and_order() {
        let all = parse(JSON);
        assert_eq!(all.len(), 3);
        assert_eq!(versions_of(&up_to(&all, "1.9.5", 3)), ["1.9.0", "1.8.0"]);
        assert_eq!(versions_of(&up_to(&all, "2.0.0", 3)), ["2.0.0", "1.9.0", "1.8.0"]);
    }

    #[test]
    fn show_only_when_new_notes() {
        let list = up_to(&parse(JSON), "1.9.5", 3);
        assert!(should_show(&list, None));
        assert!(should_show(&list, Some("1.8.0")));
        assert!(!should_show(&list, Some("1.9.0")));
        assert!(!should_show(&[], None));
    }

    #[test]
    fn broken_json() {
        assert!(parse("{oops").is_empty());
    }

    #[test]
    fn real_desktop_changelog_parses() {
        let text = include_str!("../../../../desktop/src/main/resources/changelog.json");
        assert!(!parse(text).is_empty());
    }
}

//! Строки интерфейса — тот же `strings/ru.json`, что у Kotlin-версии (плоские ключи, подстановки `{имя}`).

use serde_json::{Map, Value};
use std::sync::OnceLock;

const RU: &str = include_str!("../../../../desktop/src/main/resources/strings/ru.json");

fn base() -> &'static Map<String, Value> {
    static B: OnceLock<Map<String, Value>> = OnceLock::new();
    B.get_or_init(|| serde_json::from_str(RU).unwrap_or_default())
}

/// Текст по ключу: `tr("api.oshibka_servera_code", &[("code", "502")])` ← «Ошибка сервера ({code})».
pub fn tr(key: &str, params: &[(&str, &str)]) -> String {
    let mut s = base().get(key).and_then(Value::as_str).unwrap_or(key).to_string();
    for (name, value) in params {
        s = s.replace(&format!("{{{name}}}"), value);
    }
    s
}

#[cfg(test)]
mod tests {
    #[test]
    fn substitutes() {
        assert_eq!(super::tr("api.oshibka_servera_code", &[("code", "502")]), "Ошибка сервера (502)");
        assert_eq!(super::tr("no.such.key", &[]), "no.such.key");
    }
}

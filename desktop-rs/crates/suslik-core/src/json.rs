//! Чтение JSON так же снисходительно, как `org.json` (`optString`, `optLong`…): чужой тип или `null` — значение
//! по умолчанию, число в строке — число.

use serde_json::Value;

pub fn opt<'a>(v: &'a Value, k: &str) -> Option<&'a Value> {
    v.get(k).filter(|x| !x.is_null())
}

pub fn has(v: &Value, k: &str) -> bool {
    opt(v, k).is_some()
}

pub fn opt_str(v: &Value, k: &str) -> String {
    match opt(v, k) {
        Some(Value::String(s)) => s.clone(),
        Some(x) => x.to_string(),
        None => String::new(),
    }
}

pub fn opt_i64(v: &Value, k: &str, default: i64) -> i64 {
    match opt(v, k) {
        Some(Value::Number(n)) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)).unwrap_or(default),
        Some(Value::String(s)) => {
            let s = s.trim();
            s.parse::<i64>().ok().or_else(|| s.parse::<f64>().ok().map(|f| f as i64)).unwrap_or(default)
        }
        _ => default,
    }
}

pub fn opt_f64(v: &Value, k: &str, default: f64) -> f64 {
    match opt(v, k) {
        Some(Value::Number(n)) => n.as_f64().unwrap_or(default),
        Some(Value::String(s)) => s.trim().parse().unwrap_or(default),
        _ => default,
    }
}

pub fn opt_i32(v: &Value, k: &str, default: i32) -> i32 {
    opt_i64(v, k, default as i64) as i32
}

pub fn opt_bool(v: &Value, k: &str, default: bool) -> bool {
    match opt(v, k) {
        Some(Value::Bool(b)) => *b,
        Some(Value::String(s)) if s.eq_ignore_ascii_case("true") => true,
        Some(Value::String(s)) if s.eq_ignore_ascii_case("false") => false,
        _ => default,
    }
}

pub fn opt_array<'a>(v: &'a Value, k: &str) -> Option<&'a Vec<Value>> {
    opt(v, k).and_then(Value::as_array)
}

pub fn opt_object<'a>(v: &'a Value, k: &str) -> Option<&'a Value> {
    opt(v, k).filter(|x| x.is_object())
}

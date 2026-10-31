//! Регулярные выражения Java/Kotlin в Rust без изменения смысла.
//!
//! Правила медиатеки и поиска — одни и те же шаблоны на сайте, Android, ТВ и ПК, поэтому шаблоны переносим
//! как есть и переводим только то, что в Java значит другое:
//! - `\d`, `\s`, `\w`, `\b` в Java только ASCII (без `UNICODE_CHARACTER_CLASS`), в Rust — Unicode;
//! - `IGNORE_CASE` у Kotlin включает и `UNICODE_CASE`, то есть кириллица без учёта регистра — как `(?i)` в Rust;
//! - заглядывания `(?<!…)`/`(?=…)` даёт `fancy_regex`.

use fancy_regex::{Captures, Regex};

const WORD: &str = "A-Za-z0-9_";
const SPACE: &str = r"\t\n\x0B\f\r ";

/// Шаблон Java → `fancy_regex`. `ic` — `RegexOption.IGNORE_CASE`.
pub fn compile(pattern: &str, ic: bool) -> Regex {
    Regex::new(&translate(pattern, ic)).unwrap_or_else(|e| panic!("bad pattern {pattern}: {e}"))
}

pub fn translate(pattern: &str, ic: bool) -> String {
    let mut out = String::with_capacity(pattern.len() + 16);
    if ic {
        out.push_str("(?i)");
    }
    let mut in_class = false;
    let mut chars = pattern.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '\\' => {
                let Some(n) = chars.next() else {
                    out.push('\\');
                    break;
                };
                let rep = match (n, in_class) {
                    ('d', false) => "[0-9]".to_string(),
                    ('d', true) => "0-9".to_string(),
                    ('D', false) => "[^0-9]".to_string(),
                    ('s', false) => format!("[{SPACE}]"),
                    ('s', true) => SPACE.to_string(),
                    ('S', false) => format!("[^{SPACE}]"),
                    ('w', false) => format!("[{WORD}]"),
                    ('w', true) => WORD.to_string(),
                    ('W', false) => format!("[^{WORD}]"),
                    ('b', false) => format!("(?:(?<=[{WORD}])(?![{WORD}])|(?<![{WORD}])(?=[{WORD}]))"),
                    ('D' | 'S' | 'W', true) => panic!("negated class escape inside [] is not supported: {pattern}"),
                    _ => format!("\\{n}"),
                };
                out.push_str(&rep);
            }
            '[' if !in_class => {
                in_class = true;
                out.push('[');
                // `[]…]` и `[^]…]`: первая `]` — символ, а не конец класса (в этих шаблонах не встречается, но пусть будет честно).
                if chars.peek() == Some(&'^') {
                    out.push(chars.next().unwrap());
                }
            }
            ']' if in_class => {
                in_class = false;
                out.push(']');
            }
            _ => out.push(c),
        }
    }
    out
}

/// Статический шаблон: `rx!(r"…")` или `rx!(r"…", ic)` (без учёта регистра).
#[macro_export]
macro_rules! rx {
    ($p:expr) => {{
        static R: std::sync::LazyLock<fancy_regex::Regex> =
            std::sync::LazyLock::new(|| $crate::jrx::compile($p, false));
        &*R
    }};
    ($p:expr, ic) => {{
        static R: std::sync::LazyLock<fancy_regex::Regex> = std::sync::LazyLock::new(|| $crate::jrx::compile($p, true));
        &*R
    }};
}

/// `containsMatchIn`.
pub fn has(r: &Regex, s: &str) -> bool {
    r.is_match(s).unwrap_or(false)
}

/// `find` с группами.
pub fn caps<'t>(r: &Regex, s: &'t str) -> Option<Captures<'t>> {
    r.captures(s).ok().flatten()
}

/// `find(...)?.value`.
pub fn find<'t>(r: &Regex, s: &'t str) -> Option<&'t str> {
    r.find(s).ok().flatten().map(|m| m.as_str())
}

/// `groupValues[i]`: пусто, если группа не участвовала.
pub fn group<'t>(c: &Captures<'t>, i: usize) -> &'t str {
    c.get(i).map(|m| m.as_str()).unwrap_or("")
}

/// `String.replace(Regex, with)` (без `$` в замене).
pub fn replace(r: &Regex, s: &str, with: &str) -> String {
    r.replace_all(s, fancy_regex::NoExpand(with)).into_owned()
}

/// Все совпадения (`findAll`).
pub fn find_all<'t>(r: &Regex, s: &'t str) -> Vec<fancy_regex::Match<'t>> {
    r.find_iter(s).filter_map(Result::ok).collect()
}

pub fn captures_all<'t>(r: &Regex, s: &'t str) -> Vec<Captures<'t>> {
    r.captures_iter(s).filter_map(Result::ok).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ascii_classes_like_java() {
        let d = compile(r"\d+", false);
        assert_eq!(find(&d, "٣12"), Some("12"));
        let b = compile(r"\b(1080p)\b", true);
        assert!(has(&b, "Фильм 1080p"));
        assert!(has(&b, "Фильм1080P")); // в Java кириллица не «слово»: граница есть
        assert!(!has(&b, "x1080p"));
        let c = compile(r"[\s._-]+", false);
        assert_eq!(replace(&c, "a. b", "|"), "a|b");
        let nb = compile(r"(?<![\p{L}\p{N}])(?:трилоги[яи])(?![\p{L}\p{N}])", true);
        assert!(has(&nb, "Пила: ТРИЛОГИЯ"));
    }
}

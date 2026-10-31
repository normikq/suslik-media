//! Файлы `*.properties` в формате `java.util.Properties` (`load` / `store`): Kotlin-версия ПК хранит в них вход,
//! cookie, прогресс и настройки в `%APPDATA%\SuslikMedia`. Rust-версия читает и пишет те же файлы, чтобы при
//! переходе ничего не потерялось и можно было вернуться обратно.
//!
//! Файл — ISO-8859-1; всё вне ASCII записано как `\uXXXX` (UTF-16, пары суррогатов — двумя escape).

use std::collections::BTreeMap;

/// Ошибка разбора: битая `\uXXXX` (Java бросает `IllegalArgumentException`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MalformedEscape;

fn is_ws(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\u{000C}')
}

/// Логические строки: продолжение после нечётного числа `\` в конце, начальные пробелы продолжения убраны.
fn logical_lines(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut continuing = false;
    let mut chars = text.chars().peekable();
    let mut line = String::new();
    let mut lines = Vec::new();
    while let Some(c) = chars.next() {
        match c {
            '\n' => lines.push(std::mem::take(&mut line)),
            '\r' => {
                if chars.peek() == Some(&'\n') {
                    chars.next();
                }
                lines.push(std::mem::take(&mut line));
            }
            _ => line.push(c),
        }
    }
    if !line.is_empty() {
        lines.push(line);
    }
    for raw in lines {
        let l = raw.trim_start_matches(is_ws);
        if !continuing && (l.is_empty() || l.starts_with('#') || l.starts_with('!')) {
            continue;
        }
        let slashes = l.chars().rev().take_while(|c| *c == '\\').count();
        if slashes % 2 == 1 {
            cur.push_str(&l[..l.len() - 1]);
            continuing = true;
        } else {
            cur.push_str(l);
            out.push(std::mem::take(&mut cur));
            continuing = false;
        }
    }
    if continuing {
        out.push(cur);
    }
    out
}

/// Раскодирует escape-последовательности в UTF-16 и собирает строку.
fn unescape(s: &str) -> Result<String, MalformedEscape> {
    let mut units: Vec<u16> = Vec::with_capacity(s.len());
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            let mut buf = [0u16; 2];
            units.extend_from_slice(c.encode_utf16(&mut buf));
            continue;
        }
        let Some(n) = chars.next() else { break };
        match n {
            't' => units.push('\t' as u16),
            'n' => units.push('\n' as u16),
            'r' => units.push('\r' as u16),
            'f' => units.push(0x0C),
            'u' => {
                let hex: String = chars.by_ref().take(4).collect();
                if hex.len() != 4 {
                    return Err(MalformedEscape);
                }
                units.push(u16::from_str_radix(&hex, 16).map_err(|_| MalformedEscape)?);
            }
            other => {
                let mut buf = [0u16; 2];
                units.extend_from_slice(other.encode_utf16(&mut buf));
            }
        }
    }
    Ok(String::from_utf16_lossy(&units))
}

/// Разбор как `Properties.load(InputStream)`: байты — ISO-8859-1.
pub fn parse(bytes: &[u8]) -> Result<BTreeMap<String, String>, MalformedEscape> {
    let text: String = bytes.iter().map(|b| *b as char).collect();
    let mut map = BTreeMap::new();
    for line in logical_lines(&text) {
        // Конец ключа — первый не экранированный `=`, `:` или пробел.
        let chars: Vec<(usize, char)> = line.char_indices().collect();
        let mut key_end = line.len();
        let mut value_start = line.len();
        let mut i = 0;
        while i < chars.len() {
            let (pos, c) = chars[i];
            if c == '\\' {
                i += 2;
                continue;
            }
            if c == '=' || c == ':' || is_ws(c) {
                key_end = pos;
                // пробелы, потом один `=`/`:`, потом снова пробелы
                let mut j = i;
                let mut had_sep = c == '=' || c == ':';
                j += 1;
                while j < chars.len() {
                    let cj = chars[j].1;
                    if is_ws(cj) {
                        j += 1;
                    } else if !had_sep && (cj == '=' || cj == ':') {
                        had_sep = true;
                        j += 1;
                    } else {
                        break;
                    }
                }
                value_start = chars.get(j).map(|x| x.0).unwrap_or(line.len());
                break;
            }
            i += 1;
        }
        let key = unescape(&line[..key_end])?;
        let value = unescape(&line[value_start..])?;
        map.insert(key, value);
    }
    Ok(map)
}

fn escape(s: &str, is_key: bool, out: &mut String) {
    for (i, unit) in s.encode_utf16().enumerate() {
        match unit {
            0x20 if i == 0 || is_key => out.push_str("\\ "),
            0x09 => out.push_str("\\t"),
            0x0A => out.push_str("\\n"),
            0x0D => out.push_str("\\r"),
            0x0C => out.push_str("\\f"),
            0x3D | 0x3A | 0x23 | 0x21 | 0x5C => {
                out.push('\\');
                out.push(unit as u8 as char);
            }
            0x20..=0x7E => out.push(unit as u8 as char),
            _ => out.push_str(&format!("\\u{unit:04X}")),
        }
    }
}

/// Запись как `Properties.store(out, null)`: первая строка — комментарий, дальше `ключ=значение`, только ASCII.
pub fn write(map: &BTreeMap<String, String>) -> Vec<u8> {
    let nl = if cfg!(windows) { "\r\n" } else { "\n" };
    let mut out = format!("#suslik-media{nl}");
    for (k, v) in map {
        escape(k, true, &mut out);
        out.push('=');
        escape(v, false, &mut out);
        out.push_str(nl);
    }
    out.into_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(s: &str) -> BTreeMap<String, String> {
        parse(s.as_bytes()).unwrap()
    }

    #[test]
    fn reads_what_java_writes() {
        // Так Java пишет файл «watch»: дата-комментарий, \: в ключе, кириллица в \u
        let text = "#Sat Oct 31 11:00:00 MSK 2026\r\n42|Season\\ 1/E01.mkv=120000,2400000,1730000000000\r\nw\\:42|a=1\r\nname=\\u0422\\u0430\\u0447\\u043A\\u0438 2\r\n";
        let m = p(text);
        assert_eq!(m["42|Season 1/E01.mkv"], "120000,2400000,1730000000000");
        assert_eq!(m["w:42|a"], "1");
        assert_eq!(m["name"], "Тачки 2");
    }

    #[test]
    fn separators_comments_continuations() {
        let m = p("! comment\n  # comment\na b\nc : d\ne=\nf\\ g = h \\\n    i\nj\\=k=l\n\n");
        assert_eq!(m["a"], "b");
        assert_eq!(m["c"], "d");
        assert_eq!(m["e"], "");
        assert_eq!(m["f g"], "h i");
        assert_eq!(m["j=k"], "l");
        assert_eq!(m.len(), 5);
    }

    #[test]
    fn round_trip_with_escapes() {
        let mut m = BTreeMap::new();
        m.insert("w:42|Сезон 1/Серия #1.mkv".to_string(), " lead space, = : ! \\ \t\n😀".to_string());
        m.insert("cookie".to_string(), "sid=abc".to_string());
        let bytes = write(&m);
        assert!(bytes.iter().all(|b| b.is_ascii()));
        let text = String::from_utf8(bytes.clone()).unwrap();
        assert!(text.contains("w\\:42|\\u0421\\u0435\\u0437\\u043E\\u043D\\ 1/"), "{text}");
        assert!(text.contains("\\uD83D\\uDE00"), "{text}");
        assert_eq!(parse(&bytes).unwrap(), m);
    }

    #[test]
    fn broken_unicode_escape_fails_like_java() {
        assert_eq!(parse(b"a=\\u12"), Err(MalformedEscape));
        assert_eq!(parse(b"a=\\uzzzz"), Err(MalformedEscape));
    }

    #[test]
    fn latin1_bytes() {
        assert_eq!(p("a=\u{e9}")["a"], "Ã©"); // UTF-8 «é» читается как два символа Latin-1, как в Java
    }
}

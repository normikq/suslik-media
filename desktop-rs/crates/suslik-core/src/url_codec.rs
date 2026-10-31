//! Кодирование адресов так же, как `java.net.URLEncoder`/`URLDecoder` (UTF-8): клиенты на Kotlin и Rust
//! должны получать одинаковые строки, иначе сервер не узнает подпись или ключ файла.

/// Как `URLEncoder.encode(s, "UTF-8")`: буквы, цифры и `.-*_` как есть, пробел — `+`, остальное — `%XX`.
pub fn encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => out.push(b as char),
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

/// Как `URLDecoder.decode(s, "UTF-8")`: `+` — пробел, `%XX` — байт. Битая escape-последовательность — `None`
/// (Java бросает исключение). Неверный UTF-8 заменяется на `�`, как в Java.
pub fn decode(s: &str) -> Option<String> {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b'%' => {
                let hex = bytes.get(i + 1..i + 3)?;
                let hex = std::str::from_utf8(hex).ok()?;
                out.push(u8::from_str_radix(hex, 16).ok()?);
                i += 3;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    Some(String::from_utf8_lossy(&out).into_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn like_java() {
        assert_eq!(encode("https://a.b/f?x=1 2"), "https%3A%2F%2Fa.b%2Ff%3Fx%3D1+2");
        assert_eq!(encode("Фильм"), "%D0%A4%D0%B8%D0%BB%D1%8C%D0%BC");
        assert_eq!(decode("a+b%2Fc").as_deref(), Some("a b/c"));
        assert_eq!(decode("%D0%A4").as_deref(), Some("Ф"));
        assert_eq!(decode("%zz"), None);
        assert_eq!(decode("%2"), None);
    }
}

//! Версии вида «1.9.5» / «2.0.0.1» (до четырёх чисел, прочий текст не считается) и «канал обновлений».
//! Повторяет `Versions.kt`.

use std::cmp::Ordering;

fn parts(v: &str) -> [u64; 4] {
    let mut out = [0u64; 4];
    let mut n = 0;
    let mut cur: Option<u64> = None;
    for c in v.chars().chain(std::iter::once(' ')) {
        if let Some(d) = c.to_digit(10) {
            cur = Some(cur.unwrap_or(0).saturating_mul(10).saturating_add(d as u64));
        } else if let Some(x) = cur.take() {
            if n < 4 {
                out[n] = x;
                n += 1;
            }
        }
    }
    out
}

pub fn is_newer(remote: &str, local: &str) -> bool {
    parts(remote) > parts(local)
}

pub fn compare(a: &str, b: &str) -> Ordering {
    parts(a).cmp(&parts(b))
}

/// Решение «канала обновлений». На канале релизов текущий релиз всегда побеждает (заменяет даже бету с большим
/// номером); на бета-канале предлагается только более новая версия.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verdict {
    Offer,
    Replace,
    Installed,
    Older,
    Nothing,
}

pub fn verdict(remote: Option<&str>, installed: &str, release: bool) -> Verdict {
    match remote.filter(|r| !r.trim().is_empty()) {
        None => Verdict::Nothing,
        Some(r) if is_newer(r, installed) => Verdict::Offer,
        Some(r) if compare(r, installed) == Ordering::Equal => Verdict::Installed,
        Some(_) if release => Verdict::Replace,
        Some(_) => Verdict::Older,
    }
}

/// Предлагать ли обновление (проверка при старте, кнопка в шапке).
pub fn offers(v: Verdict) -> bool {
    matches!(v, Verdict::Offer | Verdict::Replace)
}

pub fn message(v: Verdict, remote: Option<&str>, installed: &str, beta: bool) -> String {
    let remote = remote.unwrap_or("null");
    match v {
        Verdict::Offer => format!("Доступна версия {remote}{}.", if beta { " (бета)" } else { "" }),
        Verdict::Replace => format!("Релиз {remote} заменит установленную бету {installed}."),
        Verdict::Installed => {
            format!("Установлена актуальная {} {installed}.", if beta { "бета-версия" } else { "версия" })
        }
        Verdict::Older => format!("Установлена {installed} — она новее последней беты ({remote})."),
        Verdict::Nothing => "Не удалось проверить обновления — попробуйте позже.".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compares_versions() {
        assert!(is_newer("1.10.0", "1.9.5"));
        assert!(!is_newer("1.9.5", "1.9.5"));
        assert!(is_newer("2.0.0", "1.99.99"));
        assert!(is_newer("2.0.0.1", "2.0.0"));
        assert!(!is_newer("2.0.0.1", "2.0.1"));
        assert!(is_newer("2.0.1", "2.0.0.1"));
        assert_eq!(compare("1.2", "1.2.1"), Ordering::Less);
    }

    #[test]
    fn update_channel() {
        assert_eq!(verdict(Some("2.0.1"), "2.0.0", false), Verdict::Offer);
        assert_eq!(verdict(Some("2.0.1"), "2.0.1", true), Verdict::Installed);
        assert_eq!(verdict(Some("2.0.0"), "2.0.1", false), Verdict::Older);
        assert_eq!(verdict(Some("1.9.5"), "2.0.3", true), Verdict::Replace);
        assert_eq!(verdict(None, "2.0.1", true), Verdict::Nothing);
        assert_eq!(verdict(Some("2.0.0.1"), "2.0.0", false), Verdict::Offer);
        assert_eq!(verdict(Some("2.0.0"), "2.0.0.1", true), Verdict::Replace);
    }
}

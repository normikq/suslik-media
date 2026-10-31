/// Время фильма, как его показывают плееры: «05:03», «1:05:03» (отрицательное — как 0). Повторяет `clockText`.
pub fn clock_text(ms: i64) -> String {
    let t = ms.max(0) / 1000;
    let (h, m, s) = (t / 3600, (t % 3600) / 60, t % 60);
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m:02}:{s:02}")
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn clock() {
        assert_eq!(super::clock_text(-5), "00:00");
        assert_eq!(super::clock_text(303_999), "05:03");
        assert_eq!(super::clock_text(3_903_000), "1:05:03");
    }
}

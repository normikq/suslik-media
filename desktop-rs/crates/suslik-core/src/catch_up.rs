//! «Копим запас» — одни правила во всех приложениях. Перенос `CatchUp.kt`.
//!
//! На сети медленнее фильма запас кончается, и плеер встаёт на несколько секунд каждые 15–30 с; после такой остановки
//! аппаратный декодер часто показывает застывшую или битую картинку. Вместо этого, когда запас пуст,
//! [`burst_proxy::hold`] держит фильм, пока не скачаются ~30 с ([`burst_proxy::catch_up_target_mb`], не дольше
//! [`MAX_MS`]); для плеера это одна долгая буферизация. Плеер ради этого на паузу не ставится.

use crate::burst_proxy;
use std::time::{SystemTime, UNIX_EPOCH};

pub const MAX_MS: i64 = 45_000;
/// После прошлого «копим запас» столько нового нет.
pub const AFTER_MS: i64 = 20_000;

pub struct CatchUp {
    log: Box<dyn Fn(&str) + Send>,
    now: Box<dyn Fn() -> i64 + Send>,
    active: bool,
    started_at: i64,
    not_before: i64,
}

impl CatchUp {
    pub fn new(log: impl Fn(&str) + Send + 'static) -> Self {
        Self::with_clock(log, || {
            SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
        })
    }

    pub fn with_clock(log: impl Fn(&str) + Send + 'static, now: impl Fn() -> i64 + Send + 'static) -> Self {
        CatchUp { log: Box::new(log), now: Box::new(now), active: false, started_at: 0, not_before: 0 }
    }

    /// Фильм сейчас держится.
    pub fn active(&self) -> bool {
        self.active
    }

    /// Когда началось текущее (или прошлое) накопление.
    pub fn started_at(&self) -> i64 {
        self.started_at
    }

    /// Не копить `ms` (открыли, перемотали): плеер всё равно наполняет свой буфер.
    pub fn quiet(&mut self, ms: i64) {
        self.not_before = self.not_before.max((self.now)() + ms);
    }

    /// Начинает держать фильм, если сеть медленнее него и запас (почти) пуст. `can_start` — условия приложения
    /// (играет, не пауза, перемотки нет…). `true` — началось.
    pub fn maybe_start(&mut self, url: &str, can_start: bool) -> bool {
        if self.active || !can_start || (self.now)() < self.not_before {
            return false;
        }
        if !burst_proxy::is_active(url)
            || !burst_proxy::slower_than_film(url)
            || burst_proxy::ahead_mb(url).unwrap_or(99.0) >= 2.0
        {
            return false;
        }
        self.active = true;
        self.started_at = (self.now)();
        (self.log)(&format!(
            "network slower than the film, reserve empty: holding the film to gather {:.0} MB",
            burst_proxy::catch_up_target_mb(url)
        ));
        burst_proxy::hold(url, MAX_MS + 5_000);
        true
    }

    /// Пока `active`: сколько накоплено, 0..99 %, или -1, когда хватит (или прошло [`MAX_MS`]): `end` с перезапуском.
    pub fn progress(&self, url: &str) -> i32 {
        let target = burst_proxy::catch_up_target_mb(url);
        let ahead = burst_proxy::ahead_mb(url).unwrap_or(0.0);
        if ahead >= target || (self.now)() - self.started_at >= MAX_MS {
            return -1;
        }
        ((ahead * 100.0 / target.max(1.0)) as i32).clamp(0, 99)
    }

    /// Отпускает фильм. С `restart` (запас есть) — `true`, когда плееру перемотать на текущее место: после долгой
    /// буферизации декодер отстаёт, а прыжок по данным на диске начинает его чисто. Только если позади ≥ 8 с —
    /// прыжок встаёт на прошлый ключевой кадр, на несколько секунд назад.
    pub fn end(&mut self, url: &str, restart: bool) -> bool {
        if !self.active {
            return false;
        }
        self.active = false;
        self.quiet(AFTER_MS);
        burst_proxy::hold(url, 0);
        (self.log)(&format!(
            "reserve gathered: {:.0} MB in {} s",
            burst_proxy::ahead_mb(url).unwrap_or(0.0),
            ((self.now)() - self.started_at) / 1000
        ));
        if !restart {
            return false;
        }
        let behind = burst_proxy::kept_behind_sec(url);
        if behind >= 8.0 {
            return true;
        }
        (self.log)(&format!("restart skipped: only {behind:.1} s kept behind"));
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nothing_without_a_reserve() {
        let mut c = CatchUp::with_clock(|_| {}, || 1_000_000);
        assert!(!c.maybe_start("https://x/not-in-the-reserve.mkv", true));
        assert!(!c.active());
        assert!(!c.end("https://x/not-in-the-reserve.mkv", true)); // не активно: нечего кончать
    }
}

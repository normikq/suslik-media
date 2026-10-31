//! Часы комнаты и подстройка дрейфа. Повторяет `PartyModels.kt`.

/// Смещение часов клиента относительно сервера комнаты по выборке с наименьшим RTT:
/// `offset = serverTime - (localSend + rtt / 2)`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PartyClock {
    pub offset_ms: i64,
    pub best_rtt_ms: i64,
}

impl Default for PartyClock {
    fn default() -> Self {
        Self { offset_ms: 0, best_rtt_ms: i64::MAX }
    }
}

impl PartyClock {
    pub fn add_sample(&mut self, local_send: i64, local_recv: i64, server_time: i64) {
        let rtt = (local_recv - local_send).max(0);
        if rtt < self.best_rtt_ms {
            self.best_rtt_ms = rtt;
            self.offset_ms = server_time - (local_send + rtt / 2);
        }
    }

    pub fn server_now(&self, local_now: i64) -> i64 {
        local_now + self.offset_ms
    }

    pub fn reset(&mut self) {
        *self = Self::default();
    }
}

/// Что делать с рассинхроном.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum DriftAction {
    /// Меньше 0,3 с: ничего.
    None,
    /// Слегка впереди комнаты: 0,95×.
    SlowDown(f32),
    /// Слегка позади: 1,05×.
    SpeedUp(f32),
    /// Больше 2 с: перемотка на место комнаты (с).
    HardSeek(f32),
}

pub const TOLERANCE_SEC: f32 = 0.3;
pub const HARD_SEEK_THRESHOLD_SEC: f32 = 2.0;

/// < 0,3 с — не трогаем; 0,3..2 с — скорость; больше — перемотка (docs/DISCORD.md).
pub fn evaluate_drift(local_pos_sec: f32, target_pos_sec: f32) -> DriftAction {
    let drift = local_pos_sec - target_pos_sec;
    if drift.abs() < TOLERANCE_SEC {
        DriftAction::None
    } else if !(-HARD_SEEK_THRESHOLD_SEC..=HARD_SEEK_THRESHOLD_SEC).contains(&drift) {
        DriftAction::HardSeek(target_pos_sec)
    } else if drift > 0.0 {
        DriftAction::SlowDown(0.95)
    } else {
        DriftAction::SpeedUp(1.05)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clock_offset_from_samples() {
        let mut c = PartyClock::default();
        c.add_sample(1000, 1040, 5000);
        assert_eq!((c.offset_ms, c.best_rtt_ms), (3980, 40));
        assert_eq!(c.server_now(10000), 13980);
        c.add_sample(2000, 2080, 6000);
        assert_eq!((c.offset_ms, c.best_rtt_ms), (3980, 40));
        c.add_sample(3000, 3020, 7000);
        assert_eq!((c.offset_ms, c.best_rtt_ms), (3990, 20));
    }

    #[test]
    fn drift_decisions() {
        assert_eq!(evaluate_drift(10.1, 10.0), DriftAction::None);
        assert_eq!(evaluate_drift(9.8, 10.0), DriftAction::None);
        assert_eq!(evaluate_drift(10.5, 10.0), DriftAction::SlowDown(0.95));
        assert_eq!(evaluate_drift(9.2, 10.0), DriftAction::SpeedUp(1.05));
        assert_eq!(evaluate_drift(25.0, 10.0), DriftAction::HardSeek(10.0));
        assert_eq!(evaluate_drift(5.0, 10.0), DriftAction::HardSeek(10.0));
    }
}

//! Синхронизация плеера с комнатой. Повторяет `PartySyncEngine.kt`.

use super::clock::{evaluate_drift, DriftAction};
use super::protocol::PartyState;

/// Плеер, которым управляет комната (приложение отдаёт свои функции: панели и SMTC остаются верными).
pub trait SyncPlayer {
    /// Где картинка (сразу после перемотки — куда она идёт).
    fn time_ms(&self) -> i64;
    /// Пользователь тянет полосу времени: ждём, пока отпустит.
    fn busy(&self) -> bool;
    /// Место самого движка как есть.
    fn engine_ms(&self) -> i64;
    /// Метка последней перемотки (пользователя или нашей): меняется при каждой.
    fn seek_stamp(&self) -> i64;
    fn paused(&self) -> bool;
    /// Кадры идут: не открывается и не буферизуется.
    fn running(&self) -> bool;
    /// Скорость, выбранная в плеере.
    fn user_rate(&self) -> f32;
    fn apply_paused(&mut self, paused: bool);
    fn seek_to(&mut self, ms: i64);
    /// Скорость движка (подстройка дрейфа).
    fn set_rate(&mut self, rate: f32);
}

/// Комната для движка: состояние и команды серверу (приложение шлёт их по очереди, по порядку).
pub trait PartyLink {
    fn state(&self) -> PartyState;
    /// Пауза, пуск и перемотка всей комнаты — только ведущий. Пауза гостя остаётся у него.
    fn can_control(&self) -> bool;
    fn can_seek(&self) -> bool;
    /// Где комната сейчас, мс (с откалиброванными часами).
    fn target_ms(&self) -> i64;
    /// Грузится кто-то, кроме меня.
    fn others_buffering(&self) -> bool;
    /// Я только что поставил фильм `key` в комнату: true один раз.
    fn take_started_by_me(&mut self, key: &str) -> bool;
    fn play(&mut self, pos_ms: i64);
    fn pause(&mut self, pos_ms: i64);
    fn seek(&mut self, pos_ms: i64);
    /// «Мой плеер грузится» / «готов». Повторы приложение отбрасывает само.
    fn report_buffering(&mut self, loading: bool);
}

/// Подсказка в плеере: что сделал ведущий (гостю) и кого ждёт комната (всем). Текст пишет приложение.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PartyNotice {
    Paused,
    Resumed,
    Seeked(i64),
    /// Комната стоит дольше [`WAIT_NOTICE_MS`]: ждём, пока загрузятся другие.
    Waiting,
}

pub const TICK_MS: i64 = 400;
/// После того как комната сдвинула нас, наши собственные изменения — не новость для комнаты.
pub const OWN_MS: i64 = 2_500;
/// Время движка стоит столько без паузы — ждём данные (даже если движок говорит «играю»).
pub const FROZEN_MS: i64 = 1_000;
/// Подсказка «ждём, пока загрузится у …» — если комната стоит дольше.
pub const WAIT_NOTICE_MS: i64 = 2_000;
/// Скачок места больше — это перемотка ведущего, а не ход времени.
pub const SEEK_NOTICE_MS: i64 = 3_000;

/// Держит плеер в фильме комнаты: следует за пуском, паузой и перемоткой комнаты и отправляет свои.
/// Малый дрейф выравнивает скоростью (0,95–1,05×), большой — перемоткой.
///
/// Без потоков: приложение вызывает [`tick`](Self::tick) каждые [`TICK_MS`], пока плеер показывает фильм комнаты,
/// и [`idle`](Self::idle) — пока нет. Грузящийся плеер сообщает об этом, и комната его ЖДЁТ.
pub struct PartySyncEngine<L: PartyLink> {
    pub link: L,
    log: Box<dyn FnMut(&str)>,
    notice: Box<dyn FnMut(PartyNotice)>,
    last_seq: i64,
    own_until: i64,
    prev_t: i64,
    prev_p: bool,
    prev_at: i64,
    drift: f32,
    pending_at: i64,
    pending_to: i64,
    pending_t: i64,
    stalled_since: i64,
    last_engine_ms: i64,
    engine_moved_at: i64,
    local_hold: bool,
    seen_seek: Option<i64>,
    applied: Option<PartyState>,
    wait_since: i64,
    wait_noticed: bool,
    /// Последнее увиденное место: для паузы комнаты, когда ведущий закрыл фильм.
    pub last_time_ms: i64,
}

impl<L: PartyLink> PartySyncEngine<L> {
    pub fn new(link: L) -> Self {
        Self::with_callbacks(link, |_| {}, |_| {})
    }

    pub fn with_callbacks(link: L, log: impl FnMut(&str) + 'static, notice: impl FnMut(PartyNotice) + 'static) -> Self {
        Self {
            link,
            log: Box::new(log),
            notice: Box::new(notice),
            last_seq: -1,
            own_until: 0,
            prev_t: 0,
            prev_p: true,
            prev_at: 0,
            drift: 1.0,
            pending_at: 0,
            pending_to: 0,
            pending_t: -1,
            stalled_since: 0,
            last_engine_ms: i64::MIN,
            engine_moved_at: 0,
            local_hold: false,
            seen_seek: None,
            applied: None,
            wait_since: 0,
            wait_noticed: false,
            last_time_ms: 0,
        }
    }

    /// Плеер не в фильме комнаты: вернуть свою скорость, забыть команды, сказать «не грузимся».
    pub fn idle(&mut self, player: &mut dyn SyncPlayer) {
        if self.seen_seek.is_none() {
            self.seen_seek = Some(player.seek_stamp());
        }
        self.restore_rate(player);
        self.last_seq = -1;
        self.pending_at = 0;
        self.stalled_since = 0;
        self.last_engine_ms = i64::MIN;
        self.applied = None;
        self.wait_since = 0;
        self.wait_noticed = false;
        self.link.report_buffering(false);
    }

    fn mark(&mut self, t: i64, p: bool, now: i64) {
        self.prev_t = t;
        self.prev_p = p;
        self.prev_at = now;
    }

    /// Один шаг: плеер показывает фильм комнаты `key`. `now` — локальные часы, мс.
    pub fn tick(&mut self, player: &mut dyn SyncPlayer, key: &str, now: i64) {
        if self.seen_seek.is_none() {
            self.seen_seek = Some(player.seek_stamp());
        }
        if player.busy() {
            return; // полосу тянут: место отправим после отпускания
        }
        let t = player.time_ms();
        let p = player.paused();
        self.last_time_ms = t;

        // Пользователь перемотал здесь: комната идёт туда же. Ловим по метке перемотки самого плеера.
        let stamp = player.seek_stamp();
        if Some(stamp) != self.seen_seek {
            self.seen_seek = Some(stamp);
            let expected = if self.prev_p {
                self.prev_t
            } else {
                self.prev_t + ((now - self.prev_at) as f32 * player.user_rate() * self.drift) as i64
            };
            if self.last_seq >= 0 && (t - expected).abs() > 1_500 && self.link.can_seek() {
                (self.log)(&format!("[party] local seek at {t}"));
                self.restore_rate(player);
                if p {
                    self.link.pause(t)
                } else {
                    self.link.play(t)
                }
                self.last_seq = self.link.state().seq + 1;
                // Новое место и здесь грузится секунды: «грузимся» (комната ждёт) и не гонимся за ней.
                self.pending_at = now;
                self.pending_to = t;
                self.pending_t = -1;
                self.own_until = now + OWN_MS;
                self.mark(t, p, now);
                self.link.report_buffering(!p);
                return;
            }
        }

        // Наша перемотка к комнате: готова, когда движок снова идёт вперёд.
        if self.pending_at > 0 {
            let e = player.engine_ms();
            let moved = self.pending_t >= 0
                && (100..=2_000).contains(&(e - self.pending_t))
                && player.running()
                && ((e - self.pending_to).abs() < 5_000 || now - self.pending_at > 3_000);
            if moved || p || now - self.pending_at > 30_000 {
                (self.log)(&format!("[party] seek done in {} ms", now - self.pending_at));
                self.pending_at = 0;
                self.own_until = now + OWN_MS;
            } else {
                self.pending_t = e;
            }
        }
        // Сами грузимся, хотя должны играть: через 1 с комната ждёт и нас. Стоящее время без паузы — тоже загрузка.
        let e = player.engine_ms();
        if p || e != self.last_engine_ms {
            self.last_engine_ms = e;
            self.engine_moved_at = now;
        }
        let frozen = now - self.engine_moved_at >= FROZEN_MS;
        self.stalled_since = if !p && self.pending_at == 0 && (!player.running() || frozen) {
            if self.stalled_since > 0 {
                self.stalled_since
            } else if frozen {
                self.engine_moved_at
            } else {
                now
            }
        } else {
            0
        };
        let loading = self.pending_at > 0 || (self.stalled_since > 0 && now - self.stalled_since > 1_000);
        let playing = self.link.state().playing;
        self.link.report_buffering(loading && playing);

        // 1. Я только что поставил этот фильм в комнату: комната стартует с моего места.
        if self.link.take_started_by_me(key) {
            if p {
                self.link.pause(t)
            } else {
                self.link.play(t)
            }
            self.last_seq = self.link.state().seq + 1;
            self.own_until = now + OWN_MS;
            if !p && !player.running() {
                self.pending_at = now;
                self.pending_to = t;
                self.pending_t = -1;
            }
            self.mark(t, p, now);
            return;
        }

        // 2. Пользователь поставил паузу / пуск здесь: сообщаем комнате сразу.
        if self.last_seq >= 0 && !loading && p != self.prev_p {
            if self.link.can_control() {
                (self.log)(&format!("[party] local {} at {t}", if p { "pause" } else { "play" }));
                if p {
                    self.link.pause(t)
                } else {
                    self.link.play(t)
                }
                self.last_seq = self.link.state().seq + 1;
                self.own_until = now + OWN_MS;
                self.mark(t, p, now);
                return;
            }
            // Гость: пауза только у него; пуск возвращает его к месту ведущего.
            self.local_hold = p;
            (self.log)(if p { "[party] guest pause (local only)" } else { "[party] guest goes on: back to the room" });
            if !p {
                self.last_seq = -1;
            }
        }

        // 3. Следуем за комнатой.
        let s = self.link.state();
        let target = self.link.target_ms();
        let others_loading = self.link.others_buffering();
        let want_paused = !s.playing || (s.waiting && !loading && others_loading) || self.local_hold;
        let mut nt = t;
        let mut np = p;
        if s.playing && s.waiting && others_loading {
            if self.wait_since == 0 {
                self.wait_since = now;
            } else if !self.wait_noticed && now - self.wait_since >= WAIT_NOTICE_MS {
                self.wait_noticed = true;
                (self.notice)(PartyNotice::Waiting);
            }
        } else {
            self.wait_since = 0;
            self.wait_noticed = false;
        }
        if self.last_seq < 0 || s.seq > self.last_seq {
            let before = if self.last_seq >= 0 { self.applied } else { None };
            self.applied = Some(s);
            if let Some(b) = before {
                if !self.link.can_control() {
                    if let Some(n) = notice_of(&b, &s) {
                        (self.notice)(n);
                    }
                }
            }
            self.last_seq = s.seq;
            self.restore_rate(player);
            let already_going = self.pending_at > 0 && (self.pending_to - target).abs() < 1_000;
            let need_seek = (t - target).abs() > 1_000 && !already_going;
            // Комната играет, но ждёт других, а нам ещё перематываться: перемотку грузим без паузы.
            let pause_now = if need_seek && s.playing && !self.local_hold { false } else { want_paused };
            if p != pause_now && !(loading && !pause_now) {
                player.apply_paused(pause_now);
                np = pause_now;
            }
            if need_seek {
                nt = self.seek_room(player, target, now);
            }
            self.own_until = now + OWN_MS;
            (self.log)(&format!(
                "[party] apply seq={} playing={} waiting={} target={target} local={t}",
                s.seq, s.playing, s.waiting
            ));
        } else if s.playing && s.waiting && !loading && !p && others_loading {
            // Готовы, но комната ждёт кого-то ещё: стоим здесь.
            player.apply_paused(true);
            np = true;
            self.own_until = now + 1_000;
            (self.log)(&format!("[party] ready, waiting for the others at {t}"));
        } else if s.playing
            && !s.waiting
            && !p
            && !loading
            && now > self.own_until
            && player.running()
            && player.user_rate() == 1.0
        {
            match evaluate_drift(t as f32 / 1000.0, target as f32 / 1000.0) {
                DriftAction::None => self.restore_rate(player),
                DriftAction::SlowDown(rate) | DriftAction::SpeedUp(rate) => {
                    if self.drift != rate {
                        self.drift = rate;
                        player.set_rate(rate);
                    }
                }
                DriftAction::HardSeek(_) => {
                    self.restore_rate(player);
                    nt = self.seek_room(player, target, now);
                    self.own_until = now + OWN_MS;
                    (self.log)(&format!("[party] drift seek local={t} target={target}"));
                }
            }
        }
        self.mark(nt, np, now);
    }

    fn restore_rate(&mut self, player: &mut dyn SyncPlayer) {
        if self.drift != 1.0 {
            self.drift = 1.0;
            player.set_rate(player.user_rate());
        }
    }

    fn seek_room(&mut self, player: &mut dyn SyncPlayer, target: i64, now: i64) -> i64 {
        player.seek_to(target);
        self.pending_at = now;
        self.pending_to = target;
        self.pending_t = -1;
        self.seen_seek = Some(player.seek_stamp());
        target
    }
}

/// Что изменил ведущий: перемотка (место не там, куда пришло бы само), иначе пауза или пуск.
fn notice_of(before: &PartyState, s: &PartyState) -> Option<PartyNotice> {
    let ran = if before.playing && !before.waiting {
        ((s.anchor_server_ms - before.anchor_server_ms).max(0) as f64 * before.rate) as i64
    } else {
        0
    };
    let expected = (before.pos * 1000.0) as i64 + ran;
    let pos = (s.pos * 1000.0) as i64;
    if (pos - expected).abs() > SEEK_NOTICE_MS {
        Some(PartyNotice::Seeked(pos))
    } else if before.playing && !s.playing {
        Some(PartyNotice::Paused)
    } else if !before.playing && s.playing {
        Some(PartyNotice::Resumed)
    } else {
        None
    }
}

#[cfg(test)]
#[path = "sync_tests.rs"]
mod tests;

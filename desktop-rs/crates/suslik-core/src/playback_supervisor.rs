//! Одно место, которое решает, когда показ болен и что с этим делать, — одни правила во всех приложениях. Перенос
//! `PlaybackSupervisor.kt`.
//!
//! Плеер только сообщает, что происходит (события), и что меряет (тик раз в ~2 с); надзор держит состояние и
//! разрешает проверку, только пока фильм правда играет:
//!
//! ```text
//! Idle → Opening → Buffering ⇄ Playing ⇄ Paused
//!                     ↑           ↓
//!                     └─ Recovering (одно действие за раз, потом льготный период)
//! ```
//!
//! - любой проверке нужно состояние Playing, льготный период после открытия / продолжения / перемотки /
//!   восстановления и часы фильма, которые правда идут (стоящие часы — отдельная беда: «stuck»);
//! - одно действие за тик, сначала самое мягкое (перезапуск видео, выбор звука) и только потом переоткрытие;
//! - бюджет на серию, чтобы ничего не зациклилось.

use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    Idle,
    Opening,
    Buffering,
    Playing,
    Paused,
    Recovering,
    Ended,
}

/// Что умеет плеер. Все вызовы — из `tick` и событий, в потоке вызывающего.
pub trait Actions {
    /// Новый декодер и вывод видео на текущем месте (застывшая / дёрганая картинка).
    fn restart_video(&mut self);
    /// Выбрать настоящую звуковую дорожку и снять беззвучный режим вывода.
    fn fix_sound(&mut self);
    /// Открыть фильм заново на текущем месте; `fresh` — заодно выбросить локальный запас.
    fn reopen(&mut self, fresh: bool);
    /// Короткое сообщение зрителю («Восстанавливаем звук…»).
    fn notice(&mut self, text: &str);
    fn log(&mut self, message: &str);
}

/// Что намерил плеер. Счётчики накопительные для текущего потока.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Stats {
    pub time_ms: i64,       // часы фильма
    pub audio_played: i64,  // сыграно звуковых буферов
    pub audio_decoded: i64, // декодировано звуковых блоков
    pub pictures: i64,      // показано кадров
    pub expected_fps: f64,  // частота кадров фильма (0 — неизвестна)
    pub audio_tracks: i32,  // настоящих звуковых дорожек
    pub audio_selected: bool,
    pub video_tracks: i32,
    pub user_muted: bool, // зритель сам выключил звук: тишина ожидаема
}

fn system_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

pub struct PlaybackSupervisor<A: Actions> {
    pub actions: A,
    now: Box<dyn Fn() -> i64 + Send>,
    /// Сколько терпеть стоящие часы до переоткрытия (дольше, когда переподключается локальный запас).
    stuck_limit_ms: Box<dyn Fn() -> i64 + Send>,
    state: State,
    episode: String,
    grace_until: i64,
    // бюджеты на серию
    video_fixes: i32,
    sound_fixes: i32,
    reopens: i32,
    after_buffer_fixes: i32,
    buffered_at: i64, // когда кончилась последняя настоящая буферизация (Buffering → Playing)
    last_action: i64,
    // текущие замеры
    last: Option<Stats>,
    last_at: i64,
    pictures_moved: i32, // картинка в этом потоке двигалась (не обложка)
    silent_for: i64,     // мс часов фильма без сыгранного звукового буфера
    frozen_for: i64,     // мс часов фильма без нового кадра
    choppy_ticks: i32,
    stuck_for: i64,                    // мс в Playing, когда часы стоят
    surface_check: Option<(i64, i64)>, // (когда, кадров) после смены поверхности видео
}

impl<A: Actions> PlaybackSupervisor<A> {
    pub fn new(actions: A) -> Self {
        Self::with_clock(actions, Box::new(system_ms), Box::new(|| 12_000))
    }

    pub fn with_clock(
        actions: A,
        now: Box<dyn Fn() -> i64 + Send>,
        stuck_limit_ms: Box<dyn Fn() -> i64 + Send>,
    ) -> Self {
        PlaybackSupervisor {
            actions,
            now,
            stuck_limit_ms,
            state: State::Idle,
            episode: String::new(),
            grace_until: 0,
            video_fixes: 0,
            sound_fixes: 0,
            reopens: 0,
            after_buffer_fixes: 0,
            buffered_at: -1,
            last_action: 0,
            last: None,
            last_at: 0,
            pictures_moved: 0,
            silent_for: 0,
            frozen_for: 0,
            choppy_ticks: 0,
            stuck_for: 0,
            surface_check: None,
        }
    }

    pub fn state(&self) -> State {
        self.state
    }

    fn now(&self) -> i64 {
        (self.now)()
    }

    // ---- события ----
    /// Поток начинает открываться. Новый `episode_key` обнуляет бюджеты.
    pub fn opening(&mut self, episode_key: &str) {
        if episode_key != self.episode {
            self.episode = episode_key.to_string();
            self.video_fixes = 0;
            self.sound_fixes = 0;
            self.reopens = 0;
            self.after_buffer_fixes = 0;
        }
        self.buffered_at = -1;
        self.pictures_moved = 0; // новый поток: обложка не сойдёт за фильм оттого, что прошлый двигался
        self.enter(State::Opening, 8_000);
    }

    pub fn buffering(&mut self, percent: f32) {
        if self.state == State::Paused || self.state == State::Ended {
            return;
        }
        if percent < 100.0 {
            if self.state != State::Opening {
                self.enter(State::Buffering, 4_000);
            }
        } else if self.state == State::Buffering {
            self.buffered_at = self.now();
            self.enter(State::Playing, 2_000);
        } else if self.state == State::Opening {
            self.enter(State::Playing, 4_000);
        }
    }

    pub fn playing(&mut self) {
        if self.state != State::Paused && self.state != State::Playing {
            self.enter(State::Playing, 4_000);
        }
    }

    pub fn paused(&mut self) {
        self.enter(State::Paused, 0)
    }

    pub fn resumed(&mut self) {
        self.enter(State::Playing, 5_000)
    }

    /// Любой прыжок (перемотка, глава, смена дорожки с перемоткой): плеер заново копит буфер.
    pub fn seeked(&mut self) {
        if self.state != State::Paused {
            self.enter(State::Playing, 5_000)
        } else {
            self.reset_measurements()
        }
    }

    pub fn ended(&mut self) {
        self.enter(State::Ended, 0)
    }

    pub fn closed(&mut self) {
        self.enter(State::Idle, 0)
    }

    /// Окно изменило размер / полный экран / новая поверхность: скоро проверить, что картинка ещё движется.
    pub fn surface_changed(&mut self) {
        self.surface_check = Some((self.now() + 1_500, self.last.map(|l| l.pictures).unwrap_or(-1)));
    }

    fn enter(&mut self, s: State, grace: i64) {
        self.state = s;
        if grace > 0 {
            self.grace_until = self.grace_until.max(self.now() + grace);
        }
        self.reset_measurements();
    }

    fn reset_measurements(&mut self) {
        self.last = None;
        self.silent_for = 0;
        self.frozen_for = 0;
        self.choppy_ticks = 0;
        self.stuck_for = 0;
    }

    // ---- проверка ----
    pub fn tick(&mut self, s: Stats) {
        let t = self.now();
        let prev = self.last;
        let prev_at = self.last_at;
        self.last = Some(s);
        self.last_at = t;
        if self.state == State::Recovering && t >= self.grace_until {
            self.state = State::Playing;
        }
        let Some(prev) = prev else { return };
        if s.pictures > prev.pictures && prev.pictures > 0 {
            self.pictures_moved += 1;
        }

        // Сразу после смены поверхности: один быстрый взгляд (и в льготный период после неё).
        if let Some((at, _)) = self.surface_check {
            if t >= at {
                self.surface_check = None;
                if self.state == State::Playing
                    && s.video_tracks > 0
                    && s.pictures > 0
                    && s.pictures == prev.pictures
                    && s.time_ms > prev.time_ms
                    && self.video_fixes < 3
                {
                    self.act("frozen picture after the video area changed", |me| {
                        me.video_fixes += 1;
                        me.actions.restart_video()
                    });
                    return;
                }
            }
        }

        if self.state != State::Playing || t < self.grace_until {
            self.silent_for = 0;
            self.frozen_for = 0;
            self.choppy_ticks = 0;
            self.stuck_for = 0;
            return;
        }
        let wall = (t - prev_at).max(1);
        let clock = s.time_ms - prev.time_ms;

        // 1. Часы стоят: «играет», а время не идёт (и буферизации нет).
        if clock <= 50 {
            self.stuck_for += wall;
            if self.stuck_for >= (self.stuck_limit_ms)() && self.reopens < 3 {
                let msg = format!("clock stuck for {} s", self.stuck_for / 1000);
                self.act(&msg, |me| {
                    me.reopens += 1;
                    let fresh = me.reopens >= 2;
                    me.actions.reopen(fresh)
                });
            }
            return;
        }
        self.stuck_for = 0;
        if clock < wall / 3 {
            return; // еле идут (догоняют после заминки): не время судить
        }

        // 2. Застывшая картинка: часы идут, кадров нет, а раньше картинка двигалась.
        if s.video_tracks > 0 && s.pictures > 0 && self.pictures_moved >= 3 {
            if s.pictures == prev.pictures {
                self.frozen_for += clock
            } else {
                self.frozen_for = 0
            }
            if self.frozen_for >= 3_000 {
                // Сразу после буферизации — известная беда декодера, не сломанный поток: новый декодер помогает
                // всегда, а переоткрытие только выбрасывало запас (12.10: 8 из 8 застываний — после буферизации).
                if self.buffered_at >= 0 && t - self.buffered_at < 30_000 && self.after_buffer_fixes < 20 {
                    self.act("frozen picture after buffering", |me| {
                        me.after_buffer_fixes += 1;
                        me.actions.restart_video()
                    });
                    return;
                }
                if self.video_fixes < 2 {
                    self.act("frozen picture", |me| {
                        me.video_fixes += 1;
                        me.actions.restart_video()
                    });
                    return;
                }
                if self.reopens < 3 {
                    self.act("frozen picture again", |me| {
                        me.reopens += 1;
                        let fresh = me.reopens >= 2;
                        me.actions.reopen(fresh)
                    });
                    return;
                }
            }
        }

        // 3. Дёрганая картинка: кадров намного меньше, чем в фильме.
        if s.video_tracks > 0 && s.expected_fps >= 15.0 && prev.pictures > 0 && self.pictures_moved >= 3 {
            let shown = (s.pictures - prev.pictures) as f64 * 1000.0 / clock as f64;
            if shown < s.expected_fps * 0.45 {
                self.choppy_ticks += 1
            } else {
                self.choppy_ticks = 0
            }
            if self.choppy_ticks >= 3 {
                let msg = format!("choppy picture: {shown:.1} of {:.1} fps", s.expected_fps);
                if self.video_fixes < 2 {
                    self.act(&msg, |me| {
                        me.video_fixes += 1;
                        me.actions.restart_video()
                    });
                    return;
                }
                if self.reopens < 3 {
                    self.act(&msg, |me| {
                        me.reopens += 1;
                        me.actions.notice("Восстанавливаем картинку…");
                        me.actions.reopen(true)
                    });
                    return;
                }
            }
        }

        // 4. Беззвучный фильм: настоящая дорожка, зритель звук не выключал, часы идут, до динамика ничего.
        if s.audio_tracks > 0 && !s.user_muted && (s.audio_decoded > 0 || s.pictures > 0) {
            if !s.audio_selected {
                if self.sound_fixes < 3 {
                    self.act("no audio track selected", |me| {
                        me.sound_fixes += 1;
                        me.actions.fix_sound()
                    });
                }
                return;
            }
            if s.audio_played == prev.audio_played {
                self.silent_for += clock
            } else {
                self.silent_for = 0
            }
            if self.silent_for >= 5_000 {
                if self.sound_fixes < 1 {
                    let msg = format!("silent for {} s", self.silent_for / 1000);
                    self.act(&msg, |me| {
                        me.sound_fixes += 1;
                        me.actions.fix_sound()
                    });
                    return;
                }
                if self.reopens < 3 {
                    self.act("silent again", |me| {
                        me.sound_fixes += 1;
                        me.reopens += 1;
                        me.actions.notice("Восстанавливаем звук…");
                        me.actions.reopen(false)
                    });
                }
            }
        }
    }

    fn act(&mut self, reason: &str, block: impl FnOnce(&mut Self)) {
        let t = self.now();
        if t - self.last_action < 8_000 {
            return; // никогда два восстановления подряд
        }
        self.last_action = t;
        let msg = format!(
            "supervisor: {reason} → action (video {}, sound {}, reopen {})",
            self.video_fixes, self.sound_fixes, self.reopens
        );
        self.actions.log(&msg);
        block(self);
        self.state = State::Recovering;
        self.grace_until = t + 6_000;
        self.reset_measurements();
    }
}

#[cfg(test)]
#[path = "playback_supervisor_tests.rs"]
mod tests;

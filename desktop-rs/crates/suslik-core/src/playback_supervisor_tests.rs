//! Перенос `PlaybackSupervisorTest.kt` и `SupervisorTest` из `CoreTest.kt`.

use super::*;
use std::sync::atomic::{AtomicI64, Ordering::SeqCst};
use std::sync::Arc;

#[derive(Default)]
struct Rec {
    log: Vec<String>,
    reopens: Vec<bool>,
    video_restarts: i32,
    sound_fixes: i32,
    notices: Vec<String>,
}

impl Actions for Rec {
    fn restart_video(&mut self) {
        self.video_restarts += 1;
        self.log.push("restartVideo".into());
    }
    fn fix_sound(&mut self) {
        self.sound_fixes += 1;
        self.log.push("fixSound".into());
    }
    fn reopen(&mut self, fresh: bool) {
        self.reopens.push(fresh);
        self.log.push(format!("reopen({fresh})"));
    }
    fn notice(&mut self, text: &str) {
        self.notices.push(text.into());
    }
    fn log(&mut self, _: &str) {}
}

fn supervisor(clock: &Arc<AtomicI64>, stuck_limit: i64) -> PlaybackSupervisor<Rec> {
    let c = clock.clone();
    PlaybackSupervisor::with_clock(Rec::default(), Box::new(move || c.load(SeqCst)), Box::new(move || stuck_limit))
}

// ---- PlaybackSupervisorTest: тик каждые 2 с, как его шлют плееры ----

struct Sim {
    sup: PlaybackSupervisor<Rec>,
    t: Arc<AtomicI64>,
    time: i64,
    pictures: i64,
    played: i64,
    audio_tracks: i32,
    audio_selected: bool,
    video_tracks: i32,
    user_muted: bool,
}

impl Sim {
    fn new(stuck_limit: i64) -> Sim {
        let t = Arc::new(AtomicI64::new(100_000));
        Sim {
            sup: supervisor(&t, stuck_limit),
            t,
            time: 0,
            pictures: 100,
            played: 100,
            audio_tracks: 0,
            audio_selected: true,
            video_tracks: 1,
            user_muted: false,
        }
    }

    /// Одна выборка через 2 с; по умолчанию фильм идёт нормально (24 к/с, звук идёт).
    fn tick_with(&mut self, clock: i64, new_pictures: i64, new_audio: i64) {
        self.t.fetch_add(2_000, SeqCst);
        self.time += clock;
        self.pictures += new_pictures;
        self.played += new_audio;
        self.sup.tick(Stats {
            time_ms: self.time,
            audio_played: self.played,
            audio_decoded: self.played,
            pictures: self.pictures,
            expected_fps: 0.0,
            audio_tracks: self.audio_tracks,
            audio_selected: self.audio_selected,
            video_tracks: self.video_tracks,
            user_muted: self.user_muted,
        });
    }

    fn ticks(&mut self, n: usize, clock: i64, new_pictures: i64, new_audio: i64) {
        for _ in 0..n {
            self.tick_with(clock, new_pictures, new_audio);
        }
    }

    /// Открыт, буфер полон, играет нормально, льготный период прошёл, картинка двигалась.
    fn warm(&mut self, episode: &str) {
        self.sup.opening(episode);
        self.sup.buffering(100.0);
        self.ticks(5, 2_000, 48, 100);
    }

    fn rec(&self) -> &Rec {
        &self.sup.actions
    }
}

#[test]
fn stuck_clock_reopens_after_the_limit() {
    let mut s = Sim::new(12_000);
    s.warm("a");
    s.ticks(5, 0, 48, 100);
    assert!(s.rec().reopens.is_empty());
    s.ticks(1, 0, 48, 100); // 6 × 2 с = 12 с
    assert_eq!(s.rec().reopens, vec![false]);
    assert_eq!(s.sup.state(), State::Recovering);
}

#[test]
fn nothing_is_judged_during_the_grace_period_after_a_seek() {
    let mut s = Sim::new(2_000);
    s.warm("a");
    s.sup.seeked(); // плеер заново копит буфер: часы стоят несколько секунд
    s.ticks(2, 0, 48, 100); // первая выборка, +2 с: ещё внутри 5 с льготы
    assert!(s.rec().reopens.is_empty());
    s.ticks(1, 0, 48, 100);
    assert_eq!(s.rec().reopens.len(), 1);
}

#[test]
fn surface_change_checks_the_picture_quickly() {
    let mut s = Sim::new(12_000);
    s.warm("a");
    s.sup.surface_changed();
    s.tick_with(2_000, 0, 100); // часы пошли, картинка нет
    assert_eq!(s.rec().video_restarts, 1);
}

#[test]
fn silent_film_fixes_sound_then_reopens() {
    let mut s = Sim::new(12_000);
    s.audio_tracks = 1;
    s.video_tracks = 0;
    s.warm("a");
    s.ticks(2, 2_000, 48, 0);
    assert_eq!(s.rec().sound_fixes, 0);
    s.tick_with(2_000, 48, 0); // 6 с фильма без сыгранного звука
    assert_eq!(s.rec().sound_fixes, 1);
    assert!(s.rec().reopens.is_empty());
    s.ticks(20, 2_000, 48, 0); // всё ещё тихо после исправления
    assert!(!s.rec().reopens[0]); // первое переоткрытие сохраняет запас
    assert!(s.rec().notices.iter().any(|n| n == "Восстанавливаем звук…"));
}

#[test]
fn silence_chosen_by_the_user_is_not_a_problem() {
    let mut s = Sim::new(12_000);
    s.audio_tracks = 1;
    s.user_muted = true;
    s.video_tracks = 0;
    s.warm("a");
    s.ticks(30, 2_000, 48, 0);
    assert_eq!(s.rec().sound_fixes, 0);
    assert!(s.rec().reopens.is_empty());
}

#[test]
fn missing_audio_selection_is_fixed() {
    let mut s = Sim::new(12_000);
    s.audio_tracks = 1;
    s.audio_selected = false;
    s.video_tracks = 0;
    s.warm("a");
    s.ticks(2, 2_000, 48, 100);
    assert!(s.rec().sound_fixes >= 1);
}

#[test]
fn reopen_budget_is_three_per_episode_and_resets_on_a_new_one() {
    let mut s = Sim::new(12_000);
    s.warm("a");
    s.ticks(100, 0, 48, 100);
    assert_eq!(s.rec().reopens, vec![false, true, true]); // следующие переоткрытия выбрасывают и запас

    s.sup.opening("a"); // та же серия: бюджет потрачен
    s.sup.buffering(100.0);
    s.ticks(60, 0, 48, 100);
    assert_eq!(s.rec().reopens.len(), 3);

    s.sup.opening("b"); // новая серия: новый бюджет
    s.sup.buffering(100.0);
    s.ticks(60, 0, 48, 100);
    assert!(s.rec().reopens.len() > 3);
}

// ---- SupervisorTest из CoreTest ----

struct Core {
    clock: Arc<AtomicI64>,
    sup: PlaybackSupervisor<Rec>,
    film: i64,
    audio: i64,
    pics: i64,
}

impl Core {
    fn new() -> Core {
        let clock = Arc::new(AtomicI64::new(0));
        Core { sup: supervisor(&clock, 12_000), clock, film: 0, audio: 0, pics: 0 }
    }

    /// 2 с по часам; `film_ms` часов фильма; счётчики звука и кадров растут, если надо.
    fn step_with(&mut self, film_ms: i64, sound: bool, pictures_per_sec: f64) {
        self.clock.fetch_add(2000, SeqCst);
        self.film += film_ms;
        if sound {
            self.audio += 40;
        }
        self.pics += (pictures_per_sec * film_ms as f64 / 1000.0) as i64;
        self.sup.tick(Stats {
            time_ms: self.film,
            audio_played: self.audio,
            audio_decoded: self.audio,
            pictures: self.pics,
            expected_fps: 24.0,
            audio_tracks: 1,
            audio_selected: true,
            video_tracks: 1,
            user_muted: false,
        });
    }

    fn step(&mut self) {
        self.step_with(2000, true, 24.0)
    }

    fn steps(&mut self, n: usize, film_ms: i64, sound: bool, pps: f64) {
        for _ in 0..n {
            self.step_with(film_ms, sound, pps);
        }
    }

    fn start_playing(&mut self) {
        self.sup.opening("ep1");
        self.sup.playing();
        for _ in 0..6 {
            self.step();
        }
    }

    fn log(&self) -> Vec<String> {
        self.sup.actions.log.clone()
    }
}

fn strs(v: &[&str]) -> Vec<String> {
    v.iter().map(|s| s.to_string()).collect()
}

#[test]
fn healthy_playback_does_nothing() {
    let mut c = Core::new();
    c.start_playing();
    c.steps(30, 2000, true, 24.0);
    assert!(c.log().is_empty());
}

#[test]
fn no_false_silence_after_seek() {
    let mut c = Core::new();
    c.start_playing();
    c.sup.seeked();
    // плеер заново наполняет буфер: часы и звук стоят несколько секунд — это не беззвучный фильм
    c.steps(2, 0, false, 0.0);
    c.steps(10, 2000, true, 24.0);
    assert!(c.log().is_empty());
}

#[test]
fn silent_film_fixed_gently() {
    let mut c = Core::new();
    c.start_playing();
    c.steps(4, 2000, false, 24.0);
    assert_eq!(c.log(), strs(&["fixSound"]));
    c.steps(8, 2000, false, 24.0);
    assert_eq!(c.log(), strs(&["fixSound", "reopen(false)"]));
}

#[test]
fn frozen_picture_restarts_video() {
    let mut c = Core::new();
    c.start_playing();
    c.steps(3, 2000, true, 0.0);
    assert_eq!(c.log(), strs(&["restartVideo"]));
}

#[test]
fn frozen_after_every_rebuffering_never_reopens() {
    let mut c = Core::new();
    c.start_playing();
    // 12.10: сеть медленнее фильма — буфер пустеет снова и снова, и после каждой буферизации картинка стоит, а звук
    // идёт. Каждый раз — новый декодер, никогда не переоткрытие.
    for _ in 0..6 {
        c.sup.buffering(40.0);
        c.step_with(0, false, 0.0);
        c.sup.buffering(100.0);
        c.steps(3, 2000, true, 0.0);
        c.steps(5, 2000, true, 24.0);
    }
    assert_eq!(c.log(), vec!["restartVideo".to_string(); 6]);
}

#[test]
fn frozen_again_reopens_keeping_the_reserve_first() {
    let mut c = Core::new();
    c.start_playing();
    c.steps(60, 2000, true, 0.0);
    assert_eq!(c.log()[..3], strs(&["restartVideo", "restartVideo", "reopen(false)"])[..]);
}

#[test]
fn choppy_then_fresh_reopen() {
    let mut c = Core::new();
    c.start_playing();
    c.steps(4, 2000, true, 5.0);
    assert_eq!(c.log(), strs(&["restartVideo"]));
    c.steps(6, 2000, true, 5.0);
    assert_eq!(c.log()[1], "restartVideo");
    c.steps(6, 2000, true, 5.0);
    assert_eq!(c.log().last().unwrap(), "reopen(true)");
}

#[test]
fn paused_is_never_judged() {
    let mut c = Core::new();
    c.start_playing();
    c.sup.paused();
    c.steps(20, 0, false, 0.0);
    assert!(c.log().is_empty());
}

#[test]
fn stuck_clock_reopens() {
    let mut c = Core::new();
    c.start_playing();
    c.steps(7, 0, false, 0.0);
    assert_eq!(c.log(), strs(&["reopen(false)"]));
}

#[test]
fn still_cover_image_is_not_frozen() {
    let mut c = Core::new();
    c.sup.opening("ep1");
    c.sup.playing();
    c.steps(20, 2000, true, 0.0); // звук и одна неподвижная картинка: кадры не двигаются
    assert!(c.log().is_empty());
}

#[test]
fn still_cover_after_a_moving_episode_is_not_frozen() {
    let mut c = Core::new();
    c.start_playing(); // фильм, где картинка движется
    c.sup.opening("ep2"); // дальше — звуковой файл с обложкой
    c.sup.playing();
    c.steps(20, 2000, true, 0.0);
    assert!(c.log().is_empty());
}

#[test]
fn budgets_stop_loops() {
    let mut c = Core::new();
    c.start_playing();
    c.steps(200, 2000, true, 0.0);
    assert!(c.log().len() <= 5, "{:?}", c.log());
}

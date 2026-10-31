//! Перенос `PartySyncEngineTest.kt`.
use super::super::protocol::PartyState;
use super::*;
use std::cell::RefCell;
use std::rc::Rc;

#[derive(Default)]
struct FakePlayer {
    time_ms: i64,
    busy: bool,
    engine_ms: i64,
    seek_stamp: i64,
    paused: bool,
    running: bool,
    user_rate: f32,
    seeks: Vec<i64>,
    rates: Vec<f32>,
}

impl FakePlayer {
    fn new() -> Self {
        Self { paused: true, running: true, user_rate: 1.0, ..Default::default() }
    }
    fn at(ms: i64) -> Self {
        Self { time_ms: ms, engine_ms: ms, ..Self::new() }
    }
    /// Пользователь перемотал сам.
    fn user_seek(&mut self, ms: i64) {
        self.time_ms = ms;
        self.engine_ms = ms;
        self.seek_stamp += 1;
    }
    /// Прошло `ms` воспроизведения.
    fn play(&mut self, ms: i64) {
        if !self.paused && self.running {
            self.time_ms += ms;
            self.engine_ms += ms;
        }
    }
}

impl SyncPlayer for FakePlayer {
    fn time_ms(&self) -> i64 {
        self.time_ms
    }
    fn busy(&self) -> bool {
        self.busy
    }
    fn engine_ms(&self) -> i64 {
        self.engine_ms
    }
    fn seek_stamp(&self) -> i64 {
        self.seek_stamp
    }
    fn paused(&self) -> bool {
        self.paused
    }
    fn running(&self) -> bool {
        self.running
    }
    fn user_rate(&self) -> f32 {
        self.user_rate
    }
    fn apply_paused(&mut self, paused: bool) {
        self.paused = paused;
    }
    fn seek_to(&mut self, ms: i64) {
        self.seeks.push(ms);
        self.time_ms = ms;
        self.engine_ms = ms;
        self.seek_stamp += 1;
    }
    fn set_rate(&mut self, rate: f32) {
        self.rates.push(rate);
    }
}

struct FakeLink {
    host: bool,
    state: PartyState,
    target: i64,
    others: bool,
    started_key: Option<String>,
    sent: Vec<String>,
    buffering: Vec<bool>,
}

impl FakeLink {
    fn new(host: bool, state: PartyState, target: i64) -> Self {
        Self { host, state, target, others: false, started_key: None, sent: vec![], buffering: vec![] }
    }
}

impl PartyLink for FakeLink {
    fn state(&self) -> PartyState {
        self.state
    }
    fn can_control(&self) -> bool {
        self.host
    }
    fn can_seek(&self) -> bool {
        self.host
    }
    fn target_ms(&self) -> i64 {
        self.target
    }
    fn others_buffering(&self) -> bool {
        self.others
    }
    fn take_started_by_me(&mut self, key: &str) -> bool {
        let hit = self.started_key.as_deref() == Some(key);
        if hit {
            self.started_key = None;
        }
        hit
    }
    fn play(&mut self, pos_ms: i64) {
        self.sent.push(format!("play {pos_ms}"));
    }
    fn pause(&mut self, pos_ms: i64) {
        self.sent.push(format!("pause {pos_ms}"));
    }
    fn seek(&mut self, pos_ms: i64) {
        self.sent.push(format!("seek {pos_ms}"));
    }
    fn report_buffering(&mut self, loading: bool) {
        if self.buffering.last() != Some(&loading) {
            self.buffering.push(loading);
        }
    }
}

const KEY: &str = "42|a.mkv";

fn st(playing: bool, pos: f64, seq: i64) -> PartyState {
    PartyState::new(playing, pos, 1.0, 0, seq)
}

#[test]
fn guest_joins_playing_room() {
    let mut p = FakePlayer::new();
    let mut e = PartySyncEngine::new(FakeLink::new(false, st(true, 60.0, 3), 60_000));
    e.tick(&mut p, KEY, 1_000);
    assert!(!p.paused);
    assert_eq!(p.seeks, [60_000]);
    assert!(e.link.sent.is_empty());
    e.tick(&mut p, KEY, 1_400);
    assert_eq!(e.link.buffering, [false, true]);
}

#[test]
fn guest_pause_stays_local() {
    let mut p = FakePlayer::at(10_000);
    let mut e = PartySyncEngine::new(FakeLink::new(false, st(true, 10.0, 1), 10_000));
    let mut now = 1_000;
    e.tick(&mut p, KEY, now);
    assert!(!p.paused);
    for _ in 0..3 {
        now += 400;
        p.play(400);
        e.link.target += 400;
        e.tick(&mut p, KEY, now);
    }
    p.paused = true;
    for _ in 0..10 {
        now += 400;
        e.link.target += 400;
        e.tick(&mut p, KEY, now);
    }
    assert!(p.paused);
    assert!(e.link.sent.is_empty());
    p.paused = false;
    now += 400;
    e.link.target += 400;
    e.tick(&mut p, KEY, now);
    assert!(!p.paused);
    assert_eq!(*p.seeks.last().unwrap(), e.link.target);
}

#[test]
fn host_pause_and_seek_go_to_room() {
    let mut p = FakePlayer::at(5_000);
    let mut e = PartySyncEngine::new(FakeLink::new(true, st(false, 5.0, 1), 5_000));
    e.tick(&mut p, KEY, 1_000);
    assert!(p.paused);
    e.tick(&mut p, KEY, 3_600);
    p.paused = false;
    e.tick(&mut p, KEY, 4_000);
    assert_eq!(e.link.sent, ["play 5000"]);
    e.link.state = st(true, 5.0, 2);
    p.user_seek(90_000);
    e.tick(&mut p, KEY, 4_400);
    assert_eq!(e.link.sent.last().unwrap(), "play 90000");
}

#[test]
fn started_by_me_starts_room_from_here() {
    let mut p = FakePlayer { paused: false, time_ms: 1_234, ..FakePlayer::new() };
    let mut l = FakeLink::new(true, PartyState::IDLE, 0);
    l.started_key = Some(KEY.into());
    let mut e = PartySyncEngine::new(l);
    e.tick(&mut p, KEY, 1_000);
    assert_eq!(e.link.sent, ["play 1234"]);
}

#[test]
fn small_drift_changes_rate() {
    let mut p = FakePlayer::at(10_000);
    let mut e = PartySyncEngine::new(FakeLink::new(false, st(true, 10.0, 1), 10_200));
    e.tick(&mut p, KEY, 1_000);
    assert!(p.seeks.is_empty());
    let mut now = 1_000;
    while now < 3_600 {
        now += 400;
        p.play(400);
        e.link.target += 400;
        e.tick(&mut p, KEY, now);
    }
    assert!(p.rates.is_empty());
    e.link.target = p.time_ms + 800;
    now += 400;
    p.play(400);
    e.link.target += 400;
    e.tick(&mut p, KEY, now);
    assert_eq!(p.rates, [1.05]);
    e.link.target = p.time_ms + 450;
    now += 400;
    p.play(400);
    e.tick(&mut p, KEY, now);
    assert_eq!(p.rates, [1.05, 1.0]);
    assert!(p.seeks.is_empty());
}

#[test]
fn stall_reports_buffering() {
    let mut p = FakePlayer::at(10_000);
    let mut e = PartySyncEngine::new(FakeLink::new(false, st(true, 10.0, 1), 10_000));
    e.tick(&mut p, KEY, 1_000);
    p.running = false;
    e.tick(&mut p, KEY, 1_400);
    assert_eq!(e.link.buffering, [false]);
    e.tick(&mut p, KEY, 2_600);
    assert_eq!(e.link.buffering, [false, true]);
    p.running = true;
    p.play(400);
    e.tick(&mut p, KEY, 3_000);
    assert_eq!(e.link.buffering, [false, true, false]);
}

#[test]
fn waits_for_others_and_idles() {
    let mut p = FakePlayer::at(10_000);
    let mut e = PartySyncEngine::new(FakeLink::new(false, st(true, 10.0, 1), 10_000));
    e.tick(&mut p, KEY, 1_000);
    assert!(!p.paused);
    e.link.state.waiting = true;
    e.link.others = true;
    e.tick(&mut p, KEY, 1_400);
    assert!(p.paused);
    e.idle(&mut p);
    assert_eq!(e.link.buffering.last(), Some(&false));
}

#[test]
fn seek_does_not_pause_when_no_others_buffering() {
    let mut p = FakePlayer { paused: false, ..FakePlayer::at(10_000) };
    let mut e = PartySyncEngine::new(FakeLink::new(true, st(true, 10.0, 1), 10_000));
    e.tick(&mut p, KEY, 1_000);
    assert!(!p.paused);
    p.user_seek(50_000);
    e.tick(&mut p, KEY, 4_000);
    e.link.state = PartyState { waiting: true, ..st(true, 50.0, 3) };
    e.link.others = false;
    e.link.target = 50_000;
    p.engine_ms = 50_200;
    p.running = true;
    e.tick(&mut p, KEY, 4_800);
    assert!(!p.paused);
}

#[test]
fn host_pause_works_immediately_without_waiting_own_ms() {
    let mut p = FakePlayer { paused: false, ..FakePlayer::at(10_000) };
    let mut e = PartySyncEngine::new(FakeLink::new(true, st(true, 10.0, 1), 10_000));
    e.tick(&mut p, KEY, 1_000);
    assert!(!p.paused);
    p.paused = true;
    e.tick(&mut p, KEY, 1_400);
    assert_eq!(e.link.sent, ["pause 10000"]);
}

#[test]
fn host_seek_while_paused_sends_pause_and_does_not_unpause() {
    let mut p = FakePlayer::at(10_000);
    let mut e = PartySyncEngine::new(FakeLink::new(true, st(false, 10.0, 1), 10_000));
    e.tick(&mut p, KEY, 1_000);
    assert!(p.paused);
    p.user_seek(60_000);
    e.tick(&mut p, KEY, 1_400);
    assert_eq!(e.link.sent.last().unwrap(), "pause 60000");
    e.link.state = st(false, 60.0, 2);
    e.link.target = 60_000;
    for step in 0..10 {
        e.tick(&mut p, KEY, 2_000 + step * 400);
        assert!(p.paused);
    }
}

#[test]
fn guest_seek_while_room_waits_is_not_done_until_loaded() {
    let mut p = FakePlayer::at(774_000);
    let mut l = FakeLink::new(false, PartyState { waiting: true, ..st(true, 1104.0, 3) }, 1_104_000);
    l.others = true;
    let mut e = PartySyncEngine::new(l);
    e.tick(&mut p, KEY, 1_000);
    assert_eq!(p.seeks, [1_104_000]);
    assert!(!p.paused);
    p.running = false;
    let mut now = 1_000;
    for _ in 0..5 {
        now += 400;
        e.tick(&mut p, KEY, now);
    }
    assert_eq!(e.link.buffering, [false, true]);
    p.running = true;
    for _ in 0..2 {
        now += 400;
        p.play(400);
        e.tick(&mut p, KEY, now);
    }
    assert_eq!(e.link.buffering, [false, true, false]);
    now += 400;
    p.play(400);
    e.tick(&mut p, KEY, now);
    assert!(p.paused);
}

#[test]
fn frozen_time_counts_as_loading() {
    let mut p = FakePlayer { paused: false, ..FakePlayer::at(5_208_700) };
    let mut l = FakeLink::new(true, PartyState::IDLE, 0);
    l.started_key = Some(KEY.into());
    let mut e = PartySyncEngine::new(l);
    e.tick(&mut p, KEY, 1_000);
    e.link.state = st(true, 5208.7, 2);
    e.link.target = 5_208_700;
    let mut now = 1_000;
    for _ in 0..4 {
        now += 400;
        e.link.target += 400;
        e.tick(&mut p, KEY, now);
    }
    assert_eq!(e.link.buffering, [false, true]);
    for _ in 0..6 {
        now += 400;
        e.tick(&mut p, KEY, now);
    }
    assert!(p.seeks.is_empty());
    p.play(400);
    now += 400;
    e.tick(&mut p, KEY, now);
    assert_eq!(e.link.buffering, [false, true, false]);
}

fn collecting(l: FakeLink) -> (PartySyncEngine<FakeLink>, Rc<RefCell<Vec<PartyNotice>>>) {
    let notes = Rc::new(RefCell::new(Vec::new()));
    let sink = notes.clone();
    (PartySyncEngine::with_callbacks(l, |_| {}, move |n| sink.borrow_mut().push(n)), notes)
}

#[test]
fn guest_sees_host_actions() {
    let mut p = FakePlayer::at(100_000);
    let (mut e, notes) = collecting(FakeLink::new(false, st(true, 100.0, 1), 100_000));
    let mut now = 1_000;
    e.tick(&mut p, KEY, now);
    let steps = [
        PartyState::new(false, 110.0, 1.0, 10_000, 2),
        PartyState::new(true, 110.0, 1.0, 20_000, 3),
        PartyState::new(true, 600.0, 1.0, 25_000, 4),
        PartyState { waiting: true, ..PartyState::new(true, 600.0, 1.0, 25_500, 5) },
    ];
    for s in steps {
        e.link.state = s;
        e.link.target = (s.pos * 1000.0) as i64;
        now += 400;
        p.play(400);
        e.tick(&mut p, KEY, now);
    }
    assert_eq!(*notes.borrow(), [PartyNotice::Paused, PartyNotice::Resumed, PartyNotice::Seeked(600_000)]);
}

#[test]
fn waiting_notice_once_after_two_seconds() {
    let mut p = FakePlayer::at(10_000);
    let (mut e, notes) = collecting(FakeLink::new(true, st(true, 10.0, 1), 10_000));
    let mut now = 1_000;
    e.tick(&mut p, KEY, now);
    e.link.state = PartyState { waiting: true, seq: 2, ..e.link.state };
    e.link.others = true;
    for _ in 0..4 {
        now += 400;
        e.tick(&mut p, KEY, now);
    }
    assert!(notes.borrow().is_empty());
    for _ in 0..10 {
        now += 400;
        e.tick(&mut p, KEY, now);
    }
    assert_eq!(*notes.borrow(), [PartyNotice::Waiting]);
}

//! Экран плеера (часть `PlayerView.kt`): видео на всё окно, верхняя панель с названием, нижняя — перемотка, время,
//! пауза, громкость, звук и субтитры, полный экран. Панели прячутся через 3 с без движения мыши во время показа.
//! Место сохраняется в `watch` (те же ключи, что у Kotlin-версии) каждые 10 с и при выходе. Сторож показа
//! (`PlaybackSupervisor`) и «копим запас» (`CatchUp`) — те же правила, что у Kotlin-версии.

use super::engine::{Engine, Ev, Track};
use super::mpv;
use super::video::{self, Surface, VideoOut};
use crate::library::Library;
use crate::store::Store;
use crate::strings::tr;
use crate::{player_log, reserve, theme};
use eframe::egui::{self, Color32, Key, Pos2, Rect, RichText, Sense, Stroke, Vec2};
use std::time::{Duration, Instant};
use suslik_core::burst_proxy;
use suslik_core::catch_up::CatchUp;
use suslik_core::clock_text;
use suslik_core::playback_presets;
use suslik_core::playback_supervisor::{Actions, PlaybackSupervisor};

/// Что играть и что о нём знает медиатека.
#[derive(Clone, Debug)]
pub struct PlayRequest {
    /// Подписанный адрес зеркала (`/api/file/mirror-url`).
    pub url: String,
    pub title: String,
    /// `magnetId|path` — прогресс в медиатеке.
    pub watch_key: Option<String>,
    /// «С начала»: сохранённое место в этот раз не берётся.
    pub from_start: bool,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Menu {
    Audio,
    Subs,
    Speed,
}

enum Cmd {
    RestartVideo,
    FixSound,
    Reopen(bool),
    Notice(String),
}

/// Действия сторожа копятся и выполняются после его тика (ему нельзя давать сам плеер).
#[derive(Default)]
struct Cmds(Vec<Cmd>);

impl Actions for Cmds {
    fn restart_video(&mut self) {
        self.0.push(Cmd::RestartVideo)
    }
    fn fix_sound(&mut self) {
        self.0.push(Cmd::FixSound)
    }
    fn reopen(&mut self, fresh: bool) {
        self.0.push(Cmd::Reopen(fresh))
    }
    fn notice(&mut self, text: &str) {
        self.0.push(Cmd::Notice(text.into()))
    }
    fn log(&mut self, message: &str) {
        player_log::write(message)
    }
}

const SPEEDS: [f32; 7] = [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0];

pub struct Player {
    req: PlayRequest,
    engine: Option<Engine>,
    video: Option<VideoOut>,
    ui_store: std::sync::Arc<Store>,
    /// Адрес, который открыл mpv (локальный запас или само зеркало).
    play_url: String,
    started: bool,
    time: i64,
    length: i64,
    playing: bool,
    paused: bool,
    buffering: Option<f32>,
    status: Option<String>,
    flash: Option<(String, Instant)>,
    failed: bool,
    finished: bool,
    volume: i64,
    muted: bool,
    rate: f32,
    night: bool,
    last_move: Instant,
    pointer: Option<Pos2>,
    drag: Option<f32>,
    last_save: Instant,
    menu: Option<Menu>,
    audio: Vec<Track>,
    subs: Vec<Track>,
    prefs_applied: bool,
    sup: PlaybackSupervisor<Cmds>,
    last_tick: Instant,
    catch_up: CatchUp,
    catching_up: bool,
    last_catch: Instant,
    fullscreen: bool,
    info: bool,
    info_text: String,
    info_at: Instant,
    start_at: i64,
}

fn settings_cache_ms() -> i64 {
    Store::of("settings")
        .get("cache_ms")
        .or_else(|| Store::of("settings").get("vlc_cache_ms"))
        .and_then(|v| v.parse().ok())
        .unwrap_or(playback_presets::CACHE_DEFAULT as i64)
}

fn settings_burst_mb() -> i64 {
    playback_presets::clamp_burst(
        Store::of("settings").get("burst_mb").and_then(|v| v.parse().ok()).unwrap_or(playback_presets::BURST_DEFAULT),
    ) as i64
}

impl Player {
    /// Плеер для `req`. Ошибка — когда нет libmpv или ядро не создалось (текст для зрителя).
    pub fn new(req: PlayRequest, lib: &Library, ctx: &egui::Context) -> Result<Player, String> {
        let api = mpv::api()
            .ok_or_else(|| "Видеодвижок mpv не найден (libmpv-2.dll). Переустановите приложение.".to_string())?;
        let ui_store = Store::of("player_ui");
        let sub_scale = ui_store.get("subScale").and_then(|v| v.parse().ok()).unwrap_or(100i64).clamp(50, 300);
        player_log::write(&format!("mpv: {}", api.file.display()));
        let engine = Engine::new(api, ctx.clone(), settings_cache_ms(), sub_scale)?;
        let saved = if req.from_start {
            0
        } else {
            req.watch_key
                .as_deref()
                .and_then(|k| lib.watch_info(k))
                .filter(|w| !w.watched())
                .map(|w| w.pos)
                .unwrap_or(0)
        };
        let volume = ui_store.get("volume").and_then(|v| v.parse().ok()).unwrap_or(100i64).clamp(0, 150);
        let rate = ui_store.get("rate").and_then(|v| v.parse::<f32>().ok()).unwrap_or(1.0).clamp(0.25, 4.0);
        let night = ui_store.get("night").as_deref() == Some("1");
        let url = req.url.clone();
        let catch_up_log = |m: &str| player_log::write(m);
        let catch_up = CatchUp::new(move |m: &str| catch_up_log(m));
        let stuck_url = url.clone();
        let sup = PlaybackSupervisor::with_clock(
            Cmds::default(),
            Box::new(crate::library::now_ms),
            Box::new(move || if burst_proxy::is_active(&stuck_url) { 30_000 } else { 12_000 }),
        );
        Ok(Player {
            play_url: url.clone(),
            req,
            engine: Some(engine),
            video: None,
            ui_store,
            started: false,
            time: saved,
            length: 0,
            playing: false,
            paused: false,
            buffering: None,
            status: Some(tr("player.otkryvaem", &[])),
            flash: None,
            failed: false,
            finished: false,
            volume,
            muted: false,
            rate,
            night,
            last_move: Instant::now(),
            pointer: None,
            drag: None,
            last_save: Instant::now(),
            menu: None,
            audio: Vec::new(),
            subs: Vec::new(),
            prefs_applied: false,
            sup,
            last_tick: Instant::now(),
            catch_up,
            catching_up: false,
            last_catch: Instant::now(),
            fullscreen: false,
            info: false,
            info_text: String::new(),
            info_at: Instant::now(),
            start_at: saved,
        })
    }

    fn engine(&self) -> &Engine {
        self.engine.as_ref().expect("engine")
    }

    // ---- выбор дорожек, как pref()/prefLanguage() у Kotlin-версии ----

    fn series_key(&self) -> String {
        match self.req.watch_key.as_deref().and_then(|k| k.split('|').next()).filter(|s| !s.trim().is_empty()) {
            Some(id) => format!("m{id}"),
            None => self.req.title.split(" · ").next().unwrap_or("").to_lowercase(),
        }
    }

    fn pref(&self, kind: &str) -> Option<String> {
        self.ui_store.get(&format!("{kind}:{}", self.series_key()))
    }

    fn pref_language(&self, kind: &str) -> Option<String> {
        let p = self.pref(kind).or_else(|| self.ui_store.get(&format!("{kind}:*")))?;
        let open = p.rfind('[')?;
        let close = p[open..].find(']')? + open;
        Some(p[open + 1..close].to_string()).filter(|l| !l.is_empty())
    }

    fn remember_track(&self, kind: &str, name: &str) {
        self.ui_store.put(&format!("{kind}:{}", self.series_key()), Some(name));
        self.ui_store.put(&format!("{kind}:*"), Some(name));
    }

    fn apply_prefs(&mut self) {
        let e = self.engine();
        if let Some(a) = self.pref("audio") {
            if let Some(t) = self.audio.iter().find(|t| t.description == a && !t.selected) {
                e.set_audio_track(t.id);
            }
        }
        match self.pref("spu").as_deref() {
            Some("__off__") => e.set_subtitle_track(-1),
            Some(s) => {
                if let Some(t) = self.subs.iter().find(|t| t.description == s && !t.selected) {
                    e.set_subtitle_track(t.id);
                }
            }
            None => {}
        }
    }

    fn refresh_tracks(&mut self) {
        if let Some(e) = &self.engine {
            self.audio = e.audio_tracks();
            self.subs = e.subtitle_tracks();
        }
    }

    // ---- открытие ----

    fn open(&mut self, lib: &Library, at: i64) {
        reserve::init(lib.api());
        burst_proxy::set_cap_bytes(settings_burst_mb() << 20);
        burst_proxy::keep_only(std::slice::from_ref(&self.req.url));
        self.play_url = burst_proxy::wrap(&self.req.url);
        player_log::write(&format!(
            "open {} at {}s, buffer={}ms, reserve={}MB{}",
            suslik_core::mirror_link::file_name(&self.req.url),
            at / 1000,
            settings_cache_ms(),
            settings_burst_mb(),
            if self.play_url.starts_with("http://127.0.0.1") { "" } else { " (direct)" }
        ));
        self.sup.opening(&self.req.url);
        self.catch_up.quiet(15_000);
        self.prefs_applied = false;
        self.failed = false;
        self.finished = false;
        self.status = Some(tr("player.otkryvaem", &[]));
        let (alang, slang) = (self.pref_language("audio"), self.pref_language("spu"));
        let e = self.engine();
        e.set_volume(self.volume);
        e.set_mute(self.muted);
        e.set_rate(self.rate);
        e.open(&self.play_url, at, self.night, alang.as_deref(), slang.as_deref());
    }

    fn reopen(&mut self, lib: &Library, fresh: bool) {
        let at = self.time;
        player_log::write(&format!(
            "supervisor: reopen at {}s{}",
            at / 1000,
            if fresh { " with a fresh reserve" } else { "" }
        ));
        if fresh {
            burst_proxy::drop_all();
        }
        self.open(lib, at);
    }

    fn save(&mut self, lib: &Library) {
        self.last_save = Instant::now();
        if let Some(k) = &self.req.watch_key {
            lib.save_watch(k, self.time, self.length);
        }
    }

    fn flash(&mut self, text: String) {
        self.flash = Some((text, Instant::now()));
    }

    fn seek(&mut self, t: i64) {
        let t = if self.length > 0 { t.clamp(0, self.length - 1000) } else { t.max(0) };
        self.time = t;
        self.engine().seek_to(t);
        self.sup.seeked();
        self.catch_up.quiet(10_000);
    }

    fn toggle_pause(&mut self) {
        if self.finished {
            self.finished = false;
            self.seek(0);
            self.engine().set_paused(false);
            return;
        }
        let p = !self.paused;
        self.paused = p;
        self.engine().set_paused(p);
        if p {
            self.sup.paused();
        } else {
            self.sup.resumed();
        }
    }

    fn set_volume(&mut self, v: i64) {
        self.volume = v.clamp(0, 150);
        self.muted = false;
        self.engine().set_mute(false);
        self.engine().set_volume(self.volume);
        self.ui_store.put("volume", Some(&self.volume.to_string()));
        self.flash(tr("player.gromkost_v", &[("v", &self.volume.to_string())]));
    }

    fn toggle_fullscreen(&mut self, ctx: &egui::Context) {
        self.fullscreen = !self.fullscreen;
        ctx.send_viewport_cmd(egui::ViewportCommand::Fullscreen(self.fullscreen));
    }

    // ---- события движка и сторожа ----

    fn on_events(&mut self, lib: &Library) {
        let evs = self.engine().drain();
        for e in evs {
            match e {
                Ev::Opened => {
                    self.refresh_tracks();
                    if !self.prefs_applied {
                        self.prefs_applied = true;
                        self.apply_prefs();
                        self.refresh_tracks();
                    }
                }
                Ev::Playing => {
                    self.playing = true;
                    self.paused = false;
                    self.buffering = None;
                    self.status = None;
                    self.sup.playing();
                }
                Ev::Paused => {
                    self.playing = false;
                    self.paused = true;
                }
                Ev::Buffering(p) => {
                    self.sup.buffering(p);
                    if p >= 100.0 {
                        self.buffering = None;
                    } else {
                        self.buffering = Some(self.buffering.map_or(p, |b| b.max(p)));
                    }
                }
                Ev::Time(t) => {
                    if self.drag.is_none() {
                        self.time = t;
                    }
                }
                Ev::Length(l) => {
                    self.length = l;
                    burst_proxy::set_duration(&self.req.url, l);
                }
                Ev::SeekDone => self.sup.seeked(),
                Ev::TracksChanged => self.refresh_tracks(),
                Ev::Finished => {
                    if !self.finished {
                        self.finished = true;
                        self.playing = false;
                        self.sup.ended();
                        if self.length > 0 {
                            self.time = self.length;
                        }
                        self.save(lib);
                        self.status = Some(tr("player.konec", &[]));
                    }
                }
                Ev::Failed(msg) => {
                    player_log::write(&format!("[mpv] failed: {msg}"));
                    self.failed = true;
                    self.playing = false;
                    self.status = Some(tr("player.oshibka_vosproizvedeniya_potok", &[]));
                }
            }
        }
    }

    fn supervise(&mut self, lib: &Library) {
        if self.last_tick.elapsed() >= Duration::from_secs(2) {
            self.last_tick = Instant::now();
            let user_muted = self.muted || self.volume == 0;
            if let Some(s) = self.engine().supervisor_stats(user_muted) {
                self.sup.tick(s);
            }
            for c in std::mem::take(&mut self.sup.actions.0) {
                match c {
                    Cmd::RestartVideo => self.engine().restart_video_output(),
                    Cmd::FixSound => {
                        let e = self.engine();
                        e.set_mute(self.muted);
                        e.set_volume(self.volume);
                        if !self.audio.iter().any(|t| t.selected) {
                            if let Some(t) = self.audio.first() {
                                e.set_audio_track(t.id);
                            }
                        }
                    }
                    Cmd::Reopen(fresh) => self.reopen(lib, fresh),
                    Cmd::Notice(t) => self.flash(t),
                }
            }
        }
        // «Копим запас»: сеть медленнее фильма и запас пуст — держим фильм, пока не накопится ~30 с.
        if self.last_catch.elapsed() >= Duration::from_secs(1) {
            self.last_catch = Instant::now();
            let url = self.req.url.clone();
            if !self.catching_up {
                let can = self.playing && !self.paused && self.drag.is_none();
                if self.catch_up.maybe_start(&url, can) {
                    self.catching_up = true;
                }
            }
            if self.catching_up {
                let p = self.catch_up.progress(&url);
                if p < 0 || self.paused {
                    self.catching_up = false;
                    let restart = self.catch_up.end(&url, p < 0 && self.time > 0);
                    self.status = None;
                    if restart {
                        let t = self.engine().time_ms();
                        self.seek(t);
                    }
                } else {
                    self.status = Some(tr("player.set_medlennee_filma_kopim", &[("p", &p.to_string())]));
                }
            }
        }
        if self.playing && self.last_save.elapsed() >= Duration::from_secs(10) {
            self.save(lib);
        }
    }

    // ---- клавиши (docs/DESKTOP.md §5) ----

    fn keys(&mut self, ctx: &egui::Context) -> bool {
        let mut close = false;
        let (space, left, right, up, down, shift, m, f, esc, i, enter, k) = ctx.input(|i| {
            (
                i.key_pressed(Key::Space),
                i.key_pressed(Key::ArrowLeft),
                i.key_pressed(Key::ArrowRight),
                i.key_pressed(Key::ArrowUp),
                i.key_pressed(Key::ArrowDown),
                i.modifiers.shift,
                i.key_pressed(Key::M),
                i.key_pressed(Key::F) || i.key_pressed(Key::F11),
                i.key_pressed(Key::Escape),
                i.key_pressed(Key::I),
                i.key_pressed(Key::Enter),
                i.key_pressed(Key::K),
            )
        });
        if space || k {
            self.toggle_pause();
        }
        let step = if shift { 60_000 } else { 10_000 };
        if left {
            self.seek(self.time - step);
            self.flash(format!("−{} сек  ·  {}", step / 1000, clock_text(self.time)));
        }
        if right {
            self.seek(self.time + step);
            self.flash(format!("+{} сек  ·  {}", step / 1000, clock_text(self.time)));
        }
        if up {
            self.set_volume(self.volume + 5);
        }
        if down {
            self.set_volume(self.volume - 5);
        }
        if m {
            self.muted = !self.muted;
            self.engine().set_mute(self.muted);
            self.flash(tr(
                if self.muted { "player.bez_zvuka" } else { "player.zvuk_volume" },
                &[("volume", &self.volume.to_string())],
            ));
        }
        if f || enter {
            self.toggle_fullscreen(ctx);
        }
        if i {
            self.info = !self.info;
        }
        if esc {
            if self.menu.is_some() {
                self.menu = None;
            } else if self.fullscreen {
                self.toggle_fullscreen(ctx);
            } else {
                close = true;
            }
        }
        if space || left || right || up || down || m || i {
            self.last_move = Instant::now();
        }
        close
    }

    /// Кадр окна. `false` — плеер закрыт (тогда уже всё освобождено).
    pub fn show(
        &mut self,
        ctx: &egui::Context,
        frame: &mut eframe::Frame,
        lib: &Library,
        surface: &mut Option<Surface>,
    ) -> bool {
        let Some(gl) = frame.gl().cloned() else {
            self.status = Some("Нет OpenGL: плеер не может показать видео".into());
            return self.show_error(ctx, lib);
        };
        if surface.is_none() {
            *surface = Surface::new(&gl, frame);
        }
        if !self.started {
            self.started = true;
            let e = self.engine();
            match VideoOut::start(&gl, e.api.clone(), e.handle(), ctx.clone()) {
                Ok(v) => {
                    let (renderer, soft) = video::gl_renderer(&gl);
                    player_log::write(&format!("[video] OpenGL: {renderer}{}", if soft { " (software)" } else { "" }));
                    if soft {
                        e.set_simple_render();
                    }
                    self.video = Some(v)
                }
                Err(err) => {
                    player_log::write(&format!("[video] {err}"));
                    self.status = Some(format!("{}: {err}", tr("player.ne_udalos_vosproizvesti_potok", &[])));
                    self.failed = true;
                }
            }
            if self.video.is_some() {
                let at = self.start_at;
                self.open(lib, at);
            }
        }
        self.on_events(lib);
        self.supervise(lib);
        let close = self.keys(ctx);

        // движение мыши показывает панели
        let pointer = ctx.input(|i| i.pointer.hover_pos());
        if pointer != self.pointer {
            self.pointer = pointer;
            self.last_move = Instant::now();
        }
        let bars = self.menu.is_some()
            || !self.playing
            || self.drag.is_some()
            || self.last_move.elapsed() < Duration::from_secs(3);
        if !bars {
            ctx.set_cursor_icon(egui::CursorIcon::None);
        }

        let mut close_clicked = false;
        egui::CentralPanel::default().frame(egui::Frame::none().fill(Color32::BLACK)).show(ctx, |ui| {
            let rect = ui.max_rect();
            let ppp = ctx.pixels_per_point();
            let (w, h) = ((rect.width() * ppp).round() as i32, (rect.height() * ppp).round() as i32);
            if let (Some(v), Some(s)) = (self.video.as_mut(), surface.as_mut()) {
                v.render(&gl, s, w, h);
                if v.has_frame {
                    ui.painter().image(s.id, rect, Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0)), Color32::WHITE);
                }
            }
            let resp = ui.interact(rect, ui.id().with("video"), Sense::click());
            if resp.double_clicked() {
                self.toggle_fullscreen(ctx);
            } else if resp.clicked() {
                if self.menu.is_some() {
                    self.menu = None;
                } else {
                    self.toggle_pause();
                }
            }
            if resp.hovered() {
                let scroll = ctx.input(|i| i.raw_scroll_delta.y);
                if scroll.abs() > 0.5 {
                    self.set_volume(self.volume + if scroll > 0.0 { 5 } else { -5 });
                }
            }
            self.center_status(ui, rect);
            if bars {
                close_clicked = self.top_bar(ui, rect);
                self.bottom_bar(ui, rect);
            }
            if self.info {
                self.info_panel(ui, rect);
            }
        });
        // время и скрытие панелей идут и без событий
        ctx.request_repaint_after(Duration::from_millis(250));
        if close || close_clicked {
            self.close(ctx, lib);
            return false;
        }
        true
    }

    fn show_error(&mut self, ctx: &egui::Context, lib: &Library) -> bool {
        let mut back = false;
        egui::CentralPanel::default().frame(egui::Frame::none().fill(Color32::BLACK)).show(ctx, |ui| {
            ui.centered_and_justified(|ui| {
                ui.vertical_centered(|ui| {
                    ui.label(RichText::new(self.status.clone().unwrap_or_default()).color(theme::BAD));
                    back = ui.button(tr("player.nazad", &[])).clicked();
                });
            });
        });
        if back || ctx.input(|i| i.key_pressed(Key::Escape)) {
            self.close(ctx, lib);
            return false;
        }
        true
    }

    fn center_status(&mut self, ui: &mut egui::Ui, rect: Rect) {
        let text = if let Some(b) = self.buffering.filter(|_| !self.paused) {
            Some(tr("player.buferizaciya_p", &[("p", &format!("{}", b as i64))]))
        } else {
            self.status.clone()
        };
        let flash =
            self.flash.as_ref().filter(|(_, at)| at.elapsed() < Duration::from_millis(1500)).map(|(t, _)| t.clone());
        let painter = ui.painter();
        if let Some(t) = text {
            pill(painter, rect.center(), &t, 17.0);
        }
        if let Some(t) = flash {
            pill(painter, Pos2::new(rect.center().x, rect.top() + 110.0), &t, 15.0);
        }
        if self.paused && self.status.is_none() && self.buffering.is_none() {
            let c = rect.center();
            painter.circle_filled(c, 40.0, Color32::from_black_alpha(140));
            let s = 16.0;
            painter.rect_filled(
                Rect::from_center_size(c + Vec2::new(-8.0, 0.0), Vec2::new(8.0, 2.0 * s)),
                2.0,
                Color32::WHITE,
            );
            painter.rect_filled(
                Rect::from_center_size(c + Vec2::new(8.0, 0.0), Vec2::new(8.0, 2.0 * s)),
                2.0,
                Color32::WHITE,
            );
        }
    }

    /// Верхняя панель: «Назад» и название. `true` — нажали «Назад».
    fn top_bar(&mut self, ui: &mut egui::Ui, rect: Rect) -> bool {
        let bar = Rect::from_min_size(rect.min, Vec2::new(rect.width(), 64.0));
        ui.painter().rect_filled(bar, 0.0, Color32::from_black_alpha(150));
        let mut back = false;
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(bar.shrink2(Vec2::new(16.0, 14.0))), |ui| {
            ui.horizontal_centered(|ui| {
                back = ui
                    .add(egui::Button::new(RichText::new("‹  ".to_string() + &tr("player.nazad", &[])).size(15.0)))
                    .on_hover_text(tr("player.v_biblioteku_esc", &[]))
                    .clicked();
                ui.add_space(12.0);
                ui.label(RichText::new(&self.req.title).font(theme::heading(18.0)).color(Color32::WHITE));
            });
        });
        back
    }

    fn bottom_bar(&mut self, ui: &mut egui::Ui, rect: Rect) {
        let h = 104.0;
        let bar = Rect::from_min_max(Pos2::new(rect.left(), rect.bottom() - h), rect.max);
        ui.painter().rect_filled(bar, 0.0, Color32::from_black_alpha(170));
        let inner = bar.shrink2(Vec2::new(20.0, 12.0));
        // перемотка
        let seek_rect = Rect::from_min_size(inner.min, Vec2::new(inner.width(), 22.0));
        self.seek_bar(ui, seek_rect);
        let row = Rect::from_min_max(Pos2::new(inner.left(), seek_rect.bottom() + 10.0), inner.max);
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(row), |ui| {
            ui.horizontal_centered(|ui| {
                let icon = if self.paused || self.finished { "▶" } else { "⏸" };
                let tip = tr(if self.paused { "player.vosproizvedenie_probel" } else { "player.pauza_probel" }, &[]);
                if ui
                    .add(egui::Button::new(RichText::new(icon).size(20.0)).min_size(Vec2::new(44.0, 40.0)))
                    .on_hover_text(tip)
                    .clicked()
                {
                    self.toggle_pause();
                }
                if ui
                    .add(egui::Button::new("−10").min_size(Vec2::new(44.0, 40.0)))
                    .on_hover_text(tr("player.nazad_na_sek", &[]))
                    .clicked()
                {
                    self.seek(self.time - 10_000);
                }
                if ui
                    .add(egui::Button::new("+10").min_size(Vec2::new(44.0, 40.0)))
                    .on_hover_text(tr("player.vpered_na_sek", &[]))
                    .clicked()
                {
                    self.seek(self.time + 10_000);
                }
                ui.add_space(8.0);
                let shown = self.drag.map(|f| (f as f64 * self.length as f64) as i64).unwrap_or(self.time);
                let len = if self.length > 0 { clock_text(self.length) } else { "--:--".into() };
                ui.label(RichText::new(format!("{} / {len}", clock_text(shown))).size(15.0).color(Color32::WHITE));
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let fs =
                        if self.fullscreen { "player.vyyti_iz_polnoekrannogo_rezhim" } else { "player.na_ves_ekran" };
                    if ui
                        .add(egui::Button::new("⛶").min_size(Vec2::new(40.0, 40.0)))
                        .on_hover_text(tr(fs, &[]))
                        .clicked()
                    {
                        self.toggle_fullscreen(ui.ctx());
                    }
                    let speed = format!("{}×", trim_rate(self.rate));
                    if ui
                        .add(egui::Button::new(speed).min_size(Vec2::new(52.0, 40.0)))
                        .on_hover_text(tr("player.skorost", &[]))
                        .clicked()
                    {
                        self.toggle_menu(Menu::Speed);
                    }
                    if ui.add(egui::Button::new(tr("player.subtitry", &[])).min_size(Vec2::new(0.0, 40.0))).clicked() {
                        self.toggle_menu(Menu::Subs);
                    }
                    if ui
                        .add(egui::Button::new(tr("player.audiodorozhki", &[])).min_size(Vec2::new(0.0, 40.0)))
                        .clicked()
                    {
                        self.toggle_menu(Menu::Audio);
                    }
                    let mut v = self.volume as f32;
                    let slider = ui.add(egui::Slider::new(&mut v, 0.0..=150.0).show_value(false));
                    if slider.changed() {
                        self.set_volume(v as i64);
                    }
                    let mute = if self.muted || self.volume == 0 { "🔇" } else { "🔊" };
                    if ui.add(egui::Button::new(mute).min_size(Vec2::new(40.0, 40.0))).clicked() {
                        self.muted = !self.muted;
                        self.engine().set_mute(self.muted);
                    }
                });
            });
        });
        if let Some(m) = self.menu {
            self.menu_panel(ui, Pos2::new(bar.right() - 20.0, bar.top() - 8.0), m);
        }
    }

    fn toggle_menu(&mut self, m: Menu) {
        if self.menu == Some(m) {
            self.menu = None;
        } else {
            self.menu = Some(m);
            self.refresh_tracks();
        }
    }

    fn menu_panel(&mut self, ui: &mut egui::Ui, anchor: Pos2, m: Menu) {
        let mut pick_audio = None;
        let mut pick_sub = None;
        let mut pick_rate = None;
        egui::Area::new(ui.id().with("player-menu"))
            .pivot(egui::Align2::RIGHT_BOTTOM)
            .fixed_pos(anchor)
            .order(egui::Order::Foreground)
            .show(ui.ctx(), |ui| {
                egui::Frame::popup(ui.style()).fill(Color32::from_rgba_unmultiplied(20, 20, 24, 235)).show(ui, |ui| {
                    ui.set_min_width(260.0);
                    match m {
                        Menu::Audio => {
                            ui.label(RichText::new(tr("player.audiodorozhki", &[])).strong());
                            if self.audio.is_empty() {
                                ui.label(tr("player.dorozhki_esche_ne_opredeleny", &[]));
                            }
                            for t in &self.audio {
                                if ui.selectable_label(t.selected, &t.description).clicked() {
                                    pick_audio = Some(t.clone());
                                }
                            }
                        }
                        Menu::Subs => {
                            ui.label(RichText::new(tr("player.subtitry", &[])).strong());
                            let none = !self.subs.iter().any(|t| t.selected);
                            if ui.selectable_label(none, tr("player.vyklyuchit", &[])).clicked() {
                                pick_sub = Some(None);
                            }
                            for t in &self.subs {
                                if ui.selectable_label(t.selected, &t.description).clicked() {
                                    pick_sub = Some(Some(t.clone()));
                                }
                            }
                        }
                        Menu::Speed => {
                            ui.label(RichText::new(tr("player.skorost", &[])).strong());
                            for s in SPEEDS {
                                if ui
                                    .selectable_label((self.rate - s).abs() < 0.01, format!("{}×", trim_rate(s)))
                                    .clicked()
                                {
                                    pick_rate = Some(s);
                                }
                            }
                        }
                    }
                });
            });
        if let Some(t) = pick_audio {
            self.engine().set_audio_track(t.id);
            self.remember_track("audio", &t.description);
            self.sup.seeked();
            self.menu = None;
        }
        if let Some(s) = pick_sub {
            match s {
                Some(t) => {
                    self.engine().set_subtitle_track(t.id);
                    self.remember_track("spu", &t.description);
                }
                None => {
                    self.engine().set_subtitle_track(-1);
                    self.remember_track("spu", "__off__");
                }
            }
            self.menu = None;
        }
        if let Some(r) = pick_rate {
            self.rate = r;
            self.engine().set_rate(r);
            self.ui_store.put("rate", Some(&r.to_string()));
            self.menu = None;
        }
    }

    /// Полоса перемотки: сыграно, скачано впереди (запас на диске), подсказка времени под мышью.
    fn seek_bar(&mut self, ui: &mut egui::Ui, rect: Rect) {
        let resp = ui.interact(rect, ui.id().with("seek"), Sense::click_and_drag());
        let track = Rect::from_center_size(rect.center(), Vec2::new(rect.width(), 5.0));
        let painter = ui.painter();
        painter.rect_filled(track, 3.0, Color32::from_white_alpha(50));
        let len = self.length;
        let frac_at = |x: f32| ((x - track.left()) / track.width()).clamp(0.0, 1.0);
        if len > 0 {
            let ahead = burst_proxy::ahead_ms(&self.req.url, len);
            let buffered = ((self.time + ahead) as f32 / len as f32).clamp(0.0, 1.0);
            let mut b = track;
            b.set_right(track.left() + track.width() * buffered);
            painter.rect_filled(b, 3.0, Color32::from_white_alpha(90));
            let played = self.drag.unwrap_or((self.time as f32 / len as f32).clamp(0.0, 1.0));
            let mut p = track;
            p.set_right(track.left() + track.width() * played);
            painter.rect_filled(p, 3.0, Color32::WHITE);
            let r = if resp.hovered() || resp.dragged() { 8.0 } else { 6.0 };
            painter.circle_filled(Pos2::new(p.right(), track.center().y), r, Color32::WHITE);
            if let Some(pos) = resp.hover_pos() {
                let t = (frac_at(pos.x) as f64 * len as f64) as i64;
                let at = Pos2::new(pos.x, rect.top() - 16.0);
                pill(painter, at, &clock_text(t), 13.0);
            }
            if resp.dragged() || resp.drag_started() {
                if let Some(pos) = resp.interact_pointer_pos() {
                    self.drag = Some(frac_at(pos.x));
                }
            }
            if resp.drag_stopped() || resp.clicked() {
                let f = resp.interact_pointer_pos().map(|p| frac_at(p.x)).or(self.drag);
                self.drag = None;
                if let Some(f) = f {
                    self.seek((f as f64 * len as f64) as i64);
                }
            }
        }
        if !self.playing && !self.paused && len <= 0 {
            painter.rect_stroke(track, 3.0, Stroke::new(1.0_f32, Color32::from_white_alpha(40)));
        }
    }

    fn info_panel(&mut self, ui: &mut egui::Ui, rect: Rect) {
        if self.info_at.elapsed() >= Duration::from_secs(1) || self.info_text.is_empty() {
            self.info_at = Instant::now();
            let mut t = self.engine().stats_line();
            if burst_proxy::is_active(&self.req.url) {
                let ahead = burst_proxy::ahead_mb(&self.req.url).unwrap_or(0.0);
                t.push_str(&format!("\nзапас на диске: {ahead:.0} МБ вперёд (из {} МБ)", settings_burst_mb()));
                if let Some(b) = burst_proxy::bitrate_mbit(&self.req.url) {
                    t.push_str(&format!(" · видео {b:.1} Мбит/с"));
                }
            } else {
                t.push_str(&tr("player.bez_zapasa_napryamuyu", &[]));
            }
            self.info_text = t;
        }
        let at = Rect::from_min_size(rect.min + Vec2::new(16.0, 76.0), Vec2::new(rect.width().min(720.0), 80.0));
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(at), |ui| {
            egui::Frame::none().fill(Color32::from_black_alpha(180)).rounding(8.0).inner_margin(10.0).show(ui, |ui| {
                ui.label(RichText::new(&self.info_text).monospace().size(12.0).color(Color32::WHITE));
            });
        });
    }

    /// Закрыть: место — в медиатеку, запас остаётся на диске до другого фильма, ядро mpv уничтожается в фоне.
    pub fn close(&mut self, ctx: &egui::Context, lib: &Library) {
        if self.time > 0 && !self.failed {
            self.save(lib);
        }
        if self.catching_up {
            self.catch_up.end(&self.req.url, false);
        }
        burst_proxy::clear();
        if self.fullscreen {
            ctx.send_viewport_cmd(egui::ViewportCommand::Fullscreen(false));
        }
        let freed = match self.video.take() {
            Some(v) => {
                v.free();
                true
            }
            None => true,
        };
        if let Some(e) = self.engine.take() {
            e.stop();
            e.release(freed);
        }
        player_log::write("player closed");
    }
}

fn trim_rate(r: f32) -> String {
    let s = format!("{r:.2}");
    s.trim_end_matches('0').trim_end_matches('.').to_string()
}

/// Надпись на полупрозрачной плашке по центру `at`.
fn pill(painter: &egui::Painter, at: Pos2, text: &str, size: f32) {
    let galley = painter.layout_no_wrap(text.to_string(), egui::FontId::proportional(size), Color32::WHITE);
    let r = Rect::from_center_size(at, galley.size() + Vec2::new(28.0, 14.0));
    painter.rect_filled(r, r.height() / 2.0, Color32::from_black_alpha(170));
    painter.galley(r.center() - galley.size() / 2.0, galley, Color32::WHITE);
}

//! Видеодвижок на libmpv — как `MpvEngine.kt`. Кадры рисует сам mpv (`vo=libmpv`, render API) в GL-контексте окна
//! egui, см. [`super::video`]. События ядра приходят в потоке `mpv-events` и копятся в очереди, окно разбирает её
//! каждый кадр.

use super::mpv::{self, Api, Handle};
use crate::{app_dir, player_log};
use eframe::egui;
use std::ffi::CStr;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering::SeqCst};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};
use suslik_core::playback_supervisor::Stats;

/// События движка (как `EngineListener`).
#[derive(Debug, Clone, PartialEq)]
pub enum Ev {
    Opened,
    Playing,
    Paused,
    /// 0..100; 100 — буферизация кончилась.
    Buffering(f32),
    Time(i64),
    Length(i64),
    SeekDone,
    TracksChanged,
    Finished,
    Failed(String),
}

/// Дорожка: `id` движка, -1 — «выключено».
#[derive(Debug, Clone, PartialEq)]
pub struct Track {
    pub id: i64,
    pub description: String,
    pub selected: bool,
}

#[derive(Clone, Copy)]
struct H(Handle);
unsafe impl Send for H {}
unsafe impl Sync for H {}

impl H {
    /// Через метод: замыкание тогда забирает весь `H` (Send), а не его поле.
    fn ptr(self) -> Handle {
        self.0
    }
}

struct F64(AtomicU64);
impl F64 {
    fn get(&self) -> f64 {
        f64::from_bits(self.0.load(SeqCst))
    }
    fn set(&self, v: f64) {
        self.0.store(v.to_bits(), SeqCst)
    }
}

struct Shared {
    loaded: AtomicBool,
    pause: AtomicBool,
    cache_wait: AtomicBool,
    playing_sent: AtomicBool,
    cache_pct: F64,
    time_sec: F64,
    length_sec: F64,
    last_time_event: AtomicI64,
    dead: AtomicBool,
    tx: Mutex<Sender<Ev>>,
    ctx: egui::Context,
}

impl Shared {
    fn send(&self, e: Ev) {
        let _ = self.tx.lock().unwrap_or_else(|e| e.into_inner()).send(e);
        self.ctx.request_repaint();
    }

    /// `playing` — когда ядро правда крутит кадры: не пауза и не ожидание кэша.
    fn maybe_playing(&self) {
        if self.loaded.load(SeqCst) && !self.pause.load(SeqCst) && !self.cache_wait.load(SeqCst) {
            self.playing_sent.store(true, SeqCst);
            self.send(Ev::Playing);
        }
    }
}

pub struct Engine {
    pub api: Arc<Api>,
    h: H,
    st: Arc<Shared>,
    rx: Receiver<Ev>,
    events: Option<std::thread::JoinHandle<()>>,
}

const NIGHT: &str = "lavfi=[acompressor=threshold=0.063:ratio=6:attack=25:release=100:knee=2:makeup=3]";

impl Engine {
    /// Ядро с теми же настройками, что у Kotlin-версии. `cache_ms` — буфер плеера из «Настроек».
    pub fn new(api: Arc<Api>, ctx: egui::Context, cache_ms: i64, sub_scale: i64) -> Result<Engine, String> {
        let p = unsafe { (api.create)() };
        if p.is_null() {
            return Err("mpv_create".into());
        }
        let opt = |n: &str, v: &str| {
            api.set_option(p, n, v);
        };
        opt("vo", "libmpv");
        // SUSLIK_MPV_HWDEC — только для проверки (например, no или nvdec-copy)
        let hw = std::env::var("SUSLIK_MPV_HWDEC").ok().filter(|v| !v.trim().is_empty());
        opt("hwdec", hw.as_deref().unwrap_or("auto-safe"));
        opt("log-file", &app_dir::file("mpv.log").to_string_lossy());
        // Каждый запрос с подписанным адресом — только в Suslik Debug; обычная сборка пишет предупреждения и ошибки.
        opt("msg-level", if cfg!(feature = "diag") { "all=v" } else { "all=warn" });
        opt("keep-open", "yes"); // в конце файла остаёмся на последнем кадре, `finished` сообщаем сами
        opt("idle", "yes");
        for (n, v) in [
            ("osc", "no"),
            ("osd-level", "0"),
            ("input-default-bindings", "no"),
            ("input-vo-keyboard", "no"),
            ("input-cursor", "no"),
            ("cursor-autohide", "no"),
            ("terminal", "no"),
            ("sub-auto", "no"),
            ("audio-file-auto", "no"),
            ("cache", "yes"),
        ] {
            opt(n, v);
        }
        let secs = format!("{:.1}", (cache_ms as f64 / 1000.0).max(1.0));
        opt("demuxer-readahead-secs", &secs);
        opt("cache-secs", &secs);
        opt("cache-pause", "yes");
        opt("cache-pause-initial", "yes");
        opt("cache-pause-wait", "1");
        // без перемотки назад за субтитрами при смене дорожки / перемотке: большие диапазоны по HTTP
        opt("demuxer-mkv-subtitle-preroll", "no");
        opt("demuxer-mkv-subtitle-preroll-secs", "1");
        // прошлые ключевые кадры в памяти: смена дорожки и короткая перемотка без сети (длинный запас — на диске)
        opt("demuxer-max-bytes", "96MiB");
        opt("demuxer-max-back-bytes", "24MiB");
        opt("gapless-audio", "yes");
        opt("audio-stream-silence", "yes");
        opt("network-timeout", "30");
        opt("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5");
        opt("sub-scale", &format!("{}", sub_scale as f64 / 100.0));
        opt("audio-client-name", "Suslik Media");
        opt("volume-max", "150");
        let rc = unsafe { (api.initialize)(p) };
        if rc < 0 {
            unsafe { (api.terminate_destroy)(p) };
            return Err(format!("mpv_initialize: {}", api.error_text(rc)));
        }
        api.observe(p, 1, "time-pos", mpv::FORMAT_DOUBLE);
        api.observe(p, 2, "duration", mpv::FORMAT_DOUBLE);
        api.observe(p, 3, "pause", mpv::FORMAT_FLAG);
        api.observe(p, 4, "core-idle", mpv::FORMAT_FLAG);
        api.observe(p, 5, "paused-for-cache", mpv::FORMAT_FLAG);
        api.observe(p, 6, "eof-reached", mpv::FORMAT_FLAG);
        api.observe(p, 7, "cache-buffering-state", mpv::FORMAT_INT64);
        api.observe(p, 8, "track-list", 0);
        let (tx, rx) = channel();
        let st = Arc::new(Shared {
            loaded: AtomicBool::new(false),
            pause: AtomicBool::new(false),
            cache_wait: AtomicBool::new(false),
            playing_sent: AtomicBool::new(false),
            cache_pct: F64(AtomicU64::new(100f64.to_bits())),
            time_sec: F64(AtomicU64::new(0)),
            length_sec: F64(AtomicU64::new(0)),
            last_time_event: AtomicI64::new(0),
            dead: AtomicBool::new(false),
            tx: Mutex::new(tx),
            ctx,
        });
        let h = H(p);
        let (a, s) = (api.clone(), st.clone());
        let events = std::thread::Builder::new()
            .name("mpv-events".into())
            .spawn(move || event_loop(&a, h, &s))
            .map_err(|e| e.to_string())?;
        Ok(Engine { api, h, st, rx, events: Some(events) })
    }

    pub fn handle(&self) -> Handle {
        self.h.0
    }

    /// События с прошлого кадра.
    pub fn drain(&self) -> Vec<Ev> {
        self.rx.try_iter().collect()
    }

    /// Программный OpenGL (llvmpipe, виртуалки без видеокарты): многопроходная схема mpv там то и дело даёт чёрный
    /// кадр (замечено под Xvfb), простой режим рисует стабильно и быстрее.
    pub fn set_simple_render(&self) {
        self.set("gpu-dumb-mode", "yes");
    }

    fn set(&self, name: &str, value: &str) {
        self.api.set_property(self.h.0, name, value);
    }

    fn str(&self, name: &str) -> Option<String> {
        self.api.get_property(self.h.0, name)
    }

    fn cmd(&self, args: &[&str]) {
        self.api.run(self.h.0, args);
    }

    pub fn open(&self, url: &str, start_ms: i64, night: bool, audio_lang: Option<&str>, sub_lang: Option<&str>) {
        let s = &self.st;
        s.loaded.store(false, SeqCst);
        s.playing_sent.store(false, SeqCst);
        s.time_sec.set(0.0);
        s.length_sec.set(0.0);
        s.cache_wait.store(false, SeqCst);
        s.cache_pct.set(0.0);
        s.pause.store(false, SeqCst);
        self.set("pause", "no");
        self.set("start", &if start_ms > 0 { format!("{:.3}", start_ms as f64 / 1000.0) } else { "0".into() });
        self.set("alang", audio_lang.unwrap_or(""));
        self.set("slang", sub_lang.unwrap_or(""));
        self.set("af", if night { NIGHT } else { "" });
        self.cmd(&["loadfile", url, "replace"]);
    }

    pub fn set_paused(&self, paused: bool) {
        self.st.pause.store(paused, SeqCst);
        self.set("pause", if paused { "yes" } else { "no" });
    }

    pub fn stop(&self) {
        self.cmd(&["stop"]);
    }

    pub fn seek_to(&self, ms: i64) {
        self.st.cache_pct.set(0.0);
        self.cmd(&["seek", &format!("{:.3}", ms as f64 / 1000.0), "absolute"]);
    }

    pub fn set_rate(&self, rate: f32) {
        self.set("speed", &rate.to_string());
    }

    pub fn set_volume(&self, percent: i64) {
        self.set("volume", &percent.clamp(0, 150).to_string());
    }

    pub fn set_mute(&self, mute: bool) {
        self.set("mute", if mute { "yes" } else { "no" });
    }

    pub fn time_ms(&self) -> i64 {
        (self.st.time_sec.get() * 1000.0) as i64
    }

    pub fn is_playable(&self) -> bool {
        self.st.loaded.load(SeqCst)
    }

    pub fn video_size(&self) -> Option<(i64, i64)> {
        let w: i64 = self.str("video-params/w")?.parse().ok()?;
        let h: i64 = self.str("video-params/h")?.parse().ok()?;
        (w > 0 && h > 0).then_some((w, h))
    }

    pub fn fps(&self) -> f64 {
        self.str("container-fps").and_then(|v| v.parse().ok()).unwrap_or(0.0)
    }

    pub fn audio_tracks(&self) -> Vec<Track> {
        self.tracks("audio")
    }

    pub fn subtitle_tracks(&self) -> Vec<Track> {
        self.tracks("sub")
    }

    fn tracks(&self, kind: &str) -> Vec<Track> {
        let Some(count) = self.str("track-list/count").and_then(|c| c.parse::<usize>().ok()) else { return Vec::new() };
        let mut out = Vec::new();
        for i in 0..count {
            if self.str(&format!("track-list/{i}/type")).as_deref() != Some(kind) {
                continue;
            }
            let Some(id) = self.str(&format!("track-list/{i}/id")).and_then(|v| v.parse().ok()) else { continue };
            let title = self.str(&format!("track-list/{i}/title")).filter(|t| !t.trim().is_empty());
            let lang = self.str(&format!("track-list/{i}/lang")).filter(|t| !t.trim().is_empty());
            let selected = self.str(&format!("track-list/{i}/selected")).as_deref() == Some("yes");
            let mut d = title.unwrap_or_default();
            if let Some(l) = lang {
                if !d.is_empty() {
                    d.push(' ');
                }
                d.push_str(&format!("[{l}]"));
            }
            if d.is_empty() {
                d = format!("{kind} #{id}");
            }
            out.push(Track { id, description: d, selected });
        }
        out
    }

    pub fn set_audio_track(&self, id: i64) {
        self.set("aid", &if id < 0 { "no".into() } else { id.to_string() });
    }

    pub fn set_subtitle_track(&self, id: i64) {
        self.set("sid", &if id < 0 { "no".into() } else { id.to_string() });
    }

    /// Короткая сводка для панели сведений и журнала: кодеки, кадры, потери.
    pub fn stats_line(&self) -> String {
        let g = |n: &str| self.str(n).unwrap_or_else(|| "?".into());
        let fmt = self.str("video-params/hw-pixelformat").unwrap_or_else(|| g("video-params/pixelformat"));
        format!(
            "[mpv] {:.1}/{}s video={} hw={} vo={} {}x{} fmt={} {}/{} audio={} drop={} vo-drop={} cache={}s",
            self.st.time_sec.get(),
            self.st.length_sec.get() as i64,
            g("video-codec"),
            g("hwdec-current"),
            g("current-vo"),
            g("video-params/w"),
            g("video-params/h"),
            fmt,
            g("video-params/gamma"),
            g("video-params/primaries"),
            g("audio-codec-name"),
            g("frame-drop-count"),
            g("vo-delayed-frame-count"),
            g("demuxer-cache-duration")
        )
    }

    /// Новый декодер и вывод видео (застывшая картинка); render context остаётся.
    pub fn restart_video_output(&self) {
        let vid = self.str("vid").unwrap_or_else(|| "auto".into());
        self.set("vid", "no");
        self.set("vid", &vid);
        self.cmd(&["seek", "0", "relative", "exact"]);
    }

    /// Сводка для сторожа (PlaybackSupervisor). Кадров mpv не считает — 0 выключает проверки застывшей картинки,
    /// звук и стоящие часы проверяются.
    pub fn supervisor_stats(&self, user_muted: bool) -> Option<Stats> {
        if !self.is_playable() {
            return None;
        }
        let tracks = self.audio_tracks();
        let selected = tracks.iter().any(|t| t.selected && t.id >= 0);
        let a_time = self
            .str("audio-pts")
            .and_then(|v| v.parse::<f64>().ok())
            .map(|v| (v * 1000.0) as i64)
            .unwrap_or_else(|| self.time_ms());
        let played = if selected { a_time } else { 0 };
        Some(Stats {
            time_ms: self.time_ms(),
            audio_played: played,
            audio_decoded: played,
            pictures: 0,
            expected_fps: self.fps(),
            audio_tracks: tracks.len() as i32,
            audio_selected: selected,
            video_tracks: i32::from(self.video_size().is_some()),
            user_muted,
        })
    }

    /// Остановить ядро и уничтожить его в фоне. Render context к этому времени уже освобождён (его GL-контекст —
    /// окна): живой render context при `mpv_terminate_destroy` роняет процесс.
    pub fn release(mut self, render_freed: bool) {
        self.st.dead.store(true, SeqCst);
        let (api, h) = (self.api.clone(), self.h);
        let events = self.events.take();
        api.run(h.0, &["quit", "0"]);
        unsafe { (api.wakeup)(h.0) };
        let _ = std::thread::Builder::new().name("mpv-destroy".into()).spawn(move || {
            if let Some(t) = events {
                let _ = t.join();
            }
            if render_freed {
                unsafe { (api.terminate_destroy)(h.ptr()) };
            } else {
                player_log::write("[video] render context not freed: mpv core is not destroyed");
            }
        });
    }
}

fn event_loop(api: &Api, h: H, s: &Shared) {
    while !s.dead.load(SeqCst) {
        let ev = unsafe { &*(api.wait_event)(h.0, 0.5) };
        if s.dead.load(SeqCst) {
            return;
        }
        match ev.event_id {
            0 => {}
            mpv::EV_FILE_LOADED => {
                s.loaded.store(true, SeqCst);
                if let Some(d) = api.get_property(h.0, "duration").and_then(|v| v.parse::<f64>().ok()) {
                    if d > 0.0 {
                        s.length_sec.set(d);
                        s.send(Ev::Length((d * 1000.0) as i64));
                    }
                }
                s.send(Ev::Opened);
                s.maybe_playing();
            }
            mpv::EV_PLAYBACK_RESTART => {
                s.send(Ev::SeekDone);
                s.maybe_playing();
            }
            mpv::EV_PROPERTY_CHANGE => on_property(ev, s),
            mpv::EV_END_FILE => {
                let data = ev.data as *const mpv::EventEndFile;
                let (reason, err) =
                    if data.is_null() { (mpv::END_EOF, 0) } else { unsafe { ((*data).reason, (*data).error) } };
                if reason == mpv::END_EOF {
                    s.send(Ev::Finished);
                } else if reason == mpv::END_ERROR {
                    s.send(Ev::Failed(api.error_text(err)));
                }
            }
            mpv::EV_SHUTDOWN => {
                s.dead.store(true, SeqCst);
                return;
            }
            _ => {}
        }
    }
}

fn on_property(ev: &mpv::Event, s: &Shared) {
    if ev.data.is_null() {
        return;
    }
    let p = unsafe { &*(ev.data as *const mpv::EventProperty) };
    if p.name.is_null() {
        return;
    }
    let name = unsafe { CStr::from_ptr(p.name) }.to_string_lossy();
    let has = !p.data.is_null();
    let dbl = (p.format == mpv::FORMAT_DOUBLE && has).then(|| unsafe { *(p.data as *const f64) });
    let flag = (p.format == mpv::FORMAT_FLAG && has).then(|| unsafe { *(p.data as *const i32) } != 0);
    let int64 = (p.format == mpv::FORMAT_INT64 && has).then(|| unsafe { *(p.data as *const i64) });
    match name.as_ref() {
        // Пока новый файл не открыт, time-pos — прошлого файла: его место ушло бы в новую серию (20.10, 20:43:26).
        "time-pos" => {
            if let (Some(d), true) = (dbl, s.loaded.load(SeqCst)) {
                s.time_sec.set(d);
                // картинка идёт, а `playing` не пришёл (событие прошлого файла при быстрой смене серий)
                if !s.playing_sent.load(SeqCst) && d > 0.0 {
                    s.maybe_playing();
                }
                let now = crate::library::now_ms();
                if now - s.last_time_event.load(SeqCst) >= 200 {
                    s.last_time_event.store(now, SeqCst);
                    s.send(Ev::Time((d * 1000.0) as i64));
                }
            }
        }
        "eof-reached" => {
            if flag == Some(true) && s.loaded.load(SeqCst) {
                s.send(Ev::Finished);
            }
        }
        "duration" => {
            if let Some(d) = dbl.filter(|d| *d > 0.0) {
                s.length_sec.set(d);
                s.send(Ev::Length((d * 1000.0) as i64));
            }
        }
        "pause" => {
            if let Some(f) = flag {
                s.pause.store(f, SeqCst);
                if f {
                    s.send(Ev::Paused)
                } else {
                    s.maybe_playing()
                }
            }
        }
        "core-idle" => {
            if flag == Some(false) {
                s.maybe_playing();
            }
        }
        "paused-for-cache" => {
            if let Some(f) = flag {
                s.cache_wait.store(f, SeqCst);
                if f {
                    let pct = s.cache_pct.get();
                    s.send(Ev::Buffering(if pct < 100.0 { pct as f32 } else { 0.0 }));
                } else {
                    s.cache_pct.set(100.0);
                    s.send(Ev::Buffering(100.0));
                    s.maybe_playing();
                }
            }
        }
        "cache-buffering-state" => {
            if let Some(v) = int64 {
                let pct = (v as f64).clamp(0.0, 100.0);
                s.cache_pct.set(pct);
                if s.cache_wait.load(SeqCst) {
                    s.send(Ev::Buffering(pct as f32));
                }
            }
        }
        "track-list" => s.send(Ev::TracksChanged),
        _ => {}
    }
}

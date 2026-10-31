//! Фильмы с Blu-ray (.m2ts) и другие транспортные потоки MPEG не пишут длительность: плеер узнаёт её по часам потока
//! (PCR) в самом конце файла, а по HTTP (приложение всегда играет через локальный запас) этого не делает. Здесь
//! читаются оба конца файла (два маленьких запроса), и длительность — разница первого и последнего значения часов.
//! Перенос `TsClock.kt` и `TsPlayback.kt`.

use crate::burst_proxy;
use crate::mirror_link;
use std::collections::{BTreeMap, HashMap};
use std::sync::{Arc, LazyLock, Mutex, MutexGuard, RwLock};

const PIECE: usize = 1 << 20; // столько читается с каждого конца файла
const SAMPLE_PIECE: usize = 512 * 1024; // кусок для промежуточных точек
const WRAP: i64 = 1 << 33; // часы 90 кГц переполняются через ~26,5 часа

/// Длительность, размер, PID часов и точки «время фильма → доля файла».
pub struct ClockInfo {
    pub duration_ms: i64,
    pub total_bytes: i64,
    pub pid: i32,
    pub first_pcr: i64,
    pub checkpoints: Mutex<BTreeMap<i64, f32>>,
}

impl ClockInfo {
    pub fn new(duration_ms: i64, total_bytes: i64, pid: i32, first_pcr: i64) -> Self {
        ClockInfo { duration_ms, total_bytes, pid, first_pcr, checkpoints: Mutex::new(BTreeMap::new()) }
    }

    pub fn checkpoints(&self) -> MutexGuard<'_, BTreeMap<i64, f32>> {
        self.checkpoints.lock().unwrap_or_else(|e| e.into_inner())
    }
}

type LogFn = dyn Fn(&str) + Send + Sync;

static LOG: LazyLock<RwLock<Arc<LogFn>>> = LazyLock::new(|| RwLock::new(Arc::new(|_: &str| {})));
static CACHE: LazyLock<Mutex<HashMap<String, (i64, i64)>>> = LazyLock::new(Default::default);
static CLOCKS: LazyLock<Mutex<HashMap<String, Arc<ClockInfo>>>> = LazyLock::new(Default::default);

pub fn set_log(f: impl Fn(&str) + Send + Sync + 'static) {
    *LOG.write().unwrap() = Arc::new(f);
}

fn log(msg: &str) {
    let f = LOG.read().unwrap().clone();
    f(msg)
}

pub fn is_ts(url: &str) -> bool {
    let name = url.split('?').next().unwrap_or("").rsplit('/').next().unwrap_or("").to_lowercase();
    name.ends_with(".m2ts") || name.ends_with(".mts") || name.ends_with(".ts")
}

pub(crate) fn cache_key(url: &str) -> String {
    mirror_link::file_key(url)
}

/// (длительность мс, размер байт), если уже узнавали.
pub fn cached(url: &str) -> Option<(i64, i64)> {
    CACHE.lock().unwrap().get(&cache_key(url)).copied()
}

pub fn clock_info(url: &str) -> Option<Arc<ClockInfo>> {
    CLOCKS.lock().unwrap().get(&cache_key(url)).cloned()
}

/// Для тестов и для переноса известных точек: положить сведения о часах файла.
pub fn put_clock_info(url: &str, info: Arc<ClockInfo>) {
    CLOCKS.lock().unwrap().insert(cache_key(url), info);
}

/// Длительность (мс) и размер (байт) потока или `None`. Блокирует: начало файла (ответ заодно говорит размер), потом
/// конец — два маленьких запроса, 1–3 с на мобильной сети.
pub fn probe(url: &str) -> Option<(i64, i64)> {
    if let Some(c) = cached(url) {
        return Some(c);
    }
    let r = probe_once(url).or_else(|| {
        // первая попытка могла попасть на протухшее соединение пула: сбросить и ещё раз
        burst_proxy::reset_connections();
        probe_once(url)
    })?;
    CACHE.lock().unwrap().insert(cache_key(url), r);
    Some(r)
}

fn modw(d: i64) -> i64 {
    ((d % WRAP) + WRAP) % WRAP
}

fn probe_once(url: &str) -> Option<(i64, i64)> {
    let (head, total) = burst_proxy::read_range(url, 0, PIECE)?;
    if total <= 0 {
        return None;
    }
    let tail =
        if total <= PIECE as i64 { head.clone() } else { burst_proxy::read_range(url, total - PIECE as i64, PIECE)?.0 };
    let (pid, first) = *pcrs(&head).first()?;
    let last = pcrs(&tail).iter().rev().find(|p| p.0 == pid)?.1;
    let duration_ms = modw(last - first) / 90;
    if duration_ms <= 0 {
        return None;
    }
    let info = Arc::new(ClockInfo::new(duration_ms, total, pid, first));
    {
        let mut pts = info.checkpoints();
        pts.insert(0, 0.0);
        pts.insert(duration_ms, 1.0);
    }
    if total > 4 * PIECE as i64 {
        for f in [0.25f32, 0.50, 0.75] {
            let offset = (total as f64 * f as f64) as i64;
            let Some((slice, _)) = burst_proxy::read_range(url, offset, SAMPLE_PIECE) else { continue };
            let Some(pcr) = pcrs(&slice).iter().find(|p| p.0 == pid).map(|p| p.1) else { continue };
            let elapsed = modw(pcr - first) / 90;
            if (1..duration_ms).contains(&elapsed) {
                info.checkpoints().insert(elapsed, f);
            }
        }
    }
    let n = info.checkpoints().len();
    put_clock_info(url, info);
    log(&format!("[ts] probed with {n} checkpoints"));
    Some((duration_ms, total))
}

/// Известная точка (время, доля файла), узнанная при показе.
pub fn learn(url: &str, time_ms: i64, pos: f32) {
    if time_ms <= 1_000 || pos <= 0.001 || pos >= 0.999 {
        return;
    }
    let Some(info) = clock_info(url) else { return };
    if time_ms >= info.duration_ms - 1_000 {
        return;
    }
    let mut pts = info.checkpoints();
    // Время и доля растут вместе. Точка, что противоречит новой (раньше, но дальше в файле, или позже, но ближе),
    // была плохой выборкой: уходит, иначе перемотка между ними прыгнула бы назад.
    let bad: Vec<i64> = pts
        .iter()
        .filter(|(&k, &v)| (k > 0 && k < time_ms && v >= pos) || (k > time_ms && k < info.duration_ms && v <= pos))
        .map(|(&k, _)| k)
        .collect();
    for k in bad {
        pts.remove(&k);
    }
    pts.insert(time_ms, pos);
}

/// Доля файла (0..0,999) для времени фильма: кусочно-линейно между известными точками (неровный битрейт).
pub fn pos_for(url: &str, target_ms: i64, total_duration: i64) -> f32 {
    if target_ms <= 0 {
        return 0.0;
    }
    let info = clock_info(url);
    let dur = info.as_ref().map(|i| i.duration_ms).filter(|d| *d > 0).unwrap_or(total_duration);
    if dur <= 0 {
        return 0.0;
    }
    if target_ms >= dur {
        return 0.999;
    }
    if let Some(info) = &info {
        let pts = info.checkpoints();
        if pts.len() >= 2 {
            let floor = pts.range(..=target_ms).next_back();
            let ceil = pts.range(target_ms..).next();
            if let (Some((&t0, &p0)), Some((&t1, &p1))) = (floor, ceil) {
                if t0 == t1 {
                    return p0.clamp(0.0, 0.999);
                }
                let interp = p0 + (p1 - p0) * ((target_ms - t0) as f64 / (t1 - t0) as f64) as f32;
                return interp.clamp(0.0, 0.999);
            }
        }
    }
    (target_ms as f64 / dur as f64).clamp(0.0, 0.999) as f32
}

pub fn clear_cache() {
    CACHE.lock().unwrap().clear();
    CLOCKS.lock().unwrap().clear();
}

/// Длительность от первого значения часов в `head` до последнего (того же потока) в `tail`.
pub fn duration_ms(head: &[u8], tail: &[u8]) -> Option<i64> {
    let (pid, first) = *pcrs(head).first()?;
    let last = pcrs(tail).iter().rev().find(|p| p.0 == pid)?.1;
    let d = modw(last - first) / 90;
    (d > 0).then_some(d)
}

/// (PID, часы в единицах 90 кГц) каждого PCR в `buf` по порядку. Пакеты TS по 188 и M2TS по 192 байта.
pub fn pcrs(buf: &[u8]) -> Vec<(i32, i64)> {
    let Some((start, stride)) = sync(buf) else { return Vec::new() };
    let mut out = Vec::new();
    let mut p = start;
    while p + 188 <= buf.len() {
        if buf[p] == 0x47 {
            if let Some(x) = pcr_at(buf, p) {
                out.push(x);
            }
        }
        p += stride;
    }
    out
}

/// Где начинается первый пакет и его размер: три… четыре байта 0x47 подряд на этом расстоянии.
fn sync(buf: &[u8]) -> Option<(usize, usize)> {
    for stride in [188usize, 192] {
        for o in 0..stride.min(buf.len()) {
            if (0..=3).all(|k| buf.get(o + k * stride) == Some(&0x47)) {
                return Some((o, stride));
            }
        }
    }
    None
}

fn pcr_at(b: &[u8], p: usize) -> Option<(i32, i64)> {
    let pid = (((b[p + 1] & 0x1F) as i32) << 8) | b[p + 2] as i32;
    let adaptation = (b[p + 3] >> 4) & 0x2;
    if adaptation == 0 {
        return None;
    }
    let len = b[p + 4];
    if len < 7 || (b[p + 5] & 0x10) == 0 {
        return None;
    }
    let u = |i: usize| b[p + i] as i64;
    let base = (u(6) << 25) | (u(7) << 17) | (u(8) << 9) | (u(9) << 1) | (u(10) >> 7);
    Some((pid, base))
}

// ---- TsPlayback ------------------------------------------------------------------------------------------------

struct TsInner {
    offset: i64,
    open_target: i64,
    open_pos: f32,
    target: i64,   // перемотка в пути (время фильма)
    from: i64,     // время фильма у плеера, когда её попросили
    last_pos: f32, // доля, которую попросили последней
    last_learned_time: i64,
}

/// Показ Blu-ray (.m2ts) / транспортного потока по HTTP — общие решения плееров. Длины плеер там не знает (её даёт
/// TsClock), поэтому им правят по доле файла, а не по времени:
///
/// - перемотка считается от того места, где плеер сейчас (его доля плюс расстояние): неровный битрейт диска
///   ошибается лишь на долю самого прыжка;
/// - нажатия подряд: плеер ещё говорит старое время, а доля уже новая — считать от прыжка в пути;
/// - сразу после прыжка плеер ещё недолго сообщает старое место — это не приземление;
/// - открытый на сохранённом месте, прыжок делается сразу, и плеер может считать часы от 0 там: смещение
///   прибавляется к каждому времени, что он сообщает.
///
/// Потокобезопасен: плеер на ПК перематывает в своём потоке, а время приходит в потоке окна.
pub struct TsPlayback {
    pub length_ms: i64,
    pub url: String,
    inner: Mutex<TsInner>,
}

impl TsPlayback {
    pub fn new(length_ms: i64, url: &str) -> Self {
        TsPlayback {
            length_ms,
            url: url.to_string(),
            inner: Mutex::new(TsInner {
                offset: 0,
                open_target: -1,
                open_pos: 0.0,
                target: -1,
                from: 0,
                last_pos: 0.0,
                last_learned_time: 0,
            }),
        }
    }

    fn g(&self) -> MutexGuard<'_, TsInner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Прибавляется к времени плеера (он считает от 0 с места, куда прыгнул сразу после открытия).
    pub fn offset(&self) -> i64 {
        self.g().offset
    }

    /// Время фильма для времени, которое сообщил плеер.
    pub fn film_time(&self, player_time: i64) -> i64 {
        let off = self.g().offset;
        if player_time > 0 {
            player_time + off
        } else {
            player_time
        }
    }

    fn pos_of(&self, ms: i64) -> f32 {
        if !self.url.is_empty() {
            pos_for(&self.url, ms, self.length_ms)
        } else {
            (ms as f64 / self.length_ms as f64).clamp(0.0, 0.999) as f32
        }
    }

    /// Плеер начал поток, открытый на `saved_ms`: доля, куда прыгнуть сейчас, или `None` (с начала).
    pub fn open_jump(&self, saved_ms: i64) -> Option<f32> {
        if saved_ms <= 10_000 || self.length_ms <= 0 {
            return None;
        }
        let pos = self.pos_of(saved_ms);
        let mut g = self.g();
        g.open_target = saved_ms;
        g.open_pos = pos;
        g.last_pos = pos;
        Some(pos)
    }

    /// Прыжок `open_jump` в пути.
    pub fn opening(&self) -> bool {
        self.g().open_target >= 0
    }

    /// Время от плеера, пока `opening`: время фильма, когда плеер там (тогда решается смещение), или `None`, пока он
    /// ещё сообщает начало файла.
    pub fn open_landed(&self, player_time: i64, player_pos: f32) -> Option<i64> {
        let learn_at = {
            let mut g = self.g();
            if g.open_target < 0 {
                drop(g);
                return Some(self.film_time(player_time));
            }
            if player_time <= 0 || player_pos < g.open_pos * 0.5 {
                return None;
            }
            g.offset = if player_time < g.open_target / 2 { g.open_target - player_time } else { 0 };
            g.open_target = -1;
            !self.url.is_empty() && g.offset == 0 && player_time > 1_000
        };
        if learn_at {
            learn(&self.url, player_time, player_pos);
        }
        Some(self.film_time(player_time))
    }

    /// Доля для перемотки на `target_ms`; `now_ms` / `now_pos` — время и доля плеера сейчас.
    pub fn seek(&self, target_ms: i64, now_ms: i64, now_pos: f32) -> f32 {
        let mut g = self.g();
        let (t0, p0) = if g.target >= 0 {
            (g.target, g.last_pos)
        } else if g.open_target >= 0 {
            (g.open_target, g.open_pos)
        } else {
            (now_ms, now_pos)
        };
        if !self.url.is_empty() {
            if t0 > 0 && p0 > 0.0 {
                learn(&self.url, t0, p0);
            }
            let pos = pos_for(&self.url, target_ms, self.length_ms);
            g.target = target_ms;
            g.from = now_ms;
            g.last_pos = pos;
            return pos;
        }
        let len = self.length_ms as f64;
        let base = if t0 > 0 && p0 > 0.0 { p0 as f64 - t0 as f64 / len } else { 0.0 };
        let distance = (target_ms - t0).abs();
        let weight = if distance <= 15_000 {
            1.0
        } else if distance >= 60_000 {
            0.0
        } else {
            (60_000.0 - distance as f64) / 45_000.0
        };
        let pos = ((base * weight + target_ms as f64 / len) as f32).clamp(0.0, 0.999);
        g.target = target_ms;
        g.from = now_ms;
        g.last_pos = pos;
        pos
    }

    /// Ход показа: запоминает точки, пока всё ровно.
    pub fn tick(&self, player_time: i64, player_pos: f32) {
        let learn_now = {
            let mut g = self.g();
            let steady = !self.url.is_empty()
                && g.offset == 0
                && g.open_target < 0
                && g.target < 0
                && player_time > 5_000
                && player_pos > 0.005;
            if steady && (player_time - g.last_learned_time).abs() >= 5_000 {
                g.last_learned_time = player_time;
                true
            } else {
                false
            }
        };
        if learn_now {
            learn(&self.url, player_time, player_pos);
        }
    }

    /// Перемотка в пути.
    pub fn seeking(&self) -> bool {
        self.g().target >= 0
    }

    /// Время фильма у плеера, когда попросили перемотку, что в пути.
    pub fn seek_from(&self) -> i64 {
        self.g().from
    }

    /// Время фильма от плеера, пока `seeking`: `false` — ещё старое место (не считать), `true` — приземлились.
    pub fn seek_landed(&self, film_ms: i64, player_pos: f32) -> bool {
        let learn_now = {
            let mut g = self.g();
            if g.target < 0 {
                return true;
            }
            if (film_ms - g.from).abs() < 1_500 && (g.target - g.from).abs() > 3_000 {
                return false;
            }
            g.target = -1;
            !self.url.is_empty() && g.offset == 0 && film_ms > 1_000 && player_pos > 0.005
        };
        if learn_now {
            learn(&self.url, film_ms, player_pos);
        }
        true
    }

    /// Перемотка кончилась так или иначе (пауза, бросили).
    pub fn seek_done(&self) {
        self.g().target = -1;
    }
}

#[cfg(test)]
#[path = "ts_clock_tests.rs"]
mod tests;

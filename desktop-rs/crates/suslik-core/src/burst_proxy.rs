//! «Экономная загрузка»: маленький HTTP-сервер на 127.0.0.1 между плеером (mpv) и зеркалом. Перенос `BurstProxy.kt`.
//!
//! Плеер читает поток понемногу всё время показа, и сеть не отдыхает. Здесь фильм качается рывками: так быстро, как
//! даёт сеть, на ~1,5–2 минуты вперёд (в кольцевой файл), потом соединение закрывается до тех пор, пока запас не
//! подойдёт к концу. Плеер читает с диска и ничего не замечает. Важнее на ПК: плеер говорит только с localhost —
//! оборванное или зависшее соединение с зеркалом больше не держит его на мёртвом сокете (загрузчик переподключается
//! сам), а внешняя озвучка и видео читаются с диска с одной скоростью и после перемотки начинают вместе.
//!
//! Перемотка дешёвая: запрос далеко от скачанного открывает новое «окно» (короткие пробы, например индекс MKV в конце
//! файла, обходятся без рывков). Диск на фильм ограничен: все его окна и озвучки вместе — не больше [`cap_bytes`]
//! (см. `trim_to_budget`).
//!
//! Отличия от Kotlin-версии: потоки std вместо пула Java; вместо `synchronized`/`wait` у окна — `Mutex` с файлом и
//! `Condvar`; поля, которые читают без замка (`written`, `read_pos`, `last_read_at`…), — атомарные. Порядок замков:
//! источник (`sync`) → окно; замок источника под замком окна не берётся никогда. Ответ зеркала обрывают через
//! [`Cancel`] (он закрывает сокет), а не закрытием потока из чужого потока.

pub use crate::http1::Cancel;
use crate::http1::{Client, Connector};
use crate::mirror_link;
use crate::url_codec;
use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::io::{self, BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicI64, AtomicU64, Ordering::SeqCst};
use std::sync::{Arc, Condvar, LazyLock, Mutex, MutexGuard, RwLock};
use std::time::{Duration, Instant};

const MAX_WINDOWS: usize = 3; // на поток (фильм + дальняя перемотка / чтение индекса; окно фильма не вытесняется)
const PROBE_AHEAD: i64 = 4 << 20; // пока окно не доказало, что это просмотр
const PROBE_LIMIT: i64 = 6 << 20; // прочитано подряд столько = просмотр → рывки
const PROBE_IDLE_MS: i64 = 500; // проба, которую плеер не читает столько, стоит на PROBE_AHEAD
const CHUNK: usize = 256 * 1024;
const NEAR: i64 = 8 << 20; // запрос так близко к концу окна присоединяется к нему
const NEAR_MIN: i64 = 1 << 20; // ...но не дальше, чем сеть принесёт за NEAR_SEC (см. Source::near)
const NEAR_SEC: f64 = 1.5;
const BRIDGE: i64 = 16 << 20; // запрос так близко перед данными окна качает только разрыв (см. window_for)
const TAIL_CACHE: usize = 512 * 1024; // конец файла (индекс MKV) — в памяти, без окна
const BURST_MARGIN: f64 = 1.25; // ограниченный запрос просит на 25 % больше недостающего
const SLOW_NET: f64 = 1.83; // сеть ÷ битрейт фильма ниже этого: рывок не ограничен (см. fetch)
const MAX_BRIDGES: usize = 4; // таких окон-мостиков на поток

// ---- настройки и общее состояние ------------------------------------------------------------------------------

static CAP_BYTES: AtomicI64 = AtomicI64::new(96 << 20);
static RECENT_MS: AtomicI64 = AtomicI64::new(10_000);
static HEADER_STALL_MS: AtomicI64 = AtomicI64::new(8_000);
static BODY_STALL_MS: AtomicI64 = AtomicI64::new(6_000);
static NETWORK_ONLINE: AtomicBool = AtomicBool::new(true);
static SCREEN_OFF: AtomicBool = AtomicBool::new(false);
static DOWNLOADED: AtomicI64 = AtomicI64::new(0);
static IDS: AtomicU64 = AtomicU64::new(0);

type Hook<T> = RwLock<Arc<T>>;
type RefreshUrl = dyn Fn(&str) -> Option<String> + Send + Sync;

struct Global {
    dir: Mutex<Option<PathBuf>>,
    server: Mutex<Option<u16>>,
    streams: Mutex<Streams>,
    /// Файлы фильма кроме видео (озвучки, субтитры; см. keep_only): у каждого маленькое кольцо из cap_bytes.
    side_keys: RwLock<Arc<HashSet<String>>>,
    upstream: RwLock<Option<Arc<dyn Upstream>>>,
    refresh_url: Hook<RefreshUrl>,
    reset_connections: Hook<dyn Fn() + Send + Sync>,
    log: Hook<dyn Fn(&str) + Send + Sync>,
    on_bytes: Hook<dyn Fn(i64) + Send + Sync>,
    trim_lock: Mutex<()>,
    watchdog: std::sync::Once,
}

#[derive(Default)]
struct Streams {
    sources: HashMap<String, Arc<Source>>, // токен -> источник
    by_url: HashMap<String, String>,       // ключ файла (MirrorLink::file_key) -> токен
}

static G: LazyLock<Global> = LazyLock::new(|| Global {
    dir: Mutex::new(None),
    server: Mutex::new(None),
    streams: Mutex::new(Streams::default()),
    side_keys: RwLock::new(Arc::new(HashSet::new())),
    upstream: RwLock::new(None),
    refresh_url: RwLock::new(Arc::new(|_: &str| None)),
    reset_connections: RwLock::new(Arc::new(|| {})),
    log: RwLock::new(Arc::new(|_: &str| {})),
    on_bytes: RwLock::new(Arc::new(|_: i64| {})),
    trim_lock: Mutex::new(()),
    watchdog: std::sync::Once::new(),
});

static EPOCH: LazyLock<Instant> = LazyLock::new(Instant::now);

/// Миллисекунды по монотонным часам (перевод системных часов не ломает сторожей). Начинаются не с нуля: 0 у
/// `retired_at` и `held_until` значит «нет».
fn now() -> i64 {
    EPOCH.elapsed().as_millis() as i64 + 1_000_000
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

fn log(msg: &str) {
    let f = G.log.read().unwrap().clone();
    f(msg);
}

/// Сбросить соединения пула сейчас (см. [`set_reset_connections`]).
pub fn reset_connections() {
    let f = G.reset_connections.read().unwrap().clone();
    f();
}

fn count_bytes(n: i64) {
    DOWNLOADED.fetch_add(n, SeqCst);
    let f = G.on_bytes.read().unwrap().clone();
    f(n);
}

fn spawn(name: &str, f: impl FnOnce() + Send + 'static) {
    let _ = std::thread::Builder::new().name(name.into()).spawn(f);
}

/// [`reset_connections`] не в потоке вызывающего (закрытие TLS-сокета шлёт close_notify по сети), потом `after`.
fn reset_connections_async(after: impl FnOnce() + Send + 'static) {
    spawn("burst-reset", move || {
        reset_connections();
        after();
    });
}

struct AtomicF64(AtomicU64);

impl AtomicF64 {
    const fn zero() -> Self {
        AtomicF64(AtomicU64::new(0))
    }
    fn get(&self) -> f64 {
        f64::from_bits(self.0.load(SeqCst))
    }
    fn set(&self, v: f64) {
        self.0.store(v.to_bits(), SeqCst)
    }
}

/// Диск, который может занять играющий фильм (все окна и озвучки вместе), из «Настроек»; 0 — выключено (плеер
/// читает зеркало напрямую).
pub fn cap_bytes() -> i64 {
    CAP_BYTES.load(SeqCst)
}

pub fn set_cap_bytes(v: i64) {
    CAP_BYTES.store(v, SeqCst)
}

/// Запрос без ответа / без данных столько миллисекунд обрывается и повторяется на новом соединении.
pub fn set_stall_ms(header: i64, body: i64) {
    HEADER_STALL_MS.store(header, SeqCst);
    BODY_STALL_MS.store(body, SeqCst);
}

/// Журнал (журнал плеера приложения).
pub fn set_log(f: impl Fn(&str) + Send + Sync + 'static) {
    *G.log.write().unwrap() = Arc::new(f);
}

/// Счётчик трафика приложения: каждый кусок, скачанный с зеркала.
pub fn set_on_bytes(f: impl Fn(i64) + Send + Sync + 'static) {
    *G.on_bytes.write().unwrap() = Arc::new(f);
}

/// Новый подписанный адрес того же файла (приложение спрашивает сервер, блокируясь; `None` — не вышло). Нужен, когда
/// старая подпись кончилась (зеркало отвечает 401 через 12 часов: фильм на паузе на ночь).
pub fn set_refresh_url(f: impl Fn(&str) -> Option<String> + Send + Sync + 'static) {
    *G.refresh_url.write().unwrap() = Arc::new(f);
}

/// Закрывает соединения пула (они от сети, которой больше нет, или висят): задаёт приложение.
pub fn set_reset_connections(f: impl Fn() + Send + Sync + 'static) {
    *G.reset_connections.write().unwrap() = Arc::new(f);
}

/// Байт скачано с зеркала с запуска процесса (все окна).
pub fn downloaded() -> i64 {
    DOWNLOADED.load(SeqCst)
}

/// МБ скачано с зеркала с запуска процесса.
pub fn downloaded_mb() -> f64 {
    downloaded() as f64 / 1_048_576.0
}

pub fn is_network_online() -> bool {
    NETWORK_ONLINE.load(SeqCst)
}

pub fn is_screen_off() -> bool {
    SCREEN_OFF.load(SeqCst)
}

// ---- откуда качать ---------------------------------------------------------------------------------------------

/// Как качается кусок потока (на ПК — [`HttpUpstream`]).
pub trait Upstream: Send + Sync {
    /// Байты `from..=to` (`to < 0` — до конца файла). `cancel` обрывает запрос (в том числе пока он соединяется).
    fn open(&self, url: &str, from: i64, to: i64, cancel: &Arc<Cancel>) -> io::Result<UpstreamResponse>;
}

/// Ответ зеркала: код, заголовки (имя без учёта регистра) и тело.
pub struct UpstreamResponse {
    pub code: u16,
    pub headers: Vec<(String, String)>,
    pub body: Option<Box<dyn Read + Send>>,
}

impl UpstreamResponse {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v.as_str())
    }
}

/// Запросы `Range` через [`Client`]: пул соединений, ответ, прочитанный до конца, оставляет соединение живым.
pub struct HttpUpstream {
    client: Client,
}

impl HttpUpstream {
    pub fn new(connector: Arc<dyn Connector>) -> Arc<Self> {
        Arc::new(Self { client: Client::new(connector) })
    }

    /// Закрыть соединения пула (для [`set_reset_connections`]).
    pub fn reset(&self) {
        self.client.reset()
    }
}

impl Upstream for HttpUpstream {
    fn open(&self, url: &str, from: i64, to: i64, cancel: &Arc<Cancel>) -> io::Result<UpstreamResponse> {
        let range = if to >= 0 { format!("bytes={from}-{to}") } else { format!("bytes={from}-") };
        let r = self.client.get(url, &[("Range", range)], cancel)?;
        Ok(UpstreamResponse { code: r.code, headers: r.headers, body: Some(Box::new(r.body)) })
    }
}

// ---- управление ------------------------------------------------------------------------------------------------

fn key_of(url: &str) -> String {
    mirror_link::file_key(url)
}

fn source_of(url: &str) -> Option<Arc<Source>> {
    let s = lock(&G.streams);
    s.by_url.get(&key_of(url)).and_then(|t| s.sources.get(t)).cloned()
}

fn all_sources() -> Vec<Arc<Source>> {
    lock(&G.streams).sources.values().cloned().collect()
}

fn kick_all() {
    for s in all_sources() {
        for w in s.windows().iter() {
            w.kick();
        }
    }
}

/// Устройство сменило сеть (Wi-Fi ↔ мобильная, VPN): запросы в пути и соединения пула — от старой. Всё
/// бросается и повторяется сразу, без ожидания тайм-аута в 30 с.
pub fn network_changed() {
    NETWORK_ONLINE.store(true, SeqCst);
    log("reserve: network changed → restarting the requests");
    reset_connections_async(|| {}); // окно, которое пнули, само сбросит их в своём потоке перед повтором
    kick_all();
}

/// Сеть пропала совсем: запросы обрываются, загрузчики не повторяют их, пока сеть не вернётся.
pub fn network_lost() {
    if !NETWORK_ONLINE.swap(false, SeqCst) {
        return;
    }
    log("reserve: network lost → suspending requests until connection returns");
    kick_all();
}

/// Экран выключен / приложение скрыто: всё оборвать, ничего не качать до [`on_screen_on`].
pub fn on_screen_off() {
    SCREEN_OFF.store(true, SeqCst);
    log("reserve: screen off → downloads stopped");
    kick_all();
    reset_connections_async(|| {});
}

/// Экран включён: загрузчики просыпаются сразу, на новых соединениях.
pub fn on_screen_on() {
    if !SCREEN_OFF.swap(false, SeqCst) {
        return;
    }
    log("reserve: screen on → downloads resume");
    // Будятся, когда пул уже пуст: иначе взяли бы мёртвые сокеты.
    reset_connections_async(|| {
        for s in all_sources() {
            for w in s.windows().iter() {
                w.wake();
            }
        }
    });
}

/// Один раз за процесс: папка запаса (остатки прошлого запуска убираются в фоне) и откуда качать.
pub fn init(directory: &Path, up: Arc<dyn Upstream>) {
    *G.upstream.write().unwrap() = Some(up);
    G.watchdog.call_once(|| {
        spawn("burst-watch", || loop {
            std::thread::sleep(Duration::from_secs(1));
            let t = now();
            for s in all_sources() {
                for w in s.windows().iter() {
                    w.check_stall(t);
                }
            }
        })
    });
    let mut dir = lock(&G.dir);
    if dir.is_some() {
        return;
    }
    let name = directory.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    if let Some(parent) = directory.parent().map(Path::to_path_buf) {
        let nanos =
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
        let trash = parent.join(format!("{name}.old-{nanos}"));
        if directory.exists() && std::fs::rename(directory, &trash).is_err() {
            let _ = std::fs::remove_dir_all(directory);
        }
        let prefix = format!("{name}.old-");
        spawn("burst-clean", move || {
            // остаток этого запуска и тех, что убили до конца уборки
            if let Ok(rd) = std::fs::read_dir(&parent) {
                for e in rd.flatten() {
                    if e.file_name().to_string_lossy().starts_with(&prefix) {
                        let _ = std::fs::remove_dir_all(e.path());
                    }
                }
            }
        });
    }
    let _ = std::fs::create_dir_all(directory);
    *dir = Some(directory.to_path_buf());
}

/// У запаса есть папка в этом процессе (плеер открывали): его файлы заняты, см. [`drop_retired`].
pub fn initialized() -> bool {
    lock(&G.dir).is_some()
}

fn dir() -> Option<PathBuf> {
    lock(&G.dir).clone()
}

fn ensure_server() -> Option<u16> {
    let mut server = lock(&G.server);
    if let Some(p) = *server {
        return Some(p);
    }
    let l = TcpListener::bind("127.0.0.1:0").ok()?;
    let port = l.local_addr().ok()?.port();
    spawn("burst-proxy", move || {
        for c in l.incoming() {
            match c {
                Ok(c) => spawn("burst-proxy", move || serve(c)),
                Err(_) => std::thread::sleep(Duration::from_millis(50)),
            }
        }
    });
    *server = Some(port);
    Some(port)
}

fn random_token() -> String {
    let mut b = [0u8; 16];
    if getrandom::getrandom(&mut b).is_err() {
        // запасной путь: время и счётчик (адрес слушает только 127.0.0.1)
        let t = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
        b = (t ^ ((IDS.fetch_add(1, SeqCst) as u128) << 64)).to_le_bytes();
    }
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// Адрес, который открывает плеер: локальный прокси или сам `url`, если прокси нельзя (не https, мало места,
/// сервер не поднялся, запас выключен).
pub fn wrap(url: &str) -> String {
    let Some(d) = dir() else { return url.to_string() };
    if !url.starts_with("https://") {
        return url.to_string();
    }
    let cap = cap_bytes();
    if cap <= 0 {
        return url.to_string();
    }
    // Свои файлы запаса (фильм, закрытый раньше) освобождают место новому: см. keep_only.
    let free = fs4::available_space(&d).map(|v| v as i64).unwrap_or(0);
    if free + disk_bytes_in(&d) < cap + (300 << 20) {
        return url.to_string();
    }
    let Some(port) = ensure_server() else { return url.to_string() };
    let key = key_of(url);
    let src = {
        let mut s = lock(&G.streams);
        let token = match s.by_url.get(&key) {
            Some(t) => t.clone(),
            None => {
                let t = random_token();
                s.sources.insert(t.clone(), Arc::new(Source::new(t.clone(), url.to_string())));
                s.by_url.insert(key, t.clone());
                t
            }
        };
        s.sources.get(&token).cloned()
    };
    let Some(src) = src else { return url.to_string() };
    *lock(&src.url) = url.to_string(); // новейшая подпись: старая могла кончиться
    if src.retired_at.load(SeqCst) != 0 {
        src.retired_at.store(0, SeqCst);
        log(&format!("reserve: back to a film kept on disk ({} MB)", src.kept_mb()));
    }
    if src.broken.load(SeqCst) {
        return url.to_string(); // этот поток не пошёл через прокси: напрямую
    }
    let raw = url.split('?').next().unwrap_or(url).rsplit('/').next().unwrap_or("");
    let name = if raw.trim().is_empty() { "stream" } else { raw };
    let name = url_codec::decode(name).unwrap_or_else(|| name.to_string());
    format!("http://127.0.0.1:{port}/s/{}/{}", src.token, url_codec::encode(&name).replace('+', "%20"))
}

fn remove_stream(s: &mut Streams, key: &str) -> Option<Arc<Source>> {
    let token = s.by_url.remove(key)?;
    s.sources.remove(&token)
}

/// Файлы открываемого фильма (`urls`: сначала видео, потом озвучки и субтитры): все другие потоки уходят сразу, и
/// закрытый фильм на диске тоже. Пустой `urls` (плеер закрыт, см. [`clear`]): скачанное остаётся на диске до
/// открытия другого фильма, «Очистить кэш» или выхода; хранится только последний закрытый фильм.
pub fn keep_only(urls: &[String]) {
    let mut closing = Vec::new();
    if !urls.is_empty() {
        let keys: Vec<String> = urls.iter().map(|u| key_of(u)).collect();
        let side: HashSet<String> = keys[1..].iter().filter(|k| **k != keys[0]).cloned().collect();
        *G.side_keys.write().unwrap() = Arc::new(side);
        let keep: HashSet<&String> = keys.iter().collect();
        let mut s = lock(&G.streams);
        let gone: Vec<String> = s.by_url.keys().filter(|k| !keep.contains(k)).cloned().collect();
        for k in gone {
            closing.extend(remove_stream(&mut s, &k));
        }
    } else {
        let stamp = now();
        let mut s = lock(&G.streams);
        for src in s.sources.values() {
            if src.retired_at.load(SeqCst) == 0 {
                src.retired_at.store(stamp, SeqCst);
                for w in src.windows().iter() {
                    w.kick(); // закрытый фильм перестаёт качаться сразу
                }
            }
        }
        // Старые закрытые фильмы уходят: хватит одного. Последний закрытый остаётся и при повторном вызове.
        let newest = s.sources.values().map(|x| x.retired_at.load(SeqCst)).max().unwrap_or(0);
        let gone: Vec<String> = s
            .by_url
            .iter()
            .filter(|(_, t)| {
                s.sources.get(*t).is_some_and(|x| {
                    let r = x.retired_at.load(SeqCst);
                    r != 0 && r < newest
                })
            })
            .map(|(k, _)| k.clone())
            .collect();
        for k in gone {
            closing.extend(remove_stream(&mut s, &k));
        }
    }
    for src in closing {
        src.close();
    }
}

/// Плеер закрыт: скачанное хранится до открытия другого фильма (см. [`keep_only`]).
pub fn clear() {
    keep_only(&[])
}

/// Закрытые фильмы на диске, чей файл `keep` больше не принимает (ушли из медиатеки), удаляются. Сколько.
pub fn forget_retired(keep: impl Fn(&str) -> bool) -> usize {
    let mut closing = Vec::new();
    {
        let mut s = lock(&G.streams);
        let gone: Vec<String> = s
            .by_url
            .iter()
            .filter(|(key, t)| {
                s.sources.get(*t).is_some_and(|x| x.retired_at.load(SeqCst) != 0) && {
                    let link = mirror_link::param(key, "link").unwrap_or_default();
                    !link.is_empty() && !keep(&link)
                }
            })
            .map(|(k, _)| k.clone())
            .collect();
        for k in gone {
            closing.extend(remove_stream(&mut s, &k));
        }
    }
    let n = closing.len();
    for src in closing {
        src.close();
    }
    n
}

/// «Очистить кэш»: все закрытые фильмы на диске уходят (играющий остаётся). Сколько.
pub fn drop_retired() -> usize {
    forget_retired(|_| false)
}

/// Байт запаса на диске в его папке.
pub fn disk_bytes() -> i64 {
    dir().map(|d| disk_bytes_in(&d)).unwrap_or(0)
}

/// Байт запаса на диске в `directory` (и до init, когда папки ещё нет).
pub fn disk_bytes_in(directory: &Path) -> i64 {
    fn walk(p: &Path) -> i64 {
        let Ok(rd) = std::fs::read_dir(p) else { return 0 };
        rd.flatten()
            .map(|e| match e.metadata() {
                Ok(m) if m.is_dir() => walk(&e.path()),
                Ok(m) => m.len() as i64,
                Err(_) => 0,
            })
            .sum()
    }
    walk(directory)
}

/// Выбросить всё сейчас (чистый перезапуск после проблемы, выход из приложения).
pub fn drop_all() {
    let all: Vec<Arc<Source>> = {
        let mut s = lock(&G.streams);
        s.by_url.clear();
        s.sources.drain().map(|(_, v)| v).collect()
    };
    for src in all {
        src.close();
    }
    *G.side_keys.write().unwrap() = Arc::new(HashSet::new());
}

/// Держит диск в пределах [`cap_bytes`]: сверх него уходят окна, которые читали давнее всего. Никогда — окно,
/// которое читают, текущее окно видео и окно, прочитанное в пределах `RECENT_MS` от последнего чтения фильма.
fn trim_to_budget() {
    let budget = cap_bytes();
    if budget <= 0 {
        return;
    }
    let _g = lock(&G.trim_lock);
    let mut all: Vec<(Arc<Source>, Arc<Window>)> = Vec::new();
    for s in all_sources() {
        for w in s.windows().iter() {
            all.push((s.clone(), w.clone()));
        }
    }
    let mut used: i64 = all.iter().map(|(_, w)| w.disk_bytes()).sum();
    if used <= budget {
        return;
    }
    let film_last =
        all.iter().filter(|(s, _)| s.retired_at.load(SeqCst) == 0).map(|(_, w)| w.last_read_at()).max().unwrap_or(0);
    let recent = RECENT_MS.load(SeqCst);
    let stays = |s: &Arc<Source>, w: &Arc<Window>| {
        s.retired_at.load(SeqCst) == 0
            && (w.readers.load(SeqCst) > 0
                || film_last - w.last_read_at() < recent
                || (!s.side() && s.current().is_some_and(|c| Arc::ptr_eq(&c, w))))
    };
    let mut drop: Vec<_> = all.iter().filter(|(s, w)| !stays(s, w)).collect();
    drop.sort_by_key(|(_, w)| w.last_read_at());
    for (s, w) in drop {
        if used <= budget {
            break;
        }
        let freed = w.disk_bytes();
        {
            let _sync = lock(&s.sync);
            s.remove_window(w);
        }
        w.close();
        used -= freed;
        log(&format!(
            "reserve: window at {} MB dropped ({} MB): the film's reserve is limited to {} MB",
            w.start >> 20,
            freed >> 20,
            budget >> 20
        ));
    }
}

/// Плеер знает длину фильма: средний битрейт (размер ÷ длительность) задаёт рывки с первой секунды.
pub fn set_duration(url: &str, ms: i64) {
    if ms <= 0 {
        return;
    }
    let Some(src) = source_of(url) else { return };
    let total = src.total.load(SeqCst);
    if total <= 0 {
        return;
    }
    let avg = total as f64 * 1000.0 / ms as f64;
    let first = src.avg_rate.get() <= 0.0;
    src.avg_rate.set(avg);
    if src.rate.get() <= 0.0 {
        src.rate.set(avg);
    }
    if first {
        log(&format!("reserve: bitrate from size/length ≈ {:.1} Mbit/s", avg * 8.0 / 1_000_000.0));
    }
}

/// Фильм читается через локальный запас прямо сейчас.
pub fn is_active(url: &str) -> bool {
    cap_bytes() > 0 && source_of(url).is_some_and(|s| !s.broken.load(SeqCst))
}

/// Для панели сведений: МБ, скачанные впереди плеера в читаемом окне; `None`, если запас не используется.
pub fn ahead_mb(url: &str) -> Option<f64> {
    let src = source_of(url)?;
    let Some(w) = src.current() else { return Some(0.0) };
    Some((w.end() - w.read_pos.load(SeqCst)).max(0) as f64 / 1_048_576.0)
}

/// Примерно сколько миллисекунд фильма лежит на диске впереди плеера.
pub fn ahead_ms(url: &str, duration_ms: i64) -> i64 {
    let Some(src) = source_of(url) else { return 0 };
    let Some(w) = src.current() else { return 0 };
    let ahead = (w.end() - w.read_pos.load(SeqCst)).max(0);
    if ahead <= 0 {
        return 0;
    }
    let total = src.total.load(SeqCst);
    if total > 0 && duration_ms > 0 {
        return (ahead as f64 / total as f64 * duration_ms as f64) as i64;
    }
    let film = src.film_rate();
    if film > 0.0 {
        return (ahead as f64 / film * 1000.0) as i64;
    }
    0
}

/// Средний битрейт фильма в Мбит/с, когда плеер сообщил длину; `None` до того.
pub fn bitrate_mbit(url: &str) -> Option<f64> {
    let r = source_of(url)?.film_rate();
    (r > 0.0).then(|| r * 8.0 / 1_000_000.0)
}

/// Небольшой кусок потока прямо с зеркала, мимо запаса: байты `from .. from+len-1` и размер файла. Блокирует,
/// сдаётся через 7 с. Для TsClock.
pub fn read_range(url: &str, from: i64, len: usize) -> Option<(Vec<u8>, i64)> {
    let up = G.upstream.read().unwrap().clone()?;
    let c = Arc::new(Cancel::new());
    let (done, timer) = std::sync::mpsc::channel::<()>();
    let tc = c.clone();
    spawn("burst-range-timer", move || {
        if let Err(std::sync::mpsc::RecvTimeoutError::Timeout) = timer.recv_timeout(Duration::from_secs(7)) {
            tc.cancel();
        }
    });
    let result = (|| {
        let r = up.open(url, from, from + len as i64 - 1, &c).ok()?;
        if !(200..300).contains(&r.code) {
            return None;
        }
        let total = r
            .header("content-range")
            .and_then(|v| v.rsplit('/').next()?.parse::<i64>().ok())
            .or_else(|| r.header("content-length").and_then(|v| v.parse().ok()).filter(|_| r.code == 200))
            .unwrap_or(-1);
        let mut body = r.body?;
        let mut out = Vec::with_capacity(len);
        let mut buf = vec![0u8; 64 * 1024];
        while out.len() < len {
            let want = buf.len().min(len - out.len());
            match body.read(&mut buf[..want]) {
                Ok(0) => break,
                Ok(n) => out.extend_from_slice(&buf[..n]),
                Err(_) => return None,
            }
        }
        count_bytes(out.len() as i64);
        Some((out, total))
    })();
    let _ = done.send(());
    result
}

/// Сеть даёт меньше, чем нужно фильму (с запасом 15 %): запас не наберётся, пока фильм играет.
pub fn slower_than_film(url: &str) -> bool {
    let Some(src) = source_of(url) else { return false };
    let film = src.film_rate();
    let net = src.net_rate.get();
    net > 0.0 && film > 0.0 && net < film * 1.15
}

/// Сколько запаса (МБ) копить паузой: ~30 с фильма, не больше половины запаса.
pub fn catch_up_target_mb(url: &str) -> f64 {
    let Some(src) = source_of(url) else { return 0.0 };
    (src.film_rate() * 30.0).min(cap_bytes() as f64 * 0.5) / 1_048_576.0
}

/// Секунды фильма, которые ещё лежат на диске сразу позади места чтения (0 — неизвестно / ничего).
pub fn kept_behind_sec(url: &str) -> f64 {
    let Some(src) = source_of(url) else { return 0.0 };
    let Some(w) = src.current() else { return 0.0 };
    let film = src.film_rate();
    if film <= 0.0 {
        return 0.0;
    }
    let _g = lock(&w.lock);
    (w.read_pos.load(SeqCst) - w.valid_from()).max(0) as f64 / film
}

/// Копить запас, прежде чем плеер получит ещё (`ms` самое большее; 0 — отпустить). Читаемое окно качает дальше,
/// а плеер не получает ничего дальше места, где читает сейчас: для него это просто долгая буферизация.
pub fn hold(url: &str, ms: i64) {
    let Some(src) = source_of(url) else { return };
    let Some(w) = src.current() else { return };
    let _g = lock(&w.lock);
    if ms > 0 {
        if !w.held() {
            w.gate_at.store(w.read_pos.load(SeqCst), SeqCst);
        }
        w.held_until.store(now() + ms, SeqCst);
    } else {
        w.held_until.store(0, SeqCst);
    }
    w.cv.notify_all();
}

/// Отладочная строка: каждый поток (размер, битрейты) и его окна.
pub fn snapshot() -> String {
    let all = all_sources();
    if all.is_empty() {
        return "no streams".into();
    }
    let t = now();
    all.iter()
        .map(|s| {
            let url = lock(&s.url).clone();
            let name: String =
                url.split('?').next().unwrap_or("").rsplit('/').next().unwrap_or("").chars().take(40).collect();
            let r = s.retired_at.load(SeqCst);
            let retired = if r > 0 { format!(" retired {}s ago", (t - r) / 1000) } else { String::new() };
            let ws: Vec<String> = s.windows().iter().map(|w| w.describe(t)).collect();
            let ws = if ws.is_empty() { "no windows".to_string() } else { ws.join(", ") };
            format!(
                "{name} {}MB · film {:.1} avg {:.1} net {:.1} Mbit/s{retired} · {ws}",
                s.total.load(SeqCst).max(0) >> 20,
                s.rate.get() * 8.0 / 1e6,
                s.avg_rate.get() * 8.0 / 1e6,
                s.net_rate.get() * 8.0 / 1e6
            )
        })
        .collect::<Vec<_>>()
        .join(" | ")
}

// ---- один поток (один файл) --------------------------------------------------------------------------------------

struct Tail {
    buf: Vec<u8>,
    run_start: i64,
    run_end: i64,
}

struct Source {
    token: String,
    key: String, // тот же при новых подписях
    url: Mutex<String>,
    total: AtomicI64,
    content_type: Mutex<String>,
    broken: AtomicBool,
    /// Копия при записи: загрузчики читают её (current()) без замка источника.
    windows: RwLock<Arc<Vec<Arc<Window>>>>,
    /// Как `@Synchronized` у Kotlin-версии: window_for, close, удаление окна.
    sync: Mutex<()>,
    /// Скорость, с которой плеер берёт данные (байт/с).
    rate: AtomicF64,
    /// Средний битрейт фильма (размер ÷ длина): не меряется, пауза его не исказит.
    avg_rate: AtomicF64,
    /// Как быстро сеть отдавала последние рывки (байт/с).
    net_rate: AtomicF64,
    /// Плеер ушёл; хранится на диске до открытия другого фильма (0 — используется).
    retired_at: AtomicI64,
    /// Последние TAIL_CACHE байт файла одним куском, который пришёл подряд.
    tail: Mutex<Tail>,
}

impl Source {
    fn new(token: String, url: String) -> Self {
        Source {
            token,
            key: key_of(&url),
            url: Mutex::new(url),
            total: AtomicI64::new(-1),
            content_type: Mutex::new("application/octet-stream".into()),
            broken: AtomicBool::new(false),
            windows: RwLock::new(Arc::new(Vec::new())),
            sync: Mutex::new(()),
            rate: AtomicF64::zero(),
            avg_rate: AtomicF64::zero(),
            net_rate: AtomicF64::zero(),
            retired_at: AtomicI64::new(0),
            tail: Mutex::new(Tail { buf: Vec::new(), run_start: -1, run_end: -1 }),
        }
    }

    fn url(&self) -> String {
        lock(&self.url).clone()
    }

    fn windows(&self) -> Arc<Vec<Arc<Window>>> {
        self.windows.read().unwrap().clone()
    }

    fn add_window(&self, w: Arc<Window>) {
        let mut ws = self.windows.write().unwrap();
        let mut v = (**ws).clone();
        v.push(w);
        *ws = Arc::new(v);
    }

    fn remove_window(&self, w: &Arc<Window>) {
        let mut ws = self.windows.write().unwrap();
        let v: Vec<_> = ws.iter().filter(|x| !Arc::ptr_eq(x, w)).cloned().collect();
        *ws = Arc::new(v);
    }

    /// Озвучка / субтитры фильма, не его видео (см. keep_only): маленькое кольцо.
    fn side(&self) -> bool {
        G.side_keys.read().unwrap().contains(&self.key)
    }

    /// Размер кольца нового окна: видео — cap_bytes минус то, что может взять один побочный файл.
    fn ring_cap(&self) -> i64 {
        let cap = cap_bytes();
        let sides = G.side_keys.read().unwrap().clone();
        if sides.is_empty() {
            return cap.max(16 << 20);
        }
        let side = (cap / 8).clamp(8 << 20, 32 << 20);
        if sides.contains(&self.key) {
            side
        } else {
            (cap - side).max(16 << 20)
        }
    }

    fn film_rate(&self) -> f64 {
        let avg = self.avg_rate.get();
        if avg > 0.0 {
            avg
        } else {
            self.rate.get()
        }
    }

    /// Битрейт, по которому считается запас: измеренный, в пределах 0,5–4× среднего по файлу.
    fn sizing(&self) -> f64 {
        let avg = self.avg_rate.get();
        let rate = self.rate.get();
        if avg > 0.0 {
            rate.clamp(avg * 0.5, avg * 4.0)
        } else {
            rate
        }
    }

    fn add_net_sample(&self, v: f64) {
        let net = self.net_rate.get();
        self.net_rate.set(if net <= 0.0 { v } else { net * 0.5 + v * 0.5 });
    }

    fn kept_mb(&self) -> i64 {
        self.windows().iter().map(|w| w.disk_bytes()).sum::<i64>() >> 20
    }

    /// Каждый кусок, записанный в окно (`abs` — его место в файле).
    fn store_tail(&self, abs: i64, buf: &[u8]) {
        let tot = self.total.load(SeqCst);
        if tot <= 0 {
            return;
        }
        let tail_start = (tot - TAIL_CACHE as i64).max(0);
        let end = (abs + buf.len() as i64).min(tot);
        if end <= tail_start {
            return;
        }
        let mut t = lock(&self.tail);
        if t.buf.len() != TAIL_CACHE {
            t.buf = vec![0; TAIL_CACHE];
        }
        let from = abs.max(tail_start);
        if from != t.run_end {
            // не продолжение: новый кусок
            t.run_start = from;
            t.run_end = from;
        }
        let dst = (from - tail_start) as usize;
        let src = (from - abs) as usize;
        let n = (end - from) as usize;
        t.buf[dst..dst + n].copy_from_slice(&buf[src..src + n]);
        t.run_end = end;
    }

    /// Отвечает на запрос конца файла из памяти. `false` — его там нет (целиком), нужно окно.
    fn serve_tail(
        &self,
        out: &mut TcpStream,
        method: &str,
        range: Option<&str>,
        from: i64,
        to: i64,
    ) -> io::Result<bool> {
        let tot = self.total.load(SeqCst);
        if tot <= 0 || from >= tot {
            return Ok(false);
        }
        let last = if (from..tot).contains(&to) { to } else { tot - 1 };
        let body = {
            let t = lock(&self.tail);
            if t.run_start < 0 || from < t.run_start || last >= t.run_end {
                return Ok(false);
            }
            let tail_start = (tot - TAIL_CACHE as i64).max(0);
            // пишется вне замка: медленный читатель не держит загрузчик
            t.buf[(from - tail_start) as usize..(last - tail_start + 1) as usize].to_vec()
        };
        let ct = lock(&self.content_type).clone();
        let mut head = String::new();
        head.push_str(if range.is_some() { "HTTP/1.1 206 Partial Content\r\n" } else { "HTTP/1.1 200 OK\r\n" });
        head.push_str(&format!("Content-Type: {ct}\r\nAccept-Ranges: bytes\r\nContent-Length: {}\r\n", body.len()));
        if range.is_some() {
            head.push_str(&format!("Content-Range: bytes {from}-{last}/{tot}\r\n"));
        }
        head.push_str("Connection: close\r\n\r\n");
        out.write_all(head.as_bytes())?;
        if method != "HEAD" {
            out.write_all(&body)?;
        }
        out.flush()?;
        Ok(true)
    }

    fn window_for(self: &Arc<Self>, pos: i64) -> io::Result<Arc<Window>> {
        let _sync = lock(&self.sync);
        if let Some(w) = self.window_at(pos) {
            w.last_read_at.store(now(), SeqCst); // читается сразу: trim_to_budget его оставит
            return Ok(w);
        }
        let ws = self.windows();
        // Чуть раньше данных, которые уже здесь: плеер, ищущий ключевой кадр после перемотки назад, шагает по ~1,5 МБ
        // назад снова и снова. Качается только разрыв, дальше плеер читает уже скачанное (12.10: 31 запрос, 259 МБ).
        let next = ws
            .iter()
            .filter(|w| w.usable() && pos < w.valid_from() && w.valid_from() - pos <= BRIDGE)
            .min_by_key(|w| w.valid_from() - pos);
        if let Some(next) = next {
            let bridges: Vec<_> = ws.iter().filter(|w| w.limit >= 0).collect();
            if bridges.len() >= MAX_BRIDGES {
                if let Some(old) = bridges.iter().min_by_key(|w| w.last_read_at()) {
                    self.remove_window(old);
                    old.close();
                }
            }
            log(&format!(
                "reserve: bridge at {} MB: only {} KB up to what is already here",
                pos / 1_048_576,
                (next.valid_from() - pos) / 1024
            ));
            let w = Window::new(self.clone(), pos, next.valid_from())?;
            self.add_window(w.clone());
            let d = w.clone();
            spawn("burst-download", move || d.download());
            return Ok(w);
        }
        let full: Vec<_> = ws.iter().filter(|w| w.limit < 0).collect();
        if full.len() >= MAX_WINDOWS {
            // Окно фильма (то, что оказалось просмотром и читалось последним) не уходит никогда.
            let main = first_max_by_key(full.iter().filter(|w| w.burst()), |w| w.last_read_at());
            let victim =
                full.iter().filter(|w| main.is_none_or(|m| !Arc::ptr_eq(w, m))).min_by_key(|w| w.last_read_at());
            if let Some(v) = victim {
                self.remove_window(v);
                v.close();
            }
        }
        let url = self.url();
        log(&format!(
            "reserve: new window at {} MB of {}",
            pos / 1_048_576,
            url.split('?').next().unwrap_or("").rsplit('/').next().unwrap_or("")
        ));
        let w = Window::new(self.clone(), pos, -1)?;
        self.add_window(w.clone());
        let d = w.clone();
        spawn("burst-download", move || {
            trim_to_budget();
            d.download()
        });
        Ok(w)
    }

    /// Окно, где `pos` уже есть (или скоро будет); мостик — только ниже его конца.
    fn window_at(&self, pos: i64) -> Option<Arc<Window>> {
        let near = self.near();
        self.windows()
            .iter()
            .find(|w| {
                w.usable() && pos >= w.valid_from() && if w.limit >= 0 { pos < w.limit } else { pos <= w.end() + near }
            })
            .cloned()
    }

    /// Насколько дальше конца окна запрос ещё присоединяется к нему: что сеть принесёт за ~1,5 с (1–8 МБ; 8 МБ, пока
    /// скорость неизвестна). Дальше новый запрос быстрее (18.10: прыжок на 44 с вперёд ждал разрыв 12,6 с).
    fn near(&self) -> i64 {
        let net = self.net_rate.get();
        if net <= 0.0 {
            NEAR
        } else {
            ((net * NEAR_SEC) as i64).clamp(NEAR_MIN, NEAR)
        }
    }

    fn close(&self) {
        let ws = {
            let _sync = lock(&self.sync);
            let mut ws = self.windows.write().unwrap();
            std::mem::replace(&mut *ws, Arc::new(Vec::new()))
        };
        for w in ws.iter() {
            w.close();
        }
    }

    /// Окно, которое плеер читал последним: качать может только оно.
    fn current(&self) -> Option<Arc<Window>> {
        first_max_by_key(self.windows().iter(), |w| w.last_read_at()).cloned()
    }
}

/// Как `maxByOrNull` в Kotlin: при равенстве — первый.
fn first_max_by_key<'a, T, I: Iterator<Item = &'a T>>(it: I, key: impl Fn(&T) -> i64) -> Option<&'a T> {
    let mut best: Option<(&T, i64)> = None;
    for x in it {
        let k = key(x);
        if best.is_none_or(|(_, b)| k > b) {
            best = Some((x, k));
        }
    }
    best.map(|(x, _)| x)
}

// ---- непрерывный скачанный кусок в кольцевом файле -------------------------------------------------------------

enum FetchError {
    Io(io::Error),
    /// Зеркало отказало насовсем (4xx, нет Range): окно с ошибкой.
    Fatal(String),
    /// Подписанный адрес обновлён (refresh_url): повторить сразу, это не неудача.
    Renewed,
}

impl From<io::Error> for FetchError {
    fn from(e: io::Error) -> Self {
        FetchError::Io(e)
    }
}

/// Нет сети совсем (имя не находится, нет маршрута, отказ в соединении).
fn is_offline(e: &io::Error) -> bool {
    matches!(
        e.kind(),
        io::ErrorKind::ConnectionRefused
            | io::ErrorKind::HostUnreachable
            | io::ErrorKind::NetworkUnreachable
            | io::ErrorKind::AddrNotAvailable
    )
}

/// `limit ≥ 0`: мостик — качает только `start..limit` (там начинаются данные другого окна) и останавливается.
struct Window {
    src: Arc<Source>,
    start: i64,
    limit: i64,
    cap: i64, // неизменен всю жизнь окна
    path: PathBuf,
    /// Файл кольца; замок — как `synchronized(lock)` у Kotlin-версии, `cv` — его wait/notifyAll.
    lock: Mutex<Option<File>>,
    cv: Condvar,
    written: AtomicI64,        // байт от start подряд
    read_pos: AtomicI64,       // где плеер читает сейчас
    sequential: AtomicI64,     // прочитано по порядку (решает проба → рывки)
    consumed_total: AtomicI64, // сколько плеер взял отсюда (решает, когда кончается короткий разгон)
    last_read_at: AtomicI64,
    eof: AtomicBool,
    errored: AtomicBool,
    error: Mutex<Option<String>>,
    closed: AtomicBool,
    downloading: AtomicBool,
    readers: AtomicI32,    // соединения плеера, которые читают это окно сейчас
    held_until: AtomicI64, // см. hold
    gate_at: AtomicI64,    // пока держим, плеер не получает ничего отсюда и дальше
    // запрос в пути (для сторожа и смены сети)
    cancel: Mutex<Option<Arc<Cancel>>>,
    fetch_at: AtomicI64,
    last_byte_at: AtomicI64,
    got_headers: AtomicBool,
    stalled_why: Mutex<Option<String>>,
    kicked: AtomicBool,
    failures: AtomicI32,
}

impl Window {
    fn new(src: Arc<Source>, start: i64, limit: i64) -> io::Result<Arc<Window>> {
        let dir = dir().ok_or_else(|| io::Error::other("no reserve folder"))?;
        let path = dir.join(format!("{}.bin", IDS.fetch_add(1, SeqCst) + 1));
        let file = File::options().read(true).write(true).create(true).truncate(true).open(&path)?;
        let cap = src.ring_cap();
        let t = now();
        Ok(Arc::new(Window {
            src,
            start,
            limit,
            cap,
            path,
            lock: Mutex::new(Some(file)),
            cv: Condvar::new(),
            written: AtomicI64::new(0),
            read_pos: AtomicI64::new(start),
            sequential: AtomicI64::new(0),
            consumed_total: AtomicI64::new(0),
            last_read_at: AtomicI64::new(t),
            eof: AtomicBool::new(false),
            errored: AtomicBool::new(false),
            error: Mutex::new(None),
            closed: AtomicBool::new(false),
            downloading: AtomicBool::new(false),
            readers: AtomicI32::new(0),
            held_until: AtomicI64::new(0),
            gate_at: AtomicI64::new(0),
            cancel: Mutex::new(None),
            fetch_at: AtomicI64::new(0),
            last_byte_at: AtomicI64::new(0),
            got_headers: AtomicBool::new(false),
            stalled_why: Mutex::new(None),
            kicked: AtomicBool::new(false),
            failures: AtomicI32::new(0),
        }))
    }

    fn usable(&self) -> bool {
        !self.errored.load(SeqCst) && !self.closed.load(SeqCst)
    }
    fn closed(&self) -> bool {
        self.closed.load(SeqCst)
    }
    fn eof(&self) -> bool {
        self.eof.load(SeqCst)
    }
    fn last_read_at(&self) -> i64 {
        self.last_read_at.load(SeqCst)
    }
    fn end(&self) -> i64 {
        self.start + self.written.load(SeqCst)
    }
    fn read_pos(&self) -> i64 {
        self.read_pos.load(SeqCst)
    }
    fn disk_bytes(&self) -> i64 {
        self.written.load(SeqCst).min(self.cap)
    }
    fn valid_from(&self) -> i64 {
        self.start + (self.written.load(SeqCst) - self.cap).max(0)
    }
    fn burst(&self) -> bool {
        self.sequential.load(SeqCst) >= PROBE_LIMIT
    }
    fn held(&self) -> bool {
        now() < self.held_until.load(SeqCst)
    }

    fn notify(&self) {
        let _g = lock(&self.lock);
        self.cv.notify_all();
    }

    fn set_eof(&self) {
        let _g = lock(&self.lock);
        self.eof.store(true, SeqCst);
        self.cv.notify_all();
    }

    fn fail(&self, msg: String) {
        let _g = lock(&self.lock);
        *lock(&self.error) = Some(msg);
        self.errored.store(true, SeqCst);
        self.cv.notify_all();
    }

    /// Нет ответа / данных слишком долго: оборвать, загрузчик повторит рывок на новом соединении.
    fn check_stall(&self, t: i64) {
        let Some(c) = lock(&self.cancel).clone() else { return };
        if self.closed() {
            return;
        }
        let starving = self.end() - self.read_pos() < (2 << 20);
        let header = HEADER_STALL_MS.load(SeqCst);
        // сети, которая не раз подводила, — больше терпения, только когда впереди есть запас
        let head = if starving { header } else { header + 4_000 * self.failures.load(SeqCst).min(2) as i64 };
        let got = self.got_headers.load(SeqCst);
        let fetch_at = self.fetch_at.load(SeqCst);
        let last_byte = self.last_byte_at.load(SeqCst);
        let why = if !got && t - fetch_at > head {
            format!("no answer for {:.0} s", (t - fetch_at) as f64 / 1000.0)
        } else if got && t - last_byte > BODY_STALL_MS.load(SeqCst) {
            format!("no data for {:.0} s", (t - last_byte) as f64 / 1000.0)
        } else {
            return;
        };
        *lock(&self.stalled_why) = Some(why);
        c.cancel();
    }

    fn kick(&self) {
        {
            let _g = lock(&self.lock);
            self.kicked.store(true, SeqCst);
            self.cv.notify_all();
        }
        if let Some(c) = lock(&self.cancel).clone() {
            c.cancel();
        }
    }

    /// Сейчас качать нельзя: экран выключен, фильм закрыт (только хранится) или сети нет совсем.
    fn stopped(&self) -> bool {
        is_screen_off() || self.src.retired_at.load(SeqCst) != 0 || !is_network_online()
    }

    /// Экран включён: пауза перед повтором / ожидание кончается сразу.
    fn wake(&self) {
        let _g = lock(&self.lock);
        self.kicked.store(true, SeqCst);
        self.cv.notify_all();
    }

    fn describe(&self, t: i64) -> String {
        let state = if self.closed() {
            "closed".to_string()
        } else if self.errored.load(SeqCst) {
            format!("error({})", lock(&self.error).clone().unwrap_or_default())
        } else if self.eof() {
            "eof".into()
        } else if self.downloading.load(SeqCst) {
            "downloading".into()
        } else {
            "resting".into()
        };
        let phase = if self.limit >= 0 {
            format!("bridge to {}MB", self.limit >> 20)
        } else if !self.burst() {
            "probe".into()
        } else if self.ramping() {
            "ramp".into()
        } else {
            "full".into()
        };
        format!(
            "@{}MB ahead {:.1}MB (low {} high {}) kept {}MB {phase} {state} readers {} quiet {}s",
            self.start >> 20,
            (self.end() - self.read_pos()).max(0) as f64 / 1_048_576.0,
            self.low() >> 20,
            self.high() >> 20,
            self.disk_bytes() >> 20,
            self.readers.load(SeqCst),
            (t - self.last_read_at()) / 1000
        )
    }

    /// Плеер ушёл в другое место (читают другое окно): это больше не наполнять.
    fn abandoned(&self) -> bool {
        if is_screen_off() || self.src.retired_at.load(SeqCst) != 0 {
            return true;
        }
        if self.held() {
            return false;
        }
        let quiet = now() - self.last_read_at();
        // плеер закрыл соединение к этому месту (короткое чтение индекса или прыжок): стоп сразу
        (self.readers.load(SeqCst) == 0 && quiet > 400)
            || (quiet > 1500 && !self.src.current().is_some_and(|c| std::ptr::eq(&*c, self)))
    }

    /// Сколько секунд фильма копить: от скорости сети относительно битрейта (55–130 с; неизвестно — 110 с).
    fn reserve_seconds(&self) -> f64 {
        let film = self.src.sizing();
        let net = self.src.net_rate.get();
        if film <= 0.0 || net <= 0.0 {
            return 110.0;
        }
        let ratio = net / film;
        if ratio < 2.0 {
            55.0
        } else if ratio >= 3.2 {
            130.0
        } else {
            55.0 + (ratio - 2.0) / 1.2 * 75.0
        }
    }

    /// До скольких байт впереди наполнять.
    fn high(&self) -> i64 {
        if !self.burst() {
            return PROBE_AHEAD;
        }
        let full = ((self.src.sizing() * self.reserve_seconds()) as i64)
            .max((24i64 << 20).min(self.cap * 3 / 4))
            .min(self.cap * 7 / 8);
        if self.ramping() {
            full.min(self.ramp_ahead())
        } else {
            full
        }
    }

    /// Когда начинать снова (сеть просыпается).
    fn low(&self) -> i64 {
        if !self.burst() {
            return PROBE_AHEAD / 2;
        }
        let high = self.high();
        ((self.src.sizing() * 25.0) as i64).max((6i64 << 20).min(high / 3)).min(high / 2)
    }

    // Короткий разгон: после открытия или перемотки только ~36 МБ (≥ 20 с фильма); полный запас — когда плеер
    // пробыл здесь ~45 с фильма.
    fn ramp_after(&self) -> i64 {
        ((32i64 << 20).max((self.src.sizing() * 45.0) as i64)).min(self.cap * 3 / 4)
    }
    fn ramp_ahead(&self) -> i64 {
        (36i64 << 20).max((self.src.sizing() * 20.0) as i64)
    }
    fn ramping(&self) -> bool {
        if self.consumed_total.load(SeqCst) >= self.ramp_after() {
            return false;
        }
        // сеть медленнее 3× битрейта не получает короткий запас: полный защищает от рывков
        let net = self.src.net_rate.get();
        let sizing = self.src.sizing();
        !(net > 0.0 && sizing > 0.0 && net < sizing * 3.0)
    }

    fn close(&self) {
        self.closed.store(true, SeqCst);
        if let Some(c) = lock(&self.cancel).clone() {
            c.cancel();
        }
        // под замком: чтение или запись, что уже прошли проверку closed, кончаются раньше, чем исчезнет файл
        let mut f = lock(&self.lock);
        self.cv.notify_all();
        *f = None;
        let _ = std::fs::remove_file(&self.path);
    }

    fn wait<'a>(&self, g: MutexGuard<'a, Option<File>>, ms: i64) -> MutexGuard<'a, Option<File>> {
        self.cv.wait_timeout(g, Duration::from_millis(ms.max(1) as u64)).unwrap_or_else(|e| e.into_inner()).0
    }

    fn download(self: Arc<Self>) {
        let mut offline_cycles = 0;
        while !self.closed() {
            if self.limit >= 0 && self.end() >= self.limit {
                return; // мостик готов: дальше — в следующем окне
            }
            // Ждать, пока впереди хватает (или минуту никто не читал: пауза / брошенное окно).
            {
                let mut g = lock(&self.lock);
                while !self.closed() && !self.eof() {
                    // Экран выключен / фильм закрыт / нет сети: ни опроса мёртвой сети, ни повторов.
                    let off = self.stopped();
                    let idle = off || (!self.held() && (now() - self.last_read_at() > 60_000 || self.abandoned()));
                    if !idle && self.end() - self.read_pos() < self.low() {
                        break;
                    }
                    g = self.wait(g, if off { 5000 } else { 1000 });
                }
            }
            if self.closed() || self.eof() {
                return;
            }
            if self.stopped() {
                continue;
            }
            let e = match self.fetch() {
                Ok(()) => {
                    self.failures.store(0, SeqCst);
                    continue;
                }
                Err(e) => e,
            };
            if self.closed() {
                return;
            }
            if self.stopped() {
                // остановили нарочно, не неудача
                self.kicked.store(false, SeqCst);
                *lock(&self.stalled_why) = None;
                continue;
            }
            let e = match e {
                FetchError::Renewed => {
                    log("reserve: signature expired → new address, repeating");
                    continue;
                }
                other => other,
            };
            let stalled = lock(&self.stalled_why).take();
            let net_change = self.kicked.swap(false, SeqCst);
            let (msg, io_err) = match &e {
                FetchError::Io(x) => (x.to_string(), Some(x)),
                FetchError::Fatal(m) => (m.clone(), None),
                FetchError::Renewed => unreachable!(),
            };
            let timed_out =
                io_err.is_some_and(|x| matches!(x.kind(), io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock));
            let conn_error = stalled.is_some() || timed_out;
            if stalled.is_some() {
                reset_connections();
            }
            let failures = self.failures.load(SeqCst);
            let note = if stalled.is_some() {
                " → new connection"
            } else if net_change {
                " (network changed) → new connection"
            } else {
                ""
            };
            log(&format!(
                "reserve: request failed ({}), attempt {}{note}",
                stalled.as_deref().unwrap_or(&msg),
                failures + 1
            ));
            if let FetchError::Fatal(m) = e {
                self.fail(m);
                return;
            }
            if net_change {
                // новая сеть: сразу ещё раз
                self.failures.store(0, SeqCst);
                reset_connections();
                continue;
            }
            if io_err.is_some_and(is_offline) {
                // Сети нет совсем: это не отказ сервера, попытки не тратятся. Пауза 1 → 5 → 15 → 30 с; смена сети
                // будит сразу.
                offline_cycles += 1;
                let backoff = if !is_network_online() {
                    30_000
                } else {
                    match offline_cycles {
                        1 => 1_000,
                        2 => 5_000,
                        3 => 15_000,
                        _ => 30_000,
                    }
                };
                self.pause(backoff);
                if self.kicked.swap(false, SeqCst) {
                    self.failures.store(0, SeqCst);
                    offline_cycles = 0;
                }
                continue;
            }
            offline_cycles = 0;
            let failures = self.failures.fetch_add(1, SeqCst) + 1;
            if failures > 8 {
                self.fail(if msg.is_empty() { "network".into() } else { msg });
                return;
            }
            let starving = self.end() - self.read_pos() < (2 << 20);
            if starving {
                // плеер ждёт данных прямо сейчас: не спать секунды
                std::thread::sleep(Duration::from_millis(150));
            } else if conn_error {
                std::thread::sleep(Duration::from_millis(200));
            } else {
                self.pause(8_000i64.min(500i64 << failures.min(4))); // смена сети будит
            }
            if self.kicked.swap(false, SeqCst) {
                self.failures.store(0, SeqCst);
                offline_cycles = 0;
            }
        }
    }

    /// Ждёт `ms` перед следующей попыткой; раньше кончают только смена сети (kick) и close. Чтения плеера тоже будят
    /// замок, но не прерывают паузу (12.10: ~50 попыток за 25 с без сети вместо одной в 3 с).
    fn pause(&self, ms: i64) {
        let until = now() + ms;
        let mut g = lock(&self.lock);
        while !self.kicked.load(SeqCst) && !self.closed() {
            let left = until - now();
            if left <= 0 {
                break;
            }
            g = self.wait(g, left);
        }
    }

    /// Один рывок: от end(), пока впереди плеера не будет high() байт (или не кончится файл).
    fn fetch(&self) -> Result<(), FetchError> {
        let from = self.end();
        let total = self.src.total.load(SeqCst);
        if (0..=from).contains(&total) {
            self.set_eof();
            return Ok(());
        }
        let t0 = now();
        // Проверенный (играющий) поток просит ровно недостающее: ответ кончается сам и соединение остаётся для
        // следующего рывка. Не проверен / размер неизвестен — один открытый запрос.
        let sizing = self.src.sizing();
        let net = self.src.net_rate.get();
        let to = if self.limit >= 0 {
            self.limit - 1 // мостик: ровно разрыв
        } else if self.burst() && total > 0 && sizing > 0.0 && net >= 0.001 && net <= sizing * SLOW_NET {
            // сеть ниже ~1,8× битрейта: даже 2,2× не дотянет до high() — один открытый запрос до «запас полон»
            -1
        } else if self.burst() && total > 0 {
            // Плеер ест film/net каждого байта, пока идёт рывок: чтобы набрать need, просим need·net/(net−film)
            let need = (self.high() - (self.end() - self.read_pos()).max(0)).max(1 << 20);
            let margin = if sizing > 0.0 && net > sizing * 1.2 {
                (net / (net - sizing)).clamp(BURST_MARGIN, 2.2)
            } else {
                BURST_MARGIN
            };
            (total - 1).min(from + (need as f64 * margin) as i64 - 1).max(from)
        } else {
            -1
        };
        self.kicked.store(false, SeqCst);
        *lock(&self.stalled_why) = None;
        let c = Arc::new(Cancel::new());
        *lock(&self.cancel) = Some(c.clone());
        self.got_headers.store(false, SeqCst);
        self.fetch_at.store(t0, SeqCst);
        self.last_byte_at.store(t0, SeqCst);
        let up = G.upstream.read().unwrap().clone();
        let res = match up {
            None => Err(io::Error::other("no upstream")),
            Some(up) => up.open(&self.src.url(), from, to, &c),
        };
        let res = match res {
            Ok(r) => r,
            Err(e) => {
                *lock(&self.cancel) = None;
                log(&format!("reserve: burst at {} MB: no answer after {} ms ({e})", from / 1_048_576, now() - t0));
                return Err(e.into());
            }
        };
        let t_answer = now() - t0;
        self.got_headers.store(true, SeqCst);
        self.last_byte_at.store(now(), SeqCst);
        self.downloading.store(true, SeqCst);
        let mut m = Meter { t0, t_first: 0, got: 0, m_at: 0, m_got: 0, sampled: false, why: "error" };
        let result = self.fetch_body(res, from, to, &mut m);
        // «finally»: замер скорости и строка журнала о рывке
        self.downloading.store(false, SeqCst);
        *lock(&self.cancel) = None;
        let ms = now() - t0;
        let data_ms = (ms - m.t_first).max(1);
        if !m.sampled {
            // короткий рывок: весь он — один замер
            if m.got >= (3 << 20) && data_ms >= 200 {
                self.src.add_net_sample(m.got as f64 * 1000.0 / data_ms as f64);
            }
        } else {
            // хвост после последнего замера
            let tail_ms = t0 + ms - m.m_at;
            if m.got - m.m_got >= (3 << 20) && tail_ms >= 200 {
                self.src.add_net_sample((m.got - m.m_got) as f64 * 1000.0 / tail_ms as f64);
            }
        }
        let ahead = (self.end() - self.read_pos()).max(0) as f64 / 1_048_576.0;
        let rate = if self.src.rate.get() > 0.0 {
            format!(" · film {:.1} (avg {:.1})", self.src.rate.get() * 8.0 / 1e6, self.src.avg_rate.get() * 8.0 / 1e6)
        } else {
            String::new()
        };
        log(&format!(
            "reserve: burst at {} MB: {:.1} MB in {:.1} s, {:.1} Mbit/s{rate} · answer {t_answer} ms, first data {} ms · ahead {ahead:.0} MB · {}",
            from / 1_048_576,
            m.got as f64 / 1_048_576.0,
            ms as f64 / 1000.0,
            if m.got > 0 { m.got as f64 * 8.0 / 1000.0 / data_ms as f64 } else { 0.0 },
            m.t_first,
            m.why
        ));
        result
    }

    fn fetch_body(&self, r: UpstreamResponse, from: i64, to: i64, m: &mut Meter) -> Result<(), FetchError> {
        let code = r.code;
        if code == 416 {
            self.set_eof();
            return Ok(());
        }
        if code == 200 && from > 0 {
            self.src.broken.store(true, SeqCst);
            return Err(FetchError::Fatal("no ranges".into()));
        }
        let url = self.src.url();
        if (code == 401 || code == 403) && mirror_link::is_expired(&url, mirror_link::now_sec(), 60) {
            // Нового адреса нет (нет сети, вышли из аккаунта): обычная неудачная попытка, кончится через несколько.
            let refresh = G.refresh_url.read().unwrap().clone();
            let fresh = refresh(&url)
                .filter(|f| key_of(f) == key_of(&url) && !mirror_link::is_expired(f, mirror_link::now_sec(), 60));
            let Some(fresh) = fresh else {
                return Err(io::Error::other(format!("HTTP {code}, signature expired")).into());
            };
            let mut u = lock(&self.src.url);
            if *u == url {
                *u = fresh;
            }
            return Err(FetchError::Renewed);
        }
        if (400..500).contains(&code) {
            return Err(FetchError::Fatal(format!("HTTP {code}")));
        }
        if !(200..300).contains(&code) {
            return Err(io::Error::other(format!("HTTP {code}")).into());
        }
        if let Some(t) = r.header("content-type") {
            *lock(&self.src.content_type) = t.to_string();
        }
        if self.src.total.load(SeqCst) < 0 {
            let total = r
                .header("content-range")
                .and_then(|v| v.rsplit('/').next()?.parse::<i64>().ok())
                .or_else(|| r.header("content-length").and_then(|v| v.parse::<i64>().ok()).map(|n| n + from))
                .unwrap_or(-1);
            self.src.total.store(total, SeqCst);
        }
        self.notify();
        let Some(mut input) = r.body else { return Err(io::Error::other("no body").into()) };
        let mut buf = vec![0u8; CHUNK];
        while !self.closed() {
            // Никто здесь больше не читает → закрыть запрос сразу.
            if self.abandoned() {
                m.why = "player left";
                return Ok(());
            }
            let ahead = self.end() - self.read_pos();
            // Просмотр: рывок кончается, когда впереди хватает (ограниченный запрос читается до конца: соединение в пул).
            if self.burst() && to < 0 && ahead >= self.high() {
                m.why = "reserve full";
                return Ok(());
            }
            if ahead >= self.cap * 7 / 8 {
                m.why = "ring full";
                return Ok(());
            }
            // ...если плеер не берёт данные отсюда: внешняя озвучка, которая не играет (18.10: 8 озвучек по 3–32 МБ).
            if !self.burst() && !self.held() && ahead >= PROBE_AHEAD && now() - self.last_read_at() > PROBE_IDLE_MS {
                m.why = "player idle";
                return Ok(());
            }
            let n = input.read(&mut buf)?;
            if n == 0 {
                // Ответ кончился. Конец файла — только когда пришло всё до размера; полный ограниченный ответ — конец
                // рывка; короче — соединение оборвали.
                let total = self.src.total.load(SeqCst);
                let end = self.end();
                if total > 0 && end < total && to >= 0 && end > to {
                    m.why = "burst done";
                    return Ok(());
                }
                if total > 0 && end < total {
                    return Err(io::Error::new(
                        io::ErrorKind::UnexpectedEof,
                        format!("answer ended at {} of {} MB", end >> 20, total >> 20),
                    )
                    .into());
                }
                m.why = "end of file";
                self.set_eof();
                return Ok(());
            }
            let t = now();
            self.last_byte_at.store(t, SeqCst);
            if m.t_first == 0 {
                m.t_first = t - m.t0;
            }
            m.got += n as i64;
            if m.m_at == 0 {
                m.m_at = t;
                m.m_got = m.got;
            } else if t - m.m_at >= 5_000 {
                // скорость сети меряется каждые ~5 с, не только в конце рывка
                if m.got - m.m_got >= (1 << 20) {
                    self.src.add_net_sample((m.got - m.m_got) as f64 * 1000.0 / (t - m.m_at) as f64);
                }
                m.m_at = t;
                m.m_got = m.got;
                m.sampled = true;
            }
            count_bytes(n as i64);
            {
                let mut f = lock(&self.lock);
                if self.closed() {
                    m.why = "closed";
                    return Ok(());
                }
                let end = self.end();
                self.src.store_tail(end, &buf[..n]);
                if let Some(file) = f.as_mut() {
                    self.ring_write(file, end, &buf[..n])?;
                }
                self.written.fetch_add(n as i64, SeqCst);
                self.cv.notify_all();
            }
            trim_to_budget();
        }
        m.why = "closed";
        Ok(())
    }

    fn ring_write(&self, file: &mut File, abs: i64, buf: &[u8]) -> io::Result<()> {
        let mut off = 0usize;
        let mut pos = abs;
        while off < buf.len() {
            let at = (pos - self.start) % self.cap;
            let len = ((buf.len() - off) as i64).min(self.cap - at) as usize;
            file.seek(SeekFrom::Start(at as u64))?;
            file.write_all(&buf[off..off + len])?;
            off += len;
            pos += len as i64;
        }
        Ok(())
    }

    /// Копирует до `max` байт с места `abs` в `buf`; ждёт загрузчик, если надо. -1 — больше ничего. `alive` —
    /// плеер ещё держит соединение: пока он ждёт здесь, окно считается читаемым, и ушедший плеер должен кончить
    /// ожидание (18.10: окно, где ждал закрытый плеер, снова начало качать ни для кого).
    fn read(&self, abs: i64, buf: &mut [u8], alive: &mut dyn FnMut() -> bool) -> io::Result<i64> {
        if self.limit >= 0 && abs >= self.limit {
            return Ok(-1); // за мостиком: читатель переходит в следующее окно
        }
        let mut g = lock(&self.lock);
        let mut wait_start = now();
        while !self.closed() && !self.errored.load(SeqCst) {
            if self.src.retired_at.load(SeqCst) != 0 {
                return Ok(-1); // фильм закрыт
            }
            if self.held() && abs >= self.gate_at.load(SeqCst) {
                // копим запас: плеер ждёт (это ожидание — не предел 45 с «ничего не приходит»)
                if !alive() {
                    return Ok(-1);
                }
                self.read_pos.store(abs, SeqCst);
                self.last_read_at.store(now(), SeqCst);
                g = self.wait(g, 500i64.min((self.held_until.load(SeqCst) - now()).max(1)));
                wait_start = now();
                continue;
            }
            if abs < self.end() || self.eof() {
                break;
            }
            if !alive() {
                return Ok(-1);
            }
            self.read_pos.store(abs, SeqCst);
            self.last_read_at.store(now(), SeqCst);
            self.cv.notify_all();
            if now() - wait_start > 45_000 {
                return Ok(-1);
            }
            g = self.wait(g, 500);
        }
        if self.closed() || abs >= self.end() || abs < self.valid_from() {
            return Ok(-1);
        }
        let mut n = (buf.len() as i64).min(self.end() - abs);
        if self.held() && self.gate_at.load(SeqCst) > abs {
            n = n.min(self.gate_at.load(SeqCst) - abs);
        }
        let Some(file) = g.as_mut() else { return Ok(-1) };
        let mut off = 0i64;
        let mut pos = abs;
        while off < n {
            let at = (pos - self.start) % self.cap;
            let len = (n - off).min(self.cap - at);
            file.seek(SeekFrom::Start(at as u64))?;
            file.read_exact(&mut buf[off as usize..(off + len) as usize])?;
            off += len;
            pos += len;
        }
        Ok(n)
    }

    fn consumed(&self, abs: i64, n: i64) {
        let _g = lock(&self.lock);
        if abs == self.read_pos() {
            self.sequential.fetch_add(n, SeqCst);
        }
        self.consumed_total.fetch_add(n, SeqCst);
        self.read_pos.store(abs + n, SeqCst);
        self.last_read_at.store(now(), SeqCst);
        self.cv.notify_all();
    }

    fn add_reader(&self) {
        let _g = lock(&self.lock);
        self.readers.fetch_add(1, SeqCst);
        self.last_read_at.store(now(), SeqCst);
        self.cv.notify_all();
    }

    fn remove_reader(&self) {
        let _g = lock(&self.lock);
        self.readers.fetch_sub(1, SeqCst);
        self.cv.notify_all();
    }
}

struct Meter {
    t0: i64,
    t_first: i64,
    got: i64,
    m_at: i64,
    m_got: i64,
    sampled: bool,
    why: &'static str,
}

// ---- локальная сторона HTTP (с ней говорит плеер) ---------------------------------------------------------------

fn serve(s: TcpStream) {
    let _ = serve_inner(s);
}

fn serve_inner(mut s: TcpStream) -> io::Result<()> {
    s.set_read_timeout(Some(Duration::from_secs(60)))?;
    let _ = s.set_nodelay(true);
    let mut input = BufReader::new(s.try_clone()?);
    let Some(request_line) = read_line(&mut input)? else { return Ok(()) };
    let mut range: Option<String> = None;
    let mut from_browser = false;
    loop {
        let Some(h) = read_line(&mut input)? else { return Ok(()) };
        if h.is_empty() {
            break;
        }
        let lower = h.to_ascii_lowercase();
        if lower.starts_with("range:") {
            range = Some(h[6..].trim().to_string());
        }
        // Говорит с этим сервером только плеер. Веб-страница в браузере шлёт Origin — отказ сразу (в адресе ещё и
        // случайный 128-битный токен, которого никто не знает).
        if lower.starts_with("origin:") {
            from_browser = true;
        }
    }
    let mut parts = request_line.split(' ');
    let method = parts.next().unwrap_or("").to_string();
    let path = parts.next().unwrap_or("");
    let token = path.strip_prefix("/s/").unwrap_or(path).split('/').next().unwrap_or("");
    let src = lock(&G.streams).sources.get(token).cloned();
    let Some(src) = src.filter(|_| !from_browser && (method == "GET" || method == "HEAD")) else {
        return status(&mut s, "404 Not Found");
    };
    let (mut from, mut to) = (0i64, -1i64);
    if let Some(r) = range.as_deref() {
        let r = r.strip_prefix("bytes=").unwrap_or(r);
        let mut it = r.split('-');
        from = it.next().and_then(|x| x.trim().parse().ok()).unwrap_or(0);
        to = it.next().and_then(|x| x.trim().parse().ok()).unwrap_or(-1);
    }
    if src.serve_tail(&mut s, &method, range.as_deref(), from, to)? {
        return Ok(()); // конец файла: из памяти, без окна
    }
    let mut reading = src.window_for(from)?; // читаемое окно (мостик передаёт следующему)
    reading.add_reader();
    let r = serve_window(&mut s, &src, &mut reading, &method, range.as_deref(), from, to);
    reading.remove_reader();
    r
}

#[allow(clippy::too_many_arguments)]
fn serve_window(
    s: &mut TcpStream,
    src: &Arc<Source>,
    reading: &mut Arc<Window>,
    method: &str,
    range: Option<&str>,
    from: i64,
    to: i64,
) -> io::Result<()> {
    // Длина известна после первого ответа зеркала (fetch будит читателей, когда заголовки пришли).
    let wait_start = now();
    while src.total.load(SeqCst) < 0 && reading.usable() && !src.broken.load(SeqCst) && now() - wait_start < 30_000 {
        let g = lock(&reading.lock);
        if src.total.load(SeqCst) < 0 && reading.usable() {
            drop(reading.wait(g, 200));
        }
    }
    let total = src.total.load(SeqCst);
    if total < 0 {
        return status(s, "502 Bad Gateway");
    }
    if from >= total {
        return s.write_all(
            format!(
                "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{total}\r\nConnection: close\r\n\r\n"
            )
            .as_bytes(),
        );
    }
    let last = if (from..total).contains(&to) { to } else { total - 1 };
    let ct = lock(&src.content_type).clone();
    let mut head = String::new();
    head.push_str(if range.is_some() { "HTTP/1.1 206 Partial Content\r\n" } else { "HTTP/1.1 200 OK\r\n" });
    head.push_str(&format!("Content-Type: {ct}\r\nAccept-Ranges: bytes\r\nContent-Length: {}\r\n", last - from + 1));
    if range.is_some() {
        head.push_str(&format!("Content-Range: bytes {from}-{last}/{total}\r\n"));
    }
    head.push_str("Connection: close\r\n\r\n");
    s.write_all(head.as_bytes())?;
    if method == "HEAD" {
        return Ok(());
    }
    let mut buf = vec![0u8; CHUNK];
    let mut pos = from;
    let t0 = now();
    let (mut tm, mut pm) = (0i64, 0i64); // база замера битрейта, после стартового чтения плеера
                                         // Смотрится, пока ждём данных, не чаще раза в 0,4 с.
    let peer = s.try_clone()?;
    let mut checked_at = 0i64;
    let mut gone = false;
    let mut alive = || {
        let t = now();
        if !gone && t - checked_at >= 400 {
            checked_at = t;
            gone = peer_gone(&peer);
        }
        !gone
    };
    while pos <= last {
        let want = (CHUNK as i64).min(last - pos + 1) as usize;
        let w = reading.clone();
        let n = w.read(pos, &mut buf[..want], &mut alive)?;
        if n <= 0 {
            // конец мостика: дальше в окно, чьи данные начинаются здесь (или новое, если его нет)
            if w.limit < 0 || pos < w.limit {
                break;
            }
            let next = src.window_for(pos)?;
            w.remove_reader();
            next.add_reader();
            *reading = next;
            continue;
        }
        s.write_all(&buf[..n as usize])?;
        w.consumed(pos, n);
        pos += n;
        // Местный битрейт ≈ как быстро плеер берёт данные: замеры по 20 с после первых 15 с. Отрезок с паузой
        // (сильно ниже среднего по файлу) пропускается.
        let t = now();
        if tm == 0 {
            if t - t0 > 15_000 {
                tm = t;
                pm = pos;
            }
        } else if t - tm >= 20_000 {
            let m = (pos - pm) as f64 * 1000.0 / (t - tm) as f64;
            if m >= src.avg_rate.get() * 0.4 {
                let r = src.rate.get();
                src.rate.set(if r > 0.0 { r * 0.7 + m * 0.3 } else { m });
            }
            tm = t;
            pm = pos;
        }
    }
    s.flush()
}

/// Плеер закрыл соединение. После запроса он ничего не шлёт, так что чтение даёт конец потока (или ошибку), только
/// когда он ушёл; до того — «нет данных».
fn peer_gone(s: &TcpStream) -> bool {
    if s.set_nonblocking(true).is_err() {
        return false;
    }
    let mut b = [0u8; 1];
    let r = match s.peek(&mut b) {
        Ok(0) => true,
        Ok(_) => false,
        Err(e) if e.kind() == io::ErrorKind::WouldBlock => false,
        Err(e) if e.kind() == io::ErrorKind::Interrupted => false,
        Err(_) => true,
    };
    let _ = s.set_nonblocking(false);
    r
}

fn status(out: &mut TcpStream, s: &str) -> io::Result<()> {
    out.write_all(format!("HTTP/1.1 {s}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").as_bytes())
}

fn read_line(input: &mut impl BufRead) -> io::Result<Option<String>> {
    let mut v = Vec::new();
    let n = input.by_ref().take(8192).read_until(b'\n', &mut v)?;
    if n == 0 {
        return Ok(None);
    }
    if !v.ends_with(b"\n") && n >= 8192 {
        return Ok(None);
    }
    while v.last().is_some_and(|b| *b == b'\n' || *b == b'\r') {
        v.pop();
    }
    Ok(Some(String::from_utf8_lossy(&v).into_owned()))
}

#[cfg(test)]
#[path = "burst_proxy_tests.rs"]
mod tests;

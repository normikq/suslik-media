//! Запас против настоящего HTTP-источника с Range (как зеркало). Перенос `BurstProxyTest.kt`.
//!
//! Состояние запаса одно на процесс (как `object` в Kotlin), поэтому тесты идут по одному ([`SERIAL`]).

use super::*;
use crate::http1::{self, PlainConnector};
use std::sync::atomic::AtomicUsize;

const LEN: usize = 40 << 20;

static SERIAL: Mutex<()> = Mutex::new(());

static DATA: LazyLock<Arc<Vec<u8>>> = LazyLock::new(|| {
    let mut x: u64 = 7;
    let mut v = Vec::with_capacity(LEN);
    while v.len() < LEN {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        v.extend_from_slice(&x.to_le_bytes());
    }
    v.truncate(LEN);
    Arc::new(v)
});

fn data() -> &'static [u8] {
    &DATA[..]
}

#[derive(Clone, Default)]
struct Origin {
    port: u16,
    requests: Arc<AtomicUsize>,
    cancelled: Arc<AtomicUsize>,
    /// Следующие N ответов шлют заголовки и потом ничего.
    stall_next: Arc<AtomicI32>,
    /// Ответы сочатся по ~2 МБ/с (мобильная сеть медленнее фильма).
    slow: Arc<AtomicBool>,
    ranges: Arc<Mutex<Vec<String>>>,
}

impl Origin {
    fn start() -> Origin {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let o = Origin { port: l.local_addr().unwrap().port(), ..Default::default() };
        let oc = o.clone();
        std::thread::spawn(move || {
            for s in l.incoming().flatten() {
                let o = oc.clone();
                std::thread::spawn(move || o.handle(s));
            }
        });
        o
    }

    fn requests(&self) -> usize {
        self.requests.load(SeqCst)
    }

    fn ranges(&self) -> Vec<String> {
        lock(&self.ranges).clone()
    }

    fn handle(&self, s: TcpStream) {
        let mut r = BufReader::new(s.try_clone().unwrap());
        let mut w = s;
        loop {
            let Ok(Some(line)) = read_line(&mut r) else { return };
            let mut range: Option<String> = None;
            loop {
                let Ok(Some(h)) = read_line(&mut r) else { return };
                if h.is_empty() {
                    break;
                }
                if h.to_ascii_lowercase().starts_with("range:") {
                    range = Some(h[6..].trim().to_string());
                }
            }
            let target = line.split(' ').nth(1).unwrap_or("").to_string();
            self.requests.fetch_add(1, SeqCst);
            let d = data();
            let parse = |r: &str| {
                let r = r.strip_prefix("bytes=").unwrap_or(r);
                let (a, z) = r.split_once('-').unwrap_or((r, ""));
                let a: usize = a.parse().unwrap_or(0);
                let z: usize = if z.is_empty() { d.len() - 1 } else { z.parse::<usize>().unwrap().min(d.len() - 1) };
                (a, z)
            };
            let ok = if target.starts_with("/film.mkv") {
                lock(&self.ranges).push(range.clone().unwrap_or_default());
                let (a, z) = range.as_deref().map(parse).unwrap_or((0, d.len() - 1));
                if a >= d.len() {
                    let h = format!(
                        "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{}\r\nContent-Length: 0\r\n\r\n",
                        d.len()
                    );
                    w.write_all(h.as_bytes()).is_ok()
                } else {
                    let mut h = format!(
                        "HTTP/1.1 {}\r\nContent-Type: video/x-matroska\r\nContent-Length: {}\r\n",
                        if range.is_some() { "206 Partial Content" } else { "200 OK" },
                        z - a + 1
                    );
                    if range.is_some() {
                        h.push_str(&format!("Content-Range: bytes {a}-{z}/{}\r\n", d.len()));
                    }
                    h.push_str("\r\n");
                    if w.write_all(h.as_bytes()).is_err() {
                        return;
                    }
                    if self.stall_next.fetch_sub(1, SeqCst) > 0 {
                        std::thread::sleep(Duration::from_secs(6)); // повисшее соединение
                        return;
                    }
                    if self.slow.load(SeqCst) {
                        let mut p = a;
                        while p <= z {
                            let n = 65_536.min(z - p + 1);
                            if w.write_all(&d[p..p + n]).is_err() {
                                return;
                            }
                            p += n;
                            std::thread::sleep(Duration::from_millis(32));
                        }
                        true
                    } else {
                        w.write_all(&d[a..=z]).is_ok()
                    }
                }
            } else if target.starts_with("/signed.mkv") {
                // зеркало через 12 часов: старая подпись получает 401, новая — байты
                if !target.contains("sig=fresh") {
                    w.write_all(b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n").is_ok()
                } else {
                    let (a, z) = parse(range.as_deref().unwrap_or("bytes=0-"));
                    let h = format!(
                        "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes {a}-{z}/{}\r\nContent-Length: {}\r\n\r\n",
                        d.len(),
                        z - a + 1
                    );
                    w.write_all(h.as_bytes()).is_ok() && w.write_all(&d[a..=z]).is_ok()
                }
            } else {
                w.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n").is_ok()
            };
            if !ok {
                return;
            }
        }
    }
}

/// Адреса `https://origin.test/…` идут на тестовый источник по обычному HTTP.
struct TestUp {
    port: u16,
    inner: Arc<HttpUpstream>,
    cancelled: Arc<AtomicUsize>,
}

impl Upstream for TestUp {
    fn open(&self, url: &str, from: i64, to: i64, cancel: &Arc<Cancel>) -> io::Result<UpstreamResponse> {
        let path = url.split_once("origin.test/").map(|x| x.1).unwrap_or("");
        let c = self.cancelled.clone();
        cancel.attach(move || {
            c.fetch_add(1, SeqCst);
        });
        self.inner.open(&format!("http://127.0.0.1:{}/{path}", self.port), from, to, cancel)
    }
}

struct Fx {
    o: Origin,
    url: String,
    _serial: MutexGuard<'static, ()>,
}

impl Fx {
    fn new() -> Fx {
        let serial = lock(&SERIAL);
        let o = Origin::start();
        let inner = HttpUpstream::new(Arc::new(PlainConnector));
        let reset = inner.clone();
        set_reset_connections(move || reset.reset());
        let d = std::env::temp_dir().join(format!("bp-test-{}", std::process::id()));
        init(&d, Arc::new(TestUp { port: o.port, inner, cancelled: o.cancelled.clone() }));
        set_cap_bytes(16 << 20); // меньше файла: кольцо проходит круг
        Fx { o, url: "https://origin.test/film.mkv?sig=1".into(), _serial: serial }
    }
}

impl Drop for Fx {
    // упавший тест «без сети» не должен оставить остальных без сети (они ничего не скачают)
    fn drop(&mut self) {
        self.o.slow.store(false, SeqCst);
        drop_all();
        RECENT_MS.store(10_000, SeqCst);
        set_stall_ms(8_000, 6_000);
        set_refresh_url(|_| None);
        if !is_network_online() {
            network_changed();
        }
    }
}

/// Запрос к локальному прокси; `pause` — сон после каждых 64 КБ (плеер читает с такой скоростью).
fn get_with(local: &str, range: Option<&str>, pause: Duration) -> (u16, Vec<u8>) {
    let t = http1::parse_url(local).unwrap();
    let mut s = TcpStream::connect((t.host.as_str(), t.port)).unwrap();
    let mut req = format!("GET {} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n", t.path);
    if let Some(r) = range {
        req.push_str(&format!("Range: {r}\r\n"));
    }
    req.push_str("\r\n");
    s.write_all(req.as_bytes()).unwrap();
    let mut all = Vec::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        match s.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => all.extend_from_slice(&buf[..n]),
        }
        if !pause.is_zero() {
            std::thread::sleep(pause);
        }
    }
    let split = all.windows(4).position(|w| w == b"\r\n\r\n").expect("no answer headers");
    let head = String::from_utf8_lossy(&all[..split]).into_owned();
    let code: u16 = head.split_whitespace().nth(1).unwrap().parse().unwrap();
    let body = if code < 400 { all[split + 4..].to_vec() } else { Vec::new() };
    (code, body)
}

fn get(local: &str, range: &str) -> (u16, Vec<u8>) {
    get_with(local, Some(range), Duration::ZERO)
}

fn sleep(ms: u64) {
    std::thread::sleep(Duration::from_millis(ms));
}

/// Соединение, которое ждёт и ничего не читает (плеер держит открытым).
fn open_raw(local: &str, range: &str) -> TcpStream {
    let t = http1::parse_url(local).unwrap();
    let mut s = TcpStream::connect((t.host.as_str(), t.port)).unwrap();
    s.write_all(format!("GET {} HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: {range}\r\n\r\n", t.path).as_bytes()).unwrap();
    s
}

fn assert_bytes(got: &[u8], want: &[u8], what: &str) {
    assert_eq!(got.len(), want.len(), "{what}: length");
    assert!(got == want, "{what}: bytes differ");
}

#[test]
fn serves_exact_bytes() {
    let fx = Fx::new();
    let local = wrap(&fx.url);
    assert!(local.starts_with("http://127.0.0.1:"), "{local}");
    let (code, all) = get_with(&local, None, Duration::ZERO);
    assert_eq!(code, 200);
    assert_bytes(&all, data(), "whole file");
    for (a, z) in [(0, 99), (1000, 1999), (LEN - 100, LEN - 1), (5_000_000, 5_100_000)] {
        let (c, b) = get(&local, &format!("bytes={a}-{z}"));
        assert_eq!(c, 206);
        assert_bytes(&b, &data()[a..=z], &format!("range {a}-{z}"));
    }
    assert_eq!(get(&local, &format!("bytes={}-", LEN + 10)).0, 416);
}

#[test]
fn throttled_playback_comes_in_bursts() {
    let fx = Fx::new();
    let local = wrap(&fx.url);
    let before = fx.o.requests();
    let (_, all) = get_with(&local, Some("bytes=0-"), Duration::from_millis(4)); // ~16 МБ/с
    assert_bytes(&all, data(), "whole file");
    let used = fx.o.requests() - before;
    assert!((1..=12).contains(&used), "upstream requests: {used}"); // рывки, а не запрос на кусок
                                                                    // Играющий поток просит ровно недостающее (bytes=a-b): ответ кончается сам, соединение переиспользуется.
    let ranges = fx.o.ranges();
    let bounded = |r: &String| {
        r.strip_prefix("bytes=").and_then(|x| x.split_once('-')).is_some_and(|(a, z)| {
            !a.is_empty() && !z.is_empty() && (a.to_owned() + z).chars().all(|c| c.is_ascii_digit())
        })
    };
    assert!(ranges.iter().any(bounded), "ranges: {ranges:?}");
}

#[test]
fn an_open_but_unread_stream_stops_at_the_probe_reserve() {
    // Внешняя озвучка, которая не играет: mpv прочитал заголовок и держит соединение, ничего не читая.
    let fx = Fx::new();
    fx.o.slow.store(true, SeqCst); // ~2 МБ/с: старый код заполнял кольцо (14 МБ впереди) за эти 7 с
    let local = wrap(&fx.url);
    let before = downloaded();
    let mut s = open_raw(&local, "bytes=0-");
    let mut buf = vec![0u8; 64 * 1024];
    let mut got = 0;
    while got < 256 * 1024 {
        match s.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => got += n,
        }
    }
    sleep(7_000); // соединение открыто, никто не читает
    let fetched = downloaded() - before;
    // проба — 4 МБ после того, что взял сокет
    assert!(fetched < (9 << 20), "fetched {} MB for an unread stream", fetched >> 20);
}

#[test]
fn stalled_answer_is_repeated_on_a_new_connection() {
    let fx = Fx::new();
    set_stall_ms(700, 700);
    fx.o.stall_next.store(1, SeqCst); // первый ответ приходит с заголовками и висит
    let local = wrap(&fx.url);
    let t0 = Instant::now();
    let (code, b) = get(&local, "bytes=0-2999999");
    assert_eq!(code, 206);
    assert_bytes(&b, &data()[..3_000_000], "first 3 MB");
    let took = t0.elapsed().as_millis();
    assert!(took < 5_500, "took {took} ms: the hung answer should be given up after ~1-2 s, not after its 6 s");
    assert!(fx.o.requests() >= 2, "requests: {}", fx.o.requests());
}

#[test]
fn network_change_restarts_requests_in_flight() {
    let fx = Fx::new();
    fx.o.stall_next.store(1, SeqCst);
    let local = wrap(&fx.url);
    let th = std::thread::spawn(move || get(&local, "bytes=0-1999999"));
    sleep(600); // первый ответ сейчас висит
    network_changed(); // Wi-Fi → мобильная: без ожидания тайм-аута
    let t0 = Instant::now();
    while !th.is_finished() && t0.elapsed() < Duration::from_secs(4) {
        sleep(20);
    }
    assert!(th.is_finished(), "the request should be repeated at once");
    let (code, b) = th.join().unwrap();
    assert_eq!(code, 206);
    assert_bytes(&b, &data()[..2_000_000], "first 2 MB");
}

#[test]
fn same_file_keeps_its_reserve_across_signed_addresses_and_short_absence() {
    let _fx = Fx::new();
    let addr = |link: &str, n: i32| format!("https://origin.test/film.mkv?link={link}&exp={n}&sig=s{n}");
    let a = wrap(&addr("L1", 1));
    assert_eq!(a, wrap(&addr("L1", 2))); // тот же файл, новая подпись: один запас
    assert_ne!(a, wrap(&addr("L2", 2))); // другой файл: свой
    let (code, head) = get(&a, "bytes=0-999999");
    assert_eq!(code, 206);
    assert_bytes(&head, &data()[..1_000_000], "head");
    let snap = snapshot();
    assert!(snap.contains("film.mkv") && snap.contains("ahead") && !snap.contains("sig="), "{snap}"); // без подписей
    clear(); // плеер закрыт: на диске ещё хранится
    let again = wrap(&addr("L1", 3));
    assert_eq!(a, again);
    let (c2, mid) = get(&again, "bytes=100000-299999"); // то же после возвращения
    assert_eq!(c2, 206);
    assert_bytes(&mid, &data()[100_000..300_000], "middle");
    drop_all(); // чистый перезапуск: всё забыто
    assert_ne!(a, wrap(&addr("L1", 4)));
}

#[test]
fn bitrate_comes_from_size_and_length() {
    let fx = Fx::new();
    let local = wrap(&fx.url);
    get(&local, "bytes=0-99"); // первый ответ говорит размер файла (40 МиБ)
    set_duration(&fx.url, 40_000); // 40 МиБ за 40 с
    assert!((bitrate_mbit(&fx.url).unwrap() - 8.39).abs() < 0.05);
    set_duration(&fx.url, 20_000); // плеер уточнил длину: среднее следом
    assert!((bitrate_mbit(&fx.url).unwrap() - 16.78).abs() < 0.05);
}

#[test]
fn slow_network_is_known_while_the_first_burst_still_runs() {
    let fx = Fx::new();
    fx.o.slow.store(true, SeqCst);
    let local = wrap(&fx.url);
    get(&local, "bytes=0-99");
    set_duration(&fx.url, 10_000); // 40 МиБ за 10 с = фильм 4 МиБ/с, сеть даёт ~2
                                   // плеер читает поток, как он приходит (один долгий запрос: запас не наполняется)
    let l = local.clone();
    let reader = std::thread::spawn(move || get(&l, "bytes=0-"));
    sleep(7_500);
    assert!(!reader.is_finished(), "the first burst should still be running");
    assert!(slower_than_film(&fx.url), "{}", snapshot());
    let target = catch_up_target_mb(&fx.url);
    assert!((1.0..=16.0 * 0.6 + 0.01).contains(&target), "target {target}");
}

#[test]
fn hold_gathers_a_reserve_the_player_gets_afterwards() {
    let fx = Fx::new();
    let local = wrap(&fx.url);
    get(&local, "bytes=0-99");
    hold(&fx.url, 10_000);
    let l = local.clone();
    let reader = std::thread::spawn(move || get(&l, "bytes=0-5999999"));
    sleep(1_500);
    assert!(!reader.is_finished(), "the player must wait while the reserve is gathered");
    let ahead = ahead_mb(&fx.url).unwrap();
    assert!(ahead >= 2.0, "ahead {ahead} MB: the reserve should grow meanwhile");
    hold(&fx.url, 0); // отпустить
    let (code, b) = reader.join().unwrap();
    assert_eq!(code, 206);
    assert_bytes(&b, &data()[..6_000_000], "held range");
}

#[test]
fn keyframe_search_backwards_fetches_only_the_gaps() {
    let fx = Fx::new();
    let local = wrap(&fx.url);
    let p = 20_000_000usize;
    // перемотка: плеер встаёт на p и читает немного
    assert_bytes(&get(&local, &format!("bytes={p}-{}", p + 2_999_999)).1, &data()[p..p + 3_000_000], "seek");
    sleep(300);
    // поиск ключевого кадра: по 1,5 МБ назад, дальше читает то, что уже на диске
    let mut start = p;
    for _ in 0..4 {
        start -= 1_500_000;
        let (c, b) = get(&local, &format!("bytes={start}-{}", start + 2_999_999));
        assert_eq!(c, 206);
        assert_bytes(&b, &data()[start..start + 3_000_000], &format!("step at {start}"));
    }
    // каждый шаг просил у зеркала только разрыв (ограниченный диапазон), а не мегабайты, что уже есть; первый —
    // до места, где окно перемотки ещё держит данные (его маленькое тестовое кольцо немного ушло вперёд)
    let ranges = fx.o.ranges();
    let s1 = format!("bytes={}-", p - 1_500_000);
    assert!(ranges.iter().any(|r| r.starts_with(&s1) && !r.ends_with('-')), "step 1: ranges {ranges:?}");
    for k in 2..=4 {
        let a = p - k * 1_500_000;
        let want = format!("bytes={a}-{}", a + 1_499_999);
        assert!(ranges.contains(&want), "step {k}: ranges {ranges:?}");
    }
    assert_eq!(fx.o.requests(), 5, "one request per step: {ranges:?}");
}

#[test]
fn read_range_gives_a_piece_and_the_size() {
    let fx = Fx::new();
    let (bytes, total) = read_range(&fx.url, LEN as i64 - 5000, 5000).unwrap();
    assert_eq!(total, LEN as i64);
    assert_bytes(&bytes, &data()[LEN - 5000..], "tail");
}

#[test]
fn a_closed_film_that_left_the_library_is_forgotten() {
    let _fx = Fx::new();
    let addr = |link: &str| format!("https://origin.test/film.mkv?link={}&exp=1&sig=s", url_codec::encode(link));
    let gone = "https://alld.test/f/gone";
    let kept = "https://alld.test/f/kept";
    let a = wrap(&addr(gone));
    let b = wrap(&addr(kept));
    get(&a, "bytes=0-99999");
    get(&b, "bytes=0-99999");
    clear(); // оба закрыты: на диске
    assert!(disk_bytes() > 0);
    assert_eq!(forget_retired(|l| l == kept), 1);
    assert_eq!(b, wrap(&addr(kept))); // тот, что ещё в медиатеке, на месте
    assert_ne!(a, wrap(&addr(gone))); // ушедший начинает с нуля
}

#[test]
fn the_last_closed_film_survives_a_second_clear() {
    let _fx = Fx::new();
    let addr = |n: i32| format!("https://origin.test/film.mkv?link=L1&exp={n}&sig=s{n}");
    let a = wrap(&addr(1));
    get(&a, "bytes=0-99999");
    keep_only(&[]); // плеер закрыт
    sleep(5);
    clear(); // и ещё раз (другой экран)
    assert_eq!(a, wrap(&addr(2))); // всё ещё на диске
}

#[test]
fn opening_another_film_drops_the_closed_one() {
    let _fx = Fx::new();
    let addr = |link: &str| format!("https://origin.test/film.mkv?link={link}&exp=1&sig=s");
    let a = wrap(&addr("L1"));
    get(&a, "bytes=0-99999");
    clear(); // плеер закрыт: на диске
    assert!(disk_bytes() > 0);
    keep_only(&[addr("L2")]); // открыт другой фильм
    assert_eq!(disk_bytes(), 0);
    assert_ne!(a, wrap(&addr("L1"))); // закрытый начинает с нуля
}

#[test]
fn the_film_stays_within_the_reserve_setting() {
    let fx = Fx::new();
    RECENT_MS.store(0, SeqCst); // без льготы после перемотки
    fx.o.slow.store(true, SeqCst); // ~2 МБ/с: первое окно само не дойдёт до перемотки
    let local = wrap(&fx.url);
    get(&local, "bytes=0-7999999"); // просмотр с начала
    get(&local, "bytes=30000000-38999999"); // перемотка далеко вперёд: второе окно, 8 + 9 МБ > 16 МБ
    sleep(500);
    fx.o.slow.store(false, SeqCst);
    let snap = snapshot();
    assert!(!snap.contains("@0MB"), "{snap}"); // место, которое плеер оставил, ушло
    assert!(disk_bytes() <= 16 << 20, "disk {}: {snap}", disk_bytes());
}

#[test]
fn an_expired_signature_is_renewed_instead_of_failing() {
    let _fx = Fx::new();
    let t = mirror_link::now_sec();
    let renewed = Arc::new(AtomicUsize::new(0));
    let rn = renewed.clone();
    set_refresh_url(move |old| {
        rn.fetch_add(1, SeqCst);
        old.contains("link=L9").then(|| format!("https://origin.test/signed.mkv?link=L9&exp={}&sig=fresh", t + 3600))
    });
    let local = wrap(&format!("https://origin.test/signed.mkv?link=L9&exp={}&sig=old", t - 10));
    let (code, b) = get(&local, "bytes=0-999999");
    assert_eq!(code, 206);
    assert_bytes(&b, &data()[..1_000_000], "renewed");
    assert_eq!(renewed.load(SeqCst), 1);
}

#[test]
fn off_when_reserve_is_zero() {
    let _fx = Fx::new();
    set_cap_bytes(0);
    assert_eq!(wrap("https://origin.test/other.mkv"), "https://origin.test/other.mkv");
}

#[test]
fn offline_serves_buffered_range_without_network_and_resumes_on_network_changed() {
    let fx = Fx::new();
    let local = wrap(&fx.url);
    // 1. первые 4 МБ
    let (c1, b1) = get(&local, "bytes=0-3999999");
    assert_eq!(c1, 206);
    assert_bytes(&b1, &data()[..4_000_000], "pre-buffer");
    let before = fx.o.requests();
    // 2. сеть пропала совсем
    network_lost();
    assert!(!is_network_online());
    // 3. кусок из уже скачанного. Не с самого начала: рывок идёт и после первого чтения (до 7/8 кольца), и на быстрой
    // машине кольцо может уже уйти за первые ~1,9 МБ.
    let (c2, b2) = get(&local, "bytes=2500000-3999999");
    assert_eq!(c2, 206);
    assert_bytes(&b2, &data()[2_500_000..4_000_000], "offline read");
    assert_eq!(fx.o.requests(), before, "no upstream requests should be made when reading buffered data offline");
    // 4. сеть вернулась
    network_changed();
    assert!(is_network_online());
    // 5. дальше всего, что рывок мог скачать до обрыва (он встаёт ниже ~19 МБ)
    let (c3, b3) = get(&local, "bytes=30000000-30999999");
    assert_eq!(c3, 206);
    assert_bytes(&b3, &data()[30_000_000..31_000_000], "after network returned");
    assert!(fx.o.requests() > before, "upstream requests should resume after network_changed");
}

#[test]
fn far_seek_cancels_in_flight_upstream_and_starts_new_window() {
    let fx = Fx::new();
    let local = wrap(&fx.url);
    fx.o.slow.store(true, SeqCst);
    let l = local.clone();
    let _reader = std::thread::spawn(move || get(&l, "bytes=0-9999999"));
    sleep(400); // медленный первый запрос пошёл
    let seek = 25_000_000usize;
    let (code, chunk) = get(&local, &format!("bytes={seek}-{}", seek + 999_999));
    assert_eq!(code, 206);
    assert_bytes(&chunk, &data()[seek..seek + 1_000_000], "seek");
    sleep(600);
    assert!(
        fx.o.cancelled.load(SeqCst) > 0 || fx.o.requests() >= 2,
        "far seek should start a new window and cancel or abandon the old request"
    );
}

#[test]
fn a_player_that_left_while_waiting_no_longer_counts_as_reading() {
    let fx = Fx::new();
    let local = wrap(&fx.url);
    get(&local, "bytes=0-99");
    hold(&fx.url, 15_000); // плеер не получает ничего: его соединение ждёт внутри прокси
    {
        let _s = open_raw(&local, "bytes=0-");
        sleep(800);
        assert!(snapshot().contains("readers 1"), "{}", snapshot());
    } // плеер закрыл соединение, пока ждал
    sleep(1_200);
    let snap = snapshot();
    hold(&fx.url, 0);
    assert!(snap.contains("readers 0"), "the gone player still counts: {snap}");
}

#[test]
fn a_jump_just_past_the_reserve_on_a_slow_network_opens_its_own_request() {
    let fx = Fx::new();
    fx.o.slow.store(true, SeqCst); // ~2 МБ/с: прыжок на 1,5 с этой сети и дальше быстрее новым запросом
    let local = wrap(&fx.url);
    let before = downloaded();
    {
        let mut s = open_raw(&local, "bytes=0-");
        let mut buf = vec![0u8; 64 * 1024];
        let t0 = Instant::now();
        while t0.elapsed() < Duration::from_millis(6_500) {
            match s.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(_) => {}
            }
        }
    }
    sleep(800); // никто не читает: окно перестаёт качать
    let end = (downloaded() - before) as usize; // одно окно с 0: где кончаются его данные
    let pos = end + 5_500_000; // в пределах старых 8 МБ «near», но намного дальше 1,5 с этой сети
    let (code, chunk) = get(&local, &format!("bytes={pos}-{}", pos + 99_999));
    assert_eq!(code, 206);
    assert_bytes(&chunk, &data()[pos..pos + 100_000], "jump");
    let ranges = fx.o.ranges();
    assert!(
        ranges.iter().any(|r| r.starts_with(&format!("bytes={pos}-"))),
        "a new request at the jump: ranges {ranges:?}, {}",
        snapshot()
    );
}

#[test]
fn fast_scrubbing_does_not_exceed_max_windows() {
    let fx = Fx::new();
    let local = wrap(&fx.url);
    for pos in [1_000_000usize, 10_000_000, 20_000_000, 28_000_000, 35_000_000] {
        let (code, chunk) = get(&local, &format!("bytes={pos}-{}", pos + 99_999));
        assert_eq!(code, 206);
        assert_bytes(&chunk, &data()[pos..pos + 100_000], &format!("failed at pos {pos}"));
    }
    let snap = snapshot();
    let windows = snap.matches(" ahead ").count();
    assert!(windows <= 3, "active full windows must not exceed MAX_WINDOWS (found {windows} in: {snap})");
}

#[test]
fn ring_buffer_wraparound_allows_re_fetch_of_overwritten_data() {
    let fx = Fx::new();
    let local = wrap(&fx.url);
    // кольцо 16 МБ: 20 МБ подряд сдвигают valid_from за байт 0
    let (c1, b1) = get(&local, "bytes=0-19999999");
    assert_eq!(c1, 206);
    assert_bytes(&b1, &data()[..20_000_000], "20 MB");
    // начало, уже перезаписанное, ещё раз
    let (c2, b2) = get(&local, "bytes=0-99999");
    assert_eq!(c2, 206);
    assert_bytes(&b2, &data()[..100_000], "start again");
}

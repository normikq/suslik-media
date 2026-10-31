//! Маленький HTTP/1.1-клиент для запаса (`BurstProxy`): запросы `Range` к зеркалу с пулом живых соединений.
//!
//! Зачем свой: прокси должен оборвать запрос из другого потока в любой момент (сторож зависаний, смена сети),
//! в том числе пока висит TCP или TLS. Здесь у каждого соединения есть копия его `TcpStream`, и [`Cancel`] закрывает
//! её — чтение в потоке загрузчика сразу кончается ошибкой. Ответ, прочитанный до конца, оставляет соединение в
//! пуле (у Kotlin-версии так делает OkHttp / java.net.http): ограниченные `bytes=a-b` не платят за новый TLS.
//!
//! TLS даёт приложение ([`Connector`]); в тестах — обычный TCP.

use std::collections::HashMap;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// Поток соединения (TCP или TLS поверх него).
pub trait Stream: Read + Write + Send {}
impl<T: Read + Write + Send> Stream for T {}

/// Открывает соединение. `on_tcp` получает TCP-сокет сразу после соединения (до TLS): через него запрос обрывают.
pub trait Connector: Send + Sync {
    fn connect(
        &self,
        host: &str,
        port: u16,
        tls: bool,
        on_tcp: &mut dyn FnMut(&TcpStream),
    ) -> io::Result<Box<dyn Stream>>;
}

/// Соединение TCP с ожиданием не дольше `timeout`; ошибка имени (DNS) — `HostUnreachable`, как «нет сети».
pub fn tcp(host: &str, port: u16, timeout: Duration) -> io::Result<TcpStream> {
    let addrs: Vec<_> = (host, port)
        .to_socket_addrs()
        .map_err(|e| io::Error::new(io::ErrorKind::HostUnreachable, format!("{host}: {e}")))?
        .collect();
    let mut last = io::Error::new(io::ErrorKind::HostUnreachable, format!("{host}: no address"));
    for a in addrs {
        match TcpStream::connect_timeout(&a, timeout) {
            Ok(s) => {
                let _ = s.set_nodelay(true);
                return Ok(s);
            }
            Err(e) => last = e,
        }
    }
    Err(last)
}

/// Только `http://` (тесты и локальные адреса).
pub struct PlainConnector;

impl Connector for PlainConnector {
    fn connect(
        &self,
        host: &str,
        port: u16,
        tls: bool,
        on_tcp: &mut dyn FnMut(&TcpStream),
    ) -> io::Result<Box<dyn Stream>> {
        if tls {
            return Err(io::Error::new(io::ErrorKind::Unsupported, "https without TLS"));
        }
        let s = tcp(host, port, Duration::from_secs(15))?;
        on_tcp(&s);
        Ok(Box::new(s))
    }
}

/// Обрывает запрос, который ещё соединяется или ждёт ответа (повисшее рукопожатие TLS), из другого потока.
/// Действий может быть несколько (сокет соединения, счётчик в тестах); прикреплённое после отмены выполняется сразу.
#[derive(Default)]
pub struct Cancel {
    actions: Mutex<Vec<Arc<dyn Fn() + Send + Sync>>>,
    cancelled: std::sync::atomic::AtomicBool,
}

impl Cancel {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn attach(&self, a: impl Fn() + Send + Sync + 'static) {
        let a: Arc<dyn Fn() + Send + Sync> = Arc::new(a);
        self.actions.lock().unwrap().push(a.clone());
        // отменили до того, как прикрепили (или пока прикрепляли)
        if self.is_cancelled() {
            a();
        }
    }

    pub fn cancel(&self) {
        self.cancelled.store(true, std::sync::atomic::Ordering::SeqCst);
        let all: Vec<_> = self.actions.lock().unwrap().clone();
        for a in all {
            a();
        }
    }

    pub fn is_cancelled(&self) -> bool {
        self.cancelled.load(std::sync::atomic::Ordering::SeqCst)
    }
}

struct Conn {
    stream: BufReader<Box<dyn Stream>>,
    tcp: TcpStream,
}

/// Адрес: схема, хост, порт и путь с запросом.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Target {
    pub tls: bool,
    pub host: String,
    pub port: u16,
    pub path: String,
}

pub fn parse_url(url: &str) -> io::Result<Target> {
    let bad = || io::Error::new(io::ErrorKind::InvalidInput, format!("bad url {url}"));
    let (tls, rest) = if let Some(r) = url.strip_prefix("https://") {
        (true, r)
    } else if let Some(r) = url.strip_prefix("http://") {
        (false, r)
    } else {
        return Err(bad());
    };
    let (authority, path) = match rest.find(['/', '?']) {
        Some(i) if rest.as_bytes()[i] == b'/' => (&rest[..i], rest[i..].to_string()),
        Some(i) => (&rest[..i], format!("/{}", &rest[i..])),
        None => (rest, "/".to_string()),
    };
    let (host, port) = match authority.rsplit_once(':') {
        Some((h, p)) if !h.is_empty() && p.chars().all(|c| c.is_ascii_digit()) => (h, p.parse().map_err(|_| bad())?),
        _ => (authority, if tls { 443 } else { 80 }),
    };
    if host.is_empty() {
        return Err(bad());
    }
    Ok(Target { tls, host: host.to_string(), port, path })
}

pub struct Client {
    connector: Arc<dyn Connector>,
    pool: Arc<Mutex<HashMap<String, Vec<Conn>>>>,
}

/// Ответ: код, заголовки (имена в нижнем регистре) и тело.
pub struct Response {
    pub code: u16,
    pub headers: Vec<(String, String)>,
    pub body: Body,
}

impl Response {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v.as_str())
    }
}

enum Framing {
    Length(u64),
    Chunked { left: u64, done: bool },
    UntilClose,
}

/// Тело ответа. Прочитанное до конца возвращает соединение в пул.
pub struct Body {
    conn: Option<Conn>,
    framing: Framing,
    keep_alive: bool,
    pool: Arc<Mutex<HashMap<String, Vec<Conn>>>>,
    key: String,
}

impl Body {
    fn finished(&self) -> bool {
        match self.framing {
            Framing::Length(n) => n == 0,
            Framing::Chunked { done, .. } => done,
            Framing::UntilClose => false,
        }
    }

    fn release(&mut self) {
        if self.keep_alive && self.finished() {
            if let Some(c) = self.conn.take() {
                let mut p = self.pool.lock().unwrap();
                let list = p.entry(self.key.clone()).or_default();
                if list.len() < 4 {
                    list.push(c);
                }
            }
        }
    }

    fn chunk_size(r: &mut BufReader<Box<dyn Stream>>) -> io::Result<u64> {
        let mut line = String::new();
        if r.read_line(&mut line)? == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "chunk size"));
        }
        let hex = line.trim().split(';').next().unwrap_or("");
        u64::from_str_radix(hex, 16).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "chunk size"))
    }
}

impl Read for Body {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let Some(conn) = self.conn.as_mut() else { return Ok(0) };
        let r = &mut conn.stream;
        let n = match &mut self.framing {
            Framing::Length(left) => {
                if *left == 0 {
                    0
                } else {
                    let want = buf.len().min(*left as usize);
                    let n = r.read(&mut buf[..want])?;
                    if n == 0 {
                        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "answer cut short"));
                    }
                    *left -= n as u64;
                    n
                }
            }
            Framing::Chunked { left, done } => {
                if *done {
                    0
                } else {
                    if *left == 0 {
                        *left = Self::chunk_size(r)?;
                        if *left == 0 {
                            // последний кусок и пустые трейлеры
                            loop {
                                let mut l = String::new();
                                if r.read_line(&mut l)? == 0 || l.trim().is_empty() {
                                    break;
                                }
                            }
                            *done = true;
                        }
                    }
                    if *done {
                        0
                    } else {
                        let want = buf.len().min(*left as usize);
                        let n = r.read(&mut buf[..want])?;
                        if n == 0 {
                            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "answer cut short"));
                        }
                        *left -= n as u64;
                        if *left == 0 {
                            let mut crlf = [0u8; 2];
                            r.read_exact(&mut crlf)?;
                        }
                        n
                    }
                }
            }
            Framing::UntilClose => r.read(buf)?,
        };
        if self.finished() {
            self.release();
        }
        Ok(n)
    }
}

impl Client {
    pub fn new(connector: Arc<dyn Connector>) -> Self {
        Self { connector, pool: Default::default() }
    }

    /// Закрывает соединения пула (они от сети, которой больше нет, или висят).
    pub fn reset(&self) {
        let all: Vec<Conn> = self.pool.lock().unwrap().drain().flat_map(|(_, v)| v).collect();
        for c in all {
            let _ = c.tcp.shutdown(std::net::Shutdown::Both);
        }
    }

    /// GET с заголовками. Соединение из пула, которое сервер уже закрыл, повторяется на новом.
    pub fn get(&self, url: &str, headers: &[(&str, String)], cancel: &Cancel) -> io::Result<Response> {
        let t = parse_url(url)?;
        let key = format!("{}://{}:{}", if t.tls { "https" } else { "http" }, t.host, t.port);
        loop {
            if cancel.is_cancelled() {
                return Err(io::Error::new(io::ErrorKind::Interrupted, "cancelled"));
            }
            let pooled = self.pool.lock().unwrap().get_mut(&key).and_then(Vec::pop);
            let reused = pooled.is_some();
            let conn = match pooled {
                Some(c) => c,
                None => {
                    let mut tcp_copy: Option<TcpStream> = None;
                    let stream = self.connector.connect(&t.host, t.port, t.tls, &mut |s: &TcpStream| {
                        if let Ok(c) = s.try_clone() {
                            if let Ok(c2) = c.try_clone() {
                                cancel.attach(move || {
                                    let _ = c2.shutdown(std::net::Shutdown::Both);
                                });
                            }
                            tcp_copy = Some(c);
                        }
                    })?;
                    let tcp = tcp_copy.ok_or_else(|| io::Error::other("no socket"))?;
                    Conn { stream: BufReader::with_capacity(64 * 1024, stream), tcp }
                }
            };
            if reused {
                if let Ok(c2) = conn.tcp.try_clone() {
                    cancel.attach(move || {
                        let _ = c2.shutdown(std::net::Shutdown::Both);
                    });
                }
            }
            match self.exchange(conn, &t, &key, headers) {
                Ok(r) => return Ok(r),
                // сервер закрыл соединение, пока оно лежало в пуле: ещё раз на новом
                Err(e) if reused && !cancel.is_cancelled() => {
                    let _ = e;
                    continue;
                }
                Err(e) => return Err(e),
            }
        }
    }

    fn exchange(&self, mut conn: Conn, t: &Target, key: &str, headers: &[(&str, String)]) -> io::Result<Response> {
        let host = if (t.tls && t.port == 443) || (!t.tls && t.port == 80) {
            t.host.clone()
        } else {
            format!("{}:{}", t.host, t.port)
        };
        let mut req = format!(
            "GET {} HTTP/1.1\r\nHost: {host}\r\nConnection: keep-alive\r\nAccept-Encoding: identity\r\n",
            t.path
        );
        for (k, v) in headers {
            req.push_str(&format!("{k}: {v}\r\n"));
        }
        req.push_str("\r\n");
        {
            let w = conn.stream.get_mut();
            w.write_all(req.as_bytes())?;
            w.flush()?;
        }
        let mut line = String::new();
        if conn.stream.read_line(&mut line)? == 0 {
            return Err(io::Error::new(io::ErrorKind::ConnectionAborted, "connection closed"));
        }
        let code: u16 = line
            .split_whitespace()
            .nth(1)
            .and_then(|c| c.parse().ok())
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, format!("bad status line {line:?}")))?;
        let mut hs = Vec::new();
        loop {
            let mut h = String::new();
            if conn.stream.read_line(&mut h)? == 0 {
                return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "headers cut short"));
            }
            let h = h.trim_end_matches(['\r', '\n']);
            if h.is_empty() {
                break;
            }
            if let Some((k, v)) = h.split_once(':') {
                hs.push((k.trim().to_ascii_lowercase(), v.trim().to_string()));
            }
        }
        let get = |n: &str| hs.iter().find(|(k, _)| k == n).map(|(_, v)| v.clone());
        let chunked = get("transfer-encoding").is_some_and(|v| v.to_ascii_lowercase().contains("chunked"));
        let length = get("content-length").and_then(|v| v.parse::<u64>().ok());
        let no_body = code == 204 || code == 304 || (100..200).contains(&code);
        let framing = if no_body {
            Framing::Length(0)
        } else if chunked {
            Framing::Chunked { left: 0, done: false }
        } else if let Some(n) = length {
            Framing::Length(n)
        } else {
            Framing::UntilClose
        };
        let keep_alive = !get("connection").is_some_and(|v| v.eq_ignore_ascii_case("close"))
            && !matches!(framing, Framing::UntilClose);
        let mut body = Body { conn: Some(conn), framing, keep_alive, pool: self.pool.clone(), key: key.to_string() };
        if body.finished() {
            body.release();
        }
        Ok(Response { code, headers: hs, body })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn urls() {
        let t = parse_url("https://site.example/api/file/proxy/a.mkv?link=x&sig=1").unwrap();
        assert_eq!(
            t,
            Target {
                tls: true,
                host: "site.example".into(),
                port: 443,
                path: "/api/file/proxy/a.mkv?link=x&sig=1".into()
            }
        );
        let t = parse_url("http://127.0.0.1:8080").unwrap();
        assert_eq!((t.port, t.path.as_str()), (8080, "/"));
        assert!(parse_url("ftp://x").is_err());
    }

    #[test]
    fn keeps_the_connection_for_the_next_request() {
        use std::net::TcpListener;
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        let accepted = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let acc = accepted.clone();
        std::thread::spawn(move || {
            for s in l.incoming().flatten() {
                acc.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                std::thread::spawn(move || {
                    let mut r = BufReader::new(s.try_clone().unwrap());
                    let mut w = s;
                    loop {
                        let mut line = String::new();
                        loop {
                            line.clear();
                            if r.read_line(&mut line).unwrap_or(0) == 0 {
                                return;
                            }
                            if line == "\r\n" {
                                break;
                            }
                        }
                        let _ = w.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello");
                        let _ = w.write_all(b"");
                    }
                });
            }
        });
        let c = Client::new(Arc::new(PlainConnector));
        for _ in 0..3 {
            let mut r = c.get(&format!("http://127.0.0.1:{port}/x"), &[], &Cancel::new()).unwrap();
            let mut s = String::new();
            r.body.read_to_string(&mut s).unwrap();
            assert_eq!((r.code, s.as_str()), (200, "hello"));
        }
        assert_eq!(accepted.load(std::sync::atomic::Ordering::SeqCst), 1);
    }
}

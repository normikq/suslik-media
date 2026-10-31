//! JSON API сайта со своими cookie (сеанс и cookie устройства), которые живут между запусками. Повторяет `Api` из
//! `Api.kt`. Вызовы блокирующие: окно зовёт их из фоновых потоков.
//!
//! Сеанс, токен устройства и бета-код уходят только своему серверу (`site_address::is_own`): аватары идут с CDN
//! Discord, постеры — с чужих сайтов.

use crate::store::Store;
use crate::strings::tr;
use serde_json::Value;
use std::io::Read;
use std::sync::Arc;
use std::time::Duration;
use suslik_core::{auth, url_codec};

#[derive(Debug)]
pub enum ApiError {
    /// Сервер ответил ошибкой: `message` — его `error` или «Ошибка сервера (код)».
    Http {
        code: u16,
        message: String,
        /// Тело ответа: нужно «Определить через ИИ» (429 с `retryAfterSec`), переедет вместе с ним.
        #[allow(dead_code)]
        body: Option<Value>,
    },
    Timeout,
    /// Причина — для журнала отладочной сборки (этап 8).
    Network(#[allow(dead_code)] String),
}

impl ApiError {
    /// Текст для человека (как `Api.describe`).
    pub fn describe(&self) -> String {
        match self {
            ApiError::Http { message, .. } => message.clone(),
            ApiError::Timeout => tr("api.server_ne_otvetil_vovremya", &[]),
            ApiError::Network(_) => tr("api.net_svyazi_s_serverom", &[]),
        }
    }
}

impl std::fmt::Display for ApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.describe())
    }
}

/// Ответ JSON: 304 — не изменился с `etag` (тела нет).
pub struct Fetched {
    pub code: u16,
    pub json: Value,
    pub etag: Option<String>,
}

#[derive(Clone)]
pub struct Api {
    /// `https://host[/путь]` из `SITE_URL` сборки.
    pub base: String,
    cookies: Arc<Store>,
    auth: Arc<Store>,
    settings: Arc<Store>,
    agent: ureq::Agent,
}

impl Api {
    pub fn new(base: String) -> Self {
        let agent = ureq::AgentBuilder::new()
            .timeout_connect(Duration::from_secs(15))
            .timeout(Duration::from_secs(30))
            .redirects(5)
            .build();
        Self { base, cookies: Store::of("cookies"), auth: Store::of("auth"), settings: Store::of("settings"), agent }
    }

    /// Страница сайта, `https://host/путь/media/`.
    pub fn site(&self) -> String {
        format!("{}/media/", self.base)
    }

    /// Вход через браузер (код устройства): токен сервера для этого компьютера.
    pub fn device_token(&self) -> Option<String> {
        self.auth.get("device_token").filter(|t| !t.is_empty())
    }

    pub fn set_device_token(&self, token: Option<&str>) {
        self.auth.put("device_token", token);
    }

    /// Бета-код уходит, только если бета-канал включён («Настройки»; ключи те же, что у Kotlin-версии).
    fn sent_beta_code(&self) -> Option<String> {
        let code = self.settings.get("beta_code").unwrap_or_default();
        let on = self.settings.get("beta_off").as_deref() != Some("1");
        (!code.trim().is_empty() && on).then_some(code)
    }

    fn cookie_header(&self) -> String {
        self.cookies.entries().iter().map(|(k, v)| format!("{k}={v}")).collect::<Vec<_>>().join("; ")
    }

    fn store_cookies(&self, res: &ureq::Response) {
        for c in res.all("set-cookie") {
            let pair = c.split(';').next().unwrap_or("");
            let (name, value) = pair.split_once('=').unwrap_or((pair, ""));
            let (name, value) = (name.trim(), value.trim());
            if name.is_empty() {
                continue;
            }
            let lc = c.to_ascii_lowercase();
            let expired = lc.contains("max-age=0") || lc.contains("expires=thu, 01 jan 1970");
            self.cookies.put(name, if expired || value.is_empty() { None } else { Some(value) });
        }
    }

    fn url(&self, path: &str) -> String {
        if path.starts_with("http") {
            path.to_string()
        } else {
            format!("{}{path}", self.base)
        }
    }

    /// Запрос с заголовками входа — если адрес на нашем сервере.
    pub fn request(&self, method: &str, path: &str) -> ureq::Request {
        let url = self.url(path);
        let mut req = self.agent.request(method, &url);
        if auth::is_own_server(&url, &self.base) {
            let cookie = self.cookie_header();
            if !cookie.is_empty() {
                req = req.set("Cookie", &cookie);
            }
            if let Some(t) = self.device_token() {
                req = req.set(auth::TOKEN_HEADER, &t);
            }
            if let Some(b) = self.sent_beta_code() {
                req = req.set("X-Beta-Code", &b);
            }
        }
        req
    }

    pub fn get(&self, path: &str) -> Result<Value, ApiError> {
        self.fetch(self.request("GET", path), None, None).map(|f| f.json)
    }

    pub fn post(&self, path: &str, body: Option<&Value>) -> Result<Value, ApiError> {
        let text = body.cloned().unwrap_or_else(|| Value::Object(Default::default())).to_string();
        let req = self.request("POST", path).set("Content-Type", "application/json");
        self.fetch(req, None, Some(text.into_bytes())).map(|f| f.json)
    }

    /// Загрузка `.torrent`, как на сайте: тело как есть, имя в заголовке.
    pub fn post_torrent(&self, bytes: Vec<u8>, filename: &str) -> Result<Value, ApiError> {
        let req = self
            .request("POST", "/api/torrents")
            .set("Content-Type", "application/x-bittorrent")
            .set("X-Torrent-Filename", &url_codec::encode(filename).replace('+', "%20"));
        self.fetch(req, None, Some(bytes)).map(|f| f.json)
    }

    /// GET, на который сервер может ответить «не изменилось» (304), если у него тот же `etag`.
    pub fn get_if_changed(&self, path: &str, etag: Option<&str>) -> Result<Fetched, ApiError> {
        self.fetch(self.request("GET", path), etag, None)
    }

    /// Файл; `None`, если на сервере его нет (404, 410). Нет сети или ошибка сервера — `Err`: стоит спросить позже.
    pub fn bytes(&self, path: &str) -> Result<Option<Vec<u8>>, ApiError> {
        match self.request("GET", path).call() {
            Ok(res) => {
                self.store_cookies(&res);
                let mut out = Vec::new();
                res.into_reader().read_to_end(&mut out).map_err(|e| ApiError::Network(e.to_string()))?;
                Ok(Some(out))
            }
            Err(ureq::Error::Status(404 | 410, res)) => {
                self.store_cookies(&res);
                Ok(None)
            }
            Err(ureq::Error::Status(code, res)) => {
                self.store_cookies(&res);
                Err(server_error(code, None))
            }
            Err(ureq::Error::Transport(t)) => Err(transport(t)),
        }
    }

    /// JSON сжат gzip (Java сама его не просит, а список медиатеки без сжатия в разы больше).
    fn fetch(&self, req: ureq::Request, etag: Option<&str>, body: Option<Vec<u8>>) -> Result<Fetched, ApiError> {
        let mut req = req.set("Accept-Encoding", "gzip");
        if let Some(e) = etag {
            req = req.set("If-None-Match", e);
        }
        let result = match body {
            Some(b) => req.send_bytes(&b),
            None => req.call(),
        };
        let res = match result {
            Ok(r) => r,
            Err(ureq::Error::Status(_, r)) => r,
            Err(ureq::Error::Transport(t)) => return Err(transport(t)),
        };
        self.store_cookies(&res);
        let code = res.status();
        let new_etag = res.header("etag").map(String::from);
        let gz = res.header("content-encoding").is_some_and(|v| v.eq_ignore_ascii_case("gzip"));
        let mut raw = Vec::new();
        res.into_reader().read_to_end(&mut raw).map_err(|e| ApiError::Network(e.to_string()))?;
        let bytes = if gz {
            let mut out = Vec::new();
            flate2::read::GzDecoder::new(&raw[..])
                .read_to_end(&mut out)
                .map_err(|e| ApiError::Network(e.to_string()))?;
            out
        } else {
            raw
        };
        if code == 304 {
            return Ok(Fetched { code, json: Value::Object(Default::default()), etag: etag.map(String::from) });
        }
        let json: Value = serde_json::from_slice(&bytes)
            .ok()
            .filter(Value::is_object)
            .unwrap_or_else(|| Value::Object(Default::default()));
        if !(200..300).contains(&code) {
            return Err(server_error(code, Some(json)));
        }
        Ok(Fetched { code, json, etag: new_etag })
    }
}

fn server_error(code: u16, body: Option<Value>) -> ApiError {
    let msg = body.as_ref().and_then(|b| b.get("error")).and_then(Value::as_str).filter(|m| !m.trim().is_empty());
    let message =
        msg.map(String::from).unwrap_or_else(|| tr("api.oshibka_servera_code", &[("code", &code.to_string())]));
    ApiError::Http { code, message, body }
}

fn transport(t: ureq::Transport) -> ApiError {
    let timed_out = std::error::Error::source(&t)
        .and_then(|s| s.downcast_ref::<std::io::Error>())
        .is_some_and(|e| matches!(e.kind(), std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock));
    if timed_out || t.to_string().contains("timed out") {
        ApiError::Timeout
    } else {
        ApiError::Network(t.to_string())
    }
}

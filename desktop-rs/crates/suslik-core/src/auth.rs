//! Вход (общий пароль, Discord, код устройства) и «Мои фильмы»: протокол сервера (`website/src/routes/auth.js`,
//! `/api/shelf`). Повторяет `core/auth/Auth.kt`. Без сети: приложение само шлёт запросы и отдаёт сюда ответы.

use crate::json::{opt, opt_bool, opt_object, opt_str};
use serde_json::{json, Value};

pub const SHARED_ID: &str = "shared";

/// Пользователь сервера. Вход по общему паролю — `is_shared` (id "shared").
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UserProfile {
    pub id: String,
    pub name: String,
    pub role: String,
    pub avatar_url: Option<String>,
}

impl UserProfile {
    pub fn is_admin(&self) -> bool {
        self.role == "admin"
    }
    pub fn is_shared(&self) -> bool {
        self.id == SHARED_ID
    }

    /// `user` из `/api/session` или опроса кода устройства.
    pub fn parse(u: Option<&Value>) -> Option<Self> {
        let u = u.filter(|u| u.is_object())?;
        let s = |k: &str| if opt(u, k).is_some() { opt_str(u, k) } else { String::new() };
        let name = s("name");
        let avatar = s("avatar");
        Some(Self {
            id: s("id"),
            name: if name.trim().is_empty() { s("username") } else { name },
            role: s("role"),
            avatar_url: (!avatar.trim().is_empty()).then_some(avatar),
        })
    }
}

/// Ответ `/api/session`. `discord_client_id` — приложение Discord нашего сервера (активность «Смотрит»).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionInfo {
    pub authenticated: bool,
    pub user: Option<UserProfile>,
    pub discord_client_id: Option<String>,
}

pub const SESSION_PATH: &str = "/api/session";

impl SessionInfo {
    pub fn parse(res: &Value) -> Self {
        let authenticated = opt_bool(res, "authenticated", false);
        let id = opt_str(res, "discord_client_id");
        Self {
            authenticated,
            user: if authenticated { UserProfile::parse(opt_object(res, "user")) } else { None },
            discord_client_id: (!id.trim().is_empty()).then_some(id),
        }
    }
}

/// Код устройства: `code` — для опроса (секрет устройства), `user_code` — человеку на экран, `url` — где подтвердить.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DevicePairing {
    pub code: String,
    pub user_code: String,
    pub url: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DevicePoll {
    /// Подтверждено: `token` шлём заголовком `X-Device-Token` с каждым запросом.
    Authorized {
        token: Option<String>,
        user: Option<UserProfile>,
    },
    Expired,
    Pending,
}

pub const DEVICE_START: &str = "/api/auth/device";
pub const DEVICE_POLL: &str = "/api/auth/device/poll";
pub const TOKEN_HEADER: &str = "X-Device-Token";

/// `name` — как устройство видно на сайте («Suslik Media Windows»); `site_url` — адрес сайта.
pub fn start_body(name: &str, site_url: &str) -> Value {
    json!({"name": name, "site_url": site_url.trim_end_matches('/')})
}

/// Ответ `DEVICE_START`. `site` — адрес сайта со `/` в конце: запасная ссылка для старого сервера.
/// `None`, если в ответе нет `code` или `user_code` (Kotlin бросает исключение).
pub fn parse_pairing(res: &Value, site: &str) -> Option<DevicePairing> {
    let code = res.get("code")?.as_str()?.to_string();
    let user_code = res.get("user_code")?.as_str()?.to_string();
    let v = opt_str(res, "verification_url");
    let url = if v.contains("code=") { v } else { format!("{site}link?code={user_code}") };
    Some(DevicePairing { code, user_code, url })
}

pub fn poll_body(p: &DevicePairing) -> Value {
    json!({ "code": p.code })
}

/// Ответ `DEVICE_POLL` с кодом 2xx.
pub fn parse_poll(res: &Value) -> DevicePoll {
    match opt_str(res, "status").as_str() {
        "authorized" => {
            let t = opt_str(res, "device_token");
            DevicePoll::Authorized {
                token: (!t.trim().is_empty()).then_some(t),
                user: UserProfile::parse(opt_object(res, "user")),
            }
        }
        "expired" => DevicePoll::Expired,
        _ => DevicePoll::Pending,
    }
}

/// Ошибка HTTP при опросе: 410 / 404 — код истёк (10 мин), иначе пробуем ещё.
pub fn poll_failed(http_code: u16) -> DevicePoll {
    if http_code == 410 || http_code == 404 {
        DevicePoll::Expired
    } else {
        DevicePoll::Pending
    }
}

/// Сессию, токен устройства и бета-код шлём только своему серверу.
pub fn is_own_server(url: &str, base: &str) -> bool {
    crate::site_address::is_own(url, base)
}

/// «Мои фильмы» (`services/shelf.js`): флаг `mine` у раздачи.
pub const SHELF_PATH: &str = "/api/shelf";

pub fn shelf_body(magnet_id: i64, mine: bool) -> Value {
    json!({"id": magnet_id, "mine": mine})
}

#[cfg(test)]
mod tests {
    use super::*;

    fn j(s: &str) -> Value {
        serde_json::from_str(s).unwrap()
    }

    #[test]
    fn parses_session() {
        let s = SessionInfo::parse(&j(r#"{"authenticated":true,"discord_client_id":"123",
             "user":{"id":"9","name":null,"username":"anya","avatar":"https://cdn/a.png","role":"admin"}}"#));
        assert!(s.authenticated);
        assert_eq!(s.discord_client_id.as_deref(), Some("123"));
        let u = s.user.unwrap();
        assert_eq!(u.name, "anya");
        assert!(u.is_admin() && !u.is_shared());
        let shared = SessionInfo::parse(&j(
            r#"{"authenticated":true,"discord_client_id":"","user":{"id":"shared","role":"admin"}}"#,
        ));
        assert!(shared.user.unwrap().is_shared());
        assert_eq!(shared.discord_client_id, None);
        let out = SessionInfo::parse(&j(r#"{"authenticated":false,"user":{"id":"9"}}"#));
        assert_eq!(out.user, None);
        assert_eq!(UserProfile::parse(None), None);
    }

    #[test]
    fn device_flow() {
        assert_eq!(start_body("Suslik Media Android", "https://site/media/")["site_url"], "https://site/media");
        let p = parse_pairing(
            &j(r#"{"code":"secret","user_code":"ABC123","verification_url":"https://s/link?code=ABC123"}"#),
            "https://x/",
        )
        .unwrap();
        assert_eq!(p.url, "https://s/link?code=ABC123");
        let old = parse_pairing(&j(r#"{"code":"secret","user_code":"ABC123"}"#), "https://x/media/").unwrap();
        assert_eq!(old.url, "https://x/media/link?code=ABC123");
        assert_eq!(poll_body(&old)["code"], "secret");
        assert_eq!(parse_pairing(&j(r#"{"user_code":"A"}"#), "https://x/"), None);

        match parse_poll(&j(r#"{"status":"authorized","device_token":"t","user":{"id":"9","name":"A","role":"user"}}"#))
        {
            DevicePoll::Authorized { token, user } => {
                assert_eq!(token.as_deref(), Some("t"));
                assert_eq!(user.unwrap().name, "A");
            }
            other => panic!("{other:?}"),
        }
        assert_eq!(parse_poll(&j(r#"{"status":"pending"}"#)), DevicePoll::Pending);
        assert_eq!(parse_poll(&j(r#"{"status":"expired"}"#)), DevicePoll::Expired);
        assert_eq!(poll_failed(410), DevicePoll::Expired);
        assert_eq!(poll_failed(404), DevicePoll::Expired);
        assert_eq!(poll_failed(502), DevicePoll::Pending);
    }

    #[test]
    fn credentials_only_to_own_server() {
        let base = "https://site.example";
        assert!(is_own_server(base, base));
        assert!(is_own_server(&format!("{base}/api/session"), base));
        assert!(!is_own_server("https://site.example.evil.com/x", base));
        assert!(!is_own_server("https://cdn.discordapp.com/avatars/1.png", base));
    }

    #[test]
    fn shelf() {
        let b = shelf_body(42, true);
        assert_eq!(b["id"], 42);
        assert_eq!(b["mine"], true);
    }
}

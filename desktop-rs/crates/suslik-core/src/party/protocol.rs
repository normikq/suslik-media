//! Данные комнаты и протокол сервера (`website/src/routes/rooms.js`, `services/rooms.js`). Повторяет `PartyProtocol.kt`.

use crate::json::{opt_array, opt_bool, opt_f64, opt_i64, opt_object, opt_str};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

/// Участник. `key` — отпечаток его client_id (сам client_id — пропуск устройства, другим не показывается).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PartyMember {
    pub key: String,
    pub user_id: String,
    pub name: String,
    pub buffering: bool,
    pub avatar: Option<String>,
}

impl PartyMember {
    pub fn new(key: &str, user_id: &str, name: &str) -> Self {
        Self { key: key.into(), user_id: user_id.into(), name: name.into(), buffering: false, avatar: None }
    }
}

/// Фильм комнаты: ключ просмотра `magnetId|путь` (тот же, что у прогресса в медиатеке).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PartyFileRef {
    pub key: String,
    pub title: String,
}

impl PartyFileRef {
    pub fn new(key: &str, title: &str) -> Self {
        Self { key: key.into(), title: title.into() }
    }
    pub fn ad_id(&self) -> i64 {
        self.key.split('|').next().and_then(|s| s.parse().ok()).unwrap_or(0)
    }
}

/// Где комната: `pos` (с) в момент `anchor_server_ms` по часам сервера, скорость `rate`.
/// `waiting`: кто-то грузится — время комнаты стоит, пока все не готовы (`playing` остаётся намерением).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PartyState {
    pub playing: bool,
    pub pos: f64,
    pub rate: f64,
    pub anchor_server_ms: i64,
    pub seq: i64,
    pub waiting: bool,
}

impl PartyState {
    pub const IDLE: PartyState =
        PartyState { playing: false, pos: 0.0, rate: 1.0, anchor_server_ms: 0, seq: 0, waiting: false };

    pub fn new(playing: bool, pos: f64, rate: f64, anchor_server_ms: i64, seq: i64) -> Self {
        Self { playing, pos, rate, anchor_server_ms, seq, waiting: false }
    }

    /// Где комната сейчас, мс, при часах сервера `server_now_ms` ([`super::PartyClock::server_now`]).
    pub fn target_ms(&self, server_now_ms: i64) -> i64 {
        let base = self.pos * 1000.0;
        if !self.playing || self.waiting {
            return base as i64;
        }
        let elapsed = (server_now_ms - self.anchor_server_ms).max(0);
        (base + elapsed as f64 * self.rate) as i64
    }
}

impl Default for PartyState {
    fn default() -> Self {
        Self::IDLE
    }
}

pub const SHARED_USER: &str = "shared";
/// `pending_file_key` закрытого фильма (у настоящего фильма ключ не пустой).
pub const NO_FILE: &str = "";

/// Снимок комнаты. Меняется только через [`PartyRoom::merge`] и [`PartyRoom::reduce`]: состояние плеера —
/// только более новое по `seq`, участники — только если пришёл их список.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct PartyRoom {
    pub id: Option<String>,
    pub host_id: Option<String>,
    /// Устройство создателя: при общем пароле у всех id "shared", ведущего отличает устройство.
    pub host_key: Option<String>,
    pub host_name: String,
    pub host_only: bool,
    pub file: Option<PartyFileRef>,
    pub state: PartyState,
    pub members: Vec<PartyMember>,
    /// Ведущий поставил этот фильм, сервер ещё не подтвердил: ответы на прежние действия везут старый фильм —
    /// их фильм и состояние не берём (журнал 20.10, 20:59:30).
    pub pending_file_key: Option<String>,
}

impl PartyRoom {
    /// Я ведущий: мой пользователь создал комнату (при общем пароле — именно это устройство).
    pub fn is_host(&self, my_user_id: Option<&str>, my_key: &str) -> bool {
        my_user_id.is_some()
            && my_user_id == self.host_id.as_deref()
            && (self.host_id.as_deref() != Some(SHARED_USER) || self.host_key.as_deref().is_none_or(|k| k == my_key))
    }

    /// Этот участник — создатель комнаты (при общем пароле — только создавшее устройство).
    pub fn is_creator(&self, m: &PartyMember) -> bool {
        Some(m.user_id.as_str()) == self.host_id.as_deref()
            && (self.host_id.as_deref() != Some(SHARED_USER) || self.host_key.as_deref().is_none_or(|k| k == m.key))
    }

    /// Грузится кто-то, кроме меня: комната ждёт его.
    pub fn others_buffering(&self, my_key: &str) -> bool {
        self.members.iter().any(|m| m.buffering && m.key != my_key)
    }

    /// Ведущий ставит фильм (до подтверждения сервера держим его).
    pub fn put_file(&self, f: PartyFileRef) -> Self {
        Self { pending_file_key: Some(f.key.clone()), file: Some(f), ..self.clone() }
    }

    /// Сервер не принял смену фильма: снова верим его ответам.
    pub fn file_rejected(&self) -> Self {
        Self { pending_file_key: None, ..self.clone() }
    }

    /// Ведущий закрыл фильм: у комнаты фильма нет (ждём подтверждения, как у `put_file`).
    pub fn clear_file(&self) -> Self {
        Self { file: None, pending_file_key: Some(NO_FILE.into()), ..self.clone() }
    }

    /// Ответ сервера с комнатой (создание, вход, действие, `state_changed`).
    pub fn merge(&self, u: &RoomUpdate) -> Self {
        let mut r = self.clone();
        r.id = u.id.clone().or(r.id);
        r.host_id = u.host_id.clone();
        r.host_key = u.host_key.clone();
        r.host_name = u.host_name.clone();
        r.host_only = u.host_only;
        if let Some(m) = &u.members {
            r.members = m.clone();
        }
        let incoming = u.file.as_ref().map(|f| f.key.as_str()).unwrap_or(NO_FILE);
        if self.pending_file_key.as_deref().is_some_and(|p| p != incoming) {
            // Старый ответ: участники и ведущий — свежие, фильм и время — ещё прежние.
            return r;
        }
        r.file = u.file.clone();
        if let Some(s) = u.state.filter(|s| s.seq >= self.state.seq) {
            r.state = s;
        }
        r.pending_file_key = None;
        r
    }

    /// Событие потока. `Init` с участником приложение запоминает само.
    pub fn reduce(&self, e: &PartyEvent) -> Self {
        match e {
            PartyEvent::Init { room, .. } => room.as_ref().map(|r| self.merge(r)).unwrap_or_else(|| self.clone()),
            PartyEvent::StateChanged(r) => self.merge(r),
            PartyEvent::MemberJoined(m) => {
                let mut members: Vec<PartyMember> = self.members.iter().filter(|x| x.key != m.key).cloned().collect();
                members.push(m.clone());
                Self { members, ..self.clone() }
            }
            PartyEvent::MemberLeft(key) => {
                Self { members: self.members.iter().filter(|x| &x.key != key).cloned().collect(), ..self.clone() }
            }
        }
    }
}

/// Комната из JSON сервера (`getRoomPublicData`). Поля, которых нет, — `None`: `merge` их не трогает.
#[derive(Debug, Clone, PartialEq)]
pub struct RoomUpdate {
    pub id: Option<String>,
    pub host_id: Option<String>,
    pub host_key: Option<String>,
    pub host_name: String,
    pub host_only: bool,
    pub file: Option<PartyFileRef>,
    pub state: Option<PartyState>,
    pub members: Option<Vec<PartyMember>>,
    /// `state.server_time`: ещё одна точка для часов. 0 — нет.
    pub server_time: i64,
}

/// Событие потока комнаты (`/api/rooms/<id>/events`, строки `data: {...}`).
#[derive(Debug, Clone, PartialEq)]
pub enum PartyEvent {
    /// Первое событие: я сам и вся комната.
    Init {
        my_user_id: Option<String>,
        room: Option<RoomUpdate>,
    },
    StateChanged(RoomUpdate),
    MemberJoined(PartyMember),
    MemberLeft(String),
}

pub const ROOMS: &str = "/api/rooms";

pub fn room_path(id: &str) -> String {
    format!("{ROOMS}/{id}")
}
pub fn join_path(id: &str) -> String {
    format!("{ROOMS}/{id}/join")
}
pub fn leave_path(id: &str) -> String {
    format!("{ROOMS}/{id}/leave")
}
pub fn action_path(id: &str) -> String {
    format!("{ROOMS}/{id}/action")
}
/// Поток событий. Без Accept-Encoding: сжатый ответ держит события, пока не заполнится буфер.
pub fn events_path(id: &str, client_id: &str) -> String {
    format!("{ROOMS}/{id}/events?client_id={client_id}")
}

/// Новый client_id устройства: `prefix` — платформа ("pc", "an", "tv").
pub fn new_client_id(prefix: &str) -> String {
    let mut b = [0u8; 6];
    getrandom::getrandom(&mut b).expect("system random");
    format!("{prefix}_{}", hex(&b))
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// Как сервер показывает устройство другим: sha-256 от client_id, 16 hex (`rooms.js clientKey`).
pub fn client_key(client_id: &str) -> String {
    hex(&Sha256::digest(client_id.as_bytes()))[..16].to_string()
}

/// Код комнаты, как его ввёл человек: только буквы и цифры, в нижнем регистре.
pub fn normalize_code(code: &str) -> String {
    code.trim().to_lowercase().chars().filter(|c| c.is_alphanumeric()).collect()
}

pub fn create_body(client_id: &str) -> Value {
    json!({"controls": "all", "client_id": client_id})
}
pub fn join_body(client_id: &str) -> Value {
    json!({ "client_id": client_id })
}
pub fn leave_body(client_id: &str) -> Value {
    json!({ "client_id": client_id })
}
pub fn play(pos_ms: i64) -> Value {
    json!({"type": "play", "pos": pos_ms as f64 / 1000.0})
}
pub fn pause(pos_ms: i64) -> Value {
    json!({"type": "pause", "pos": pos_ms as f64 / 1000.0})
}
pub fn seek(pos_ms: i64) -> Value {
    json!({"type": "seek", "pos": pos_ms as f64 / 1000.0})
}
pub fn buffering(loading: bool) -> Value {
    json!({"type": "buffering", "buffering": loading})
}
pub fn change_file(f: &PartyFileRef) -> Value {
    json!({"type": "change_file", "file": file_json(f)})
}
/// Ведущий закрыл фильм: у гостей плеер тоже закрывается (сервер ставит `room.file = null`).
pub fn close_file() -> Value {
    json!({"type": "change_file", "file": null})
}
pub fn with_client(mut body: Value, client_id: &str) -> Value {
    if let Some(o) = body.as_object_mut() {
        o.insert("client_id".into(), client_id.into());
    }
    body
}
pub fn file_json(f: &PartyFileRef) -> Value {
    json!({"key": f.key, "name": f.title, "ad_id": f.ad_id()})
}

fn non_blank(s: String) -> Option<String> {
    (!s.trim().is_empty()).then_some(s)
}

/// Комната из ответа создания / входа / действия (`{room: {...}}`).
pub fn room_of(res: &Value) -> Option<RoomUpdate> {
    opt_object(res, "room").map(parse_room)
}
/// Мой пользователь из ответа: создание — `room.host_id`, вход — `member.id`.
pub fn host_id_of(res: &Value) -> Option<String> {
    opt_object(res, "room").and_then(|r| non_blank(opt_str(r, "host_id")))
}
pub fn member_id_of(res: &Value) -> Option<String> {
    opt_object(res, "member").and_then(|m| non_blank(opt_str(m, "id")))
}

pub fn parse_room(room: &Value) -> RoomUpdate {
    let s = opt_object(room, "state");
    RoomUpdate {
        id: non_blank(opt_str(room, "id")),
        host_id: non_blank(opt_str(room, "host_id")),
        host_key: non_blank(opt_str(room, "host_key")),
        host_name: opt_str(room, "host_name"),
        host_only: opt_str(room, "controls") == "host",
        file: opt_object(room, "file").and_then(|f| {
            let key = opt_str(f, "key");
            (!key.trim().is_empty()).then(|| PartyFileRef { key, title: opt_str(f, "name") })
        }),
        state: s.map(|s| PartyState {
            playing: opt_bool(s, "playing", false),
            pos: opt_f64(s, "pos", 0.0),
            rate: opt_f64(s, "rate", 1.0),
            anchor_server_ms: opt_i64(s, "anchor_server_ms", 0),
            seq: opt_i64(s, "seq", 0),
            waiting: opt_bool(s, "waiting", false),
        }),
        members: opt_array(room, "members").map(|a| a.iter().filter(|m| m.is_object()).map(parse_member).collect()),
        server_time: s.map(|s| opt_i64(s, "server_time", 0)).unwrap_or(0),
    }
}

pub fn parse_member(m: &Value) -> PartyMember {
    let name = opt_str(m, "name");
    PartyMember {
        key: opt_str(m, "key"),
        user_id: opt_str(m, "id"),
        name: if name.trim().is_empty() { "Гость".into() } else { name },
        buffering: opt_bool(m, "buffering", false),
        avatar: Some(opt_str(m, "avatar")).filter(|a| a.starts_with("https://")),
    }
}

/// Строка потока SSE. Не `data:` или незнакомое событие — `None`.
pub fn parse_event_line(line: &str) -> Option<PartyEvent> {
    line.strip_prefix("data:").and_then(|t| parse_event(t.trim()))
}

pub fn parse_event(text: &str) -> Option<PartyEvent> {
    let msg: Value = serde_json::from_str(text).ok().filter(Value::is_object)?;
    let data = opt_object(&msg, "data");
    match opt_str(&msg, "event").as_str() {
        "init" => Some(PartyEvent::Init {
            my_user_id: opt_object(&msg, "member").and_then(|m| non_blank(opt_str(m, "id"))),
            room: opt_object(&msg, "room").map(parse_room),
        }),
        "state_changed" => data.map(|d| PartyEvent::StateChanged(parse_room(d))),
        "member_joined" => {
            data.and_then(|d| opt_object(d, "member")).map(|m| PartyEvent::MemberJoined(parse_member(m)))
        }
        "member_left" => data.and_then(|d| non_blank(opt_str(d, "key"))).map(PartyEvent::MemberLeft),
        _ => None,
    }
}

#[cfg(test)]
#[path = "protocol_tests.rs"]
mod tests;

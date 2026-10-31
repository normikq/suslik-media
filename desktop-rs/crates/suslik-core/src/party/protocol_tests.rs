//! Перенос `PartyProtocolTest.kt`.
use super::*;

const ROOM: &str = r#"{"id":"ab12cd","host_id":"u1","host_key":"k1","host_name":"Аня","controls":"all",
 "file":{"key":"42|Season 1/e01.mkv","name":"Серия 1","ad_id":42},
 "state":{"playing":true,"pos":12.5,"rate":1,"anchor_server_ms":1000,"seq":7,"waiting":false,"server_time":1500},
 "members":[{"key":"k1","id":"u1","name":"Аня","buffering":false,"avatar":"https://cdn.discordapp.com/a.png"},
            {"key":"k2","id":"u2","name":"","buffering":true,"avatar":"http://evil/a.png"}]}"#;

fn room() -> Value {
    serde_json::from_str(ROOM).unwrap()
}

fn edit(f: impl FnOnce(&mut Value)) -> RoomUpdate {
    let mut v = room();
    f(&mut v);
    parse_room(&v)
}

#[test]
fn parses_room() {
    let r = parse_room(&room());
    assert_eq!(r.id.as_deref(), Some("ab12cd"));
    assert_eq!(r.host_id.as_deref(), Some("u1"));
    assert_eq!(r.host_key.as_deref(), Some("k1"));
    assert!(!r.host_only);
    assert_eq!(r.file, Some(PartyFileRef::new("42|Season 1/e01.mkv", "Серия 1")));
    assert_eq!(r.file.as_ref().unwrap().ad_id(), 42);
    assert_eq!(r.state, Some(PartyState::new(true, 12.5, 1.0, 1000, 7)));
    assert_eq!(r.server_time, 1500);
    let m = r.members.unwrap();
    assert_eq!(m[0].avatar.as_deref(), Some("https://cdn.discordapp.com/a.png"));
    assert_eq!(m[1].name, "Гость");
    assert_eq!(m[1].avatar, None);
    assert!(m[1].buffering);
}

#[test]
fn merge_keeps_newer_state_and_members() {
    let first = PartyRoom::default().merge(&parse_room(&room()));
    let stale = edit(|v| {
        v["state"]["seq"] = 5.into();
        v["state"]["playing"] = false.into();
        v.as_object_mut().unwrap().remove("members");
    });
    let r = first.merge(&stale);
    assert_eq!(r.state.seq, 7);
    assert!(r.state.playing);
    assert_eq!(r.members.len(), 2);
    let no_file = edit(|v| {
        v.as_object_mut().unwrap().remove("file");
    });
    assert_eq!(first.merge(&no_file).file, None);
}

#[test]
fn target_ms_follows_anchor() {
    let s = PartyState::new(true, 10.0, 1.0, 5_000, 1);
    assert_eq!(s.target_ms(8_000), 13_000);
    assert_eq!(s.target_ms(4_000), 10_000);
    assert_eq!(PartyState { waiting: true, ..s }.target_ms(8_000), 10_000);
    assert_eq!(PartyState { playing: false, ..s }.target_ms(8_000), 10_000);
    assert_eq!(PartyState { rate: 1.5, ..s }.target_ms(8_000), 14_500);
}

#[test]
fn host_rules() {
    let a = PartyMember::new("ka", "shared", "A");
    let b = PartyMember::new("kb", "shared", "B");
    let shared = PartyRoom { host_id: Some("shared".into()), host_key: Some("ka".into()), ..Default::default() };
    assert!(shared.is_host(Some("shared"), "ka"));
    assert!(!shared.is_host(Some("shared"), "kb"));
    assert!(shared.is_creator(&a));
    assert!(!shared.is_creator(&b));
    let discord = PartyRoom { host_id: Some("u1".into()), host_key: Some("ka".into()), ..Default::default() };
    assert!(discord.is_host(Some("u1"), "kb"));
    assert!(!discord.is_host(Some("u2"), "ka"));
    assert!(!discord.is_host(None, "ka"));
    let r = PartyRoom { members: vec![PartyMember { buffering: true, ..a }, b], ..Default::default() };
    assert!(r.others_buffering("kb"));
    assert!(!r.others_buffering("ka"));
}

#[test]
fn parses_events_and_reduces() {
    let init = parse_event_line(&format!(r#"data: {{"event":"init","member":{{"id":"u2"}},"room":{ROOM}}}"#)).unwrap();
    let PartyEvent::Init { my_user_id, .. } = &init else { panic!("not init") };
    assert_eq!(my_user_id.as_deref(), Some("u2"));
    let mut r = PartyRoom::default().reduce(&init);
    assert_eq!(r.members.len(), 2);

    r = r.reduce(
        &parse_event(r#"{"event":"member_joined","data":{"member":{"key":"k2","id":"u2","name":"Боря"}}}"#).unwrap(),
    );
    assert_eq!(r.members.len(), 2);
    assert_eq!(r.members.last().unwrap().name, "Боря");

    r = r.reduce(&parse_event(r#"{"event":"member_left","data":{"key":"k1"}}"#).unwrap());
    assert_eq!(r.members.iter().map(|m| m.key.as_str()).collect::<Vec<_>>(), ["k2"]);

    let mut changed = room();
    changed["state"]["seq"] = 8.into();
    changed["state"]["playing"] = false.into();
    r = r.reduce(&parse_event(&format!(r#"{{"event":"state_changed","data":{changed}}}"#)).unwrap());
    assert!(!r.state.playing);
    assert_eq!(r.state.seq, 8);

    assert_eq!(parse_event_line(": ping"), None);
    assert_eq!(parse_event(r#"{"event":"something_new"}"#), None);
    assert_eq!(parse_event("not json"), None);
}

#[test]
fn commands_and_keys() {
    assert_eq!(normalize_code("  AB-12 cd "), "ab12cd");
    assert_eq!(client_key("pc_test"), "9703bd8de4eb8d02");
    let id = new_client_id("an");
    assert!(id.starts_with("an_") && id.len() == 15);
    let p = with_client(play(12_340), "pc_1");
    assert_eq!(p["type"], "play");
    assert!((p["pos"].as_f64().unwrap() - 12.34).abs() < 1e-9);
    assert_eq!(p["client_id"], "pc_1");
    assert_eq!(change_file(&PartyFileRef::new("42|a.mkv", "A"))["file"]["ad_id"], 42);
    assert_eq!(events_path("x", "pc_1"), "/api/rooms/x/events?client_id=pc_1");
}

#[test]
fn pending_file_is_not_overwritten_by_stale_answers() {
    let r0 = PartyRoom::default().merge(&parse_room(&room()));
    let next = PartyFileRef::new("42|Season 1/e02.mkv", "Серия 2");
    let mut r = r0.put_file(next.clone());
    let stale = edit(|v| {
        v["state"]["seq"] = 9.into();
        v["state"]["pos"] = 679.0.into();
        v["members"].as_array_mut().unwrap().push(serde_json::json!({"key":"k3","id":"u3","name":"Вика"}));
    });
    r = r.merge(&stale);
    assert_eq!(r.file.as_ref(), Some(&next));
    assert_eq!(r.state.seq, 7);
    assert_eq!(r.members.len(), 3);
    let confirmed = edit(|v| {
        v["file"] = serde_json::json!({"key": next.key, "name": next.title});
        v["state"]["seq"] = 10.into();
        v["state"]["pos"] = 0.0.into();
        v["state"]["playing"] = false.into();
    });
    r = r.merge(&confirmed);
    assert_eq!(r.pending_file_key, None);
    assert_eq!(r.state.seq, 10);
    assert_eq!(r.file.as_ref(), Some(&next));
    assert_eq!(r0.put_file(next).file_rejected().pending_file_key, None);
}

#[test]
fn closed_file_stays_closed() {
    let r0 = PartyRoom::default().merge(&parse_room(&room()));
    let mut r = r0.clear_file();
    assert_eq!(r.file, None);
    r = r.merge(&parse_room(&room()));
    assert_eq!(r.file, None);
    let confirmed = edit(|v| {
        v["file"] = Value::Null;
        v["state"]["seq"] = 10.into();
        v["state"]["playing"] = false.into();
    });
    r = r.merge(&confirmed);
    assert_eq!(r.pending_file_key, None);
    assert_eq!(r.file, None);
    assert_eq!(r.state.seq, 10);
    let body = close_file();
    assert_eq!(body["type"], "change_file");
    assert!(body["file"].is_null());
}

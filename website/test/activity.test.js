"use strict";

// Discord Activity: комната голосового канала, голосование, ключ Activity, заголовки iframe (docs/DISCORD-ACTIVITY.md)
const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-activity-test-"));
process.env.DISCORD_CLIENT_ID = "123456";
process.env.DISCORD_CLIENT_SECRET = "test-secret";

const test = require("node:test");
const assert = require("node:assert/strict");

const { findOrCreateUser } = require("../src/services/users");
const rooms = require("../src/services/rooms");
const { issueActivityToken, verifyActivityToken, revokeActivityToken } = require("../src/services/activity");

const alice = findOrCreateUser({ id: "1001", username: "alice", global_name: "Алиса", avatar: null }, "allow");
const bob = findOrCreateUser({ id: "1002", username: "bob", global_name: "Боб", avatar: null }, "allow");
const carol = findOrCreateUser({ id: "1003", username: "carol", global_name: "Кэрол", avatar: null }, "allow");

test("model.js у Activity — та же копия, что у ТВ", () => {
  const a = fs.readFileSync(path.join(__dirname, "..", "public", "activity", "model.js"), "utf8");
  const b = fs.readFileSync(path.join(__dirname, "..", "..", "tizen", "js", "model.js"), "utf8");
  assert.equal(a, b, "скопируйте tizen/js/model.js в website/public/activity/model.js");
});

test("комната канала: одна на канал, код из шести цифр, ведущий — первый", () => {
  assert.equal(rooms.getOrCreateChannelRoom("abc", alice), null);
  assert.equal(rooms.getOrCreateChannelRoom("", alice), null);
  const room = rooms.getOrCreateChannelRoom("111111111111111111", alice);
  assert.match(room.id, /^\d{6}$/);
  assert.equal(room.host_id, alice.id);
  const again = rooms.getOrCreateChannelRoom("111111111111111111", bob);
  assert.equal(again, room);
  assert.equal(again.host_id, alice.id);
  const other = rooms.getOrCreateChannelRoom("222222222222222222", bob);
  assert.notEqual(other.id, room.id);
  assert.equal(other.host_id, bob.id);
  const pub = rooms.getRoomPublicData(room);
  assert.equal(pub.channel, true);
  assert.deepEqual(pub.candidates, []);
  assert.equal(pub.poll, null);
  // Обычная комната приложений — без полей Activity
  const plain = rooms.createRoom(alice);
  assert.equal(rooms.getRoomPublicData(plain).channel, undefined);
});

test("у пульта: Activity не занимает место в плеере, ПК того же человека входит", () => {
  const room = rooms.getOrCreateChannelRoom("333333333333333333", alice);
  const leave = rooms.addWatcher(room, alice);
  assert.equal(room.watchers.size, 1);
  assert.equal(room.members.size, 0);
  assert.equal(rooms.findUserActiveRoom(alice, "pc_1", room.id), null);
  const joined = rooms.joinRoom(room.id, alice, "pc_1");
  assert.equal(joined.ok, true);
  rooms.leaveRoom(room.id, "pc_1", true);
  leave();
  leave(); // второй раз ничего не ломает
  assert.equal(room.watchers.size, 0);
  assert.ok(room.empty_since > 0);
});

test("ведущий закрыл Activity: ведущим становится тот, кто остался у пульта", () => {
  const room = rooms.getOrCreateChannelRoom("444444444444444444", alice);
  const leaveA = rooms.addWatcher(room, alice);
  const leaveB = rooms.addWatcher(room, bob);
  leaveA();
  assert.equal(rooms.promoteIfHostGone(room), true);
  assert.equal(room.host_id, bob.id);
  leaveB();
});

test("предложения и голосование: победитель включается в комнате, поровну — кого предложили раньше", () => {
  const room = rooms.getOrCreateChannelRoom("555555555555555555", alice);
  const f1 = { key: "10|Film.One.2020.mkv", name: "Фильм 1" };
  const f2 = { key: "11|Show/S01E01.mkv", name: "Сериал S01E01" };
  assert.equal(rooms.suggestFile(room.id, bob, { key: "bad" }).ok, false);
  assert.equal(rooms.startPoll(room.id, alice).ok, false); // меньше двух
  assert.equal(rooms.suggestFile(room.id, bob, f1).ok, true);
  assert.equal(rooms.suggestFile(room.id, bob, f1).ok, true); // повтор не добавляет
  assert.equal(room.candidates.length, 1);
  assert.equal(rooms.suggestFile(room.id, carol, f2).ok, true);
  // Убрать чужое нельзя, своё и ведущему — можно
  assert.equal(rooms.unsuggestFile(room.id, carol, f1.key).ok, false);
  assert.equal(rooms.startPoll(room.id, bob).ok, false); // не ведущий
  assert.equal(rooms.startPoll(room.id, alice, 60000).ok, true);
  assert.equal(rooms.suggestFile(room.id, carol, { key: "12|x.mkv" }).ok, false); // во время голосования список стоит
  assert.equal(rooms.vote(room.id, bob, "nope").ok, false);
  assert.equal(rooms.vote(room.id, bob, f2.key).ok, true);
  assert.equal(rooms.vote(room.id, carol, f1.key).ok, true);
  const pub = rooms.getRoomPublicData(room);
  assert.equal(pub.poll.tally[f1.key], 1);
  assert.equal(pub.poll.votes[bob.id], f2.key);
  const win = rooms.finishPoll(room);
  assert.equal(win.candidate.key, f1.key); // 1:1 — первый предложенный
  assert.deepEqual(room.file, { key: f1.key, name: "Фильм 1", ad_id: 10 });
  assert.equal(room.poll, null);
  assert.equal(room.candidates.length, 0);
});

test("голосование без голосов фильм не меняет; ведущий может отменить", () => {
  const room = rooms.getOrCreateChannelRoom("666666666666666666", alice);
  rooms.suggestFile(room.id, alice, { key: "20|a.mkv", name: "A" });
  rooms.suggestFile(room.id, alice, { key: "21|b.mkv", name: "B" });
  rooms.startPoll(room.id, alice, 60000);
  assert.equal(rooms.finishPoll(room), null);
  assert.equal(room.file, null);
  assert.equal(room.candidates.length, 2);
  rooms.startPoll(room.id, alice, 60000);
  assert.equal(rooms.cancelPoll(room.id, bob).ok, false);
  assert.equal(rooms.cancelPoll(room.id, alice).ok, true);
  assert.equal(room.poll, null);
});

test("ключ Activity: живёт, отзывается, неизвестный не пускает", () => {
  const { token } = issueActivityToken(alice.id);
  assert.match(token, /^sa_[0-9a-f]{48}$/);
  assert.equal(verifyActivityToken(token).id, alice.id);
  assert.equal(verifyActivityToken("sa_" + "0".repeat(48)), null);
  assert.equal(verifyActivityToken("device-token"), null);
  revokeActivityToken(token);
  assert.equal(verifyActivityToken(token), null);
});

test("сервер: страница Activity встраивается только в Discord, API — по ключу, /join — код для приложения", async () => {
  const { createApp } = require("../src/app");
  const server = createApp().listen(0, "127.0.0.1");
  await new Promise(r => server.once("listening", r));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const page = await fetch(`${base}/?frame_id=f1&instance_id=i1&channel_id=777&platform=desktop`);
    assert.equal(page.status, 200);
    assert.equal(page.headers.get("x-frame-options"), null);
    assert.match(page.headers.get("content-security-policy"), /frame-ancestors 'self' https:\/\/discord\.com https:\/\/\*\.discord\.com https:\/\/\*\.discordsays\.com/);
    assert.match(page.headers.get("content-security-policy"), /script-src 'self'(;|$)/);
    const pageText = await page.text();
    // ?v= — хеш файла, а не постоянная «1»: иначе Discord годами держит старый скрипт (immutable)
    assert.match(pageText, /activity\/activity\.js\?v=[0-9a-f]{10}"/);
    assert.doesNotMatch(pageText, /\?v=1"/);

    const site = await fetch(`${base}/`);
    assert.equal(site.headers.get("x-frame-options"), "DENY");
    assert.match(site.headers.get("content-security-policy") || "", /frame-ancestors 'none'/);

    const cfg = await (await fetch(`${base}/api/activity/config`)).json();
    assert.equal(cfg.client_id, "123456");

    const noKey = await fetch(`${base}/api/activity/room`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
    assert.equal(noKey.status, 401);

    const { token } = issueActivityToken(bob.id);
    const auth = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
    const made = await fetch(`${base}/api/activity/room`, { method: "POST", headers: auth, body: JSON.stringify({ channel_id: "888888888888888888" }) });
    assert.equal(made.status, 200);
    const { room } = await made.json();
    assert.match(room.id, /^\d{6}$/);
    assert.equal(room.host_id, bob.id);

    // Ведущий ставит фильм обычной командой комнаты (как приложения); cookie нет — проверка «с этого сайта» не мешает
    const put = await fetch(`${base}/api/rooms/${room.id}/action`, {
      method: "POST", headers: { ...auth, Origin: "https://123456.discordsays.com", "Sec-Fetch-Site": "cross-site" },
      body: JSON.stringify({ type: "change_file", file: { key: "5|Film.mkv", name: "Фильм", ad_id: 5 } })
    });
    assert.equal(put.status, 200);
    assert.equal((await put.json()).room.file.key, "5|Film.mkv");

    const join = await fetch(`${base}/join/842-190"><script>`);
    assert.equal(join.status, 200);
    const html = await join.text();
    assert.match(html, /suslik:\/\/room\/842190script/);
    assert.doesNotMatch(html, /<script>/);
    assert.match(html, /activity\/join\.js\?v=[0-9a-f]{10}"/);
    assert.match(join.headers.get("content-security-policy"), /frame-ancestors 'none'/);
  } finally {
    server.close();
  }
});

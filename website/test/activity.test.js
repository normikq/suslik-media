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

test("на опросе: GET комнаты держит человека у пульта, без опроса он уходит", () => {
  const room = rooms.getOrCreateChannelRoom("454545454545454545", alice);
  const leaveA = rooms.addWatcher(room, alice);
  leaveA(); // поток оборвался (прокси Discord)…
  assert.equal(room.watchers.size, 0);
  rooms.touchWatcher(room, alice); // …а страница опрашивает
  assert.equal(room.watchers.has(alice.id), true);
  assert.equal(room.empty_since, 0);
  const leaveB = rooms.addWatcher(room, bob);
  leaveB();
  const w = room.watchers.get(alice.id);
  assert.equal(rooms.getRoomPublicData(room).watchers.length, 1);
  assert.deepEqual(Object.keys(rooms.getRoomPublicData(room).watchers[0]).sort(), ["avatar", "id", "name"]); // без таймера
  // Опрос прекратился: срок вышел — ушёл
  w.seen_until = Date.now() - 1;
  const leaveAgain = rooms.addWatcher(room, alice); // и поток вернулся — тот же наблюдатель
  assert.equal(room.watchers.get(alice.id), w);
  leaveAgain();
  assert.equal(room.watchers.size, 0);
  assert.ok(room.empty_since > 0);
  clearTimeout(w.expire);
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

test("«Итог сейчас» — только ведущему; все у пульта проголосовали — итог через 5 с", () => {
  const room = rooms.getOrCreateChannelRoom("121212121212121212", alice);
  const leaveA = rooms.addWatcher(room, alice);
  const leaveB = rooms.addWatcher(room, bob);
  rooms.suggestFile(room.id, alice, { key: "30|a.mkv", name: "A" });
  rooms.suggestFile(room.id, bob, { key: "31|b.mkv", name: "B" });
  rooms.startPoll(room.id, alice, 60000);
  assert.equal(rooms.finishPollNow(room.id, alice).ok, false); // никто не голосовал
  rooms.vote(room.id, bob, "31|b.mkv");
  assert.equal(rooms.getRoomPublicData(room).poll.all_voted, false);
  assert.ok(room.poll.ends_at - Date.now() > 50000);
  rooms.vote(room.id, alice, "31|b.mkv");
  assert.equal(rooms.getRoomPublicData(room).poll.all_voted, true);
  assert.ok(room.poll.ends_at - Date.now() <= 5000);
  assert.equal(rooms.finishPollNow(room.id, bob).ok, false); // не ведущий
  assert.equal(rooms.finishPollNow(room.id, alice).ok, true);
  assert.equal(room.poll, null);
  assert.equal(room.file.key, "31|b.mkv");
  assert.equal(rooms.finishPollNow(room.id, alice).ok, false); // уже закончилось
  leaveA();
  leaveB();
});

test("рулетка: победителя выбирает сервер, у всех одно, потом фильм включается", () => {
  const room = rooms.getOrCreateChannelRoom("131313131313131313", alice);
  assert.equal(rooms.spinRoulette(room.id, alice).ok, false); // меньше двух
  rooms.suggestFile(room.id, alice, { key: "40|a.mkv", name: "A" });
  rooms.suggestFile(room.id, bob, { key: "41|b.mkv", name: "B" });
  assert.equal(rooms.spinRoulette(room.id, bob).ok, false); // не ведущий
  const sent = [];
  const stop = rooms.addListener(room.id, msg => sent.push(JSON.parse(msg)));
  const r = rooms.spinRoulette(room.id, alice, 60000);
  assert.equal(r.ok, true);
  const pub = rooms.getRoomPublicData(room);
  assert.equal(pub.spin.key, r.key);
  assert.ok(pub.spin.ends_at > Date.now());
  assert.equal(pub.spin.ends_at - pub.spin.started_at, 60000); // клиент крутит барабан ровно столько
  // длительность от ведущего: 2–30 с, мусор — по умолчанию
  assert.equal(rooms.spinMs(10), 10000);
  assert.equal(rooms.spinMs("7.5"), 7500);
  assert.equal(rooms.spinMs(1), 2000);
  assert.equal(rooms.spinMs(999), 30000);
  assert.equal(rooms.spinMs(undefined), 4000);
  assert.equal(rooms.spinMs("abc"), 4000);
  // Пока крутится: список и голосование стоят
  assert.equal(rooms.suggestFile(room.id, carol, { key: "42|c.mkv", name: "C" }).ok, false);
  assert.equal(rooms.unsuggestFile(room.id, alice, "40|a.mkv").ok, false);
  assert.equal(rooms.startPoll(room.id, alice).ok, false);
  const win = rooms.finishSpin(room);
  assert.equal(win.key, r.key);
  assert.equal(room.file.key, r.key);
  assert.equal(room.spin, null);
  assert.equal(room.candidates.length, 0);
  const result = sent.find(m => m.event === "poll_result");
  assert.equal(result.data.roulette, true);
  assert.equal(result.data.key, r.key);
  stop();
});

test("рулетку можно отменить: только ведущий, фильм не включается, список остаётся", () => {
  const room = rooms.getOrCreateChannelRoom("141414141414141414", alice);
  rooms.suggestFile(room.id, alice, { key: "50|a.mkv", name: "A" });
  rooms.suggestFile(room.id, bob, { key: "51|b.mkv", name: "B" });
  const sent = [];
  const stop = rooms.addListener(room.id, msg => sent.push(JSON.parse(msg)));
  assert.equal(rooms.spinRoulette(room.id, alice, 60000).ok, true);
  assert.equal(rooms.cancelSpin(room.id, bob).ok, false); // не ведущий
  assert.ok(room.spin);
  assert.equal(rooms.cancelSpin(room.id, alice).ok, true);
  assert.equal(room.spin, null);
  assert.equal(room.file, null);
  assert.equal(room.candidates.length, 2);
  assert.ok(sent.some(m => m.event === "spin_cancelled"));
  assert.equal(rooms.finishSpin(room), null); // таймер уже ни на что не влияет
  assert.equal(rooms.cancelSpin(room.id, alice).ok, true); // второй раз — тоже без ошибки
  stop();
});

test("приглашение в чат: код для ТВ, кнопка только с https, без упоминаний", () => {
  const { inviteMessage } = require("../src/services/discord");
  const m = inviteMessage({ code: "381667", film: "Дюна **2**", by: "Глеб", link: "https://example.org/join/381667" });
  assert.match(m.embeds[0].title, /381-667/);
  assert.match(m.embeds[0].description, /Дюна 2/);
  assert.equal(m.components[0].components[0].url, "https://example.org/join/381667");
  assert.deepEqual(m.allowed_mentions, { parse: [] });
  const plain = inviteMessage({ code: "381667", link: "http://localhost/join/381667" });
  assert.deepEqual(plain.components, []);
  assert.equal(plain.embeds[0].url, undefined);
});

test("RuTracker через ПК: запрос в поток комнаты, ответ только от ПК комнаты, «add» — тому ПК, что нашёл", async () => {
  const pcSearch = require("../src/services/pc-search");
  const room = rooms.getOrCreateChannelRoom("999999999999999999", alice);
  assert.deepEqual(await pcSearch.search(room, alice, "Dune"), { ok: false, reason: "no_pc" });

  rooms.joinRoom(room.id, bob, "pc_bob1");
  rooms.joinRoom(room.id, carol, "android_carol");
  const sent = [];
  const stop = rooms.addListener(room.id, msg => sent.push(JSON.parse(msg)));
  const wait = pcSearch.search(room, alice, "Dune");
  const req = sent.find(m => m.event === "rutracker_request");
  assert.equal(req.data.kind, "search");
  assert.equal(req.data.query, "Dune");
  const rid = req.data.request_id;
  // Чужой: не участник, не тот аккаунт, не ПК
  assert.equal(pcSearch.reply(room.id, alice, { request_id: rid, client_id: "pc_bob1", hits: [] }).status, 403);
  assert.equal(pcSearch.reply(room.id, carol, { request_id: rid, client_id: "android_carol", hits: [] }).status, 403);
  assert.equal(pcSearch.reply("000000", bob, { request_id: rid, client_id: "pc_bob1", hits: [] }).status, 404);
  assert.equal(pcSearch.reply(room.id, bob, {
    request_id: rid, client_id: "pc_bob1",
    hits: [
      { title: "Дюна / Dune (2021) BDRip 1080p", size: 1e10, seeders: 50, topic_id: "6123456", badges: ["1080p", 5] },
      { title: "magnet", magnet: "magnet:?xt=urn:btih:" + "a".repeat(40) + "&dn=x" },
      { title: "<плохой>", magnet: "javascript:alert(1)" },
      { topic_id: "1" }
    ]
  }).ok, true);
  const got = await wait;
  assert.equal(got.ok, true);
  assert.equal(got.hits.length, 2);
  assert.equal(got.hits[0].topic_id, "6123456");
  assert.deepEqual(got.hits[0].badges, ["1080p"]);
  assert.equal(got.pc.key, rooms.clientKey("pc_bob1"));
  assert.equal(pcSearch.reply(room.id, bob, { request_id: rid, client_id: "pc_bob1", hits: [] }).status, 404); // уже ответили

  // Ошибка единственного ПК — сразу ответ, без таймаута
  const failing = pcSearch.search(room, alice, "Nope");
  const rid2 = sent.filter(m => m.event === "rutracker_request").pop().data.request_id;
  pcSearch.reply(room.id, bob, { request_id: rid2, client_id: "pc_bob1", error: "RuTracker не настроен на этом ПК." });
  assert.deepEqual(await failing, { ok: false, reason: "failed", error: "RuTracker не настроен на этом ПК." });

  // add: только нужному ПК
  assert.equal((await pcSearch.add(room, alice, { target: "чужой", topicId: "1", title: "x" })).reason, "no_pc");
  const adding = pcSearch.add(room, alice, { target: got.pc.key, topicId: "6123456", title: "Дюна" });
  const ar = sent.filter(m => m.event === "rutracker_request").pop().data;
  assert.equal(ar.kind, "add");
  assert.equal(ar.topic_id, "6123456");
  assert.equal(ar.target, got.pc.key);
  pcSearch.reply(room.id, bob, { request_id: ar.request_id, client_id: "pc_bob1", id: 4242, ready: true });
  assert.deepEqual(await adding, { ok: true, id: 4242, ready: true });
  stop();
  rooms.leaveRoom(room.id, "pc_bob1", true);
  rooms.leaveRoom(room.id, "android_carol", true);
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

    // Вход: мусор вместо кода в Discord не уходит
    const junk = await fetch(`${base}/api/auth/discord/activity`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code: "<x>" }) });
    assert.equal(junk.status, 400);

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

    // «Позвать в чат»: только тот, кто у пульта; без бота — ссылка для копирования
    const notHere = await fetch(`${base}/api/activity/rooms/${room.id}/invite`, { method: "POST", headers: auth, body: "{}" });
    assert.equal(notHere.status, 403);
    const leave = rooms.addWatcher(rooms.getRoom(room.id), bob);
    const inv = await (await fetch(`${base}/api/activity/rooms/${room.id}/invite`, { method: "POST", headers: auth, body: "{}" })).json();
    assert.equal(inv.fallback, true);
    assert.equal(inv.reason, "no_bot");
    assert.match(inv.link, new RegExp(`/join/${room.id}$`));
    leave();
    // Опрос комнаты из Activity — тоже «у пульта»: позвать можно и без потока
    const polled = await fetch(`${base}/api/activity/rooms/${room.id}`, { headers: auth });
    assert.equal(polled.status, 200);
    assert.ok(rooms.getRoom(room.id).watchers.has(bob.id));
    const inv2 = await (await fetch(`${base}/api/activity/rooms/${room.id}/invite`, { method: "POST", headers: auth, body: "{}" })).json();
    assert.equal(inv2.reason, "no_bot");
    const pw = rooms.getRoom(room.id).watchers.get(bob.id);
    clearTimeout(pw.expire);
    rooms.getRoom(room.id).watchers.delete(bob.id);
    const cancelSpin = await fetch(`${base}/api/activity/rooms/${room.id}/roulette/cancel`, { method: "POST", headers: auth, body: "{}" });
    assert.equal(cancelSpin.status, 200);
    const fin = await fetch(`${base}/api/activity/rooms/${room.id}/poll/finish`, { method: "POST", headers: auth, body: "{}" });
    assert.equal(fin.status, 400); // голосования нет
    const spin = await fetch(`${base}/api/activity/rooms/${room.id}/roulette`, { method: "POST", headers: auth, body: "{}" });
    assert.equal(spin.status, 400); // меньше двух фильмов

    // RuTracker через ПК: без ПК в комнате — сразу понятный ответ, а не ожидание
    const rt = await fetch(`${base}/api/activity/rooms/${room.id}/rutracker/search`, { method: "POST", headers: auth, body: JSON.stringify({ query: "Dune" }) });
    assert.equal(rt.status, 200);
    const rtj = await rt.json();
    assert.equal(rtj.ok, false);
    assert.equal(rtj.reason, "no_pc");
    const big = await fetch(`${base}/api/rooms/${room.id}/rutracker/reply`, { method: "POST", headers: auth,
      body: JSON.stringify({ request_id: "rq_x", client_id: "pc_1", hits: Array(100).fill({ title: "x".repeat(300), topic_id: "1" }) }) });
    assert.equal(big.status, 404); // тело больше 20 КБ принято, запрос просто устарел

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

// ---- Приглушение фильма, когда говорят в Discord ----
const voiceDuck = require("../src/services/voice-duck");
const dan = findOrCreateUser({ id: "200000000000000001", username: "dan", global_name: "Дэн", avatar: null }, "allow");
const eve = findOrCreateUser({ id: "200000000000000002", username: "eve", global_name: "Ева", avatar: null }, "allow");

test("приглушение: настройка по умолчанию 10 %, шаг 10, только аккаунт Discord", () => {
  assert.deepEqual(voiceDuck.getVoiceDuck(dan.id), { enabled: true, level: 10, min_ms: 400 });
  assert.deepEqual(voiceDuck.getVoiceDuck("shared"), { enabled: true, level: 10, min_ms: 400 });
  assert.equal(voiceDuck.setVoiceDuck(dan.id, { enabled: true, level: 10, min_ms: 450 }), null);
  assert.equal(voiceDuck.setVoiceDuck(dan.id, { enabled: true, level: 10, min_ms: 2100 }), null);
  assert.equal(voiceDuck.setVoiceDuck(dan.id, { enabled: true, level: 15 }), null);
  assert.equal(voiceDuck.setVoiceDuck(dan.id, { enabled: true, level: 110 }), null);
  assert.equal(voiceDuck.setVoiceDuck(dan.id, { enabled: "yes", level: 10 }), null);
  assert.equal(voiceDuck.setVoiceDuck("shared", { enabled: false, level: 10 }), null);
  assert.deepEqual(voiceDuck.setVoiceDuck(dan.id, { enabled: false, level: 100, min_ms: 800 }), { enabled: false, level: 100, min_ms: 800 });
  // без min_ms (старая страница) задержка не сбрасывается
  assert.deepEqual(voiceDuck.setVoiceDuck(dan.id, { enabled: false, level: 100 }), { enabled: false, level: 100, min_ms: 800 });
  assert.deepEqual(voiceDuck.getVoiceDuck(dan.id), { enabled: false, level: 100, min_ms: 800 });
  voiceDuck.setVoiceDuck(dan.id, { enabled: true, level: 10, min_ms: 400 });
});

test("приглушение: говорящие сводятся в один список, конец фразы — с задержкой, сообщает только пульт", async t => {
  const room = rooms.getOrCreateChannelRoom("555555555555555555", dan);
  const events = [];
  const stopListen = rooms.addListener(room.id, msg => {
    const m = JSON.parse(msg);
    if (m.event === "voice_changed" || m.event === "settings_changed") events.push(m);
  });
  // не у пульта — нельзя
  assert.equal(rooms.reportSpeaking(room.id, dan, eve.id, true).status, 403);
  const leaveD = rooms.addWatcher(room, dan);
  const leaveE = rooms.addWatcher(room, eve);
  assert.equal(rooms.reportSpeaking(room.id, dan, "1001", true).status, 400);
  assert.deepEqual(rooms.getRoomPublicData(room).voice, { active: false, speakers: [], talking: [] });

  const t0 = Date.now();
  assert.equal(rooms.reportSpeaking(room.id, dan, eve.id, true, t0).changed, true);
  // тот же говорящий от второго пульта — без нового события
  assert.equal(rooms.reportSpeaking(room.id, eve, eve.id, true, t0).changed, false);
  assert.equal(events.length, 1);
  assert.deepEqual(events[0].data, { active: true, speakers: [eve.id], talking: [eve.id] });
  assert.deepEqual(rooms.getRoomPublicData(room).voice, { active: true, speakers: [eve.id], talking: [eve.id] });
  // «замолчал» неизвестный — ничего
  assert.equal(rooms.reportSpeaking(room.id, dan, dan.id, false).changed, false);

  // конец фразы: ещё 1,5 с в списке (паузы между словами не дёргают громкость), потом voice_changed пустой
  rooms.reportSpeaking(room.id, dan, eve.id, false);
  assert.equal(room.speakers.has(eve.id), true);
  // замолчала: ещё в speakers, но уже не в talking — плеер, чей порог не набран, не глушит
  assert.deepEqual(events.at(-1).data, { active: true, speakers: [eve.id], talking: [] });
  await new Promise(r => setTimeout(r, 1700));
  assert.equal(room.speakers.has(eve.id), false);
  assert.deepEqual(events.at(-1).data, { active: false, speakers: [], talking: [] });

  // повтор «говорит» без перемены — без события; заговорила снова в паузе — событие (talking снова с ней)
  rooms.reportSpeaking(room.id, dan, eve.id, true);
  const n = events.length;
  rooms.reportSpeaking(room.id, dan, eve.id, true);
  assert.equal(events.length, n);
  rooms.reportSpeaking(room.id, dan, eve.id, false);
  rooms.reportSpeaking(room.id, dan, eve.id, true);
  assert.deepEqual(events.at(-1).data.talking, [eve.id]);
  rooms.reportSpeaking(room.id, dan, eve.id, false);

  // настройка — только комнатам, где человек есть; for_user — чей
  rooms.notifyUserSettings(dan.id, { voice_duck: { enabled: true, level: 50 } });
  const s = events.find(e => e.event === "settings_changed");
  assert.deepEqual(s.data, { for_user: dan.id, voice_duck: { enabled: true, level: 50 } });
  const before = events.length;
  rooms.notifyUserSettings(carol.id, { voice_duck: { enabled: true, level: 50 } });
  assert.equal(events.length, before);

  await new Promise(r => setTimeout(r, 1700));
  stopListen();
  leaveD();
  leaveE();
  t.diagnostic(`событий: ${events.length}`);
});

test("приглушение: маршруты — только из Activity, говорящий — участник канала", async () => {
  const { createApp } = require("../src/app");
  const server = createApp().listen(0, "127.0.0.1");
  await new Promise(r => server.once("listening", r));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const { token } = issueActivityToken(eve.id);
    const auth = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
    const got = await (await fetch(`${base}/api/activity/voice-duck`, { headers: auth })).json();
    assert.deepEqual(got.voice_duck, { enabled: true, level: 10, min_ms: 400 });
    const bad = await fetch(`${base}/api/activity/voice-duck`, { method: "POST", headers: auth, body: JSON.stringify({ enabled: true, level: 25 }) });
    assert.equal(bad.status, 400);
    const ok = await (await fetch(`${base}/api/activity/voice-duck`, { method: "POST", headers: auth, body: JSON.stringify({ enabled: true, level: 30, min_ms: 600 }) })).json();
    assert.deepEqual(ok.voice_duck, { enabled: true, level: 30, min_ms: 600 });
    assert.deepEqual(voiceDuck.getVoiceDuck(eve.id), { enabled: true, level: 30, min_ms: 600 });

    const room = rooms.getOrCreateChannelRoom("666666666666666666", eve);
    const notHere = await fetch(`${base}/api/activity/rooms/${room.id}/speaking`, { method: "POST", headers: auth, body: JSON.stringify({ user_id: "200000000000000001", speaking: true }) });
    assert.equal(notHere.status, 403);
    const leave = rooms.addWatcher(room, eve);
    const junk = await fetch(`${base}/api/activity/rooms/${room.id}/speaking`, { method: "POST", headers: auth, body: JSON.stringify({ user_id: "u_1", speaking: true }) });
    assert.equal(junk.status, 400);
    const sp = await (await fetch(`${base}/api/activity/rooms/${room.id}/speaking`, { method: "POST", headers: auth, body: JSON.stringify({ user_id: "200000000000000001", speaking: true }) })).json();
    assert.equal(sp.changed, true);
    assert.deepEqual(rooms.getRoomPublicData(room).voice.speakers, [dan.id]);
    rooms.reportSpeaking(room.id, eve, dan.id, false);

    // ключ устройства (приложение) не шлёт события речи и не меняет настройку
    const devHeaders = { "Content-Type": "application/json" };
    const noAct = await fetch(`${base}/api/activity/rooms/${room.id}/speaking`, { method: "POST", headers: devHeaders, body: JSON.stringify({ user_id: "200000000000000001", speaking: true }) });
    assert.equal(noAct.status, 401);
    await new Promise(r => setTimeout(r, 1700));
    leave();
  } finally {
    server.close();
  }
});

test("приглушение: «замолчал» сразу после «говорит» не оставляет запись, бот может снять говорящего", () => {
  const room = rooms.getOrCreateChannelRoom("777777777777777777", dan);
  const events = [];
  const stop = rooms.addListener(room.id, msg => { const m = JSON.parse(msg); if (m.event === "voice_changed") events.push(m.data); });
  const leave = rooms.addWatcher(room, dan);
  // фраза короче ответа бота: оба события засчитаны по порядку, запись уходит через 1,5 с, а не висит
  rooms.reportSpeaking(room.id, dan, eve.id, true);
  rooms.reportSpeaking(room.id, dan, eve.id, false);
  assert.ok(room.speakers.get(eve.id).until - Date.now() <= 1500);
  // бот: говорящего нет в канале — снимается сразу
  assert.equal(rooms.dropSpeaker(room.id, eve.id), true);
  assert.equal(room.speakers.size, 0);
  assert.deepEqual(events.at(-1), { active: false, speakers: [], talking: [] });
  assert.equal(rooms.dropSpeaker(room.id, eve.id), false);
  // без повторов «говорит» запись живёт не дольше 12 с
  rooms.reportSpeaking(room.id, dan, eve.id, true);
  assert.ok(room.speakers.get(eve.id).until - Date.now() <= 12000);
  rooms.dropSpeaker(room.id, eve.id);
  stop();
  leave();
});

test("ведущего можно передать тому, кто в комнате; передать может только ведущий", () => {
  const room = rooms.getOrCreateChannelRoom("161616161616161616", alice);
  const leaveA = rooms.addWatcher(room, alice);
  const leaveB = rooms.addWatcher(room, bob);
  const leaveC = rooms.addWatcher(room, carol);
  assert.equal(rooms.transferHost(room.id, bob, bob.id).ok, false, "гость не передаёт");
  assert.equal(rooms.transferHost(room.id, alice, "999").ok, false, "нет в комнате");
  assert.equal(rooms.transferHost(room.id, alice, bob.id).ok, true);
  assert.equal(room.host_id, bob.id);
  assert.equal(room.host_name, bob.name);
  assert.equal(rooms.transferHost(room.id, alice, carol.id).ok, false, "бывший ведущий уже не ведущий");
  assert.equal(rooms.transferHost(room.id, bob, bob.id).ok, true, "себе — без ошибки");
  // Передача ведущему с плеером: его устройство становится ведущим устройством
  const joined = rooms.joinRoom(room.id, carol, "pc_carol");
  assert.equal(joined.ok, true);
  assert.equal(rooms.transferHost(room.id, bob, carol.id).ok, true);
  assert.equal(room.host_client_id, "pc_carol");
  assert.equal(rooms.isRoomHost(room, carol, "pc_carol"), true);
  rooms.leaveRoom(room.id, "pc_carol", true);
  leaveA(); leaveB(); leaveC();
});

test("ведущий вышел из пустой комнаты: тот, кто вошёл позже минуты, становится ведущим", () => {
  const room = rooms.getOrCreateChannelRoom("171717171717171717", alice);
  const leaveA = rooms.addWatcher(room, alice);
  leaveA(); // комната пуста, ведущего нет
  assert.equal(room.host_id, alice.id, "ведущий записан, но его нет");
  assert.ok(room.host_absent_since > 0);
  // Вошёл сразу — ведущий ещё не «устарел»
  const leaveB = rooms.addWatcher(room, bob);
  assert.equal(room.host_id, alice.id);
  leaveB();
  // Прошло больше минуты без ведущего — новый человек становится ведущим
  room.host_absent_since = Date.now() - 61 * 1000;
  const leaveC = rooms.addWatcher(room, carol);
  assert.equal(room.host_id, carol.id);
  assert.equal(room.host_absent_since, 0);
  leaveC();
});

test("закрыть комнату может только ведущий: код перестаёт работать, в канале заводится новая", () => {
  const room = rooms.getOrCreateChannelRoom("181818181818181818", alice);
  const leaveA = rooms.addWatcher(room, alice);
  const leaveB = rooms.addWatcher(room, bob);
  assert.equal(rooms.closeRoom(room.id, bob).ok, false);
  assert.ok(rooms.getRoom(room.id));
  assert.equal(rooms.closeRoom(room.id, alice).ok, true);
  assert.equal(rooms.getRoom(room.id), null);
  const fresh = rooms.getOrCreateChannelRoom("181818181818181818", bob);
  assert.notEqual(fresh.id, room.id);
  assert.equal(fresh.host_id, bob.id);
  assert.equal(rooms.closeRoom(room.id, alice).ok, false, "уже закрыта");
  leaveA(); leaveB();
  rooms.closeRoom(fresh.id, bob);
});

test("пустая комната канала удаляется через 5 минут, обычная — через 30", () => {
  const channel = rooms.getOrCreateChannelRoom("191919191919191919", alice);
  const leave = rooms.addWatcher(channel, alice);
  leave();
  const plain = rooms.createRoom(alice);
  const later = Date.now() + 6 * 60 * 1000;
  rooms.sweepRooms(later);
  assert.equal(rooms.getRoom(channel.id), null, "канал: через 6 минут уже нет");
  assert.ok(rooms.getRoom(plain.id), "обычная комната ещё ждёт 30 минут");
  rooms.sweepRooms(Date.now() + 31 * 60 * 1000);
  assert.equal(rooms.getRoom(plain.id), null);
});

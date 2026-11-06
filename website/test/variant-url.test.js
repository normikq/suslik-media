"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-variant-test-"));

const test = require("node:test");
const assert = require("node:assert/strict");

const { treeCache } = require("../src/services/alldebrid");
const users = require("../src/services/users");
const rooms = require("../src/services/rooms");

test("маршруты variant-url: проверка без авторизации и с валидными параметрами", async t => {
  const { createApp } = require("../src/app");
  const server = createApp().listen(0, "127.0.0.1");
  t.after(() => server.close());
  await new Promise(resolve => server.once("listening", resolve));
  const base = `http://127.0.0.1:${server.address().port}`;

  // Без авторизации — 401
  const rUnauth = await fetch(`${base}/api/file/variant-url`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ad_id: 104921, path: "Dune.2021.1080p.mkv" })
  });
  assert.equal(rUnauth.status, 401);

  // Авторизованный пользователь
  const user = users.findOrCreateUser({ id: "v_user_1", username: "vtester" }, "allow");
  const pairing = users.createDevicePairing("Desktop Tester");
  users.authorizeDevicePairing(pairing.user_code, user.id);
  const token = users.pollDevicePairing(pairing.code).device_token;
  const authHeaders = {
    "Content-Type": "application/json",
    "X-Device-Token": token
  };

  // Некорректные параметры — 400
  const rBad = await fetch(`${base}/api/file/variant-url`, {
    method: "POST",
    headers: authHeaders,
    body: JSON.stringify({ ad_id: "not_a_number" })
  });
  assert.equal(rBad.status, 400);

  // Раздача не найдена — 404
  const rNotFound = await fetch(`${base}/api/file/variant-url`, {
    method: "POST",
    headers: authHeaders,
    body: JSON.stringify({ ad_id: 999999, path: "test.mkv" })
  });
  assert.equal(rNotFound.status, 404);

  // Заполняем кэш файлов для magnet id 104921
  treeCache.set(104921, [
    {
      name: "Dune.2021.1080p.mkv",
      path: "Dune.2021/Dune.2021.1080p.mkv",
      type: "file",
      size: 8500000000,
      sourceLink: "https://alldebrid.com/f/dune1080"
    },
    {
      name: "Dune.2021.2160p.mkv",
      path: "Dune.2021/Dune.2021.2160p.mkv",
      type: "file",
      size: 25000000000,
      sourceLink: "https://alldebrid.com/f/dune2160"
    }
  ]);

  // Успешный запрос по ad_id и path
  const rOk1 = await fetch(`${base}/api/file/variant-url`, {
    method: "POST",
    headers: authHeaders,
    body: JSON.stringify({ ad_id: 104921, path: "Dune.2021/Dune.2021.1080p.mkv" })
  });
  assert.equal(rOk1.status, 200);
  const data1 = await rOk1.json();
  assert.equal(data1.ok, true);
  assert.equal(data1.ad_id, 104921);
  assert.equal(data1.name, "Dune.2021.1080p.mkv");
  assert.match(data1.url, /\/api\/file\/proxy\/Dune\.2021\.1080p\.mkv\?link=/);
  assert.equal(data1.source_url, "https://alldebrid.com/f/dune1080");

  // Успешный запрос по variant_key
  const rOk2 = await fetch(`${base}/api/file/variant-url`, {
    method: "POST",
    headers: authHeaders,
    body: JSON.stringify({ variant_key: "104921|Dune.2021/Dune.2021.2160p.mkv" })
  });
  assert.equal(rOk2.status, 200);
  const data2 = await rOk2.json();
  assert.equal(data2.ok, true);
  assert.equal(data2.ad_id, 104921);
  assert.equal(data2.name, "Dune.2021.2160p.mkv");
  assert.match(data2.url, /\/api\/file\/proxy\/Dune\.2021\.2160p\.mkv\?link=/);

  // Проверка кэширования (повторный запрос возвращает тот же URL)
  const rOkCached = await fetch(`${base}/api/file/variant-url`, {
    method: "POST",
    headers: authHeaders,
    body: JSON.stringify({ ad_id: 104921, path: "Dune.2021/Dune.2021.1080p.mkv" })
  });
  assert.equal(rOkCached.status, 200);
  const dataCached = await rOkCached.json();
  assert.equal(dataCached.url, data1.url);

  // Проверка динамического publicBase при смене Host заголовка из кэша (http.request позволяет переопределить Host)
  const http = require("http");
  const resHost = await new Promise((resolve, reject) => {
    const req = http.request({
      host: "127.0.0.1",
      port: server.address().port,
      method: "POST",
      path: "/api/file/variant-url",
      headers: {
        ...authHeaders,
        "Host": "otherhost.example.com"
      }
    }, res => {
      let text = "";
      res.setEncoding("utf8");
      res.on("data", c => { text += c; });
      res.on("end", () => resolve({ status: res.statusCode, json: JSON.parse(text) }));
    });
    req.on("error", reject);
    req.end(JSON.stringify({ ad_id: 104921, path: "Dune.2021/Dune.2021.1080p.mkv" }));
  });
  assert.equal(resHost.status, 200);
  assert.match(resHost.json.url, /^https:\/\/otherhost\.example\.com\/api\/file\/proxy\//);

  // Тест на различение файлов сериала с одинаковыми именами («01.mkv» в разных сезонах) и субтитры
  treeCache.set(200001, [
    {
      name: "01.mkv",
      path: "Season 1/01.mkv",
      type: "file",
      size: 1000000000,
      sourceLink: "https://alldebrid.com/f/s01e01"
    },
    {
      name: "01.mkv",
      path: "Season 2/01.mkv",
      type: "file",
      size: 1200000000,
      sourceLink: "https://alldebrid.com/f/s02e01"
    },
    {
      name: "01.srt",
      path: "Season 2/01.srt",
      type: "file",
      size: 50000,
      sourceLink: "https://alldebrid.com/f/s02e01_sub"
    }
  ]);

  const rS2 = await fetch(`${base}/api/file/variant-url`, {
    method: "POST",
    headers: authHeaders,
    body: JSON.stringify({ ad_id: 200001, path: "Season 2/01.mkv" })
  });
  assert.equal(rS2.status, 200);
  const dataS2 = await rS2.json();
  assert.equal(dataS2.source_url, "https://alldebrid.com/f/s02e01");
  assert.equal(dataS2.path, "Season 2/01.mkv");
  assert.equal(dataS2.subtitles.length, 1);
  assert.equal(dataS2.subtitles[0].path, "Season 2/01.srt");

  // variant_key имеет приоритет над старыми ad_id и path
  const rPrio = await fetch(`${base}/api/file/variant-url`, {
    method: "POST",
    headers: authHeaders,
    body: JSON.stringify({
      ad_id: 104921,
      path: "Dune.2021/Dune.2021.1080p.mkv",
      variant_key: "200001|Season 2/01.mkv"
    })
  });
  assert.equal(rPrio.status, 200);
  const dataPrio = await rPrio.json();
  assert.equal(dataPrio.ad_id, 200001);
  assert.equal(dataPrio.path, "Season 2/01.mkv");

  const ca = require("../src/services/custom-audio");

  // Отбор субтитров и дорожек конкретной серии в многосерийной раздаче
  treeCache.set(300001, [
    { name: "Show.S01E01.mkv", path: "Show/Show.S01E01.mkv", type: "file", size: 1000, sourceLink: "https://alldebrid.com/f/e01" },
    { name: "Show.S01E02.mkv", path: "Show/Show.S01E02.mkv", type: "file", size: 1000, sourceLink: "https://alldebrid.com/f/e02" },
    { name: "Show.S01E01.rus.srt", path: "Show/Show.S01E01.rus.srt", type: "file", size: 50, sourceLink: "https://alldebrid.com/f/e01_sub" },
    { name: "Show.S01E02.rus.srt", path: "Show/Show.S01E02.rus.srt", type: "file", size: 50, sourceLink: "https://alldebrid.com/f/e02_sub" },
    { name: "Show.S01E01.mka", path: "Show/Show.S01E01.mka", type: "file", size: 100, sourceLink: "https://alldebrid.com/f/e01_aud" },
    { name: "Show.S01E02.mka", path: "Show/Show.S01E02.mka", type: "file", size: 100, sourceLink: "https://alldebrid.com/f/e02_aud" }
  ]);

  treeCache.set(400001, [
    { name: "Dub_E01.mka", path: "Dub/Dub_E01.mka", type: "file", size: 100, sourceLink: "https://alldebrid.com/f/dub_e01" },
    { name: "Dub_E02.mka", path: "Dub/Dub_E02.mka", type: "file", size: 100, sourceLink: "https://alldebrid.com/f/dub_e02" }
  ]);
  ca.addTracks({
    movieMagnetId: 300001,
    audioMagnetId: 400001,
    author: "vtester",
    pairs: [
      { videoPath: "Show/Show.S01E01.mkv", audioPath: "Dub/Dub_E01.mka", voice: "Кубик в Кубе" },
      { videoPath: "Show/Show.S01E02.mkv", audioPath: "Dub/Dub_E02.mka", voice: "Кубик в Кубе" }
    ]
  });

  const rSubsEp1 = await fetch(`${base}/api/file/variant-url`, {
    method: "POST",
    headers: authHeaders,
    body: JSON.stringify({ variant_key: "300001|Show/Show.S01E01.mkv" })
  });
  assert.equal(rSubsEp1.status, 200);
  const dataEp1 = await rSubsEp1.json();
  assert.equal(dataEp1.subtitles.length, 1);
  assert.equal(dataEp1.subtitles[0].path, "Show/Show.S01E01.rus.srt");
  assert.equal(dataEp1.audio.length, 2); // 1 sidecar + 1 custom track
  assert.equal(dataEp1.audio.some(a => a.id.startsWith("sidecar:") && a.path === "Show/Show.S01E01.mka"), true);
  assert.equal(dataEp1.audio.some(a => !a.id.startsWith("sidecar:") && a.title === "Кубик в Кубе"), true);
  // Никаких дорожек от серии 2 быть не должно
  assert.equal(dataEp1.audio.some(a => (a.path && a.path.includes("E02")) || (a.url && a.url.includes("E02"))), false);
});

test("комнаты: действие variant_change обновляет variant_key участника", () => {
  const host = { id: "h1", name: "Host" };
  const guest = { id: "g1", name: "Guest" };
  const room = rooms.createRoom(host, { key: "1|film.mkv", name: "Film" });

  const joinRes = rooms.joinRoom(room.id, guest, "guest_client_1");
  assert.equal(joinRes.ok, true);

  const events = [];
  rooms.addListener(room.id, payload => events.push(JSON.parse(payload)));

  // Гость переключает пресет качества
  const res = rooms.handleRoomAction(room.id, guest, {
    type: "variant_change",
    client_id: "guest_client_1",
    variant_key: "102|Film.1080p.mkv"
  });
  assert.equal(res.ok, true);

  const member = room.members.get("guest_client_1");
  assert.equal(member.variant_key, "102|Film.1080p.mkv");

  const pub = rooms.getRoomPublicData(room);
  const pubMember = pub.members.find(m => m.name === "Guest");
  assert.equal(pubMember.variant_key, "102|Film.1080p.mkv");

  assert.equal(events.some(e => e.event === "state_changed"), true);
});

test("комнаты: variant_key проверяется по списку пресетов файла", () => {
  const host = { id: "h2", name: "Host" };
  const guest = { id: "g2", name: "Guest" };
  const file = {
    key: "10|Film.2160p.mkv",
    name: "Film",
    variants: [{ key: "10|Film.2160p.mkv", label: "4K" }, { key: "11|Film.1080p.mkv", label: "1080p" }]
  };
  const room = rooms.createRoom(host, file);
  assert.equal(rooms.joinRoom(room.id, guest, "g2_client").ok, true);

  const bad = rooms.handleRoomAction(room.id, guest, { type: "variant_change", client_id: "g2_client", variant_key: "99|Other.mkv" });
  assert.equal(bad.ok, false);
  assert.equal(room.members.get("g2_client").variant_key, undefined);

  const junk = rooms.handleRoomAction(room.id, guest, { type: "variant_change", client_id: "g2_client", variant_key: "not a key" });
  assert.equal(junk.ok, false);

  // buffering ключ качества не принимает вообще: для этого есть variant_change
  const buf = rooms.handleRoomAction(room.id, guest, { type: "buffering", client_id: "g2_client", buffering: false, variant_key: "99|Other.mkv" });
  assert.equal(buf.ok, true);
  assert.equal(room.members.get("g2_client").variant_key, undefined);

  const good = rooms.handleRoomAction(room.id, guest, { type: "variant_change", client_id: "g2_client", variant_key: "11|Film.1080p.mkv" });
  assert.equal(good.ok, true);
  assert.equal(room.members.get("g2_client").variant_key, "11|Film.1080p.mkv");
});

test("комнаты: аккаунт не меняет качество другого участника", () => {
  const host = { id: "u_1", name: "A" };
  const other = { id: "u_2", name: "B" };
  const room = rooms.createRoom(host, { key: "1|f.mkv", name: "F" });
  rooms.joinRoom(room.id, host, "a_client");
  const res = rooms.handleRoomAction(room.id, other, { type: "variant_change", client_id: "a_client", variant_key: "2|f.mkv" });
  assert.equal(res.ok, false);
});

test("комнаты: повторное предложение того же фильма пополняет пресеты кандидата", () => {
  const host = { id: "u_5", name: "Host" };
  const room = rooms.getOrCreateChannelRoom("123456789", host);
  const v = (k, label) => ({ key: k, label });
  assert.equal(rooms.suggestFile(room.id, host, { key: "1|a.mkv", name: "A", canonical_id: "m:a_2020:default", variants: [v("1|a.mkv", "1080p")] }).ok, true);
  assert.equal(rooms.suggestFile(room.id, host, { key: "2|a.mkv", name: "A", canonical_id: "m:a_2020:default", variants: [v("2|a.mkv", "4K"), v("1|a.mkv", "1080p")] }).ok, true);
  assert.equal(room.candidates.length, 1);
  assert.deepEqual(room.candidates[0].variants.map(x => x.key), ["1|a.mkv", "2|a.mkv"]);
});

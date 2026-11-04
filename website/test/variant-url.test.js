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

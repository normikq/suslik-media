"use strict";

// Файл комнаты из тела запроса проходит cleanFile: при создании и в change_file (аудит PR #83)
const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-rooms-file-test-"));

const test = require("node:test");
const assert = require("node:assert/strict");
const rooms = require("../src/services/rooms");

const host = { id: "u_host", name: "Ведущий" };
const guest = { id: "u_guest", name: "Гость" };

function listen(roomId) {
  const events = [];
  rooms.addListener(roomId, (payload) => events.push(JSON.parse(payload)));
  return events;
}

test("change_file: файл очищается, лишние поля не рассылаются", () => {
  const room = rooms.createRoom(host);
  const events = listen(room.id);
  const res = rooms.handleRoomAction(room.id, host, {
    type: "change_file",
    file: { key: "42|Film/Film.2020.mkv", name: "  Фильм  ", ad_id: 7, evil: "x".repeat(10000), nested: { a: 1 } }
  });
  assert.equal(res.ok, true);
  assert.deepEqual(room.file, { key: "42|Film/Film.2020.mkv", name: "Фильм", ad_id: 42 });
  assert.deepEqual(events.map(e => e.event), ["state_changed", "refresh_library"]);
  assert.deepEqual(events[1].data.file, room.file);
  assert.equal(JSON.stringify(events).includes("evil"), false);
});

test("change_file: длинные строки обрезаются", () => {
  const room = rooms.createRoom(host);
  rooms.handleRoomAction(room.id, host, { type: "change_file", file: { key: "5|" + "a".repeat(5000), name: "б".repeat(5000) } });
  assert.equal(room.file.key.length, 1000);
  assert.equal(room.file.name.length, 300);
});

test("change_file: неверный файл отклоняется, фильм комнаты не меняется", () => {
  const room = rooms.createRoom(host);
  rooms.handleRoomAction(room.id, host, { type: "change_file", file: { key: "1|a.mkv", name: "А" } });
  const seq = room.state.seq;
  const events = listen(room.id);
  for (const bad of [{ key: "bad" }, { key: "0|a.mkv" }, { key: "1|" }, { key: 5 }, "1|a.mkv", 42, []]) {
    const res = rooms.handleRoomAction(room.id, host, { type: "change_file", file: bad });
    assert.equal(res.ok, false, JSON.stringify(bad));
  }
  assert.equal(room.file.key, "1|a.mkv");
  assert.equal(room.state.seq, seq);
  assert.equal(events.length, 0);
});

test("change_file: null закрывает фильм без refresh_library; гостю нельзя", () => {
  const room = rooms.createRoom(host, { key: "3|b.mkv", name: "Б" });
  assert.equal(room.file.key, "3|b.mkv");
  assert.equal(rooms.handleRoomAction(room.id, guest, { type: "change_file", file: null }).ok, false);
  const events = listen(room.id);
  assert.equal(rooms.handleRoomAction(room.id, host, { type: "change_file", file: null }).ok, true);
  assert.equal(room.file, null);
  assert.deepEqual(events.map(e => e.event), ["state_changed"]);
});

test("создание комнаты: файл тоже очищается, неверный — без фильма", () => {
  assert.deepEqual(rooms.createRoom(host, { key: "9|c.mkv", name: "В", junk: 1 }).file, { key: "9|c.mkv", name: "В", ad_id: 9 });
  assert.equal(rooms.createRoom(host, { key: "nope" }).file, null);
  assert.equal(rooms.createRoom(host, "9|c.mkv").file, null);
});

test("change_file: canonical_id, edition и variants сохраняются", () => {
  const room = rooms.createRoom(host);
  const variants = [
    { key: "101|dune.2160p.mkv", label: "4K · DV · Atmos", quality: "4K", audio: "TrueHD Atmos", bitrate: 48.2, offset_ms: 0 },
    { key: "102|dune.1080p.mkv", label: "1080p · AC3", quality: "1080p", audio: "AC3 5.1", bitrate: 8.1, offset_ms: 0 }
  ];
  const res = rooms.handleRoomAction(room.id, host, {
    type: "change_file",
    file: {
      key: "101|dune.2160p.mkv",
      name: "Дюна (2021)",
      canonical_id: "m:dune_2021:theatrical",
      edition: "theatrical",
      variants
    }
  });
  assert.equal(res.ok, true);
  assert.equal(room.file.canonical_id, "m:dune_2021:theatrical");
  assert.equal(room.file.edition, "theatrical");
  assert.equal(room.file.variants.length, 2);
  assert.equal(room.file.variants[0].quality, "4K");
  assert.equal(room.file.variants[1].bitrate, 8.1);
});

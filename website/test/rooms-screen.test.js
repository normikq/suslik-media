"use strict";

// Экран кинотеатра ведущего в Cyberpunk 2077 (действие screen) проходит cleanScreen
const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-rooms-screen-test-"));

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

test("screen: ведущий делится экраном, лишнее отбрасывается", () => {
  const room = rooms.createRoom(host);
  const events = listen(room.id);
  const res = rooms.handleRoomAction(room.id, host, {
    type: "screen",
    screen: {
      c: [1.123456, 2, 3],
      n: [0, 1, 0],
      w: 4.5,
      h: 2.53125,
      sr: 0.1,
      fit: "fill",
      evil: "x".repeat(1000)
    }
  });
  assert.equal(res.ok, true);
  assert.deepEqual(room.screen, {
    c: [1.1235, 2, 3],
    n: [0, 1, 0],
    w: 4.5,
    h: 2.5313,
    sr: 0.1,
    su: 0,
    sn: 0,
    ar: 0,
    fit: "fill"
  });
  assert.deepEqual(events.map(e => e.event), ["state_changed"]);
  assert.deepEqual(events[0].data.screen, room.screen);
  assert.equal(JSON.stringify(events).includes("evil"), false);
});

test("screen: нет экрана — поля нет в данных комнаты", () => {
  const room = rooms.createRoom(host);
  assert.equal("screen" in rooms.getRoomPublicData(room), false);

  const resShare = rooms.handleRoomAction(room.id, host, {
    type: "screen",
    screen: { c: [0, 0, 0], n: [0, 1, 0], w: 1, h: 1 }
  });
  assert.equal(resShare.ok, true);
  assert.equal("screen" in rooms.getRoomPublicData(room), true);

  const resClear = rooms.handleRoomAction(room.id, host, {
    type: "screen",
    screen: null
  });
  assert.equal(resClear.ok, true);
  assert.equal(room.screen, null);
  assert.equal("screen" in rooms.getRoomPublicData(room), false);
});

test("screen: гостю нельзя", () => {
  const room = rooms.createRoom(host);
  const events = listen(room.id);
  const res = rooms.handleRoomAction(room.id, guest, {
    type: "screen",
    screen: { c: [0, 0, 0], n: [0, 1, 0], w: 1, h: 1 }
  });
  assert.equal(res.ok, false);
  assert.match(res.error, /Экраном делится/);
  assert.equal(room.screen, undefined);
  assert.equal(events.length, 0);
});

test("screen: неверный экран отклоняется", () => {
  const room = rooms.createRoom(host);
  const validScreen = { c: [0, 0, 0], n: [0, 1, 0], w: 1, h: 1 };
  const resValid = rooms.handleRoomAction(room.id, host, {
    type: "screen",
    screen: validScreen
  });
  assert.equal(resValid.ok, true);
  const previous = { ...room.screen };

  const badList = [
    { c: [1, 2], n: [0, 1, 0], w: 1, h: 1 },
    { c: [1, 2, 3], n: [0, 0, 0], w: 1, h: 1 },
    { c: [1, 2, 3], n: [0, 1, 0], w: 0, h: 1 },
    { c: [1, 2, 3], n: [0, 1, 0], w: 1, h: 1, sr: 500 },
    { c: [1, 2, "3"], n: [0, 1, 0], w: 1, h: 1 },
    { c: [1, 2, 3], n: [0, 1, 0], w: 1, h: Infinity },
    "screen",
    42,
    []
  ];

  const events = listen(room.id);
  for (const bad of badList) {
    const res = rooms.handleRoomAction(room.id, host, { type: "screen", screen: bad });
    assert.equal(res.ok, false, JSON.stringify(bad));
  }

  assert.deepEqual(room.screen, previous);
  assert.equal(events.length, 0);
});

test("screen: неизвестный fit — fit", () => {
  const room = rooms.createRoom(host);
  const res = rooms.handleRoomAction(room.id, host, {
    type: "screen",
    screen: { c: [0, 0, 0], n: [0, 1, 0], w: 1, h: 1, fit: "zoom" }
  });
  assert.equal(res.ok, true);
  assert.equal(room.screen.fit, "fit");
});

test("screen: смена фильма экран не сбрасывает", () => {
  const room = rooms.createRoom(host);
  rooms.handleRoomAction(room.id, host, {
    type: "screen",
    screen: { c: [0, 0, 0], n: [0, 1, 0], w: 1, h: 1 }
  });
  assert.notEqual(room.screen, null);
  const screenBefore = { ...room.screen };

  const res = rooms.handleRoomAction(room.id, host, {
    type: "change_file",
    file: { key: "1|a.mkv", name: "А" }
  });
  assert.equal(res.ok, true);
  assert.deepEqual(room.screen, screenBefore);
});

"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-sync-test-"));

const test = require("node:test");
const assert = require("node:assert/strict");

const { apply, changesSince, sweep, dropMagnet, _reset } = require("../src/services/sync");

const DAY = 24 * 3600 * 1000;
const U = "u_100";

test("sync: progress and tracks go to the account and come back", () => {
  _reset();
  const now = 1_000 * DAY;
  const r = apply(U, {
    watch: [{ key: "42|Season 1/E01.mkv", pos: 60_000, len: 2_400_000, at: now - 1000 }],
    tracks: [{ key: "m42", audio: "Дубляж [Russian]", spu: "__off__", at: now - 1000 }]
  }, now);
  assert.deepEqual(r, { watch: 1, tracks: 1 });
  const all = changesSince(U, 0, now);
  assert.deepEqual(all.watch, [{ key: "42|Season 1/E01.mkv", pos: 60_000, len: 2_400_000, at: now - 1000 }]);
  assert.deepEqual(all.tracks, [{ key: "m42", audio: "Дубляж [Russian]", spu: "__off__", sidecar: null, at: now - 1000 }]);
  // Другой пользователь ничего не видит, вход по паролю — тоже
  assert.equal(changesSince("u_200", 0, now).watch.length, 0);
  assert.deepEqual(apply("shared", { watch: [{ key: "1|a", pos: 1, at: now }] }, now), { watch: 0, tracks: 0 });
});

test("sync: the later choice wins, an older one is ignored, missing fields stay", () => {
  _reset();
  const now = 1_000 * DAY;
  apply(U, { watch: [{ key: "7|f.mkv", pos: 500_000, len: 900_000, at: now - 10 }] }, now);
  apply(U, { watch: [{ key: "7|f.mkv", pos: 100_000, len: 900_000, at: now - 20 }] }, now);
  assert.equal(changesSince(U, 0, now).watch[0].pos, 500_000);
  apply(U, { tracks: [{ key: "m7", audio: "A", spu: "S", at: now - 10 }] }, now);
  apply(U, { tracks: [{ key: "m7", sidecar: "rus 1", at: now - 5 }] }, now);
  assert.deepEqual(changesSince(U, 0, now).tracks[0], { key: "m7", audio: "A", spu: "S", sidecar: "rus 1", at: now - 5 });
  // Время клиента из будущего не закрепляет запись навсегда
  apply(U, { watch: [{ key: "7|f.mkv", pos: 1, len: 900_000, at: now + 10 * DAY }] }, now);
  assert.equal(changesSince(U, 0, now).watch[0].at, now);
});

test("sync: only what changed since the last answer", () => {
  _reset();
  const t0 = 1_000 * DAY;
  apply(U, { watch: [{ key: "1|a.mkv", pos: 10_000, len: 0, at: t0 }] }, t0);
  const first = changesSince(U, 0, t0 + 5);
  assert.equal(first.watch.length, 1);
  apply(U, { watch: [{ key: "2|b.mkv", pos: 20_000, len: 0, at: t0 + 100 }] }, t0 + 100);
  const next = changesSince(U, first.now, t0 + 200);
  assert.deepEqual(next.watch.map(w => w.key), ["2|b.mkv"]);
});

test("sync: bad entries are skipped", () => {
  _reset();
  const now = 1_000 * DAY;
  const r = apply(U, {
    watch: [
      { key: "no-id", pos: 1, at: now },
      { key: "1|a", pos: -5, at: now },
      { key: "1|a", pos: 5 },
      { key: 5, pos: 5, at: now }
    ],
    tracks: [
      { key: "title", audio: "x", at: now },
      { key: "m1", audio: 12, at: now },
      { key: "m1", audio: "bad\nline", at: now },
      { key: "m1", at: now }
    ]
  }, now);
  assert.deepEqual(r, { watch: 0, tracks: 0 });
});

test("sync: unused for 10 days is removed; watching a film keeps its tracks", () => {
  _reset();
  const t0 = 1_000 * DAY;
  apply(U, {
    watch: [{ key: "5|old.mkv", pos: 10_000, len: 0, at: t0 }],
    tracks: [
      { key: "m5", audio: "A", at: t0 },
      { key: "m9", audio: "B", at: t0 },
      { key: "*", audio: "A", at: t0 }
    ]
  }, t0);
  // Через 6 дней смотрят другую серию того же сериала: его дорожки и общий выбор продлеваются
  apply(U, { watch: [{ key: "5|new.mkv", pos: 20_000, len: 0, at: t0 + 6 * DAY }] }, t0 + 6 * DAY);
  assert.equal(sweep(t0 + 11 * DAY), 2); // 5|old.mkv и m9
  const left = changesSince(U, 0, t0 + 11 * DAY);
  assert.deepEqual(left.watch.map(w => w.key), ["5|new.mkv"]);
  assert.deepEqual(left.tracks.map(t => t.key).sort(), ["*", "m5"]);
  assert.equal(sweep(t0 + 17 * DAY), 3);
  assert.equal(changesSince(U, 0, t0 + 17 * DAY).watch.length, 0);
  // Устаревшее не принимается вовсе (первая выгрузка старого прогресса с клиента)
  assert.equal(apply(U, { watch: [{ key: "5|x.mkv", pos: 1, len: 0, at: t0 }] }, t0 + 17 * DAY).watch, 0);
});

test("sync: a removed torrent leaves the account", () => {
  _reset();
  const now = 1_000 * DAY;
  apply(U, {
    watch: [{ key: "3|a.mkv", pos: 1, len: 0, at: now }, { key: "33|a.mkv", pos: 1, len: 0, at: now }],
    tracks: [{ key: "m3", audio: "A", at: now }]
  }, now);
  dropMagnet(3);
  const left = changesSince(U, 0, now);
  assert.deepEqual(left.watch.map(w => w.key), ["33|a.mkv"]);
  assert.equal(left.tracks.length, 0);
});

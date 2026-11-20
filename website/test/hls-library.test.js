"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-hls-lib-test-"));
process.env.SESSION_SECRET = "test-session-secret-do-not-use";
process.env.HLS_ENABLED = "true";
process.env.AUTO_CLEAN = "false";

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("crypto");

// Ответ AllDebrid magnet/status подменяется: сеть в тестах не нужна
const axios = require("axios");
let magnets = [];
axios.post = async () => ({ status: 200, data: { status: "success", data: { magnets } } });

const { treeCache } = require("../src/services/alldebrid");
const { buildLibrary, libraryChanged } = require("../src/services/library");
const hls = require("../src/services/hls");
const store = require("../src/services/hls-store");

const HASH = "0123456789ABCDEF0123456789ABCDEF01234567";
const NOW = Date.parse("2026-11-19T12:00:00Z");
const fp = n => crypto.createHash("sha256").update(String(n)).digest("hex");

test("buildLibrary хранит hash и ставит видеофайлы при переходе в «готова»", async () => {
  magnets = [{ id: 412345678, filename: "Movie.2010.1080p", status: "Downloading", statusCode: 1, size: 10, hash: HASH }];
  const first = await buildLibrary();
  assert.equal(first.magnets[0].hash, HASH.toLowerCase());
  assert.equal(store.allJobs().size, 0, "первая загрузка — точка отсчёта");

  // раздача готова: дерево файлов уже в кэше (AllDebrid за ним не ходим)
  treeCache.set(412345678, [
    { path: "Movie.2010.1080p/Movie.2010.1080p.mkv", type: "file", size: 8589934592, sourceLink: "x" },
    { path: "Movie.2010.1080p/Sample/sample.mkv", type: "file", size: 1000, sourceLink: "y" },
    { path: "Movie.2010.1080p/rus.srt", type: "file", size: 10, sourceLink: "z" }
  ]);
  magnets = [{ id: 412345678, filename: "Movie.2010.1080p", status: "Ready", statusCode: 4, size: 10, hash: HASH }];
  libraryChanged();
  await buildLibrary();
  const jobs = [...store.allJobs().values()];
  assert.equal(jobs.length, 1);
  assert.equal(jobs[0].path, "Movie.2010.1080p/Movie.2010.1080p.mkv");
  assert.equal(jobs[0].priority, 2);
  assert.equal(jobs[0].hash, HASH.toLowerCase());
  assert.equal(jobs[0].hls_id, "178e77a7ddb10079", "hls_id по infohash — вектор vectors.json");

  libraryChanged();
  await buildLibrary();
  assert.equal(store.allJobs().size, 1, "уже готовая раздача повторно не ставится");
});

test("раздача без hash: hash null, hls_id по ad_id", async () => {
  magnets = [{ id: 7, filename: "X", status: "Ready", statusCode: 4, size: 1, hash: "" }];
  treeCache.set(7, [{ path: "X/x.mp4", type: "file", size: 100, sourceLink: "q" }]);
  libraryChanged();
  const lib = await buildLibrary();
  assert.equal(lib.magnets[0].hash, null);
  const job = store.jobForFile("7", "X/x.mp4");
  assert.equal(job.hls_id, hls.hlsId({ adId: "7", path: "X/x.mp4", size: 100 }));
});

test("готовая версия по ключу переиспользования — задание не ставится", () => {
  hls._reset();
  const cid = { canonical_id: "m:movie_2010:default", edition: "default" };
  const a = hls.enqueue({ ad_id: "1", path: "A/Movie.mkv", size: 10, priority: 2, ...cid }, NOW).job;
  hls.next(NOW);
  const r = hls.handleProbe(a.job_id, { fingerprint: fp(1), duration_ms: 7200000, format: {}, streams: [] }, NOW);
  hls.done({ job_id: a.job_id, hls_id: r.hls_id, profile: r.profile, manifest_sha256: "a".repeat(64), manifest: require("../../docs/hls-fixtures/manifest.json"), bytes: 1 }, NOW);
  // enqueueMagnet берёт canonical_id из «Определить через ИИ»: подменяем ответ подтверждённым
  const ai = require("../src/services/aititle");
  const orig = ai.resultFor;
  ai.resultFor = id => (String(id) === "2" ? { status: "accepted", ...cid } : null);
  try {
    assert.equal(hls.enqueueMagnet({ id: 2, files: [{ path: "B/Movie.mkv", type: "file", size: 20 }] }, 2, NOW), 0);
    assert.equal(store.jobForFile("2", "B/Movie.mkv"), null);
    // «Смотреть» (приоритет 0) ставит, probe потом даст alias
    const w = hls.enqueue({ ad_id: "2", path: "B/Movie.mkv", size: 20, priority: 0 }, NOW).job;
    assert.equal(w.canonical_id, cid.canonical_id);
    hls.next(NOW);
    assert.equal(hls.handleProbe(w.job_id, { fingerprint: fp(2), duration_ms: 7200500, format: {}, streams: [] }, NOW).action, "alias");
  } finally {
    ai.resultFor = orig;
  }
});

test("индексы строятся заново из файлов при старте", () => {
  hls._reset();
  const cid = { canonical_id: "s:show_s1:default", edition: "default" };
  const a = hls.enqueue({ ad_id: "1", path: "S/Show.S01E03.mkv", size: 10, priority: 2, ...cid }, NOW).job;
  const q = hls.enqueue({ ad_id: "1", path: "S/Show.S01E04.mkv", size: 10, priority: 3, ...cid }, NOW).job;
  hls.next(NOW);
  hls.handleProbe(a.job_id, { fingerprint: fp(5), duration_ms: 2700000, format: {}, streams: [] }, NOW);
  store.flush();
  for (const f of ["hls-assets.json", "hls-aliases.json", "hls-queue.json"]) {
    assert.ok(fs.existsSync(path.join(process.env.DATA_DIR, f)), f);
  }
  store._reset({ reload: true });
  assert.equal(store.assetByFingerprint(fp(5)).id, a.hls_id);
  assert.deepEqual(store.assetsByReuseKey("s:show_s1:default|default|S01E03"), [a.hls_id]);
  assert.equal(store.getAlias("1", "S/Show.S01E03.mkv").hls_id, a.hls_id);
  assert.deepEqual(store.aliasesOf(a.hls_id), ["1|S/Show.S01E03.mkv"]);
  assert.equal(store.jobForFile("1", "S/Show.S01E04.mkv").job_id, q.job_id);
  assert.equal(store.jobForHls(a.hls_id).job_id, a.job_id);
});

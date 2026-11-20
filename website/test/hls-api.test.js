"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-hls-api-test-"));
process.env.SESSION_SECRET = "test-session-secret-do-not-use";
process.env.HLS_ENABLED = "true";
process.env.HLS_CDN_URL = "https://cdn.example.org/some/path";
process.env.HLS_SIGN_SECRET = "test-secret-do-not-use";
process.env.HLS_WORKER_TOKEN = "worker-token-test";
process.env.HLS_CDN_TOKEN = "cdn-token-test";
process.env.AUTO_CLEAN = "false";
delete process.env.HLS_BETA_USERS;

const test = require("node:test");
const assert = require("node:assert/strict");

const config = require("../src/config");
const { createApp } = require("../src/app");
const { treeCache } = require("../src/services/alldebrid");
const users = require("../src/services/users");
const hls = require("../src/services/hls");
const store = require("../src/services/hls-store");
const manifest = require("../../docs/hls-fixtures/manifest.json");
const { pageCsp } = require("../src/services/precompressed");

let server;
let base;

test.before(async () => {
  server = createApp().listen(0, "127.0.0.1");
  await new Promise(resolve => server.once("listening", resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

test.after(() => {
  server.close();
});

async function call(method, url, { token, json, raw, headers = {} } = {}) {
  const h = { ...headers };
  if (token) h.Authorization = `Bearer ${token}`;
  let body;
  if (json !== undefined) {
    h["Content-Type"] = "application/json";
    body = JSON.stringify(json);
  } else if (raw !== undefined) {
    h["Content-Type"] = "application/json";
    body = raw;
  }
  const res = await fetch(`${base}${url}`, { method, headers: h, body });
  const text = await res.text();
  const ct = res.headers.get("content-type") || "";
  const parsed = ct.includes("application/json") && text ? JSON.parse(text) : null;
  return { status: res.status, headers: res.headers, text, json: parsed };
}

function deviceToken(discordId, role = "allow") {
  const user = users.findOrCreateUser({ id: discordId, username: "u" + discordId }, role);
  const pairing = users.createDevicePairing("HLS Tester");
  users.authorizeDevicePairing(pairing.user_code, user.id);
  return users.pollDevicePairing(pairing.code).device_token;
}

function probeBody(fpSeed) {
  return {
    fingerprint: crypto.createHash("sha256").update(String(fpSeed)).digest("hex"),
    duration_ms: 7951200,
    format: { bit_rate: 8000000 },
    streams: [
      {
        index: 0,
        codec_type: "video",
        codec_name: "hevc",
        pix_fmt: "yuv420p10le",
        width: 1920,
        height: 800,
        color_transfer: "smpte2084",
        keyframe_interval_max_s: 4
      },
      {
        index: 1,
        codec_type: "audio",
        codec_name: "dts",
        channels: 6
      }
    ]
  };
}

function makeReady(adId, filePath, size, fpSeed) {
  const { job } = hls.enqueue({ ad_id: String(adId), path: filePath, size, priority: 2 });
  job.state = "running";
  job.leased_until = Math.floor(Date.now() / 1000) + 600;
  store.putJob(job.job_id, job);
  const r = hls.handleProbe(job.job_id, probeBody(fpSeed));
  hls.done({
    job_id: job.job_id,
    hls_id: r.hls_id,
    profile: r.profile,
    manifest_sha256: "a".repeat(64),
    bytes: 1000,
    manifest
  });
  return r.hls_id;
}

test("internal/hls/next: выдача задачи с mirror_url, пропуск отсутствующих файлов и 204 на пустой очереди", async () => {
  treeCache.set(700001, [
    { name: "Film.mkv", path: "Film.mkv", type: "file", size: 5000000000, sourceLink: "https://alldebrid.com/f/700001" }
  ]);
  treeCache.set(700002, [
    { name: "Other.mkv", path: "Other.mkv", type: "file", size: 1000000000, sourceLink: "https://alldebrid.com/f/700002" }
  ]);

  const { job: missingJob } = hls.enqueue({ ad_id: "700002", path: "Missing.mkv", size: 1000, priority: 0 });
  const { job: okJob } = hls.enqueue({ ad_id: "700001", path: "Film.mkv", size: 5000000000, priority: 1 });

  const r1 = await call("GET", "/internal/hls/next", { token: config.HLS_WORKER_TOKEN });
  assert.equal(r1.status, 200);
  assert.equal(r1.json.job_id, okJob.job_id);
  assert.match(r1.json.mirror_url, /\/api\/file\/proxy\/.+\?link=.+&exp=\d+&sig=[0-9a-f]{64}/);
  assert.equal(store.getJob(missingJob.job_id).state, "failed");

  // Дожимаем очередь до 204
  for (let i = 0; i < 10; i++) {
    const r = await call("GET", "/internal/hls/next", { token: config.HLS_WORKER_TOKEN });
    if (r.status === 204) break;
  }
  const rEmpty = await call("GET", "/internal/hls/next", { token: config.HLS_WORKER_TOKEN });
  assert.equal(rEmpty.status, 204);
});

test("internal/hls: авторизация, ошибки, progress (wait), failed, evicted, cdn-stats", async () => {
  // Без токена и с чужим токеном — 401
  const rNoAuth = await call("GET", "/internal/hls/next");
  assert.equal(rNoAuth.status, 401);
  assert.equal(rNoAuth.json.code, "unauthorized");

  const rWrongWorker = await call("GET", "/internal/hls/next", { token: config.HLS_CDN_TOKEN });
  assert.equal(rWrongWorker.status, 401);

  const rWrongCdn = await call("GET", "/internal/hls/pending-deletes", { token: config.HLS_WORKER_TOKEN });
  assert.equal(rWrongCdn.status, 401);

  // Ненастроенный токен — 503
  const savedWorkerToken = config.HLS_WORKER_TOKEN;
  config.HLS_WORKER_TOKEN = "";
  try {
    const rUnconf = await call("GET", "/internal/hls/next", { token: savedWorkerToken });
    assert.equal(rUnconf.status, 503);
    assert.equal(rUnconf.json.code, "not_configured");
  } finally {
    config.HLS_WORKER_TOKEN = savedWorkerToken;
  }

  // Неизвестный job_id — 404 unknown_job
  const rProbeUnk = await call("POST", "/internal/hls/probe", {
    token: config.HLS_WORKER_TOKEN,
    json: { job_id: "no-such-job", ...probeBody("unk") }
  });
  assert.equal(rProbeUnk.status, 404);
  assert.equal(rProbeUnk.json.code, "unknown_job");

  const rProgUnk = await call("POST", "/internal/hls/progress", {
    token: config.HLS_WORKER_TOKEN,
    json: { job_id: "no-such-job", stage: "encode", progress: 10, eta_s: 5 }
  });
  assert.equal(rProgUnk.status, 404);
  assert.equal(rProgUnk.json.code, "unknown_job");

  // progress со stage "wait" и failed для взятой в работу задачи
  treeCache.set(700003, [
    { name: "Wait.mkv", path: "Wait.mkv", type: "file", size: 2000000000, sourceLink: "https://alldebrid.com/f/700003" }
  ]);
  const { job } = hls.enqueue({ ad_id: "700003", path: "Wait.mkv", size: 2000000000, priority: 0 });
  const rNext = await call("GET", "/internal/hls/next", { token: config.HLS_WORKER_TOKEN });
  assert.equal(rNext.status, 200);
  assert.equal(rNext.json.job_id, job.job_id);

  const rWait = await call("POST", "/internal/hls/progress", {
    token: config.HLS_WORKER_TOKEN,
    json: { job_id: job.job_id, stage: "wait", progress: 0, eta_s: 30 }
  });
  assert.equal(rWait.status, 200);
  assert.equal(typeof rWait.json.leased_until, "number");

  const rFail = await call("POST", "/internal/hls/failed", {
    token: config.HLS_WORKER_TOKEN,
    json: { job_id: job.job_id, stage: "encode", error: "boom", retryable: false }
  });
  assert.equal(rFail.status, 200);
  assert.equal(rFail.json.ok, true);
  assert.equal(rFail.json.state, "failed");

  const rFailUnk = await call("POST", "/internal/hls/failed", {
    token: config.HLS_WORKER_TOKEN,
    json: { job_id: "no-such-job", stage: "encode", error: "x", retryable: false }
  });
  assert.equal(rFailUnk.status, 404);

  // evicted неизвестного и cdn-stats
  const rEvict = await call("POST", "/internal/hls/evicted", {
    token: config.HLS_CDN_TOKEN,
    json: [{ hls_id: "ffffffffffffffff", profile: "v1", last_access: 0 }]
  });
  assert.equal(rEvict.status, 200);
  assert.equal(rEvict.json.removed, 0);

  const rStats = await call("POST", "/internal/hls/cdn-stats", {
    token: config.HLS_CDN_TOKEN,
    json: { disk_total: 1000, disk_used: 100 }
  });
  assert.equal(rStats.status, 200);
  assert.equal(rStats.json.ok, true);
});

test("ограничение размера JSON-тела: 256kb на /internal/hls и 20kb на обычном API", async () => {
  const r100k = await call("POST", "/internal/hls/cdn-stats", {
    token: config.HLS_CDN_TOKEN,
    json: { disk_total: 1, pad: "x".repeat(100 * 1024) }
  });
  assert.equal(r100k.status, 200);

  const r300k = await call("POST", "/internal/hls/cdn-stats", {
    token: config.HLS_CDN_TOKEN,
    json: { disk_total: 1, pad: "x".repeat(300 * 1024) }
  });
  assert.equal(r300k.status, 413);

  const tok = deviceToken("hls_limit_u1", "allow");
  const r30k = await call("POST", "/api/file/variant-url", {
    headers: { "X-Device-Token": tok },
    json: { ad_id: 700001, path: "x".repeat(30 * 1024) }
  });
  assert.equal(r30k.status, 413);
});

test("GET /api/hls/:id/master.m3u8: подпись, срок, 404 и содержимое плейлиста", async () => {
  const hlsId = makeReady(700004, "Master.mkv", 4000000000, "fp-master-700004");
  const now = Math.floor(Date.now() / 1000);
  const exp = now + hls.MASTER_TTL_S;
  const sig = hls.signMaster(hlsId, exp);

  const rBadSig = await call("GET", `/api/hls/${hlsId}/master.m3u8?exp=${exp}&sig=${"0".repeat(64)}`);
  assert.equal(rBadSig.status, 403);

  const expPast = now - 100;
  const sigPast = hls.signMaster(hlsId, expPast);
  const rExpired = await call("GET", `/api/hls/${hlsId}/master.m3u8?exp=${expPast}&sig=${sigPast}`);
  assert.equal(rExpired.status, 410);

  const missingId = "0123456789abcdef";
  const sigMissing = hls.signMaster(missingId, exp);
  const rMissing = await call("GET", `/api/hls/${missingId}/master.m3u8?exp=${exp}&sig=${sigMissing}`);
  assert.equal(rMissing.status, 404);

  const rOk = await call("GET", `/api/hls/${hlsId}/master.m3u8?exp=${exp}&sig=${sig}`);
  assert.equal(rOk.status, 200);
  assert.match(rOk.headers.get("content-type") || "", /application\/vnd\.apple\.mpegurl/);
  assert.equal(rOk.headers.get("cache-control"), "no-store");
  assert.equal(rOk.text.startsWith("#EXTM3U"), true);

  const audLines = rOk.text.split("\n").filter(l => l.startsWith('#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud"'));
  assert.equal(audLines.length, 2);
  const subLines = rOk.text.split("\n").filter(l => l.includes('TYPE=SUBTITLES,GROUP-ID="sub"'));
  assert.equal(subLines.length, 1);
  const streamLines = rOk.text.split("\n").filter(l => l.startsWith("#EXT-X-STREAM-INF:"));
  assert.equal(streamLines.length, 2);
  assert.match(streamLines[0], /CODECS="hvc1\.1\.6\.L120\.90,mp4a\.40\.2"/);
  assert.match(streamLines[0], /RESOLUTION=1920x800/);
  assert.match(streamLines[1], /CODECS="hvc1\.1\.6\.L93\.90,mp4a\.40\.2"/);
  assert.match(streamLines[1], /RESOLUTION=1280x534/);
  for (const line of streamLines) {
    assert.match(line, /BANDWIDTH=\d+,AVERAGE-BANDWIDTH=\d+/);
    assert.match(line, /FRAME-RATE=23\.976/);
    assert.match(line, /VIDEO-RANGE=SDR/);
    assert.match(line, /AUDIO="aud",SUBTITLES="sub"/);
  }
  assert.match(audLines[0], /NAME="Русский \(Дубляж\)",LANGUAGE="ru",DEFAULT=YES/);
  assert.match(audLines[1], /NAME="English",LANGUAGE="en",DEFAULT=NO/);
  assert.match(
    rOk.text,
    new RegExp(`https://cdn\\.example\\.org/some/path/hls/${hls.signCdn(exp, hlsId)}/${exp}/${hlsId}/v1/1080p/index\\.m3u8`)
  );
  assert.equal(store.getAsset(hlsId).last_access >= now - 5, true);
});

test("POST /api/file/variant-url: постановка в очередь, серии, прогресс, ready, кэш и бета-доступ", async () => {
  const tok = deviceToken("hls_var_u1", "allow");
  const auth = { "X-Device-Token": tok };

  treeCache.set(700005, [
    { name: "Disk.iso", path: "Disk.iso", type: "file", size: 4000000000, sourceLink: "https://alldebrid.com/f/iso5" },
    { name: "Movie.mkv", path: "Movie.mkv", type: "file", size: 3000000000, sourceLink: "https://alldebrid.com/f/mov5" }
  ]);

  // Не-видео .iso не ставится в очередь
  const rIso = await call("POST", "/api/file/variant-url", {
    headers: auth,
    json: { ad_id: 700005, path: "Disk.iso" }
  });
  assert.equal(rIso.status, 200);
  assert.equal(rIso.json.hls.status, "none");
  assert.equal(typeof rIso.json.hls.direct_ok, "boolean");
  assert.equal(rIso.json.hls.direct.ext, "iso");
  assert.equal(store.jobForFile("700005", "Disk.iso"), null);

  // Свежий .mkv ставится с priority 0
  const rMov1 = await call("POST", "/api/file/variant-url", {
    headers: auth,
    json: { ad_id: 700005, path: "Movie.mkv" }
  });
  assert.equal(rMov1.status, 200);
  assert.equal(rMov1.json.hls.status, "encoding");
  assert.equal(rMov1.json.hls.progress, 0);
  const movJob = store.jobForFile("700005", "Movie.mkv");
  assert.equal(movJob.priority, 0);

  // Прогресс отображается в variant-url
  movJob.state = "running";
  movJob.leased_until = Math.floor(Date.now() / 1000) + 600;
  store.putJob(movJob.job_id, movJob);
  hls.progress({ job_id: movJob.job_id, stage: "encode", progress: 0.42, eta_s: 120 });

  const rMovProg = await call("POST", "/api/file/variant-url", {
    headers: auth,
    json: { ad_id: 700005, path: "Movie.mkv" }
  });
  assert.equal(rMovProg.status, 200);
  assert.equal(rMovProg.json.hls.progress, 0.42);
  assert.equal(rMovProg.json.hls.eta, 120);

  // Подпись не кэшируется: url mirror одинаковый, но после ready статус меняется на ready
  const rProbe = hls.handleProbe(movJob.job_id, probeBody("fp-mov-700005"));
  hls.done({
    job_id: movJob.job_id,
    hls_id: rProbe.hls_id,
    profile: rProbe.profile,
    manifest_sha256: "b".repeat(64),
    bytes: 1000,
    manifest
  });

  const rMovReady = await call("POST", "/api/file/variant-url", {
    headers: auth,
    json: { ad_id: 700005, path: "Movie.mkv" }
  });
  assert.equal(rMovReady.status, 200);
  assert.equal(rMovReady.json.url, rMov1.json.url);
  assert.equal(rMovReady.json.hls.status, "ready");
  assert.match(rMovReady.json.hls.url, /\/api\/hls\/[0-9a-f]{16}\/master\.m3u8\?exp=\d+&sig=[0-9a-f]{64}$/);
  assert.equal(rMovReady.json.hls.profile, "v1");
  assert.equal(rMovReady.json.hls.shift_ms, 0);

  const masterUrl = new URL(rMovReady.json.hls.url);
  const rMaster = await call("GET", masterUrl.pathname + masterUrl.search);
  assert.equal(rMaster.status, 200);

  // Выключенный HLS на том же закэшированном запросе не возвращает поле hls
  config.HLS_ENABLED = false;
  try {
    const rDisabled = await call("POST", "/api/file/variant-url", {
      headers: auth,
      json: { ad_id: 700005, path: "Movie.mkv" }
    });
    assert.equal(rDisabled.status, 200);
    assert.equal("hls" in rDisabled.json, false);
  } finally {
    config.HLS_ENABLED = true;
  }

  // Бета-список пользователей
  config.HLS_BETA_USERS = ["u_someone_else"];
  try {
    const rBeta = await call("POST", "/api/file/variant-url", {
      headers: auth,
      json: { ad_id: 700005, path: "Movie.mkv" }
    });
    assert.equal(rBeta.status, 200);
    assert.equal("hls" in rBeta.json, false);
  } finally {
    config.HLS_BETA_USERS = [];
  }

  // Сериал: E01 -> priority 0, E02 и E03 -> priority 1, E04 -> нет задачи
  treeCache.set(700006, [
    { name: "Show.S01E01.mkv", path: "Show.S01/Show.S01E01.mkv", type: "file", size: 1000000000, sourceLink: "https://alldebrid.com/f/e01" },
    { name: "Show.S01E02.mkv", path: "Show.S01/Show.S01E02.mkv", type: "file", size: 1000000000, sourceLink: "https://alldebrid.com/f/e02" },
    { name: "Show.S01E03.mkv", path: "Show.S01/Show.S01E03.mkv", type: "file", size: 1000000000, sourceLink: "https://alldebrid.com/f/e03" },
    { name: "Show.S01E04.mkv", path: "Show.S01/Show.S01E04.mkv", type: "file", size: 1000000000, sourceLink: "https://alldebrid.com/f/e04" }
  ]);
  const rEp1 = await call("POST", "/api/file/variant-url", {
    headers: auth,
    json: { ad_id: 700006, path: "Show.S01/Show.S01E01.mkv" }
  });
  assert.equal(rEp1.status, 200);
  assert.equal(store.jobForFile("700006", "Show.S01/Show.S01E01.mkv").priority, 0);
  assert.equal(store.jobForFile("700006", "Show.S01/Show.S01E02.mkv").priority, 1);
  assert.equal(store.jobForFile("700006", "Show.S01/Show.S01E03.mkv").priority, 1);
  assert.equal(store.jobForFile("700006", "Show.S01/Show.S01E04.mkv"), null);
});

test("Admin API: state, retry, delete -> pending-deletes -> evicted, reencode", async () => {
  const userTok = deviceToken("hls_adm_regular", "allow");
  const adminTok = deviceToken("hls_adm_boss", "admin");

  assert.equal((await call("GET", "/api/hls/admin/state")).status, 401);
  assert.equal((await call("GET", "/api/hls/admin/state", { headers: { "X-Device-Token": userTok } })).status, 403);

  const rState = await call("GET", "/api/hls/admin/state", { headers: { "X-Device-Token": adminTok } });
  assert.equal(rState.status, 200);
  assert.equal(rState.json.enabled, true);
  assert.equal(typeof rState.json.totals, "object");
  assert.equal(Array.isArray(rState.json.queue), true);
  assert.equal(Array.isArray(rState.json.assets), true);
  assert.equal(typeof rState.json.cdn, "object");

  // retry: неизвестный 404, упавший -> queued с attempts 0
  const rRetryUnk = await call("POST", "/api/hls/admin/retry", {
    headers: { "X-Device-Token": adminTok },
    json: { job_id: "no-such-job" }
  });
  assert.equal(rRetryUnk.status, 404);

  const { job: failJob } = hls.enqueue({ ad_id: "700007", path: "Retry.mkv", size: 1000, priority: 0 });
  failJob.state = "failed";
  failJob.attempts = 3;
  store.putJob(failJob.job_id, failJob);

  const rRetry = await call("POST", "/api/hls/admin/retry", {
    headers: { "X-Device-Token": adminTok },
    json: { job_id: failJob.job_id }
  });
  assert.equal(rRetry.status, 200);
  assert.equal(rRetry.json.ok, true);
  assert.equal(store.getJob(failJob.job_id).state, "queued");
  assert.equal(store.getJob(failJob.job_id).attempts, 0);

  // delete -> pending-deletes -> evicted
  const delId = makeReady(700008, "Delete.mkv", 2000000000, "fp-del-700008");
  const rDel = await call("POST", "/api/hls/admin/delete", {
    headers: { "X-Device-Token": adminTok },
    json: { hls_id: delId, profile: "v1" }
  });
  assert.equal(rDel.status, 200);
  assert.deepEqual(rDel.json.pending_delete, ["v1"]);

  const rPending = await call("GET", "/internal/hls/pending-deletes", { token: config.HLS_CDN_TOKEN });
  assert.equal(rPending.status, 200);
  assert.equal(rPending.json.items.some(x => x.hls_id === delId && x.profile === "v1"), true);

  const rEvicted = await call("POST", "/internal/hls/evicted", {
    token: config.HLS_CDN_TOKEN,
    json: [{ hls_id: delId, profile: "v1", last_access: 0 }]
  });
  assert.equal(rEvicted.status, 200);
  assert.equal(rEvicted.json.removed, 1);
  assert.equal(store.getAsset(delId), null);

  // reencode для уже готового файла
  treeCache.set(700009, [
    { name: "Reenc.mkv", path: "Reenc.mkv", type: "file", size: 2000000000, sourceLink: "https://alldebrid.com/f/700009" }
  ]);
  makeReady(700009, "Reenc.mkv", 2000000000, "fp-reenc-700009");

  const rReenc = await call("POST", "/api/hls/admin/reencode", {
    headers: { "X-Device-Token": adminTok },
    json: { ad_id: 700009, path: "Reenc.mkv" }
  });
  assert.equal(rReenc.status, 200);
  assert.equal(rReenc.json.ok, true);
  assert.equal(rReenc.json.job.priority, 0);
  assert.equal(rReenc.json.job.profile, "v1");
  assert.equal(store.getJob(rReenc.json.job.job_id).force, true);
});

test("CSP: origin CDN без пути и статика /vendor/hls.min.js", async () => {
  const csp = pageCsp("<html></html>");
  assert.equal(csp.includes("connect-src 'self' ws: wss: https://cdn.example.org"), true);
  assert.equal(csp.includes("media-src 'self' blob: https://cdn.example.org"), true);
  assert.equal(csp.includes("worker-src 'self' blob:"), true);
  assert.equal(csp.includes("/some/path"), false);

  const savedCdn = config.HLS_CDN_URL;
  config.HLS_CDN_URL = "";
  try {
    const cspNoCdn = pageCsp("<html></html>");
    assert.equal(cspNoCdn.includes("cdn.example.org"), false);
    assert.equal(cspNoCdn.includes("worker-src 'self' blob:"), true);
  } finally {
    config.HLS_CDN_URL = savedCdn;
  }

  const rStatic = await call("GET", "/vendor/hls.min.js");
  assert.equal(rStatic.status, 200);
  assert.match(rStatic.headers.get("content-type") || "", /javascript/);
});

test("direct_ok: кодеки звука из media-tags.json (meta.acodecs) важнее меток", () => {
  const { audioCodecsFromStreams } = require("../src/services/mediatags");
  assert.deepEqual(
    audioCodecsFromStreams([{ codec_type: "video", codec_name: "h264" }, { codec_type: "audio", codec_name: "AAC" }, { codec_type: "audio", codec_name: "ac3" }, { codec_type: "audio", codec_name: "aac" }]),
    ["aac", "ac3"]
  );
  const entry = acodecs => ({ t: [], meta: { codec: "h264", acodecs }, at: 1 });
  // метки пустые (AC3 меток не даёт), но звук AC3 — напрямую нельзя
  assert.deepEqual(hls.computeDirectOk("Clip/Clip.mp4", entry(["aac", "ac3"])), { direct_ok: false, direct: { ext: "mp4", vcodec: "h264", acodec: "ac3" } });
  assert.equal(hls.computeDirectOk("Clip/Clip.mp4", entry(["eac3"])).direct.acodec, "eac3");
  assert.equal(hls.computeDirectOk("Clip/Clip.mp4", entry(["truehd"])).direct_ok, false);
  assert.deepEqual(hls.computeDirectOk("Clip/Clip.mp4", entry(["aac"])), { direct_ok: true, direct: { ext: "mp4", vcodec: "h264", acodec: "aac" } });
  assert.equal(hls.computeDirectOk("Clip/Clip.webm", { t: [], meta: { codec: "vp9", acodecs: ["opus"] } }).direct_ok, true);
  // старая запись без acodecs — как раньше, по меткам
  assert.equal(hls.computeDirectOk("Clip/Clip.mp4", { t: [], meta: { codec: "h264" } }).direct_ok, true);
  assert.equal(hls.computeDirectOk("Clip/Clip.mp4", { t: ["DTS-HD MA"], meta: { codec: "h264" } }).direct.acodec, "dts");
});

test("probe: dovi:true от воркера → v1, без него — v1-copy", () => {
  const body = dovi => ({
    duration_ms: 3600000,
    format: { bit_rate: 6000000 },
    streams: [{ codec_type: "video", codec_name: "hevc", pix_fmt: "yuv420p", width: 1920, height: 1080, color_transfer: "bt709", keyframe_interval_max_s: 4, dovi }]
  });
  assert.equal(hls.decideProfile(body(false)), "v1-copy");
  assert.equal(hls.decideProfile(body(true)), "v1");
});

test("done: без manifest — ошибка bad_manifest; master строится строго по manifest.video (720p, avc1, без субтитров)", () => {
  const { job } = hls.enqueue({ ad_id: "700020", path: "Small/Small.mp4", size: 500000000, priority: 2 });
  job.state = "running";
  job.leased_until = Math.floor(Date.now() / 1000) + 600;
  store.putJob(job.job_id, job);
  const r = hls.handleProbe(job.job_id, probeBody("fp-small-700020"));
  assert.throws(() => hls.done({ job_id: job.job_id, hls_id: r.hls_id, profile: r.profile, bytes: 1 }), e => e.code === "bad_manifest" && e.status === 400);
  const man = {
    duration_ms: 3600000,
    encoder: "copy",
    video: [{ dir: "720p", codec: "avc1.64001f", width: 1280, height: 720, bandwidth: 2500000, fps: 25 }],
    audio: [{ dir: "a0", codec: "mp4a.40.2", channels: 2, language: "ru", name: 'Рус "дубляж"', default: false }],
    subtitles: []
  };
  hls.done({ job_id: job.job_id, hls_id: r.hls_id, profile: r.profile, bytes: 1, manifest: man });
  const exp = Math.floor(Date.now() / 1000) + 3600;
  const text = hls.masterPlaylist(r.hls_id, exp);
  const inf = text.split("\n").filter(l => l.startsWith("#EXT-X-STREAM-INF:"));
  assert.equal(inf.length, 1);
  assert.equal(inf[0], '#EXT-X-STREAM-INF:BANDWIDTH=2500000,CODECS="avc1.64001f,mp4a.40.2",RESOLUTION=1280x720,FRAME-RATE=25,VIDEO-RANGE=SDR,AUDIO="aud"');
  assert.match(text, /\/v1\/720p\/index\.m3u8\n$/);
  assert.equal(text.includes("TYPE=SUBTITLES"), false);
  // единственная дорожка без default всё равно DEFAULT=YES; кавычки в названии не ломают атрибут
  assert.match(text, /NAME="Рус дубляж",LANGUAGE="ru",DEFAULT=YES/);
});

test("reencode: после done алиасы старого ассета переходят на новый, старый — в pending_delete", () => {
  const oldId = makeReady(700021, "Old/Film.mkv", 3000000000, "fp-old-700021");
  store.setAlias("700022", "Other/Film.mkv", oldId, 500);
  const { job, replaces } = hls.reencode({ ad_id: "700023", path: "Better/Film.mkv", size: 4000000000, replaces: oldId });
  assert.equal(replaces, oldId);
  const live = store.getJob(job.job_id);
  live.state = "running";
  live.leased_until = Math.floor(Date.now() / 1000) + 600;
  store.putJob(job.job_id, live);
  // тот же отпечаток, что у старого ассета, не даёт alias: принудительно encode v1
  const p = hls.handleProbe(job.job_id, { ...probeBody("fp-new-700023"), duration_ms: 7951200 + 2000 });
  assert.deepEqual(p, { action: "encode", hls_id: job.hls_id, profile: "v1" });
  hls.done({ job_id: job.job_id, hls_id: p.hls_id, profile: "v1", bytes: 5, manifest });
  assert.equal(store.getAlias("700022", "Other/Film.mkv").hls_id, p.hls_id);
  assert.equal(store.getAlias("700022", "Other/Film.mkv").shift_ms, 2500);
  assert.equal(store.getAlias("700021", "Old/Film.mkv").hls_id, p.hls_id);
  assert.equal(store.getAlias("700023", "Better/Film.mkv").shift_ms, 0);
  const old = store.getAsset(oldId);
  assert.equal(old.active_profile, null);
  assert.deepEqual(old.pending_delete, ["v1"]);
  assert.equal(hls.statusFor("700022", "Other/Film.mkv").status, "ready");
});

test("HLS_SIGN_SECRET: допустимые символы для nginx", () => {
  assert.equal(config.HLS_SECRET_RE.test("Abc-123._~+/="), true);
  assert.equal(config.HLS_SECRET_RE.test("bad secret"), false);
  assert.equal(config.HLS_SECRET_RE.test('bad"q'), false);
});

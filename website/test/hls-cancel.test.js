"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-hls-cancel-test-"));
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
const users = require("../src/services/users");
const hls = require("../src/services/hls");
const hlsAudio = require("../src/services/hls-audio");
const store = require("../src/services/hls-store");
const manifest = require("../../docs/hls-fixtures/manifest.json");
const ui = require("../public/hls-admin.js");

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

function leaseAndProbe(adId, filePath, fpSeed) {
  const { job } = hls.enqueue({ ad_id: String(adId), path: filePath, size: 2000000000, priority: 2 });
  job.state = "running";
  job.leased_until = Math.floor(Date.now() / 1000) + 600;
  store.putJob(job.job_id, job);
  const r = hls.handleProbe(job.job_id, probeBody(fpSeed));
  return { job_id: job.job_id, hls_id: r.hls_id, profile: r.profile };
}

test("права: без входа 401, не админ 403 forbidden для cancel и clear", async () => {
  const userTok = deviceToken("cancel-allow-1", "allow");

  const routes = [
    ["POST", "/api/hls/admin/cancel", { job_id: "x" }],
    ["POST", "/api/hls/admin/clear", {}]
  ];

  for (const [method, url, json] of routes) {
    const rAnon = await call(method, url, { json });
    assert.equal(rAnon.status, 401, `${method} ${url} без входа`);

    const rUser = await call(method, url, { headers: { "X-Device-Token": userTok }, json });
    assert.equal(rUser.status, 403, `${method} ${url} не админ`);
    assert.equal(rUser.json.code, "forbidden");
  }
});

test("отмена задания в очереди: убирает из очереди, повторно 404 unknown_job", async () => {
  const adminTok = deviceToken("cancel-admin-2", "admin");
  const { job } = hls.enqueue({ ad_id: "720002", path: "Queued.mkv", size: 2000000000, priority: 2 });

  const r1 = await call("POST", "/api/hls/admin/cancel", {
    headers: { "X-Device-Token": adminTok },
    json: { job_id: job.job_id }
  });
  assert.equal(r1.status, 200);
  assert.equal(r1.json.ok, true);
  assert.equal(r1.json.cancelled, "queued");
  assert.equal(store.getJob(job.job_id), null);

  const r2 = await call("POST", "/api/hls/admin/cancel", {
    headers: { "X-Device-Token": adminTok },
    json: { job_id: job.job_id }
  });
  assert.equal(r2.status, 404);
  assert.equal(r2.json.code, "unknown_job");
  assert.equal(ui.errorText(404, r2.json), "Задания уже нет.");
});

test("отмена упавшего задания: убирает как queued и не помечает отменённым", async () => {
  const adminTok = deviceToken("cancel-admin-3", "admin");
  const { job } = hls.enqueue({ ad_id: "720003", path: "Failed.mkv", size: 2000000000, priority: 2 });
  job.state = "failed";
  store.putJob(job.job_id, job);

  const r = await call("POST", "/api/hls/admin/cancel", {
    headers: { "X-Device-Token": adminTok },
    json: { job_id: job.job_id }
  });
  assert.equal(r.status, 200);
  assert.equal(r.json.ok, true);
  assert.equal(r.json.cancelled, "queued");
  assert.equal(store.getJob(job.job_id), null);
  assert.equal(hls.isCancelled(job.job_id), false);
});

test("отмена выполняющегося задания: удаляет пустой ассет и возвращает 410 воркеру", async () => {
  const adminTok = deviceToken("cancel-admin-4", "admin");
  const { job_id, hls_id, profile } = leaseAndProbe(720004, "Running.mkv", "fp-cancel-720004");

  const rCancel = await call("POST", "/api/hls/admin/cancel", {
    headers: { "X-Device-Token": adminTok },
    json: { job_id }
  });
  assert.equal(rCancel.status, 200);
  assert.equal(rCancel.json.ok, true);
  assert.equal(rCancel.json.cancelled, "running");
  assert.equal(store.getJob(job_id), null);
  assert.equal(store.getAsset(hls_id), null);
  assert.equal(store.getAlias("720004", "Running.mkv"), null);

  const workerHeaders = { token: config.HLS_WORKER_TOKEN };

  const rProg = await call("POST", "/internal/hls/progress", {
    ...workerHeaders,
    json: { job_id, stage: "encode", progress: 0.5, eta_s: 10 }
  });
  assert.equal(rProg.status, 410);
  assert.equal(rProg.json.code, "cancelled");
  assert.equal(rProg.json.error, "Задание отменено на сайте.");

  const rDone = await call("POST", "/internal/hls/done", {
    ...workerHeaders,
    json: { job_id, hls_id, profile, manifest_sha256: "a".repeat(64), bytes: 1, manifest }
  });
  assert.equal(rDone.status, 410);
  assert.equal(rDone.json.code, "cancelled");

  const rProbe = await call("POST", "/internal/hls/probe", {
    ...workerHeaders,
    json: { job_id, ...probeBody("x") }
  });
  assert.equal(rProbe.status, 410);
  assert.equal(rProbe.json.code, "cancelled");

  const rFail = await call("POST", "/internal/hls/failed", {
    ...workerHeaders,
    json: { job_id, stage: "encode", error: "x", retryable: true }
  });
  assert.equal(rFail.status, 410);
  assert.equal(rFail.json.code, "cancelled");

  const rAudio = await call("POST", "/internal/hls/audio-done", {
    ...workerHeaders,
    json: { job_id, hls_id, profile, tracks: [] }
  });
  assert.equal(rAudio.status, 410);
  assert.equal(rAudio.json.code, "cancelled");

  assert.equal(store.getAsset(hls_id), null);
  assert.equal(hls.isCancelled(job_id, Date.now() + 2 * 3600 * 1000 + 1000), false);

  const rNope = await call("POST", "/internal/hls/progress", {
    ...workerHeaders,
    json: { job_id: "j_nope", stage: "encode", progress: 0.5, eta_s: 10 }
  });
  assert.equal(rNope.status, 404);
  assert.equal(rNope.json.code, "unknown_job");
});

test("отмена v1 не трогает готовую v1-copy и алиас", async () => {
  const adminTok = deviceToken("cancel-admin-5", "admin");
  // 8-битный h264 без HDR — сначала v1-copy, после done сервер сам ставит v1 (приоритет 4)
  const copyBody = probeBody("fp-cancel-720005");
  Object.assign(copyBody.streams[0], { codec_name: "h264", pix_fmt: "yuv420p", color_transfer: "bt709" });
  const { job: first } = hls.enqueue({ ad_id: "720005", path: "Ready.mkv", size: 2000000000, priority: 2 });
  first.state = "running";
  first.leased_until = Math.floor(Date.now() / 1000) + 600;
  store.putJob(first.job_id, first);
  const r = hls.handleProbe(first.job_id, copyBody);
  assert.equal(r.profile, "v1-copy");
  hls.done({ job_id: first.job_id, hls_id: r.hls_id, profile: "v1-copy", manifest_sha256: "a".repeat(64), bytes: 1000, manifest });
  const id = r.hls_id;

  const job = [...store.allJobs().values()].find(j => j.hls_id === id && j.profile === "v1");
  assert.ok(job, "v1 в очереди после v1-copy");
  job.state = "running";
  job.leased_until = Math.floor(Date.now() / 1000) + 600;
  store.putJob(job.job_id, job);
  assert.equal(hls.handleProbe(job.job_id, copyBody).action, "encode");
  assert.equal(store.getAsset(id).profiles.v1.status, "encoding");

  const rCancel = await call("POST", "/api/hls/admin/cancel", {
    headers: { "X-Device-Token": adminTok },
    json: { job_id: job.job_id }
  });
  assert.equal(rCancel.status, 200);
  assert.equal(rCancel.json.cancelled, "running");

  const asset = store.getAsset(id);
  assert.ok(asset);
  assert.equal(asset.profiles.v1, undefined);
  assert.equal(asset.profiles["v1-copy"].status, "ready");
  assert.equal(asset.active_profile, "v1-copy");
  assert.equal(store.getAlias("720005", "Ready.mkv").hls_id, id);
});

test("очистка очереди: без running убирает только неактивные, с running — и текущее", async () => {
  const adminTok = deviceToken("cancel-admin-6", "admin");
  hls.clearQueue({ running: true });

  hls.enqueue({ ad_id: "720061", path: "Q1.mkv", size: 1000, priority: 2 });
  hls.enqueue({ ad_id: "720062", path: "Q2.mkv", size: 1000, priority: 2 });

  const { job: failedJob } = hls.enqueue({ ad_id: "720063", path: "F1.mkv", size: 1000, priority: 2 });
  failedJob.state = "failed";
  store.putJob(failedJob.job_id, failedJob);

  const { job: runningJob } = hls.enqueue({ ad_id: "720064", path: "R1.mkv", size: 1000, priority: 2 });
  runningJob.state = "running";
  runningJob.leased_until = Math.floor(Date.now() / 1000) + 600;
  store.putJob(runningJob.job_id, runningJob);

  const r1 = await call("POST", "/api/hls/admin/clear", {
    headers: { "X-Device-Token": adminTok },
    json: {}
  });
  assert.equal(r1.status, 200);
  assert.equal(r1.json.ok, true);
  assert.equal(r1.json.removed, 3);
  assert.ok(store.getJob(runningJob.job_id));

  const r2 = await call("POST", "/api/hls/admin/clear", {
    headers: { "X-Device-Token": adminTok },
    json: { running: true }
  });
  assert.equal(r2.status, 200);
  assert.equal(r2.json.ok, true);
  assert.equal(r2.json.removed, 1);
  assert.equal(store.getJob(runningJob.job_id), null);
  assert.equal(hls.isCancelled(runningJob.job_id), true);
});

test("отмена аудио-задания: освобождает папки, чистит манифест и ставит в pending_delete_paths", async () => {
  const adminTok = deviceToken("cancel-admin-7", "admin");
  const id = makeReady(720007, "Aud.mkv", 2000000000, "fp-cancel-720007");
  const profile = store.getAsset(id).active_profile;

  const a = store.getAsset(id);
  a.ext_audio = {
    "custom:trk_a": { dir: "x0", kind: "custom", name: "A", language: "ru", offset_ms: 0, src: { ad_id: "720007", path: "A.mka" } },
    "custom:trk_b": { dir: "x1", kind: "custom", name: "B", language: null, offset_ms: 0, src: { ad_id: "720007", path: "B.mka" } }
  };
  store.putAsset(id, a);

  const r = hlsAudio.enqueueAudio(id, profile, ["custom:trk_a", "custom:trk_b"]);
  const job = r.job;
  job.state = "running";
  job.leased_until = Math.floor(Date.now() / 1000) + 600;
  store.putJob(job.job_id, job);

  const rCancel = await call("POST", "/api/hls/admin/cancel", {
    headers: { "X-Device-Token": adminTok },
    json: { job_id: job.job_id }
  });
  assert.equal(rCancel.status, 200);
  assert.equal(rCancel.json.cancelled, "running");

  let asset = store.getAsset(id);
  assert.ok(!(asset.profiles[profile].manifest.audio || []).some(t => t.external));
  assert.equal(asset.ext_audio["custom:trk_a"], undefined);
  assert.equal(asset.ext_audio["custom:trk_b"], undefined);
  assert.ok(asset.pending_delete_paths.some(p => p.profile === profile && p.path === "x0"));
  assert.ok(asset.pending_delete_paths.some(p => p.profile === profile && p.path === "x1"));

  const rAudioDone = await call("POST", "/internal/hls/audio-done", {
    token: config.HLS_WORKER_TOKEN,
    json: {
      job_id: job.job_id,
      hls_id: id,
      profile,
      tracks: [{ key: "custom:trk_a", dir: "x0", codec: "mp4a.40.2", channels: 2, bytes: 10 }]
    }
  });
  assert.equal(rAudioDone.status, 410);

  asset = store.getAsset(id);
  assert.ok(!(asset.profiles[profile].manifest.audio || []).some(t => t.external));

  // Подслучай: аудио-задание в очереди (не running)
  asset.ext_audio["custom:trk_c"] = {
    dir: "x2",
    kind: "custom",
    name: "C",
    language: "en",
    offset_ms: 0,
    src: { ad_id: "720007", path: "C.mka" }
  };
  store.putAsset(id, asset);

  const rQueued = hlsAudio.enqueueAudio(id, profile, ["custom:trk_c"]);
  const rCancelQueued = await call("POST", "/api/hls/admin/cancel", {
    headers: { "X-Device-Token": adminTok },
    json: { job_id: rQueued.job.job_id }
  });
  assert.equal(rCancelQueued.status, 200);
  assert.equal(rCancelQueued.json.cancelled, "queued");

  asset = store.getAsset(id);
  assert.equal(asset.ext_audio["custom:trk_c"], undefined);
  assert.ok(!asset.pending_delete_paths.some(p => p.path === "x2"));
});

test("hls-admin.js и hls-admin.html: тексты отмены и очистки очереди", () => {
  assert.equal(ui.cancelLabel({ state: "running", leased_until: 200 }, 100), "Остановить");
  assert.equal(ui.cancelLabel({ state: "queued" }, 100), "Убрать");
  assert.equal(ui.cancelLabel({ state: "failed" }, 100), "Убрать");
  assert.equal(ui.cancelLabel({ state: "running", leased_until: 50 }, 100), "Убрать");

  assert.equal(
    ui.clearableCount([{ state: "queued" }, { state: "failed" }, { state: "running", leased_until: 200 }], 100),
    2
  );
  assert.match(ui.clearConfirmText(3), /3 задания/);
  assert.match(ui.clearConfirmText(5), /5 заданий/);
  assert.equal(ui.cancelConfirmText({ state: "queued", path: "a/F.mkv" }, 100), "Убрать из очереди «F.mkv»?");
  assert.ok(ui.cancelConfirmText({ state: "running", leased_until: 200, path: "F.mkv" }, 100).startsWith("Остановить кодирование «F.mkv»?"));

  const html = fs.readFileSync(path.join(__dirname, "../public/hls-admin.html"), "utf8");
  assert.ok(html.includes('id="clearQueue"'));
  assert.ok(html.includes("Очистить очередь"));
});

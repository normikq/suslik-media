"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-hls-extaudio-copy-test-"));
process.env.SESSION_SECRET = "test-session-secret-do-not-use";
process.env.HLS_ENABLED = "true";
process.env.HLS_CDN_URL = "https://cdn.example.org";
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
const hls = require("../src/services/hls");
const store = require("../src/services/hls-store");
const hlsAudio = require("../src/services/hls-audio");
const ca = require("../src/services/custom-audio");

const manifest = require("../../docs/hls-fixtures/manifest.json");

// Заглушки сетевых зависимостей: ничего не ходит в AllDebrid
hlsAudio.deps.fileList = async adId => treeCache.get(Number(adId)) || null;
hlsAudio.deps.customTracks = async (adId, videoPath) =>
  ca.forMovie(Number(adId)).filter(t => t.videoPath === videoPath);
hlsAudio.deps.companions = async () => [];

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

async function settle() {
  await hlsAudio.idle();
}

function audioJobs(id) {
  return [...store.allJobs().values()].filter(j => j.type === "audio" && (!id || j.hls_id === id));
}

function copyProbeBody(fpSeed) {
  return {
    fingerprint: crypto.createHash("sha256").update(String(fpSeed)).digest("hex"),
    duration_ms: 7951200,
    format: { bit_rate: 8000000 },
    streams: [
      {
        index: 0,
        codec_type: "video",
        codec_name: "h264",
        pix_fmt: "yuv420p",
        width: 1920,
        height: 800,
        keyframe_interval_max_s: 4
      },
      {
        index: 1,
        codec_type: "audio",
        codec_name: "aac",
        channels: 2
      }
    ]
  };
}

const copyManifest = {
  ...manifest,
  profile: "v1-copy",
  video: [{ ...manifest.video[0], codec: "avc1.640028" }]
};

function makeCopyReady(adId, filePath, fpSeed) {
  const { job } = hls.enqueue({ ad_id: String(adId), path: filePath, size: 1000, priority: 2 });
  job.state = "running";
  job.leased_until = Math.floor(Date.now() / 1000) + 600;
  store.putJob(job.job_id, job);
  const r = hls.handleProbe(job.job_id, copyProbeBody(fpSeed));
  assert.equal(r.profile, "v1-copy");
  hls.done({
    job_id: job.job_id,
    hls_id: r.hls_id,
    profile: "v1-copy",
    manifest_sha256: "a".repeat(64),
    bytes: 1000,
    manifest: copyManifest
  });
  return r.hls_id;
}

function v1Job(id) {
  return [...store.allJobs().values()].find(
    j => j.type !== "audio" && j.hls_id === id && j.profile === "v1"
  );
}

function finishV1(id) {
  const job = v1Job(id);
  assert.ok(job, "должно существовать видео-задание v1");
  return hls.done({
    job_id: job.job_id,
    hls_id: id,
    profile: "v1",
    manifest_sha256: "b".repeat(64),
    bytes: 2000,
    manifest
  });
}

function leaseAudio(id, profile) {
  for (const j of [...store.allJobs().values()]) {
    if (j.hls_id !== id) store.deleteJob(j.job_id);
  }
  const leasedNonAudio = [];
  let found = null;
  for (let i = 0; i < 10; i++) {
    const nextJob = hls.next();
    if (!nextJob) break;
    if (nextJob.type === "audio" && nextJob.hls_id === id && nextJob.profile === profile) {
      found = nextJob;
      break;
    }
    if (nextJob.type !== "audio") leasedNonAudio.push(nextJob);
  }
  for (const j of leasedNonAudio) {
    const cur = store.getJob(j.job_id);
    if (cur) {
      cur.state = "queued";
      cur.leased_until = 0;
      store.putJob(cur.job_id, cur);
    }
  }
  assert.ok(found, `должно быть выдано задание audio для ${profile}`);
  return found;
}

function audioDoneFor(job) {
  return hlsAudio.audioDone({
    job_id: job.job_id,
    hls_id: job.hls_id,
    profile: job.profile,
    tracks: job.tracks.map(t => ({
      key: t.key,
      dir: t.dir,
      codec: "mp4a.40.2",
      channels: 2,
      bytes: 100
    }))
  });
}

function extLines(id) {
  const exp = Math.floor(Date.now() / 1000) + 3600;
  return hls
    .masterPlaylist(id, exp)
    .split("\n")
    .filter(l => l.includes("TYPE=AUDIO") && /\/x[0-7]\/index\.m3u8/.test(l));
}

function setupMovie(adId, audioAdId, withCustom) {
  const videoPath = `Copy${adId}/Copy${adId}.mkv`;
  treeCache.set(adId, [
    { type: "file", path: videoPath, size: 1000, sourceLink: `https://alldebrid.com/f/v${adId}` },
    {
      type: "file",
      path: `Copy${adId}/Copy${adId}.LostFilm.mka`,
      size: 200,
      sourceLink: `https://alldebrid.com/f/lf${adId}`
    }
  ]);
  treeCache.set(audioAdId, [
    { type: "file", path: "Dub.mka", size: 200, sourceLink: `https://alldebrid.com/f/dub${audioAdId}` }
  ]);
  if (withCustom) {
    ca.addTracks({
      movieMagnetId: adId,
      audioMagnetId: audioAdId,
      pairs: [{ videoPath, audioPath: "Dub.mka", voice: "Dub", offsetMs: 500 }],
      author: "u_1",
      override: {}
    });
  }
  return videoPath;
}

test("v1-copy: после done — задание «звук» на все дорожки, audio-done → manifest и master", async () => {
  const videoPath = setupMovie(920001, 920002, true);
  const id = makeCopyReady(920001, videoPath, "fp-copy-920001");
  await settle();

  const jobs = audioJobs(id);
  assert.equal(jobs.length, 1);
  assert.equal(jobs[0].profile, "v1-copy");
  assert.equal(jobs[0].priority, 3);
  assert.equal(jobs[0].keys.length, 2);
  assert.ok(jobs[0].keys.some(k => k.startsWith("file:")));
  assert.ok(jobs[0].keys.some(k => k.startsWith("custom:")));

  const job = leaseAudio(id, "v1-copy");
  assert.equal(job.tracks.length, 2);
  const res = audioDoneFor(job);
  assert.equal(res.added, 2);

  const asset = store.getAsset(id);
  assert.equal(asset.profiles["v1-copy"].manifest.audio.filter(e => e.external).length, 2);
  assert.equal(asset.active_profile, "v1-copy");

  const lines = extLines(id);
  assert.equal(lines.length, 2);
  for (const l of lines) assert.ok(l.includes("/v1-copy/x"));

  assert.ok(v1Job(id));
});

test("v1-copy: озвучка добавлена после готовности — задание на v1-copy", async () => {
  const videoPath = setupMovie(920003, 920004, false);
  const id = makeCopyReady(920003, videoPath, "fp-copy-920003");
  await settle();

  for (const j of audioJobs(id)) store.deleteJob(j.job_id);

  ca.addTracks({
    movieMagnetId: 920003,
    audioMagnetId: 920004,
    pairs: [{ videoPath, audioPath: "Dub.mka", voice: "Dub", offsetMs: 500 }],
    author: "u_1",
    override: {}
  });
  await settle();

  const jobs = audioJobs(id);
  assert.ok(jobs.some(j => j.profile === "v1-copy" && j.keys.some(k => k.startsWith("custom:"))));
  assert.equal(jobs.some(j => j.profile === "v1"), false);
});

test("v1-copy: «Смотреть» без дорожек — задание с приоритетом 1", async () => {
  const videoPath = setupMovie(920005, 920006, false);
  const id = makeCopyReady(920005, videoPath, "fp-copy-920005");
  await settle();

  for (const j of audioJobs(id)) store.deleteJob(j.job_id);
  hlsAudio._reset();

  hlsAudio.onWatch(id);
  await settle();

  const jobs = audioJobs(id);
  assert.equal(jobs.length, 1);
  assert.equal(jobs[0].profile, "v1-copy");
  assert.equal(jobs[0].priority, 1);
});

test("done v1: звук на v1 с приоритетом 1, v1-copy держится до audio-done v1", async () => {
  const videoPath = setupMovie(920007, 920008, true);
  const id = makeCopyReady(920007, videoPath, "fp-copy-920007");
  await settle();

  const a = leaseAudio(id, "v1-copy");
  audioDoneFor(a);

  finishV1(id);
  await settle();

  const assetBefore = store.getAsset(id);
  assert.equal(assetBefore.profiles.v1.status, "ready");
  assert.equal((assetBefore.pending_delete || []).includes("v1-copy"), false);
  assert.equal(assetBefore.active_profile, "v1-copy");
  assert.equal(extLines(id).length, 2);

  const st = hls.statusFor(920007, videoPath);
  assert.equal(st.status, "ready");
  assert.equal(st.profile, "v1-copy");

  const jobs = audioJobs(id);
  assert.equal(jobs.length, 1);
  assert.equal(jobs[0].profile, "v1");
  assert.equal(jobs[0].priority, 1);
  assert.equal(jobs[0].keys.length, 2);
  assert.equal(jobs.some(j => j.profile === "v1-copy"), false);

  const b = leaseAudio(id, "v1");
  audioDoneFor(b);

  const assetAfter = store.getAsset(id);
  assert.equal(assetAfter.active_profile, "v1");
  assert.ok(assetAfter.pending_delete.includes("v1-copy"));

  const lines = extLines(id);
  assert.equal(lines.length, 2);
  for (const l of lines) assert.ok(l.includes("/v1/x"));

  const pd = hls.pendingDeletes();
  assert.ok(pd.items.some(x => x.hls_id === id && x.profile === "v1-copy" && !("path" in x)));
});

test("done v1: незавершённое задание «звук» для v1-copy снимается", async () => {
  const videoPath = setupMovie(920009, 920010, true);
  const id = makeCopyReady(920009, videoPath, "fp-copy-920009");
  await settle();

  finishV1(id);
  await settle();

  const jobs = audioJobs(id);
  assert.equal(jobs.some(j => j.profile === "v1-copy"), false);
  assert.equal(jobs.length, 1);
  assert.equal(jobs[0].profile, "v1");
  assert.equal(jobs[0].priority, 1);

  const asset = store.getAsset(id);
  assert.equal(asset.active_profile, "v1");
  assert.ok(asset.pending_delete.includes("v1-copy"));
});

test("done v1 без озвучек у v1-copy — v1-copy сразу в pending_delete", async () => {
  const videoPath = "Copy920011/Copy920011.mkv";
  treeCache.set(920011, [
    { type: "file", path: videoPath, size: 1000, sourceLink: "https://alldebrid.com/f/v920011" }
  ]);
  const id = makeCopyReady(920011, videoPath, "fp-copy-920011");
  await settle();

  finishV1(id);
  await settle();

  const asset = store.getAsset(id);
  assert.equal(asset.active_profile, "v1");
  assert.ok(asset.pending_delete.includes("v1-copy"));
  assert.equal(audioJobs(id).length, 0);
});

test("done v1: задание «звук» v1 упало без повтора — v1-copy всё равно уходит", async () => {
  const videoPath = setupMovie(920012, 920013, true);
  const id = makeCopyReady(920012, videoPath, "fp-copy-920012");
  await settle();

  const a = leaseAudio(id, "v1-copy");
  audioDoneFor(a);

  finishV1(id);
  await settle();

  const b = leaseAudio(id, "v1");
  hls.failed({ job_id: b.job_id, stage: "encode", error: "x", retryable: false });

  const asset = store.getAsset(id);
  assert.equal(asset.active_profile, "v1");
  assert.ok(asset.pending_delete.includes("v1-copy"));
});

test("done v1: снятие задания «звук» v1 из админки отпускает v1-copy", async () => {
  const videoPath = setupMovie(920014, 920015, true);
  const id = makeCopyReady(920014, videoPath, "fp-copy-920014");
  await settle();

  const a = leaseAudio(id, "v1-copy");
  audioDoneFor(a);

  finishV1(id);
  await settle();

  const [job] = audioJobs(id).filter(j => j.profile === "v1");
  assert.ok(job);
  hls.cancelJob(job.job_id);

  const asset = store.getAsset(id);
  assert.equal(asset.active_profile, "v1");
  assert.ok(asset.pending_delete.includes("v1-copy"));
});

test("done v1-copy: v1 не выдаётся, пока подбираются озвучки, — звук копии идёт раньше v1", async () => {
  const videoPath = setupMovie(920016, 920017, false);
  const id = makeCopyReady(920016, videoPath, "fp-copy-920016");
  for (const j of [...store.allJobs().values()]) if (j.hls_id !== id) store.deleteJob(j.job_id);
  assert.ok(v1Job(id));

  // подбор ещё идёт (сеть): единственный воркер не должен сразу забрать долгое v1
  assert.equal(hls.next(), null);

  await settle();
  const first = hls.next();
  assert.ok(first);
  assert.equal(first.type, "audio");
  assert.equal(first.profile, "v1-copy");

  audioDoneFor(first);
  const second = hls.next();
  assert.ok(second);
  assert.notEqual(second.type, "audio");
  assert.equal(second.profile, "v1");
  assert.ok(store.getAsset(id).profiles["v1-copy"].manifest.audio.some(e => e.external));
});

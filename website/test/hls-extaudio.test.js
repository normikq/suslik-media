"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-hls-extaudio-test-"));
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
const nextAudioFx = require("../../docs/hls-fixtures/next-job-audio.json");
const pdFx = require("../../docs/hls-fixtures/pending-deletes-audio.json");
const manExtFx = require("../../docs/hls-fixtures/manifest-external.json");

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

async function settle() {
  await hlsAudio.idle();
}

function audioJobs(id) {
  return [...store.allJobs().values()].filter(j => j.type === "audio" && (!id || j.hls_id === id));
}

// Оставить в очереди только задания «звук» этого ассета (видео v1 и чужие задания других тестов — убрать)
function dropVideoJobs(keepId) {
  for (const j of [...store.allJobs().values()]) {
    if (j.type !== "audio" || j.hls_id !== keepId) store.deleteJob(j.job_id);
  }
}

test("подбор: sidecar .mka из раздачи и custom, только .mka, порядок и названия", () => {
  const videoPath = "Movie.2010.1080p/Movie.2010.1080p.mkv";
  const picked = hlsAudio.pickTracks([
    {
      ad_id: "1",
      path: videoPath,
      shift_ms: 0,
      list: [
        { type: "file", path: videoPath, size: 1000, sourceLink: "https://alldebrid.com/f/v1" },
        { type: "file", path: "Movie.2010.1080p/Audio/Movie.2010.LostFilm.mka", sourceLink: "https://alldebrid.com/f/a1" },
        { type: "file", path: "Movie.2010.1080p/Audio/Movie.2010.Other.ac3", sourceLink: "https://alldebrid.com/f/a2" }
      ],
      custom: [
        { id: "trk_00000042", audioMagnetId: 5, audioPath: "HDRezka.mka", title: "HDRezka [rus]", lang: "rus", offsetMs: -4200, createdAt: 2 },
        { id: "trk_00000043", audioMagnetId: 5, audioPath: "x.flac", title: "Flac", lang: "rus", offsetMs: 0, createdAt: 1 }
      ]
    }
  ]);

  assert.deepEqual(
    picked.map(t => [t.key, t.kind, t.name, t.language, t.offset_ms]),
    [
      ["file:Movie.2010.1080p/Audio/Movie.2010.LostFilm.mka", "file", "LostFilm", null, 0],
      ["custom:trk_00000042", "custom", "HDRezka", "ru", -4200]
    ]
  );
  assert.deepEqual(picked[1].src, { ad_id: "5", path: "HDRezka.mka" });
});

test("подбор: shift_ms алиаса прибавляется к сдвигу, серия — только своя", () => {
  const pickedShift = hlsAudio.pickTracks([
    {
      ad_id: "2",
      path: "Film/Film.mkv",
      shift_ms: 1500,
      list: [{ type: "file", path: "Film/Film.mkv", sourceLink: "https://alldebrid.com/f/v2" }],
      custom: [
        { id: "trk_00000050", audioMagnetId: 6, audioPath: "Dub.mka", title: "Dub", lang: "rus", offsetMs: -4200, createdAt: 1 }
      ]
    }
  ]);
  assert.equal(pickedShift.length, 1);
  assert.equal(pickedShift[0].offset_ms, -2700);
  assert.equal(pickedShift[0].base_offset_ms, -4200);
  assert.equal(pickedShift[0].shift_ms, 1500);

  const pickedSeries = hlsAudio.pickTracks([
    {
      ad_id: "3",
      path: "Show/Show.S01E02.mkv",
      shift_ms: 0,
      list: [
        { type: "file", path: "Show/Show.S01E01.mkv", sourceLink: "https://alldebrid.com/f/e1" },
        { type: "file", path: "Show/Show.S01E02.mkv", sourceLink: "https://alldebrid.com/f/e2" },
        { type: "file", path: "Show/Show.S01E01.LostFilm.mka", sourceLink: "https://alldebrid.com/f/a1" },
        { type: "file", path: "Show/Show.S01E02.LostFilm.mka", sourceLink: "https://alldebrid.com/f/a2" }
      ],
      custom: []
    }
  ]);
  assert.deepEqual(
    pickedSeries.map(t => t.key),
    ["file:Show/Show.S01E02.LostFilm.mka"]
  );
});

test("подбор: не больше 8 дорожек, номера xN не переиспользуются", async () => {
  const videoPath = "Film/Film.mkv";
  const items = [{ type: "file", path: videoPath, size: 1000, sourceLink: "https://alldebrid.com/f/v3" }];
  for (let i = 1; i <= 10; i++) {
    const num = String(i).padStart(2, "0");
    items.push({
      type: "file",
      path: `Film/Film.Dub${num}.mka`,
      size: 100,
      sourceLink: `https://alldebrid.com/f/d${num}`
    });
  }
  treeCache.set(910003, items);

  const id = makeReady(910003, videoPath, 1000, "fp-ext-910003");
  await settle();

  const a = store.getAsset(id);
  const keys = Object.keys(a.ext_audio);
  assert.equal(keys.length, 8);
  assert.deepEqual(
    Object.values(a.ext_audio).map(e => e.dir).sort(),
    ["x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7"]
  );

  const jobs = audioJobs(id);
  assert.equal(jobs.length, 1);
  assert.equal(jobs[0].profile, "v1");
  assert.equal(jobs[0].priority, hlsAudio.PRIORITY);
  assert.equal(jobs[0].priority, 3);
  assert.equal(jobs[0].keys.length, 8);
  assert.equal(jobs[0].state, "queued");

  const firstKey = keys[0];
  const freedDir = a.ext_audio[firstKey].dir;
  hlsAudio.removeExternal(id, firstKey);

  const jobsAfterRemove = audioJobs(id);
  assert.equal(jobsAfterRemove.length, 1);
  assert.equal(jobsAfterRemove[0].keys.length, 7);

  const added = hlsAudio.allocate(id, [
    {
      key: "file:new.mka",
      kind: "file",
      name: "New",
      language: null,
      base_offset_ms: 0,
      shift_ms: 0,
      offset_ms: 0,
      src: { ad_id: "910003", path: "new.mka" }
    }
  ]);
  assert.deepEqual(added, ["file:new.mka"]);
  assert.equal(store.getAsset(id).ext_audio["file:new.mka"].dir, freedDir);
});

test("next: задание «звук» в формате фикстуры, mirror_url зеркала", async () => {
  const videoPath = "Movie.2010.1080p/Movie.2010.1080p.mkv";
  treeCache.set(910004, [
    { type: "file", path: videoPath, size: 1000, sourceLink: "https://alldebrid.com/f/v4" },
    { type: "file", path: "Movie.2010.1080p/Audio/Movie.2010.LostFilm.mka", size: 200, sourceLink: "https://alldebrid.com/f/lf4" }
  ]);
  treeCache.set(910005, [
    { type: "file", path: "HDRezka.mka", size: 200, sourceLink: "https://alldebrid.com/f/hdrezka" }
  ]);

  const id = makeReady(910004, videoPath, 1000, "fp-ext-910004");
  ca.addTracks({
    movieMagnetId: 910004,
    audioMagnetId: 910005,
    pairs: [{ videoPath, audioPath: "HDRezka.mka", voice: "HDRezka", offsetMs: -4200 }],
    author: "u_1",
    override: { lang: "rus" } // язык дорожки rus → LANGUAGE "ru"
  });
  await settle();
  dropVideoJobs(id);

  const rUnauth = await call("GET", "/internal/hls/next");
  assert.equal(rUnauth.status, 401);

  const r = await call("GET", "/internal/hls/next", { token: config.HLS_WORKER_TOKEN });
  assert.equal(r.status, 200);
  const body = r.json;

  assert.deepEqual(Object.keys(body).sort(), Object.keys(nextAudioFx).sort());
  assert.equal(body.type, "audio");
  assert.equal(body.hls_id, id);
  assert.equal(body.profile, "v1");
  assert.equal(body.duration_ms, 7951200);
  assert.equal(body.tracks.length, 2);

  for (const t of body.tracks) {
    assert.deepEqual(Object.keys(t).sort(), Object.keys(nextAudioFx.tracks[0]).sort());
    assert.match(t.mirror_url, /\/api\/file\/proxy\/[^?]+\?link=.+&exp=\d+&sig=/);
    assert.match(t.dir, /^x[0-7]$/);
  }
  assert.equal(new Set(body.tracks.map(t => t.dir)).size, 2);

  const names = new Set(body.tracks.map(t => t.name));
  assert.ok(names.has("LostFilm"));
  assert.ok(names.has("HDRezka"));

  const hdrezka = body.tracks.find(t => t.name === "HDRezka");
  const lostfilm = body.tracks.find(t => t.name === "LostFilm");
  assert.equal(hdrezka.offset_ms, -4200);
  assert.equal(hdrezka.language, "ru");
  assert.ok(hdrezka.mirror_url.includes(encodeURIComponent("https://alldebrid.com/f/hdrezka")));
  assert.equal(lostfilm.offset_ms, 0);
});

test("audio-done: дорожки в manifest.audio и в master.m3u8 после дорожек файла", async () => {
  const videoPath = "Movie.2010.1080p/Movie.2010.1080p.mkv";
  treeCache.set(910006, [
    { type: "file", path: videoPath, size: 1000, sourceLink: "https://alldebrid.com/f/v6" },
    { type: "file", path: "Movie.2010.1080p/Audio/Movie.2010.LostFilm.mka", size: 200, sourceLink: "https://alldebrid.com/f/lf6" }
  ]);
  treeCache.set(910007, [
    { type: "file", path: "HDRezka.mka", size: 200, sourceLink: "https://alldebrid.com/f/hdrezka6" }
  ]);

  const id = makeReady(910006, videoPath, 1000, "fp-ext-910006");
  ca.addTracks({
    movieMagnetId: 910006,
    audioMagnetId: 910007,
    pairs: [{ videoPath, audioPath: "HDRezka.mka", voice: "HDRezka", offsetMs: -4200 }],
    author: "u_1",
    override: {}
  });
  await settle();
  dropVideoJobs(id);

  let job;
  for (let i = 0; i < 10; i++) {
    const nextJob = hls.next();
    if (!nextJob) break;
    if (nextJob.type === "audio" && nextJob.hls_id === id) {
      job = nextJob;
      break;
    }
  }
  assert.ok(job, "должно быть выдано задание audio");

  const donePayload = {
    job_id: job.job_id,
    hls_id: id,
    profile: "v1",
    tracks: job.tracks.map(t => ({
      key: t.key,
      dir: t.dir,
      codec: "mp4a.40.2",
      channels: 2,
      bytes: 1000
    }))
  };

  const rUnauth = await call("POST", "/internal/hls/audio-done", { json: donePayload });
  assert.equal(rUnauth.status, 401);

  const rBad = await call("POST", "/internal/hls/audio-done", {
    token: config.HLS_WORKER_TOKEN,
    json: { ...donePayload, hls_id: "nonexistent-id" }
  });
  assert.equal(rBad.status, 400);

  const rDone = await call("POST", "/internal/hls/audio-done", {
    token: config.HLS_WORKER_TOKEN,
    json: donePayload
  });
  assert.equal(rDone.status, 200);
  assert.equal(rDone.json.ok, true);
  assert.equal(rDone.json.added, 2);
  assert.equal(store.getJob(job.job_id), null);

  const manAudio = store.getAsset(id).profiles.v1.manifest.audio;
  const baseCount = manifest.audio.length;
  assert.equal(manAudio.length, baseCount + 2);
  for (let i = 0; i < baseCount; i++) {
    assert.equal(manAudio[i].external, undefined);
  }
  const extEntries = manAudio.slice(baseCount);
  const expectedKeys = Object.keys(manExtFx.audio[2]).sort();
  for (const entry of extEntries) {
    assert.deepEqual(Object.keys(entry).sort(), expectedKeys);
    assert.equal(entry.default, false);
    assert.ok(entry.external.key);
    assert.ok(entry.external.kind);
    assert.equal(typeof entry.external.offset_ms, "number");
  }
  const hdEntry = extEntries.find(e => e.name === "HDRezka");
  assert.ok(hdEntry);
  assert.equal(hdEntry.external.offset_ms, -4200);
  assert.equal(hdEntry.external.kind, "custom");

  const exp = Math.floor(Date.now() / 1000) + 3600;
  const master = hls.masterPlaylist(id, exp);
  const audioLines = master.split("\n").filter(l => l.includes("TYPE=AUDIO"));
  assert.equal(audioLines.length, baseCount + 2);
  const lastTwo = audioLines.slice(-2);
  for (let i = 0; i < 2; i++) {
    const ext = extEntries[i];
    assert.ok(lastTwo[i].includes(`NAME="${ext.name}"`));
    assert.ok(lastTwo[i].includes("DEFAULT=NO"));
    assert.ok(lastTwo[i].includes(`/v1/${ext.dir}/index.m3u8`));
  }

  // Повторный POST того же тела заменяет, не дублирует
  const rRepeat = await call("POST", "/internal/hls/audio-done", {
    token: config.HLS_WORKER_TOKEN,
    json: donePayload
  });
  assert.equal(rRepeat.status, 200);
  assert.equal(store.getAsset(id).profiles.v1.manifest.audio.length, baseCount + 2);
});

test("master: имя внешней дорожки с кавычками и переводом строки экранируется", () => {
  treeCache.set(910008, [
    { type: "file", path: "Safe/Safe.mkv", size: 1000, sourceLink: "https://alldebrid.com/f/v8" }
  ]);
  const id = makeReady(910008, "Safe/Safe.mkv", 1000, "fp-ext-910008");
  const a = store.getAsset(id);
  a.ext_audio = {
    "custom:trk_0000beef": {
      dir: "x0",
      kind: "custom",
      name: 'Bad "name"\nEXT',
      language: "ru",
      base_offset_ms: 0,
      shift_ms: 0,
      offset_ms: 0,
      src: { ad_id: "1", path: "a.mka" }
    }
  };
  store.putAsset(id, a);

  hlsAudio.audioDone({
    job_id: "none",
    hls_id: id,
    profile: "v1",
    tracks: [{ key: "custom:trk_0000beef", dir: "x0", codec: "mp4a.40.2", channels: 2, bytes: 1 }]
  });

  const exp = Math.floor(Date.now() / 1000) + 3600;
  const master = hls.masterPlaylist(id, exp);
  const lines = master.split("\n");
  assert.ok(lines.every(l => l === "" || l.startsWith("#") || l.startsWith("https://")));
  assert.ok(!lines.some(l => l.startsWith("EXT")));

  const x0Line = lines.find(l => l.includes("TYPE=AUDIO") && l.includes("/v1/x0/index.m3u8"));
  assert.ok(x0Line);
  assert.match(x0Line, /NAME="[^"\n]*"/);
  assert.ok(!x0Line.includes('"name"'));
});

test("attach/offset/delete → задания и pending-deletes с path", async () => {
  const videoPath = "Movie9/Movie9.mkv";
  treeCache.set(910009, [
    { type: "file", path: videoPath, size: 1000, sourceLink: "https://alldebrid.com/f/v9" }
  ]);
  treeCache.set(910010, [
    { type: "file", path: "Dub.mka", size: 200, sourceLink: "https://alldebrid.com/f/d9" }
  ]);

  const id = makeReady(910009, videoPath, 1000, "fp-ext-910009");
  await settle();
  assert.equal(audioJobs(id).length, 0);

  const { created } = ca.addTracks({
    movieMagnetId: 910009,
    audioMagnetId: 910010,
    pairs: [{ videoPath, audioPath: "Dub.mka", voice: "Dub", offsetMs: 1000 }],
    author: "u_1",
    override: {}
  });
  const trackId = created[0].id;
  const key = `custom:${trackId}`;
  await settle();

  const jobs1 = audioJobs(id);
  assert.equal(jobs1.length, 1);
  assert.equal(jobs1[0].priority, 3);
  assert.deepEqual(jobs1[0].keys, [key]);

  dropVideoJobs(id);
  const leased1 = hls.next();
  assert.equal(leased1.type, "audio");
  const dir = leased1.tracks[0].dir;

  hlsAudio.audioDone({
    job_id: leased1.job_id,
    hls_id: id,
    profile: "v1",
    tracks: [{ key, dir, codec: "mp4a.40.2", channels: 2, bytes: 100 }]
  });
  assert.equal(audioJobs(id).length, 0);
  const man1 = store.getAsset(id).profiles.v1.manifest.audio;
  assert.equal(man1.find(e => e.external && e.external.key === key).external.offset_ms, 1000);

  // Смена сдвига синхронно ставит новое задание и обновляет ext_audio
  ca.setOffset(trackId, -500);
  const jobs2 = audioJobs(id);
  assert.equal(jobs2.length, 1);
  assert.deepEqual(jobs2[0].keys, [key]);
  assert.equal(store.getAsset(id).ext_audio[key].offset_ms, -500);

  const leased2 = hls.next();
  assert.equal(leased2.type, "audio");
  hlsAudio.audioDone({
    job_id: leased2.job_id,
    hls_id: id,
    profile: "v1",
    tracks: [{ key, dir: leased2.tracks[0].dir, codec: "mp4a.40.2", channels: 2, bytes: 100 }]
  });
  const man2 = store.getAsset(id).profiles.v1.manifest.audio.filter(e => e.external);
  assert.equal(man2.length, 1);
  assert.equal(man2[0].dir, dir);
  assert.equal(man2[0].external.offset_ms, -500);

  // Удаление дорожки убирает её из манифеста и добавляет path в pending-deletes
  ca.removeTrack(trackId);
  assert.equal(
    store.getAsset(id).profiles.v1.manifest.audio.filter(e => e.external).length,
    0
  );
  assert.equal(store.getAsset(id).ext_audio[key], undefined);

  const pd = hls.pendingDeletes();
  const expectedItem = { hls_id: id, profile: "v1", path: dir };
  const found = pd.items.find(x => x.hls_id === id && x.path === dir);
  assert.deepEqual(found, expectedItem);
  assert.deepEqual(Object.keys(found).sort(), Object.keys(pdFx.items[0]).sort());

  const rPending = await call("GET", "/internal/hls/pending-deletes", { token: config.HLS_CDN_TOKEN });
  assert.equal(rPending.status, 200);
  assert.ok(rPending.json.items.some(x => x.hls_id === id && x.profile === "v1" && x.path === dir));

  const rEvicted = await call("POST", "/internal/hls/evicted", {
    token: config.HLS_CDN_TOKEN,
    json: [{ hls_id: id, profile: "v1", path: dir, last_access: 0 }]
  });
  assert.equal(rEvicted.status, 200);
  assert.equal(store.getAsset(id).profiles.v1.status, "ready");
  assert.equal(
    hls.pendingDeletes().items.some(x => x.hls_id === id && x.path === dir),
    false
  );
});

test("удаление раздачи-озвучки (dropMagnet) убирает дорожку", async () => {
  const videoPath = "Movie11/Movie11.mkv";
  treeCache.set(910011, [
    { type: "file", path: videoPath, size: 1000, sourceLink: "https://alldebrid.com/f/v11" }
  ]);
  treeCache.set(910012, [
    { type: "file", path: "Dub11.mka", size: 200, sourceLink: "https://alldebrid.com/f/d11" }
  ]);

  const id = makeReady(910011, videoPath, 1000, "fp-ext-910011");
  const { created } = ca.addTracks({
    movieMagnetId: 910011,
    audioMagnetId: 910012,
    pairs: [{ videoPath, audioPath: "Dub11.mka", voice: "Dub11", offsetMs: 0 }],
    author: "u_1",
    override: {}
  });
  const key = `custom:${created[0].id}`;
  await settle();
  dropVideoJobs(id);

  const leased = hls.next();
  assert.equal(leased.type, "audio");
  const dir = leased.tracks[0].dir;
  hlsAudio.audioDone({
    job_id: leased.job_id,
    hls_id: id,
    profile: "v1",
    tracks: [{ key, dir, codec: "mp4a.40.2", channels: 2, bytes: 100 }]
  });

  ca.dropMagnet(910012);
  assert.equal(store.getAsset(id).ext_audio[key], undefined);
  assert.ok(
    hls.pendingDeletes().items.some(x => x.hls_id === id && x.profile === "v1" && x.path === dir)
  );
});

test("дедупликация: новое задание поглощает ожидающее, взятое — нет", () => {
  treeCache.set(910013, [
    { type: "file", path: "Dedup/Dedup.mkv", size: 1000, sourceLink: "https://alldebrid.com/f/v13" }
  ]);
  const id = makeReady(910013, "Dedup/Dedup.mkv", 1000, "fp-ext-910013");
  const a = store.getAsset(id);
  a.ext_audio = {
    "custom:trk_000000a1": {
      dir: "x0",
      kind: "custom",
      name: "A1",
      language: "ru",
      base_offset_ms: 0,
      shift_ms: 0,
      offset_ms: 0,
      src: { ad_id: "1", path: "a.mka" }
    },
    "custom:trk_000000a2": {
      dir: "x1",
      kind: "custom",
      name: "A2",
      language: "ru",
      base_offset_ms: 0,
      shift_ms: 0,
      offset_ms: 0,
      src: { ad_id: "1", path: "b.mka" }
    }
  };
  store.putAsset(id, a);
  dropVideoJobs(id);

  const r1 = hlsAudio.enqueueAudio(id, "v1", ["custom:trk_000000a1"], 3);
  assert.equal(r1.created, true);

  const r2 = hlsAudio.enqueueAudio(id, "v1", ["custom:trk_000000a1"], 3);
  assert.equal(r2.deduped, true);
  assert.equal(r2.job.job_id, r1.job.job_id);

  const r3 = hlsAudio.enqueueAudio(id, "v1", ["custom:trk_000000a2"], 1);
  assert.equal(r3.created, true);
  assert.equal(r3.job.priority, 1);
  assert.deepEqual(r3.job.keys, ["custom:trk_000000a1", "custom:trk_000000a2"]);
  assert.equal(audioJobs(id).length, 1);

  const leased = hls.next();
  assert.equal(leased.type, "audio");
  assert.equal(leased.job_id, r3.job.job_id);

  // Взятое задание с тем же сдвигом покрывает запрос
  const r4 = hlsAudio.enqueueAudio(id, "v1", ["custom:trk_000000a1"], 3);
  assert.equal(r4.deduped, true);
  assert.equal(audioJobs(id).length, 1);

  // Если сдвиг изменился — создаётся второе задание
  const aUpdated = store.getAsset(id);
  aUpdated.ext_audio["custom:trk_000000a1"].offset_ms = 700;
  store.putAsset(id, aUpdated);

  const r5 = hlsAudio.enqueueAudio(id, "v1", ["custom:trk_000000a1"], 3);
  assert.equal(r5.created, true);
  assert.equal(audioJobs(id).length, 2);
});

test("«Смотреть»: недостающая дорожка — приоритет 1", async () => {
  const videoPath = "Watch14/Watch14.mkv";
  treeCache.set(910014, [
    { type: "file", path: videoPath, size: 1000, sourceLink: "https://alldebrid.com/f/v14" },
    { type: "file", path: "Watch14/Watch14.LostFilm.mka", size: 200, sourceLink: "https://alldebrid.com/f/lf14" }
  ]);
  const id = makeReady(910014, videoPath, 1000, "fp-ext-910014");
  await settle();

  for (const j of audioJobs(id)) store.deleteJob(j.job_id);
  hlsAudio._reset();

  hlsAudio.onWatch(id);
  await settle();

  const jobs = audioJobs(id);
  assert.equal(jobs.length, 1);
  assert.equal(jobs[0].priority, hlsAudio.WATCH_PRIORITY);
  assert.equal(jobs[0].priority, 1);

  // Повторный вызов сразу — троттлинг
  assert.equal(hlsAudio.onWatch(id), null);
});

test("задание «звук»: probe отклоняется, старые задания без type — видео", async () => {
  const videoPath = "Probe15/Probe15.mkv";
  treeCache.set(910015, [
    { type: "file", path: videoPath, size: 1000, sourceLink: "https://alldebrid.com/f/v15" },
    { type: "file", path: "Probe15/Probe15.LostFilm.mka", size: 200, sourceLink: "https://alldebrid.com/f/lf15" }
  ]);
  const id = makeReady(910015, videoPath, 1000, "fp-ext-910015");
  await settle();

  const jobs = audioJobs(id);
  assert.equal(jobs.length, 1);
  assert.throws(
    () => hls.handleProbe(jobs[0].job_id, probeBody(1)),
    err => err && err.code === "bad_request"
  );

  assert.equal(store.isAudioJob({}), false);
  assert.equal(store.isAudioJob({ ad_id: "1", path: "a" }), false);
});

test("audio-done: тело по фикстуре, дорожку удалили во время кодирования — папка в pending-deletes", async () => {
  const doneFx = require("../../docs/hls-fixtures/audio-done-request.json");
  const videoPath = "Run16/Run16.mkv";
  treeCache.set(910016, [{ type: "file", path: videoPath, size: 1000, sourceLink: "https://alldebrid.com/f/v16" }]);
  treeCache.set(910017, [{ type: "file", path: "Dub16.mka", size: 200, sourceLink: "https://alldebrid.com/f/d16" }]);
  const id = makeReady(910016, videoPath, 1000, "fp-ext-910016");
  const { created } = ca.addTracks({ movieMagnetId: 910016, audioMagnetId: 910017,
    pairs: [{ videoPath, audioPath: "Dub16.mka", voice: "Dub16", offsetMs: 0 }], author: "u_1" });
  const key = `custom:${created[0].id}`;
  await settle();
  dropVideoJobs(id);
  const leased = hls.next();
  assert.equal(leased.type, "audio");
  const dir = leased.tracks[0].dir;
  // удалили, пока воркер кодирует: в manifest её ещё нет, но папка на CDN появится
  ca.removeTrack(created[0].id);
  assert.ok(hls.pendingDeletes().items.some(x => x.hls_id === id && x.path === dir), "кодируемая папка — в pending-deletes");
  const payload = { job_id: leased.job_id, hls_id: id, profile: "v1", tracks: [{ key, dir, codec: "mp4a.40.2", channels: 2, bytes: 5 }] };
  assert.deepEqual(Object.keys(payload).sort(), Object.keys(doneFx).sort());
  assert.deepEqual(Object.keys(payload.tracks[0]).sort(), Object.keys(doneFx.tracks[0]).sort());
  const r = await call("POST", "/internal/hls/audio-done", { token: config.HLS_WORKER_TOKEN, json: payload });
  assert.equal(r.status, 200);
  assert.equal(r.json.added, 0);
  assert.deepEqual(r.json.skipped, [key]);
  assert.equal(store.getAsset(id).profiles.v1.manifest.audio.filter(x => x.external).length, 0);
  assert.equal(hls.pendingDeletes().items.filter(x => x.hls_id === id && x.path === dir).length, 1, "без дублей");
  // токен CDN воркеру не подходит, и наоборот
  assert.equal((await call("POST", "/internal/hls/audio-done", { token: config.HLS_CDN_TOKEN, json: payload })).status, 401);
  assert.equal((await call("GET", "/internal/hls/pending-deletes", { token: config.HLS_WORKER_TOKEN })).status, 401);
});

test("хранилище: задания «звук» переживают перезапуск и не попадают в индексы файлов", async () => {
  treeCache.set(910018, [
    { type: "file", path: "Keep18/Keep18.mkv", size: 1000, sourceLink: "https://alldebrid.com/f/v18" },
    { type: "file", path: "Keep18/Keep18.LostFilm.mka", size: 200, sourceLink: "https://alldebrid.com/f/lf18" }
  ]);
  const id = makeReady(910018, "Keep18/Keep18.mkv", 1000, "fp-ext-910018");
  await settle();
  const [job] = audioJobs(id);
  assert.ok(job);
  assert.equal(store.jobForHls(id)?.type === "audio", false, "jobByHls — только видео");
  store.flush();
  store._reset({ reload: true });
  const again = store.getJob(job.job_id);
  assert.ok(again, "задание прочитано с диска");
  assert.equal(again.type, "audio");
  assert.deepEqual(again.keys, job.keys);
  assert.equal(store.getAsset(id).ext_audio["file:Keep18/Keep18.LostFilm.mka"].name, "LostFilm");
  // забыть раздачу фильма: задания «звук» ассета остаются (ассет живёт, §11)
  hls.forgetMagnet("910018");
  assert.ok(store.getJob(job.job_id));
});

test("админка: задание «звук» с названиями озвучек, у ассета — внешние дорожки", async () => {
  const ui = require("../public/hls-admin.js");
  treeCache.set(910019, [
    { type: "file", path: "Adm19/Adm19.mkv", size: 1000, sourceLink: "https://alldebrid.com/f/v19" },
    { type: "file", path: "Adm19/Adm19.LostFilm.mka", size: 200, sourceLink: "https://alldebrid.com/f/lf19" }
  ]);
  treeCache.set(910020, [{ type: "file", path: "HDRezka.mka", size: 200, sourceLink: "https://alldebrid.com/f/hd20" }]);
  const id = makeReady(910019, "Adm19/Adm19.mkv", 1000, "fp-ext-910019");
  ca.addTracks({ movieMagnetId: 910019, audioMagnetId: 910020,
    pairs: [{ videoPath: "Adm19/Adm19.mkv", audioPath: "HDRezka.mka", voice: "HDRezka", offsetMs: -4200 }], author: "u_1" });
  await settle();
  const st = hls.adminState();
  const q = st.queue.find(j => j.type === "audio" && j.hls_id === id);
  assert.ok(q);
  assert.equal(q.path, "Adm19/Adm19.mkv");
  assert.equal(ui.audioJobText(q), "Озвучки: LostFilm, HDRezka");
  const asset = st.assets.find(x => x.hls_id === id);
  assert.deepEqual(asset.external.tracks.map(x => [x.dir, x.name, x.kind, x.offset_ms, x.profiles]),
    [["x0", "LostFilm", "file", 0, []], ["x1", "HDRezka", "custom", -4200, []]]);
  assert.equal(ui.externalText(asset.external.tracks[1]), "HDRezka — x1, сдвиг \u22124,2 с, добавлена пользователем, ещё не готова");
  assert.equal(ui.offsetText(0), "");
  assert.equal(ui.offsetText(1500), "+1,5 с");
});

test("HLS выключен: события custom-audio ничего не делают", () => {
  const was = config.HLS_ENABLED;
  config.HLS_ENABLED = false;
  try {
    assert.equal(hlsAudio.onAdded([{ id: "trk_00000001", movieMagnetId: 1, videoPath: "a.mkv", audioPath: "a.mka" }]), null);
    assert.equal(hlsAudio.onOffset({ id: "trk_00000001", offsetMs: 5 }), 0);
    assert.equal(hlsAudio.onRemoved([{ id: "trk_00000001" }]), 0);
    assert.equal(hlsAudio.onWatch("0123456789abcdef"), null);
    assert.equal(hlsAudio.onProfileReady({ hls_id: "0123456789abcdef", profile: "v1" }), null);
  } finally {
    config.HLS_ENABLED = was;
  }
});

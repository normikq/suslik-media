"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-hls-archive-stream-test-"));
process.env.SESSION_SECRET = "test-session-secret-do-not-use";
process.env.HLS_ENABLED = "true";
process.env.HLS_CDN_URL = "https://cdn.example.org/some/path";
process.env.HLS_SIGN_SECRET = "test-secret-do-not-use";
process.env.HLS_WORKER_TOKEN = "worker-token-test";
process.env.HLS_CDN_TOKEN = "cdn-token-test";
process.env.AUTO_CLEAN = "false";
delete process.env.HLS_BETA_USERS;
delete process.env.HLS_ARCHIVE_STREAM;

const test = require("node:test");
const assert = require("node:assert/strict");

const config = require("../src/config");
const { createApp } = require("../src/app");
const { treeCache } = require("../src/services/alldebrid");
const users = require("../src/services/users");
const hls = require("../src/services/hls");
const store = require("../src/services/hls-store");
const manifest = require("../../docs/hls-fixtures/manifest.json");
const hlsPlayer = require("../public/hls-player.js");

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

test.afterEach(() => {
  config.HLS_ARCHIVE_STREAM = false;
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
  return { id: r.hls_id, profile: r.profile };
}

function makeCold(adId, name, seed) {
  const { id, profile } = makeReady(adId, name, 3000000000, seed);
  hls.archived([{ hls_id: id, profile, bytes: 4800000000, at: 1767225600 }]);
  hls.evicted([{ hls_id: id, profile, last_access: 1, archived: true }]);
  return { id, profile };
}

function masterUrl(id) {
  const exp = Math.floor(Date.now() / 1000) + 3600;
  return `/api/hls/${id}/master.m3u8?exp=${exp}&sig=${hls.signMaster(id, exp)}`;
}

async function variant(tok, adId, filePath) {
  return call("POST", "/api/file/variant-url", {
    headers: { "X-Device-Token": tok },
    json: { ad_id: adId, path: filePath }
  });
}

function setTree(adId, filePath) {
  treeCache.set(adId, [
    { name: filePath, path: filePath, type: "file", size: 3000000000, sourceLink: "https://alldebrid.com/f/" + adId }
  ]);
}

test("флаг выключен: cold-версия — restoring, master 404, без from_archive", async () => {
  config.HLS_ARCHIVE_STREAM = false;
  const tok = deviceToken("stream-user-730001", "allow");
  setTree(730001, "S1.mkv");
  const { id } = makeCold(730001, "S1.mkv", "fp-stream-730001");

  const rVar = await variant(tok, 730001, "S1.mkv");
  assert.equal(rVar.status, 200);
  assert.equal(rVar.json.hls.status, "restoring");
  assert.equal(rVar.json.hls.from_archive, undefined);
  assert.equal(rVar.json.hls.url, undefined);

  const rMaster = await call("GET", masterUrl(id));
  assert.equal(rMaster.status, 404);
  assert.equal(hls.archiveStreamProfile(store.getAsset(id)), null);
});

test("флаг включён: variant-url отдаёт ready из архива и ставит запрос возврата", async () => {
  config.HLS_ARCHIVE_STREAM = true;
  const tok = deviceToken("stream-user-730002", "allow");
  setTree(730002, "S2.mkv");
  const { id, profile } = makeCold(730002, "S2.mkv", "fp-stream-730002");

  const rVar = await variant(tok, 730002, "S2.mkv");
  assert.equal(rVar.status, 200);
  assert.equal(rVar.json.hls.status, "ready");
  assert.equal(rVar.json.hls.from_archive, true);
  assert.equal(rVar.json.hls.profile, profile);
  assert.equal(typeof rVar.json.hls.shift_ms, "number");
  assert.match(rVar.json.hls.url, new RegExp(`/api/hls/${id}/master\\.m3u8\\?exp=\\d+&sig=`));

  const ver = store.getAsset(id).profiles[profile];
  assert.equal(ver.status, "cold");
  assert.ok(ver.restore_requested_at);
  assert.equal(ver.restore_user, true);

  const rPending = await call("GET", "/internal/hls/pending-restores", {
    token: config.HLS_CDN_TOKEN
  });
  assert.equal(rPending.status, 200);
  assert.ok(rPending.json.items.some(x => x.hls_id === id && x.profile === profile));

  const stFor = hls.statusFor("730002", "S2.mkv");
  assert.equal(stFor.status, "ready");
  assert.equal(stFor.from_archive, true);

  const rVar2 = await variant(tok, 730002, "S2.mkv");
  assert.equal(rVar2.status, 200);
  assert.equal(rVar2.json.hls.status, "ready");
  assert.equal(rVar2.json.hls.from_archive, true);

  const rPending2 = await call("GET", "/internal/hls/pending-restores", {
    token: config.HLS_CDN_TOKEN
  });
  assert.equal(rPending2.status, 200);
  const matching = rPending2.json.items.filter(x => x.hls_id === id);
  assert.equal(matching.length, 1);
});

test("флаг включён: master.m3u8 cold-версии по manifest, с внешней озвучкой, и обновляет last_access", async () => {
  config.HLS_ARCHIVE_STREAM = true;
  const { id, profile } = makeCold(730003, "S3.mkv", "fp-stream-730003");

  const a = store.getAsset(id);
  a.profiles[profile].manifest.audio.push({
    dir: "x1",
    codec: "mp4a.40.2",
    channels: 2,
    language: "ru",
    name: "Внешняя",
    default: false,
    external: { key: "k1", kind: "file", offset_ms: 0 }
  });
  a.last_access = 1;
  store.putAsset(id, a);

  const rMaster = await call("GET", masterUrl(id));
  assert.equal(rMaster.status, 200);
  assert.ok((rMaster.headers.get("content-type") || "").includes("mpegurl"));
  assert.ok(rMaster.text.startsWith("#EXTM3U"));
  assert.ok(rMaster.text.includes(`/${id}/${profile}/1080p/index.m3u8`));
  assert.ok(rMaster.text.includes(`/${id}/${profile}/x1/index.m3u8`));
  assert.ok(rMaster.text.includes('NAME="Внешняя"'));
  assert.ok(store.getAsset(id).last_access > 1);

  config.HLS_ARCHIVE_STREAM = false;
  const rMasterOff = await call("GET", masterUrl(id));
  assert.equal(rMasterOff.status, 404);
});

test("cold без manifest — без потока, как раньше", async () => {
  config.HLS_ARCHIVE_STREAM = true;
  const tok = deviceToken("stream-user-730004", "allow");
  const { id, profile } = makeCold(730004, "S4.mkv", "fp-stream-730004");

  const a = store.getAsset(id);
  delete a.profiles[profile].manifest;
  store.putAsset(id, a);
  setTree(730004, "S4.mkv");

  const rVar = await variant(tok, 730004, "S4.mkv");
  assert.equal(rVar.status, 200);
  assert.equal(rVar.json.hls.status, "restoring");
  assert.equal(rVar.json.hls.from_archive, undefined);

  const rMaster = await call("GET", masterUrl(id));
  assert.equal(rMaster.status, 404);
});

test("готовая v1-copy предпочтительнее cold v1", async () => {
  config.HLS_ARCHIVE_STREAM = true;
  const tok = deviceToken("stream-user-730005", "allow");
  const { id, profile } = makeReady(730005, "S5.mkv", 3000000000, "fp-stream-730005");

  const a = store.getAsset(id);
  const base = a.profiles[profile];
  a.profiles.v1 = { ...structuredClone(base), status: "cold", archived: true, archive_bytes: 4800000000 };
  a.profiles["v1-copy"] = { ...structuredClone(base), status: "ready" };
  a.active_profile = "v1";
  a.pending_delete = [];
  store.putAsset(id, a);

  assert.equal(hls.liveProfile(store.getAsset(id)), "v1-copy");
  assert.equal(hls.archiveStreamProfile(store.getAsset(id)), null);

  setTree(730005, "S5.mkv");
  const rVar = await variant(tok, 730005, "S5.mkv");
  assert.equal(rVar.status, 200);
  assert.equal(rVar.json.hls.status, "ready");
  assert.equal(rVar.json.hls.profile, "v1-copy");
  assert.equal(rVar.json.hls.from_archive, undefined);

  assert.equal(hls.pendingRestores().items.some(x => x.hls_id === id), false);

  const rMaster = await call("GET", masterUrl(id));
  assert.equal(rMaster.status, 200);
  assert.ok(rMaster.text.includes(`/${id}/v1-copy/`));
  assert.equal(rMaster.text.includes(`/${id}/v1/`), false);

  const a2 = store.getAsset(id);
  delete a2.profiles["v1-copy"];
  store.putAsset(id, a2);

  const rVar2 = await variant(tok, 730005, "S5.mkv");
  assert.equal(rVar2.status, 200);
  assert.equal(rVar2.json.hls.status, "ready");
  assert.equal(rVar2.json.hls.profile, "v1");
  assert.equal(rVar2.json.hls.from_archive, true);
});

test("после restored поле from_archive пропадает", async () => {
  config.HLS_ARCHIVE_STREAM = true;
  const tok = deviceToken("stream-user-730006", "allow");
  setTree(730006, "S6.mkv");
  const { id, profile } = makeCold(730006, "S6.mkv", "fp-stream-730006");

  const rVar1 = await variant(tok, 730006, "S6.mkv");
  assert.equal(rVar1.status, 200);
  assert.equal(rVar1.json.hls.from_archive, true);

  const rRest = await call("POST", "/internal/hls/restored", {
    token: config.HLS_CDN_TOKEN,
    json: [{ hls_id: id, profile, ok: true, bytes: 4800000000 }]
  });
  assert.equal(rRest.status, 200);
  assert.equal(store.getAsset(id).profiles[profile].status, "ready");

  const rVar2 = await variant(tok, 730006, "S6.mkv");
  assert.equal(rVar2.status, 200);
  assert.equal(rVar2.json.hls.status, "ready");
  assert.equal(rVar2.json.hls.from_archive, undefined);

  assert.equal(hls.pendingRestores().items.some(x => x.hls_id === id), false);
});

test("админка: cold-версия при флаге отмечена from_archive", async () => {
  config.HLS_ARCHIVE_STREAM = true;
  const { id, profile } = makeCold(730007, "S7.mkv", "fp-stream-730007");

  const st = hls.adminState();
  const asset = st.assets.find(x => x.hls_id === id);
  assert.ok(asset);
  assert.equal(asset.profiles[profile].from_archive, true);
  assert.equal(st.configured.archive_stream, true);

  config.HLS_ARCHIVE_STREAM = false;
  const stOff = hls.adminState();
  const assetOff = stOff.assets.find(x => x.hls_id === id);
  assert.ok(assetOff);
  assert.equal(assetOff.profiles[profile].from_archive, false);
  assert.equal(stOff.configured.archive_stream, false);
});

test("плеер: pickSource и playerState с from_archive", () => {
  const resp = {
    url: "https://mirror.example/file.mkv",
    hls: {
      status: "ready",
      url: "https://site.example/api/hls/abc/master.m3u8?exp=1&sig=x",
      profile: "v1",
      shift_ms: 0,
      from_archive: true,
      direct_ok: false,
      direct: null
    }
  };
  const CHROME = {
    hevcMse: true,
    avcMse: true,
    nativeHls: false,
    nativeHevc: false,
    nativeAvc: false,
    safari: false,
    canPlay: () => false
  };

  const src = hlsPlayer.pickSource(resp, CHROME);
  assert.equal(src.kind, "hls");
  assert.equal(src.engine, "hlsjs");
  assert.equal(src.fromArchive, true);
  assert.equal(src.url, resp.hls.url);

  const ps = hlsPlayer.playerState({ source: src });
  assert.equal(ps.kind, "hls");
  assert.equal(ps.fromArchive, true);

  delete resp.hls.from_archive;
  const srcNoArch = hlsPlayer.pickSource(resp, CHROME);
  assert.equal(srcNoArch.fromArchive, undefined);
  assert.equal(hlsPlayer.playerState({ source: srcNoArch }).fromArchive, undefined);

  assert.equal(typeof hlsPlayer.ARCHIVE_NOTE, "string");
  assert.ok(hlsPlayer.ARCHIVE_NOTE.length > 0);
  assert.ok(hlsPlayer.ARCHIVE_NOTE.includes("архив"));
});

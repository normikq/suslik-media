"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-hls-archive-test-"));
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

const fixtureArchivedRequest = require("../../docs/hls-fixtures/archived-request.json");
const fixtureEvictedArchived = require("../../docs/hls-fixtures/evicted-request-archived.json");
const fixturePendingRestores = require("../../docs/hls-fixtures/pending-restores.json");
const fixtureRestoredRequest = require("../../docs/hls-fixtures/restored-request.json");
const fixturePendingDeletesArchive = require("../../docs/hls-fixtures/pending-deletes-archive.json");
const fixtureVariantRestoring = require("../../docs/hls-fixtures/variant-url-restoring.json");

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
  return { id: r.hls_id, profile: r.profile };
}

function makeCold(adId, name, seed) {
  const { id, profile } = makeReady(adId, name, 3000000000, seed);
  hls.archived([{ hls_id: id, profile, bytes: 4800000000, at: 1767225600 }]);
  hls.evicted([{ hls_id: id, profile, last_access: 1, archived: true }]);
  return { id, profile };
}

test("archived по HTTP помечает версию как заархивированную", async () => {
  const { id, profile } = makeReady(720001, "Arch1.mkv", 3000000000, "fp-arch-720001");

  const r = await call("POST", "/internal/hls/archived", {
    token: config.HLS_CDN_TOKEN,
    json: [
      { hls_id: id, profile, bytes: 4800000000, at: 1767225600 },
      { hls_id: "ffffffffffffffff", profile: "v1", bytes: 1, at: 1 }
    ]
  });
  assert.equal(r.status, 200);

  const ver = store.getAsset(id).profiles[profile];
  assert.equal(ver.archived, true);
  assert.equal(ver.archive_bytes, 4800000000);
  assert.equal(ver.archived_at, 1767225600);
});

test("evicted с архивом переводит версию в cold, без архива — удаляет", async () => {
  const { id: id1, profile: p1 } = makeReady(720002, "Ev1.mkv", 3000000000, "fp-arch-720002");
  hls.archived([{ hls_id: id1, profile: p1, bytes: 4800000000, at: 1767225600 }]);

  const rEv1 = await call("POST", "/internal/hls/evicted", {
    token: config.HLS_CDN_TOKEN,
    json: [{ hls_id: id1, profile: p1, last_access: 1, archived: true }]
  });
  assert.equal(rEv1.status, 200);
  assert.equal(store.getAsset(id1).profiles[p1].status, "cold");
  assert.notEqual(store.getAsset(id1), null);
  assert.notEqual(store.getAlias("720002", "Ev1.mkv"), null);

  const exp = Math.floor(Date.now() / 1000) + 3600;
  const rMaster = await call("GET", `/api/hls/${id1}/master.m3u8?exp=${exp}&sig=${hls.signMaster(id1, exp)}`);
  assert.equal(rMaster.status, 404);

  // Незаархивированный ассет при archived: false удаляется
  const { id: id2, profile: p2 } = makeReady(720003, "Ev2.mkv", 3000000000, "fp-arch-720003");
  const rEv2 = await call("POST", "/internal/hls/evicted", {
    token: config.HLS_CDN_TOKEN,
    json: [{ hls_id: id2, profile: p2, last_access: 1, archived: false }]
  });
  assert.equal(rEv2.status, 200);
  assert.equal(store.getAsset(id2), null);

  // Уже заархивированный ассет без поля archived в evicted всё равно становится cold
  const { id: id3, profile: p3 } = makeReady(720004, "Ev3.mkv", 3000000000, "fp-arch-720004");
  hls.archived([{ hls_id: id3, profile: p3, bytes: 4800000000, at: 1767225600 }]);
  const rEv3 = await call("POST", "/internal/hls/evicted", {
    token: config.HLS_CDN_TOKEN,
    json: [{ hls_id: id3, profile: p3, last_access: 1 }]
  });
  assert.equal(rEv3.status, 200);
  assert.equal(store.getAsset(id3).profiles[p3].status, "cold");
});

test("variant-url на cold-версии запускает восстановление и попадает в pending-restores", async () => {
  const tok = deviceToken("arch-user-720005", "allow");
  const filePath = "Cold1.mkv";
  treeCache.set(720005, [
    { name: filePath, path: filePath, type: "file", size: 3000000000, sourceLink: "https://alldebrid.com/f/720005" }
  ]);
  const { id, profile } = makeCold(720005, filePath, "fp-arch-720005");

  const rVar = await call("POST", "/api/file/variant-url", {
    headers: { "X-Device-Token": tok },
    json: { ad_id: 720005, path: filePath }
  });
  assert.equal(rVar.status, 200);
  assert.equal(rVar.json.hls.status, "restoring");
  assert.equal(rVar.json.hls.eta, 150);
  assert.equal(rVar.json.hls.profile, profile);

  const rPending = await call("GET", "/internal/hls/pending-restores", {
    token: config.HLS_CDN_TOKEN
  });
  assert.equal(rPending.status, 200);
  const item = rPending.json.items.find(x => x.hls_id === id && x.profile === profile);
  assert.ok(item);
  assert.deepEqual(
    Object.keys(item).sort(),
    Object.keys(fixturePendingRestores.items[0]).sort()
  );

  assert.equal(hls.statusFor("720005", filePath).status, "restoring");
});

test("restored ok по HTTP возвращает версию в ready и открывает master", async () => {
  const { id, profile } = makeCold(720006, "RestOk.mkv", "fp-arch-720006");
  hls.requestRestore(id, profile);

  const rRest = await call("POST", "/internal/hls/restored", {
    token: config.HLS_CDN_TOKEN,
    json: [{ hls_id: id, profile, ok: true, bytes: 4800000000 }]
  });
  assert.equal(rRest.status, 200);

  const asset = store.getAsset(id);
  assert.equal(asset.profiles[profile].status, "ready");
  assert.ok(asset.last_access >= Math.floor(Date.now() / 1000) - 5);

  const exp = Math.floor(Date.now() / 1000) + 3600;
  const rMaster = await call("GET", `/api/hls/${id}/master.m3u8?exp=${exp}&sig=${hls.signMaster(id, exp)}`);
  assert.equal(rMaster.status, 200);

  assert.equal(hls.pendingRestores().items.some(x => x.hls_id === id), false);
});

test("restored с ошибкой not_in_archive удаляет ассет и ставит файл в очередь с приоритетом 0", async () => {
  const tok = deviceToken("arch-user-720007", "allow");
  const filePath = "NotInArch.mkv";
  treeCache.set(720007, [
    { name: filePath, path: filePath, type: "file", size: 3000000000, sourceLink: "https://alldebrid.com/f/720007" }
  ]);
  const { id, profile } = makeCold(720007, filePath, "fp-arch-720007");

  const rVar = await call("POST", "/api/file/variant-url", {
    headers: { "X-Device-Token": tok },
    json: { ad_id: 720007, path: filePath }
  });
  assert.equal(rVar.status, 200);

  const rRest = await call("POST", "/internal/hls/restored", {
    token: config.HLS_CDN_TOKEN,
    json: [{ hls_id: id, profile, ok: false, error: "not_in_archive" }]
  });
  assert.equal(rRest.status, 200);
  assert.equal(store.getAsset(id), null);

  const job = store.jobForFile("720007", filePath);
  assert.ok(job);
  assert.equal(job.priority, 0);
  assert.equal(job.state, "queued");
});

test("три таймаута восстановления сбрасывают ассет и перепостановляют файл", () => {
  const T0 = Date.now();
  const { id, profile } = makeCold(720008, "Timeout3.mkv", "fp-arch-720008");
  hls.requestRestore(id, profile, T0);

  assert.ok(hls.pendingRestores(T0 + 1801e3).items.some(x => x.hls_id === id));
  assert.ok(hls.pendingRestores(T0 + 2 * 1801e3).items.some(x => x.hls_id === id));
  assert.equal(hls.pendingRestores(T0 + 3 * 1801e3).items.some(x => x.hls_id === id), false);

  assert.equal(store.getAsset(id), null);
  assert.ok(store.jobForFile("720008", "Timeout3.mkv"));
});

test("три ошибки восстановления rclone сбрасывают ассет", () => {
  const { id, profile } = makeCold(720009, "Fail3.mkv", "fp-arch-720009");
  hls.requestRestore(id, profile);

  const r1 = hls.restored([{ hls_id: id, profile, ok: false, error: "rclone" }]);
  assert.equal(r1.failed, 1);
  assert.ok(hls.pendingRestores().items.some(x => x.hls_id === id));

  const r2 = hls.restored([{ hls_id: id, profile, ok: false, error: "rclone" }]);
  assert.equal(r2.failed, 1);
  assert.ok(hls.pendingRestores().items.some(x => x.hls_id === id));

  const r3 = hls.restored([{ hls_id: id, profile, ok: false, error: "rclone" }]);
  assert.equal(r3.dropped, 1);
  assert.equal(store.getAsset(id), null);
});

test("pending-deletes выставляет флаг archive только для заархивированных версий", async () => {
  const { id: idA, profile: pA } = makeReady(720010, "DelA.mkv", 3000000000, "fp-arch-720010");
  hls.archived([{ hls_id: idA, profile: pA, bytes: 4800000000, at: 1767225600 }]);
  hls.requestDelete(idA, pA);

  const { id: idB, profile: pB } = makeReady(720011, "DelB.mkv", 3000000000, "fp-arch-720011");
  hls.requestDelete(idB, pB);

  const rPending = await call("GET", "/internal/hls/pending-deletes", {
    token: config.HLS_CDN_TOKEN
  });
  assert.equal(rPending.status, 200);

  const allowedKeys = new Set(
    fixturePendingDeletesArchive.items.flatMap(x => Object.keys(x))
  );
  for (const item of rPending.json.items) {
    for (const k of Object.keys(item)) {
      assert.ok(allowedKeys.has(k), `неожиданный ключ ${k}`);
    }
  }

  const itemA = rPending.json.items.find(x => x.hls_id === idA && x.profile === pA);
  const itemB = rPending.json.items.find(x => x.hls_id === idB && x.profile === pB);
  assert.ok(itemA);
  assert.equal(itemA.archive, true);
  assert.ok(itemB);
  assert.equal(Object.prototype.hasOwnProperty.call(itemB, "archive"), false);

  const rEv = await call("POST", "/internal/hls/evicted", {
    token: config.HLS_CDN_TOKEN,
    json: [{ hls_id: idA, profile: pA, last_access: 1, archived: false }]
  });
  assert.equal(rEv.status, 200);
  assert.equal(store.getAsset(idA), null);
});

test("удаление cold-версии через админку отменяет восстановление и ставит archive в pending-deletes", async () => {
  const adminTok = deviceToken("arch-admin-720012", "admin");
  const { id, profile } = makeCold(720012, "DelCold.mkv", "fp-arch-720012");

  const rDel = await call("POST", "/api/hls/admin/delete", {
    headers: { "X-Device-Token": adminTok },
    json: { hls_id: id, profile }
  });
  assert.equal(rDel.status, 200);

  const pendingDeletes = hls.pendingDeletes().items;
  assert.ok(pendingDeletes.some(x => x.hls_id === id && x.profile === profile && x.archive === true));

  assert.throws(() => hls.requestRestore(id, profile), e => e.code === "not_cold");
  assert.equal(hls.pendingRestores().items.some(x => x.hls_id === id), false);
});

test("проверка токенов на новых маршрутах архива", async () => {
  const routes = [
    ["POST", "/internal/hls/archived", []],
    ["GET", "/internal/hls/pending-restores", undefined],
    ["POST", "/internal/hls/restored", []]
  ];

  for (const [method, url, json] of routes) {
    const rNone = await call(method, url, { json });
    assert.equal(rNone.status, 401);

    const rWrong = await call(method, url, { token: "nope", json });
    assert.equal(rWrong.status, 401);

    const rWorker = await call(method, url, { token: config.HLS_WORKER_TOKEN, json });
    assert.equal(rWorker.status, 401);

    const rCdn = await call(method, url, { token: config.HLS_CDN_TOKEN, json });
    assert.equal(rCdn.status, 200);
  }
});

test("фикстуры архива принимаются сервером и совпадают по схеме", async () => {
  const rArch = await call("POST", "/internal/hls/archived", {
    token: config.HLS_CDN_TOKEN,
    json: fixtureArchivedRequest
  });
  assert.equal(rArch.status, 200);

  const rEv = await call("POST", "/internal/hls/evicted", {
    token: config.HLS_CDN_TOKEN,
    json: fixtureEvictedArchived
  });
  assert.equal(rEv.status, 200);

  const rRest = await call("POST", "/internal/hls/restored", {
    token: config.HLS_CDN_TOKEN,
    json: fixtureRestoredRequest
  });
  assert.equal(rRest.status, 200);

  const rPending = await call("GET", "/internal/hls/pending-restores", {
    token: config.HLS_CDN_TOKEN
  });
  assert.equal(rPending.status, 200);
  assert.ok(Array.isArray(rPending.json.items));

  const tok = deviceToken("arch-user-720013", "allow");
  const filePath = "FixtRest.mkv";
  treeCache.set(720013, [
    { name: filePath, path: filePath, type: "file", size: 3000000000, sourceLink: "https://alldebrid.com/f/720013" }
  ]);
  makeCold(720013, filePath, "fp-arch-720013");

  const rVar = await call("POST", "/api/file/variant-url", {
    headers: { "X-Device-Token": tok },
    json: { ad_id: 720013, path: filePath }
  });
  assert.equal(rVar.status, 200);

  const fixtureKeys = Object.keys(fixtureVariantRestoring.hls);
  for (const k of Object.keys(rVar.json.hls)) {
    assert.ok(fixtureKeys.includes(k), `неожиданный ключ hls.${k}`);
  }
  assert.equal(rVar.json.hls.status, fixtureVariantRestoring.hls.status);
  assert.equal(typeof rVar.json.hls.eta, "number");
});

test("перезапуск хранилища сохраняет состояние cold и запрос восстановления", () => {
  const { id, profile } = makeCold(720014, "Restart.mkv", "fp-arch-720014");
  hls.requestRestore(id, profile, Date.now(), { user: true });
  store.flush();
  store._reset({ reload: true });

  const ver = store.getAsset(id).profiles[profile];
  assert.equal(ver.status, "cold");
  assert.ok(ver.restore_requested_at);
  assert.ok(hls.pendingRestores().items.some(x => x.hls_id === id));
  assert.equal(hls.statusFor("720014", "Restart.mkv").status, "restoring");
});

test("админский restore и отображение архива в admin/state", async () => {
  const userTok = deviceToken("arch-user-720015", "allow");
  const adminTok = deviceToken("arch-admin-720015", "admin");

  const rAnon = await call("POST", "/api/hls/admin/restore", {
    json: { hls_id: "x", profile: "v1" }
  });
  assert.equal(rAnon.status, 401);

  const rUser = await call("POST", "/api/hls/admin/restore", {
    headers: { "X-Device-Token": userTok },
    json: { hls_id: "x", profile: "v1" }
  });
  assert.equal(rUser.status, 403);
  assert.equal(rUser.json.code, "forbidden");

  const { id, profile } = makeCold(720015, "AdmRest.mkv", "fp-arch-720015");

  const r1 = await call("POST", "/api/hls/admin/restore", {
    headers: { "X-Device-Token": adminTok },
    json: { hls_id: id, profile }
  });
  assert.equal(r1.status, 200);
  assert.equal(r1.json.ok, true);
  assert.equal(r1.json.already, false);

  const r2 = await call("POST", "/api/hls/admin/restore", {
    headers: { "X-Device-Token": adminTok },
    json: { hls_id: id, profile }
  });
  assert.equal(r2.status, 200);
  assert.equal(r2.json.already, true);

  const { id: readyId, profile: readyProfile } = makeReady(720016, "AdmReady.mkv", 3000000000, "fp-arch-720016");
  const rReady = await call("POST", "/api/hls/admin/restore", {
    headers: { "X-Device-Token": adminTok },
    json: { hls_id: readyId, profile: readyProfile }
  });
  assert.equal(rReady.status, 409);
  assert.equal(rReady.json.code, "not_cold");

  const rState = await call("GET", "/api/hls/admin/state", {
    headers: { "X-Device-Token": adminTok }
  });
  assert.equal(rState.status, 200);
  assert.ok(rState.json.totals.archived >= 1);
  assert.ok(rState.json.totals.archive_bytes >= 4800000000);
  assert.ok(rState.json.totals.restoring >= 1);

  const assetEntry = rState.json.assets.find(a => a.hls_id === id);
  assert.ok(assetEntry);
  assert.equal(assetEntry.profiles[profile].status, "restoring");
  assert.equal(assetEntry.profiles[profile].archived, true);
});

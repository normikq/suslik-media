"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-hls-admin-test-"));
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

test("админка: без входа 401, не админ 403, админ 200 — все четыре маршрута", async () => {
  const userTok = deviceToken("admin-test-allow-1", "allow");
  const adminTok = deviceToken("admin-test-admin-1", "admin");

  const routes = [
    ["GET", "/api/hls/admin/state", undefined],
    ["POST", "/api/hls/admin/retry", { job_id: "x" }],
    ["POST", "/api/hls/admin/reencode", { ad_id: 1, path: "a.mkv" }],
    ["POST", "/api/hls/admin/delete", { hls_id: "x" }]
  ];

  for (const [method, url, json] of routes) {
    // Без входа — 401
    const rAnon = await call(method, url, { json });
    assert.equal(rAnon.status, 401, `${method} ${url} без входа`);

    // Обычный пользователь — 403 forbidden
    const rUser = await call(method, url, { headers: { "X-Device-Token": userTok }, json });
    assert.equal(rUser.status, 403, `${method} ${url} не админ`);
    assert.equal(rUser.json.code, "forbidden");

    // Админ — пускает (state -> 200, остальные не 401 и не 403)
    const rAdmin = await call(method, url, { headers: { "X-Device-Token": adminTok }, json });
    assert.notEqual(rAdmin.status, 401, `${method} ${url} админ не 401`);
    assert.notEqual(rAdmin.status, 403, `${method} ${url} админ не 403`);
    if (url === "/api/hls/admin/state") {
      assert.equal(rAdmin.status, 200);
    }
  }
});

test("retry: упавшее задание снова в очереди, в аренде — 409 busy", async () => {
  const adminTok = deviceToken("admin-test-admin-2", "admin");

  const { job } = hls.enqueue({ ad_id: "710001", path: "Fail.mkv", size: 1000, priority: 2 });
  job.state = "failed";
  job.attempts = 3;
  job.error = { stage: "encode", error: "boom", at: 1 };
  store.putJob(job.job_id, job);

  const rRetry = await call("POST", "/api/hls/admin/retry", {
    headers: { "X-Device-Token": adminTok },
    json: { job_id: job.job_id }
  });
  assert.equal(rRetry.status, 200);
  assert.equal(rRetry.json.ok, true);

  const updated = store.getJob(job.job_id);
  assert.equal(updated.state, "queued");
  assert.equal(updated.attempts, 0);
  assert.equal(updated.error, undefined);

  // Задание в аренде — 409 busy
  const { job: busyJob } = hls.enqueue({ ad_id: "710002", path: "Busy.mkv", size: 1000, priority: 2 });
  busyJob.state = "running";
  busyJob.leased_until = Math.floor(Date.now() / 1000) + 600;
  store.putJob(busyJob.job_id, busyJob);

  const rBusy = await call("POST", "/api/hls/admin/retry", {
    headers: { "X-Device-Token": adminTok },
    json: { job_id: busyJob.job_id }
  });
  assert.equal(rBusy.status, 409);
  assert.equal(rBusy.json.code, "busy");
  assert.ok(ui.errorText(409, rBusy.json).startsWith("Задание сейчас в работе"));
});

test("reencode: задание с приоритетом 0 и hls_id заменяемого ассета", async () => {
  const adminTok = deviceToken("admin-test-admin-3", "admin");

  treeCache.set(710003, [
    { name: "Film.mkv", path: "Film.mkv", type: "file", size: 2000000000, sourceLink: "https://alldebrid.com/f/710003" }
  ]);
  const oldId = makeReady(710003, "Film.mkv", 2000000000, "fp-admin-710003");

  const rRe = await call("POST", "/api/hls/admin/reencode", {
    headers: { "X-Device-Token": adminTok },
    json: { ad_id: 710003, path: "Film.mkv", hls_id: oldId }
  });
  assert.equal(rRe.status, 200);
  assert.equal(rRe.json.job.priority, 0);
  assert.equal(rRe.json.job.profile, "v1");
  assert.equal(store.getJob(rRe.json.job.job_id).force, true);

  const rState = await call("GET", "/api/hls/admin/state", {
    headers: { "X-Device-Token": adminTok }
  });
  assert.equal(rState.status, 200);
  const entry = rState.json.queue.find(j => j.job_id === rRe.json.job.job_id);
  assert.ok(entry);
  assert.equal(entry.priority, 0);
  assert.equal(entry.force, true);

  // Повторное перекодирование, пока первое в очереди, — 409 already_queued, кнопка заблокирована
  const rAgain = await call("POST", "/api/hls/admin/reencode", {
    headers: { "X-Device-Token": adminTok },
    json: { ad_id: 710003, path: "Film.mkv", hls_id: oldId }
  });
  assert.equal(rAgain.status, 409);
  assert.equal(rAgain.json.code, "already_queued");
  assert.equal(ui.errorText(409, rAgain.json), "Перекодирование уже в очереди.");
  assert.equal(rState.json.queue.filter(j => j.force).length, 1);
  assert.ok(ui.reencodingAssets(rState.json.queue).has(oldId));
  assert.equal(ui.reencodingAssets([{ force: true, state: "failed", hls_id: oldId }]).size, 0);

  // Отсутствующий файл — 404 unknown_file
  const rMissing = await call("POST", "/api/hls/admin/reencode", {
    headers: { "X-Device-Token": adminTok },
    json: { ad_id: 710003, path: "Nope.mkv" }
  });
  assert.equal(rMissing.status, 404);
  assert.equal(rMissing.json.code, "unknown_file");
  assert.equal(ui.errorText(404, rMissing.json), "Файла больше нет в раздаче.");
});

test("delete: профиль ждёт удаления на CDN, после evicted запись исчезает", async () => {
  const adminTok = deviceToken("admin-test-admin-4", "admin");

  const id = makeReady(710004, "Del.mkv", 2000000000, "fp-admin-710004");
  const profile = store.getAsset(id).active_profile;

  const rDel = await call("POST", "/api/hls/admin/delete", {
    headers: { "X-Device-Token": adminTok },
    json: { hls_id: id }
  });
  assert.equal(rDel.status, 200);
  assert.ok(rDel.json.pending_delete.includes(profile));
  assert.equal(rDel.json.removed, false);

  const rState1 = await call("GET", "/api/hls/admin/state", {
    headers: { "X-Device-Token": adminTok }
  });
  assert.equal(rState1.status, 200);
  const assetInState = rState1.json.assets.find(a => a.hls_id === id);
  assert.ok(assetInState);
  assert.ok(assetInState.pending_delete.includes(profile));

  const rPending = await call("GET", "/internal/hls/pending-deletes", { token: config.HLS_CDN_TOKEN });
  assert.equal(rPending.status, 200);
  const pendingList = Array.isArray(rPending.json) ? rPending.json : rPending.json.items;
  assert.ok(pendingList.some(x => x.hls_id === id && x.profile === profile));

  const rEvicted = await call("POST", "/internal/hls/evicted", {
    token: config.HLS_CDN_TOKEN,
    json: [{ hls_id: id, profile, last_access: 1 }]
  });
  assert.equal(rEvicted.status, 200);
  assert.equal(store.getAsset(id), null);

  const rState2 = await call("GET", "/api/hls/admin/state", {
    headers: { "X-Device-Token": adminTok }
  });
  assert.equal(rState2.status, 200);
  assert.equal(rState2.json.assets.find(a => a.hls_id === id), undefined);

  // Повторное удаление — 404 unknown_asset
  const rDel2 = await call("POST", "/api/hls/admin/delete", {
    headers: { "X-Device-Token": adminTok },
    json: { hls_id: id }
  });
  assert.equal(rDel2.status, 404);
  assert.equal(rDel2.json.code, "unknown_asset");
});

test("cdn-stats попадают в state.cdn", async () => {
  const adminTok = deviceToken("admin-test-admin-5", "admin");

  const rStats = await call("POST", "/internal/hls/cdn-stats", {
    token: config.HLS_CDN_TOKEN,
    json: { disk_total: 1000, disk_used: 250, assets: 3, profiles: 4, at: 123 }
  });
  assert.equal(rStats.status, 200);

  const rState = await call("GET", "/api/hls/admin/state", {
    headers: { "X-Device-Token": adminTok }
  });
  assert.equal(rState.status, 200);
  assert.equal(rState.json.cdn.disk_used, 250);
  assert.equal(rState.json.cdn.disk_total, 1000);
  assert.equal(typeof rState.json.cdn.received_at, "number");
  assert.equal(ui.percent(250, 1000), 25);
});

test("страница: hls-admin.html отдаётся со CSP без inline-скриптов", async () => {
  const rHtml = await call("GET", "/hls-admin.html");
  assert.equal(rHtml.status, 200);
  assert.match(rHtml.headers.get("content-type") || "", /html/);
  const csp = rHtml.headers.get("content-security-policy") || "";
  assert.ok(csp.includes("script-src 'self'"));
  assert.ok(csp.includes("frame-ancestors 'none'"));
  assert.match(rHtml.text, /<script src="hls-admin.js"/);
  assert.doesNotMatch(rHtml.text, /<script(?![^>]*\bsrc=)[^>]*>/i);
  assert.doesNotMatch(rHtml.text, /\son[a-z]+\s*=/i);

  const rJs = await call("GET", "/hls-admin.js");
  assert.equal(rJs.status, 200);
  assert.match(rJs.headers.get("content-type") || "", /javascript/);
});

test("POST админки с cookie с чужого сайта отклоняется", async () => {
  const r = await call("POST", "/api/hls/admin/retry", {
    headers: { Cookie: "movieportal.sid=abc", "Sec-Fetch-Site": "cross-site" },
    json: { job_id: "x" }
  });
  assert.equal(r.status, 403);
});

test("hls-admin.js: статусы архива и ошибка not_cold", () => {
  assert.equal(ui.profileStatusText("cold"), "в архиве");
  assert.equal(ui.profileStatusText("restoring"), "достаём из архива");
  assert.equal(ui.profileStatusText("ready"), "готово");
  assert.equal(ui.errorText(409, { error: "Версии нет в архиве.", code: "not_cold" }), "Этой версии нет в архиве.");
});

test("hls-admin.js: форматирование и тексты ошибок", () => {
  assert.equal(typeof globalThis.document, "undefined");

  assert.equal(ui.formatBytes(null), "—");
  assert.equal(ui.formatBytes(512), "512 Б");
  assert.equal(ui.formatBytes(1536), "1,5 КБ");
  assert.equal(ui.formatBytes(1610612736), "1,5 ГБ");
  assert.equal(ui.formatBytes(200 * 1024 * 1024), "200 МБ");
  assert.equal(ui.formatBytes(1024 * 1024), "1 МБ");

  assert.equal(ui.formatEta(null), "—");
  assert.equal(ui.formatEta(30), "меньше минуты");
  assert.equal(ui.formatEta(600), "10 мин");
  assert.equal(ui.formatEta(3600), "1 ч");
  assert.equal(ui.formatEta(3900), "1 ч 5 мин");

  assert.equal(ui.formatDuration(6720000), "1 ч 52 мин");
  assert.equal(ui.formatDuration(0), "—");

  assert.equal(ui.formatAgo(null, 100), "никогда");
  assert.equal(ui.formatAgo(100, 130), "только что");
  assert.equal(ui.formatAgo(100, 100 + 300), "5 мин назад");
  assert.equal(ui.formatAgo(100, 100 + 7200), "2 ч назад");
  assert.equal(ui.formatAgo(1, 1 + 86400), "1 день назад");
  assert.equal(ui.formatAgo(1, 1 + 3 * 86400), "3 дня назад");
  assert.equal(ui.formatAgo(1, 1 + 5 * 86400), "5 дней назад");

  assert.equal(ui.percent(1, 0), null);
  assert.equal(ui.percent(50, 200), 25);
  assert.equal(ui.percent(300, 200), 100);

  assert.equal(ui.progressPercent(0.37), 37);
  assert.equal(ui.progressPercent(null), 0);
  assert.equal(ui.progressPercent(2), 100);

  assert.equal(ui.fileName("A/B/C.mkv"), "C.mkv");
  assert.equal(ui.fileName(""), "—");

  assert.equal(ui.stateText("failed"), "Ошибка");
  assert.equal(ui.stageText("upload"), "загрузка на CDN");
  assert.equal(ui.priorityText(0), "Смотрят сейчас");

  assert.equal(ui.errorText(0, null), "Нет связи с сервером.");
  assert.equal(ui.errorText(401, {}), "Нужно войти на сайт.");
  assert.equal(ui.errorText(403, { code: "forbidden", error: "x" }), "Нет доступа.");
  assert.equal(ui.errorText(404, { code: "unknown_asset" }), "Запись уже удалена.");
  assert.equal(ui.errorText(404, { code: "unknown_profile" }), "Этой версии уже нет.");
  assert.equal(ui.errorText(404, { code: "unknown_job" }), "Задания уже нет.");
  assert.match(ui.errorText(429, null), /Подождите/);
  assert.match(ui.errorText(500, {}), /Ошибка сервера/);
  assert.equal(ui.errorText(400, { code: "bad_request", error: "Нужны ad_id и path." }), "Нужны ad_id и path.");

  assert.deepEqual(ui.missingConfig({ cdn_url: true, sign_secret: false, worker_token: true }), [
    "HLS_SIGN_SECRET",
    "HLS_CDN_TOKEN"
  ]);
});

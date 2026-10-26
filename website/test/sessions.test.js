"use strict";

// «Сессии»: владелец, отзыв ключа устройства вместе с сеансом приложения, общий пароль, защита от CSRF.
const os = require("os");
const fs = require("fs");
const path = require("path");
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-test-"));
process.env.SITE_PASSWORD = "test-password";
process.env.SESSION_SECRET = "test-secret";

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("crypto");
const signature = require("cookie-signature");
const config = require("../src/config");
const { createApp } = require("../src/app");
const { sessionStore } = require("../src/middleware/auth");
const users = require("../src/services/users");

let base;
const server = createApp().listen(0, "127.0.0.1");
test.before(async () => {
  if (!server.listening) await new Promise(resolve => server.once("listening", resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});
test.after(() => server.close());

const SAME = { "Sec-Fetch-Site": "same-origin" };
const userA = users.findOrCreateUser({ id: "1001", username: "a" });
const userB = users.findOrCreateUser({ id: "1002", username: "b" });

// Сеанс в хранилище и подписанная cookie к нему — как после входа через Discord
function seedSession(fields) {
  const sid = crypto.randomBytes(24).toString("base64url");
  const expires = new Date(Date.now() + config.SESSION_MAX_AGE_MS);
  sessionStore.set(sid, { cookie: { expires, httpOnly: true, path: "/" }, authenticated: true, ...fields });
  return { sid, cookie: `movieportal.sid=${encodeURIComponent("s:" + signature.sign(sid, config.SESSION_SECRET))}` };
}

async function call(p, { cookie, token, body, headers } = {}) {
  const h = { ...SAME, ...(headers || {}) };
  if (cookie) h.Cookie = cookie;
  if (token) h["X-Device-Token"] = token;
  if (body !== undefined) h["Content-Type"] = "application/json";
  const res = await fetch(base + p, { method: body !== undefined ? "POST" : "GET", headers: h, body: body !== undefined ? JSON.stringify(body) : undefined });
  return { status: res.status, json: await res.json().catch(() => null), setCookie: res.headers.getSetCookie() };
}

// Привязка приложения по коду: ключ устройства и cookie-сеанс приложения
async function pairApp(userId) {
  const start = await call("/api/auth/device", { body: { name: "Тестовый ТВ" } });
  users.authorizeDevicePairing(start.json.user_code, userId);
  const poll = await call("/api/auth/device/poll", { body: { code: start.json.code } });
  const cookie = poll.setCookie.map(c => c.split(";")[0]).find(c => c.startsWith("movieportal.sid="));
  return { token: poll.json.device_token, cookie };
}

async function passwordLogin() {
  const res = await call("/api/login", { body: { password: "test-password" }, headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0" } });
  assert.equal(res.status, 200);
  return res.setCookie.map(c => c.split(";")[0]).find(c => c.startsWith("movieportal.sid="));
}

test("sessions: only own sessions are listed and revocable", async () => {
  const a = seedSession({ uid: userA.id, meta: { ua: "Firefox/130 Linux", at: Date.now() } });
  const b = seedSession({ uid: userB.id, meta: { ua: "Chrome/130 Windows", at: Date.now() } });
  const listA = (await call("/api/sessions", { cookie: a.cookie })).json.sessions;
  const listB = (await call("/api/sessions", { cookie: b.cookie })).json.sessions;
  assert.equal(listA.filter(s => s.kind === "browser").length, 1);
  assert.ok(listA.every(s => !("_sid" in s) && !("_hash" in s)));
  const foreign = listB.find(s => s.current).id;
  assert.equal((await call("/api/sessions/revoke", { cookie: a.cookie, body: { id: foreign } })).status, 404);
  assert.equal((await call("/api/sessions", { cookie: b.cookie })).status, 200);
});

test("sessions: revoking a device key also signs out the app's cookie session", async () => {
  const owner = seedSession({ uid: userA.id, meta: { ua: "Firefox", at: Date.now() } });
  const app = await pairApp(userA.id);
  assert.ok(app.token && app.cookie);
  const dev = (await call("/api/sessions", { cookie: owner.cookie })).json.sessions.find(s => s.kind === "device" && s.name === "Тестовый ТВ");
  assert.ok(dev);
  assert.equal((await call("/api/sessions/revoke", { cookie: owner.cookie, body: { id: dev.id } })).status, 200);
  assert.equal((await call("/api/sessions", { token: app.token })).status, 401);
  assert.equal((await call("/api/sessions", { cookie: app.cookie })).status, 401);
});

test("sessions: an app session issued before tagging is bound to the key on first use", async () => {
  const owner = seedSession({ uid: userA.id, meta: { ua: "Firefox", at: Date.now() } });
  const app = await pairApp(userA.id);
  const legacy = seedSession({ uid: userA.id }); // старый сеанс Android: без meta
  assert.equal((await call("/api/sessions", { cookie: legacy.cookie, token: app.token })).status, 200);
  const names = (await call("/api/sessions", { cookie: owner.cookie })).json.sessions;
  const legacyId = "b" + crypto.createHash("sha256").update(legacy.sid).digest("hex").slice(0, 16);
  assert.ok(!names.some(s => s.id === legacyId), "старый сеанс приложения больше не виден как «Браузер»");
  const dev = names.find(s => s.kind === "device" && s.name === "Тестовый ТВ");
  await call("/api/sessions/revoke", { cookie: owner.cookie, body: { id: dev.id } });
  assert.equal((await call("/api/sessions", { cookie: legacy.cookie, token: app.token })).status, 401);
  assert.equal((await call("/api/sessions", { cookie: legacy.cookie })).status, 401);
});

test("sessions: shared password users do not see or revoke each other", async () => {
  const one = await passwordLogin();
  const two = await passwordLogin();
  const list = await call("/api/sessions", { cookie: one });
  assert.equal(list.json.shared, true);
  assert.deepEqual(list.json.sessions.map(s => s.current), [true]);
  assert.equal(list.json.sessions[0].name, "Chrome · Windows");
  assert.equal((await call("/api/sessions/revoke-others", { cookie: one, body: {} })).json.revoked, 0);
  assert.equal((await call("/api/sessions", { cookie: two })).status, 200);
});

test("sessions: signing out the current session clears the cookie and it stays gone", async () => {
  const me = seedSession({ uid: userB.id, meta: { ua: "Firefox", at: Date.now() } });
  const cur = (await call("/api/sessions", { cookie: me.cookie })).json.sessions.find(s => s.current);
  const res = await call("/api/sessions/revoke", { cookie: me.cookie, body: { id: cur.id } });
  assert.equal(res.json.current, true);
  const set = res.setCookie.filter(c => c.startsWith("movieportal.sid="));
  assert.ok(set.length >= 1 && set.every(c => /^movieportal\.sid=;/.test(c)), set.join(" | "));
  // Запрос, начатый до отзыва, не может записать сеанс обратно
  sessionStore.set(me.sid, { cookie: { expires: new Date(Date.now() + 1e6) }, authenticated: true, uid: userB.id });
  assert.equal((await call("/api/sessions", { cookie: me.cookie })).status, 401);
});

test("sessions: stale bound is clamped and the current session is kept", async () => {
  const me = seedSession({ uid: userB.id, meta: { ua: "Firefox", at: Date.now() } });
  const res = await call("/api/sessions/revoke-stale", { cookie: me.cookie, body: { days: -5 } });
  assert.equal(res.status, 200);
  assert.equal((await call("/api/sessions", { cookie: me.cookie })).status, 200);
});

test("csrf: cookie writes from another site are refused, apps and same-origin pass", async () => {
  const me = seedSession({ uid: userA.id, meta: { ua: "Firefox", at: Date.now() } });
  const cross = await call("/api/sessions/revoke-others", { cookie: me.cookie, body: {}, headers: { "Sec-Fetch-Site": "same-site" } });
  assert.equal(cross.status, 403);
  const oldBrowser = await fetch(base + "/api/sessions/revoke-others", {
    method: "POST", headers: { Cookie: me.cookie, Origin: "https://evil.example", "Content-Type": "application/x-www-form-urlencoded" }, body: "x=1"
  });
  assert.equal(oldBrowser.status, 403);
  const host = new URL(base).host;
  const sameOld = await fetch(base + "/api/sessions/revoke-stale", { method: "POST", headers: { Cookie: me.cookie, Origin: `http://${host}`, "Content-Type": "application/json" }, body: "{}" });
  assert.equal(sameOld.status, 200);
  // Приложение по cookie без Origin (Android) и запрос с ключом устройства не затрагиваются
  const native = await fetch(base + "/api/sessions/revoke-stale", { method: "POST", headers: { Cookie: me.cookie, "Content-Type": "application/json" }, body: "{}" });
  assert.equal(native.status, 200);
  assert.equal((await call("/api/sessions", { cookie: me.cookie })).status, 200);
});

"use strict";

// Правки по аудиту website/ (ноябрь 2026): лимиты, роль общего пароля, очередь ffprobe, запись JSON, SSE, ссылки
const os = require("os");
const fs = require("fs");
const path = require("path");
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-test-"));

const test = require("node:test");
const assert = require("node:assert/strict");

const { clientKey } = require("../src/middleware/rate-limit");
const { issueActivityToken } = require("../src/services/activity");
const { SHARED_USER } = require("../src/services/users");
const { withFfprobeSlot } = require("../src/services/metadata");
const { makeJsonWriter } = require("../src/utils/json-writer");
const { publicBase, sseSender } = require("../src/utils/helpers");

const reqWith = (auth, ip = "203.0.113.7") => ({ ip, get: h => (h.toLowerCase() === "authorization" ? auth : "") });

test("лимит: поддельный ключ Activity считается по адресу, настоящий — по ключу", () => {
  const byIp = clientKey(reqWith(""));
  const fake1 = "Bearer sa_" + "0".repeat(48);
  const fake2 = "Bearer sa_" + "1".repeat(48);
  assert.equal(clientKey(reqWith(fake1)), byIp);
  assert.equal(clientKey(reqWith(fake2)), byIp);
  const { token } = issueActivityToken("shared");
  const real = clientKey(reqWith("Bearer " + token));
  assert.notEqual(real, byIp);
  assert.match(real, /^sa:/);
});

test("общий пароль — не админ", () => {
  assert.equal(SHARED_USER.role, "user");
});

test("ffprobe: фон занимает не больше одного места и пропускает вперёд интерфейс", async () => {
  const order = [];
  const gates = [];
  const job = (name, opts) =>
    withFfprobeSlot(() => {
      order.push(name);
      return new Promise(resolve => gates.push(resolve));
    }, opts);
  const all = [job("bg1", { background: true }), job("bg2", { background: true }), job("ui1"), job("ui2")];
  await new Promise(r => setImmediate(r));
  // bg1 и ui1 работают, bg2 ждёт второго фонового места, ui2 — свободного
  assert.deepEqual(order, ["bg1", "ui1"]);
  gates.shift()(); // bg1 закончился: место отдаётся ждущему ui2, а не bg2
  await new Promise(r => setImmediate(r));
  assert.deepEqual(order, ["bg1", "ui1", "ui2"]);
  gates.shift()(); // ui1
  await new Promise(r => setImmediate(r));
  assert.deepEqual(order, ["bg1", "ui1", "ui2", "bg2"]);
  while (gates.length) gates.shift()();
  await Promise.all(all);
});

test("запись JSON: асинхронно, изменения во время записи не теряются, flush пишет сразу", async () => {
  const file = path.join(process.env.DATA_DIR, "writer.json");
  let value = 1;
  const save = makeJsonWriter(file, () => ({ value }), 5);
  save();
  await new Promise(r => setTimeout(r, 60));
  assert.deepEqual(JSON.parse(fs.readFileSync(file, "utf8")), { value: 1 });
  value = 2;
  save();
  save.flush();
  assert.deepEqual(JSON.parse(fs.readFileSync(file, "utf8")), { value: 2 });
  assert.deepEqual(fs.readdirSync(process.env.DATA_DIR).filter(f => f.startsWith("writer.json.") && f.endsWith(".tmp")), []);
});

test("publicBase: https по умолчанию, http только для локального адреса по http", () => {
  const req = (host, secure = false) => ({ secure, get: h => (h === "host" ? host : ""), app: { get: () => false } });
  assert.equal(publicBase(req("example.org")), "https://example.org");
  assert.equal(publicBase(req("127.0.0.1:3000")), "http://127.0.0.1:3000");
  assert.equal(publicBase(req("192.168.1.5:3000")), "http://192.168.1.5:3000");
  assert.equal(publicBase(req("localhost:3000", true)), "https://localhost:3000");
});

test("SSE: клиент не читает — поток закрывается, а не копит память", () => {
  let destroyed = false;
  const written = [];
  const res = { destroyed: false, writableEnded: false, writableLength: 0, write: t => written.push(t), destroy: () => { destroyed = true; } };
  const send = sseSender(res);
  send("a");
  res.writableLength = 2 * 1024 * 1024;
  send("b");
  assert.deepEqual(written, ["a"]);
  assert.equal(destroyed, true);
});

test("/join/<код>/ перенаправляется без слэша и с параметрами", async () => {
  const { createApp } = require("../src/app");
  const app = createApp();
  const server = app.listen(0);
  try {
    const { port } = server.address();
    const r = await fetch(`http://127.0.0.1:${port}/join/123456/?autoplay=1`, { redirect: "manual" });
    assert.equal(r.status, 301);
    assert.match(r.headers.get("location"), /\/join\/123456\?autoplay=1$/);
  } finally {
    server.close();
  }
});

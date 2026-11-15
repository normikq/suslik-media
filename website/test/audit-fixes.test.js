"use strict";

// Правки по аудиту 16.11: куки, SSRF, испорченные файлы данных, запасной текст tr(), ведущий комнаты.
const os = require("os");
const fs = require("fs");
const path = require("path");
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-test-"));

const test = require("node:test");
const assert = require("node:assert/strict");
const { readCookie, isPrivateAddress, publicOnlyLookup } = require("../src/utils/helpers");
const { readJsonFile } = require("../src/utils/json-writer");
const { tr } = require("../public/i18n");

test("readCookie: точка в имени — буква, а не любой символ", () => {
  const req = cookie => ({ headers: { cookie } });
  assert.equal(readCookie(req("movieportal.sid=abc"), "movieportal.sid"), "abc");
  assert.equal(readCookie(req("movieportalXsid=evil"), "movieportal.sid"), "");
  assert.equal(readCookie(req("a=1; movieportal.sid=x%2Fy"), "movieportal.sid"), "x%2Fy");
});

test("isPrivateAddress: внутренние адреса", () => {
  for (const ip of ["127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254",
    "100.64.0.1", "0.0.0.0", "::1", "::", "fc00::1", "fd12::1", "fe80::1", "::ffff:127.0.0.1", "[::1]"]) {
    assert.equal(isPrivateAddress(ip), true, ip);
  }
  for (const ip of ["8.8.8.8", "172.32.0.1", "1.1.1.1", "2606:4700::1111", "::ffff:8.8.8.8"]) {
    assert.equal(isPrivateAddress(ip), false, ip);
  }
});

test("publicOnlyLookup: имя, ведущее на 127.0.0.1, не соединяется", async () => {
  const err = await new Promise(resolve => publicOnlyLookup("127.0.0.1", {}, e => resolve(e)));
  assert.equal(err && err.code, "EPRIVATEADDR");
  const all = await new Promise(resolve => publicOnlyLookup("localhost", { all: true }, e => resolve(e)));
  assert.equal(all && all.code, "EPRIVATEADDR");
});

test("readJsonFile: нет файла — null; испорченный откладывается, а не затирается", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-json-"));
  assert.equal(readJsonFile(path.join(dir, "none.json")), null);
  const ok = path.join(dir, "ok.json");
  fs.writeFileSync(ok, '{"a":1}');
  assert.deepEqual(readJsonFile(ok), { a: 1 });
  const bad = path.join(dir, "bad.json");
  fs.writeFileSync(bad, '{"a":');
  assert.equal(readJsonFile(bad), null);
  assert.equal(fs.existsSync(bad), false);
  const kept = fs.readdirSync(dir).filter(n => n.startsWith("bad.json.corrupt-"));
  assert.equal(kept.length, 1);
  assert.equal(fs.readFileSync(path.join(dir, kept[0]), "utf8"), '{"a":');
});

test("tr: строка вторым аргументом — запасной текст", () => {
  assert.equal(tr("no.such.key", "Отменить загрузку?"), "Отменить загрузку?");
  assert.equal(tr("no.such.key", { n: 2 }, "{n} шт."), "2 шт.");
  assert.equal(tr("no.such.key"), "no.such.key");
});

test("sync: ключ дорожек — только для «<id>|<путь>», остальное как есть", () => {
  const sync = require("../src/services/sync");
  // Не падает и не превращает произвольный ключ в «m…»: запрос без записи — пустой ответ
  assert.deepEqual(sync.lookup("dev_" + "c".repeat(32), { key: "*" }), { progress: null, trackChoice: null });
});

test("комната: ведущий вернулся и снова ушёл — минута отсчитывается заново", async () => {
  const rooms = require("../src/services/rooms");
  const host = { id: "u_host", name: "Host" };
  const guest = { id: "u_guest", name: "Guest" };
  const room = rooms.createRoom(host, null, "all", "c-host");
  rooms.joinRoom(room.id, host, "c-host");
  rooms.joinRoom(room.id, guest, "c-guest");
  rooms.leaveRoom(room.id, "c-host", true);
  assert.ok(room.host_absent_since > 0);
  // Ушёл давно — но вернулся: отметка сбрасывается
  room.host_absent_since = Date.now() - 50 * 1000;
  rooms.joinRoom(room.id, host, "c-host");
  assert.equal(room.host_absent_since, 0);
  // Снова ушёл: отсчёт новый, ведущий остаётся за ним
  rooms.leaveRoom(room.id, "c-host", true);
  assert.ok(Date.now() - room.host_absent_since < 1000);
  assert.equal(room.host_id, "u_host");
  if (room.host_timer) clearTimeout(room.host_timer);
});

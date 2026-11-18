"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { createCacheChecker, hashesForIds } = require("../src/services/cachecheck");

const H = c => c.repeat(40);

function fake({ account = [], ready = {}, owners = new Set() } = {}) {
  const log = { uploads: [], removed: [], changed: 0, accountCalls: 0 };
  let nextId = 100;
  let t = 0;
  const deps = {
    async account() { log.accountCalls++; return account; },
    async upload(hashes) {
      log.uploads.push(hashes);
      return hashes.map(h => (h in ready ? { hash: h, id: nextId++, ready: ready[h] } : null));
    },
    async remove(id) { log.removed.push(id); },
    isProtected: id => owners.has(id),
    changed() { log.changed++; },
    now: () => t
  };
  return { deps, log, tick: ms => { t += ms; } };
}

test("кешированные и нет, все добавленные проверкой удаляются", async () => {
  const { deps, log } = fake({ ready: { [H("a")]: true, [H("b")]: false } });
  const c = createCacheChecker(deps);
  const r = await c.check([H("a"), H("B"), "junk"]);
  assert.deepEqual(r, { [H("a")]: true, [H("b")]: false });
  assert.deepEqual(log.removed.sort(), ["100", "101"]);
  assert.equal(log.changed, 1);
});

test("раздачи из медиатеки не загружаются и не удаляются", async () => {
  const { deps, log } = fake({ account: [{ id: 7, hash: H("a"), statusCode: 4 }, { id: 8, hash: H("b"), statusCode: 1 }] });
  const c = createCacheChecker(deps);
  assert.deepEqual(await c.check([H("a"), H("b")]), { [H("a")]: true, [H("b")]: false });
  assert.equal(log.uploads.length, 0);
  assert.equal(log.removed.length, 0);
});

test("AllDebrid вернул id уже существующей раздачи — не удаляем", async () => {
  const { deps, log } = fake({ account: [{ id: 100, hash: "" }], ready: { [H("c")]: false } });
  const c = createCacheChecker(deps);
  await c.check([H("c")]);
  assert.equal(log.removed.length, 0);
});

test("раздачу с владельцем не удаляем", async () => {
  const { deps, log } = fake({ ready: { [H("d")]: false }, owners: new Set(["100"]) });
  await createCacheChecker(deps).check([H("d")]);
  assert.equal(log.removed.length, 0);
});

test("ошибка по раздаче — неизвестно, ответ без неё", async () => {
  const { deps } = fake({ ready: {} });
  assert.deepEqual(await createCacheChecker(deps).check([H("e")]), {});
});

test("результат запоминается; некешированное — на 30 минут", async () => {
  const { deps, log, tick } = fake({ ready: { [H("a")]: true, [H("b")]: false } });
  const c = createCacheChecker(deps);
  await c.check([H("a"), H("b")]);
  await c.check([H("a"), H("b")]);
  assert.equal(log.uploads.length, 1);
  tick(31 * 60 * 1000);
  await c.check([H("a"), H("b")]);
  assert.deepEqual(log.uploads[1], [H("b")]);
});

test("одновременные запросы одного хэша — одна проверка; settle ждёт её", async () => {
  const { deps, log } = fake({ ready: { [H("f")]: true } });
  const c = createCacheChecker(deps);
  const p1 = c.check([H("f")]);
  const p2 = c.check([H("f")]);
  let settled = false;
  const s = c.settle(H("f")).then(() => { settled = true; });
  const [r1, r2] = await Promise.all([p1, p2]);
  await s;
  assert.ok(settled);
  assert.deepEqual(r1, r2);
  assert.equal(log.uploads.length, 1);
});

test("не больше 80 хэшей, пачками по 10", async () => {
  const ready = {};
  const hashes = [];
  for (let i = 0; i < 90; i++) {
    const h = i.toString(16).padStart(40, "0");
    hashes.push(h);
    ready[h] = i % 2 === 0;
  }
  const { deps, log } = fake({ ready });
  const r = await createCacheChecker(deps).check(hashes);
  assert.equal(Object.keys(r).length, 80);
  assert.deepEqual(log.uploads.map(u => u.length), [10, 10, 10, 10, 10, 10, 10, 10]);
});

test("отказ AllDebrid по раздаче — повтор мелкими пачками после удаления", async () => {
  const { deps, log } = fake({ ready: { [H("a")]: false, [H("b")]: false } });
  let first = true;
  const upload = deps.upload;
  deps.upload = async hashes => {
    if (first) { first = false; log.uploads.push(hashes); return hashes.map(h => (h === H("b") ? { error: "MAGNET_TOO_MANY_ACTIVE" } : { hash: h, id: 1, ready: false })); }
    return upload(hashes);
  };
  const r = await createCacheChecker(deps).check([H("a"), H("b")]);
  assert.deepEqual(r, { [H("a")]: false, [H("b")]: false });
  assert.deepEqual(log.uploads[1], [H("b")]);
});

test("хэш раздачи закрытого трекера — со страницы раздачи, один раз", async () => {
  const results = {
    a: { infoHash: H("a") },
    b: { lazy: { topic: 1 } },
    c: { lazy: { topic: 2 } },
    d: { magnet: "magnet:?xt=urn:btih:" + H("d").toUpperCase() }
  };
  let resolves = 0;
  const ts = {
    getResult: id => results[id],
    hashFromMagnet: m => (/btih:([0-9a-z]+)/i.exec(m || "") || [])[1] || "",
    async resolveLazy(r) { resolves++; return r.lazy.topic === 1 ? { infoHash: H("b"), magnet: "" } : { infoHash: "", magnet: "" }; }
  };
  assert.deepEqual(await hashesForIds(["a", "b", "c", "d", "zzz", 5], ts), { a: H("a"), b: H("b"), d: H("d") });
  assert.deepEqual(await hashesForIds(["b", "c"], ts), { b: H("b") });
  assert.equal(resolves, 2);
});

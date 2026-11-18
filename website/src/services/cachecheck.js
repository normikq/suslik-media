"use strict";

// «Есть ли раздача в кеше AllDebrid» — для пометок в поиске.
// magnet/instant AllDebrid отключил в конце 2024, поэтому проверка такая: раздачи пачкой добавляются в аккаунт
// (magnet/upload), кешированные AllDebrid отдаёт сразу с ready=true, и все добавленные проверкой тут же удаляются.
// Никогда не удаляется то, что уже было в аккаунте до проверки, и то, что за это время кто-то добавил сам
// (у раздачи появился владелец или идёт /api/search/add с тем же хэшем — он ждёт конца проверки через settle()).

const CACHED_TTL_MS = 6 * 60 * 60 * 1000; // кешированное редко пропадает
const UNCACHED_TTL_MS = 30 * 60 * 1000; // некешированное может докачаться у кого-то ещё
const MAX_HASHES = 60; // за один запрос клиента
const MAX_CHECK = 80; // вместе с хэшами, найденными по id
const CHUNK = 10; // магнетов в одном magnet/upload
const RETRY_CHUNK = 3;
const RETRY_ROUNDS = 3;
const MAX_KNOWN = 20000;

const HASH_RE = /^[0-9a-f]{40}$/;

function normHash(h) {
  const v = String(h || "").trim().toLowerCase();
  return HASH_RE.test(v) ? v : "";
}

/**
 * deps: {
 *   account(): Promise<Array<{ id, hash, statusCode }>>  — что сейчас в аккаунте,
 *   upload(hashes): Promise<Array<{ hash, id, ready } | { error } | null>> — magnet/upload пачкой, по порядку,
 *   remove(id): Promise<void>,
 *   isProtected(id): boolean — у раздачи есть владелец (её добавили сами),
 *   changed(): void — сбросить кеш медиатеки,
 *   now(): number
 * }
 */
function createCacheChecker(deps) {
  const now = deps.now || Date.now;
  const known = new Map(); // hash -> { cached, at }
  const pending = new Map(); // hash -> Promise (идёт проверка)
  let queue = Promise.resolve(); // проверки к AllDebrid — по одной

  function remember(hash, cached) {
    known.delete(hash);
    known.set(hash, { cached, at: now() });
    if (known.size > MAX_KNOWN) known.delete(known.keys().next().value);
  }

  function fresh(hash) {
    const k = known.get(hash);
    if (!k) return undefined;
    if (now() - k.at > (k.cached ? CACHED_TTL_MS : UNCACHED_TTL_MS)) {
      known.delete(hash);
      return undefined;
    }
    return k.cached;
  }

  async function probe(hashes) {
    const out = new Map();
    const inAccount = new Map(); // hash -> statusCode
    const accountIds = new Set();
    for (const m of await deps.account()) {
      if (m && m.id !== undefined) accountIds.add(String(m.id));
      const h = normHash(m && m.hash);
      if (h) inAccount.set(h, Number(m.statusCode));
    }
    const toUpload = [];
    for (const h of hashes) {
      // Уже в медиатеке: готово — значит, AllDebrid её отдаёт; качается — значит, кеша не было
      if (inAccount.has(h)) out.set(h, inAccount.get(h) === 4);
      else toUpload.push(h);
    }
    let touched = false;
    // Отказ по отдельной раздаче (чаще всего «слишком много активных загрузок») — повтор мелкими пачками,
    // когда добавленные проверкой уже удалены
    let todo = toUpload;
    for (let round = 0; round < RETRY_ROUNDS && todo.length; round++) {
      const size = round === 0 ? CHUNK : RETRY_CHUNK;
      const failed = [];
      for (let i = 0; i < todo.length; i += size) {
        const part = todo.slice(i, i + size);
        let items;
        try {
          items = await deps.upload(part);
        } catch (err) {
          console.warn("[cachecheck] upload:", err.message);
          failed.push(...todo.slice(i));
          break;
        }
        const victims = [];
        const errors = new Set();
        part.forEach((h, idx) => {
          const it = items && items[idx];
          if (!it || it.id === undefined || it.id === null) {
            failed.push(h);
            if (it && it.error) errors.add(String(it.error));
            return;
          }
          out.set(h, Boolean(it.ready));
          const id = String(it.id);
          if (!accountIds.has(id)) victims.push(id);
        });
        if (errors.size) console.warn("[cachecheck] AllDebrid отказал:", [...errors].join(", "));
        if (victims.length) touched = true;
        await Promise.all(victims.filter(id => !deps.isProtected(id)).map(id =>
          deps.remove(id).catch(err => console.warn("[cachecheck] delete", id, err.message))));
      }
      todo = failed;
    }
    if (touched) deps.changed();
    return out;
  }

  /** hashes -> { hash: true|false } (неизвестные не попадают в ответ) */
  async function check(rawHashes) {
    const hashes = [...new Set((Array.isArray(rawHashes) ? rawHashes : []).map(normHash).filter(Boolean))].slice(0, MAX_CHECK);
    const result = {};
    const waits = [];
    const missing = [];
    for (const h of hashes) {
      const v = fresh(h);
      if (v !== undefined) result[h] = v;
      else if (pending.has(h)) waits.push(pending.get(h).then(m => { if (m.has(h)) result[h] = m.get(h); }));
      else missing.push(h);
    }
    if (missing.length) {
      const job = queue.then(() => probe(missing)).then(m => {
        for (const [h, v] of m) remember(h, v);
        return m;
      }, err => {
        console.warn("[cachecheck]", err.message);
        return new Map();
      });
      queue = job.then(() => {}, () => {});
      for (const h of missing) pending.set(h, job);
      job.finally(() => { for (const h of missing) if (pending.get(h) === job) pending.delete(h); });
      waits.push(job.then(m => { for (const h of missing) if (m.has(h)) result[h] = m.get(h); }));
    }
    await Promise.all(waits);
    return result;
  }

  /** Дождаться конца проверки этого хэша (перед настоящим добавлением, чтобы проверка не удалила его). */
  async function settle(hash) {
    const p = pending.get(normHash(hash));
    if (p) await p.catch(() => {});
  }

  /** Раздачу добавили в медиатеку — теперь её статус известен точнее. */
  function noteAdded(hash, ready) {
    const h = normHash(hash);
    if (h && ready) remember(h, true);
  }

  return { check, settle, noteAdded, _known: known };
}

// ---- Связка с AllDebrid ---------------------------------------------------------------------------

let instance = null;
function get() {
  if (instance) return instance;
  const axios = require("axios");
  const config = require("../config");
  const { adAuth } = require("./alldebrid");
  const { hasOwner } = require("./downloads");
  const torrentSearch = require("../../search");
  instance = createCacheChecker({
    async account() {
      const r = await axios.post(`${config.ALLDEBRID_API_BASE}/v4.1/magnet/status`, new URLSearchParams(), {
        headers: { ...adAuth(), "Content-Type": "application/x-www-form-urlencoded" },
        timeout: 20000,
        validateStatus: () => true
      });
      if (r.status < 200 || r.status >= 300 || r.data?.status === "error") throw new Error("magnet/status failed");
      const raw = r.data?.data?.magnets;
      const list = Array.isArray(raw) ? raw : (raw && typeof raw === "object" ? [raw] : []);
      return list.map(m => ({ id: m.id, hash: m.hash, statusCode: Number(m.statusCode ?? 0) }));
    },
    async upload(hashes) {
      const body = new URLSearchParams();
      for (const h of hashes) body.append("magnets[]", torrentSearch.buildMagnet(h, h));
      const r = await axios.post(`${config.ALLDEBRID_API_BASE}/v4/magnet/upload`, body, {
        headers: { ...adAuth(), "Content-Type": "application/x-www-form-urlencoded" },
        timeout: 30000,
        validateStatus: () => true
      });
      if (r.status < 200 || r.status >= 300 || r.data?.status === "error") {
        throw new Error(r.data?.error?.message || `magnet/upload ${r.status}`);
      }
      const items = Array.isArray(r.data?.data?.magnets) ? r.data.data.magnets : [];
      // Ответ по порядку запроса; на всякий случай сверяем по хэшу
      const byHash = new Map();
      for (const it of items) {
        const h = normHash(it && it.hash);
        if (h && !it.error) byHash.set(h, it);
      }
      return hashes.map((h, i) => {
        const it = byHash.get(h) || (items[i] && !items[i].error && !items[i].hash ? items[i] : null);
        if (it) return { hash: h, id: it.id, ready: Boolean(it.ready) };
        const bad = items[i] && items[i].error;
        return bad ? { error: bad.code || bad.message || "error" } : null;
      });
    },
    async remove(id) {
      await require("./cleanup").deleteMagnet(id);
    },
    isProtected: id => hasOwner(id),
    changed: () => require("./library").libraryChanged()
  });
  return instance;
}

// ---- Раздачи закрытых трекеров: хэш есть только на странице раздачи --------------------------------

const MAX_IDS = 20; // страниц раздач за один запрос клиента
const RESOLVE_PARALLEL = 4;

/** id результатов поиска -> { id: hash } (у кого хэш узнать не вышло, тех нет). Узнанный хэш запоминается в результате. */
async function hashesForIds(rawIds, torrentSearch = require("../../search")) {
  const ids = [...new Set((Array.isArray(rawIds) ? rawIds : []).filter(x => typeof x === "string" && x.length <= 64))].slice(0, MAX_IDS);
  const out = {};
  const lazy = [];
  for (const id of ids) {
    const r = torrentSearch.getResult(id);
    if (!r) continue;
    const h = normHash(r.infoHash) || normHash(torrentSearch.hashFromMagnet(r.magnet)) || normHash(r.resolvedHash);
    if (h) out[id] = h;
    else if (r.lazy && !r.resolveFailed) lazy.push([id, r]);
  }
  let next = 0;
  async function worker() {
    while (next < lazy.length) {
      const [id, r] = lazy[next++];
      try {
        const x = await torrentSearch.resolveLazy(r);
        const h = normHash(x.infoHash) || normHash(torrentSearch.hashFromMagnet(x.magnet));
        if (h) { r.resolvedHash = h; out[id] = h; } else r.resolveFailed = true;
      } catch (err) {
        console.warn("[cachecheck] resolve:", err.message);
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(RESOLVE_PARALLEL, lazy.length) }, worker));
  return out;
}

module.exports = { createCacheChecker, get, hashesForIds, normHash, MAX_HASHES, MAX_IDS };

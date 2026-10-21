"use strict";

const fs = require("fs");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");

// ---- Синхронизация просмотра по аккаунту Discord ---------------------------------------------------------------
// Что хранится у пользователя (u_<id>), чтобы ПК, телефон и ТВ продолжали с одного места:
//  - watch: прогресс файла, ключ "magnetId|path" (как watchKey в приложениях) -> { p: позиция, l: длина, a: когда смотрели };
//  - tracks: выбранные озвучка/субтитры, ключ "m<magnetId>" (фильм или сериал) или "*" (последний выбор вообще)
//    -> { au: дорожка, sp: субтитры ("__off__" — выключены), sc: файл озвучки рядом с фильмом (Android), a: когда выбрали }.
// У каждой записи ещё c — когда сервер её изменил (по нему клиенты берут «что нового с прошлого раза») и u — когда ею
// пользовались. Пользовались — записали её или смотрели этот фильм: прогресс фильма продлевает и его дорожки, и "*".
// Запись, которой не пользовались SYNC_IDLE_DAYS дней (10), удаляется. Раздача удалена из AllDebrid — её записи тоже.
// Удаления клиентам не рассылаются: у них остаётся своё, сервер просто перестаёт это хранить.
const OWNER_RE = /^u_\d{1,25}$/;
const WATCH_KEY_RE = /^(\d{1,20})\|.{1,600}$/s;
const TRACK_KEY_RE = /^(?:\*|m(\d{1,20}))$/;
const MAX_WATCH = 3000;
const MAX_TRACKS = 1000;
const MAX_USERS = 2000;
const MAX_BATCH = 500; // записей одного вида в одном запросе
const SWEEP_EVERY_MS = 60 * 60 * 1000;

const idleMs = () => config.SYNC_IDLE_DAYS * 24 * 3600 * 1000;

const users = new Map(); // owner -> { w: Map(key -> entry), t: Map(key -> entry) }

function num(v) {
  const n = Number(v);
  return Number.isFinite(n) && n >= 0 ? Math.floor(n) : null;
}

function text(v) {
  if (v === null || v === undefined || v === "") return null;
  if (typeof v !== "string") return undefined; // не строка — запись некорректна
  // Управляющие символы (кроме таба) — мусор: клиенты кладут это в свои файлы настроек
  return v.length <= 300 && !/[\u0000-\u0008\u000a-\u001f\u007f]/.test(v) ? v : undefined;
}

function load() {
  try {
    const saved = JSON.parse(fs.readFileSync(config.SYNC_FILE, "utf8"));
    for (const [owner, data] of Object.entries(saved || {})) {
      if (!OWNER_RE.test(owner) || !data || typeof data !== "object") continue;
      const w = new Map();
      const t = new Map();
      for (const [k, e] of Object.entries(data.w || {})) if (WATCH_KEY_RE.test(k) && e && num(e.u) !== null) w.set(k, e);
      for (const [k, e] of Object.entries(data.t || {})) if (TRACK_KEY_RE.test(k) && e && num(e.u) !== null) t.set(k, e);
      if (w.size || t.size) users.set(owner, { w, t });
    }
  } catch {}
}
load();

const persist = makeJsonWriter(
  config.SYNC_FILE,
  () => Object.fromEntries([...users].map(([o, d]) => [o, { w: Object.fromEntries(d.w), t: Object.fromEntries(d.t) }])),
  3000
);

function bucket(owner, create) {
  let d = users.get(owner);
  if (!d && create) {
    d = { w: new Map(), t: new Map() };
    users.set(owner, d);
    if (users.size > MAX_USERS) {
      // давно не заходивший пользователь уходит первым
      let oldest = null;
      let oldestAt = Infinity;
      for (const [o, x] of users) {
        if (o === owner) continue;
        const at = lastUse(x);
        if (at < oldestAt) { oldest = o; oldestAt = at; }
      }
      if (oldest) users.delete(oldest);
    }
  }
  return d || null;
}

function lastUse(d) {
  let at = 0;
  for (const e of d.w.values()) if (e.u > at) at = e.u;
  for (const e of d.t.values()) if (e.u > at) at = e.u;
  return at;
}

// Больше лимита — уходят записи, которыми дольше всего не пользовались
function trim(map, max) {
  if (map.size <= max) return;
  const order = [...map].sort((a, b) => a[1].u - b[1].u);
  for (const [k] of order.slice(0, map.size - max)) map.delete(k);
}

function touch(d, key, now) {
  const e = d.t.get(key);
  if (e) e.u = now;
}

/**
 * Принять записи клиента. body: { watch: [{ key, pos, len, at }], tracks: [{ key, audio, spu, sidecar, at }] }.
 * Побеждает запись с более поздним `at` (время клиента, но не позже, чем сейчас на сервере). Старше срока — не принимаются.
 * Возвращает { watch, tracks } — сколько записей принято.
 */
function apply(owner, body, now = Date.now()) {
  if (!OWNER_RE.test(String(owner || ""))) return { watch: 0, tracks: 0 };
  const watch = Array.isArray(body?.watch) ? body.watch.slice(0, MAX_BATCH) : [];
  const tracks = Array.isArray(body?.tracks) ? body.tracks.slice(0, MAX_BATCH) : [];
  if (!watch.length && !tracks.length) return { watch: 0, tracks: 0 };
  const d = bucket(owner, true);
  const oldest = now - idleMs();
  let nw = 0;
  let nt = 0;
  for (const it of watch) {
    const key = typeof it?.key === "string" ? it.key : "";
    const m = WATCH_KEY_RE.exec(key);
    const pos = num(it?.pos);
    const len = num(it?.len) ?? 0;
    let at = num(it?.at);
    if (!m || pos === null || at === null) continue;
    at = Math.min(at, now);
    if (at < oldest) continue;
    const old = d.w.get(key);
    if (old && old.a > at) continue;
    d.w.set(key, { p: pos, l: len, a: at, c: now, u: now });
    touch(d, "m" + m[1], now);
    touch(d, "*", now);
    nw++;
  }
  for (const it of tracks) {
    const key = typeof it?.key === "string" ? it.key : "";
    let at = num(it?.at);
    const au = text(it?.audio);
    const sp = text(it?.spu);
    const sc = text(it?.sidecar);
    if (!TRACK_KEY_RE.test(key) || at === null || au === undefined || sp === undefined || sc === undefined) continue;
    if (au === null && sp === null && sc === null) continue;
    at = Math.min(at, now);
    if (at < oldest) continue;
    const old = d.t.get(key);
    if (old && old.a > at) continue;
    // Поле, которое клиент не прислал, остаётся прежним: ПК не знает о файле озвучки, Android — о субтитрах, выбранных на ПК
    d.t.set(key, {
      au: au ?? old?.au ?? null,
      sp: sp ?? old?.sp ?? null,
      sc: sc ?? old?.sc ?? null,
      a: at,
      c: now,
      u: now
    });
    nt++;
  }
  trim(d.w, MAX_WATCH);
  trim(d.t, MAX_TRACKS);
  if (!d.w.size && !d.t.size) users.delete(owner);
  if (nw || nt) persist();
  return { watch: nw, tracks: nt };
}

/** Записи, изменённые на сервере не раньше since (0 — все). Клиент запоминает `now` и шлёт его в следующий раз. */
function changesSince(owner, since = 0, now = Date.now()) {
  const out = { now, watch: [], tracks: [] };
  const d = OWNER_RE.test(String(owner || "")) ? users.get(owner) : null;
  if (!d) return out;
  const from = num(since) ?? 0;
  for (const [key, e] of d.w) if (e.c >= from) out.watch.push({ key, pos: e.p, len: e.l, at: e.a });
  for (const [key, e] of d.t) {
    if (e.c >= from) out.tracks.push({ key, audio: e.au ?? null, spu: e.sp ?? null, sidecar: e.sc ?? null, at: e.a });
  }
  return out;
}

/** Удалить всё, чем не пользовались дольше срока. Возвращает, сколько записей удалено. */
function sweep(now = Date.now()) {
  const oldest = now - idleMs();
  let n = 0;
  for (const [owner, d] of users) {
    for (const map of [d.w, d.t]) {
      for (const [k, e] of map) {
        if (!(e.u >= oldest)) { map.delete(k); n++; }
      }
    }
    if (!d.w.size && !d.t.size) users.delete(owner);
  }
  if (n) persist();
  return n;
}

/** Раздачи больше нет: её прогресс и дорожки не нужны никому. */
function dropMagnet(id) {
  const key = String(id ?? "");
  if (!/^\d{1,20}$/.test(key)) return;
  const prefix = key + "|";
  let changed = false;
  for (const [owner, d] of users) {
    for (const k of d.w.keys()) if (k.startsWith(prefix)) { d.w.delete(k); changed = true; }
    if (d.t.delete("m" + key)) changed = true;
    if (!d.w.size && !d.t.size) users.delete(owner);
  }
  if (changed) persist();
}

/** Только для тестов. */
function _reset() {
  users.clear();
}

sweep();
setInterval(() => sweep(), SWEEP_EVERY_MS).unref();

module.exports = {
  apply,
  changesSince,
  sweep,
  dropMagnet,
  OWNER_RE,
  _reset
};

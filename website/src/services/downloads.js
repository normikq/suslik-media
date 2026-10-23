"use strict";

const fs = require("fs");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");
const { clientIp } = require("../utils/helpers");
const { deviceId } = require("../middleware/auth");

// Limit each client IP (or Discord user) to two unfinished magnet/torrent submissions.
// This is process-local; use a shared store if running multiple Node workers/instances.
const MAX_ACTIVE_DOWNLOADS_PER_IP = 2;
const activeDownloadsByIp = new Map(); // ip -> { ids: Set<string>, pending: number }
const downloadOwnerById = new Map(); // magnet id -> ip

// ---- Владелец раздачи ------------------------------------------------------------------------
// Кто добавил magnet/torrent в библиотеку, тот один и может его удалить/отменить. Устройство определяется
// долгоживущим cookie "sdev" (случайный токен; IP меняется при переходе Wi-Fi <-> мобильная сеть, поэтому
// на него нельзя опираться). Записи без владельца (добавлены раньше или напрямую в AllDebrid) удалять может любой.
// Отключить проверку: OWNER_LOCK=false.
const magnetOwners = new Map(); // magnet id -> { dev, ip, at }
try {
  for (const [k, v] of Object.entries(JSON.parse(fs.readFileSync(config.OWNERS_FILE, "utf8")))) {
    if (v && typeof v.dev === "string") magnetOwners.set(k, v);
  }
} catch {}

const persistOwners = makeJsonWriter(config.OWNERS_FILE, () => Object.fromEntries(magnetOwners), 1000);

let libraryChangedHook = () => {};

function setLibraryChangedHook(fn) {
  if (typeof fn === "function") libraryChangedHook = fn;
}

function recordOwner(id, dev, ip) {
  if (id === undefined || id === null) return;
  // Уже известная раздача (AllDebrid вернул тот же id на повторное добавление): владелец остаётся прежним
  if (magnetOwners.has(String(id))) return;
  magnetOwners.set(String(id), { dev, ip, at: Date.now() });
  if (magnetOwners.size > 5000) {
    for (const k of [...magnetOwners.keys()].slice(0, magnetOwners.size - 5000)) {
      magnetOwners.delete(k);
    }
  }
  persistOwners();
}

function isLibraryAdmin(req) {
  // Админ Discord. Не «shared»: у общего пароля тоже role "admin", но это все сразу
  return Boolean(req.user && req.user.role === "admin" && req.user.id !== "shared");
}

// Кто может убрать раздачу из AllDebrid (у всех): готовую — только админ (остальное чистит автоочистка,
// services/cleanup.js); незаконченную или сломанную загрузку (statusCode не 4) — ещё и тот, кто её добавил
// (без записи о владельце — любой). Неизвестное состояние считается готовой раздачей.
// Проверка для всех раздач одного запроса: cookie устройства читается один раз, а не для каждой раздачи медиатеки.
function removeChecker(req) {
  if (!config.OWNER_LOCK || isLibraryAdmin(req)) return () => true;
  const dev = deviceId(req, null); // только cookie устройства, IP не учитывается
  return (id, statusCode) => {
    const code = Number(statusCode);
    if (statusCode === undefined || statusCode === null || !Number.isFinite(code) || code === 4) return false;
    const o = magnetOwners.get(String(id));
    return !o || o.dev === dev;
  };
}

function canRemove(id, req, statusCode) {
  return removeChecker(req)(id, statusCode);
}

// Раздачу добавил именно это устройство/пользователь (запись об авторе есть и совпадает). Без записи — нет.
function isOwner(id, req) {
  const o = magnetOwners.get(String(id));
  return Boolean(o && o.dev === deviceId(req, null));
}

function deleteOwner(id) {
  if (magnetOwners.delete(String(id))) {
    persistOwners();
    return true;
  }
  return false;
}

/** Раздачи, добавленные этим владельцем (для первого заполнения «Моих фильмов»). */
function ownedBy(owner) {
  const out = [];
  for (const [id, o] of magnetOwners) if (o.dev === owner) out.push(id);
  return out;
}

// Первый вход через Discord: раздачи, добавленные с этого устройства, переходят пользователю
function adoptOwners(fromDev, toUser) {
  if (!fromDev || fromDev === toUser) return 0;
  let n = 0;
  for (const o of magnetOwners.values()) {
    if (o.dev === fromDev) { o.dev = toUser; n++; }
  }
  if (n) persistOwners();
  return n;
}

function ipDownloadState(ip) {
  if (!activeDownloadsByIp.has(ip)) activeDownloadsByIp.set(ip, { ids: new Set(), pending: 0 });
  return activeDownloadsByIp.get(ip);
}

function cleanupIpState(ip, state) {
  if (state && state.ids.size === 0 && state.pending === 0) activeDownloadsByIp.delete(ip);
}

function releaseTrackedDownload(id) {
  const key = String(id);
  const ip = downloadOwnerById.get(key);
  if (!ip) return;
  downloadOwnerById.delete(key);
  const state = activeDownloadsByIp.get(ip);
  if (state) {
    state.ids.delete(key);
    cleanupIpState(ip, state);
  }
}

function reserveDownloadSlot(req, res) {
  const ip = clientIp(req);
  const dev = deviceId(req, res);
  // Вошедший через Discord считается сам по себе (семья за одним роутером, мобильный NAT); общий пароль — по IP
  const slotKey = /^u_\d+$/.test(dev) ? dev : ip;
  const state = ipDownloadState(slotKey);
  if (state.ids.size + state.pending >= MAX_ACTIVE_DOWNLOADS_PER_IP) {
    res.status(429).json({ error: "У вас уже выполняются две загрузки. Дождитесь завершения одной из них или отмените её." });
    return null;
  }
  state.pending += 1;
  let completed = false;
  return {
    dev,
    attach(id, ready) {
      if (completed) return;
      completed = true;
      state.pending = Math.max(0, state.pending - 1);
      recordOwner(id, dev, ip);
      // Добавленное попадает и в общую библиотеку, и в «Мои фильмы» — любым путём: magnet, torrent, поиск.
      // (require внутри: shelf сам зависит от этого модуля)
      if (id !== undefined && id !== null) require("./shelf").setOnShelf(dev, id, true);
      libraryChangedHook(); // a library answer already on its way does not know this torrent: it must not free the slot
      if (!ready && id !== undefined && id !== null) {
        const key = String(id);
        state.ids.add(key);
        downloadOwnerById.set(key, slotKey);
      }
      cleanupIpState(slotKey, state);
    },
    release() {
      if (completed) return;
      completed = true;
      state.pending = Math.max(0, state.pending - 1);
      cleanupIpState(slotKey, state);
    }
  };
}

function cleanupFinishedDownloads(states) {
  for (const id of [...downloadOwnerById.keys()]) {
    if (!states.has(id) || states.get(id) >= 4) releaseTrackedDownload(id);
  }
}

module.exports = {
  MAX_ACTIVE_DOWNLOADS_PER_IP,
  canRemove,
  isOwner,
  removeChecker,
  isLibraryAdmin,
  recordOwner,
  deleteOwner,
  adoptOwners,
  ownedBy,
  releaseTrackedDownload,
  reserveDownloadSlot,
  cleanupFinishedDownloads,
  setLibraryChangedHook
};

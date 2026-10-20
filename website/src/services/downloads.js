"use strict";

const fs = require("fs");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");
const { clientIp } = require("../utils/helpers");
const { deviceId } = require("../middleware/auth");

// Limit each client IP to two unfinished magnet/torrent submissions.
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
  magnetOwners.set(String(id), { dev, ip, at: Date.now() });
  if (magnetOwners.size > 5000) {
    for (const k of [...magnetOwners.keys()].slice(0, magnetOwners.size - 5000)) {
      magnetOwners.delete(k);
    }
  }
  persistOwners();
}

function canRemove(id, req) {
  if (!config.OWNER_LOCK) return true;
  const o = magnetOwners.get(String(id));
  return !o || o.dev === deviceId(req, null); // только cookie устройства, IP не учитывается
}

function deleteOwner(id) {
  if (magnetOwners.delete(String(id))) {
    persistOwners();
    return true;
  }
  return false;
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
  const state = ipDownloadState(ip);
  if (state.ids.size + state.pending >= MAX_ACTIVE_DOWNLOADS_PER_IP) {
    res.status(429).json({ error: "Для этого IP-адреса уже выполняются две загрузки. Дождитесь завершения одной из них или отмените её." });
    return null;
  }
  state.pending += 1;
  let completed = false;
  return {
    attach(id, ready) {
      if (completed) return;
      completed = true;
      state.pending = Math.max(0, state.pending - 1);
      recordOwner(id, dev, ip);
      libraryChangedHook(); // a library answer already on its way does not know this torrent: it must not free the slot
      if (!ready && id !== undefined && id !== null) {
        const key = String(id);
        state.ids.add(key);
        downloadOwnerById.set(key, ip);
      }
      cleanupIpState(ip, state);
    },
    release() {
      if (completed) return;
      completed = true;
      state.pending = Math.max(0, state.pending - 1);
      cleanupIpState(ip, state);
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
  recordOwner,
  deleteOwner,
  releaseTrackedDownload,
  reserveDownloadSlot,
  cleanupFinishedDownloads,
  setLibraryChangedHook
};

"use strict";

const fs = require("fs");
const axios = require("axios");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");
const { treeCache, persistTreeCache } = require("./alldebrid");
const { deleteOwner, releaseTrackedDownload } = require("./downloads");
const { dropEverywhere } = require("./shelf");
const { dropMagnet } = require("./sync");
const customAudio = require("./custom-audio");

// ---- Автоочистка библиотеки ------------------------------------------------------------------------
// Раздачу «открыли», когда кто-то попросил ссылку на её файл (mirror-url, file/unlock, плейлист): так делает любой
// клиент при запуске фильма. Раздачи, которые никто не открывал LIBRARY_IDLE_DAYS дней, удаляются из AllDebrid;
// если раздач больше LIBRARY_MAX_ITEMS — удаляются давно не открытые, пока не останется столько.
// Отсчёт для раздачи, которую сервер видит впервые (новая или до выкладки), — с этого момента: истории открытий
// до выкладки нет, поэтому по сроку ничего не удаляется ещё 10 дней. Выключить: AUTO_CLEAN=false.
const usage = new Map(); // magnet id -> ms последнего открытия
try {
  for (const [id, t] of Object.entries(JSON.parse(fs.readFileSync(config.USAGE_FILE, "utf8")))) {
    if (Number.isFinite(t)) usage.set(String(id), t);
  }
} catch {}
const persistUsage = makeJsonWriter(config.USAGE_FILE, () => Object.fromEntries(usage), 5000);

const SWEEP_EVERY_MS = 60 * 60 * 1000;
const MAX_DELETES_PER_SWEEP = 50; // ошибка в правилах не сотрёт всю библиотеку за раз
let lastSweep = 0;
let sweeping = false;

// ссылка на файл -> id раздачи (из кэша деревьев готовых раздач); строится заново, когда ссылки не нашлось
let linkIndex = new Map();
let linkIndexAt = 0;
function rebuildLinkIndex() {
  linkIndex = new Map();
  for (const [id, files] of treeCache) {
    for (const f of files || []) if (f && f.sourceLink) linkIndex.set(f.sourceLink, String(id));
  }
  linkIndexAt = Date.now();
}

function markOpened(id) {
  const key = String(id ?? "");
  if (!/^\d{1,20}$/.test(key)) return;
  const now = Date.now();
  usage.set(key, now);
  // Фильм открыли — раздачи с его внешними озвучками тоже нужны (не удалять ещё LIBRARY_IDLE_DAYS дней)
  for (const audioId of customAudio.linkedAudioMagnets(Number(key))) usage.set(String(audioId), now);
  persistUsage();
}

function markLinkOpened(link) {
  let id = linkIndex.get(link);
  if (!id && Date.now() - linkIndexAt > 10000) {
    rebuildLinkIndex();
    id = linkIndex.get(link);
  }
  if (id) markOpened(id);
}

function lastOpened(id) {
  return usage.get(String(id));
}

async function deleteMagnet(id) {
  const response = await axios.post(
    `${config.ALLDEBRID_API_BASE}/v4/magnet/delete`,
    new URLSearchParams({ id: String(id) }),
    {
      headers: { Authorization: `Bearer ${config.ALLDEBRID_API_KEY}`, "Content-Type": "application/x-www-form-urlencoded" },
      timeout: 15000,
      validateStatus: () => true
    }
  );
  if (response.status < 200 || response.status >= 300 || response.data?.status === "error") {
    const err = new Error(response.data?.error?.message || "Не удалось удалить раздачу в AllDebrid.");
    err.status = 502;
    throw err;
  }
  forget(id);
  return response.data?.data?.message || "Раздача удалена.";
}

/** Раздачи больше нет: убрать её из всех служебных списков. */
function forget(id) {
  const key = String(id);
  releaseTrackedDownload(key);
  deleteOwner(key);
  dropEverywhere(key);
  dropMagnet(key);
  customAudio.dropMagnet(Number(key));
  if (usage.delete(key)) persistUsage();
  if (treeCache.delete(Number(id))) persistTreeCache();
}

/**
 * Что удалить: [{ id, reason }]. Чистая функция (без сети) — её проверяют тесты.
 * magnets — список из AllDebrid ({ id }), now — текущее время.
 */
function pickForCleanup(magnets, now = Date.now(), opts = {}) {
  const idleMs = (opts.idleDays ?? config.LIBRARY_IDLE_DAYS) * 24 * 3600 * 1000;
  const max = opts.maxItems ?? config.LIBRARY_MAX_ITEMS;
  const items = magnets.map(m => {
    const key = String(m.id);
    let t = usage.get(key);
    if (t === undefined) {
      t = now;
      usage.set(key, t);
      persistUsage();
    }
    return { id: Number(m.id), t };
  });
  const out = [];
  const keep = [];
  for (const it of items) {
    if (now - it.t > idleMs) out.push({ id: it.id, reason: "idle" });
    else keep.push(it);
  }
  if (keep.length > max) {
    // давно не открытые — первыми; при равном времени — добавленные раньше (меньший id)
    keep.sort((a, b) => a.t - b.t || a.id - b.id);
    for (const it of keep.slice(0, keep.length - max)) out.push({ id: it.id, reason: "limit" });
  }
  return out;
}

/** После каждой удачной загрузки библиотеки; работает не чаще раза в час. Возвращает, сколько удалено. */
async function maybeSweep(magnets, onChanged) {
  if (!config.AUTO_CLEAN || sweeping || !Array.isArray(magnets) || !magnets.length) return 0;
  if (Date.now() - lastSweep < SWEEP_EVERY_MS) return 0;
  lastSweep = Date.now();
  sweeping = true;
  let n = 0;
  try {
    for (const { id, reason } of pickForCleanup(magnets).slice(0, MAX_DELETES_PER_SWEEP)) {
      const m = magnets.find(x => x.id === id);
      try {
        await deleteMagnet(id);
        n++;
        console.log(`[cleanup] удалена раздача ${id} «${m?.name || ""}»: ${reason === "idle" ? `не открывали ${config.LIBRARY_IDLE_DAYS} дн.` : `в библиотеке больше ${config.LIBRARY_MAX_ITEMS}`}`);
      } catch (err) {
        console.warn(`[cleanup] не удалось удалить раздачу ${id}:`, err.message);
      }
    }
  } finally {
    sweeping = false;
    if (n && typeof onChanged === "function") onChanged();
  }
  return n;
}

module.exports = {
  markOpened,
  markLinkOpened,
  lastOpened,
  deleteMagnet,
  forget,
  pickForCleanup,
  maybeSweep
};

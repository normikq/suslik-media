"use strict";

const fs = require("fs");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");

// ---- Хранилище HLS (docs/HLS.md §3) -----------------------------------------------------------------------------
// Три JSON-файла в DATA_DIR, запись через makeJsonWriter (атомарно, с задержкой):
//   hls-assets.json  — hls_id → ассет (отпечаток, монтаж, серия, длительность, источник, профили на CDN);
//   hls-aliases.json — "<adId>|<путь>" → { hls_id, shift_ms }: этот файл раздачи играет этот ассет со сдвигом;
//   hls-queue.json   — job_id → задание кодировщика.
// Холодный архив (§17) хранится в записи версии (profiles[<профиль>]) и переживает перезапуск вместе с ассетом:
//   archived, archive_bytes, archived_at — копия проверена в архиве; status "cold" — вытеснена с CDN, есть в архиве;
//   restore_requested_at, restore_attempts, restore_timeouts, restore_error, restore_user, restore_file — запрос возврата.
// Индексы в памяти строятся при старте и поддерживаются при каждом изменении:
//   byFingerprint — отпечаток → hls_id; byReuseKey — "canonical_id|edition|episode" → Set(hls_id);
//   byAlias — hls_id → Set(ключей алиасов) (обратный индекс: вытеснение ассета удаляет его алиасы);
//   jobByFile — "<adId>|<путь>" → job_id, jobByHls — hls_id → job_id (дедупликация очереди, §11).
// Задания «звук» (§18, type: "audio") в индексы файлов не входят: у них нет файла, только hls_id и профиль.
// У ассета внешние озвучки (§18): ext_audio — ключ дорожки → { dir: "xN", … }, pending_delete_paths — [{profile, path}].
// Логика (что ставить, что отвечать воркеру) — в services/hls.js; здесь только данные и индексы.

let assets = new Map();
let aliases = new Map();
let jobs = new Map();

const byFingerprint = new Map();
const byReuseKey = new Map();
const byAlias = new Map();
const jobByFile = new Map();
const jobByHls = new Map();

function readJson(file) {
  try {
    const v = JSON.parse(fs.readFileSync(file, "utf8"));
    return v && typeof v === "object" && !Array.isArray(v) ? v : {};
  } catch {
    return {};
  }
}

const persistAssets = makeJsonWriter(config.HLS_ASSETS_FILE, () => Object.fromEntries(assets), 2000);
const persistAliases = makeJsonWriter(config.HLS_ALIASES_FILE, () => Object.fromEntries(aliases), 2000);
const persistQueue = makeJsonWriter(config.HLS_QUEUE_FILE, () => Object.fromEntries(jobs), 2000);

const fileKey = (adId, path) => `${adId}|${path}`;

/**
 * Ключ переиспользования (§8): "canonical_id|edition|episode". Без canonical_id — null (только отпечаток).
 * У сериала (canonical_id "s:…") без распознанного номера серии — тоже null: canonical_id один на весь сезон.
 */
function reuseKeyOf(a) {
  if (!a || typeof a.canonical_id !== "string" || !a.canonical_id) return null;
  const episode = a.episode || "";
  if (a.canonical_id.startsWith("s:") && !episode) return null;
  return `${a.canonical_id}|${a.edition || "default"}|${episode}`;
}

function addTo(map, key, value) {
  let set = map.get(key);
  if (!set) map.set(key, (set = new Set()));
  set.add(value);
}

function removeFrom(map, key, value) {
  const set = map.get(key);
  if (!set) return;
  set.delete(value);
  if (!set.size) map.delete(key);
}

function indexAsset(id, a) {
  if (a.fingerprint) byFingerprint.set(a.fingerprint, id);
  const rk = reuseKeyOf(a);
  if (rk) addTo(byReuseKey, rk, id);
}

function unindexAsset(id, a) {
  if (a.fingerprint && byFingerprint.get(a.fingerprint) === id) byFingerprint.delete(a.fingerprint);
  const rk = reuseKeyOf(a);
  if (rk) removeFrom(byReuseKey, rk, id);
}

const isAudioJob = j => !!j && j.type === "audio";

function indexJob(id, j) {
  if (isAudioJob(j)) return;
  jobByFile.set(fileKey(j.ad_id, j.path), id);
  if (j.hls_id) jobByHls.set(j.hls_id, id);
}

function unindexJob(id, j) {
  if (isAudioJob(j)) return;
  const fk = fileKey(j.ad_id, j.path);
  if (jobByFile.get(fk) === id) jobByFile.delete(fk);
  if (j.hls_id && jobByHls.get(j.hls_id) === id) jobByHls.delete(j.hls_id);
}

function rebuildIndexes() {
  for (const m of [byFingerprint, byReuseKey, byAlias, jobByFile, jobByHls]) m.clear();
  for (const [id, a] of assets) indexAsset(id, a);
  for (const [key, al] of aliases) if (al && al.hls_id) addTo(byAlias, al.hls_id, key);
  for (const [id, j] of jobs) indexJob(id, j);
}

function load() {
  assets = new Map(Object.entries(readJson(config.HLS_ASSETS_FILE)).filter(([, a]) => a && typeof a === "object"));
  aliases = new Map(Object.entries(readJson(config.HLS_ALIASES_FILE)).filter(([, a]) => a && typeof a.hls_id === "string"));
  jobs = new Map(Object.entries(readJson(config.HLS_QUEUE_FILE)).filter(([, j]) => j && (isAudioJob(j) ? typeof j.hls_id === "string" && Array.isArray(j.keys) : j.ad_id != null && typeof j.path === "string")));
  rebuildIndexes();
}
load();

// ---- Ассеты ----

function getAsset(id) {
  return assets.get(id) || null;
}

/** Записать ассет целиком (новый или изменённый: индексы пересчитываются по старой и новой записи). */
function putAsset(id, a) {
  const old = assets.get(id);
  if (old) unindexAsset(id, old);
  assets.set(id, a);
  indexAsset(id, a);
  persistAssets();
  return a;
}

/** Удалить ассет и все его алиасы. */
function deleteAsset(id) {
  const old = assets.get(id);
  if (!old) return false;
  unindexAsset(id, old);
  assets.delete(id);
  persistAssets();
  for (const key of [...(byAlias.get(id) || [])]) deleteAlias(key);
  return true;
}

function assetByFingerprint(fp) {
  const id = fp ? byFingerprint.get(fp) : null;
  return id ? { id, asset: assets.get(id) } : null;
}

/** hls_id ассетов с этим ключом переиспользования. */
function assetsByReuseKey(key) {
  return key ? [...(byReuseKey.get(key) || [])] : [];
}

// ---- Алиасы ----

function getAlias(adId, path) {
  return aliases.get(fileKey(adId, path)) || null;
}

function setAlias(adId, path, hlsId, shiftMs = 0) {
  const key = fileKey(adId, path);
  const old = aliases.get(key);
  if (old) removeFrom(byAlias, old.hls_id, key);
  aliases.set(key, { hls_id: hlsId, shift_ms: shiftMs });
  addTo(byAlias, hlsId, key);
  persistAliases();
}

function deleteAlias(key) {
  const old = aliases.get(key);
  if (!old) return false;
  removeFrom(byAlias, old.hls_id, key);
  aliases.delete(key);
  persistAliases();
  return true;
}

/** Удалить алиасы всех файлов раздачи. Возвращает, сколько удалено. */
function deleteAliasesOf(adId) {
  const prefix = `${adId}|`;
  let n = 0;
  for (const key of [...aliases.keys()]) if (key.startsWith(prefix) && deleteAlias(key)) n++;
  return n;
}

function aliasesOf(hlsId) {
  return [...(byAlias.get(hlsId) || [])];
}

// ---- Задания ----

function getJob(id) {
  return jobs.get(id) || null;
}

function putJob(id, j) {
  const old = jobs.get(id);
  if (old) unindexJob(id, old);
  jobs.set(id, j);
  indexJob(id, j);
  persistQueue();
  return j;
}

function deleteJob(id) {
  const old = jobs.get(id);
  if (!old) return false;
  unindexJob(id, old);
  jobs.delete(id);
  persistQueue();
  return true;
}

function jobForFile(adId, path) {
  const id = jobByFile.get(fileKey(adId, path));
  return id ? jobs.get(id) || null : null;
}

function jobForHls(hlsId) {
  const id = hlsId ? jobByHls.get(hlsId) : null;
  return id ? jobs.get(id) || null : null;
}

/** Удалить задания раздачи. Возвращает, сколько удалено. */
function deleteJobsOf(adId) {
  let n = 0;
  for (const [id, j] of [...jobs]) if (!isAudioJob(j) && String(j.ad_id) === String(adId) && deleteJob(id)) n++;
  return n;
}

/** Записать на диск сейчас (тесты, остановка). */
function flush() {
  persistAssets.flush();
  persistAliases.flush();
  persistQueue.flush();
}

/** Для тестов: очистить память (reload — заново прочитать файлы). */
function _reset({ reload = false } = {}) {
  if (reload) return load();
  assets = new Map();
  aliases = new Map();
  jobs = new Map();
  rebuildIndexes();
}

module.exports = {
  fileKey,
  isAudioJob,
  reuseKeyOf,
  getAsset,
  putAsset,
  deleteAsset,
  assetByFingerprint,
  assetsByReuseKey,
  getAlias,
  setAlias,
  deleteAlias,
  deleteAliasesOf,
  aliasesOf,
  getJob,
  putJob,
  deleteJob,
  jobForFile,
  jobForHls,
  deleteJobsOf,
  allAssets: () => assets,
  allAliases: () => aliases,
  allJobs: () => jobs,
  flush,
  _reset,
  _indexes: { byFingerprint, byReuseKey, byAlias, jobByFile, jobByHls }
};

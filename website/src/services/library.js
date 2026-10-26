"use strict";

const axios = require("axios");
const config = require("../config");
const { adAuth, fetchMagnetTrees, treeCache, persistTreeCache } = require("./alldebrid");
const { cleanupFinishedDownloads, setLibraryChangedHook } = require("./downloads");

let libraryInflight = null;
let libraryLastGood = null;
let libraryFresh = null; // { at, data } — the last AllDebrid answer, reused for a few seconds
let libraryGen = 0;

// Bumped on every change: an AllDebrid answer asked for before a change is not kept as fresh (it would hide a torrent
// that was just added for the next 20 s), and a library load after the change does not wait for that old answer.
function libraryChanged() {
  libraryGen++;
  libraryFresh = null;
  libraryInflight = null;
}

// Connect downloads slot listener to library invalidation
setLibraryChangedHook(libraryChanged);

async function buildLibrary(gen = libraryGen) {
  const statusResponse = await axios.post(
    `${config.ALLDEBRID_API_BASE}/v4.1/magnet/status`,
    new URLSearchParams(),
    {
      headers: { ...adAuth(), "Content-Type": "application/x-www-form-urlencoded" },
      timeout: 20000,
      validateStatus: () => true
    }
  );
  if (statusResponse.status < 200 || statusResponse.status >= 300 || statusResponse.data?.status === "error") {
    const err = new Error(statusResponse.data?.error?.message || "Не удалось загрузить список раздач из облачного хранилища.");
    err.userMessage = err.message;
    throw err;
  }
  const rawMagnets = statusResponse.data?.data?.magnets;
  const magnets = Array.isArray(rawMagnets) ? rawMagnets : (rawMagnets && typeof rawMagnets === "object" ? [rawMagnets] : []);
  const normalized = magnets
    .filter(m => m && Number.isSafeInteger(Number(m.id)))
    .slice(0, 1000)
    .map(m => ({
      id: Number(m.id),
      name: m.filename || m.name || `Magnet ${m.id}`,
      status: m.status || "Неизвестно",
      statusCode: Number(m.statusCode ?? 0),
      size: Number(m.size || 0),
      downloaded: Number(m.downloaded || 0),
      files: []
    }));

  const isReady = m => m.statusCode === 4;
  const missing = normalized.filter(m => !(isReady(m) && treeCache.has(m.id))).map(m => m.id);
  const fetched = missing.length ? await fetchMagnetTrees(missing) : new Map();
  let cacheChanged = false;

  for (const m of normalized) {
    if (isReady(m) && treeCache.has(m.id)) {
      m.files = treeCache.get(m.id);
      continue;
    }
    m.files = fetched.get(m.id) || [];
    if (isReady(m) && m.files.length) {
      treeCache.set(m.id, m.files);
      cacheChanged = true;
    }
  }

  // Forget trees of magnets that were deleted from the account.
  const present = new Set(normalized.map(m => m.id));
  for (const id of [...treeCache.keys()]) {
    if (!present.has(id)) {
      treeCache.delete(id);
      cacheChanged = true;
    }
  }
  if (cacheChanged) persistTreeCache();

  // The «two downloads per IP» slots of torrents that finished, failed or are gone (the app that added them may have
  // stopped asking for their status long ago: closed, or the PC gives up after ~30 minutes).
  // Not from an answer asked for before a change: a torrent added meanwhile is missing from it.
  const states = new Map(normalized.map(m => [String(m.id), m.statusCode]));
  if (gen === libraryGen) {
    cleanupFinishedDownloads(states);
    // Автоочистка (не чаще раза в час) — в фоне: ответ библиотеки её не ждёт, следующий уже без удалённых
    require("./cleanup").maybeSweep(normalized, libraryChanged).catch(err => console.warn("[cleanup]", err.message));
  }

  return { ok: true, total: normalized.length, magnets: normalized };
}

async function getLibrary() {
  if (libraryFresh && Date.now() - libraryFresh.at < config.LIBRARY_CACHE_MS) {
    return { data: libraryFresh.data, stale: false };
  }
  try {
    if (!libraryInflight) {
      const gen = libraryGen;
      const job = buildLibrary(gen)
        .then(d => {
          if (gen === libraryGen) libraryFresh = { at: Date.now(), data: d };
          return d;
        })
        .finally(() => {
          if (libraryInflight === job) libraryInflight = null;
        });
      libraryInflight = job;
    }
    const data = await libraryInflight;
    libraryLastGood = data;
    return { data, stale: false };
  } catch (err) {
    console.error("Не удалось получить медиатеку:", err.message);
    if (libraryLastGood) {
      return { data: libraryLastGood, stale: true };
    }
    throw err;
  }
}

module.exports = {
  libraryChanged,
  buildLibrary,
  getLibrary
};

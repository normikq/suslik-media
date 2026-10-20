"use strict";

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const axios = require("axios");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");
const { safeHttpsUrl, adFail } = require("../utils/helpers");
const torrentSearch = require("../../search");

const adAuth = () => ({ Authorization: `Bearer ${config.ALLDEBRID_API_KEY}` });

async function alldebridRequest(endpoint, params = {}) {
  if (!config.ALLDEBRID_API_KEY) {
    const err = new Error("На сервере не задан ALLDEBRID_API_KEY. Добавьте ключ в .env и перезапустите сервер.");
    err.status = 503;
    throw err;
  }

  const response = await axios.get(`${config.ALLDEBRID_API_BASE}/v4${endpoint}`, {
    params,
    headers: adAuth(),
    timeout: 15000,
    validateStatus: () => true
  });

  if (response.status < 200 || response.status >= 300) {
    const err = new Error(`AllDebrid HTTP error (${response.status}).`);
    err.status = 502;
    throw err;
  }

  const data = response.data;
  if (data && data.status === "error") {
    const err = new Error(data.error?.message || "AllDebrid отклонил запрос.");
    err.status = 502;
    throw err;
  }
  return data;
}

async function adUploadMagnet(magnet) {
  const response = await axios.post(
    `${config.ALLDEBRID_API_BASE}/v4/magnet/upload`,
    new URLSearchParams({ "magnets[]": magnet }),
    {
      headers: {
        Authorization: `Bearer ${config.ALLDEBRID_API_KEY}`,
        "Content-Type": "application/x-www-form-urlencoded"
      },
      timeout: 20000,
      validateStatus: () => true
    }
  );
  if (response.status < 200 || response.status >= 300 || response.data?.status === "error") {
    throw adFail(502, response.data?.error?.message || "AllDebrid не смог принять эту magnet-ссылку.");
  }
  const item = response.data?.data?.magnets?.[0];
  if (!item || item.error) {
    throw adFail(400, item?.error?.message || "AllDebrid не вернул результат для magnet-ссылки.");
  }
  return item;
}

async function adUploadTorrent(bytes, filename) {
  const safeName = path.basename(filename).replace(/[\r\n"\\]/g, "_").slice(0, 180);
  const boundary = `----MoviePortal${crypto.randomBytes(16).toString("hex")}`;
  const body = Buffer.concat([
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="files[]"; filename="${safeName}"\r\nContent-Type: application/x-bittorrent\r\n\r\n`),
    bytes,
    Buffer.from(`\r\n--${boundary}--\r\n`)
  ]);
  const response = await axios.post(
    `${config.ALLDEBRID_API_BASE}/v4/magnet/upload/file`,
    body,
    {
      headers: {
        Authorization: `Bearer ${config.ALLDEBRID_API_KEY}`,
        "Content-Type": `multipart/form-data; boundary=${boundary}`,
        "Content-Length": body.length
      },
      timeout: 30000,
      maxBodyLength: 4 * 1024 * 1024,
      validateStatus: () => true
    }
  );
  if (response.status < 200 || response.status >= 300 || response.data?.status === "error") {
    throw adFail(502, response.data?.error?.message || "AllDebrid не смог принять этот торрент-файл.");
  }
  const item = response.data?.data?.files?.[0];
  if (!item || item.error) {
    throw adFail(400, item?.error?.message || "AllDebrid не вернул результат для торрент-файла.");
  }
  return item;
}

// .torrent only from the configured Prowlarr/Jackett (their download URLs carry the API key).
// Jackett often answers with a redirect to a magnet: link — that is accepted as well.
async function fetchTorrentFromIndexer(url) {
  let current = url;
  for (let hop = 0; hop < 3; hop++) {
    let origin;
    try {
      origin = new URL(current).origin;
    } catch {
      throw adFail(400, "Некорректная ссылка на торрент.");
    }
    if (!torrentSearch.trustedTorrentOrigins().includes(origin)) {
      throw adFail(400, "Скачивание .torrent разрешено только через настроенный Prowlarr/Jackett.");
    }
    const r = await axios.get(current, {
      responseType: "arraybuffer",
      maxRedirects: 0,
      timeout: 20000,
      maxContentLength: 3 * 1024 * 1024,
      validateStatus: () => true,
      headers: { "User-Agent": "SuslikMedia/1.0" }
    });
    if (r.status >= 300 && r.status < 400 && r.headers.location) {
      const loc = String(r.headers.location);
      if (loc.startsWith("magnet:?")) return { magnet: loc };
      try {
        current = new URL(loc, current).toString();
      } catch {
        throw adFail(502, "Индексатор вернул некорректный редирект.");
      }
      continue;
    }
    if (r.status !== 200) throw adFail(502, `Индексатор не отдал торрент (HTTP ${r.status}).`);
    const bytes = Buffer.from(r.data);
    if (bytes.length === 0 || bytes[0] !== 0x64) {
      throw adFail(502, "Индексатор вернул не .torrent файл (возможно, нужна авторизация на трекере).");
    }
    return { bytes };
  }
  throw adFail(502, "Слишком много редиректов при скачивании торрента.");
}

// ---- Library -------------------------------------------------------------------------
// The file tree of a finished magnet never changes, so it is fetched from AllDebrid once and kept
// (memory + file). A refresh then costs one magnet/status call plus the trees of new magnets only.
const treeCache = new Map(); // magnet id -> flat file list (finished magnets only)
try {
  for (const [id, files] of Object.entries(JSON.parse(fs.readFileSync(config.TREE_CACHE_FILE, "utf8")))) {
    if (Array.isArray(files)) treeCache.set(Number(id), files);
  }
} catch {}
const persistTreeCache = makeJsonWriter(config.TREE_CACHE_FILE, () => Object.fromEntries(treeCache));

// Flatten AllDebrid's nested tree. The order of this list is the file "index" used by playlists.
function flattenMagnetFiles(root) {
  const out = [];
  const walk = (nodes, prefix = "") => {
    if (!Array.isArray(nodes)) return;
    for (const node of nodes) {
      if (!node || typeof node !== "object") continue;
      const name = typeof node.n === "string" ? node.n : (typeof node.name === "string" ? node.name : "");
      if (!name) continue;
      const fullPath = prefix ? `${prefix}/${name}` : name;
      const children = node.e || node.files || node.children;
      const isFolder = Array.isArray(children) && children.length > 0;
      const link = typeof node.l === "string" && safeHttpsUrl(node.l) ? node.l : (typeof node.link === "string" && safeHttpsUrl(node.link) ? node.link : null);
      out.push({ name, path: fullPath, type: isFolder ? "folder" : "file", size: Number(node.s || node.size || 0) || null, sourceLink: link });
      if (Array.isArray(children)) walk(children, fullPath);
    }
  };
  walk(root?.files || root?.links || root?.e || []);
  return out;
}

async function fetchMagnetTrees(ids) {
  const result = new Map();
  for (let offset = 0; offset < ids.length; offset += 25) {
    const form = new URLSearchParams();
    for (const id of ids.slice(offset, offset + 25)) form.append("id[]", String(id));
    const response = await axios.post(
      `${config.ALLDEBRID_API_BASE}/v4/magnet/files`,
      form,
      {
        headers: { ...adAuth(), "Content-Type": "application/x-www-form-urlencoded" },
        timeout: 25000,
        validateStatus: () => true
      }
    );
    // Keep the library usable even if the file details of one batch are unavailable.
    if (response.status < 200 || response.status >= 300 || response.data?.status === "error") continue;
    const raw = response.data?.data?.magnets;
    for (const root of Array.isArray(raw) ? raw : (raw && typeof raw === "object" ? [raw] : [])) {
      result.set(Number(root.id), flattenMagnetFiles(root));
    }
  }
  return result;
}

// File list of one magnet for playlists: cached tree when known, otherwise a single API call.
async function getMagnetFileList(id) {
  if (treeCache.has(id)) return treeCache.get(id);
  return (await fetchMagnetTrees([id])).get(id) || null;
}

module.exports = {
  adAuth,
  alldebridRequest,
  adUploadMagnet,
  adUploadTorrent,
  fetchTorrentFromIndexer,
  flattenMagnetFiles,
  fetchMagnetTrees,
  getMagnetFileList,
  treeCache,
  persistTreeCache
};

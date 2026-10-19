"use strict";

const fs = require("fs");
const crypto = require("crypto");
const { spawn } = require("child_process");
const axios = require("axios");
const config = require("../config");
const { safeHttpsUrl } = require("../utils/helpers");
const { isAllDebridFileLink } = require("./mirror");

// Persistent media metadata cache. Cache keys are hashes of provider file links;
// direct URLs themselves are never written to disk.
const MEDIA_CACHE_TTL = 30 * 24 * 60 * 60 * 1000;
const MEDIA_CACHE_MAX = 5000; // записей; при переполнении вытесняются самые старые
const mediaMetadataCache = new Map();

try {
  const saved = JSON.parse(fs.readFileSync(config.MEDIA_CACHE_FILE, "utf8"));
  for (const [key, value] of Object.entries(saved)) {
    if (value && value.expiresAt > Date.now()) mediaMetadataCache.set(key, value);
  }
} catch {}

let cacheWriteQueued = false;
function persistMediaCache() {
  if (cacheWriteQueued) return;
  cacheWriteQueued = true;
  setTimeout(() => {
    cacheWriteQueued = false;
    try {
      const obj = Object.fromEntries(mediaMetadataCache);
      const tmp = config.MEDIA_CACHE_FILE + ".tmp";
      fs.writeFileSync(tmp, JSON.stringify(obj), { mode: 0o600 });
      fs.renameSync(tmp, config.MEDIA_CACHE_FILE);
    } catch (err) {
      console.error("Не удалось сохранить кэш сведений о медиафайлах:", err.message);
    }
  }, 250);
}

// At most two ffprobe processes at once (a season page asks for dozens; each may run 25 s on a one-core VPS), and the
// same file asked for twice waits for the one analysis already running.
const FFPROBE_MAX = 2;
let ffprobeRunning = 0;
const ffprobeQueue = [];
const metadataInflight = new Map(); // key -> Promise<metadata>

async function withFfprobeSlot(fn) {
  if (ffprobeRunning >= FFPROBE_MAX) {
    await new Promise(resolve => ffprobeQueue.push(resolve));
  }
  ffprobeRunning++;
  try {
    return await fn();
  } finally {
    ffprobeRunning--;
    const next = ffprobeQueue.shift();
    if (next) next();
  }
}

function runFfprobe(url) {
  return new Promise((resolve, reject) => {
    const args = [
      "-v",
      "error",
      "-rw_timeout",
      "12000000",
      "-user_agent",
      "Mozilla/5.0",
      "-analyzeduration",
      "8000000",
      "-probesize",
      "8000000",
      "-select_streams",
      "v:0",
      "-show_entries",
      "format=bit_rate,duration:stream=codec_name,width,height,bit_rate,avg_frame_rate",
      "-of",
      "json",
      url
    ];
    const child = spawn(config.FFPROBE_PATH || "ffprobe", args, { stdio: ["ignore", "pipe", "pipe"], windowsHide: true });
    let stdout = "";
    let stderr = "";
    const timeout = setTimeout(() => child.kill("SIGKILL"), 25000);
    child.stdout.on("data", chunk => {
      if (stdout.length < 1024 * 1024) stdout += chunk.toString();
    });
    child.stderr.on("data", chunk => {
      if (stderr.length < 8000) stderr += chunk.toString();
    });
    child.on("error", err => {
      clearTimeout(timeout);
      reject(err);
    });
    child.on("close", code => {
      clearTimeout(timeout);
      if (code !== 0) return reject(new Error(stderr || "ffprobe не смог проанализировать этот медиафайл."));
      try {
        const data = JSON.parse(stdout);
        const stream = (data.streams || []).find(s => s.width && s.height);
        if (!stream) return reject(new Error("Видеопоток не найден."));
        const rate = Number(stream.bit_rate || data.format?.bit_rate || 0);
        resolve({
          width: Number(stream.width),
          height: Number(stream.height),
          codec: stream.codec_name || null,
          bitrate: rate || null,
          bitrateMbps: rate ? Math.round(rate / 100000) / 10 : null,
          frameRate: stream.avg_frame_rate || null
        });
      } catch {
        reject(new Error("Не удалось разобрать ответ ffprobe."));
      }
    });
  });
}

async function getMediaMetadata(rawLink, magnetId, wantedPath) {
  let parsed = safeHttpsUrl(rawLink);
  // Library snapshots may not contain file links; resolve the requested file from AllDebrid by magnet ID + path.
  if (!isAllDebridFileLink(parsed)) {
    if (!Number.isSafeInteger(magnetId) || magnetId <= 0 || !wantedPath) {
      const err = new Error("Отсутствует ссылка на файл или идентификатор/путь magnet-ссылки.");
      err.status = 400;
      throw err;
    }
    try {
      const filesResponse = await axios.post(
        `${config.ALLDEBRID_API_BASE}/v4/magnet/files`,
        new URLSearchParams({ "id[]": String(magnetId) }),
        {
          headers: {
            Authorization: `Bearer ${config.ALLDEBRID_API_KEY}`,
            "Content-Type": "application/x-www-form-urlencoded"
          },
          timeout: 20000,
          validateStatus: () => true
        }
      );
      if (filesResponse.status < 200 || filesResponse.status >= 300 || filesResponse.data?.status === "error") {
        const err = new Error("AllDebrid пока не смог получить этот файл.");
        err.status = 502;
        throw err;
      }
      const rawRoots = filesResponse.data?.data?.magnets;
      const roots = Array.isArray(rawRoots) ? rawRoots : rawRoots && typeof rawRoots === "object" ? [rawRoots] : [];
      let foundLink = "";
      const walk = (nodes, prefix = "") => {
        if (!Array.isArray(nodes) || foundLink) return;
        for (const node of nodes) {
          if (!node || typeof node !== "object") continue;
          const name = typeof node.n === "string" ? node.n : typeof node.name === "string" ? node.name : "";
          if (!name) continue;
          const fullPath = prefix ? prefix + "/" + name : name;
          const children = node.e || node.files || node.children;
          const candidate = typeof node.l === "string" ? node.l : typeof node.link === "string" ? node.link : "";
          if (fullPath === wantedPath && candidate && isAllDebridFileLink(safeHttpsUrl(candidate))) {
            foundLink = candidate;
            return;
          }
          if (Array.isArray(children)) walk(children, fullPath);
          if (foundLink) return;
        }
      };
      for (const root of roots) {
        walk(root.files || root.links || root.e || []);
        if (foundLink) break;
      }
      parsed = safeHttpsUrl(foundLink);
      if (!isAllDebridFileLink(parsed)) {
        const err = new Error("Ссылка на файл пока недоступна. Обновите медиатеку после завершения обработки в AllDebrid.");
        err.status = 404;
        throw err;
      }
    } catch (err) {
      if (err.status) throw err;
      const e = new Error("Не удалось найти файл в AllDebrid.");
      e.status = 502;
      throw e;
    }
  }

  const key = crypto.createHash("sha256").update(parsed.toString()).digest("hex");
  const cached = mediaMetadataCache.get(key);
  if (cached && cached.expiresAt > Date.now()) {
    return { metadata: cached.metadata, cached: true };
  }

  let job = metadataInflight.get(key);
  if (!job) {
    job = withFfprobeSlot(async () => {
      // Resolve through the official provider API; only inspect the resulting direct URL.
      const unlocked = await axios.post(
        `${config.ALLDEBRID_API_BASE}/v4/link/unlock`,
        new URLSearchParams({ link: parsed.toString() }),
        {
          headers: {
            Authorization: `Bearer ${config.ALLDEBRID_API_KEY}`,
            "Content-Type": "application/x-www-form-urlencoded"
          },
          timeout: 20000,
          validateStatus: () => true
        }
      );
      if (unlocked.status < 200 || unlocked.status >= 300 || unlocked.data?.status === "error") {
        throw Object.assign(new Error("unlock"), { userMessage: "AllDebrid не смог получить ссылку на это видео." });
      }
      const direct = safeHttpsUrl(unlocked.data?.data?.link || "");
      if (!direct || !["http:", "https:"].includes(direct.protocol)) {
        throw Object.assign(new Error("direct"), { userMessage: "AllDebrid вернул некорректный URL видео." });
      }
      const metadata = await runFfprobe(direct.toString());
      for (const [k, v] of mediaMetadataCache) {
        if (!(v.expiresAt > Date.now())) mediaMetadataCache.delete(k);
      }
      mediaMetadataCache.delete(key);
      mediaMetadataCache.set(key, { metadata, expiresAt: Date.now() + MEDIA_CACHE_TTL });
      while (mediaMetadataCache.size > MEDIA_CACHE_MAX) {
        mediaMetadataCache.delete(mediaMetadataCache.keys().next().value);
      }
      persistMediaCache();
      return metadata;
    }).finally(() => metadataInflight.delete(key));
    metadataInflight.set(key, job);
  }

  const metadata = await job;
  return { metadata, cached: false };
}

module.exports = {
  mediaMetadataCache,
  runFfprobe,
  getMediaMetadata
};

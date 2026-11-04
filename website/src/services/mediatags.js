"use strict";

const fs = require("fs");
const { spawn } = require("child_process");
const config = require("../config");
const { safeHttpsUrl } = require("../utils/helpers");
const { makeJsonWriter } = require("../utils/json-writer");
const { isAllDebridFileLink } = require("./mirror");
const { resolveDirectUrl, withFfprobeSlot } = require("./metadata");

// ---- Метки Dolby Vision / HDR / DTS для постеров ---------------------------------------------------------------
// ffprobe смотрит самый большой видеофайл раздачи один раз: Dolby Vision — запись DOVI в потоке видео, HDR — передаточная
// функция PQ/HLG, DTS — звуковая дорожка dts (с уточнением: DTS-HD, DTS-HD MA, DTS:X). Ответ хранится, пока раздача
// есть в AllDebrid (удалённые убираются при следующем запросе); приложения и сайт берут его вместо догадки по названию.
// Не получилось (нет ссылки, AllDebrid или ffprobe не ответили) — повтор не раньше чем через 6 часов, не больше 3 раз.
const RETRY_AFTER_MS = 6 * 3600 * 1000;
const MAX_ATTEMPTS = 3;
const PAUSE_MS = 1500; // между раздачами: фоновый разбор не мешает просмотру
const QUEUE_MAX = 300;
const GONE_MS = 24 * 3600 * 1000;
const missingSince = new Map(); // id -> когда раздачи впервые не оказалось в списке

const store = new Map(); // magnet id (строка) -> { t: ["Dolby Vision","HDR","DTS-HD MA"], at } | { f: когда не вышло, n: сколько раз }
try {
  for (const [id, e] of Object.entries(JSON.parse(fs.readFileSync(config.TAGS_FILE, "utf8")))) {
    if (/^\d{1,20}$/.test(id) && e && (Array.isArray(e.t) || Number.isFinite(e.f))) store.set(id, e);
  }
} catch {}
const persist = makeJsonWriter(config.TAGS_FILE, () => Object.fromEntries(store), 2000);

const VIDEO_EXT = /\.(mkv|mp4|avi|mov|webm|m4v|m2ts|ts|iso)$/i;

/** Самый большой видеофайл раздачи (не sample; образ диска — только если другого нет) со ссылкой для AllDebrid. */
function pickMainFile(files) {
  const vids = (files || []).filter(f => f && f.type !== "folder" && f.sourceLink && VIDEO_EXT.test(f.path || "")
    && !/(^|[\/._ -])sample([\/._ -]|$)/i.test(f.path || ""));
  const real = vids.filter(f => !/\.iso$/i.test(f.path));
  return (real.length ? real : vids).sort((a, b) => (Number(b.size) || 0) - (Number(a.size) || 0))[0] || null;
}

/** Метки по ответу ffprobe (-show_streams). */
function tagsFromStreams(streams) {
  const list = Array.isArray(streams) ? streams : [];
  const tags = [];
  const video = list.find(s => s.codec_type === "video");
  const sideTypes = video && Array.isArray(video.side_data_list) ? video.side_data_list.map(d => String(d.side_data_type || "")) : [];
  if (sideTypes.some(t => /dovi|dolby vision/i.test(t))) tags.push("Dolby Vision");
  if (video && /^(smpte2084|arib-std-b67)$/i.test(String(video.color_transfer || ""))) tags.push("HDR");
  // лучшая из дорожек DTS: DTS:X > DTS-HD MA > DTS-HD > DTS
  let best = 0;
  let label = "";
  for (const s of list) {
    if (s.codec_type !== "audio" || String(s.codec_name || "").toLowerCase() !== "dts") continue;
    const p = String(s.profile || "");
    const [rank, name] = /dts:x/i.test(p) ? [4, "DTS:X"] : /ma/i.test(p) ? [3, "DTS-HD MA"] : /hd|hra/i.test(p) ? [2, "DTS-HD"] : [1, "DTS"];
    if (rank > best) { best = rank; label = name; }
  }
  if (label) tags.push(label);
  return tags;
}

function probeTags(url) {
  return new Promise((resolve, reject) => {
    const args = ["-v", "error", "-rw_timeout", "12000000", "-user_agent", "Mozilla/5.0", "-analyzeduration", "10000000",
      "-probesize", "16000000", "-show_streams", "-of", "json", url];
    const child = spawn(config.FFPROBE_PATH || "ffprobe", args, { stdio: ["ignore", "pipe", "pipe"], windowsHide: true });
    let stdout = "";
    let stderr = "";
    const timeout = setTimeout(() => child.kill("SIGKILL"), 30000);
    child.stdout.on("data", c => { if (stdout.length < 2 * 1024 * 1024) stdout += c.toString(); });
    child.stderr.on("data", c => { if (stderr.length < 4000) stderr += c.toString(); });
    child.on("error", err => { clearTimeout(timeout); reject(err); });
    child.on("close", code => {
      clearTimeout(timeout);
      if (code !== 0) return reject(new Error(stderr || "ffprobe не смог проанализировать файл."));
      try { resolve(tagsFromStreams(JSON.parse(stdout).streams)); } catch { reject(new Error("Не удалось разобрать ответ ffprobe.")); }
    });
  });
}

const queue = []; // { id, link }
const queued = new Set();
let running = false;

async function work() {
  if (running) return;
  running = true;
  try {
    while (queue.length) {
      const job = queue.shift();
      queued.delete(job.id);
      const prev = store.get(job.id);
      try {
        const parsed = safeHttpsUrl(job.link);
        if (!isAllDebridFileLink(parsed)) throw new Error("ссылка");
        // ссылку берём до слота: ожидание AllDebrid (до 20 с) не держит ffprobe, общий с /api/media-metadata.
        // Слот фоновый: не больше одного и после ждущих запросов из интерфейса
        const direct = await resolveDirectUrl(parsed);
        const tags = await withFfprobeSlot(() => probeTags(direct.toString()), { background: true });
        store.set(job.id, { t: tags, at: Date.now() });
      } catch (err) {
        store.set(job.id, { f: Date.now(), n: ((prev && prev.n) || 0) + 1 });
        console.warn(`[tags] раздача ${job.id}: ${err.userMessage || err.message}`.slice(0, 200));
      }
      persist();
      await new Promise(r => setTimeout(r, PAUSE_MS));
    }
  } finally {
    running = false;
  }
}

/**
 * Для списка раздач библиотеки: убирает записи удалённых раздач, ставит в очередь неразобранные готовые и отдаёт
 * известные метки. [prune] — только когда список настоящий (не устаревшая копия и не пустой).
 */
function snapshot(magnets, prune) {
  if (prune) {
    // раздача пропала из списка — запись удаляется, только если её нет дольше GONE_MS (сбой AllDebrid не стирает разбор)
    const present = new Set(magnets.map(m => String(m.id)));
    const now = Date.now();
    let dropped = false;
    for (const id of [...store.keys()]) {
      if (present.has(id)) { missingSince.delete(id); continue; }
      if (!missingSince.has(id)) missingSince.set(id, now);
      else if (now - missingSince.get(id) > GONE_MS) { store.delete(id); missingSince.delete(id); dropped = true; }
    }
    if (dropped) persist();
  }
  for (const m of magnets) {
    if (Number(m.statusCode) !== 4) continue;
    const id = String(m.id);
    const e = store.get(id);
    if (e && Array.isArray(e.t)) continue;
    if (e && (e.n >= MAX_ATTEMPTS || Date.now() - e.f < RETRY_AFTER_MS)) continue;
    if (queued.has(id) || queue.length >= QUEUE_MAX) continue;
    const file = pickMainFile(m.files);
    if (!file) continue;
    queued.add(id);
    queue.push({ id, link: file.sourceLink });
  }
  work().catch(() => {});
  const tags = {};
  const profiles = {};
  for (const m of magnets) {
    const e = store.get(String(m.id));
    if (e && Array.isArray(e.t)) tags[m.id] = e.t;
    const file = pickMainFile(m.files);
    if (file) {
      profiles[m.id] = buildMediaVariant({
        adId: m.id,
        filePath: file.path,
        fileName: m.name,
        sizeBytes: Number(file.size) || Number(m.size) || 0,
        tags: tags[m.id] || []
      });
    }
  }
  return { tags, profiles, pending: queue.length + (running ? 1 : 0) };
}

const RESOLUTION_SCORES = {
  "4K": 400,
  "1080p": 300,
  "720p": 200,
  "SD": 100
};

const VIDEO_TECH_SCORES = {
  "DV": 40,
  "HDR10+": 30,
  "HDR10": 20,
  "10-bit": 10,
  "SDR": 0
};

const AUDIO_TECH_SCORES = {
  "TrueHD Atmos": 40,
  "DTS:X": 40,
  "DTS-HD MA": 30,
  "DTS-HD": 30,
  "DTS 5.1": 20,
  "DD+": 20,
  "AC3 5.1": 10,
  "Stereo": 0
};

const SOURCE_TYPE_SCORES = {
  "Remux": 25,
  "BDRip": 15,
  "Web-DL": 10,
  "HDTV": 0
};

function calculateScore({ resolution = "SD", videoTech = "SDR", audioTech = "Stereo", sourceType = "Web-DL" } = {}) {
  const rScore = RESOLUTION_SCORES[resolution] ?? 100;
  const vScore = VIDEO_TECH_SCORES[videoTech] ?? 0;
  let aScore = AUDIO_TECH_SCORES[audioTech];
  if (aScore === undefined) {
    if (/atmos|dts:?x/i.test(audioTech)) aScore = 40;
    else if (/dts-?hd/i.test(audioTech)) aScore = 30;
    else if (/dts|dd\+|ddp|e-?ac-?3/i.test(audioTech)) aScore = 20;
    else if (/ac-?3|5\.1/i.test(audioTech)) aScore = 10;
    else aScore = 0;
  }
  const sScore = SOURCE_TYPE_SCORES[sourceType] ?? 0;
  return rScore + vScore + aScore + sScore;
}

function getHardwareFlags({ videoTech = "SDR", audioTech = "Stereo", bitrateMbps = 0, sourceType = "" } = {}) {
  const flags = [];
  if (audioTech && /dts/i.test(audioTech)) flags.push("dts");
  if (videoTech === "DV") flags.push("dv");
  if (videoTech && /hdr/i.test(videoTech)) flags.push("hdr");
  if ((bitrateMbps && bitrateMbps > 30) || sourceType === "Remux") flags.push("heavy");
  return flags;
}

function formatBytesRu(bytes) {
  if (!bytes || !Number.isFinite(bytes)) return "";
  const gb = bytes / (1024 * 1024 * 1024);
  if (gb >= 1) return gb.toFixed(1).replace(/\.0$/, "") + " ГБ";
  const mb = bytes / (1024 * 1024);
  return mb.toFixed(0) + " МБ";
}

function buildCompositeLabel({ resolution = "", sourceType = "", videoTech = "SDR", audioTech = "Stereo", sizeBytes = 0 } = {}) {
  const parts = [];
  if (resolution) parts.push(resolution);
  if (videoTech && videoTech !== "SDR") parts.push(videoTech === "DV" ? "Dolby Vision" : videoTech);
  if (sourceType) parts.push(sourceType === "Remux" ? "BDRemux" : sourceType);
  if (audioTech && audioTech !== "Stereo") parts.push(audioTech);
  const main = parts.join(" · ") || resolution || "Видео";
  const sz = formatBytesRu(sizeBytes);
  return sz ? `${main} (${sz})` : main;
}

function parseResolution(name, streams = []) {
  const v = streams.find(s => s && (s.codec_type === "video" || (s.width && s.height)));
  if (v) {
    const h = Number(v.height) || 0;
    const w = Number(v.width) || 0;
    if (h >= 1600 || w >= 3000) return "4K";
    if (h >= 900 || w >= 1600) return "1080p";
    if (h >= 600 || w >= 1100) return "720p";
    if (h > 0 || w > 0) return "SD";
  }
  const s = String(name || "").toLowerCase();
  if (/(?:^|[^a-z0-9])(?:2160p|4k|uhd)(?![a-z])|(?:^|\D)3840(?!\d)/i.test(s)) return "4K";
  if (/(?:^|\D)1080[pi](?![a-z])|(?:^|\D)1920(?!\d)/i.test(s)) return "1080p";
  if (/(?:^|\D)720p(?![a-z])|(?:^|\D)1280(?!\d)/i.test(s)) return "720p";
  if (/(?:^|\D)(?:480|576)[pi](?![a-z])|(?:^|[^a-z])dvd/i.test(s)) return "SD";
  return "";
}

function parseSourceType(name) {
  const s = String(name || "").toLowerCase();
  if (/remux/i.test(s)) return "Remux";
  if (/bdrip|brrip|blu[\s._-]?ray/i.test(s)) return "BDRip";
  if (/(?:^|[^a-z])web(?:[\s._-]?(?:dl|rip))?(?![a-z])/i.test(s)) return "Web-DL";
  if (/hdtv/i.test(s)) return "HDTV";
  return "";
}

function parseVideoTech(name, streams = [], tags = []) {
  if (tags.includes("Dolby Vision")) return "DV";
  const v = streams.find(s => s && s.codec_type === "video");
  if (v) {
    const side = Array.isArray(v.side_data_list) ? v.side_data_list.map(d => String(d.side_data_type || "")) : [];
    if (side.some(t => /dovi|dolby vision/i.test(t))) return "DV";
    const ct = String(v.color_transfer || "").toLowerCase();
    if (/hdr10\+/i.test(ct)) return "HDR10+";
    if (/smpte2084|arib-std-b67/i.test(ct)) return "HDR10";
    const pix = String(v.pix_fmt || "");
    if (/10(?:le|be)?|hi10p/i.test(pix)) return "10-bit";
  }
  if (tags.includes("HDR")) return "HDR10";
  const s = String(name || "").toLowerCase();
  if (/dolby[\s._-]?vision|dovi|(?:^|[^a-z])dv(?![a-z])/i.test(s)) return "DV";
  if (/hdr10\+|hdr10plus/i.test(s)) return "HDR10+";
  if (/hdr10|(?:^|[^a-z])hdr(?![a-z])/i.test(s)) return "HDR10";
  if (/10[\s._-]?bit|hi10p/i.test(s)) return "10-bit";
  return "SDR";
}

function parseAudioTech(name, streams = [], tags = []) {
  for (const t of tags) {
    if (t === "DTS:X") return "DTS:X";
    if (t === "DTS-HD MA") return "DTS-HD MA";
    if (t === "DTS-HD") return "DTS-HD MA";
    if (t === "DTS") return "DTS 5.1";
  }
  for (const s of streams) {
    if (s.codec_type !== "audio") continue;
    const codec = String(s.codec_name || "").toLowerCase();
    const prof = String(s.profile || "").toLowerCase();
    if (/atmos/i.test(prof) || (codec === "truehd" && /atmos/i.test(s.title || ""))) return "TrueHD Atmos";
    if (codec === "dts" && /dts:x/i.test(prof)) return "DTS:X";
    if (codec === "dts" && /ma/i.test(prof)) return "DTS-HD MA";
    if (codec === "dts" && /hd|hra/i.test(prof)) return "DTS-HD MA";
    if (codec === "dts") return "DTS 5.1";
    if (codec === "eac3") return "DD+";
    if (codec === "ac3") return "AC3 5.1";
  }
  const s = String(name || "").toLowerCase();
  if (/atmos(?![a-z])/i.test(s)) return "TrueHD Atmos";
  if (/dts[:._-]?x(?![a-z0-9])/i.test(s)) return "DTS:X";
  if (/dts[\s._-]?hd/i.test(s)) return "DTS-HD MA";
  if (/(?:^|[^a-z])dts(?![a-z])/i.test(s)) return "DTS 5.1";
  if (/dd\+|(?:^|[^a-z])ddp(?![a-z])|(?:^|[^a-z])e[\s._-]?ac[\s._-]?3(?!\d)|dolby\s*digital\s*plus/i.test(s)) return "DD+";
  if (/(?:^|[^a-z])ac[\s._-]?3(?!\d)|(?:^|[^a-z])dd[\s._-]?5\.1|(?:^|[^\d.])5\.1(?!\d)|dolby\s*digital/i.test(s)) return "AC3 5.1";
  return "Stereo";
}

function buildMediaVariant({
  adId,
  filePath,
  fileName,
  sizeBytes = 0,
  durationSec = 0,
  bitrateMbps = 0,
  streams = [],
  tags = []
}) {
  const combined = [fileName, filePath].filter(Boolean).join(" ");
  const resolution = parseResolution(combined, streams);
  const sourceType = parseSourceType(combined);
  const videoTech = parseVideoTech(combined, streams, tags);
  const audioTech = parseAudioTech(combined, streams, tags);
  const score = calculateScore({ resolution, videoTech, audioTech, sourceType });
  const hardwareFlags = getHardwareFlags({ videoTech, audioTech, bitrateMbps, sourceType });
  const key = `${adId}|${filePath || fileName || ""}`;
  const label = buildCompositeLabel({ resolution, sourceType, videoTech, audioTech, sizeBytes });

  return {
    key,
    ad_id: Number(adId),
    label,
    resolution,
    source_type: sourceType,
    video_tech: videoTech,
    audio_tech: audioTech,
    bitrate_mbps: bitrateMbps || (durationSec > 0 && sizeBytes > 0 ? Math.round((sizeBytes * 8) / (durationSec * 1000000) * 10) / 10 : 0),
    size_bytes: Number(sizeBytes) || 0,
    duration_sec: Number(durationSec) || 0,
    offset_ms: 0,
    score,
    hardware_flags: hardwareFlags
  };
}

module.exports = {
  snapshot,
  tagsFromStreams,
  pickMainFile,
  store,
  buildMediaVariant,
  calculateScore,
  getHardwareFlags,
  buildCompositeLabel,
  parseResolution,
  parseSourceType,
  parseVideoTech,
  parseAudioTech
};

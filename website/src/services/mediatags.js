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
        const tags = await withFfprobeSlot(async () => probeTags((await resolveDirectUrl(parsed)).toString()));
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
    const present = new Set(magnets.map(m => String(m.id)));
    let dropped = false;
    for (const id of [...store.keys()]) if (!present.has(id)) { store.delete(id); dropped = true; }
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
  for (const m of magnets) {
    const e = store.get(String(m.id));
    if (e && Array.isArray(e.t)) tags[m.id] = e.t;
  }
  return { tags, pending: queue.length + (running ? 1 : 0) };
}

module.exports = { snapshot, tagsFromStreams, pickMainFile, store };

"use strict";

// Звук для телевизора Samsung (приложение Tizen): AVPlay не играет DTS, TrueHD и звук Blu-ray, а .m2ts по сети
// показывает рывками. Для таких файлов сервер отдаёт тот же фильм через ffmpeg: видео как есть (-c:v copy), одна
// звуковая дорожка — в AC3 5.1 (или как есть, если ТВ её играет), контейнер MKV. Нагрузка — только звук: ~5–15 %
// одного ядра на просмотр. Перемотка — новый запрос с &t=<сек> (поток из трубы перематывать нельзя).
// Только для ТВ: остальные приложения и сайт ходят в обычное зеркало /api/file/proxy.

const crypto = require("crypto");
const { spawn } = require("child_process");
const config = require("../config");

// Звук, который AVPlay на Samsung 2018+ играет сам: такую дорожку не перекодируем, а копируем
const TV_AUDIO_OK = new Set(["aac", "ac3", "eac3", "mp3", "mp2"]);
// Текстовые субтитры MKV копируются в поток; картинки (PGS, VobSub) AVPlay всё равно не показывает
const TEXT_SUBS = new Set(["subrip", "ass", "ssa"]);

/** Запрос от приложения для телевизора: User-Agent Tizen или токен устройства, выданный ТВ. */
function isTvClient(req) {
  const ua = String(req.get("user-agent") || "");
  if (/Tizen|SMART-TV|SmartTV/i.test(ua)) return true;
  const name = String((req.device && req.device.name) || "");
  return /samsung tv|tizen/i.test(name);
}

function makeTvToken(link, audio, expires) {
  return crypto.createHmac("sha256", config.SESSION_SECRET).update(`tv:${expires}:${audio}:${link}`).digest("hex");
}

function isValidTvToken(link, audio, expires, token) {
  const now = Math.floor(Date.now() / 1000);
  if (
    !/^\d+$/.test(String(expires || "")) ||
    Number(expires) < now ||
    Number(expires) > now + config.MIRROR_TTL + 60 ||
    typeof token !== "string" ||
    !/^[0-9a-f]{64}$/i.test(token)
  ) {
    return false;
  }
  try {
    return crypto.timingSafeEqual(Buffer.from(makeTvToken(link, audio, expires), "hex"), Buffer.from(token, "hex"));
  } catch {
    return false;
  }
}

/** Ответ ffprobe (-show_format -show_streams) → то, что нужно ТВ и ffmpeg. */
function parseProbe(data) {
  const streams = Array.isArray(data && data.streams) ? data.streams : [];
  const format = (data && data.format) || {};
  const video = streams.find(s => s.codec_type === "video" && !(s.disposition && s.disposition.attached_pic)) || null;
  const audio = streams
    .filter(s => s.codec_type === "audio")
    .map(s => ({
      index: Number(s.index),
      codec: String(s.codec_name || ""),
      profile: String(s.profile || ""),
      channels: Number(s.channels) || 0,
      language: String((s.tags && s.tags.language) || ""),
      title: String((s.tags && s.tags.title) || ""),
      isDefault: !!(s.disposition && s.disposition.default),
      ok: TV_AUDIO_OK.has(String(s.codec_name || ""))
    }));
  const subs = streams
    .filter(s => s.codec_type === "subtitle" && TEXT_SUBS.has(String(s.codec_name || "")))
    .map(s => ({ index: Number(s.index), codec: String(s.codec_name), language: String((s.tags && s.tags.language) || ""), title: String((s.tags && s.tags.title) || "") }));
  const duration = Number(format.duration);
  return {
    container: String(format.format_name || ""),
    durationMs: Number.isFinite(duration) && duration > 0 ? Math.round(duration * 1000) : 0,
    video: video ? { index: Number(video.index), codec: String(video.codec_name || ""), width: Number(video.width) || 0, height: Number(video.height) || 0 } : null,
    audio,
    subs
  };
}

/**
 * Нужен ли серверный поток: .m2ts/.ts (MPEG-TS телевизор по сети показывает рывками) или в файле нет звука, который
 * ТВ играет. MKV/MP4 с хотя бы одной дорожкой AC3/AAC телевизор переключит сам — нагрузки на сервер нет.
 */
function needsTvStream(info) {
  if (!info || !info.video || !info.audio.length) return false;
  if (/mpegts/.test(info.container)) return true;
  return !info.audio.some(a => a.ok);
}

/** Какую дорожку взять по умолчанию: та, что ТВ играет, русская, «по умолчанию», первая — в этом порядке. */
function defaultAudio(info) {
  const list = info.audio;
  const rus = a => /^(rus|ru)$/i.test(a.language);
  return (
    list.find(a => a.ok && rus(a)) ||
    list.find(a => a.ok && a.isDefault) ||
    list.find(a => a.ok) ||
    list.find(rus) ||
    list.find(a => a.isDefault) ||
    list[0] ||
    null
  );
}

/**
 * Аргументы ffmpeg: [url] — прямая ссылка, [startSec] — с какого места, [audio] — дорожка из parseProbe,
 * [format] — "ts" (MPEG-TS: поток без длины и индекса телевизор принимает как эфир) или "mkv".
 */
function ffmpegArgs(url, startSec, info, audio, format) {
  const ts = format !== "mkv";
  const args = ["-hide_banner", "-loglevel", "error", "-nostdin"];
  if (/^https?:/i.test(url)) {
    // Файл-хост замолчал на 30 с — ffmpeg выходит, телевизор переоткроет поток с того же места
    args.push("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "10", "-rw_timeout", "30000000", "-user_agent", "Mozilla/5.0");
  }
  args.push("-fflags", "+genpts");
  if (startSec > 0) args.push("-ss", String(startSec));
  args.push("-i", url, "-map", `0:${info.video.index}`, "-map", `0:${audio.index}`);
  if (!ts) for (const s of info.subs) args.push("-map", `0:${s.index}`); // текстовых субтитров MPEG-TS не несёт
  args.push("-c:v", "copy");
  if (audio.ok) args.push("-c:a", "copy");
  else {
    const ch = Math.min(audio.channels || 2, 6);
    // AC3, а не AAC: кодируется дешевле, и телевизор передаёт 5.1 на саундбар по ARC/оптике
    args.push("-c:a", "ac3", "-b:a", ch > 2 ? "640k" : "192k", "-ac", String(ch));
  }
  if (!ts && info.subs.length) args.push("-c:s", "copy");
  args.push("-max_muxing_queue_size", "1024", "-avoid_negative_ts", "make_zero");
  // MPEG-TS: время с нуля, PAT/PMT в начале каждого ключевого кадра — плеер подхватывает поток с любого места
  if (ts) args.push("-f", "mpegts", "-mpegts_flags", "+resend_headers", "pipe:1");
  else args.push("-f", "matroska", "pipe:1");
  return args;
}

// ---- ffprobe с кешем ----

const probeCache = new Map(); // link -> { info, until }
const probeInflight = new Map();

function runProbe(url) {
  return new Promise((resolve, reject) => {
    const args = ["-v", "error", "-rw_timeout", "15000000", "-user_agent", "Mozilla/5.0", "-probesize", "20000000",
      "-analyzeduration", "10000000", "-show_format", "-show_streams", "-of", "json", url];
    const child = spawn(config.FFPROBE_PATH || "ffprobe", args, { stdio: ["ignore", "pipe", "pipe"], windowsHide: true });
    let out = "", err = "";
    const timer = setTimeout(() => child.kill("SIGKILL"), 40000);
    child.stdout.on("data", c => { if (out.length < 2 * 1024 * 1024) out += c; });
    child.stderr.on("data", c => { if (err.length < 4000) err += c; });
    child.on("error", e => { clearTimeout(timer); reject(e); });
    child.on("close", code => {
      clearTimeout(timer);
      if (code !== 0) return reject(new Error(err.trim() || "ffprobe не смог открыть файл"));
      try { resolve(parseProbe(JSON.parse(out))); } catch { reject(new Error("Не удалось разобрать ответ ffprobe")); }
    });
  });
}

/** Сведения о файле [key] (ссылка AllDebrid) по прямой ссылке [url]; 6 ч в памяти, один ffprobe на файл. */
function probeFile(key, url) {
  const hit = probeCache.get(key);
  if (hit && hit.until > Date.now()) return Promise.resolve(hit.info);
  let p = probeInflight.get(key);
  if (!p) {
    p = runProbe(url).then(info => {
      probeCache.set(key, { info, until: Date.now() + 6 * 3600 * 1000 });
      while (probeCache.size > 300) probeCache.delete(probeCache.keys().next().value);
      return info;
    }).finally(() => probeInflight.delete(key));
    probeInflight.set(key, p);
  }
  return p;
}

// ---- ffmpeg: не больше TV_STREAM_MAX процессов на сервер ----

const active = new Map(); // id -> { child, viewer }

function activeCount() { return active.size; }

/**
 * Запустить ffmpeg. [viewer] — метка плеера (s= из адреса): новый запрос того же плеера (перемотка) сразу гасит
 * прошлый процесс, даже если телевизор ещё не закрыл старое соединение. Возвращает null, если сервер занят.
 */
function startFfmpeg(args, viewer) {
  if (viewer) for (const [id, a] of active) if (a.viewer === viewer) { a.child.kill("SIGKILL"); active.delete(id); }
  if (active.size >= config.TV_STREAM_MAX) return null;
  const child = spawn(config.FFMPEG_PATH || "ffmpeg", args, { stdio: ["ignore", "pipe", "pipe"], windowsHide: true });
  const id = crypto.randomBytes(8).toString("hex");
  active.set(id, { child, viewer });
  child.stderrText = "";
  child.stderr.on("data", c => { if (child.stderrText.length < 4000) child.stderrText += c; });
  const forget = () => active.delete(id);
  child.on("close", forget);
  child.on("error", forget);
  return child;
}

module.exports = {
  TV_AUDIO_OK,
  isTvClient,
  makeTvToken,
  isValidTvToken,
  parseProbe,
  needsTvStream,
  defaultAudio,
  ffmpegArgs,
  probeFile,
  startFfmpeg,
  activeCount
};

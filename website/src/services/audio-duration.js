"use strict";

const { safeHttpsUrl } = require("../utils/helpers");
const { resolveDirect, isAllDebridFileLink } = require("./mirror");
const tv = require("./tvstream");

// ---- Проверка содержимого озвучки по длительности ---------------------------------------------------------------
// Имена файлов могут совпасть, а монтаж — нет (театральная версия и Extended Cut, чужая серия, PAL-ускорение).
// ffprobe читает только заголовки (у .mka длительность в начале файла); сведения о файле кешируются на 6 ч (tvstream).

// Разница длительностей, после которой дорожка считается чужой: 60 с или 3 % видео (что больше) плюс смещение из тега
const MIN_DIFF_MS = 60_000;
const MAX_DIFF_SHARE = 0.03;
// PAL-ускорение: фильм 23,976/24 кадр/с переведён на 25 — звук короче на ~4,1 % (обратное — длиннее)
const PAL_RATIOS = [25 / 23.976, 25 / 24];
// Допуск вокруг ожидаемой PAL-длительности: 10 с или 0,2 % (что больше), без смещения — иначе серия на 50 с длиннее
// считалась бы ускоренной
const PAL_TOLERANCE_MS = 10_000;
const PAL_TOLERANCE_SHARE = 0.002;
// Сколько пар проверять в одной раздаче (сериал — первая, средние и последняя серии) и сколько ждать всю проверку
const MAX_PAIRS = 4;
const DEADLINE_MS = 45_000;
const CONCURRENCY = 2;

// 5520000 -> «1 ч 32 мин», 2710000 -> «45 мин 10 с»
function formatDuration(ms) {
  const s = Math.round(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h) return m ? `${h} ч ${m} мин` : `${h} ч`;
  const sec = s % 60;
  return sec ? `${m} мин ${sec} с` : `${m} мин`;
}

/**
 * Сверка сведений ffprobe (parseProbe) озвучки и видео. Возвращает null (всё в порядке) или ошибку
 * { code, message, audioMs?, videoMs? }. Коды: NO_AUDIO, HIDDEN_VIDEO, FRAME_RATE_MISMATCH, DURATION_MISMATCH.
 * audioInfo/videoInfo без длительности (durationMs 0) — длительность не сверяется.
 */
function compareDuration(audioInfo, videoInfo, offsetMs = 0) {
  if (!audioInfo.audio || !audioInfo.audio.length) return { code: "NO_AUDIO", message: "В файле озвучки нет звуковой дорожки." };
  if (audioInfo.video) return { code: "HIDDEN_VIDEO", message: "В файле озвучки есть видеопоток: разрешены только звуковые дорожки." };
  const a = audioInfo.durationMs;
  const v = videoInfo && videoInfo.durationMs;
  if (!a || !v) return null;
  const extra = { audioMs: a, videoMs: v };
  const palTolerance = Math.max(PAL_TOLERANCE_MS, v * PAL_TOLERANCE_SHARE);
  for (const r of PAL_RATIOS) {
    if (Math.abs(a - v / r) <= palTolerance) {
      return { code: "FRAME_RATE_MISMATCH", ...extra, message: `Длительность дорожки (${formatDuration(a)}) короче фильма (${formatDuration(v)}) на ~4 %: озвучка от версии 25 кадров/с (PAL), а видео — 24. Звук будет постепенно убегать.` };
    }
    if (Math.abs(a - v * r) <= palTolerance) {
      return { code: "FRAME_RATE_MISMATCH", ...extra, message: `Длительность дорожки (${formatDuration(a)}) длиннее фильма (${formatDuration(v)}) на ~4 %: озвучка от версии 24 кадра/с, а видео — 25 (PAL). Звук будет постепенно отставать.` };
    }
  }
  const limit = Math.max(MIN_DIFF_MS, v * MAX_DIFF_SHARE) + Math.abs(offsetMs || 0);
  if (Math.abs(a - v) > limit) {
    return { code: "DURATION_MISMATCH", ...extra, message: `Длительность дорожки (${formatDuration(a)}) не совпадает с фильмом (${formatDuration(v)}). Возможно, озвучка от другой версии монтажа (Theatrical / Director's Cut) или другой серии.` };
  }
  return null;
}

// Не больше n пар, равномерно: первая, последняя и между ними
function samplePairs(pairs, n = MAX_PAIRS) {
  if (pairs.length <= n) return pairs;
  const idx = new Set(Array.from({ length: n }, (_, i) => Math.round((i * (pairs.length - 1)) / (n - 1))));
  return [...idx].map(i => pairs[i]);
}

// Сведения о файле по ссылке AllDebrid (sourceLink)
async function probeLink(link) {
  const parsed = safeHttpsUrl(link || "");
  if (!isAllDebridFileLink(parsed)) throw new Error("Нет ссылки на файл.");
  const key = parsed.toString();
  const entry = await resolveDirect(key, false);
  return tv.probeFile(key, entry.url);
}

/**
 * Проверить пары { videoPath, audioPath, offsetMs } по содержимому. linkOf(kind, path) -> ссылка AllDebrid или null
 * (может быть async). Возвращает { errors, warnings, checked: [{ videoPath, audioPath, audioMs, videoMs }] }.
 * Сбой чтения (нет ffprobe, AllDebrid не отдал файл, не успели за DEADLINE_MS) не блокирует: только предупреждение.
 */
async function checkDurations(pairs, linkOf, { probe = probeLink, deadlineMs = DEADLINE_MS } = {}) {
  const errors = [];
  const warnings = [];
  const checked = [];
  const sample = samplePairs(pairs);
  const one = async p => {
    const [audioLink, videoLink] = await Promise.all([linkOf("audio", p.audioPath), linkOf("video", p.videoPath)]);
    const [ai, vi] = await Promise.all([probe(audioLink), probe(videoLink)]);
    if (finished) return;
    const err = compareDuration(ai, vi, p.offsetMs);
    if (err) errors.push({ ...err, path: p.audioPath, videoPath: p.videoPath });
    else checked.push({ videoPath: p.videoPath, audioPath: p.audioPath, audioMs: ai.durationMs || null, videoMs: vi.durationMs || null });
  };
  const unchecked = [];
  let next = 0;
  let finished = false;
  const worker = async () => {
    while (!finished && next < sample.length) {
      const p = sample[next++];
      try {
        await one(p);
      } catch {
        if (!finished) unchecked.push(p.audioPath);
      }
    }
  };
  let timer;
  const timedOut = new Promise(resolve => { timer = setTimeout(() => resolve(true), deadlineMs); });
  const all = Promise.all(Array.from({ length: Math.min(CONCURRENCY, sample.length) }, worker)).then(() => false);
  const late = await Promise.race([all, timedOut]);
  clearTimeout(timer);
  finished = true;
  if (late) {
    // Недоделанные пробы досчитаются в фоне и попадут в кеш: повторная проверка будет быстрой
    for (const p of sample) {
      if (!checked.some(c => c.audioPath === p.audioPath) && !errors.some(e => e.path === p.audioPath) && !unchecked.includes(p.audioPath)) unchecked.push(p.audioPath);
    }
  }
  if (unchecked.length) {
    warnings.push({ code: "DURATION_UNCHECKED", paths: unchecked, message: "Не удалось прочитать часть файлов: длительность не сверена." });
  }
  return { errors, warnings, checked };
}

module.exports = { compareDuration, formatDuration, samplePairs, checkDurations, MIN_DIFF_MS, MAX_DIFF_SHARE };

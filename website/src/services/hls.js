"use strict";

const crypto = require("crypto");
const config = require("../config");
const store = require("./hls-store");

// ---- HLS: серверное ядро (docs/HLS.md, задача T3) ------------------------------------------------------------
// Идентификаторы (§4), подписи CDN и master.m3u8 (§6), переиспользование ассетов (§8), direct_ok (§9),
// ответ воркеру на probe (§5, §10), очередь кодирования (§11). Маршруты /api/hls и /internal/hls — T4:
// они только вызывают функции отсюда. Время в записях — unix-секунды (как в фикстурах), параметр now — мс.

const PROFILES = new Set(["v1", "v1-copy"]);
const MASTER_TTL_S = 12 * 3600; // §6: срок подписи 12 ч (+60 с допуск при проверке)
const LEASE_S = 10 * 60; // аренда задания; progress продлевает
const WAIT_RETRY_S = 60; // ответ wait: ассет уже кодирует другое задание
const MAX_ATTEMPTS = 3; // retryable-ошибок подряд, потом задание failed
const FAIL_BACKOFF_S = 5 * 60; // пауза перед повтором: attempts × 5 мин
const SHIFT_ZERO_MS = 1500; // §8: |Δ| ≤ 1,5 с — без сдвига
const SHIFT_MAX_MS = 120 * 1000; // §8: |Δ| > 2 мин — другой монтаж
const COPY_MAX_BITRATE = 12_000_000; // §5 v1-copy: средний битрейт ≤ 12 Мбит
const COPY_MAX_KEYINT_S = 10;
const VIDEO_EXT = /\.(mkv|mp4|avi|mov|webm|m4v|m2ts|ts|wmv|mpg|mpeg)$/i;
const HEX16 = /^[0-9a-f]{16}$/;
const HEX64 = /^[0-9a-f]{64}$/i;

class HlsError extends Error {
  constructor(code, message, status = 400) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

const sec = now => Math.floor(now / 1000);
const normPath = p => String(p ?? "").replace(/\\/g, "/");

// ---- §4 Идентификаторы ----

/** hls_id = первые 16 hex sha1("<infohash>|<путь>|<размер>"), без hash — sha1("ad:<adId>|<путь>|<размер>"). */
function hlsId({ hash, adId, path, size }) {
  const h = typeof hash === "string" ? hash.trim().toLowerCase() : "";
  const head = h ? h : `ad:${adId}`;
  const input = `${head}|${normPath(path)}|${String(size).trim()}`;
  return crypto.createHash("sha1").update(input).digest("hex").slice(0, 16);
}

// ---- §6 Подписи ----

/** Подпись nginx secure_link: base64url(md5("<exp><hls_id> <secret>")) без паддинга. */
function signCdn(exp, id, secret = config.HLS_SIGN_SECRET) {
  return crypto.createHash("md5").update(`${exp}${id} ${secret}`).digest("base64url");
}

/** Абсолютная подписанная ссылка на файл профиля на CDN: <HLS_CDN_URL>/hls/<sig>/<exp>/<hls_id>/<profile>/<путь>. */
function signHlsUrl(id, profile, relPath, exp, opts = {}) {
  const base = String(opts.cdnUrl ?? config.HLS_CDN_URL).replace(/\/+$/, "");
  const secret = opts.secret ?? config.HLS_SIGN_SECRET;
  if (!base || !secret) throw new HlsError("not_configured", "HLS_CDN_URL или HLS_SIGN_SECRET не заданы.", 503);
  if (!HEX16.test(id) || !PROFILES.has(profile)) throw new HlsError("bad_request", "Неверный hls_id или профиль.");
  const rel = normPath(relPath).split("/").filter(s => s && s !== "." && s !== "..").map(encodeURIComponent).join("/");
  return `${base}/hls/${signCdn(exp, id, secret)}/${exp}/${id}/${profile}/${rel}`;
}

/** Подпись master.m3u8: HMAC-SHA256(SESSION_SECRET, "hls:<exp>:<hls_id>"), hex. */
function signMaster(id, exp, secret = config.SESSION_SECRET) {
  return crypto.createHmac("sha256", secret).update(`hls:${exp}:${id}`).digest("hex");
}

/**
 * Проверка подписи master.m3u8 (как isValidMirrorToken): "ok" | "expired" (подпись верна, срок вышел → 410) |
 * "invalid" (→ 403). exp — целое в [now, now + 12 ч + 60 с].
 */
function verifyMaster(id, exp, sig, now = Date.now(), secret = config.SESSION_SECRET) {
  const t = sec(now);
  if (!/^\d{1,12}$/.test(String(exp ?? "")) || typeof sig !== "string" || !HEX64.test(sig) || !HEX16.test(String(id))) return "invalid";
  let good = false;
  try {
    good = crypto.timingSafeEqual(Buffer.from(signMaster(id, exp, secret), "hex"), Buffer.from(sig, "hex"));
  } catch {
    good = false;
  }
  if (!good) return "invalid";
  const e = Number(exp);
  if (e < t) return "expired";
  if (e > t + MASTER_TTL_S + 60) return "invalid";
  return "ok";
}

/** Путь подписанного master.m3u8 (без домена; T4 добавляет адрес сайта). exp по умолчанию — через 12 ч. */
function masterPath(id, exp = sec(Date.now()) + MASTER_TTL_S) {
  return `${config.SITE_PREFIX}/api/hls/${id}/master.m3u8?exp=${exp}&sig=${signMaster(id, exp)}`;
}

// ---- §8 Переиспользование ----

const pad2 = n => String(Number(n)).padStart(2, "0");
const epFmt = (s, e) => `S${pad2(s)}E${pad2(e)}`;

/**
 * Номер серии из пути: "S01E02" или null. Понимает S01E02, s1e2, S01.E02, S01 - E02, 1x02, «Season 1 Episode 2»,
 * «1 сезон 2 серия», «Сезон 1 Серия 2»; номер серии в имени файла (E05, Ep05, Episode 5, «5 серия») с сезоном из папки.
 */
function episodeOf(p) {
  const parts = normPath(p).split("/").filter(Boolean);
  const base = parts.pop() || "";
  let m = base.match(/(?:^|[^a-z0-9])s(\d{1,2})[\s._-]*e(\d{1,4})(?![0-9])/i)
    || base.match(/(?:^|[^a-z0-9])(\d{1,2})x(\d{2,3})(?![0-9a-z])/i)
    || base.match(/season[\s._-]*(\d{1,2})[\s._-]*(?:episode|ep|e)[\s._-]*(\d{1,4})(?![0-9])/i)
    || base.match(/(?:^|[^0-9])(\d{1,2})[\s._-]*сезон\D{0,12}?(\d{1,4})[\s._-]*сери/i)
    || base.match(/сезон[\s._-]*(\d{1,2})\D{0,12}?сери[яи][\s._-]*(\d{1,4})/i);
  if (m) return epFmt(m[1], m[2]);
  const ep = base.match(/(?:^|[^a-z0-9])(?:episode|ep|e)[\s._-]*(\d{1,4})(?![0-9])/i)
    || base.match(/сери[яи][\s._-]*(\d{1,4})/i)
    || base.match(/(?:^|[^0-9])(\d{1,4})[\s._-]*сери[яи]/i);
  if (!ep) return null;
  for (const dir of parts.reverse()) {
    const s = dir.match(/(?:^|[^a-z0-9])s(\d{1,2})(?![0-9])/i)
      || dir.match(/season[\s._-]*(\d{1,2})(?![0-9])/i)
      || dir.match(/(?:^|[^0-9])(\d{1,2})[\s._-]*сезон/i)
      || dir.match(/сезон[\s._-]*(\d{1,2})(?![0-9])/i);
    if (s) return epFmt(s[1], ep[1]);
  }
  return null;
}

/**
 * Сдвиг алиаса по длительностям: позиция ассета = позиция файла + shift_ms. |Δ| ≤ 1,5 с → 0; ≤ 2 мин → Δ;
 * больше или длительность неизвестна → null (переиспользовать нельзя).
 */
function shiftMs(assetDurMs, fileDurMs) {
  const a = Number(assetDurMs);
  const b = Number(fileDurMs);
  if (!Number.isFinite(a) || !Number.isFinite(b) || a <= 0 || b <= 0) return null;
  const d = Math.round(a - b);
  if (Math.abs(d) <= SHIFT_ZERO_MS) return 0;
  if (Math.abs(d) <= SHIFT_MAX_MS) return d;
  return null;
}

/** Совпадение отпечатков (§7): оба — 64 hex, без учёта регистра. */
function fingerprintMatch(a, b) {
  return typeof a === "string" && typeof b === "string" && HEX64.test(a) && HEX64.test(b) && a.toLowerCase() === b.toLowerCase();
}

/**
 * Правило 2 §8: тот же монтаж из другой раздачи. a — ассет, b — файл ({canonical_id, edition, episode, duration_ms}).
 * Совпали canonical_id, edition, серия (у сериала обязательна) и |Δ длительности| ≤ 2 мин.
 */
function canReuse(a, b) {
  if (!a || !b) return false;
  const ka = store.reuseKeyOf(a);
  return !!ka && ka === store.reuseKeyOf(b) && shiftMs(a.duration_ms, b.duration_ms) !== null;
}

// ---- §9 direct_ok ----

const BAD_AUDIO = [[/^dts/i, "dts"], [/^truehd/i, "truehd"], [/^(dd\+|e-?ac-?3)/i, "eac3"], [/^(ac-?3|dd\b)/i, "ac3"]];

// Звук, который браузер играет в mp4/webm без перекодирования
const GOOD_AUDIO = new Set(["aac", "mp3", "opus", "vorbis", "flac"]);

/** Кодек звука ffprobe → имя для direct.acodec: dts, truehd, ac3, eac3, aac, … */
function normAcodec(c) {
  const s = String(c || "").toLowerCase().trim();
  if (!s) return "";
  if (/^dts|^dca$/.test(s)) return "dts";
  if (/^(truehd|mlp)$/.test(s)) return "truehd";
  if (/^(eac3|e-ac-3|ec-3|ec3)$/.test(s)) return "eac3";
  if (/^(ac3|ac-3)$/.test(s)) return "ac3";
  if (/^(aac|aac_latm|mp4a)/.test(s)) return "aac";
  return s.slice(0, 32);
}

function normVcodec(c) {
  const s = String(c || "").toLowerCase();
  if (/^(h\.?264|avc1?|x264)$/.test(s)) return "h264";
  if (/^(hevc|h\.?265|hvc1|hev1|x265)$/.test(s)) return "hevc";
  return s;
}

/**
 * Можно ли играть файл напрямую через зеркало в браузере. tagsEntry — запись media-tags.json этой раздачи
 * ({t, meta:{codec,…}, at}) или null. → { direct_ok, direct: {ext, vcodec, acodec} }.
 * Запись без meta.codec (старые записи) — видео не проверяется, только контейнер и звук.
 */
function computeDirectOk(path, tagsEntry) {
  const m = normPath(path).match(/\.([a-z0-9]{1,5})$/i);
  const ext = m ? m[1].toLowerCase() : "";
  const has = !!tagsEntry && (Array.isArray(tagsEntry.t) || (tagsEntry.meta && typeof tagsEntry.meta === "object"));
  if (!has) {
    return { direct_ok: ["mp4", "m4v", "webm"].includes(ext), direct: { ext, vcodec: "", acodec: "" } };
  }
  const vcodec = normVcodec(tagsEntry.meta?.codec);
  let acodec = "aac";
  let audioOk;
  const acodecs = Array.isArray(tagsEntry.meta?.acodecs) ? tagsEntry.meta.acodecs.map(normAcodec).filter(Boolean) : null;
  if (acodecs && acodecs.length) {
    // кодеки звука из ffprobe (mediatags.js, meta.acodecs): точнее меток, которые ставятся только для DTS
    const bad = acodecs.find(c => !GOOD_AUDIO.has(c));
    acodec = bad || acodecs[0];
    audioOk = !bad;
  } else {
    for (const tag of Array.isArray(tagsEntry.t) ? tagsEntry.t : []) {
      const hit = BAD_AUDIO.find(([re]) => re.test(String(tag)));
      if (hit) { acodec = hit[1]; break; }
    }
    audioOk = acodec === "aac";
  }
  let ok = false;
  if (vcodec === "hevc") ok = false;
  else if (ext === "mp4" || ext === "m4v") ok = audioOk && (!vcodec || vcodec === "h264");
  else if (ext === "webm") ok = audioOk && (!vcodec || ["h264", "vp9", "av1"].includes(vcodec));
  return { direct_ok: ok, direct: { ext, vcodec, acodec: has ? acodec : "" } };
}

/** direct_ok по кэшу media-tags.json (ключ — id раздачи). */
function directFor(adId, path) {
  let entry = null;
  try {
    entry = require("./mediatags").store.get(String(adId)) || null;
  } catch {}
  return computeDirectOk(path, entry);
}

// ---- §5 Выбор профиля ----

/** v1-copy, если видео уже годится для HLS без перекодирования (все условия §5), иначе v1. */
function decideProfile(body, size = 0) {
  const streams = Array.isArray(body?.streams) ? body.streams : [];
  const v = streams.find(s => s && s.codec_type === "video" && !s.disposition?.attached_pic);
  if (!v) return "v1";
  const codec = normVcodec(v.codec_name);
  if (codec !== "h264" && codec !== "hevc") return "v1";
  if (/p0?1[0-6]/.test(String(v.pix_fmt || ""))) return "v1"; // 10/12 бит (yuv420p10le, p010le; nv12 — 8 бит)
  if (/^(smpte2084|arib-std-b67)$/i.test(String(v.color_transfer || ""))) return "v1";
  const side = Array.isArray(v.side_data_list) ? v.side_data_list.map(d => String(d?.side_data_type || "")) : [];
  if (v.dovi === true || side.some(t => /dovi|dolby vision/i.test(t)) || /^dv/i.test(String(v.codec_tag_string || "")) || /dolby/i.test(String(v.profile || ""))) return "v1";
  const w = Number(v.width) || 0;
  const h = Number(v.height) || 0;
  if (!w || !h || h > 1080 || w > 1920) return "v1";
  let br = Number(body?.format?.bit_rate) || 0;
  const durS = Number(body?.duration_ms) / 1000;
  if (!br && size > 0 && durS > 0) br = (size * 8) / durS;
  if (!br || br > COPY_MAX_BITRATE) return "v1";
  const ki = Number(v.keyframe_interval_max_s);
  if (!Number.isFinite(ki) || ki <= 0 || ki > COPY_MAX_KEYINT_S) return "v1";
  return "v1-copy";
}

// ---- Ассеты ----

/** Профиль, который сейчас можно играть (active_profile и он ready), или null. */
function readyProfile(a) {
  const p = a?.active_profile;
  return p && a.profiles?.[p]?.status === "ready" ? p : null;
}

/** Обновить last_access (master.m3u8, T4). */
function touch(id, now = Date.now()) {
  const a = store.getAsset(id);
  if (!a) return false;
  a.last_access = sec(now);
  store.putAsset(id, a);
  return true;
}

/** canonical_id и edition раздачи: только подтверждённый владельцем ответ ИИ (как isSameMovieRelease). */
function reuseInfoFor(adId) {
  try {
    const r = require("./aititle").resultFor(adId);
    if (r && r.status === "accepted" && r.canonical_id) return { canonical_id: r.canonical_id, edition: r.edition || "default" };
  } catch {}
  return { canonical_id: null, edition: null };
}

// ---- §11 Очередь ----

const newJobId = () => `j_${crypto.randomBytes(6).toString("hex")}`;
let jobSeq = 0;
function nextSeq() {
  if (!jobSeq) for (const j of store.allJobs().values()) jobSeq = Math.max(jobSeq, Number(j.seq) || 0);
  return ++jobSeq;
}

function publicJob(j) {
  return {
    job_id: j.job_id,
    hls_id: j.hls_id || null,
    ad_id: String(j.ad_id),
    path: j.path,
    size: j.size,
    hash: j.hash || null,
    canonical_id: j.canonical_id || null,
    edition: j.edition || null,
    episode: j.episode || null,
    priority: j.priority,
    profile: j.profile || null,
    leased_until: j.leased_until || 0
  };
}

/**
 * Поставить файл в очередь. Приоритет 0 — пользователь ждёт, 1 — следующие серии, 2 — новая раздача,
 * 3 — остальное, 4 — v1 после v1-copy. Дедупликация по ad_id|путь и по hls_id: у существующего задания
 * приоритет повышается (failed — ставится заново). Файл, у которого уже готовый ассет, не ставится.
 * → { job, created } | { job, deduped: true } | { skipped: "ready", hls_id }.
 */
function enqueue(opts, now = Date.now()) {
  const adId = String(opts?.ad_id ?? "");
  const path = normPath(opts?.path);
  const size = Number(opts?.size);
  const priority = Number.isInteger(opts?.priority) ? opts.priority : 3;
  if (!/^\d{1,20}$/.test(adId) || !path || path.length > 1000 || !Number.isSafeInteger(size) || size < 0 || priority < 0 || priority > 4) {
    throw new HlsError("bad_request", "Неверное задание.");
  }
  const profile = PROFILES.has(opts.profile) ? opts.profile : null;
  const hash = typeof opts.hash === "string" && /^[0-9a-f]{32,64}$/i.test(opts.hash) ? opts.hash.toLowerCase() : null;
  const id = typeof opts.hls_id === "string" && HEX16.test(opts.hls_id) ? opts.hls_id : hlsId({ hash, adId, path, size });

  if (!profile) {
    const al = store.getAlias(adId, path);
    if (al && readyProfile(store.getAsset(al.hls_id))) return { skipped: "ready", hls_id: al.hls_id };
    if (readyProfile(store.getAsset(id))) {
      store.setAlias(adId, path, id, 0);
      return { skipped: "ready", hls_id: id };
    }
  }

  const existing = store.jobForFile(adId, path) || store.jobForHls(id);
  if (existing) {
    let changed = false;
    if (priority < existing.priority) { existing.priority = priority; changed = true; }
    if (existing.state === "failed") {
      Object.assign(existing, { state: "queued", attempts: 0, leased_until: 0 });
      delete existing.error;
      changed = true;
    }
    if (changed) store.putJob(existing.job_id, existing);
    return { job: existing, deduped: true };
  }

  const info = opts.canonical_id !== undefined ? { canonical_id: opts.canonical_id || null, edition: opts.edition || null } : reuseInfoFor(adId);
  const job = {
    job_id: newJobId(),
    hls_id: id,
    ad_id: adId,
    path,
    size,
    hash,
    canonical_id: info.canonical_id,
    edition: info.edition || (info.canonical_id ? "default" : null),
    episode: opts.episode !== undefined ? opts.episode || null : episodeOf(path),
    priority,
    state: "queued",
    attempts: 0,
    progress: 0,
    eta_s: null,
    profile,
    leased_until: 0,
    created_at: sec(now),
    seq: nextSeq()
  };
  store.putJob(job.job_id, job);
  return { job, created: true };
}

/** Видеофайлы раздачи для очереди (без sample). */
function videoFiles(files) {
  return (Array.isArray(files) ? files : []).filter(f =>
    f && typeof f.path === "string" && (f.type === undefined || f.type === "file") &&
    VIDEO_EXT.test(f.path) && !/(?:^|[^a-z])sample(?:[^a-z]|$)/i.test(normPath(f.path).split("/").pop()));
}

/**
 * Поставить видеофайлы готовой раздачи. Файл, у которого по ключу переиспользования уже есть готовая версия,
 * не ставится (кодирование откладывается до первого «Смотреть»). Возвращает, сколько заданий создано или повышено.
 */
function enqueueMagnet(m, priority = 2, now = Date.now()) {
  const info = reuseInfoFor(m.id);
  let n = 0;
  for (const f of videoFiles(m.files)) {
    const path = normPath(f.path);
    const episode = episodeOf(path);
    const key = store.reuseKeyOf({ ...info, episode });
    if (key && store.assetsByReuseKey(key).some(id => readyProfile(store.getAsset(id)))) continue;
    try {
      const r = enqueue({ ad_id: m.id, path, size: Number(f.size) || 0, hash: m.hash, priority, ...info, episode }, now);
      if (r.job) n++;
    } catch (err) {
      console.warn("[hls] не удалось поставить в очередь:", err.message);
    }
  }
  return n;
}

// Статусы раздач с прошлой загрузки библиотеки: в очередь — только при переходе в «готова, есть файлы».
// Первая загрузка после запуска — точка отсчёта (не ставит всю медиатеку разом).
const lastStatus = new Map();
let baseline = false;

/** Вызывается из buildLibrary() (только при HLS_ENABLED). Возвращает, сколько заданий поставлено. */
function onLibrary(magnets, now = Date.now()) {
  if (!config.HLS_ENABLED || !Array.isArray(magnets)) return 0;
  const first = !baseline;
  const present = new Set();
  let n = 0;
  for (const m of magnets) {
    const key = String(m.id);
    present.add(key);
    const readyNow = m.statusCode === 4 && Array.isArray(m.files) && m.files.length > 0;
    const prev = lastStatus.get(key);
    lastStatus.set(key, readyNow ? 4 : (m.statusCode === 4 ? -1 : m.statusCode));
    if (!first && readyNow && prev !== 4) n += enqueueMagnet(m, 2, now);
  }
  for (const key of [...lastStatus.keys()]) if (!present.has(key)) lastStatus.delete(key);
  baseline = true;
  return n;
}

/** Задание, которое сейчас кодирует ассет (в аренде), кроме exceptId. */
function liveEncoder(assetId, exceptId, nowS) {
  for (const j of store.allJobs().values()) {
    if (j.job_id !== exceptId && j.hls_id === assetId && j.profile && j.state === "running" && (j.leased_until || 0) > nowS) return j;
  }
  return null;
}

// Порядок выдачи: приоритет, затем кто раньше поставлен (seq — порядковый номер постановки)
const jobOrder = (a, b) => a.priority - b.priority || (a.created_at || 0) - (b.created_at || 0) || (a.seq || 0) - (b.seq || 0);

/** Выдать следующее задание в аренду (или null → 204). Просроченная аренда возвращает задание в очередь. */
function next(now = Date.now()) {
  const t = sec(now);
  let best = null;
  for (const j of store.allJobs().values()) {
    if (j.state === "running" && (j.leased_until || 0) <= t) {
      j.state = "queued";
      j.leased_until = 0;
      store.putJob(j.job_id, j);
    }
    if (j.state !== "queued" || (j.leased_until || 0) > t) continue;
    if (!best || jobOrder(j, best) < 0) best = j;
  }
  if (!best) return null;
  best.state = "running";
  best.leased_until = t + LEASE_S;
  store.putJob(best.job_id, best);
  return publicJob(best);
}

function getLiveJob(jobOrId) {
  const id = typeof jobOrId === "string" ? jobOrId : jobOrId?.job_id;
  return id ? store.getJob(id) : null;
}

function finishAlias(job, id, shift) {
  store.setAlias(job.ad_id, job.path, id, shift);
  store.deleteJob(job.job_id);
  return { action: "alias", hls_id: id, shift_ms: shift };
}

function waitJob(job, t) {
  job.state = "queued";
  job.leased_until = t + WAIT_RETRY_S;
  store.putJob(job.job_id, job);
  return { action: "wait", retry_after_s: WAIT_RETRY_S };
}

function startEncode(job, id, asset, profile, t) {
  asset.profiles = asset.profiles || {};
  if (asset.profiles[profile]?.status !== "ready") asset.profiles[profile] = { status: "encoding", created_at: t };
  delete asset.error;
  store.putAsset(id, asset);
  if (!store.getAlias(job.ad_id, job.path)) store.setAlias(job.ad_id, job.path, id, 0);
  job.hls_id = id;
  job.profile = profile;
  job.state = "running";
  job.leased_until = Math.max(job.leased_until || 0, t + LEASE_S);
  store.putJob(job.job_id, job);
  return { action: "encode", hls_id: id, profile };
}

/**
 * Ответ на POST /internal/hls/probe: { action: "alias", hls_id, shift_ms } | { action: "encode", hls_id, profile } |
 * { action: "wait", retry_after_s }. job — задание или его job_id; body — { fingerprint, duration_ms, format, streams }.
 * Неизвестное задание → HlsError("unknown_job", 404), плохое тело → HlsError("bad_request", 400).
 */
function handleProbe(jobOrId, body, now = Date.now()) {
  const job = getLiveJob(jobOrId);
  if (!job) throw new HlsError("unknown_job", "Задание не найдено.", 404);
  const fp = typeof body?.fingerprint === "string" ? body.fingerprint.toLowerCase() : "";
  if (!HEX64.test(fp)) throw new HlsError("bad_request", "Неверный отпечаток.");
  const dur = Number(body.duration_ms);
  const durationMs = Number.isFinite(dur) && dur > 0 ? Math.round(dur) : null;
  const t = sec(now);

  // Перекодирование по команде админа (reencode): без переиспользования, всегда v1 из этого файла
  if (job.force && job.hls_id) {
    if (liveEncoder(job.hls_id, job.job_id, t)) return waitJob(job, t);
    const prev = store.getAsset(job.hls_id);
    const info = job.canonical_id ? { canonical_id: job.canonical_id, edition: job.edition || "default" } : reuseInfoFor(job.ad_id);
    const asset = {
      ...(prev || {}),
      fingerprint: fp,
      canonical_id: info.canonical_id || null,
      edition: info.edition || null,
      episode: job.episode || episodeOf(job.path) || null,
      duration_ms: durationMs,
      source: { ad_id: String(job.ad_id), path: job.path, size: job.size, hash: job.hash || null },
      profiles: prev?.profiles || {},
      active_profile: prev?.active_profile || null,
      pending_delete: prev?.pending_delete || [],
      last_access: prev?.last_access || t
    };
    return startEncode(job, job.hls_id, asset, "v1", t);
  }

  // Задание уже привязано к ассету и профилю (повтор после аренды или v1 после v1-copy)
  if (job.profile && job.hls_id) {
    const a = store.getAsset(job.hls_id);
    if (a && (!a.fingerprint || a.fingerprint === fp)) {
      if (a.profiles?.[job.profile]?.status === "ready") return finishAlias(job, job.hls_id, store.getAlias(job.ad_id, job.path)?.shift_ms || 0);
      if (liveEncoder(job.hls_id, job.job_id, t)) return waitJob(job, t);
      if (!a.fingerprint) a.fingerprint = fp;
      if (!a.duration_ms && durationMs) a.duration_ms = durationMs;
      return startEncode(job, job.hls_id, a, job.profile, t);
    }
    job.profile = null; // ассет вытеснен или файл другой — решаем заново
  }

  // 1. Тот же файл: совпал отпечаток
  const hit = store.assetByFingerprint(fp);
  if (hit) {
    if (readyProfile(hit.asset)) return finishAlias(job, hit.id, 0);
    if (liveEncoder(hit.id, job.job_id, t)) return waitJob(job, t);
    return startEncode(job, hit.id, hit.asset, decideProfile(body, job.size), t);
  }

  // 2. Тот же монтаж из другой раздачи
  const info = job.canonical_id ? { canonical_id: job.canonical_id, edition: job.edition || "default" } : reuseInfoFor(job.ad_id);
  const episode = job.episode || episodeOf(job.path);
  const me = { canonical_id: info.canonical_id, edition: info.edition, episode, duration_ms: durationMs };
  let best = null;
  let encoding = false;
  for (const id of store.assetsByReuseKey(store.reuseKeyOf(me))) {
    const a = store.getAsset(id);
    if (!canReuse(a, me)) continue;
    const s = shiftMs(a.duration_ms, durationMs);
    if (readyProfile(a)) {
      if (!best || Math.abs(s) < Math.abs(best.s)) best = { id, s };
    } else if (liveEncoder(id, job.job_id, t)) {
      encoding = true;
    }
  }
  if (best) return finishAlias(job, best.id, best.s);
  if (encoding) return waitJob(job, t);

  // 3. Новый ассет
  const id = job.hls_id || hlsId({ hash: job.hash, adId: job.ad_id, path: job.path, size: job.size });
  const prev = store.getAsset(id);
  const asset = {
    fingerprint: fp,
    canonical_id: info.canonical_id || null,
    edition: info.edition || null,
    episode: episode || null,
    duration_ms: durationMs,
    source: { ad_id: String(job.ad_id), path: job.path, size: job.size, hash: job.hash || null },
    profiles: prev?.profiles || {},
    active_profile: prev?.active_profile || null,
    pending_delete: prev?.pending_delete || [],
    last_access: prev?.last_access || t
  };
  return startEncode(job, id, asset, decideProfile(body, job.size), t);
}

/** POST /internal/hls/progress: продлевает аренду. → задание или null (неизвестно). */
function progress(body, now = Date.now()) {
  const job = getLiveJob(body?.job_id);
  if (!job) return null;
  const t = sec(now);
  const p = Number(body.progress);
  const eta = Number(body.eta_s);
  job.progress = Number.isFinite(p) ? Math.min(1, Math.max(0, p)) : job.progress;
  job.eta_s = Number.isFinite(eta) && eta >= 0 ? Math.round(eta) : null;
  if (typeof body.stage === "string") job.stage = body.stage.slice(0, 32);
  job.state = "running";
  job.leased_until = t + LEASE_S;
  store.putJob(job.job_id, job);
  return publicJob(job);
}

/**
 * POST /internal/hls/done. Профиль становится ready. v1-copy → active_profile (если v1 ещё нет) и в очередь v1
 * с приоритетом 4; v1 → active_profile, а v1-copy уходит в pending_delete. → { ok, active_profile }.
 */
function done(body, now = Date.now()) {
  const id = String(body?.hls_id || "");
  const profile = body?.profile;
  if (!HEX16.test(id) || !PROFILES.has(profile)) throw new HlsError("bad_request", "Неверный hls_id или профиль.");
  const asset = store.getAsset(id);
  if (!asset) throw new HlsError("unknown_asset", "Ассет не найден.", 404);
  const manifest = cleanManifest(body.manifest);
  if (!manifest) throw new HlsError("bad_manifest", "Нет manifest или в нём нет видео.");
  const t = sec(now);
  const job = getLiveJob(body.job_id);
  if (job) store.deleteJob(job.job_id);

  asset.profiles = asset.profiles || {};
  asset.pending_delete = Array.isArray(asset.pending_delete) ? asset.pending_delete : [];
  asset.profiles[profile] = {
    status: "ready",
    created_at: t,
    bytes: Number.isSafeInteger(Number(body.bytes)) ? Number(body.bytes) : 0,
    manifest_sha256: typeof body.manifest_sha256 === "string" ? body.manifest_sha256.slice(0, 64) : null,
    manifest
  };
  if (!asset.duration_ms && manifest.duration_ms) asset.duration_ms = manifest.duration_ms;
  asset.pending_delete = asset.pending_delete.filter(p => p !== profile);
  delete asset.error;
  if (profile === "v1") {
    asset.active_profile = "v1";
    if (asset.profiles["v1-copy"] && !asset.pending_delete.includes("v1-copy")) asset.pending_delete.push("v1-copy");
  } else if (asset.profiles.v1?.status !== "ready") {
    asset.active_profile = "v1-copy";
  }
  store.putAsset(id, asset);
  if (job?.force && job.replaces && job.replaces !== id) replaceAsset(job.replaces, id);
  if (job && (job.force || !store.getAlias(job.ad_id, job.path))) store.setAlias(job.ad_id, job.path, id, 0);

  if (profile === "v1-copy" && !asset.profiles.v1) {
    const s = asset.source || {};
    try {
      enqueue({ ad_id: s.ad_id, path: s.path, size: s.size, hash: s.hash, priority: 4, hls_id: id, profile: "v1",
        canonical_id: asset.canonical_id, edition: asset.edition, episode: asset.episode }, now);
    } catch (err) {
      console.warn("[hls] не удалось поставить v1:", err.message);
    }
  }
  return { ok: true, active_profile: asset.active_profile };
}

/**
 * POST /internal/hls/failed. retryable — задание вернётся в очередь через attempts × 5 мин (до 3 попыток),
 * иначе state "failed" (повтор — новой постановкой, например «Смотреть»). → задание или null.
 */
function failed(body, now = Date.now()) {
  const job = getLiveJob(body?.job_id);
  if (!job) return null;
  const t = sec(now);
  job.attempts = (job.attempts || 0) + 1;
  job.error = { stage: String(body.stage || "").slice(0, 32), error: String(body.error || "").slice(0, 500), at: t };
  if (body.retryable === true && job.attempts < MAX_ATTEMPTS) {
    job.state = "queued";
    job.leased_until = t + FAIL_BACKOFF_S * job.attempts;
  } else {
    job.state = "failed";
    job.leased_until = 0;
    const a = job.hls_id && job.profile ? store.getAsset(job.hls_id) : null;
    if (a) {
      if (a.profiles?.[job.profile]?.status === "encoding") delete a.profiles[job.profile];
      a.error = job.error;
      store.putAsset(job.hls_id, a);
    }
  }
  store.putJob(job.job_id, job);
  return publicJob(job);
}

/** GET /internal/hls/pending-deletes → { items: [{hls_id, profile}] }. */
function pendingDeletes() {
  const items = [];
  for (const [id, a] of store.allAssets()) {
    for (const profile of Array.isArray(a.pending_delete) ? a.pending_delete : []) items.push({ hls_id: id, profile });
  }
  return { items };
}

/**
 * POST /internal/hls/evicted: [{hls_id, profile, last_access}] удалены с диска CDN. Профиль исчезает из записи;
 * без профилей — исчезают ассет и его алиасы. → сколько профилей убрано.
 */
function evicted(list) {
  let n = 0;
  for (const it of Array.isArray(list) ? list : []) {
    const id = String(it?.hls_id || "");
    const a = store.getAsset(id);
    if (!a || !PROFILES.has(it.profile) || !a.profiles?.[it.profile]) continue;
    delete a.profiles[it.profile];
    a.pending_delete = (a.pending_delete || []).filter(p => p !== it.profile);
    if (a.active_profile === it.profile) {
      a.active_profile = ["v1", "v1-copy"].find(p => a.profiles[p]?.status === "ready") || null;
    }
    n++;
    if (!Object.keys(a.profiles).length) store.deleteAsset(id);
    else store.putAsset(id, a);
  }
  return n;
}

let lastCdnStats = null;

/** POST /internal/hls/cdn-stats: последнее состояние диска CDN (в памяти, для админки T6). */
function cdnStats(body, now = Date.now()) {
  const num = k => (Number.isFinite(Number(body?.[k])) ? Number(body[k]) : null);
  lastCdnStats = {
    disk_total: num("disk_total"),
    disk_used: num("disk_used"),
    assets: num("assets"),
    profiles: num("profiles"),
    at: num("at"),
    received_at: sec(now)
  };
  return lastCdnStats;
}

const getCdnStats = () => lastCdnStats;

/**
 * Статус файла раздачи для variant-url (T4): { status: "none"|"encoding"|"ready", hls_id, profile, shift_ms, eta,
 * progress, state }. eta — секунды (null — неизвестно). state — состояние задания (queued/running/failed) или null.
 */
function statusFor(adId, path) {
  const key = String(adId);
  const p = normPath(path);
  const al = store.getAlias(key, p);
  const res = { status: "none", hls_id: null, profile: null, shift_ms: null, eta: null, progress: null, state: null };
  if (al) {
    const a = store.getAsset(al.hls_id);
    res.hls_id = al.hls_id;
    if (a) {
      const prof = readyProfile(a);
      if (prof) return { ...res, status: "ready", profile: prof, shift_ms: al.shift_ms || 0 };
      const enc = [...store.allJobs().values()].find(j => j.hls_id === al.hls_id && j.profile && j.state !== "failed");
      if (enc) {
        return { ...res, status: "encoding", profile: enc.profile, shift_ms: al.shift_ms || 0, eta: enc.eta_s ?? null, progress: enc.progress ?? 0, state: enc.state };
      }
    }
  }
  const job = store.jobForFile(key, p);
  if (job) {
    res.hls_id = res.hls_id || job.hls_id || null;
    res.state = job.state;
    if (job.state !== "failed") return { ...res, status: "encoding", profile: job.profile || null, eta: job.eta_s ?? null, progress: job.progress ?? 0 };
  }
  return res;
}

/** HLS включён для пользователя: HLS_ENABLED и (HLS_BETA_USERS пуст или id в нём). */
function enabledFor(userId) {
  if (!config.HLS_ENABLED) return false;
  return !config.HLS_BETA_USERS.length || config.HLS_BETA_USERS.includes(String(userId ?? ""));
}

/** Раздача удалена (cleanup.forget): алиасы и задания этого adId. Ассеты остаются. */
function forgetMagnet(adId) {
  const key = String(adId);
  lastStatus.delete(key);
  return { aliases: store.deleteAliasesOf(key), jobs: store.deleteJobsOf(key) };
}

// ---- manifest.json и master.m3u8 (T4) ----

const DIR_RE = /^[a-z0-9_-]{1,16}$/i;
const CODEC_RE = /^[a-z0-9.]{3,40}$/i;
const LANG_RE = /^[a-z]{2,3}(?:-[a-z0-9]{2,8})?$/i;
const posInt = v => (Number.isSafeInteger(Number(v)) && Number(v) > 0 ? Number(v) : null);
const cleanName = v => String(v ?? "").replace(/[\u0000-\u001f"\u007f]/g, " ").replace(/\s+/g, " ").trim().slice(0, 80);

/**
 * manifest.json профиля (воркер присылает его целиком в done) → только поля, нужные master.m3u8. Без видео → null.
 * Видео до 4 (у исходника ≤720p — только 720p/, у v1-copy — одна дорожка, кодек может быть avc1), звук до 4, субтитры до 6.
 */
function cleanManifest(m) {
  if (!m || typeof m !== "object") return null;
  const video = (Array.isArray(m.video) ? m.video : [])
    .filter(v => v && DIR_RE.test(String(v.dir)) && CODEC_RE.test(String(v.codec)) && posInt(v.bandwidth))
    .slice(0, 4)
    .map(v => ({
      dir: String(v.dir),
      codec: String(v.codec),
      width: posInt(v.width),
      height: posInt(v.height),
      bandwidth: posInt(v.bandwidth),
      avg_bandwidth: posInt(v.avg_bandwidth),
      fps: Number.isFinite(Number(v.fps)) && Number(v.fps) > 0 && Number(v.fps) < 1000 ? Number(v.fps) : null
    }));
  if (!video.length) return null;
  const lang = v => (LANG_RE.test(String(v ?? "")) ? String(v).toLowerCase() : null);
  const audio = (Array.isArray(m.audio) ? m.audio : [])
    .filter(a => a && DIR_RE.test(String(a.dir)))
    .slice(0, 4)
    .map(a => ({
      dir: String(a.dir),
      codec: CODEC_RE.test(String(a.codec)) ? String(a.codec) : "mp4a.40.2",
      channels: posInt(a.channels) || 2,
      language: lang(a.language),
      name: cleanName(a.name) || String(a.dir),
      default: a.default === true
    }));
  const subtitles = (Array.isArray(m.subtitles) ? m.subtitles : [])
    .filter(s => s && DIR_RE.test(String(s.dir)))
    .slice(0, 6)
    .map(s => ({ dir: String(s.dir), language: lang(s.language), name: cleanName(s.name) || String(s.dir), forced: s.forced === true }));
  return {
    duration_ms: posInt(m.duration_ms),
    encoder: typeof m.encoder === "string" ? m.encoder.slice(0, 16) : null,
    video,
    audio,
    subtitles
  };
}

/**
 * master.m3u8 ассета (§6, §10; пример — docs/hls-fixtures/master-v1.m3u8) строго по manifest: EXT-X-MEDIA для звука (aud)
 * и субтитров (sub), EXT-X-STREAM-INF на каждый элемент manifest.video, абсолютные подписанные ссылки CDN с тем же exp.
 * Ассет не готов или без manifest → HlsError 404; CDN не настроен → HlsError 503.
 */
function masterPlaylist(id, exp, opts = {}) {
  const a = store.getAsset(String(id));
  const profile = readyProfile(a);
  const man = profile ? a.profiles[profile].manifest : null;
  if (!man || !Array.isArray(man.video) || !man.video.length) throw new HlsError("not_ready", "HLS ещё не готов.", 404);
  const url = rel => signHlsUrl(id, profile, `${rel}/index.m3u8`, exp, opts);
  const yn = b => (b ? "YES" : "NO");
  const lines = ["#EXTM3U", "#EXT-X-VERSION:7", "#EXT-X-INDEPENDENT-SEGMENTS"];
  const audio = man.audio || [];
  const subs = man.subtitles || [];
  const defAudio = audio.findIndex(x => x.default);
  audio.forEach((x, i) => {
    const attrs = ["TYPE=AUDIO", 'GROUP-ID="aud"', `NAME="${cleanName(x.name) || x.dir}"`];
    if (x.language) attrs.push(`LANGUAGE="${x.language}"`);
    attrs.push(`DEFAULT=${yn(defAudio >= 0 ? i === defAudio : i === 0)}`, "AUTOSELECT=YES", `CHANNELS="${x.channels || 2}"`, `URI="${url(x.dir)}"`);
    lines.push(`#EXT-X-MEDIA:${attrs.join(",")}`);
  });
  for (const x of subs) {
    const attrs = ["TYPE=SUBTITLES", 'GROUP-ID="sub"', `NAME="${cleanName(x.name) || x.dir}"`];
    if (x.language) attrs.push(`LANGUAGE="${x.language}"`);
    // FORCED=YES требует AUTOSELECT=YES (RFC 8216 §4.3.4.1)
    attrs.push("DEFAULT=NO", `AUTOSELECT=${yn(x.forced)}`, `FORCED=${yn(x.forced)}`, `URI="${url(x.dir)}"`);
    lines.push(`#EXT-X-MEDIA:${attrs.join(",")}`);
  }
  const aCodec = audio.length ? audio[defAudio >= 0 ? defAudio : 0].codec || "mp4a.40.2" : null;
  for (const v of man.video) {
    const attrs = [`BANDWIDTH=${v.bandwidth}`];
    if (v.avg_bandwidth) attrs.push(`AVERAGE-BANDWIDTH=${v.avg_bandwidth}`);
    attrs.push(`CODECS="${aCodec ? `${v.codec},${aCodec}` : v.codec}"`);
    if (v.width && v.height) attrs.push(`RESOLUTION=${v.width}x${v.height}`);
    if (v.fps) attrs.push(`FRAME-RATE=${Number(v.fps.toFixed(3))}`);
    attrs.push("VIDEO-RANGE=SDR");
    if (audio.length) attrs.push('AUDIO="aud"');
    if (subs.length) attrs.push('SUBTITLES="sub"');
    lines.push(`#EXT-X-STREAM-INF:${attrs.join(",")}`, url(v.dir));
  }
  return lines.join("\n") + "\n";
}

// ---- Следующие серии (variant-url, приоритет 1) ----

/** Видеофайлы раздачи со следующими сериями того же сезона (N+1 … N+count) после path. */
function nextEpisodes(files, path, count = 2) {
  const cur = episodeOf(path);
  if (!cur) return [];
  const [, s, e] = cur.match(/^S(\d+)E(\d+)$/);
  const want = new Map();
  for (let k = 1; k <= count; k++) want.set(epFmt(s, Number(e) + k), k);
  const found = new Map();
  for (const f of videoFiles(files)) {
    const k = want.get(episodeOf(f.path));
    if (k && !found.has(k) && normPath(f.path) !== normPath(path)) found.set(k, f);
  }
  return [...found.keys()].sort((a, b) => a - b).map(k => found.get(k));
}

// ---- Админка (T6) ----

/**
 * Перекодировать v1 из файла раздачи (новый источник). Файл получает свой ассет (hls_id файла), задание — приоритет 0,
 * без переиспользования. replaces — ассет, который он заменяет (по умолчанию тот, что сейчас играет этот файл, или готовый
 * по ключу переиспользования): после done алиасы старого ассета переходят на новый, старый уходит в pending_delete.
 * → { job, replaces }.
 */
function reencode(opts, now = Date.now()) {
  const adId = String(opts?.ad_id ?? "");
  const path = normPath(opts?.path);
  const hash = typeof opts?.hash === "string" && /^[0-9a-f]{32,64}$/i.test(opts.hash) ? opts.hash.toLowerCase() : null;
  const size = Number(opts?.size);
  if (!/^\d{1,20}$/.test(adId) || !path || !Number.isSafeInteger(size) || size < 0) throw new HlsError("bad_request", "Неверный файл.");
  const id = hlsId({ hash, adId, path, size });
  let replaces = typeof opts.replaces === "string" && HEX16.test(opts.replaces) ? opts.replaces : null;
  if (replaces && !store.getAsset(replaces)) throw new HlsError("unknown_asset", "Ассет не найден.", 404);
  if (!replaces) {
    const al = store.getAlias(adId, path);
    if (al && store.getAsset(al.hls_id)) replaces = al.hls_id;
  }
  if (!replaces) {
    const info = reuseInfoFor(adId);
    const key = store.reuseKeyOf({ ...info, episode: episodeOf(path) });
    replaces = store.assetsByReuseKey(key).find(x => readyProfile(store.getAsset(x))) || null;
  }
  const t = sec(now);
  for (const j of [store.jobForFile(adId, path), store.jobForHls(id)]) {
    if (!j || !store.getJob(j.job_id)) continue;
    if (j.state === "running" && (j.leased_until || 0) > t) throw new HlsError("busy", "Этот файл сейчас кодируется.", 409);
    store.deleteJob(j.job_id);
  }
  const r = enqueue({ ad_id: adId, path, size, hash, priority: 0, hls_id: id, profile: "v1" }, now);
  const job = r.job;
  job.force = true;
  job.replaces = replaces && replaces !== id ? replaces : null;
  store.putJob(job.job_id, job);
  return { job: publicJob(job), replaces: job.replaces };
}

/** done перекодирования: алиасы старого ассета → новый (сдвиг пересчитывается), старый — в pending_delete. */
function replaceAsset(oldId, newId) {
  const old = store.getAsset(oldId);
  const nw = store.getAsset(newId);
  if (!old || !nw) return;
  const d = shiftMs(nw.duration_ms, old.duration_ms);
  for (const key of store.aliasesOf(oldId)) {
    const al = store.allAliases().get(key);
    if (!al || d === null) continue;
    const bar = key.indexOf("|");
    store.setAlias(key.slice(0, bar), key.slice(bar + 1), newId, (al.shift_ms || 0) + d);
  }
  old.active_profile = null;
  old.pending_delete = Object.keys(old.profiles || {});
  store.putAsset(oldId, old);
}

/** Повторить задание (админка): снова в очередь сразу, попытки с нуля. Задание в аренде → HlsError 409. */
function retryJob(jobId, now = Date.now()) {
  const job = getLiveJob(String(jobId || ""));
  if (!job) throw new HlsError("unknown_job", "Задание не найдено.", 404);
  if (job.state === "running" && (job.leased_until || 0) > sec(now)) throw new HlsError("busy", "Задание сейчас выполняется.", 409);
  Object.assign(job, { state: "queued", attempts: 0, leased_until: 0 });
  delete job.error;
  store.putJob(job.job_id, job);
  const a = job.hls_id ? store.getAsset(job.hls_id) : null;
  if (a?.error) {
    delete a.error;
    store.putAsset(job.hls_id, a);
  }
  return publicJob(job);
}

/**
 * Удалить профиль или весь ассет (админка): профиль уходит в pending_delete, CDN удалит файлы и пришлёт evicted —
 * тогда исчезнет и запись. Без профиля — все профили; задания ассета вне аренды снимаются. Ассет без профилей
 * (на CDN ничего нет) удаляется сразу. → { hls_id, pending_delete, removed }.
 */
function requestDelete(id, profile, now = Date.now()) {
  const a = store.getAsset(String(id || ""));
  if (!a) throw new HlsError("unknown_asset", "Ассет не найден.", 404);
  if (profile !== undefined && profile !== null && !PROFILES.has(profile)) throw new HlsError("bad_request", "Неверный профиль.");
  if (profile && !a.profiles?.[profile]) throw new HlsError("unknown_profile", "У ассета нет такого профиля.", 404);
  const t = sec(now);
  const names = profile ? [profile] : Object.keys(a.profiles || {});
  if (!profile) {
    for (const j of [...store.allJobs().values()]) {
      if (j.hls_id === id && !(j.state === "running" && (j.leased_until || 0) > t)) store.deleteJob(j.job_id);
    }
    if (!names.length) {
      store.deleteAsset(id);
      return { hls_id: id, pending_delete: [], removed: true };
    }
  }
  a.pending_delete = [...new Set([...(Array.isArray(a.pending_delete) ? a.pending_delete : []), ...names])];
  if (names.includes(a.active_profile)) {
    a.active_profile = ["v1", "v1-copy"].find(p => !a.pending_delete.includes(p) && a.profiles?.[p]?.status === "ready") || null;
  }
  store.putAsset(id, a);
  return { hls_id: id, pending_delete: a.pending_delete, removed: false };
}

/** Состояние для админки: очередь, ассеты с профилями и размерами, последнее cdn-stats. */
function adminState(now = Date.now()) {
  const jobs = [...store.allJobs().values()].sort(jobOrder).map(j => ({
    ...publicJob(j),
    state: j.state,
    attempts: j.attempts || 0,
    progress: j.progress ?? 0,
    eta_s: j.eta_s ?? null,
    stage: j.stage || null,
    created_at: j.created_at || null,
    error: j.error || null,
    force: !!j.force,
    replaces: j.replaces || null
  }));
  let bytes = 0;
  let ready = 0;
  const assets = [...store.allAssets()].map(([id, a]) => {
    const profiles = {};
    let total = 0;
    for (const [name, p] of Object.entries(a.profiles || {})) {
      profiles[name] = { status: p.status, bytes: p.bytes || 0, created_at: p.created_at || null, encoder: p.manifest?.encoder || null };
      total += Number(p.bytes) || 0;
    }
    bytes += total;
    if (readyProfile(a)) ready++;
    const aliases = store.aliasesOf(id).map(key => {
      const bar = key.indexOf("|");
      return { ad_id: key.slice(0, bar), path: key.slice(bar + 1), shift_ms: store.allAliases().get(key)?.shift_ms || 0 };
    });
    return {
      hls_id: id,
      source: a.source || null,
      canonical_id: a.canonical_id || null,
      edition: a.edition || null,
      episode: a.episode || null,
      duration_ms: a.duration_ms || null,
      active_profile: a.active_profile || null,
      pending_delete: a.pending_delete || [],
      last_access: a.last_access || null,
      error: a.error || null,
      profiles,
      bytes: total,
      aliases
    };
  }).sort((x, y) => (y.last_access || 0) - (x.last_access || 0));
  const count = st => jobs.filter(j => j.state === st).length;
  return {
    enabled: !!config.HLS_ENABLED,
    configured: {
      cdn_url: !!config.HLS_CDN_URL,
      sign_secret: !!config.HLS_SIGN_SECRET,
      worker_token: !!config.HLS_WORKER_TOKEN,
      cdn_token: !!config.HLS_CDN_TOKEN
    },
    now: sec(now),
    totals: { jobs: jobs.length, queued: count("queued"), running: count("running"), failed: count("failed"), assets: assets.length, ready, bytes },
    queue: jobs,
    assets,
    cdn: getCdnStats()
  };
}

function _reset() {
  lastStatus.clear();
  baseline = false;
  lastCdnStats = null;
  jobSeq = 0;
  store._reset();
}

module.exports = {
  HlsError,
  PROFILES,
  MASTER_TTL_S,
  LEASE_S,
  WAIT_RETRY_S,
  MAX_ATTEMPTS,
  // чистые функции
  hlsId,
  signCdn,
  signHlsUrl,
  signMaster,
  verifyMaster,
  masterPath,
  episodeOf,
  shiftMs,
  fingerprintMatch,
  canReuse,
  computeDirectOk,
  decideProfile,
  readyProfile,
  videoFiles,
  // данные
  directFor,
  touch,
  enqueue,
  enqueueMagnet,
  onLibrary,
  next,
  handleProbe,
  progress,
  done,
  failed,
  pendingDeletes,
  evicted,
  cdnStats,
  getCdnStats,
  statusFor,
  enabledFor,
  forgetMagnet,
  normAcodec,
  cleanManifest,
  masterPlaylist,
  nextEpisodes,
  reencode,
  retryJob,
  requestDelete,
  adminState,
  _reset
};

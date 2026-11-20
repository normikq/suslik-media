"use strict";

const config = require("../config");
const store = require("./hls-store");
const hls = require("./hls");
const ca = require("./custom-audio");
const { pickAudio } = require("./sidecars");

// ---- Внешние озвучки в HLS (docs/HLS.md §18) --------------------------------------------------------------------
// Внешняя озвучка ассета — .mka рядом с видео в той же раздаче (sidecar, ключ file:<путь>, сдвиг 0) или привязанная
// через /api/custom-audio (ключ custom:<trackId>, свой offsetMs). Подбор — как у ТВ (tv-info, CUSTOM-AUDIO-API.md §8):
// sidecars.pickAudio и custom-audio.trackLinks (с раздачами того же релиза) для исходного файла ассета и его алиасов.
// Сдвиг для ассета: offset_ms = offsetMs + shift_ms файла, к которому привязана озвучка (§8).
//
// У ассета: ext_audio — ключ → { dir: "xN", kind, name, language, base_offset_ms, shift_ms, offset_ms, src: {ad_id, path},
// bytes?, created_at }. Номер xN выдаётся здесь и не переиспользуется, пока запись дорожки жива (и пока её папка
// ждёт удаления на CDN: pending_delete_paths [{profile, path}]). До 8 дорожек (x0…x7).
//
// Задания «звук» (type: "audio") — в общей очереди (hls-store), приоритет 3, при «Смотреть» — 1. Выдача и снимок
// дорожек — hls.next; mirror_url — маршрут /internal/hls/next. Итог — audioDone (POST /internal/hls/audio-done).
// Поводы: done видео-профиля (событие hls "profile-ready"), «Смотреть» (onWatch), события custom-audio
// added / offset / removed. HLS выключен — ничего не делается.

const MAX_EXT = 8;
const DIRS = Array.from({ length: MAX_EXT }, (_, i) => `x${i}`);
const PRIORITY = 3;
const WATCH_PRIORITY = 1;
const WATCH_THROTTLE_MS = 5 * 60 * 1000;
const MKA_RE = /\.mka$/i;
const HEX16 = /^[0-9a-f]{16}$/;
const CODEC_RE = /^[a-z0-9.]{3,40}$/i;
const KEY_MAX = 1100;

const sec = now => Math.floor(now / 1000);
const normPath = p => String(p ?? "").replace(/\\/g, "/");
const baseName = p => normPath(p).split("/").filter(Boolean).pop() || "";
const dirBase = p => {
  const parts = normPath(p).split("/").filter(Boolean);
  parts.pop();
  return parts.pop() || "";
};
const stem = p => baseName(p).replace(/\.[a-z0-9]{1,6}$/i, "");
const dirNum = d => Number(String(d || "").slice(1));

// Внешние вызовы (подменяются в тестах): список файлов раздачи, дорожки пользователей видео, раздачи того же релиза
const deps = {
  fileList: adId => require("./alldebrid").getMagnetFileList(Number(adId)),
  customTracks: async (adId, videoPath) => (await ca.trackLinks(Number(adId), videoPath)).items.map(it => it.track),
  companions: async adId => {
    const { getLibrary } = require("./library");
    const { data } = await getLibrary();
    const magnets = data?.magnets || [];
    const self = magnets.find(m => String(m.id) === String(adId));
    if (!self) return [];
    return magnets.filter(m => m.statusCode === 4 && m.id !== self.id && ca.isSameMovieRelease(m, self)).map(m => String(m.id));
  }
};

// ---- Названия и язык ----

// ISO 639-2 → 639-1 для LANGUAGE в master.m3u8 (RFC 5646 предпочитает короткий код); und/mul — без языка
const ISO2 = {
  rus: "ru", eng: "en", ukr: "uk", bel: "be", kaz: "kk", deu: "de", ger: "de", fra: "fr", fre: "fr", spa: "es", ita: "it",
  jpn: "ja", kor: "ko", zho: "zh", chi: "zh", por: "pt", pol: "pl", tur: "tr", ces: "cs", cze: "cs", nld: "nl", dut: "nl"
};

function hlsLang(v) {
  const s = String(v ?? "").trim().toLowerCase();
  if (!s || s === "und" || s === "mul") return null;
  if (ISO2[s]) return ISO2[s];
  return /^[a-z]{2,3}$/.test(s) ? s : null;
}

const stripLangTag = s => String(s || "").replace(/\s*\[[a-z]{2,3}\]\s*/gi, " ").replace(/\s+/g, " ").trim();

/**
 * Название озвучки из раздачи: озвучка из имени файла (как custom-audio.parseAudioName: «Movie.2010.LostFilm.mka» →
 * «LostFilm»), иначе хвост имени после имени видео, иначе папка, иначе имя файла (как dubLabel на ТВ).
 */
function fileTrackName(videoPath, audioPath) {
  let name = "";
  try {
    name = stripLangTag(ca.parseAudioName(normPath(audioPath)).voice);
  } catch {}
  if (!name) {
    const vBase = stem(videoPath);
    const n = stem(audioPath);
    let rest = vBase && n.toLowerCase().startsWith(vBase.toLowerCase()) ? n.slice(vBase.length) : n;
    rest = rest.replace(/[._]+/g, " ").replace(/^[\s-]+|[\s-]+$/g, "");
    name = rest || dirBase(audioPath) || baseName(audioPath);
  }
  return hls.cleanName(name) || stem(audioPath) || "audio";
}

/** Язык озвучки из раздачи: тег [rus] в имени, иначе кириллица — ru, иначе неизвестен. */
function fileTrackLang(audioPath) {
  const tag = ca.langTag(baseName(audioPath));
  if (tag) return hlsLang(tag);
  return /[а-яё]/i.test(baseName(audioPath)) ? "ru" : null;
}

/** Название озвучки пользователя: title без хвоста « [lang]» (язык идёт в LANGUAGE), иначе имя файла. */
function customTrackName(t) {
  const title = stripLangTag(t?.title);
  return hls.cleanName(title) || stem(t?.audioPath) || "audio";
}

// ---- Подбор ----

/**
 * Дорожки для файлов ассета. files: [{ ad_id, path, shift_ms, list, custom }] — list: файлы раздачи (AllDebrid),
 * custom: дорожки пользователей этого видео (как trackLinks). Порядок стабильный: по файлам, у каждого — сначала
 * из раздачи (в порядке списка), затем пользователей (по времени добавления). Только .mka. Повтор ключа — первый.
 * → [{ key, kind, name, language, base_offset_ms, shift_ms, offset_ms, src: {ad_id, path} }].
 */
function pickTracks(files) {
  const out = [];
  const seen = new Set();
  const push = t => {
    if (seen.has(t.key) || t.key.length > KEY_MAX) return;
    seen.add(t.key);
    out.push(t);
  };
  for (const f of Array.isArray(files) ? files : []) {
    const shift = Number(f.shift_ms) || 0;
    const list = Array.isArray(f.list) ? f.list : [];
    for (const s of pickAudio(list, f.path)) {
      if (!MKA_RE.test(s.path)) continue;
      const p = normPath(s.path);
      push({ key: `file:${p}`, kind: "file", name: fileTrackName(f.path, p), language: fileTrackLang(p),
        base_offset_ms: 0, shift_ms: shift, offset_ms: shift, src: { ad_id: String(f.ad_id), path: s.path } });
    }
    const custom = (Array.isArray(f.custom) ? f.custom : [])
      .filter(t => t && typeof t.id === "string" && typeof t.audioPath === "string" && MKA_RE.test(t.audioPath))
      .sort((a, b) => (a.createdAt || 0) - (b.createdAt || 0));
    for (const t of custom) {
      const base = Number.isFinite(Number(t.offsetMs)) ? Math.round(Number(t.offsetMs)) : 0;
      push({ key: `custom:${t.id}`, kind: "custom", name: customTrackName(t), language: hlsLang(t.lang),
        base_offset_ms: base, shift_ms: shift, offset_ms: base + shift, src: { ad_id: String(t.audioMagnetId), path: t.audioPath } });
    }
  }
  return out;
}

/** Файлы ассета: источник (если его раздача ещё в медиатеке — есть алиас) и алиасы, со сдвигом shift_ms. */
function filesOf(id) {
  const a = store.getAsset(id);
  if (!a) return [];
  const out = [];
  const seen = new Set();
  const add = (adId, path, shift) => {
    const key = store.fileKey(String(adId), normPath(path));
    if (seen.has(key)) return;
    seen.add(key);
    out.push({ ad_id: String(adId), path: normPath(path), shift_ms: Number(shift) || 0 });
  };
  const s = a.source;
  if (s && s.ad_id != null && s.path) {
    const al = store.getAlias(String(s.ad_id), normPath(s.path));
    if (al && al.hls_id === id) add(s.ad_id, s.path, al.shift_ms);
  }
  for (const key of store.aliasesOf(id).sort()) {
    const al = store.allAliases().get(key);
    const bar = key.indexOf("|");
    add(key.slice(0, bar), key.slice(bar + 1), al?.shift_ms);
  }
  return out;
}

/** Подбор по файлам ассета (сеть: списки раздач и дорожки пользователей; сбой — у файла просто нет дорожек). */
async function gather(id) {
  const files = [];
  for (const f of filesOf(id)) {
    let list = null;
    let custom = null;
    try { list = await deps.fileList(f.ad_id); } catch {}
    try { custom = await deps.customTracks(f.ad_id, f.path); } catch {}
    files.push({ ...f, list: Array.isArray(list) ? list : [], custom: Array.isArray(custom) ? custom : [] });
  }
  return pickTracks(files);
}

// ---- Номера папок ----

function extOf(a) {
  if (!a.ext_audio || typeof a.ext_audio !== "object" || Array.isArray(a.ext_audio)) a.ext_audio = {};
  return a.ext_audio;
}

function pendingPathsOf(a) {
  if (!Array.isArray(a.pending_delete_paths)) a.pending_delete_paths = [];
  return a.pending_delete_paths;
}

/** Свободная папка xN: не занята дорожкой, не ждёт удаления и не упомянута в manifest. */
function freeDir(a) {
  const used = new Set(Object.values(extOf(a)).map(e => e && e.dir));
  for (const p of pendingPathsOf(a)) used.add(p && p.path);
  for (const v of Object.values(a.profiles || {})) for (const x of v?.manifest?.audio || []) used.add(x.dir);
  return DIRS.find(d => !used.has(d)) || null;
}

/**
 * Записать подобранные дорожки в ext_audio ассета: новым — свободный xN (если дорожек меньше 8), у известных обновить
 * название, язык, сдвиг и источник. Дорожки, которых в подборе нет, не трогаются (снимаются только событием удаления).
 * → ключи из picks, у которых есть папка.
 */
function allocate(id, picks, now = Date.now()) {
  const a = store.getAsset(id);
  if (!a) return [];
  const ext = extOf(a);
  let changed = false;
  const keys = [];
  for (const p of Array.isArray(picks) ? picks : []) {
    let e = ext[p.key];
    if (!e) {
      if (Object.keys(ext).length >= MAX_EXT) continue;
      const dir = freeDir(a);
      if (!dir) continue;
      e = ext[p.key] = { dir, created_at: sec(now) };
      changed = true;
    }
    for (const k of ["kind", "name", "language", "base_offset_ms", "shift_ms", "offset_ms", "src"]) {
      if (JSON.stringify(e[k]) !== JSON.stringify(p[k])) {
        e[k] = p[k];
        changed = true;
      }
    }
    keys.push(p.key);
  }
  if (changed) store.putAsset(id, a);
  return keys;
}

// ---- Очередь ----

const audioJobsOf = (id, profile) =>
  [...store.allJobs().values()].filter(j => store.isAudioJob(j) && j.hls_id === id && (!profile || j.profile === profile));
const isLive = (j, t) => j.state === "running" && (j.leased_until || 0) > t;

/**
 * Поставить задание «звук» на дорожки keys профиля. Дедупликация по hls_id + профиль + набор ключей: дорожки, которые
 * уже кодируются с тем же сдвигом, не ставятся; ожидающее задание с тем же набором — только повышение приоритета
 * (упавшее — снова в очередь); иначе новое задание поглощает ожидающие (их дорожки входят в него).
 * → { job, created } | { job, deduped: true } | null.
 */
function enqueueAudio(id, profile, keys, priority = PRIORITY, now = Date.now()) {
  const a = store.getAsset(id);
  if (!a || !hls.PROFILES.has(profile)) return null;
  const ext = extOf(a);
  const t = sec(now);
  const jobs = audioJobsOf(id, profile);
  const running = jobs.filter(j => isLive(j, t));
  const want = [...new Set(keys)].filter(k => ext[k] && !running.some(j =>
    (j.tracks || []).some(tr => tr.key === k && tr.dir === ext[k].dir && tr.offset_ms === ext[k].offset_ms)));
  if (!want.length) return running.length ? { job: running[0], deduped: true } : null;
  const waiting = jobs.filter(j => !isLive(j, t));
  const union = [...new Set([...want, ...waiting.flatMap(j => j.keys || [])])].filter(k => ext[k]);
  union.sort((x, y) => dirNum(ext[x].dir) - dirNum(ext[y].dir));
  const prio = Math.min(priority, ...waiting.map(j => (Number.isInteger(j.priority) ? j.priority : PRIORITY)));
  if (waiting.length === 1 && waiting[0].keys.length === union.length && union.every(k => waiting[0].keys.includes(k))) {
    const j = waiting[0];
    j.priority = prio;
    if (j.state === "failed" || j.state === "running") {
      Object.assign(j, { state: "queued", attempts: 0, leased_until: 0 });
      delete j.error;
    }
    j.keys = union;
    store.putJob(j.job_id, j);
    return { job: j, deduped: true };
  }
  for (const j of waiting) store.deleteJob(j.job_id);
  const job = {
    job_id: hls.newJobId(),
    type: "audio",
    hls_id: id,
    profile,
    keys: union,
    priority: prio,
    state: "queued",
    attempts: 0,
    progress: 0,
    eta_s: null,
    leased_until: 0,
    created_at: t,
    seq: hls.nextSeq()
  };
  store.putJob(job.job_id, job);
  return { job, created: true };
}

/**
 * Поставить в очередь дорожки keys, которых нет в manifest профиля (или они там с другим сдвигом). profiles — какие
 * версии (по умолчанию все готовые, кроме ждущих удаления). → результаты enqueueAudio.
 */
function ensure(id, keys, { priority = PRIORITY, profiles = null, now = Date.now() } = {}) {
  const a = store.getAsset(id);
  if (!a) return [];
  const ext = extOf(a);
  const pending = Array.isArray(a.pending_delete) ? a.pending_delete : [];
  const targets = profiles || Object.keys(a.profiles || {}).filter(p => a.profiles[p]?.status === "ready");
  const out = [];
  // v1 готов — v1-copy доживает до audio-done v1 (hls.settleCopy), новых озвучек ей не кодируем
  const v1Ready = a.profiles?.v1?.status === "ready" && !pending.includes("v1");
  for (const profile of targets) {
    const v = a.profiles?.[profile];
    if (!v || !v.manifest || pending.includes(profile) || (profile === "v1-copy" && v1Ready)) continue;
    const audio = Array.isArray(v.manifest.audio) ? v.manifest.audio : [];
    const need = (keys || []).filter(k => ext[k] && !audio.some(x =>
      x.external?.key === k && x.dir === ext[k].dir && x.external.offset_ms === ext[k].offset_ms));
    if (!need.length) continue;
    const r = enqueueAudio(id, profile, need, priority, now);
    if (r) out.push(r);
  }
  return out;
}

/** Подобрать дорожки ассета, выдать папки и поставить недостающие. → результаты ensure. */
async function refresh(id, opts = {}) {
  const picks = await gather(id);
  allocate(id, picks, opts.now);
  const a = store.getAsset(id);
  if (!a) return [];
  return ensure(id, Object.keys(extOf(a)), opts);
}

/**
 * Убрать внешнюю дорожку ассета: запись из ext_audio и из manifest всех версий, папка xN — в pending_delete_paths
 * у версий на CDN, где она есть (или кодируется). Ожидающие задания теряют этот ключ (пустые — удаляются).
 */
function removeExternal(id, key, now = Date.now()) {
  const a = store.getAsset(id);
  const ext = a ? extOf(a) : null;
  const e = ext ? ext[key] : null;
  if (!e) return false;
  const t = sec(now);
  delete ext[key];
  const paths = pendingPathsOf(a);
  const pendingWhole = Array.isArray(a.pending_delete) ? a.pending_delete : [];
  const jobs = audioJobsOf(id);
  for (const [profile, v] of Object.entries(a.profiles || {})) {
    const audio = Array.isArray(v?.manifest?.audio) ? v.manifest.audio : [];
    const had = audio.some(x => x.dir === e.dir || x.external?.key === key);
    if (had) v.manifest.audio = audio.filter(x => x.dir !== e.dir && x.external?.key !== key);
    const encoding = jobs.some(j => j.profile === profile && isLive(j, t) && (j.keys || []).includes(key));
    if ((had || encoding) && v.status === "ready" && !pendingWhole.includes(profile) &&
        !paths.some(p => p.profile === profile && p.path === e.dir)) {
      paths.push({ profile, path: e.dir });
    }
  }
  store.putAsset(id, a);
  for (const j of jobs) {
    if (isLive(j, t) || !(j.keys || []).includes(key)) continue;
    j.keys = j.keys.filter(k => k !== key);
    if (j.keys.length) store.putJob(j.job_id, j);
    else store.deleteJob(j.job_id);
  }
  hls.settleCopy(id);
  return true;
}

// ---- audio-done ----

/**
 * POST /internal/hls/audio-done { job_id, hls_id, profile, tracks: [{ key, dir, codec, channels, bytes }] }: записи в
 * manifest.audio профиля (повтор key или dir — замена), внешние — после дорожек файла, по номеру папки. Сдвиг —
 * тот, что выдан воркеру в задании. Дорожку удалили или перенесли, пока кодировалась, — запись пропускается, лишняя
 * папка уходит в pending_delete_paths. Задание закрывается; сдвиг успели поменять — дорожка ставится снова.
 * → { ok, added, skipped: [key] }.
 */
function audioDone(body, now = Date.now()) {
  const id = String(body?.hls_id || "");
  const profile = body?.profile;
  if (!HEX16.test(id) || !hls.PROFILES.has(profile)) throw new hls.HlsError("bad_request", "Неверный hls_id или профиль.");
  const list = body.tracks;
  if (!Array.isArray(list) || list.length > MAX_EXT) throw new hls.HlsError("bad_request", "Ожидается tracks: до 8 дорожек.");
  const a = store.getAsset(id);
  if (!a) throw new hls.HlsError("unknown_asset", "Ассет не найден.", 404);
  const v = a.profiles?.[profile];
  if (!v || !v.manifest) throw new hls.HlsError("unknown_profile", "У ассета нет такого профиля.", 404);
  const t = sec(now);
  const job = store.getJob(String(body.job_id || ""));
  const myJob = job && store.isAudioJob(job) && job.hls_id === id && job.profile === profile ? job : null;
  const ext = extOf(a);
  const paths = pendingPathsOf(a);
  const onCdn = v.status === "ready" && !(a.pending_delete || []).includes(profile);
  let audio = Array.isArray(v.manifest.audio) ? [...v.manifest.audio] : [];
  let added = 0;
  const skipped = [];
  for (const tr of list) {
    const key = typeof tr?.key === "string" ? tr.key : "";
    const dir = String(tr?.dir ?? "");
    if (!key || !hls.EXT_DIR_RE.test(dir)) {
      skipped.push(key);
      continue;
    }
    const e = ext[key];
    if (!e || e.dir !== dir) {
      const owned = Object.values(ext).some(x => x && x.dir === dir);
      if (!owned && onCdn && !paths.some(p => p.profile === profile && p.path === dir)) paths.push({ profile, path: dir });
      skipped.push(key);
      continue;
    }
    const snap = (myJob?.tracks || []).find(x => x.key === key && x.dir === dir);
    const channels = Number(tr.channels);
    const entry = {
      dir,
      codec: CODEC_RE.test(String(tr.codec || "")) ? String(tr.codec) : "mp4a.40.2",
      channels: Number.isSafeInteger(channels) && channels > 0 && channels <= 16 ? channels : 2,
      language: e.language || null,
      name: hls.cleanName(e.name) || dir,
      default: false,
      external: { key, kind: e.kind === "custom" ? "custom" : "file", offset_ms: snap ? snap.offset_ms : Number(e.offset_ms) || 0 }
    };
    audio = audio.filter(x => x.dir !== dir && x.external?.key !== key);
    audio.push(entry);
    const bytes = Number(tr.bytes);
    if (Number.isSafeInteger(bytes) && bytes >= 0) e.bytes = bytes;
    e.done_at = t;
    added++;
  }
  const own = audio.filter(x => !x.external);
  const external = audio.filter(x => x.external).sort((x, y) => dirNum(x.dir) - dirNum(y.dir));
  v.manifest.audio = [...own, ...external];
  store.putAsset(id, a);
  if (myJob) store.deleteJob(myJob.job_id);
  ensure(id, Object.keys(ext), { profiles: [profile], now });
  hls.settleCopy(id); // у v1 теперь все озвучки v1-copy — копия уходит в pending_delete
  return { ok: true, added, skipped };
}

// ---- Поводы ----

const inflight = new Set();
function run(fn) {
  const p = Promise.resolve()
    .then(fn)
    .catch(err => console.warn("[hls] внешние озвучки:", err && err.message));
  inflight.add(p);
  p.finally(() => inflight.delete(p));
  return p;
}

/** Дождаться фоновых подборов (тесты). */
async function idle() {
  while (inflight.size) await Promise.all([...inflight]);
}

const watched = new Map(); // hls_id → когда проверяли при «Смотреть»

/** «Смотреть» на готовом ассете: недостающие дорожки — с приоритетом 1 (не чаще раза в 5 мин на ассет). */
function onWatch(id, now = Date.now()) {
  if (!config.HLS_ENABLED || !HEX16.test(String(id || ""))) return null;
  const last = watched.get(id) || 0;
  if (now - last < WATCH_THROTTLE_MS) return null;
  watched.set(id, now);
  if (watched.size > 5000) watched.delete(watched.keys().next().value);
  return run(() => refresh(id, { priority: WATCH_PRIORITY }));
}

/** Ассеты, у которых есть файл в одной из раздач adIds (источник с алиасом или алиас). */
function assetsWithMagnets(adIds) {
  const want = new Set([...adIds].map(String));
  const out = new Set();
  for (const [key, al] of store.allAliases()) {
    const adId = key.slice(0, key.indexOf("|"));
    if (want.has(adId) && al && store.getAsset(al.hls_id)) out.add(al.hls_id);
  }
  return [...out];
}

/** Ассеты, у которых есть внешняя дорожка с этим ключом. */
function assetsWithKey(key) {
  const out = [];
  for (const [id, a] of store.allAssets()) if (a?.ext_audio && a.ext_audio[key]) out.push(id);
  return out;
}

function onAdded(tracks) {
  if (!config.HLS_ENABLED) return null;
  const movies = [...new Set((Array.isArray(tracks) ? tracks : []).filter(t => t && MKA_RE.test(String(t.audioPath || ""))).map(t => String(t.movieMagnetId)))];
  if (!movies.length) return null;
  return run(async () => {
    const ids = new Set(movies);
    for (const m of movies) {
      try {
        for (const c of (await deps.companions(m)) || []) ids.add(String(c));
      } catch {}
    }
    for (const id of assetsWithMagnets(ids)) await refresh(id, { priority: PRIORITY });
  });
}

function onOffset(track) {
  if (!config.HLS_ENABLED || !track || typeof track.id !== "string") return 0;
  const key = `custom:${track.id}`;
  let n = 0;
  for (const id of assetsWithKey(key)) {
    const a = store.getAsset(id);
    const e = a.ext_audio[key];
    const base = Number.isFinite(Number(track.offsetMs)) ? Math.round(Number(track.offsetMs)) : 0;
    e.base_offset_ms = base;
    e.offset_ms = base + (Number(e.shift_ms) || 0);
    store.putAsset(id, a);
    n += ensure(id, [key], { priority: PRIORITY }).length;
  }
  return n;
}

function onRemoved(tracks) {
  if (!config.HLS_ENABLED) return 0;
  let n = 0;
  for (const t of Array.isArray(tracks) ? tracks : []) {
    if (!t || typeof t.id !== "string") continue;
    const key = `custom:${t.id}`;
    for (const id of assetsWithKey(key)) if (removeExternal(id, key)) n++;
  }
  return n;
}

/**
 * done видео-профиля. v1-copy: пока идёт подбор (сеть), видео-задания ассета (v1) не выдаются (hls.holdVideo) — задание
 * «звук» для копии успевает встать раньше v1. v1 при живой v1-copy: известные дорожки сразу (до hls.settleCopy в done)
 * ставятся на v1 с приоритетом 1 — копия держится, пока их нет в v1; ждущие задания «звук» копии снимаются.
 */
function onProfileReady({ hls_id: id, profile } = {}) {
  if (!config.HLS_ENABLED || !id || !profile) return null;
  if (profile === "v1-copy") {
    hls.holdVideo(id);
    return run(async () => {
      try {
        await refresh(id, { priority: PRIORITY, profiles: [profile] });
      } finally {
        hls.releaseVideo(id);
      }
    });
  }
  let priority = PRIORITY;
  const a = store.getAsset(id);
  if (profile === "v1" && a) {
    const copyLive = a.profiles?.["v1-copy"]?.status === "ready" && !(a.pending_delete || []).includes("v1-copy");
    if (copyLive) priority = WATCH_PRIORITY;
    ensure(id, Object.keys(extOf(a)), { priority, profiles: ["v1"] });
    const t = sec(Date.now());
    for (const j of audioJobsOf(id, "v1-copy")) if (!isLive(j, t)) store.deleteJob(j.job_id);
  }
  return run(() => refresh(id, { priority, profiles: [profile] }));
}

hls.events.on("profile-ready", onProfileReady);
ca.events.on("added", onAdded);
ca.events.on("offset", onOffset);
ca.events.on("removed", onRemoved);

function _reset() {
  watched.clear();
}

module.exports = {
  MAX_EXT,
  PRIORITY,
  WATCH_PRIORITY,
  deps,
  hlsLang,
  fileTrackName,
  customTrackName,
  pickTracks,
  filesOf,
  gather,
  allocate,
  enqueueAudio,
  ensure,
  refresh,
  removeExternal,
  audioDone,
  onWatch,
  onAdded,
  onOffset,
  onRemoved,
  onProfileReady,
  idle,
  _reset
};

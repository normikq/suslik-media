"use strict";

const fs = require("fs");
const crypto = require("crypto");
const axios = require("axios");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");

// ---- Заставки и титры из открытых баз (TheIntroDB, AniSkip) ---------------------------------------------------------
// Плеер сначала смотрит главы файла (shared/.../core/MarkersDetector.kt); если там нет заставки или титров — спрашивает
// сервер: GET /api/markers?title=&alt=&season=&episode=&duration= (без episode — фильм).
// 1. TheIntroDB (сериалы и фильмы): id TMDB по названию (тот же поиск, что у постеров, services/posters.js), затем
//    https://api.theintrodb.org/v4/media?tmdb_id=&season=&episode=&duration_ms= — интервалы в миллисекундах.
//    Ключ THEINTRODB_API_KEY необязателен (без него 5000 запросов в день).
// 2. AniSkip (аниме, только серии) — для того, чего нет в TheIntroDB: название ищется в MyAnimeList через Jikan (только
//    точное совпадение с одним из названий тайтла, иначе обычный сериал попадёт на случайное аниме), по id MAL и номеру
//    серии AniSkip отдаёт опенинг и эндинг в секундах.
// Ответы хранятся в markers-cache.json по хешу «название|сезон|серия»: найденное — насовсем, ненайденное — MISS_TTL_MS
// (базы пополняют пользователи).
const MISS_TTL_MS = 3 * 24 * 3600 * 1000;
const ERROR_TTL_MS = 30 * 60 * 1000; // сеть или база не ответили: не спрашиваем снова каждую серию
const MAX_ENTRIES = 20000;
const TIMEOUT_MS = 6000;
const JIKAN = "https://api.jikan.moe/v4/anime";
const ANISKIP = "https://api.aniskip.com/v2/skip-times";
const INTRODB = "https://api.theintrodb.org/v4/media";

const store = new Map(); // ключ -> { i: [s, e] | null, c: [s, e] | null, src, at } | { miss: true, at }
try {
  for (const [k, v] of Object.entries(JSON.parse(fs.readFileSync(config.MARKERS_FILE, "utf8")))) {
    if (/^[a-f0-9]{40}$/.test(k) && v && Number.isFinite(v.at)) store.set(k, v);
  }
} catch {}
const persist = makeJsonWriter(config.MARKERS_FILE, () => Object.fromEntries(store), 3000);
const inflight = new Map(); // ключ -> Promise: одна серия, открытая на двух устройствах, — один поход в базы

/** Название для сравнения: буквы и цифры любого алфавита, без регистра, «ё» = «е». */
function norm(s) {
  return String(s || "").toLowerCase().replace(/ё/g, "е").replace(/[^\p{L}\p{N}]+/gu, "");
}

function cacheKey(title, season, episode) {
  return crypto.createHash("sha1").update(episode ? `${norm(title)}|${season || 1}|${episode}` : `${norm(title)}|movie`).digest("hex");
}

/** Варианты названия сезона в MAL: «Title Season 2», «Title 2nd Season», «Title II», «Title 2», «Title Part 2». */
function seasonNames(title, season) {
  const base = norm(title);
  if (!season || season <= 1) return [base];
  const n = String(season);
  const ord = n + (season === 2 ? "nd" : season === 3 ? "rd" : "th");
  const roman = ["", "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x"][season] || "";
  return [base + "season" + n, base + n, base + ord + "season", base + "part" + n, roman && base + roman].filter(Boolean);
}

/** id MAL тайтла из ответа Jikan: только если одно из его названий точно совпало с [title] (с сезоном). */
function pickMalId(data, title, season) {
  const want = new Set(seasonNames(title, season));
  for (const a of Array.isArray(data) ? data : []) {
    const names = [a.title, a.title_english, a.title_japanese, ...(a.title_synonyms || []), ...(a.titles || []).map(t => t && t.title)];
    if (names.some(n => n && want.has(norm(n))) && Number.isInteger(a.mal_id)) return a.mal_id;
  }
  return null;
}

/** Интервалы опенинга и эндинга из ответа AniSkip (секунды → мс); дорожки вне длины серии отбрасываются. */
function intervalsFrom(body, durationSec) {
  const out = { i: null, c: null };
  if (!body || body.found !== true || !Array.isArray(body.results)) return out;
  const pick = types => {
    for (const t of types) {
      const r = body.results.find(x => x && x.skipType === t && x.interval);
      if (!r) continue;
      const s = Number(r.interval.startTime);
      const e = Number(r.interval.endTime);
      if (!Number.isFinite(s) || !Number.isFinite(e) || s < 0 || e <= s) continue;
      if (durationSec > 0 && s >= durationSec) continue;
      return [Math.round(s * 1000), Math.round(e * 1000)];
    }
    return null;
  };
  out.i = pick(["op", "mixed-op"]);
  out.c = pick(["ed", "mixed-ed"]);
  return out;
}

/**
 * Интервалы из ответа TheIntroDB (`intro` / `credits` — списки { start_ms, end_ms }; null в начале — с 0, в конце — до
 * конца видео; 0/0 — «точно нет»). Заставка — первая годная, титры — последние (после них бывает превью).
 */
function introDbIntervals(body, durationSec) {
  const out = { i: null, c: null };
  if (!body || typeof body !== "object") return out;
  const len = durationSec > 0 ? Math.round(durationSec * 1000) : 0;
  const ranges = (list, toEnd) => (Array.isArray(list) ? list : []).map(x => {
    if (!x) return null;
    const s = x.start_ms == null ? 0 : Number(x.start_ms);
    let e = x.end_ms == null ? null : Number(x.end_ms);
    if (!Number.isFinite(s) || s < 0) return null;
    if (e == null) { if (!toEnd || x.start_ms == null) return null; e = len || s + 1; }
    if (!Number.isFinite(e) || e <= s) return null;
    if (len && s >= len) return null;
    return [Math.round(s), Math.round(e)];
  }).filter(Boolean);
  out.i = ranges(body.intro, false)[0] || null;
  const credits = ranges(body.credits, true);
  out.c = credits.length ? credits[credits.length - 1] : null;
  return out;
}

async function lookupTmdb(title) {
  if (!config.TMDB_API_KEY) return null;
  return require("./posters").tmdbRef(title);
}

async function fetchIntroDb(ref, season, episode, durationSec) {
  const params = { tmdb_id: ref.id };
  if (episode) { params.season = season || 1; params.episode = episode; }
  if (durationSec > 0) params.duration_ms = Math.round(durationSec * 1000);
  const headers = config.THEINTRODB_API_KEY ? { Authorization: `Bearer ${config.THEINTRODB_API_KEY}` } : {};
  try {
    const r = await axios.get(INTRODB, { params, headers, timeout: TIMEOUT_MS });
    return introDbIntervals(r.data, durationSec);
  } catch (err) {
    if (err.response && err.response.status === 404) return { i: null, c: null };
    throw err;
  }
}

async function lookupMal(title, season) {
  const q = season > 1 ? `${title} season ${season}` : title;
  const r = await axios.get(JIKAN, { params: { q, limit: 10 }, timeout: TIMEOUT_MS });
  return pickMalId(r.data && r.data.data, title, season);
}

async function fetchAniSkip(malId, episode, durationSec) {
  try {
    const r = await axios.get(`${ANISKIP}/${malId}/${episode}`, {
      // episodeLength обязателен; 0 — без отбора по длине серии
      params: { "types[]": ["op", "ed", "mixed-op", "mixed-ed"], episodeLength: Math.round(durationSec) || 0 },
      paramsSerializer: { indexes: null },
      timeout: TIMEOUT_MS
    });
    return intervalsFrom(r.data, durationSec);
  } catch (err) {
    if (err.response && err.response.status === 404) return { i: null, c: null };
    throw err;
  }
}

function trim() {
  if (store.size <= MAX_ENTRIES) return;
  const old = [...store.entries()].sort((a, b) => a[1].at - b[1].at).slice(0, store.size - MAX_ENTRIES);
  for (const [k] of old) store.delete(k);
}

function toResponse(e, isMovie = false) {
  const range = r => (r ? { startMs: r[0], endMs: r[1] } : null);
  const credits = isMovie ? null : range(e && e.c);
  return { intro: range(e && e.i), credits, source: e && (e.i || (!isMovie && e.c)) ? e.src || "aniskip" : null };
}

/** Проверка параметров запроса; null — некорректный. */
function parseQuery(q) {
  const raw = [q.title, q.alt].map(t => String(t || "").trim().slice(0, 200)).filter(t => norm(t).length >= 2);
  const titles = [];
  for (const t of raw) {
    if (!titles.includes(t)) titles.push(t);
    // Разбиваем составные названия («Русское название / Original Title»)
    for (const part of t.split(/\s*[\/|]\s*|\s+[-–—]\s+/)) {
      const p = part.trim();
      if (norm(p).length >= 2 && !titles.includes(p)) titles.push(p);
    }
  }
  const season = q.season === undefined || q.season === "" ? 1 : Number(q.season);
  const episode = q.episode === undefined || q.episode === "" ? 0 : Number(q.episode); // 0 — фильм
  const duration = q.duration === undefined || q.duration === "" ? 0 : Number(q.duration);
  if (!titles.length || !Number.isInteger(season) || season < 0 || season > 100) return null;
  if (!Number.isInteger(episode) || episode < 0 || episode > 5000) return null;
  if (!Number.isFinite(duration) || duration < 0 || duration > 6 * 3600) return null;
  return { titles, season: season || 1, episode, duration };
}

/**
 * Заставка и титры серии: { intro: {startMs,endMs}|null, credits: …, source }.
 * [lookup] — для тестов: tmdb(title) => { id, media_type }, introdb(ref, season, episode, duration) => {i, c},
 * mal(title, season) => id MAL, skip(malId, episode, duration) => {i, c}.
 */
const LOOKUP = { tmdb: lookupTmdb, introdb: fetchIntroDb, mal: lookupMal, skip: fetchAniSkip };

/** Обе базы по очереди: TheIntroDB, затем AniSkip — для аниме, если TheIntroDB не дал интервалы. Ошибка одной не отменяет другую. */
async function lookupBoth(title, season, episode, duration, lookup) {
  const found = { i: null, c: null };
  const src = [];
  let failed = false;
  let isAnime = true;
  try {
    const ref = await lookup.tmdb(title);
    if (ref && ref.id && ref.media_type === (episode ? "tv" : "movie")) {
      const r = await lookup.introdb(ref, season, episode, duration);
      if (r.i || r.c) { found.i = r.i; found.c = r.c; src.push("theintrodb"); }
      // Западные игровые сериалы в базе аниме (MyAnimeList / AniSkip) не ищем — экономим 6-12 секунд и лимиты Jikan
      if (ref.original_language && ref.original_language !== "ja") {
        isAnime = false;
      }
    }
  } catch { failed = true; }
  // Для аниме (сериалы и фильмы): если в TheIntroDB нет заставки, опрашиваем AniSkip
  if (isAnime && (!found.i || (episode && !found.c))) {
    try {
      const mal = await lookup.mal(title, season);
      const ep = episode || 1; // для аниме-фильмов на AniSkip серия = 1
      const r = mal ? await lookup.skip(mal, ep, duration) : { i: null, c: null };
      if ((!found.i && r.i) || (episode && !found.c && r.c)) src.push("aniskip");
      found.i = found.i || r.i;
      if (episode) found.c = found.c || r.c;
    } catch { failed = true; }
  }
  // Для одиночных фильмов титры никогда не берутся — только заставка
  if (!episode) found.c = null;
  return { ...found, src: src.join("+"), failed };
}

async function markersFor({ titles, season, episode, duration }, now = Date.now(), lookup = LOOKUP) {
  lookup = { ...LOOKUP, ...lookup };
  const isMovie = !episode;
  for (const title of titles) {
    const key = cacheKey(title, season, episode);
    const hit = store.get(key);
    if (hit && !hit.miss) return toResponse(hit, isMovie);
    if (hit && hit.miss && now - hit.at < (hit.err ? ERROR_TTL_MS : MISS_TTL_MS)) continue;
    let job = inflight.get(key);
    if (!job) {
      job = (async () => {
        try {
          const found = await lookupBoth(title, season, episode, duration, lookup);
          const entry = found.i || found.c ? { i: found.i, c: found.c, src: found.src, at: now }
            : found.failed ? { miss: true, err: true, at: now } : { miss: true, at: now };
          store.set(key, entry);
          return entry;
        } finally {
          inflight.delete(key);
          trim();
          persist();
        }
      })();
      inflight.set(key, job);
    }
    const entry = await job;
    if (!entry.miss) return toResponse(entry, isMovie);
  }
  return toResponse(null, isMovie);
}

function _reset() { store.clear(); inflight.clear(); }

module.exports = { markersFor, parseQuery, pickMalId, intervalsFrom, introDbIntervals, seasonNames, cacheKey, norm, _reset };

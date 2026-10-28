"use strict";

const fs = require("fs");
const crypto = require("crypto");
const axios = require("axios");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");

// ---- Заставки и титры из открытых баз (AniSkip) ----------------------------------------------------------------
// Плеер сначала смотрит главы файла (shared/.../core/MarkersDetector.kt); если там ни заставки, ни титров — спрашивает
// сервер: GET /api/markers?title=&alt=&season=&episode=&duration=. Название ищется в MyAnimeList через Jikan (только точное
// совпадение с одним из названий тайтла, иначе обычный сериал попадёт на случайное аниме), по id MAL и номеру серии
// AniSkip отдаёт опенинг и эндинг в секундах. Ответы хранятся в markers-cache.json по хешу «название|сезон|серия»:
// найденное — насовсем, ненайденное — MISS_TTL_MS (базу пополняют пользователи AniSkip).
const MISS_TTL_MS = 3 * 24 * 3600 * 1000;
const ERROR_TTL_MS = 30 * 60 * 1000; // сеть или база не ответили: не спрашиваем снова каждую серию
const MAX_ENTRIES = 20000;
const TIMEOUT_MS = 6000;
const JIKAN = "https://api.jikan.moe/v4/anime";
const ANISKIP = "https://api.aniskip.com/v2/skip-times";

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
  return crypto.createHash("sha1").update(`${norm(title)}|${season || 1}|${episode}`).digest("hex");
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

function toResponse(e) {
  const range = r => (r ? { startMs: r[0], endMs: r[1] } : null);
  return { intro: range(e && e.i), credits: range(e && e.c), source: e && (e.i || e.c) ? e.src || "aniskip" : null };
}

/** Проверка параметров запроса; null — некорректный. */
function parseQuery(q) {
  const titles = [q.title, q.alt].map(t => String(t || "").trim().slice(0, 200)).filter(t => norm(t).length >= 2);
  const season = q.season === undefined || q.season === "" ? 1 : Number(q.season);
  const episode = Number(q.episode);
  const duration = q.duration === undefined || q.duration === "" ? 0 : Number(q.duration);
  if (!titles.length || !Number.isInteger(season) || season < 0 || season > 100) return null;
  if (!Number.isInteger(episode) || episode < 1 || episode > 5000) return null;
  if (!Number.isFinite(duration) || duration < 0 || duration > 6 * 3600) return null;
  return { titles: [...new Set(titles)], season: season || 1, episode, duration };
}

/**
 * Заставка и титры серии: { intro: {startMs,endMs}|null, credits: …, source }.
 * [lookup] — для тестов: (title, season) => id MAL, (malId, episode, duration) => {i, c}.
 */
async function markersFor({ titles, season, episode, duration }, now = Date.now(), lookup = { mal: lookupMal, skip: fetchAniSkip }) {
  for (const title of titles) {
    const key = cacheKey(title, season, episode);
    const hit = store.get(key);
    if (hit && !hit.miss) return toResponse(hit);
    if (hit && hit.miss && now - hit.at < (hit.err ? ERROR_TTL_MS : MISS_TTL_MS)) continue;
    let job = inflight.get(key);
    if (!job) {
      job = (async () => {
        try {
          const mal = await lookup.mal(title, season);
          const found = mal ? await lookup.skip(mal, episode, duration) : { i: null, c: null };
          const entry = found.i || found.c ? { i: found.i, c: found.c, src: "aniskip", mal, at: now } : { miss: true, at: now };
          store.set(key, entry);
          return entry;
        } catch {
          const entry = { miss: true, err: true, at: now };
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
    if (!entry.miss) return toResponse(entry);
  }
  return toResponse(null);
}

function _reset() { store.clear(); inflight.clear(); }

module.exports = { markersFor, parseQuery, pickMalId, intervalsFrom, seasonNames, cacheKey, norm, _reset };

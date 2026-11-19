"use strict";

// Сведения о фильме для страницы фильма (/watch/<id>, GET /api/title/:adId): слоган, страна, режиссёр, жанры, возраст,
// время, актёры, рейтинг TMDB. Фильм ищется тем же путём, что постер (services/posters.js): принятое название от ИИ
// «Original (год)» — результат TMDB того же года, иначе название раздачи без пометок релиза.
// Ответы TMDB запоминаются в DATA_DIR/tmdb-details.json: найденное — на 30 дней, «не нашлось» — на сутки.
// Ключ TMDB в журнал не пишется (в сообщениях об ошибках только код ответа).

const fs = require("fs");
const path = require("path");
const axios = require("axios");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");
const { posterQueryVariants } = require("../../poster-query");
const model = require("../../public/library-model");

const FILE = path.join(config.DATA_DIR, "tmdb-details.json");
const HIT_MS = 30 * 24 * 3600 * 1000;
const MISS_MS = 24 * 3600 * 1000;
const MAX_ENTRIES = 3000;
const IMG_FILE = /^[A-Za-z0-9_-]+\.(?:jpg|png|webp)$/;
const TMDB = "https://api.themoviedb.org/3";

const cache = new Map(); // ключ запроса -> { at, body }
const inflight = new Map();

function load() {
  try {
    const raw = JSON.parse(fs.readFileSync(FILE, "utf8"));
    for (const [k, v] of Object.entries(raw || {})) if (v && typeof v.at === "number" && v.body) cache.set(k, v);
  } catch {
    /* файла ещё нет */
  }
}
load();
const persist = makeJsonWriter(FILE, () => Object.fromEntries(cache));

// GET к TMDB: подменяется в тестах (_setHttp). Возвращает data или бросает ошибку.
let httpGet = async (url, params) => {
  const r = await axios.get(url, { params: { api_key: config.TMDB_API_KEY, ...params }, timeout: 6000, validateStatus: () => true });
  if (r.status < 200 || r.status >= 300) {
    const err = new Error(`TMDB ответил ${r.status}`);
    err.status = r.status;
    throw err;
  }
  return r.data;
};

/** Что искать для раздачи: { query, year, kind: "movie"|"tv"|null }. Как aiPosterQuery / displayTitle на сайте. */
function lookupOf(m) {
  const ai = m && m.aiTitle;
  const accepted = ai && ai.status === "accepted";
  let kind = null;
  if (accepted && ai.kind === "series") kind = "tv";
  else if (accepted && (ai.kind === "movie" || ai.kind === "collection")) kind = "movie";
  if (accepted && ai.original) return { query: String(ai.original).trim(), year: Number(ai.year) || null, kind };
  const base = accepted && ai.base ? String(ai.base) : model.escapeTitle((m && m.name) || "");
  const year = accepted && ai.year ? Number(ai.year) : model.releaseYear(m || {});
  if (!kind) {
    try {
      const pl = model.buildPlaylist(m);
      if (pl.type === "series" && !model.isCollection(pl, m)) kind = "tv";
    } catch {
      /* без файлов — без подсказки */
    }
  }
  return { query: base.trim(), year: year || null, kind };
}

const yearOf = item => Number(String(item.release_date || item.first_air_date || "").slice(0, 4)) || 0;

async function findMatch({ query, year, kind }) {
  let fallback = null;
  for (const q of posterQueryVariants(query)) {
    const data = await httpGet(`${TMDB}/search/multi`, { query: q, include_adult: false, language: "ru-RU" });
    const results = Array.isArray(data && data.results) ? data.results : [];
    let found = results.filter(x => x && x.id && (x.media_type === "movie" || x.media_type === "tv"));
    if (kind) {
      const same = found.filter(x => x.media_type === kind);
      if (same.length) found = same;
    }
    const match = year
      ? found.find(x => yearOf(x) === year) || found.find(x => Math.abs(yearOf(x) - year) <= 1) || null
      : found[0] || null;
    if (match) return match;
    if (!fallback) fallback = found[0] || null;
  }
  return fallback;
}

let regionNames = null;
// Как пишут на сайтах о кино, а не официальные названия ICU («Соединенные Штаты», «Республика Корея»)
const SHORT_COUNTRY = { US: "США", KR: "Южная Корея", KP: "КНДР", AE: "ОАЭ", SU: "СССР" };
function countryName(code) {
  if (SHORT_COUNTRY[code]) return SHORT_COUNTRY[code];
  try {
    if (!regionNames) regionNames = new Intl.DisplayNames(["ru"], { type: "region" });
    return regionNames.of(code) || code;
  } catch {
    return code;
  }
}

const text = v => (typeof v === "string" ? v.trim() : "");
const names = (list, max) =>
  (Array.isArray(list) ? list : []).map(x => text(x && x.name)).filter(Boolean).filter((v, i, a) => a.indexOf(v) === i).slice(0, max);

/** Возраст: сертификат России, иначе США («16+», «18+», «PG-13», «TV-MA»). */
function ageOf(details, type) {
  const pick = code => {
    if (type === "movie") {
      const r = ((details.release_dates && details.release_dates.results) || []).find(x => x && x.iso_3166_1 === code);
      const c = r && (r.release_dates || []).map(d => text(d && d.certification)).find(Boolean);
      return c || "";
    }
    const r = ((details.content_ratings && details.content_ratings.results) || []).find(x => x && x.iso_3166_1 === code);
    return text(r && r.rating);
  };
  const ru = pick("RU");
  if (ru) return /^\d+$/.test(ru) ? ru + "+" : ru;
  return pick("US") || null;
}

/** Ответ TMDB (подробности с credits и сертификатами) → поля страницы фильма. */
function parseDetails(d, type) {
  const tv = type === "tv";
  const credits = d.credits || {};
  const directors = tv
    ? names(d.created_by, 4)
    : names((credits.crew || []).filter(c => c && c.job === "Director"), 4);
  const cast = (Array.isArray(credits.cast) ? credits.cast.slice() : [])
    .sort((a, b) => (Number(a && a.order) || 0) - (Number(b && b.order) || 0));
  const castNames = names(cast, 10);
  const codes = tv
    ? (Array.isArray(d.origin_country) && d.origin_country.length ? d.origin_country : (d.production_countries || []).map(c => c && c.iso_3166_1))
    : (d.production_countries || []).map(c => c && c.iso_3166_1);
  const countries = [...new Set((codes || []).filter(c => typeof c === "string" && /^[A-Z]{2}$/.test(c)))].slice(0, 5).map(countryName);
  let runtime = 0;
  if (tv) {
    const runs = (Array.isArray(d.episode_run_time) ? d.episode_run_time : []).map(Number).filter(n => n > 0);
    runtime = runs.length ? runs.reduce((a, b) => a + b, 0) / runs.length : Number(d.last_episode_to_air && d.last_episode_to_air.runtime) || 0;
  } else runtime = Number(d.runtime) || 0;
  const release = text(tv ? d.first_air_date : d.release_date);
  const posterFile = text(d.poster_path).replace(/^\//, "");
  return {
    found: true,
    tmdb_id: Number(d.id) || null,
    type: tv ? "tv" : "movie",
    title: text(tv ? d.name : d.title) || null,
    original_title: text(tv ? d.original_name : d.original_title) || null,
    tagline: text(d.tagline) || null,
    overview: text(d.overview).slice(0, 2000) || null,
    release_date: /^\d{4}-\d{2}-\d{2}$/.test(release) ? release : null,
    year: /^\d{4}/.test(release) ? Number(release.slice(0, 4)) : null,
    countries,
    directors,
    genres: names(d.genres, 6).map(g => g.toLowerCase()),
    runtime_min: runtime > 0 && runtime < 1000 ? Math.round(runtime) : null,
    age: ageOf(d, tv ? "tv" : "movie"),
    rating: Number(d.vote_average) > 0 ? Math.round(Number(d.vote_average) * 10) / 10 : null,
    votes: Number(d.vote_count) > 0 ? Math.round(Number(d.vote_count)) : null,
    cast: castNames,
    cast_more: cast.length > castNames.length,
    seasons: tv && Number(d.number_of_seasons) > 0 ? Number(d.number_of_seasons) : null,
    poster: IMG_FILE.test(posterFile) ? `/api/poster/img/w500/${posterFile}` : null
  };
}

function keyOf(l) {
  return `${l.kind || "any"}|${l.year || ""}|${l.query.toLowerCase()}`;
}

function remember(key, body) {
  cache.delete(key);
  cache.set(key, { at: Date.now(), body });
  while (cache.size > MAX_ENTRIES) cache.delete(cache.keys().next().value);
  persist();
}

/**
 * Сведения о раздаче m (объект медиатеки: name, files, aiTitle). Без ключа TMDB или не нашлось — { found: false }.
 * Сбой сети не запоминается (бросает ошибку со status 502).
 */
async function detailsFor(m) {
  if (!config.TMDB_API_KEY) return { found: false, reason: "no-key" };
  const l = lookupOf(m);
  if (!l.query) return { found: false };
  const key = keyOf(l);
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < (hit.body.found ? HIT_MS : MISS_MS)) return hit.body;
  if (inflight.has(key)) return inflight.get(key);
  const job = (async () => {
    try {
      const match = await findMatch(l);
      if (!match) {
        const body = { found: false };
        remember(key, body);
        return body;
      }
      const type = match.media_type;
      const extra = type === "tv" ? "credits,content_ratings" : "credits,release_dates";
      const d = await httpGet(`${TMDB}/${type}/${match.id}`, { language: "ru-RU", append_to_response: extra });
      const body = parseDetails(d || {}, type);
      remember(key, body);
      return body;
    } catch (err) {
      const e = new Error("Не удалось получить сведения о фильме.");
      e.status = 502;
      e.cause = err && err.status ? err.status : undefined;
      throw e;
    } finally {
      inflight.delete(key);
    }
  })();
  inflight.set(key, job);
  return job;
}

module.exports = {
  detailsFor,
  lookupOf,
  parseDetails,
  ageOf,
  _setHttp(fn) {
    httpGet = fn;
  },
  _reset() {
    cache.clear();
    inflight.clear();
  },
  _file: FILE
};

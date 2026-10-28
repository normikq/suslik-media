"use strict";

const fs = require("fs");
const path = require("path");
const axios = require("axios");
const config = require("../config");
const { posterQueryVariants } = require("../../poster-query");

// Ответы TMDB на поиск постера кэшируются в памяти на сутки (до 3000 названий): одно и то же название не ищется повторно.
const posterLookupCache = new Map(); // query -> { at, body }
const POSTER_SIZES = new Set(["w185", "w342", "w500", "w1280"]);
const posterInflight = new Map();

const IMG_FILE = /^[A-Za-z0-9_-]+\.(?:jpg|png|webp)$/;

// Название по-русски из переводов TMDB (сначала ru-RU, потом другие ru); у русского фильма без перевода — оригинал.
// Перевода нет — пустая строка. [data] — ответ /{type}/{id}/translations, [match] — результат поиска.
function russianTitle(data, match) {
  const list = Array.isArray(data?.translations) ? data.translations : [];
  const text = v => (typeof v === "string" ? v.trim() : "");
  const ru = list.filter(t => t && t.iso_639_1 === "ru").sort((a, b) => (b.iso_3166_1 === "RU") - (a.iso_3166_1 === "RU"));
  for (const t of ru) {
    const name = text(t.data?.title) || text(t.data?.name);
    if (name) return name.slice(0, 200);
  }
  return match?.original_language === "ru" ? text(match.original_title || match.original_name).slice(0, 200) : "";
}

// Для баннера: лучший широкий кадр БЕЗ текста на нём (iso_639_1 = null), логотип названия (русский, иначе английский),
// описание по-русски. Любая ошибка — баннер остаётся с тем, что нашёл поиск.
async function addBannerExtras(body, match) {
  const base = `https://api.themoviedb.org/3/${match.media_type}/${match.id}`;
  const [images, details] = await Promise.all([
    axios.get(`${base}/images`, { params: { api_key: config.TMDB_API_KEY, include_image_language: "ru,en,null" }, timeout: 5000 }).then(r => r.data).catch(() => null),
    axios.get(base, { params: { api_key: config.TMDB_API_KEY, language: "ru-RU" }, timeout: 5000 }).then(r => r.data).catch(() => null)
  ]);
  const name = f => (f && f.file_path ? String(f.file_path).replace(/^\//, "") : "");
  const score = f => (Number(f.vote_average) || 0) * 10 + Math.min(Number(f.vote_count) || 0, 20) / 20;
  const wide = (images?.backdrops || []).filter(f => !f.iso_639_1 && f.width >= 1280 && IMG_FILE.test(name(f))).sort((x, y) => score(y) - score(x));
  if (wide.length) body.backdrop = `/api/poster/img/w1280/${name(wide[0])}`;
  const logos = (images?.logos || []).filter(f => IMG_FILE.test(name(f)) && !name(f).endsWith(".svg"));
  const logo = logos.filter(f => f.iso_639_1 === "ru").sort((x, y) => score(y) - score(x))[0]
    || logos.filter(f => f.iso_639_1 === "en").sort((x, y) => score(y) - score(x))[0];
  if (logo) body.logo = `/api/poster/img/w500/${name(logo)}`;
  const ov = typeof details?.overview === "string" ? details.overview.trim() : "";
  if (ov) body.overview = ov.slice(0, 600);
}

// Сезон, который смотрят (&season=N): год выхода, описание и рейтинг этого сезона вместо сведений о первом.
// У сезона нет своего описания или рейтинга (TMDB часто пуст по-русски) — остаются сведения сериала.
function fetchSeason(match, season) {
  const url = `https://api.themoviedb.org/3/tv/${match.id}/season/${season}`;
  return axios.get(url, { params: { api_key: config.TMDB_API_KEY, language: "ru-RU" }, timeout: 5000 }).then(r => r.data).catch(() => null);
}

function applySeason(body, data, season) {
  if (!data) return;
  const year = String(data.air_date || "").slice(0, 4);
  if (/^\d{4}$/.test(year)) body.year = year;
  const ov = typeof data.overview === "string" ? data.overview.trim() : "";
  if (ov) body.overview = ov.slice(0, 600);
  if (Number(data.vote_average) > 0) body.rating = Math.round(Number(data.vote_average) * 10) / 10;
  body.season = season;
}

// Дополнения баннера (кадр, логотип, описание по-русски — ещё 2 запроса к TMDB, с сезоном — 3) нужны только
// «Продолжить просмотр»: их просят с ?hero=1 (до 4 названий), обычные плитки медиатеки получают один поиск.
const heroCache = new Map(); // query|season -> { at, body }

async function searchPoster(rawQuery, opts = {}) {
  const base = await searchBase(rawQuery);
  if (!opts.hero || !base.ref) return base.body;
  const season = base.ref.media_type === "tv" && Number.isInteger(opts.season) && opts.season > 0 && opts.season < 100 ? opts.season : 0;
  const key = season ? `${base.ck}|s${season}` : base.ck;
  const hit = heroCache.get(key);
  if (hit && Date.now() - hit.at < 24 * 3600 * 1000) return hit.body;
  const body = { ...base.body };
  const [, seasonData] = await Promise.all([addBannerExtras(body, base.ref), season ? fetchSeason(base.ref, season) : null]);
  applySeason(body, seasonData, season);
  heroCache.set(key, { at: Date.now(), body });
  if (heroCache.size > 300) heroCache.delete(heroCache.keys().next().value);
  return body;
}

async function searchBase(rawQuery) {
  const query = typeof rawQuery === "string" ? rawQuery.trim().slice(0, 160) : "";
  if (!query) {
    const err = new Error("Не указано название.");
    err.status = 400;
    throw err;
  }
  if (!config.TMDB_API_KEY) {
    const err = new Error("На сервере не задан TMDB_API_KEY.");
    err.status = 503;
    throw err;
  }

  const ck = query.toLowerCase();
  const hit = posterLookupCache.get(ck);
  if (hit && Date.now() - hit.at < 24 * 3600 * 1000) {
    return { ck, body: hit.body, ref: hit.ref };
  }

  // Название из библиотеки — имя раздачи: «Severance Season 1», «Show - S01-10», «Фильм TrueHD Atmos», «Erai-raws Show - 01 ~ 10».
  // Если по самому названию постера нет, пробуем очищенное и без дефисов.
  // «Dune (2021)» — название и год (так ищут по названию от ИИ, services/aititle.js): TMDB ищет год как часть
  // названия, поэтому ищем без него и берём результат того же года (±1: премьера на фестивале, сериал под Новый год).
  // Без совпадения по году — первый найденный, как раньше.
  let match = null;
  let failed = 0;
  const ym = /^(.*\S)\s*\((\d{4})\)$/.exec(query);
  const year = ym ? Number(ym[2]) : null;
  const variants = posterQueryVariants(ym ? ym[1] : query);
  const yearOf = item => Number(String(item.release_date || item.first_air_date || "").slice(0, 4)) || 0;
  let fallback = null;

  for (const q of variants) {
    try {
      const response = await axios.get("https://api.themoviedb.org/3/search/multi", {
        params: { api_key: config.TMDB_API_KEY, query: q, include_adult: false },
        timeout: 5000
      });
      const results = Array.isArray(response.data?.results) ? response.data.results : [];
      const found = results.filter(item => item.poster_path && (item.media_type === "movie" || item.media_type === "tv"));
      if (!year) match = found[0] || null;
      else {
        match = found.find(item => yearOf(item) === year) || found.find(item => Math.abs(yearOf(item) - year) <= 1) || null;
        if (!fallback) fallback = found[0] || null;
      }
    } catch {
      failed++;
    }
    if (match) break;
  }
  if (!match) match = fallback;

  // Название по-русски: поиск идёт без языка (с ru-RU TMDB отдал бы и другие постеры, а клиенты 30 дней помнят путь
  // постера и по нему сливают версии), поэтому переводы — отдельным запросом. Сбой — ответ не запоминается.
  let ruTitle = "";
  let trFailed = false;
  if (match) {
    const tr = await axios.get(`https://api.themoviedb.org/3/${match.media_type}/${match.id}/translations`, {
      params: { api_key: config.TMDB_API_KEY }, timeout: 5000
    }).then(r => r.data).catch(() => null);
    trFailed = !tr;
    ruTitle = russianTitle(tr, match);
  }

  const file = match ? String(match.poster_path).replace(/^\//, "") : "";
  const backdropFile = match && match.backdrop_path ? String(match.backdrop_path).replace(/^\//, "") : "";
  const body = {
    poster: /^[A-Za-z0-9_-]+\.(?:jpg|png|webp)$/.test(file) ? `/api/poster/img/w500/${file}` : null,
    poster_url: /^[A-Za-z0-9_-]+\.(?:jpg|png|webp)$/.test(file) ? `https://image.tmdb.org/t/p/w500/${file}` : null,
    title: ruTitle || match?.title || match?.name || null,
    // только русское название (или null): подпись плитки, когда пользователь не принял название от ИИ
    title_ru: ruTitle || null,
    type: match ? match.media_type : null,
    animation: !!match && Array.isArray(match.genre_ids) && match.genre_ids.includes(16), // 16 = Animation в TMDB
    // Для баннера «Продолжить просмотр» на ПК: широкая картинка, описание, год и рейтинг (у старых клиентов поля игнорируются)
    backdrop: /^[A-Za-z0-9_-]+\.(?:jpg|png|webp)$/.test(backdropFile) ? `/api/poster/img/w1280/${backdropFile}` : null,
    overview: match && typeof match.overview === "string" && match.overview.trim() ? match.overview.trim().slice(0, 600) : null,
    year: match ? String(match.release_date || match.first_air_date || "").slice(0, 4) || null : null,
    rating: match && Number(match.vote_average) > 0 ? Math.round(Number(match.vote_average) * 10) / 10 : null
  };

  const ref = match && match.id ? { id: match.id, media_type: match.media_type } : null;

  // «Постера нет» после сбоя сети по части вариантов — не окончательный ответ: не запоминаем, клиент спросит снова.
  if (!match && failed) {
    const err = new Error("Не удалось найти постер.");
    err.status = 502;
    throw err;
  }

  if (!trFailed) posterLookupCache.set(ck, { at: Date.now(), body, ref });
  if (posterLookupCache.size > 3000) {
    posterLookupCache.delete(posterLookupCache.keys().next().value);
  }

  return { ck, body, ref };
}

function getPosterImagePath(size, file) {
  return path.join(config.POSTER_DIR, `${size}_${file}`);
}

async function fetchPosterImage(size, file) {
  if (!POSTER_SIZES.has(size) || !/^[A-Za-z0-9_-]+\.(?:jpg|png|webp)$/.test(file)) {
    const err = new Error("Invalid parameters");
    err.status = 400;
    throw err;
  }

  const cached = getPosterImagePath(size, file);
  if (fs.existsSync(cached)) return cached;

  const key = `${size}/${file}`;
  if (!posterInflight.has(key)) {
    posterInflight.set(
      key,
      (async () => {
        const r = await axios.get(`https://image.tmdb.org/t/p/${size}/${file}`, {
          responseType: "arraybuffer",
          timeout: 15000,
          maxContentLength: 5 * 1024 * 1024,
          validateStatus: () => true
        });
        if (r.status !== 200 || !String(r.headers["content-type"] || "").startsWith("image/")) {
          throw new Error("HTTP " + r.status);
        }
        const tmp = cached + "." + process.pid + ".tmp";
        fs.writeFileSync(tmp, r.data);
        fs.renameSync(tmp, cached);
      })().finally(() => posterInflight.delete(key))
    );
  }

  await posterInflight.get(key);
  return cached;
}

/** Фильм или сериал TMDB по названию («Dune (2021)» — с годом): { id, media_type } или null (тот же поиск и кэш, что у постеров). */
async function tmdbRef(query) {
  return (await searchBase(query)).ref || null;
}

module.exports = {
  POSTER_SIZES,
  searchPoster,
  tmdbRef,
  getPosterImagePath,
  fetchPosterImage
};

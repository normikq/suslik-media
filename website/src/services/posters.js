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

// Дополнения баннера (кадр, логотип, описание по-русски — ещё 2 запроса к TMDB) нужны только «Продолжить просмотр»:
// их просят с ?hero=1 (до 4 названий), обычные плитки медиатеки получают один поиск.
const heroCache = new Map(); // query -> { at, body }

async function searchPoster(rawQuery, opts = {}) {
  const base = await searchBase(rawQuery);
  if (!opts.hero || !base.ref) return base.body;
  const hit = heroCache.get(base.ck);
  if (hit && Date.now() - hit.at < 24 * 3600 * 1000) return hit.body;
  const body = { ...base.body };
  await addBannerExtras(body, base.ref);
  heroCache.set(base.ck, { at: Date.now(), body });
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
  let match = null;
  let failed = 0;
  const variants = posterQueryVariants(query);

  for (const q of variants) {
    try {
      const response = await axios.get("https://api.themoviedb.org/3/search/multi", {
        params: { api_key: config.TMDB_API_KEY, query: q, include_adult: false },
        timeout: 5000
      });
      const results = Array.isArray(response.data?.results) ? response.data.results : [];
      match = results.find(item => item.poster_path && (item.media_type === "movie" || item.media_type === "tv"));
    } catch {
      failed++;
    }
    if (match) break;
  }

  const file = match ? String(match.poster_path).replace(/^\//, "") : "";
  const backdropFile = match && match.backdrop_path ? String(match.backdrop_path).replace(/^\//, "") : "";
  const body = {
    poster: /^[A-Za-z0-9_-]+\.(?:jpg|png|webp)$/.test(file) ? `/api/poster/img/w500/${file}` : null,
    poster_url: /^[A-Za-z0-9_-]+\.(?:jpg|png|webp)$/.test(file) ? `https://image.tmdb.org/t/p/w500/${file}` : null,
    title: match?.title || match?.name || null,
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

  posterLookupCache.set(ck, { at: Date.now(), body, ref });
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

module.exports = {
  POSTER_SIZES,
  searchPoster,
  getPosterImagePath,
  fetchPosterImage
};

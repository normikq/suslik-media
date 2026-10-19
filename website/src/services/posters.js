"use strict";

const fs = require("fs");
const path = require("path");
const axios = require("axios");
const config = require("../config");
const { posterQueryVariants } = require("../../poster-query");

// Ответы TMDB на поиск постера кэшируются в памяти на сутки (до 3000 названий): одно и то же название не ищется повторно.
const posterLookupCache = new Map(); // query -> { at, body }
const POSTER_SIZES = new Set(["w185", "w342", "w500"]);
const posterInflight = new Map();

async function searchPoster(rawQuery) {
  const query = typeof rawQuery === "string" ? rawQuery.trim().slice(0, 160) : "";
  if (!query) {
    const err = new Error("Не указано название.");
    err.status = 400;
    throw err;
  }
  if (!config.TMDB_API_KEY) {
    const err = new Error("TMDB_API_KEY is not configured.");
    err.status = 503;
    throw err;
  }

  const ck = query.toLowerCase();
  const hit = posterLookupCache.get(ck);
  if (hit && Date.now() - hit.at < 24 * 3600 * 1000) {
    return hit.body;
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

  if (!match && failed === variants.length) {
    const err = new Error("Не удалось найти постер.");
    err.status = 502;
    throw err;
  }

  const file = match ? String(match.poster_path).replace(/^\//, "") : "";
  const body = {
    poster: /^[A-Za-z0-9_-]+\.(?:jpg|png|webp)$/.test(file) ? `/api/poster/img/w500/${file}` : null,
    title: match?.title || match?.name || null,
    type: match ? match.media_type : null,
    animation: !!match && Array.isArray(match.genre_ids) && match.genre_ids.includes(16) // 16 = Animation в TMDB
  };

  posterLookupCache.set(ck, { at: Date.now(), body });
  if (posterLookupCache.size > 3000) {
    posterLookupCache.delete(posterLookupCache.keys().next().value);
  }

  return body;
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

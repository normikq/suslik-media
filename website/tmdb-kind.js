"use strict";
// Тип раздачи по TMDB: фильм / сериал / мультфильм (жанр Animation, id 16). Ключ берётся из TMDB_API_KEY;
// без ключа или при сбое TMDB остаётся определение по названию (classify.js).


const TTL = 7 * 24 * 3600 * 1000;
const cache = new Map(); // запрос -> { at, kind }
const MAX_LOOKUPS = 60, CONCURRENCY = 6;

// «ВАЛЛ·И / WALL·E (2008) BDRip 1080p» -> { query: "WALL·E", year: "2008" }
function cleanTitle(title) {
  const raw = String(title || "");
  const year = (/[\(\[]\s*((?:19|20)\d{2})/.exec(raw) || /\b((?:19|20)\d{2})\b/.exec(raw) || [])[1] || "";
  let t = raw.split(/[(\[]/)[0];
  if (!t.trim()) t = raw.replace(/[\[(][^\])]*[\])]/, " ");
  t = t.replace(/\b(?:BDRip|BDRemux|REMUX|WEB-?DL|WEB-?Rip|HDRip|DVDRip|BluRay|1080p|720p|2160p|4K|UHD|HDR)\b.*$/i, "");
  const parts = t.split(/\s+\/\s+|\s+\|\s+/).map(s => s.trim()).filter(Boolean);
  const latin = parts.find(p => /[A-Za-z]{3}/.test(p) && !/[А-Яа-яЁё]/.test(p));
  const query = (latin || parts[0] || "").replace(/\s+(?:19|20)\d{2}\s*$/, "").replace(/\s+/g, " ").trim().slice(0, 120);
  return { query, year };
}

async function lookup(apiKey, title) {
  const axios = require("axios"); // лениво: модульные тесты cleanTitle/refineKinds идут без зависимостей
  const { query, year } = cleanTitle(title);
  if (query.length < 2) return null;
  const ck = `${query.toLowerCase()}|${year}`;
  const hit = cache.get(ck);
  if (hit && Date.now() - hit.at < TTL) return hit.kind;
  try {
    const r = await axios.get("https://api.themoviedb.org/3/search/multi", {
      params: { api_key: apiKey, query, include_adult: false, language: "ru-RU" }, timeout: 4000
    });
    const list = (Array.isArray(r.data?.results) ? r.data.results : []).filter(x => x.media_type === "movie" || x.media_type === "tv");
    const byYear = year && list.find(x => String(x.release_date || x.first_air_date || "").startsWith(year));
    const m = byYear || list[0];
    let kind = null;
    if (m) kind = m.media_type === "tv" ? "series" : (Array.isArray(m.genre_ids) && m.genre_ids.includes(16) ? "cartoon" : "movie");
    cache.set(ck, { at: Date.now(), kind });
    if (cache.size > 5000) cache.delete(cache.keys().next().value);
    return kind;
  } catch { return null; }
}

const BUDGET_MS = 2500; // поиск не ждёт TMDB дольше: что не успело, остаётся с типом по названию (ответ попадёт в кэш для следующего раза)

const keyOf = c => `${c.query.toLowerCase()}|${c.year}`;

// Уточняет `kind` у результатов (кроме «other» и «series»: тег сезона/серии в названии надёжнее первого совпадения TMDB), правит массив на месте.
async function refineKinds(results, apiKey = process.env.TMDB_API_KEY, budgetMs = BUDGET_MS) {
  if (!apiKey) return;
  const todo = results.filter(r => r.kind !== "other" && r.kind !== "series").map(r => ({ r, c: cleanTitle(r.title) })).filter(x => x.c.query);
  const titles = new Map(); // ключ запроса -> название
  for (const { r, c } of todo) if (titles.size < MAX_LOOKUPS) titles.set(keyOf(c), r.title);
  const kinds = new Map();
  const queue = [...titles];
  const workers = Array.from({ length: Math.min(CONCURRENCY, queue.length) }, async () => {
    for (let e; (e = queue.shift());) kinds.set(e[0], await lookup(apiKey, e[1]));
  });
  let timer;
  await Promise.race([Promise.all(workers), new Promise(res => { timer = setTimeout(res, budgetMs); })]);
  clearTimeout(timer);
  queue.length = 0; // опоздавшие запросы больше не стартуют
  for (const { r, c } of todo) {
    const kind = kinds.get(keyOf(c));
    if (!kind) continue;
    if (kind === "series" && r.kind === "movie") continue; // «фильм» в названии надёжнее случайного сериала с тем же именем
    r.kind = kind;
  }
}
module.exports = { refineKinds, cleanTitle };

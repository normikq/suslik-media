"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-posters-ru-"));
process.env.TMDB_API_KEY = "test-tmdb-key";

const test = require("node:test");
const assert = require("node:assert/strict");
const axios = require("axios");

// Название по-русски из переводов TMDB: title_ru — подпись плитки, title — ещё и запасное английское (баннер)
const SEARCH = {
  "dune": { id: 1, media_type: "movie", title: "Dune", original_title: "Dune", original_language: "en", poster_path: "/d.jpg" },
  "frieren": { id: 2, media_type: "tv", name: "Frieren: Beyond Journey's End", original_name: "葬送のフリーレン", original_language: "ja", poster_path: "/f.jpg" },
  "brat": { id: 3, media_type: "movie", title: "Brother", original_title: "Брат", original_language: "ru", poster_path: "/b.jpg" },
  "broken": { id: 4, media_type: "movie", title: "Broken", original_language: "en", poster_path: "/x.jpg" }
};
const TRANSLATIONS = {
  "/movie/1": [
    { iso_639_1: "ru", iso_3166_1: "UA", data: { title: "Дюна (UA)" } },
    { iso_639_1: "ru", iso_3166_1: "RU", data: { title: "Дюна", overview: "" } },
    { iso_639_1: "en", iso_3166_1: "US", data: { title: "Dune" } }],
  "/tv/2": [
    { iso_639_1: "ru", iso_3166_1: "RU", data: { name: "" } },
    { iso_639_1: "en", iso_3166_1: "US", data: { name: "Frieren: Beyond Journey's End" } }],
  "/movie/3": []
};
let brokenCalls = 0;

axios.get = async (url, { params }) => {
  if (url.endsWith("/search/multi")) {
    assert.equal(params.language, undefined); // постеры не меняются: поиск без языка
    return { data: { results: [SEARCH[params.query.toLowerCase()]].filter(Boolean) } };
  }
  if (url.endsWith("/images")) return { data: { backdrops: [], logos: [] } };
  const m = /\/3(\/\w+\/\d+)\/translations$/.exec(url);
  if (m && TRANSLATIONS[m[1]]) return { data: { id: 1, translations: TRANSLATIONS[m[1]] } };
  if (m && m[1] === "/movie/4") brokenCalls++;
  if (/\/3\/\w+\/\d+$/.test(url)) return { data: {} };
  throw new Error("HTTP 500");
};

const { searchPoster } = require("../src/services/posters");

test("posters: русское название из перевода ru-RU", async () => {
  const b = await searchPoster("Dune");
  assert.equal(b.title_ru, "Дюна");
  assert.equal(b.title, "Дюна");
  assert.equal((await searchPoster("Dune", { hero: true })).title, "Дюна");
});

test("posters: перевода нет — title_ru пуст, в баннере название из поиска, а не японский оригинал", async () => {
  const b = await searchPoster("Frieren", { hero: true });
  assert.equal(b.title_ru, null);
  assert.equal(b.title, "Frieren: Beyond Journey's End");
});

test("posters: русский фильм без перевода — оригинальное название", async () => {
  assert.equal((await searchPoster("Brat")).title_ru, "Брат");
});

test("posters: переводы не пришли — название из поиска, ответ не запоминается", async () => {
  const b = await searchPoster("Broken");
  assert.equal(b.title, "Broken");
  assert.equal(b.title_ru, null);
  await searchPoster("Broken");
  assert.equal(brokenCalls, 2);
});

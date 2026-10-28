"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-posters-ru-"));
process.env.TMDB_API_KEY = "test-tmdb-key";

const test = require("node:test");
const assert = require("node:assert/strict");
const axios = require("axios");

// Название баннера «Продолжить просмотр» — по-русски из переводов TMDB; без перевода — из поиска (английское)
const SEARCH = {
  "dune": { id: 1, media_type: "movie", title: "Dune", original_title: "Dune", poster_path: "/d.jpg" },
  "frieren": { id: 2, media_type: "tv", name: "Frieren: Beyond Journey's End", original_name: "葬送のフリーレン", poster_path: "/f.jpg" },
  "brat": { id: 3, media_type: "movie", title: "Brother", original_title: "Брат", poster_path: "/b.jpg" },
  "broken": { id: 4, media_type: "movie", title: "Broken", poster_path: "/x.jpg" }
};
const DETAILS = {
  "/movie/1": { original_language: "en", original_title: "Dune", translations: { translations: [
    { iso_639_1: "ru", iso_3166_1: "UA", data: { title: "Дюна (UA)" } },
    { iso_639_1: "ru", iso_3166_1: "RU", data: { title: "Дюна", overview: "" } },
    { iso_639_1: "en", iso_3166_1: "US", data: { title: "Dune" } }] } },
  "/tv/2": { original_language: "ja", original_name: "葬送のフリーレン", translations: { translations: [
    { iso_639_1: "ru", iso_3166_1: "RU", data: { name: "" } },
    { iso_639_1: "en", iso_3166_1: "US", data: { name: "Frieren: Beyond Journey's End" } }] } },
  "/movie/3": { original_language: "ru", original_title: "Брат", translations: { translations: [] } }
};

axios.get = async (url, { params }) => {
  if (url.endsWith("/search/multi")) return { data: { results: [SEARCH[params.query.toLowerCase()]].filter(Boolean) } };
  if (url.endsWith("/images")) return { data: { backdrops: [], logos: [] } };
  const key = url.replace("https://api.themoviedb.org/3", "");
  if (DETAILS[key]) {
    assert.equal(params.language, "ru-RU");
    assert.equal(params.append_to_response, "translations");
    return { data: DETAILS[key] };
  }
  throw new Error("HTTP 500");
};

const { searchPoster } = require("../src/services/posters");

test("posters: русское название из перевода ru-RU", async () => {
  assert.equal((await searchPoster("Dune", { hero: true })).title, "Дюна");
});

test("posters: перевода нет — название из поиска, а не японский оригинал", async () => {
  assert.equal((await searchPoster("Frieren", { hero: true })).title, "Frieren: Beyond Journey's End");
});

test("posters: русский фильм без перевода — оригинальное название", async () => {
  assert.equal((await searchPoster("Brat", { hero: true })).title, "Брат");
});

test("posters: TMDB не ответил на подробности — название из поиска", async () => {
  assert.equal((await searchPoster("Broken", { hero: true })).title, "Broken");
});

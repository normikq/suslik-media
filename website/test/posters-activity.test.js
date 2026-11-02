"use strict";

// Сведения /api/poster для Discord Activity: оригинальное название, язык, жанры по-русски (категории «Мультфильмы» и «Аниме»),
// а с ?hero=1 — жанры и длительность из подробностей TMDB (карточка фильма)
const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-posters-act-test-"));
process.env.TMDB_API_KEY = "test-tmdb-key";

const test = require("node:test");
const assert = require("node:assert/strict");
const axios = require("axios");

axios.get = async url => {
  if (/search\/multi/.test(url)) {
    return { data: { results: [{ id: 7, media_type: "tv", name: "Frieren", original_name: "葬送のフリーレン", original_language: "ja",
      first_air_date: "2023-09-29", poster_path: "/fr.jpg", genre_ids: [16, 10759, 999] }] } };
  }
  if (/translations/.test(url)) return { data: { translations: [{ iso_639_1: "ru", iso_3166_1: "RU", data: { name: "Провожающая в последний путь Фрирен" } }] } };
  if (/images/.test(url)) return { data: { backdrops: [], logos: [] } };
  return { data: { overview: "Эльфийка-маг…", genres: [{ name: "Мультфильм" }, { name: "Драма" }], episode_run_time: [24], number_of_seasons: 2 } };
};

const { searchPoster } = require("../src/services/posters");

test("posters: оригинал, язык и жанры по-русски; с hero — длительность и сезоны", async () => {
  const base = await searchPoster("Frieren");
  assert.equal(base.original, "葬送のフリーレン");
  assert.equal(base.lang, "ja");
  assert.equal(base.animation, true);
  assert.deepEqual(base.genres, ["мультфильм", "боевик и приключения"]);
  const hero = await searchPoster("Frieren", { hero: true });
  assert.equal(hero.overview, "Эльфийка-маг…");
  assert.deepEqual(hero.genres, ["мультфильм", "драма"]);
  assert.equal(hero.runtime, 24);
  assert.equal(hero.seasons, 2);
});

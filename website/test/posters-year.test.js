"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-posters-test-"));
process.env.TMDB_API_KEY = "test-tmdb-key";

const test = require("node:test");
const assert = require("node:assert/strict");
const axios = require("axios");

// Ответы TMDB search/multi по тексту запроса
const TMDB = {
  "shogun": [
    { id: 1, media_type: "tv", name: "Shōgun", first_air_date: "1980-09-15", poster_path: "/old.jpg" },
    { id: 2, media_type: "tv", name: "Shōgun", first_air_date: "2024-02-27", poster_path: "/new.jpg" }
  ],
  "dune": [
    { id: 3, media_type: "movie", title: "Dune", release_date: "1984-12-14", poster_path: "/dune84.jpg" },
    { id: 4, media_type: "movie", title: "Dune", release_date: "2021-09-15", poster_path: "/dune21.jpg" }
  ],
  "frieren beyond journey's end": [
    { id: 5, media_type: "tv", name: "Frieren", first_air_date: "2023-09-29", poster_path: "/frieren.jpg" }
  ],
  "2012": [
    { id: 6, media_type: "movie", title: "2012", release_date: "2009-11-10", poster_path: "/2012.jpg" }
  ]
};
const asked = [];
axios.get = async (url, { params }) => {
  asked.push(params.query);
  return { data: { results: TMDB[String(params.query).toLowerCase()] || [] } };
};

const { searchPoster } = require("../src/services/posters");

test("posters: «Название (год)» — результат TMDB того же года, год в запрос не уходит", async () => {
  assert.equal((await searchPoster("Shogun (2024)")).poster, "/api/poster/img/w500/new.jpg");
  assert.equal((await searchPoster("Shogun (1980)")).poster, "/api/poster/img/w500/old.jpg");
  assert.equal((await searchPoster("Dune (2021)")).poster, "/api/poster/img/w500/dune21.jpg");
  assert.ok(!asked.some(q => /\(\d{4}\)/.test(q)));
});

test("posters: год ±1 подходит, без совпадения — первый найденный, без года — как раньше", async () => {
  assert.equal((await searchPoster("Frieren Beyond Journey's End (2024)")).poster, "/api/poster/img/w500/frieren.jpg");
  assert.equal((await searchPoster("Dune (1999)")).poster, "/api/poster/img/w500/dune84.jpg");
  assert.equal((await searchPoster("Dune")).poster, "/api/poster/img/w500/dune84.jpg");
  // название-год без скобок — не год
  assert.equal((await searchPoster("2012 (2009)")).poster, "/api/poster/img/w500/2012.jpg");
});

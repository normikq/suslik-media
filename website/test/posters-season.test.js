"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-posters-season-"));
process.env.TMDB_API_KEY = "test-tmdb-key";

const test = require("node:test");
const assert = require("node:assert/strict");
const axios = require("axios");

// Баннер «Продолжить просмотр» со вторым сезоном: год, описание и рейтинг — второго сезона, кадр и логотип — сериала
axios.get = async (url, { params }) => {
  if (url.endsWith("/search/multi")) {
    return { data: { results: [{ id: 7, media_type: "tv", name: "Cyberpunk", first_air_date: "2022-09-13", poster_path: "/p.jpg", overview: "Сериал", vote_average: 8.5 }] } };
  }
  if (url.endsWith("/tv/7/season/2")) return { data: { air_date: "2026-05-01", overview: "Второй сезон", vote_average: 7.9 } };
  if (url.endsWith("/tv/7/season/3")) return { data: { air_date: "", overview: "", vote_average: 0 } };
  if (url.endsWith("/tv/7/images")) return { data: { backdrops: [], logos: [] } };
  if (url.endsWith("/tv/7")) return { data: { overview: "Описание сериала" } };
  throw new Error("unexpected " + url + " " + JSON.stringify(params));
};

const { searchPoster } = require("../src/services/posters");

test("posters: баннер с сезоном — год, описание и рейтинг этого сезона", async () => {
  const show = await searchPoster("Cyberpunk", { hero: true });
  assert.equal(show.year, "2022");
  assert.equal(show.overview, "Описание сериала");
  const s2 = await searchPoster("Cyberpunk", { hero: true, season: 2 });
  assert.equal(s2.year, "2026");
  assert.equal(s2.overview, "Второй сезон");
  assert.equal(s2.rating, 7.9);
  assert.equal(s2.season, 2);
  // без сведений о сезоне — остаётся сериал; кэш без сезона не испорчен
  const s3 = await searchPoster("Cyberpunk", { hero: true, season: 3 });
  assert.equal(s3.year, "2022");
  assert.equal(s3.overview, "Описание сериала");
  assert.equal((await searchPoster("Cyberpunk", { hero: true })).year, "2022");
});

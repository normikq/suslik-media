"use strict";

// GET /api/title/:adId — сведения о фильме для страницы фильма (services/tmdb-details.js). TMDB подменён, сети нет.

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-title-test-"));
process.env.SESSION_SECRET = "test-session-secret-do-not-use";
process.env.AUTO_CLEAN = "false";
process.env.TMDB_API_KEY = "tmdb-test-key";

const test = require("node:test");
const assert = require("node:assert/strict");

const config = require("../src/config");
const { createApp } = require("../src/app");
const users = require("../src/services/users");
const tmdb = require("../src/services/tmdb-details");
const titleRoutes = require("../src/routes/title");

const MAGNETS = {
  1: { id: 1, name: "Dune.Part.Two.2024.2160p.HDR", statusCode: 4, files: [{ path: "Dune.Part.Two.2024.2160p.mkv", size: 4e10 }] },
  2: {
    id: 2,
    name: "Severance.S01.1080p",
    statusCode: 4,
    files: [{ path: "Severance.S01E01.mkv", size: 2e9 }, { path: "Severance.S01E02.mkv", size: 2e9 }]
  },
  3: { id: 3, name: "Unknown.Thing.2001", statusCode: 4, files: [{ path: "Unknown.Thing.2001.mkv", size: 1e9 }] },
  4: {
    id: 4,
    name: "Какое-то имя раздачи",
    statusCode: 4,
    files: [{ path: "x.mkv", size: 1e9 }],
    aiTitle: { status: "accepted", base: "Дюна", original: "Dune", year: 2021, kind: "movie" }
  }
};

const MOVIE = {
  id: 693134,
  title: "Дюна: Часть вторая",
  original_title: "Dune: Part Two",
  tagline: "Долгая жизнь бойцам",
  overview: "Пол Атрейдес объединяется с фрименами.",
  release_date: "2024-02-27",
  runtime: 166,
  vote_average: 8.153,
  vote_count: 6543,
  poster_path: "/abc123.jpg",
  genres: [{ name: "Фантастика" }, { name: "Приключения" }],
  production_countries: [{ iso_3166_1: "US", name: "United States of America" }, { iso_3166_1: "CA", name: "Canada" }],
  credits: {
    crew: [{ job: "Director", name: "Дени Вильнёв" }, { job: "Producer", name: "Кто-то" }],
    cast: Array.from({ length: 12 }, (_, i) => ({ name: "Актёр " + (i + 1), order: i }))
  },
  release_dates: {
    results: [
      { iso_3166_1: "US", release_dates: [{ certification: "PG-13" }] },
      { iso_3166_1: "RU", release_dates: [{ certification: "" }, { certification: "12" }] }
    ]
  }
};

const TV = {
  id: 95396,
  name: "Разделение",
  original_name: "Severance",
  overview: "Офис, где память делят надвое.",
  first_air_date: "2022-02-17",
  episode_run_time: [50, 56],
  vote_average: 8.4,
  vote_count: 1500,
  poster_path: "/tv.jpg",
  origin_country: ["US"],
  genres: [{ name: "Драма" }],
  created_by: [{ name: "Дэн Эриксон" }],
  credits: { cast: [{ name: "Адам Скотт", order: 0 }] },
  content_ratings: { results: [{ iso_3166_1: "US", rating: "TV-MA" }] }
};

let calls = [];
function fakeTmdb(url, params) {
  calls.push({ url, params });
  if (url.endsWith("/search/multi")) {
    const q = String(params.query || "").toLowerCase();
    if (q.startsWith("dune part two")) return { results: [{ id: 693134, media_type: "movie", release_date: "2024-02-27" }] };
    if (q.startsWith("severance")) return { results: [{ id: 95396, media_type: "tv", first_air_date: "2022-02-17" }] };
    if (q === "dune")
      return {
        results: [
          { id: 841, media_type: "movie", release_date: "1984-12-14" },
          { id: 438631, media_type: "movie", release_date: "2021-09-15" }
        ]
      };
    return { results: [] };
  }
  if (url.endsWith("/movie/693134")) return MOVIE;
  if (url.endsWith("/movie/438631")) return { ...MOVIE, id: 438631, title: "Дюна", release_dates: { results: [] } };
  if (url.endsWith("/tv/95396")) return TV;
  throw Object.assign(new Error("нет такого"), { status: 404 });
}

let server;
let base;
let token;

test.before(async () => {
  tmdb._setHttp(async (url, params) => fakeTmdb(url, params));
  titleRoutes.setMagnetLookup(async id => MAGNETS[id] || null);
  server = createApp().listen(0, "127.0.0.1");
  await new Promise(resolve => server.once("listening", resolve));
  base = `http://127.0.0.1:${server.address().port}`;
  const user = users.findOrCreateUser({ id: "777000111", username: "watcher" }, "allow");
  const pairing = users.createDevicePairing("Title Tester");
  users.authorizeDevicePairing(pairing.user_code, user.id);
  token = users.pollDevicePairing(pairing.code).device_token;
});

test.after(() => {
  server.close();
});

const get = async url => {
  const res = await fetch(base + url, { headers: { Authorization: `Bearer ${token}` } });
  return { status: res.status, json: await res.json().catch(() => null) };
};

test("title: без входа — 401, неверный номер — 400, нет в медиатеке — 404", async () => {
  assert.equal((await fetch(base + "/api/title/1")).status, 401);
  assert.equal((await get("/api/title/abc")).status, 400);
  assert.equal((await get("/api/title/999")).status, 404);
});

test("title: фильм — поля TMDB, возраст России, 10 актёров, страны по-русски, наш адрес постера", async () => {
  tmdb._reset();
  calls = [];
  const { status, json } = await get("/api/title/1");
  assert.equal(status, 200);
  assert.equal(json.found, true);
  assert.equal(json.type, "movie");
  assert.equal(json.tmdb_id, 693134);
  assert.equal(json.title, "Дюна: Часть вторая");
  assert.equal(json.original_title, "Dune: Part Two");
  assert.equal(json.tagline, "Долгая жизнь бойцам");
  assert.equal(json.release_date, "2024-02-27");
  assert.equal(json.year, 2024);
  assert.deepEqual(json.directors, ["Дени Вильнёв"]);
  assert.deepEqual(json.genres, ["фантастика", "приключения"]);
  assert.equal(json.runtime_min, 166);
  assert.equal(json.age, "12+", "сертификат России важнее США");
  assert.equal(json.rating, 8.2);
  assert.equal(json.votes, 6543);
  assert.equal(json.cast.length, 10);
  assert.equal(json.cast_more, true);
  assert.equal(json.poster, "/api/poster/img/w500/abc123.jpg");
  assert.equal(json.countries.length, 2);
  assert.equal(json.countries[0], "США");
  assert.equal(json.countries[1], "Канада");
  const details = calls.find(c => /\/movie\/693134$/.test(c.url));
  assert.equal(details.params.language, "ru-RU");
  assert.equal(details.params.append_to_response, "credits,release_dates");
  assert.ok(calls.every(c => !JSON.stringify(c).includes("tmdb-test-key")), "ключ добавляет только транспорт");
});

test("title: сериал — создатели, средняя длительность серии, возраст США", async () => {
  tmdb._reset();
  calls = [];
  const { json } = await get("/api/title/2");
  assert.equal(json.type, "tv");
  assert.deepEqual(json.directors, ["Дэн Эриксон"]);
  assert.equal(json.runtime_min, 53);
  assert.equal(json.age, "TV-MA");
  assert.equal(json.original_title, "Severance");
  assert.equal(json.cast_more, false);
  const details = calls.find(c => /\/tv\/95396$/.test(c.url));
  assert.equal(details.params.append_to_response, "credits,content_ratings");
});

test("title: принятое название ИИ — ищется оригинал, результат того же года", async () => {
  tmdb._reset();
  calls = [];
  const { json } = await get("/api/title/4");
  assert.equal(json.tmdb_id, 438631);
  assert.equal(json.age, null, "сертификатов нет — пусто");
  assert.equal(calls[0].params.query, "Dune");
});

test("title: кэш — второй запрос без TMDB, переживает перезапуск через файл", async () => {
  tmdb._reset();
  calls = [];
  await get("/api/title/1");
  const n = calls.length;
  assert.ok(n >= 2);
  const again = await get("/api/title/1");
  assert.equal(again.json.found, true);
  assert.equal(calls.length, n, "из памяти");
  await new Promise(r => setTimeout(r, 2300)); // makeJsonWriter пишет через 2 с
  const saved = JSON.parse(fs.readFileSync(tmdb._file, "utf8"));
  assert.ok(Object.values(saved).some(v => v.body && v.body.tmdb_id === 693134));
});

test("title: не нашлось — found:false и запоминается; сбой сети — 502 без запоминания", async () => {
  tmdb._reset();
  calls = [];
  const miss = await get("/api/title/3");
  assert.equal(miss.status, 200);
  assert.deepEqual(miss.json, { found: false });
  const n = calls.length;
  await get("/api/title/3");
  assert.equal(calls.length, n, "промах из кэша");

  tmdb._reset();
  tmdb._setHttp(async () => { throw new Error("ECONNRESET"); });
  try {
    assert.equal((await get("/api/title/1")).status, 502);
  } finally {
    tmdb._setHttp(async (url, params) => fakeTmdb(url, params));
  }
  calls = [];
  assert.equal((await get("/api/title/1")).json.found, true);
  assert.ok(calls.length > 0, "сбой не запомнен");
});

test("title: без TMDB_API_KEY — found:false без запросов", async () => {
  tmdb._reset();
  calls = [];
  const key = config.TMDB_API_KEY;
  config.TMDB_API_KEY = "";
  try {
    const { status, json } = await get("/api/title/1");
    assert.equal(status, 200);
    assert.equal(json.found, false);
    assert.equal(calls.length, 0);
  } finally {
    config.TMDB_API_KEY = key;
  }
});

test("title: возраст — Россия с плюсом, иначе США, иначе null", () => {
  assert.equal(tmdb.ageOf({ release_dates: { results: [{ iso_3166_1: "RU", release_dates: [{ certification: "18+" }] }] } }, "movie"), "18+");
  assert.equal(tmdb.ageOf({ release_dates: { results: [{ iso_3166_1: "US", release_dates: [{ certification: "R" }] }] } }, "movie"), "R");
  assert.equal(tmdb.ageOf({ content_ratings: { results: [{ iso_3166_1: "RU", rating: "16" }] } }, "tv"), "16+");
  assert.equal(tmdb.ageOf({}, "movie"), null);
});

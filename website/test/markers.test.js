"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-markers-test-"));

const test = require("node:test");
const assert = require("node:assert/strict");

const { markersFor, parseQuery, pickMalId, intervalsFrom, seasonNames, _reset } = require("../src/services/markers");

test("markers: id MAL только при точном совпадении названия (с сезоном)", () => {
  const data = [
    { mal_id: 1, title: "Frieren: Beyond Journey's End Movie", title_synonyms: [] },
    { mal_id: 52991, title: "Sousou no Frieren", title_english: "Frieren: Beyond Journey's End" },
    { mal_id: 3, title: "Shingeki no Kyojin Season 2", title_english: "Attack on Titan Season 2" }
  ];
  assert.equal(pickMalId(data, "Frieren: Beyond Journey's End", 1), 52991);
  assert.equal(pickMalId(data, "sousou no frieren", 1), 52991);
  assert.equal(pickMalId(data, "Attack on Titan", 2), 3);
  assert.equal(pickMalId(data, "Attack on Titan", 1), null);
  assert.equal(pickMalId(data, "Breaking Bad", 1), null);
  assert.ok(seasonNames("Title", 2).includes("title2ndseason"));
  assert.ok(seasonNames("Title", 3).includes("titleiii"));
});

test("markers: опенинг и эндинг из ответа AniSkip, в миллисекундах", () => {
  const body = {
    found: true,
    results: [
      { interval: { startTime: 1290.5, endTime: 1380.5 }, skipType: "ed" },
      { interval: { startTime: 89.1, endTime: 179.1 }, skipType: "op" }
    ]
  };
  assert.deepEqual(intervalsFrom(body, 1420), { i: [89100, 179100], c: [1290500, 1380500] });
  assert.deepEqual(intervalsFrom({ found: false, results: [] }, 0), { i: null, c: null });
  // эндинг за концом этой серии (другая версия) не берётся
  assert.deepEqual(intervalsFrom(body, 1200).c, null);
});

test("markers: параметры запроса", () => {
  assert.deepEqual(parseQuery({ title: "Frieren", season: "1", episode: "3", duration: "1420" }),
    { titles: ["Frieren"], season: 1, episode: 3, duration: 1420 });
  assert.equal(parseQuery({ title: "Frieren", episode: "-1" }), null);
  assert.equal(parseQuery({ title: "Dune (2021)" }).episode, 0); // без серии — фильм
  assert.equal(parseQuery({ title: "", episode: "1" }), null);
  assert.equal(parseQuery({ title: "X", alt: "Фрирен", episode: "2" }).titles.length, 1); // «X» короче двух букв
});

test("markers: найденное кешируется, ненайденное спрашивается снова через 3 дня", async () => {
  _reset();
  let calls = 0;
  const lookup = {
    mal: async t => { calls++; return t === "Frieren" ? 52991 : null; },
    skip: async () => ({ i: [90_000, 180_000], c: null })
  };
  const q = { titles: ["Фрирен", "Frieren"], season: 1, episode: 1, duration: 1420 };
  const r = await markersFor(q, 1000, lookup);
  assert.deepEqual(r, { intro: { startMs: 90_000, endMs: 180_000 }, credits: null, source: "aniskip" });
  assert.equal(calls, 2);
  await markersFor(q, 2000, lookup);
  assert.equal(calls, 2); // «Фрирен» — промах в кеше, «Frieren» — найденное
  const miss = await markersFor({ titles: ["Во все тяжкие"], season: 1, episode: 1, duration: 0 }, 3000, lookup);
  assert.deepEqual(miss, { intro: null, credits: null, source: null });
  await markersFor({ titles: ["Во все тяжкие"], season: 1, episode: 1, duration: 0 }, 4000, lookup);
  assert.equal(calls, 3);
  await markersFor({ titles: ["Во все тяжкие"], season: 1, episode: 1, duration: 0 }, 3000 + 4 * 24 * 3600 * 1000, lookup);
  assert.equal(calls, 4);
});

test("markers: ошибка сети не роняет ответ", async () => {
  _reset();
  const lookup = { mal: async () => { throw new Error("timeout"); }, skip: async () => ({ i: null, c: null }) };
  assert.deepEqual(await markersFor({ titles: ["Frieren"], season: 1, episode: 1, duration: 0 }, 1, lookup),
    { intro: null, credits: null, source: null });
});

test("markers: интервалы TheIntroDB (мс, null — до конца, 0/0 — нет)", () => {
  const { introDbIntervals } = require("../src/services/markers");
  const body = {
    intro: [{ start_ms: null, end_ms: 29344 }],
    credits: [{ start_ms: 0, end_ms: 0 }, { start_ms: 1266000, end_ms: 1325200 }, { start_ms: 1330000, end_ms: null }],
    recap: [], preview: []
  };
  assert.deepEqual(introDbIntervals(body, 1400), { i: [0, 29344], c: [1330000, 1400000] });
  assert.deepEqual(introDbIntervals({ intro: [{ start_ms: 0, end_ms: 0 }], credits: [] }, 0), { i: null, c: null });
});

test("markers: TheIntroDB первым, AniSkip дополняет; фильм — только TheIntroDB", async () => {
  _reset();
  const seen = [];
  const lookup = {
    tmdb: async t => (t.startsWith("Film") ? { id: 7, media_type: "movie" } : { id: 1396, media_type: "tv" }),
    introdb: async (ref, s, e) => { seen.push(`introdb ${ref.id} ${s} ${e}`); return { i: [1000, 60000], c: null }; },
    mal: async () => 52991,
    skip: async () => ({ i: [5, 6], c: [2_000_000, 2_100_000] })
  };
  const r = await markersFor({ titles: ["Show"], season: 2, episode: 3, duration: 2400 }, 1, lookup);
  assert.deepEqual(r, { intro: { startMs: 1000, endMs: 60000 }, credits: { startMs: 2_000_000, endMs: 2_100_000 }, source: "theintrodb+aniskip" });
  const f = await markersFor({ titles: ["Film (2002)"], season: 1, episode: 0, duration: 9000 }, 1, lookup);
  assert.equal(f.source, "theintrodb");
  assert.deepEqual(seen, ["introdb 1396 2 3", "introdb 7 1 0"]);
  // сериал с тем же названием, что у фильма в TMDB, не спрашивается как фильм
  const none = await markersFor({ titles: ["Film other"], season: 1, episode: 4, duration: 0 }, 1, { ...lookup, mal: async () => null });
  assert.equal(none.source, null);

  // не-аниме сериал (original_language = en) не опрашивает MAL / AniSkip
  let malCalled = false;
  const nonAnime = await markersFor({ titles: ["Western Show"], season: 1, episode: 1, duration: 2400 }, 1, {
    tmdb: async () => ({ id: 999, media_type: "tv", original_language: "en" }),
    introdb: async () => ({ i: null, c: null }),
    mal: async () => { malCalled = true; return 1; },
    skip: async () => ({ i: [1000, 2000], c: null })
  });
  assert.equal(malCalled, false);
  assert.equal(nonAnime.source, null);
});

"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const {
  parseEpisode,
  seasonOf,
  nameSeason,
  buildPlaylist,
  groupLibraryVersions,
  getItemTags,
  collapseTags,
  setPosterDb,
  setServerTags
} = require("../public/library-model.js");

const mk = (id, name, paths, size = 1.5e9) => ({
  id,
  name,
  statusCode: 4,
  files: paths.map(p => ({ path: p, size }))
});

test("parseEpisode: разбор номеров сезона и серии из разных форматов", () => {
  assert.deepEqual(parseEpisode("Show.S01E02.mkv"), { season: 1, episode: 2 });
  assert.deepEqual(parseEpisode("Show 2x05.mkv"), { season: 2, episode: 5 });
  assert.deepEqual(parseEpisode("Сезон 3/Серия 4.mkv"), { season: 3, episode: 4 });
  assert.deepEqual(parseEpisode("Сезон 1/02. Name.mkv"), { season: 1, episode: 2 });
  assert.equal(parseEpisode("Movie.2021.mkv"), null);
  assert.deepEqual(parseEpisode("Ep 07.mkv", 4), { season: 4, episode: 7 });
});

test("seasonOf: извлечение номера сезона из пути папки", () => {
  assert.equal(seasonOf("Сезон 2/01.mkv"), 2);
  assert.equal(seasonOf("Show/S03/01.mkv"), 3);
  assert.equal(seasonOf("Show.S01E01.mkv"), null);
});

test("nameSeason: извлечение номера сезона из названия", () => {
  assert.equal(nameSeason("Show.S02.1080p"), 2);
  assert.equal(nameSeason("Сериал (Сезон: 3)"), 3);
  assert.equal(nameSeason("Show S01-S03"), null);
});

test("buildPlaylist: сортировка серий в одном сезоне", () => {
  const m = mk(1, "Show S01", ["Show.S01E02.mkv", "Show.S01E01.mkv"]);
  const pl = buildPlaylist(m);
  assert.equal(pl.type, "series");
  assert.equal(pl.seasons.length, 1);
  assert.equal(pl.seasons[0].season, 1);
  assert.deepEqual(pl.seasons[0].items.map(x => x.episode), [1, 2]);
  assert.deepEqual(pl.seasons[0].items.map(x => x.file.path), [
    "Show.S01E01.mkv",
    "Show.S01E02.mkv"
  ]);
});

test("buildPlaylist: сериал с папками сезонов", () => {
  const m = mk(2, "Show", ["Сезон 1/02.mkv", "Сезон 1/01.mkv", "Сезон 2/01.mkv"]);
  const pl = buildPlaylist(m);
  assert.equal(pl.type, "series");
  assert.deepEqual(pl.seasons.map(s => s.season), [1, 2]);
  assert.deepEqual(pl.seasons[0].items.map(x => x.file.path), [
    "Сезон 1/01.mkv",
    "Сезон 1/02.mkv"
  ]);
});

test("buildPlaylist: одиночный фильм", () => {
  const m = mk(3, "Dune 2021", ["Dune.2021.1080p.mkv"]);
  const pl = buildPlaylist(m);
  assert.equal(pl.type, "movie");
  assert.equal(pl.file.path, "Dune.2021.1080p.mkv");
});

test("buildPlaylist: сезон из названия при отсутствии сезона в файлах", () => {
  const m = mk(4, "Show S02 1080p", ["Show.E01.mkv", "Show.E02.mkv"]);
  const pl = buildPlaylist(m);
  assert.equal(pl.type, "series");
  assert.equal(pl.seasons.length, 1);
  assert.equal(pl.seasons[0].season, 2);
});

test("buildPlaylist: нумерованные серии без префикса SxxExx", () => {
  const paths = [1, 2, 3, 4, 5, 6].map(n => `Show - 0${n} [1080p].mkv`);
  const m = mk(5, "Show [1080p]", paths);
  const pl = buildPlaylist(m);
  assert.equal(pl.type, "series");
  assert.equal(pl.seasons.length, 1);
  assert.equal(pl.seasons[0].season, 1);
  assert.deepEqual(pl.seasons[0].items.map(x => x.episode), [1, 2, 3, 4, 5, 6]);
});

test("groupLibraryVersions: группировка версий фильмов, сезонов сериалов и неготовых раздач", () => {
  const m3 = mk(3, "Dune.2021.1080p", ["Dune.2021.1080p.mkv"]);
  const m4 = mk(4, "Dune 2021 2160p HDR", ["Dune.2021.2160p.mkv"]);
  const g1 = groupLibraryVersions([m3, m4]);
  assert.equal(g1.shown.length, 1);
  assert.deepEqual(g1.versions.get(3).map(x => x.id), [3, 4]);

  const m5 = mk(5, "Dune 1984", ["Dune.1984.mkv"]);
  const g2 = groupLibraryVersions([m3, m4, m5]);
  assert.equal(g2.shown.length, 2);
  assert.deepEqual([...g2.versions.keys()], [3]);

  const s10 = mk(10, "Show S01", ["Show.S01E01.mkv", "Show.S01E02.mkv"]);
  const s11 = mk(11, "Show S02", ["Show.S02E01.mkv", "Show.S02E02.mkv"]);
  const g3 = groupLibraryVersions([s10, s11]);
  assert.deepEqual(g3.versions.get(10).map(x => x.id), [10, 11]);

  const pending = { ...mk(12, "Dune.2021.1080p", ["Dune.2021.1080p.mkv"]), statusCode: 1 };
  const g4 = groupLibraryVersions([m3, pending]);
  assert.equal(g4.shown.length, 2);
  assert.equal(g4.versions.size, 0);
});

test("groupLibraryVersions: объединение разных названий через общий постер в posterDb", () => {
  setPosterDb({
    "фильм а": { u: "/api/poster/img/w500/x.jpg", t: Date.now(), r: "" },
    "film b": { u: "/api/poster/img/w500/x.jpg", t: Date.now(), r: "" }
  });
  try {
    const a = mk(1, "Фильм А 2020", ["Фильм.А.2020.mkv"]);
    const b = mk(2, "Film B 2019", ["Film.B.2019.mkv"]);
    const g = groupLibraryVersions([a, b]);
    assert.equal(g.shown.length, 1);
  } finally {
    setPosterDb({});
  }
});

test("getItemTags, collapseTags и setServerTags: теги качества и серверные теги", () => {
  const tags = getItemTags(mk(1, "X.2160p.DV.HDR.DTS-HD.MA", ["x.mkv"]));
  assert.equal(tags.includes("Dolby Vision"), true);
  assert.equal(tags.includes("HDR"), true);
  assert.equal(tags.includes("DTS-HD MA"), true);
  assert.deepEqual(collapseTags(tags), ["Dolby Vision", "DTS"]);

  setServerTags({ 7: ["HDR"] });
  try {
    assert.deepEqual(getItemTags({ id: 7, name: "plain", files: [] }), ["HDR"]);
  } finally {
    setServerTags({});
  }
});

test("buildPlaylist: коллекция с aiTitle для сериала", () => {
  const m = {
    ...mk(20, "Collection", ["a1.mkv", "a2.mkv", "a3.mkv"]),
    aiTitle: { status: "accepted", kind: "series", season: 2, base: "Шоу" }
  };
  const pl = buildPlaylist(m);
  assert.equal(pl.type, "series");
  assert.equal(pl.seasons.length, 1);
  assert.equal(pl.seasons[0].season, 2);
  assert.equal(pl.seasons[0].items.length, 3);
});

// Модуль — копия кода index.html (страница пока на модуль не переведена): правило поменяли в одном месте — тест падает
test("library-model: функции совпадают с index.html слово в слово", () => {
  const fs = require("fs");
  const path = require("path");
  const page = fs.readFileSync(path.join(__dirname, "..", "public", "index.html"), "utf8").replace(/\r\n/g, "\n");
  const mod = fs.readFileSync(path.join(__dirname, "..", "public", "library-model.js"), "utf8").replace(/\r\n/g, "\n");
  const body = (src, name) => {
    const i = src.indexOf("\nfunction " + name + "(");
    assert.ok(i >= 0, name);
    return src.slice(i, src.indexOf("\n}\n", i) + 2);
  };
  for (const name of ["escapeTitle", "pickMainVideo", "nameSeason", "parseEpisode", "filmsByYear", "namedCollection", "isCollection",
    "collectionTypeLabel", "buildPlaylist", "applyAiTitle", "buildPlaylistByFiles", "numberedEpisodesOf", "displayTitle",
    "aiPosterQuery", "titleKey", "playableKind", "groupLibraryVersions", "tileTitle", "posterPathOf", "extractYear",
    "releaseYear", "getPosterQueries", "collapseTags", "getItemTags"]) {
    assert.equal(body(mod, name), body(page, name), name);
  }
  for (const re of ["NAME_SEASON_RANGE", "NAME_SEASON", "COLLECTION_STRONG", "COLLECTION_WEAK", "SERIES_HINT", "NUM_NOISE", "SEASON_NOISE", "VIDEO_EXT"]) {
    const line = src => (src.match(new RegExp("\nconst " + re + "=.*")) || [""])[0];
    assert.equal(line(mod), line(page), re);
  }
});

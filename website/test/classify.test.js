"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { classify } = require("../classify");

test("classify by Torznab/Newznab categories (priority over title)", async (t) => {
  await t.test("Adult 6xxx returns other (highest category priority)", () => {
    assert.equal(classify("Adult Movie 1080p", [6000]), "other");
    assert.equal(classify("Adult S01E01 1080p", [5000, 6000]), "other"); // 6000 beats 5000
    assert.equal(classify("Explicit Content", [6010]), "other");
  });

  await t.test("TV 5xxx returns series", () => {
    assert.equal(classify("Some Show", [5000]), "series");
    assert.equal(classify("Anime 1080p Movie Title", [5070]), "series"); // Anime in 5070
    assert.equal(classify("Documentary HD", [5010]), "series");
  });

  await t.test("Movies 2xxx returns movie", () => {
    assert.equal(classify("Movie Title", [2000]), "movie");
    assert.equal(classify("4K UHD Film", [2045]), "movie");
    // Category takes precedence even if title mentions "Season" or "S01"
    assert.equal(classify("Movie S01 Special", [2000]), "movie");
  });

  await t.test("Other categories (Audio 3xxx, PC/Console 1xxx & 4xxx, Books 7xxx, etc.)", () => {
    assert.equal(classify("Audio album", [3000]), "other");
    assert.equal(classify("PC Game 2024", [4000]), "other");
    assert.equal(classify("Console Game", [1000]), "other");
    assert.equal(classify("E-Book Fiction", [7000]), "other");
    assert.equal(classify("Misc software", [8000]), "other");
  });

  await t.test("Unmapped category (< 1000 or >= 9000) falls back to title heuristics", () => {
    assert.equal(classify("Breaking Bad S01 1080p", [500]), "series");
    assert.equal(classify("Inception 2010 BDRip", [500]), "movie");
    assert.equal(classify("Breaking Bad S01 1080p", [99999]), "series");
    assert.equal(classify("Inception 2010 BDRip", [99999]), "movie");
  });
});

test("classify series by title heuristics", async (t) => {
  await t.test("Standard SxxEyy and Sxx tokens", () => {
    assert.equal(classify("Severance S01E01 1080p"), "series");
    assert.equal(classify("Severance S02E10 WEB-DL"), "series");
    assert.equal(classify("The Office S05 720p"), "series");
  });

  await t.test("English season and episode markers", () => {
    assert.equal(classify("Stranger Things Season 4 BDRip"), "series");
    assert.equal(classify("Westworld Seasons 1-3"), "series");
    assert.equal(classify("Planet Earth Complete Series 1080p"), "series");
    assert.equal(classify("Ted Lasso Episode 05"), "series");
    assert.equal(classify("Ted Lasso Episodes 1-6"), "series");
    assert.equal(classify("Show Name 01 of 10"), "series");
  });

  await t.test("Russian series markers (сезон, сериал, серия, серии, эпизод, X из Y)", () => {
    assert.equal(classify("Чернобыль 1 сезон 1080p"), "series");
    assert.equal(classify("Слово пацана Сезон 2 WEB-DL"), "series");
    assert.equal(classify("Интерны Серия 5"), "series");
    assert.equal(classify("Интерны Серии 1-20"), "series");
    assert.equal(classify("Бригада (сериал) 2002"), "series");
    assert.equal(classify("Новый Эпизод 10"), "series");
    assert.equal(classify("Шоу 03 из 12 1080p"), "series");
  });

  await t.test("TV tag tokens", () => {
    assert.equal(classify("Concert [TV] 720p"), "series");
    assert.equal(classify("Special Event [TV-1]"), "series");
  });
});

test("classify movies by video release tags", async (t) => {
  await t.test("Optical rips and sources", () => {
    assert.equal(classify("Inception 2010 BDRip 1080p"), "movie");
    assert.equal(classify("Gladiator BD-Rip 720p"), "movie");
    assert.equal(classify("The Dark Knight BDRemux"), "movie");
    assert.equal(classify("Interstellar BluRay 1080p"), "movie");
    assert.equal(classify("Avatar Blu-ray 4K"), "movie");
    assert.equal(classify("Old Film DVDRip"), "movie");
    assert.equal(classify("Concert DVD5"), "movie");
    assert.equal(classify("Live Show DVD9"), "movie");
  });

  await t.test("Web and HDTV sources", () => {
    assert.equal(classify("Dune Part Two WEB-DL 2160p"), "movie");
    assert.equal(classify("Dune WEB-DLRip"), "movie");
    assert.equal(classify("Civil War WEBRip"), "movie");
    assert.equal(classify("Broadcast Event HDTVRip"), "movie");
    assert.equal(classify("Live Broadcast TVRip"), "movie");
    assert.equal(classify("Satellite Capture SATRip"), "movie");
  });

  await t.test("Cam, Telesync and Screener", () => {
    assert.equal(classify("New Film CAMRip"), "movie");
    assert.equal(classify("New Film HDCAM"), "movie");
    assert.equal(classify("New Film HDTS"), "movie");
    assert.equal(classify("Award Screener DVDScr"), "movie");
  });

  await t.test("Codecs, resolutions and formats", () => {
    assert.equal(classify("Action Movie 4K"), "movie");
    assert.equal(classify("Epic Movie UHD HDR10"), "movie");
    assert.equal(classify("Adventure Film x265 HEVC"), "movie");
    assert.equal(classify("Classic Film AVC H.264"), "movie");
    assert.equal(classify("Modern Film Dolby Vision IMAX"), "movie");
    assert.equal(classify("Clean 1080i Capture"), "movie");
    assert.equal(classify("Low Res 480p Video"), "movie");
  });
});

test("classify non-video content as other", async (t) => {
  await t.test("Games and Repacks", () => {
    assert.equal(classify("Cyberpunk 2077 PC RePack"), "other");
    assert.equal(classify("Elden Ring FitGirl"), "other");
    assert.equal(classify("GTA V xatab"), "other");
    assert.equal(classify("Game Title R.G. Mechanics"), "other");
    assert.equal(classify("God of War PS4"), "other");
    assert.equal(classify("Zelda Nintendo NSW"), "other");
    assert.equal(classify("Halo Xbox"), "other");
  });

  await t.test("Music and Audio", () => {
    assert.equal(classify("Pink Floyd FLAC Lossless"), "other");
    assert.equal(classify("Best Hits MP3 320kbps"), "other");
    assert.equal(classify("Artist Discography APE"), "other");
    assert.equal(classify("Группа Кино Дискография"), "other");
    assert.equal(classify("Movie Soundtrack OST"), "other");
    assert.equal(classify("Композитор Саундтрек"), "other");
    assert.equal(classify("Книга Аудиокнига MP3"), "other");
    assert.equal(classify("Novel Audiobook"), "other");
    assert.equal(classify("Master Audio WavPack"), "other");
  });

  await t.test("Books and Documents", () => {
    assert.equal(classify("Programming Guide PDF"), "other");
    assert.equal(classify("Fantasy Novel FB2 EPUB"), "other");
    assert.equal(classify("Technical Spec DJVU"), "other");
    assert.equal(classify("Comics Issue CBR CBZ"), "other");
    assert.equal(classify("E-reader file MOBI"), "other");
  });

  await t.test("Software and Operating Systems", () => {
    assert.equal(classify("Adobe Photoshop Portable"), "other");
    assert.equal(classify("Utility Keygen Crack"), "other");
    assert.equal(classify("Tool Activator"), "other");
    assert.equal(classify("Microsoft Office 2021"), "other");
    assert.equal(classify("Windows 11 Pro 64bit"), "other");
    assert.equal(classify("Ubuntu Linux 24.04"), "other");
    assert.equal(classify("Custom ROM Android"), "other");
    assert.equal(classify("macOS Sequoia Installer"), "other");
    assert.equal(classify("Distro [ISO]"), "other");
  });
});

test("classify edge cases and fallbacks", async (t) => {
  await t.test("Series tag takes precedence over video tag in title", () => {
    // Both SERIES and VIDEO match -> must be series
    assert.equal(classify("Breaking Bad S01 1080p BDRip HEVC"), "series");
    assert.equal(classify("The Wire Complete Series 720p WEB-DL"), "series");
  });

  await t.test("Unknown for ambiguous or bare names", () => {
    assert.equal(classify("Just A Name"), "unknown");
    assert.equal(classify(""), "unknown");
    assert.equal(classify(null), "unknown");
    assert.equal(classify(undefined), "unknown");
    assert.equal(classify(12345), "unknown");
  });
});

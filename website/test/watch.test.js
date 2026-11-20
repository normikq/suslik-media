"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-watch-test-"));
process.env.SESSION_SECRET = "test-session-secret-do-not-use";
process.env.AUTO_CLEAN = "false";

const test = require("node:test");
const assert = require("node:assert/strict");

const watch = require("../public/watch.js");
const { createApp } = require("../src/app");

let server;
let base;

test.before(async () => {
  server = createApp().listen(0, "127.0.0.1");
  await new Promise(resolve => server.once("listening", resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

test.after(() => {
  server.close();
});

test("sitePaths: разбирает пути с базой и без, невалидный id даёт null", () => {
  assert.deepEqual(watch.sitePaths("/media/watch/123"), {
    id: "123",
    siteBase: "/media",
    apiRoot: ""
  });
  assert.deepEqual(watch.sitePaths("/suslik/media/watch/5"), {
    id: "5",
    siteBase: "/suslik/media",
    apiRoot: "/suslik"
  });
  assert.equal(watch.sitePaths("/watch/abc").id, null);
});

test("parseQuery: разбирает параметры серии, файла и времени", () => {
  assert.deepEqual(watch.parseQuery("?s=1&e=2&t=30"), {
    f: null,
    s: 1,
    e: 2,
    t: 30
  });
  assert.equal(
    watch.parseQuery("?f=" + encodeURIComponent("Сезон 1/02.mkv")).f,
    "Сезон 1/02.mkv"
  );
  const bad = watch.parseQuery("?t=-5&s=x");
  assert.equal(bad.t, null);
  assert.equal(bad.s, null);
});

test("pickItem, nextItem, itemQuery, flatItems: выбор эпизодов сериала и фильма", () => {
  const pl = {
    type: "series",
    seasons: [
      {
        season: 1,
        items: [
          { season: 1, episode: 1, file: { path: "S1/01.mkv" } },
          { season: 1, episode: 2, file: { path: "S1/02.mkv" } }
        ]
      },
      {
        season: 2,
        items: [{ season: 2, episode: 1, file: { path: "S2/01.mkv" } }]
      }
    ]
  };

  assert.equal(watch.pickItem(pl, { s: 1, e: 2 }).file.path, "S1/02.mkv");
  assert.equal(watch.pickItem(pl, { f: "S2/01.mkv" }).season, 2);
  assert.equal(watch.pickItem(pl, { s: 2 }).file.path, "S2/01.mkv");
  assert.equal(watch.pickItem(pl, { s: 9, e: 9 }, "S1/02.mkv").file.path, "S1/02.mkv");
  assert.equal(watch.pickItem(pl, {}).file.path, "S1/01.mkv");

  assert.equal(watch.nextItem(pl, pl.seasons[0].items[1]).file.path, "S2/01.mkv");
  assert.equal(watch.nextItem(pl, pl.seasons[1].items[0]), null);
  assert.equal(watch.itemQuery(pl, pl.seasons[1].items[0]), "?s=2&e=1");

  const mv = { type: "movie", file: { path: "m.mkv" } };
  const mvItems = watch.flatItems(mv);
  assert.equal(mvItems.length, 1);
  assert.equal(watch.itemQuery(mv, mvItems[0]), "");
  assert.equal(watch.pickItem(mv, {}).file.path, "m.mkv");
});

test("kindOf и headingText: тип контента и заголовок", () => {
  const pl = {
    type: "series",
    seasons: [{ season: 1, items: [{ season: 1, episode: 1, file: { path: "S1/01.mkv" } }] }]
  };
  assert.equal(watch.kindOf(pl, false), "series");
  assert.equal(watch.kindOf(pl, true), "collection");
  assert.equal(watch.kindOf({ type: "movie", file: null }), "none");

  assert.equal(
    watch.headingText("Шоу", { season: 2, episode: 5 }, "series"),
    "Шоу 2 сезон 5 серия"
  );
  assert.equal(watch.headingText("Фильм", null, "movie"), "Фильм");
});

test("watchBadge: прогресс просмотра и завершение", () => {
  assert.deepEqual(watch.watchBadge({ pos: 60000, len: 120000 }), { done: false, pct: 50 });
  assert.equal(watch.watchBadge({ pos: 119000, len: 120000 }).done, true);
  assert.equal(watch.watchBadge({ pos: 1000, len: 120000 }), null);
  assert.equal(watch.watchBadge(null), null);
});

test("qualityOptions, subtitleOptions, trackLabel: опции плеера", () => {
  assert.deepEqual(
    watch.qualityOptions({ levels: [{ index: 0, height: 720, label: "720p" }] }),
    []
  );
  assert.deepEqual(
    watch.qualityOptions({
      levels: [
        { index: 0, height: 720, label: "720p" },
        { index: 1, height: 1080, label: "1080p" }
      ],
      currentLevel: 1
    }),
    [
      { value: -1, text: "Авто (сейчас 1080p)" },
      { value: 1, text: "1080p" },
      { value: 0, text: "720p" }
    ]
  );

  assert.deepEqual(watch.subtitleOptions({ subtitles: [] }), []);
  assert.deepEqual(
    watch.subtitleOptions({
      subtitles: [{ index: 3, name: "Русские", lang: "rus" }]
    }),
    [
      { value: -1, text: "Выключены" },
      { value: 3, text: "Русские (rus)" }
    ]
  );

  assert.equal(watch.trackLabel({ name: "", lang: "" }, 1), "Дорожка 2");
});

test("overlayFor и playerHref: оверлей и ссылки на внешние плееры", () => {
  assert.equal(watch.overlayFor({ kind: "hls" }), null);

  const prep = watch.overlayFor({ kind: "preparing", etaMin: 7 });
  assert.ok(prep.text.includes("примерно 7 мин"));
  assert.equal(prep.app, true);

  assert.ok(watch.overlayFor({ kind: "unsupported" }).text.includes("браузер"));

  const url = "https://x.test/api/file/proxy/a.mkv?sig=1";
  assert.ok(watch.playerHref(url, "Windows NT").startsWith("potplayer://"));

  const android = watch.playerHref(url, "Linux; Android 14");
  assert.ok(android.startsWith("intent://x.test/"));
  assert.ok(android.includes("S.browser_fallback_url="));

  assert.ok(watch.playerHref(url, "iPhone").startsWith("vlc-x-callback://"));
});

test("factRows и aboutTitle: факты о тайтле и заголовок описания", () => {
  const rows = watch.factRows(
    {
      rating: 7.84,
      votes: 12345,
      tagline: "Слоган",
      release_date: "2021-10-21",
      countries: ["США"],
      directors: ["Дени Вильнёв"],
      genres: ["фантастика"],
      age: "12+",
      runtime_min: 155,
      cast: ["A", "B"],
      cast_more: true,
      type: "movie"
    },
    { quality: "4K, HDR", sizeText: "50 ГБ" }
  );

  assert.deepEqual(
    rows.map(r => r[0]),
    [
      "Рейтинг",
      "Слоган",
      "Дата выхода",
      "Страна",
      "Режиссёр",
      "Жанр",
      "В качестве",
      "Возраст",
      "Время",
      "В ролях",
      "Размер"
    ]
  );
  assert.ok(rows[0][1].startsWith("7,8 TMDB"));
  assert.equal(rows[2][1], "21 октября 2021 г.");
  assert.equal(rows[9][1], "A, B и другие");

  const tvRows = watch.factRows({ type: "tv", directors: ["X"] }, {});
  assert.ok(tvRows.some(r => r[0] === "Создатели"));

  assert.deepEqual(watch.factRows({}, { quality: "", sizeText: "" }), []);

  assert.equal(watch.aboutTitle("Дюна", "movie"), "Про что фильм «Дюна»:");
  assert.equal(watch.aboutTitle("Шоу", "series"), "Про что сериал «Шоу»:");
});

test("HTTP: GET /watch/123 отдаёт HTML со строгим CSP без inline-скриптов", async () => {
  const res = await fetch(`${base}/watch/123`);
  assert.equal(res.status, 200);
  assert.match(res.headers.get("content-type") || "", /text\/html/);

  const csp = res.headers.get("content-security-policy");
  assert.ok(csp);
  const scriptSrc = csp
    .split(";")
    .map(p => p.trim())
    .find(p => p.startsWith("script-src"));
  assert.ok(scriptSrc);
  assert.ok(scriptSrc.includes("'self'"));
  assert.ok(!scriptSrc.includes("unsafe-inline"));

  const body = await res.text();
  assert.doesNotMatch(body, /<script(?![^>]*\bsrc=)[^>]*>/i);
  assert.ok(body.includes("../watch.js"));
});

test("HTTP: GET /watch/abc -> 404, редиректы /watch/123/ и /api/title/123 без входа", async () => {
  const r404 = await fetch(`${base}/watch/abc`);
  assert.equal(r404.status, 404);
  const body404 = await r404.text();
  assert.ok(body404.includes("watch.js"));

  const rRedir1 = await fetch(`${base}/watch/123/`, { redirect: "manual" });
  assert.equal(rRedir1.status, 302);
  assert.equal(rRedir1.headers.get("location"), "../123");

  const rRedir2 = await fetch(`${base}/watch/123/dune?s=1&e=2`, { redirect: "manual" });
  assert.equal(rRedir2.status, 302);
  assert.equal(rRedir2.headers.get("location"), "../123?s=1&e=2");

  const rApi = await fetch(`${base}/api/title/123`);
  assert.equal(rApi.status, 401);
});

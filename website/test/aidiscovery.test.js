"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-aidiscovery-test-"));
process.env.GEMINI_API_KEY = "test-key-not-real";
process.env.AI_DISCOVERY_COOLDOWN_SEC = "5";
process.env.AI_DISCOVERY_DAILY_MAX = "3";

const test = require("node:test");
const assert = require("node:assert/strict");

const ai = require("../src/services/aidiscovery");

const NOW = Date.parse("2026-11-01T12:00:00Z");
const OWNER = "u_42";

let calls = [];
let replies = [];
function gemini(items) {
  return { status: 200, body: { candidates: [{ content: { parts: [{ text: JSON.stringify({ items }) }] } }], usageMetadata: { promptTokenCount: 300, candidatesTokenCount: 200 } } };
}
globalThis.fetch = async (url, init) => {
  calls.push({ url, body: JSON.parse(init?.body || "{}") });
  const r = replies.shift();
  if (!r) throw new Error("unexpected fetch");
  if (r.throw) throw r.throw;
  return { status: r.status, ok: r.status >= 200 && r.status < 300, json: async () => r.body };
};

const looked = [];
const lookup = async q => {
  looked.push(q);
  if (q.startsWith("Nope")) throw new Error("tmdb down");
  return { poster: "/api/poster/img/w500/abc.jpg", rating: 8.1, overview: "Описание", title_ru: "Нечто" };
};

const THING = { title: "Нечто", original: "The Thing", year: 1982, kind: "movie", reason: "«Эталон паранойи на полярной станции» ✨", genres: ["Ужасы", "фантастика"] };

test.beforeEach(() => {
  ai._reset();
  calls = [];
  replies = [];
  looked.length = 0;
});

test("подборка: модель, затем TMDB по «Original (год)», постер w342", async () => {
  replies.push(gemini([THING, { title: "Экзамен", original: "Nope", year: 2009, kind: "movie", reason: "Битва кандидатов в одной комнате." }]));
  const out = await ai.discover("триллер в закрытом пространстве", OWNER, NOW, { lookup });
  assert.equal(out.cached, false);
  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /:generateContent$/);
  assert.equal(calls[0].body.generationConfig.temperature, 0.4);
  assert.deepEqual(looked.sort(), ["Nope (2009)", "The Thing (1982)"]);
  const [a, b] = out.items;
  assert.equal(a.poster, "/api/poster/img/w342/abc.jpg");
  assert.equal(a.rating, 8.1);
  assert.equal(a.tmdbTitle, "Нечто");
  assert.equal(a.reason, "Эталон паранойи на полярной станции", "кавычки и эмодзи убраны");
  assert.deepEqual(a.genres, ["ужасы", "фантастика"]);
  assert.equal(b.poster, null, "сбой TMDB — фильм без постера");
});

test("тот же запрос — из памяти, без нового вызова и кулдауна", async () => {
  replies.push(gemini([THING]));
  await ai.discover("Космос без монстров", OWNER, NOW, { lookup });
  const again = await ai.discover("космос  без монстров!", OWNER, NOW + 1000, { lookup });
  assert.equal(again.cached, true);
  assert.equal(calls.length, 1);
});

test("кулдаун на пользователя; fresh повышает температуру", async () => {
  replies.push(gemini([THING]));
  await ai.discover("что-нибудь", OWNER, NOW, { lookup });
  await assert.rejects(ai.discover("другое", OWNER, NOW + 1000, { lookup }), e => e.code === "cooldown" && e.retryAfterMs === 4000);
  replies.push(gemini([THING]));
  await ai.discover("что-нибудь", OWNER, NOW + 6000, { lookup, fresh: true });
  assert.equal(calls.length, 2);
  assert.equal(calls[1].body.generationConfig.temperature, 0.9);
});

test("перегрузка Gemini не списывается и не запускает кулдаун", async () => {
  replies.push({ status: 503, body: {} });
  await assert.rejects(ai.discover("драма", OWNER, NOW, { lookup }), e => e.code === "busy");
  replies.push(gemini([THING]));
  const out = await ai.discover("драма", OWNER, NOW + 100, { lookup });
  assert.equal(out.items.length, 1);
  assert.equal(ai.stats(NOW).today.calls, 1);
});

test("дневной лимит", async () => {
  for (let i = 0; i < 3; i++) {
    replies.push(gemini([THING]));
    await ai.discover("запрос " + i, "u_" + i, NOW, { lookup });
  }
  await assert.rejects(ai.discover("ещё", "u_9", NOW, { lookup }), e => e.code === "daily");
});

test("проверка ответа: без года, выдуманный год, повторы, пустой ответ", async () => {
  const items = ai.parseItems({ items: [
    THING,
    { ...THING, title: "Нечто (повтор)" },
    { title: "Без года", original: "No Year", kind: "movie", reason: "x" },
    { title: "Будущее", original: "Future", year: 2099, kind: "movie", reason: "x" },
    { title: "Сериал", original: "Show", year: 2015, kind: "tv", reason: "x" }
  ] }, NOW);
  assert.deepEqual(items.map(i => i.title), ["Нечто", "Сериал"]);
  assert.equal(items[1].kind, "movie", "неизвестный вид — фильм");
  replies.push(gemini([]));
  await assert.rejects(ai.discover("пусто", OWNER, NOW, { lookup }), e => e.code === "bad");
  await assert.rejects(ai.discover(" ", OWNER, NOW, { lookup }), e => e.code === "empty");
});

"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-aititle-test-"));
process.env.GEMINI_API_KEY = "test-key-not-real";
process.env.AI_TITLE_COOLDOWN_MIN = "15";
process.env.AI_TITLE_DAILY_MAX = "3";

const test = require("node:test");
const assert = require("node:assert/strict");

const ai = require("../src/services/aititle");

const MIN = 60 * 1000;
const NOW = Date.parse("2026-10-27T12:00:00Z");

const show = {
  id: 101,
  name: "Тед Лассо / Ted Lasso [S02] (2021) WEB-DL 1080p",
  files: [
    { path: "Ted.Lasso.S02/Ted.Lasso.S02E01.mkv", type: "file" },
    { path: "Ted.Lasso.S02/Ted.Lasso.S02E02.mkv", type: "file" },
    { path: "Ted.Lasso.S02/sample.mkv", type: "file" },
    { path: "Ted.Lasso.S02/Subs/rus.srt", type: "file" },
    { path: "Ted.Lasso.S02", type: "folder" }
  ]
};
const film = { id: 202, name: "Тачки 2 / Cars 2 (2011) BDRip 1080p", files: [{ path: "Cars.2.2011.mkv", type: "file" }] };

// Мок fetch: очередь ответов, все вызовы записываются
let calls = [];
let replies = [];
function gemini(answer, usage = { promptTokenCount: 120, candidatesTokenCount: 21, totalTokenCount: 141 }) {
  return { status: 200, body: { candidates: [{ content: { parts: [{ text: JSON.stringify(answer) }] } }], usageMetadata: usage } };
}
globalThis.fetch = async (url, init) => {
  calls.push({ url, init, body: JSON.parse(init.body) });
  const r = replies.shift();
  if (!r) throw new Error("unexpected fetch");
  if (r.throw) throw r.throw;
  return { status: r.status, ok: r.status >= 200 && r.status < 300, json: async () => r.body };
};
const origLog = console.log;
const origWarn = console.warn;
test.before(() => { console.log = () => {}; console.warn = () => {}; });
test.after(() => { console.log = origLog; console.warn = origWarn; });

function reset() {
  ai._reset();
  calls = [];
  replies = [];
}

test("aititle: season must be mentioned in the name or the paths", () => {
  assert.equal(ai.seasonMentioned(2, ["Ted.Lasso.S02E01.mkv"]), true);
  assert.equal(ai.seasonMentioned(2, ["Сериал [2 сезон]"]), true);
  assert.equal(ai.seasonMentioned(2, ["Сериал, 2-й сезон"]), true);
  assert.equal(ai.seasonMentioned(2, ["Сериал / Сезон: 2 / Серии 1-8"]), true);
  assert.equal(ai.seasonMentioned(2, ["Show Season 02 Complete"]), true);
  assert.equal(ai.seasonMentioned(2, ["Сериал [1-3 сезон]"]), true);
  assert.equal(ai.seasonMentioned(2, ["Show S01-S03"]), true);
  assert.equal(ai.seasonMentioned(4, ["Сериал [1-3 сезон]"]), false);
  assert.equal(ai.seasonMentioned(2, ["Тачки 2 (2011)"]), false);
  assert.equal(ai.seasonMentioned(1, ["Show S10E01.mkv"]), false);
  assert.equal(ai.seasonMentioned(1, ["Show.S01E10.mkv"]), true);
});

test("aititle: validate keeps a good answer and drops a made-up one", () => {
  assert.deepEqual(ai.validate({ base: "Тед Лассо", season: 2 }, show.name, show.files), { base: "Тед Лассо", season: 2 });
  assert.deepEqual(ai.validate({ base: "Тачки 2", season: null }, film.name, film.files), { base: "Тачки 2", season: null });
  // Сезона 3 нет ни в названии, ни в путях
  assert.equal(ai.validate({ base: "Тед Лассо", season: 3 }, show.name, show.files), null);
  // Ни одного слова из раздачи
  assert.equal(ai.validate({ base: "Футбольный тренер", season: 2 }, show.name, show.files), null);
  assert.equal(ai.validate({ base: "", season: null }, film.name, film.files), null);
  assert.equal(ai.validate({ base: "Тачки", season: 1.5 }, film.name, film.files), null);
  assert.equal(ai.validate(null, film.name, film.files), null);
});

test("aititle: the key follows the name and the video files, not the order or samples", () => {
  const same = { ...show, files: [...show.files].reverse().filter(f => !/sample/.test(f.path)) };
  assert.equal(ai.keyOf(show.name, show.files), ai.keyOf(same.name, same.files));
  assert.notEqual(ai.keyOf(show.name, show.files), ai.keyOf(show.name, show.files.slice(1)));
  assert.deepEqual(ai.videoPaths(show.files), ["Ted.Lasso.S02/Ted.Lasso.S02E01.mkv", "Ted.Lasso.S02/Ted.Lasso.S02E02.mkv"]);
});

test("aititle: one call, the right request, then the stored answer", async () => {
  reset();
  replies.push(gemini({ base: "Тед Лассо", season: 2 }));
  const r = await ai.suggest(show, "u_1", NOW);
  assert.equal(r.cached, false);
  assert.deepEqual(r.aiTitle, { base: "Тед Лассо", season: 2, source: "ai", model: "gemini-3.5-flash-lite", at: NOW, status: "suggested" });
  assert.equal(calls.length, 1);
  const c = calls[0];
  assert.match(c.url, /\/v1beta\/models\/gemini-3\.5-flash-lite:generateContent$/);
  assert.equal(c.init.headers["x-goog-api-key"], "test-key-not-real");
  assert.ok(!c.url.includes("test-key"), "ключ не в адресе");
  assert.equal(c.body.generationConfig.temperature, 0);
  assert.equal(c.body.generationConfig.responseMimeType, "application/json");
  assert.deepEqual(Object.keys(c.body.generationConfig.responseSchema.properties), ["base", "season"]);
  const text = c.body.contents[0].parts[0].text;
  assert.ok(text.includes(show.name) && text.includes("Ted.Lasso.S02E02.mkv") && !text.includes("sample") && !text.includes(".srt"));

  // Тот же id — из хранилища, без вызова и без кулдауна
  const again = await ai.suggest(show, "u_2", NOW + 1000);
  assert.equal(again.cached, true);
  assert.equal(calls.length, 1);
  assert.equal(ai.cooldownLeft("u_2", NOW + 1000), 0);
  // Та же раздача, добавленная заново (новый id), — тоже из хранилища
  const readded = await ai.suggest({ ...show, id: 999 }, "u_3", NOW + 2000);
  assert.equal(readded.cached, true);
  assert.equal(calls.length, 1);
  assert.equal(ai.resultFor(999).base, "Тед Лассо");
});

test("aititle: 15 minute cooldown per user", async () => {
  reset();
  replies.push(gemini({ base: "Тед Лассо", season: 2 }));
  await ai.suggest(show, "u_1", NOW);
  await assert.rejects(ai.suggest(film, "u_1", NOW + 5 * MIN), err => {
    assert.equal(err.code, "cooldown");
    assert.equal(err.retryAfterMs, 10 * MIN);
    return true;
  });
  assert.equal(ai.readyAt("u_1", NOW + 5 * MIN), NOW + 15 * MIN);
  // Другой пользователь не ждёт
  replies.push(gemini({ base: "Тачки 2", season: null }));
  assert.equal((await ai.suggest(film, "u_2", NOW + 5 * MIN)).aiTitle.base, "Тачки 2");
  // Кулдаун прошёл
  replies.push(gemini({ base: "Тачки 2", season: null }));
  await ai.suggest({ ...film, id: 7, name: "Другая раздача Тачки 2" }, "u_1", NOW + 15 * MIN);
  assert.equal(calls.length, 3);
});

test("aititle: no cooldown for the library admin, the daily limit still counts", async () => {
  reset();
  for (let i = 0; i < 3; i++) {
    replies.push(gemini({ base: "Тачки 2", season: null }));
    await ai.suggest({ ...film, id: 500 + i, name: film.name + " a" + i }, "u_1", NOW + i, { noCooldown: true });
  }
  assert.equal(ai.cooldownLeft("u_1", NOW + 5), 0);
  assert.equal(ai.readyAt("u_1", NOW + 5), 0);
  await assert.rejects(ai.suggest({ ...film, id: 600, name: film.name + " a9" }, "u_1", NOW + 5, { noCooldown: true }), err => err.code === "daily");
});

test("aititle: 429 and 503 from Gemini, a timeout or no network do not count", async () => {
  reset();
  replies.push({ status: 503, body: { error: { code: 503 } } });
  await assert.rejects(ai.suggest(show, "u_1", NOW), err => err.code === "busy" && err.status === 503);
  assert.equal(ai.cooldownLeft("u_1", NOW), 0);
  replies.push({ status: 429, body: {} });
  await assert.rejects(ai.suggest(show, "u_1", NOW), err => err.code === "busy" && err.status === 429);
  replies.push({ throw: Object.assign(new Error("timeout"), { name: "TimeoutError" }) });
  await assert.rejects(ai.suggest(show, "u_1", NOW), err => err.code === "busy");
  assert.equal(ai.resultFor(show.id), null);
  replies.push(gemini({ base: "Тед Лассо", season: 2 }));
  assert.equal((await ai.suggest(show, "u_1", NOW)).aiTitle.status, "suggested");
  const s = ai.stats(NOW);
  assert.equal(s.today.calls, 1);
  assert.equal(s.today.e503, 1);
  assert.equal(s.today.e429, 1);
  assert.equal(s.today.inTok, 120);
  assert.equal(s.today.outTok, 21);
});

test("aititle: other errors count, so a broken key is not hammered", async () => {
  reset();
  replies.push({ status: 400, body: { error: { message: "API key not valid" } } });
  await assert.rejects(ai.suggest(show, "u_1", NOW), err => err.code === "bad");
  assert.ok(ai.cooldownLeft("u_1", NOW) > 0);
  assert.equal(ai.stats(NOW).today.calls, 1);
});

test("aititle: an answer that fails the check is stored as none and not asked again", async () => {
  reset();
  replies.push(gemini({ base: "Тед Лассо", season: 5 }));
  const r = await ai.suggest(show, "u_1", NOW);
  assert.equal(r.aiTitle.status, "none");
  const again = await ai.suggest(show, "u_9", NOW);
  assert.equal(again.cached, true);
  assert.equal(calls.length, 1);
  assert.equal(ai.decide(show.id, true), null);
});

test("aititle: daily limit for the whole server", async () => {
  reset();
  for (let i = 0; i < 3; i++) {
    replies.push(gemini({ base: "Тачки 2", season: null }));
    await ai.suggest({ ...film, id: 300 + i, name: film.name + " v" + i }, "u_" + (10 + i), NOW);
  }
  await assert.rejects(ai.suggest({ ...film, id: 400, name: film.name + " v9" }, "u_99", NOW), err => {
    assert.equal(err.code, "daily");
    assert.equal(err.retryAfterMs, 12 * 60 * MIN);
    return true;
  });
  // Новый день (UTC) — снова можно
  replies.push(gemini({ base: "Тачки 2", season: null }));
  await ai.suggest({ ...film, id: 400, name: film.name + " v9" }, "u_99", NOW + 12 * 60 * MIN);
  assert.equal(calls.length, 4);
});

test("aititle: two requests for one release at once make one call", async () => {
  reset();
  replies.push(gemini({ base: "Тед Лассо", season: 2 }));
  const [a, b] = await Promise.all([ai.suggest(show, "u_1", NOW), ai.suggest(show, "u_1", NOW)]);
  assert.equal(calls.length, 1);
  assert.deepEqual(a, b);
});

test("aititle: owner accepts or rejects, totals follow", async () => {
  reset();
  replies.push(gemini({ base: "Тед Лассо", season: 2 }));
  await ai.suggest(show, "u_1", NOW);
  assert.equal(ai.decide(show.id, true).status, "accepted");
  assert.equal(ai.decide(show.id, false).status, "rejected");
  const s = ai.stats(NOW).totals;
  assert.equal(s.accepted, 0);
  assert.equal(s.rejected, 1);
  assert.equal(ai.decide(12345, true), null);
  ai.dropMagnet(show.id);
  assert.equal(ai.resultFor(show.id), null);
});

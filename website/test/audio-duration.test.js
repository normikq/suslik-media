"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const d = require("../src/services/audio-duration");

const MIN = 60_000;
const audio = ms => ({ durationMs: ms, video: null, audio: [{ index: 0 }] });
const video = ms => ({ durationMs: ms, video: { index: 0 }, audio: [{ index: 1 }] });
const code = (a, v, off) => d.compareDuration(a, v, off)?.code || null;

test("длительность: порог — 60 с или 3 % видео, плюс смещение", () => {
  assert.equal(code(audio(120 * MIN + 30_000), video(120 * MIN)), null);
  assert.equal(code(audio(123 * MIN), video(120 * MIN)), null); // 3 мин < 3,6 мин (3 % от 2 ч)
  assert.equal(code(audio(124 * MIN), video(120 * MIN)), "DURATION_MISMATCH");
  assert.equal(code(audio(22 * MIN + 30_000), video(22 * MIN)), null); // серия: порог 60 с
  assert.equal(code(audio(22 * MIN + 90_000), video(22 * MIN)), "DURATION_MISMATCH");
  assert.equal(code(audio(22 * MIN + 90_000), video(22 * MIN), -40_000), null);
  const e = d.compareDuration(audio(92 * MIN), video(135 * MIN));
  assert.equal(e.code, "DURATION_MISMATCH");
  assert.match(e.message, /1 ч 32 мин.*2 ч 15 мин/);
});

test("длительность: PAL-ускорение в обе стороны", () => {
  const film = 120 * MIN;
  assert.equal(code(audio(Math.round(film * 23.976 / 25)), video(film)), "FRAME_RATE_MISMATCH");
  assert.equal(code(audio(Math.round(film * 25 / 23.976)), video(film)), "FRAME_RATE_MISMATCH");
  assert.equal(code(audio(Math.round(film * 24 / 25)), video(film)), "FRAME_RATE_MISMATCH");
  assert.match(d.compareDuration(audio(Math.round(film * 23.976 / 25)), video(film)).message, /PAL/);
  // Озвучка на 4 % короче, но не ровно PAL — обычное несовпадение
  assert.equal(code(audio(Math.round(film * 0.965)), video(film)), "DURATION_MISMATCH");
});

test("длительность: нет звука, скрытое видео, неизвестная длительность", () => {
  assert.equal(code({ durationMs: MIN, video: null, audio: [] }, video(MIN)), "NO_AUDIO");
  assert.equal(code({ durationMs: MIN, video: { index: 0 }, audio: [{ index: 1 }] }, video(MIN)), "HIDDEN_VIDEO");
  assert.equal(code(audio(0), video(120 * MIN)), null);
  assert.equal(code(audio(MIN), video(0)), null);
});

test("длительность: формат и выборка пар", () => {
  assert.equal(d.formatDuration(92 * MIN), "1 ч 32 мин");
  assert.equal(d.formatDuration(2 * 3600_000), "2 ч");
  assert.equal(d.formatDuration(45 * MIN + 10_000), "45 мин 10 с");
  const pairs = Array.from({ length: 10 }, (_, i) => i);
  assert.deepEqual(d.samplePairs(pairs), [0, 3, 6, 9]);
  assert.deepEqual(d.samplePairs([1, 2]), [1, 2]);
});

test("checkDurations: ошибка, сбой чтения и таймаут не блокируют", async () => {
  const info = { "a/1.mka": audio(92 * MIN), "a/2.mka": audio(120 * MIN), "m.mkv": video(120 * MIN) };
  const linkOf = (kind, path) => (path === "a/bad.mka" ? null : path);
  const probe = async link => {
    if (!link) throw new Error("нет ссылки");
    if (link === "a/slow.mka") return new Promise(() => {});
    return info[link];
  };
  const r = await d.checkDurations([{ videoPath: "m.mkv", audioPath: "a/1.mka" }, { videoPath: "m.mkv", audioPath: "a/2.mka" }], linkOf, { probe });
  assert.deepEqual(r.errors.map(e => [e.code, e.path]), [["DURATION_MISMATCH", "a/1.mka"]]);
  assert.deepEqual(r.checked.map(c => c.audioPath), ["a/2.mka"]);

  const w = await d.checkDurations([{ videoPath: "m.mkv", audioPath: "a/bad.mka" }, { videoPath: "m.mkv", audioPath: "a/slow.mka" }], linkOf, { probe, deadlineMs: 50 });
  assert.equal(w.errors.length, 0);
  assert.equal(w.warnings[0].code, "DURATION_UNCHECKED");
  assert.deepEqual(w.warnings[0].paths.sort(), ["a/bad.mka", "a/slow.mka"]);
});

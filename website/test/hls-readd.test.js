"use strict";

// Раздачу удалили и добавили снова (новый ad_id): v1-copy видна, v1 докодируется из нового файла,
// зависшие «encoding» снимаются, кодек версии — из manifest

const os = require("os");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-hls-readd-"));
process.env.SESSION_SECRET = "test-session-secret-do-not-use";
process.env.HLS_ENABLED = "true";

const test = require("node:test");
const assert = require("node:assert/strict");

const hls = require("../src/services/hls");
const store = require("../src/services/hls-store");

let T = Date.parse("2026-11-21T10:00:00Z");
const tick = () => (T += 60_000);
const FP = crypto.createHash("sha256").update("film").digest("hex");
const HASH = "a".repeat(40);
const FILE = "Film/Film.mkv";

const body = {
  fingerprint: FP,
  duration_ms: 6_000_000,
  format: { format_name: "matroska,webm", bit_rate: 6_000_000 },
  streams: [{ codec_type: "video", codec_name: "h264", pix_fmt: "yuv420p", width: 1920, height: 1080, color_transfer: "bt709", keyframe_interval_max_s: 4 }]
};
const manCopy = { video: [{ dir: "src", codec: "avc1.640028", bandwidth: 6_000_000, width: 1920, height: 1080 }], audio: [{ dir: "a0", codec: "mp4a.40.2" }] };
const manV1 = { video: [{ dir: "1080p", codec: "hvc1.1.6.L120.90", bandwidth: 4_000_000, width: 1920, height: 1080 }], audio: [{ dir: "a0", codec: "mp4a.40.2" }] };

function reset() {
  hls._reset();
  store._reset();
}
const watch = ad => hls.enqueue({ ad_id: ad, path: FILE, size: 5000, hash: HASH, priority: 0, canonical_id: null, edition: null }, T);
function work() {
  const j = hls.next(tick());
  return j ? { j, r: hls.handleProbe(j.job_id, body, tick()) } : null;
}
const done = (w, manifest) => hls.done({ hls_id: w.r.hls_id, profile: w.r.profile, job_id: w.j.job_id, manifest }, tick());
const v1Jobs = () => [...store.allJobs().values()].filter(j => !store.isAudioJob(j) && j.profile === "v1");

test("v1 после v1-copy ставится на новую раздачу, а не на удалённую", () => {
  reset();
  watch("100");
  const w = work();
  hls.forgetMagnet("100"); // удалили во время кодирования v1-copy
  assert.equal(store.getAsset(w.r.hls_id), null, "пустой ассет с зависшей v1-copy убран");

  watch("200");
  const w2 = work();
  assert.equal(w2.r.profile, "v1-copy");
  done(w2, manCopy);
  assert.equal(store.getAsset(w2.r.hls_id).source.ad_id, "200");
  assert.deepEqual(v1Jobs().map(j => j.ad_id), ["200"]);
  assert.equal(hls.statusFor("200", FILE, T).status, "ready");
});

test("удалили во время кодирования v1: после повторного добавления v1 ставится снова", () => {
  reset();
  watch("100");
  done(work(), manCopy);
  const wv1 = work();
  assert.equal(wv1.r.profile, "v1");
  hls.forgetMagnet("100");
  const a = store.getAsset(wv1.r.hls_id);
  assert.equal(a.profiles.v1, undefined, "зависшая v1 «encoding» снята");
  assert.equal(a.profiles["v1-copy"].status, "ready");

  const r = watch("200");
  assert.equal(r.skipped, "ready");
  assert.deepEqual(v1Jobs().map(j => j.ad_id), ["200"]);
  const w = work();
  assert.equal(w.r.profile, "v1");
  done(w, manV1);
  assert.equal(hls.statusFor("200", FILE, T).profile, "v1");
});

test("новый ad_id без hash: совпал отпечаток — алиас и v1 из этого файла", () => {
  reset();
  watch("100");
  done(work(), manCopy);
  work(); // v1 взят воркером
  hls.forgetMagnet("100");
  hls.enqueue({ ad_id: "300", path: FILE, size: 5000, hash: null, priority: 0, canonical_id: null, edition: null }, T);
  const w = work();
  assert.equal(w.r.action, "alias");
  assert.deepEqual(v1Jobs().map(j => j.ad_id), ["300"]);
});

test("v1 не ставится второй раз, пока источник ассета жив", () => {
  reset();
  watch("100");
  done(work(), manCopy);
  assert.equal(v1Jobs().length, 1);
  watch("100");
  assert.equal(v1Jobs().length, 1);
});

test("videoCodecOf: кодек версии по manifest", () => {
  reset();
  watch("100");
  const w = work();
  done(w, manCopy);
  assert.equal(hls.videoCodecOf(w.r.hls_id, "v1-copy"), "h264");
  assert.equal(hls.videoCodecOf(w.r.hls_id, "v1"), "");
  done({ j: { job_id: "x" }, r: { hls_id: w.r.hls_id, profile: "v1" } }, manV1);
  assert.equal(hls.videoCodecOf(w.r.hls_id, "v1"), "hevc");
});

test("decideProfile: v1-copy — только до 8 Мбит", () => {
  const at = br => hls.decideProfile({ ...body, format: { bit_rate: br } }, 5000);
  assert.equal(at(8_000_000), "v1-copy");
  assert.equal(at(8_000_001), "v1");
  assert.equal(at(12_000_000), "v1");
});

test("уже сломанный ассет: упавшее v1 на удалённую раздачу заменяется заданием на новую", () => {
  reset();
  watch("100");
  const w = work();
  done(w, manCopy);
  const old = v1Jobs()[0];
  hls.forgetMagnet("100");
  // старое состояние (до исправления): v1 стоит на удалённой раздаче и упала
  store.putJob(old.job_id, { ...old, state: "failed", attempts: 3 });
  store.setAlias("200", FILE, w.r.hls_id, 0);
  watch("200");
  assert.deepEqual(v1Jobs().map(j => [j.ad_id, j.state]), [["200", "queued"]]);
});

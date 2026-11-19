"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-hls-test-"));
process.env.SESSION_SECRET = "test-session-secret-do-not-use";
process.env.HLS_ENABLED = "true";

const test = require("node:test");
const assert = require("node:assert/strict");

const hls = require("../src/services/hls");
const store = require("../src/services/hls-store");
const vectors = require("../../docs/hls-fixtures/vectors.json");

const NOW = Date.parse("2026-11-19T12:00:00Z");
const sec = ms => Math.floor(ms / 1000);

function fp(n) {
  return crypto.createHash("sha256").update(String(n)).digest("hex");
}

function probeBody(
  fingerprint,
  duration_ms,
  video = {
    codec_name: "hevc",
    pix_fmt: "yuv420p10le",
    width: 1920,
    height: 800,
    color_transfer: "smpte2084",
    keyframe_interval_max_s: 4
  }
) {
  return {
    fingerprint,
    duration_ms,
    format: { format_name: "matroska,webm", bit_rate: 8000000 },
    streams: [{ index: 0, codec_type: "video", ...video }]
  };
}

const copyVideo = {
  codec_name: "h264",
  pix_fmt: "yuv420p",
  width: 1920,
  height: 1080,
  color_transfer: "bt709",
  keyframe_interval_max_s: 4
};

function add(ad_id, filePath, opts = {}) {
  return hls.enqueue(
    { ad_id, path: filePath, size: 5000, priority: 2, canonical_id: null, edition: null, ...opts },
    NOW
  ).job;
}

function take() {
  return hls.next(NOW);
}

function encodeDone(job, body) {
  const r = hls.handleProbe(job.job_id, body, NOW);
  assert.equal(r.action, "encode");
  hls.done(
    {
      job_id: job.job_id,
      hls_id: r.hls_id,
      profile: r.profile,
      manifest_sha256: "a".repeat(64),
      bytes: 1000
    },
    NOW
  );
  return r;
}

test.beforeEach(() => {
  hls._reset();
});

test("1. Векторы: hls_id, signCdn, signMaster и signHlsUrl", () => {
  for (const v of vectors.hls_id) {
    if (v.infohash) {
      assert.equal(hls.hlsId({ hash: v.infohash, path: v.path, size: v.size }), v.hls_id);
    } else {
      assert.equal(hls.hlsId({ adId: v.ad_id, path: v.path, size: v.size }), v.hls_id);
    }
  }

  for (const v of vectors.sign) {
    assert.equal(hls.signCdn(v.exp, v.hls_id, v.secret), v.sig);
  }

  for (const v of vectors.master_sign) {
    assert.equal(hls.signMaster(v.hls_id, v.exp, v.session_secret), v.sig);
  }

  const s = vectors.sign[0];
  const url = hls.signHlsUrl(s.hls_id, "v1", "1080p/index.m3u8", s.exp, {
    cdnUrl: "https://cdn.example.test/",
    secret: s.secret
  });
  assert.equal(
    url,
    "https://cdn.example.test/hls/" + s.sig + "/" + s.exp + "/" + s.hls_id + "/v1/1080p/index.m3u8"
  );
});

test("2. verifyMaster: проверка подписи и срока действия", () => {
  const id = "0".repeat(16);
  const expOk = sec(NOW) + 3600;
  const sigOk = hls.signMaster(id, expOk);
  assert.equal(hls.verifyMaster(id, expOk, sigOk, NOW), "ok");
  assert.equal(hls.verifyMaster(id, expOk, "0".repeat(64), NOW), "invalid");

  const expExpired = sec(NOW) - 10;
  const sigExpired = hls.signMaster(id, expExpired);
  assert.equal(hls.verifyMaster(id, expExpired, sigExpired, NOW), "expired");

  const expFar = sec(NOW) + 12 * 3600 + 120;
  const sigFar = hls.signMaster(id, expFar);
  assert.equal(hls.verifyMaster(id, expFar, sigFar, NOW), "invalid");

  assert.equal(hls.verifyMaster(id, expOk, "zz", NOW), "invalid");
});

test("3. episodeOf: извлечение номера серии из пути", () => {
  assert.equal(hls.episodeOf("Show.S01/Show.S01E02.mkv"), "S01E02");
  assert.equal(hls.episodeOf("show.1x05.mkv"), "S01E05");
  assert.equal(hls.episodeOf("Show/Season 2/Episode 7.mkv"), "S02E07");
  assert.equal(hls.episodeOf("Сериал/1 сезон 3 серия.mkv"), "S01E03");
  assert.equal(hls.episodeOf("Movie.1920x1080.mkv"), null);
  assert.equal(hls.episodeOf("Movie.2010.mkv"), null);
});

test("4. shiftMs и fingerprintMatch", () => {
  assert.equal(hls.shiftMs(7200000, 7201000), 0);
  assert.equal(hls.shiftMs(7202350, 7200000), 2350);
  assert.equal(hls.shiftMs(7200000, 7330000), null);
  assert.equal(hls.shiftMs(null, 1), null);

  assert.equal(hls.fingerprintMatch(fp(1), fp(1).toUpperCase()), true);
  assert.equal(hls.fingerprintMatch(fp(1), fp(2)), false);
});

test("5. computeDirectOk: проверка прямого воспроизведения", () => {
  assert.deepEqual(hls.computeDirectOk("a/Clip.mp4", { t: [], meta: { codec: "h264" } }), {
    direct_ok: true,
    direct: { ext: "mp4", vcodec: "h264", acodec: "aac" }
  });

  const dts = hls.computeDirectOk("a/M.mkv", { t: ["DTS-HD MA"], meta: { codec: "h264" } });
  assert.equal(dts.direct_ok, false);
  assert.equal(dts.direct.acodec, "dts");

  assert.equal(hls.computeDirectOk("a/M.mp4", { t: [], meta: { codec: "hevc" } }).direct_ok, false);

  assert.deepEqual(hls.computeDirectOk("a/M.mp4", null), {
    direct_ok: true,
    direct: { ext: "mp4", vcodec: "", acodec: "" }
  });

  assert.equal(hls.computeDirectOk("a/M.mkv", null).direct_ok, false);
  assert.equal(hls.computeDirectOk("a/M.webm", { t: [], meta: { codec: "vp9" } }).direct_ok, true);
});

test("6. decideProfile: выбор профиля кодирования", () => {
  const probeReq = require("../../docs/hls-fixtures/probe-request.json");
  assert.equal(hls.decideProfile(probeReq, 8589934592), "v1");
  assert.equal(hls.decideProfile(probeBody(fp(1), 7200000, copyVideo), 5000), "v1-copy");
  assert.equal(
    hls.decideProfile(probeBody(fp(1), 7200000, { ...copyVideo, keyframe_interval_max_s: 12 }), 5000),
    "v1"
  );
  assert.equal(
    hls.decideProfile(probeBody(fp(1), 7200000, { ...copyVideo, width: 3840, height: 2160 }), 5000),
    "v1"
  );
});

test("7. Тот же файл с новым adId получает alias", () => {
  const a = add("100", "M/M.mkv");
  take();
  const r = encodeDone(a, probeBody(fp(1), 7200000));
  assert.equal(r.profile, "v1");
  assert.equal(r.hls_id, a.hls_id);

  const b = add("200", "M/M.mkv");
  assert.notEqual(b.hls_id, a.hls_id);
  take();
  const p = hls.handleProbe(b.job_id, probeBody(fp(1), 7200000), NOW);
  assert.deepEqual(p, { action: "alias", hls_id: a.hls_id, shift_ms: 0 });
  assert.equal(store.getJob(b.job_id), null);

  const st = hls.statusFor("200", "M/M.mkv");
  assert.equal(st.status, "ready");
  assert.equal(st.hls_id, a.hls_id);
  assert.equal(st.profile, "v1");
  assert.equal(st.shift_ms, 0);
});

test("8. Другая раздача того же монтажа получает alias со сдвигом shift_ms", () => {
  const opts = { canonical_id: "m:movie_2010:default", edition: "default" };
  const a = add("100", "A/Movie.mkv", opts);
  take();
  encodeDone(a, probeBody(fp(1), 7200000));

  const b = add("200", "B/Movie.mkv", opts);
  take();
  const p = hls.handleProbe(b.job_id, probeBody(fp(2), 7197650), NOW);
  assert.deepEqual(p, { action: "alias", hls_id: a.hls_id, shift_ms: 2350 });
});

test("9. Режиссёрская версия кодируется отдельно от default", () => {
  const a = add("100", "A/Movie.mkv", { canonical_id: "m:movie_2010:default", edition: "default" });
  take();
  encodeDone(a, probeBody(fp(1), 7200000));

  const b = add("200", "B/Movie.DC.mkv", {
    canonical_id: "m:movie_2010:directors_cut",
    edition: "directors_cut"
  });
  take();
  const p = hls.handleProbe(b.job_id, probeBody(fp(2), 7200000), NOW);
  assert.equal(p.action, "encode");
  assert.equal(p.hls_id, b.hls_id);
});

test("10. Разница длительности > 2 мин — кодируется отдельно", () => {
  const opts = { canonical_id: "m:movie_2010:default", edition: "default" };
  const a = add("100", "A/Movie.mkv", opts);
  take();
  encodeDone(a, probeBody(fp(1), 7200000));

  const b = add("200", "B/Movie.mkv", opts);
  take();
  const p = hls.handleProbe(b.job_id, probeBody(fp(2), 7200000 + 130000), NOW);
  assert.equal(p.action, "encode");
});

test("11. Серии: совпадение по серии и без номера серии", () => {
  const opts = { canonical_id: "s:show_s1:default", edition: "default" };
  const e1 = add("100", "S/Show.S01E01.mkv", opts);
  take();
  encodeDone(e1, probeBody(fp(1), 2700000));

  const e2 = add("100", "S/Show.S01E02.mkv", opts);
  take();
  assert.equal(hls.handleProbe(e2.job_id, probeBody(fp(2), 2700000), NOW).action, "encode");

  const x = add("300", "X/Show.S01E01.1080p.mkv", opts);
  take();
  const px = hls.handleProbe(x.job_id, probeBody(fp(3), 2700000), NOW);
  assert.equal(px.action, "alias");
  assert.equal(px.hls_id, e1.hls_id);

  const n = add("400", "S/Show.mkv", opts);
  take();
  assert.equal(hls.handleProbe(n.job_id, probeBody(fp(4), 2700000), NOW).action, "encode");
});

test("12. Параллельные probe одного отпечатка: wait и последующий alias", () => {
  const j1 = add("1", "M/M.mkv");
  const j2 = add("2", "M/M.mkv");
  const j3 = add("3", "M/M.mkv");
  take();
  take();
  take();

  const p1 = hls.handleProbe(j1.job_id, probeBody(fp(9), 7200000), NOW);
  assert.equal(p1.action, "encode");
  assert.deepEqual(hls.handleProbe(j2.job_id, probeBody(fp(9), 7200000), NOW), {
    action: "wait",
    retry_after_s: 60
  });
  assert.equal(hls.handleProbe(j3.job_id, probeBody(fp(9), 7200000), NOW).action, "wait");

  hls.done(
    {
      job_id: j1.job_id,
      hls_id: p1.hls_id,
      profile: p1.profile,
      manifest_sha256: "a".repeat(64),
      bytes: 1000
    },
    NOW
  );

  const nextJob = hls.next(NOW + 61000);
  assert.ok(nextJob.job_id === j2.job_id || nextJob.job_id === j3.job_id);
  const pNext = hls.handleProbe(nextJob.job_id, probeBody(fp(9), 7200000), NOW + 61000);
  assert.equal(pNext.action, "alias");
  assert.equal(pNext.hls_id, j1.hls_id);
});

test("13. Очередь: приоритеты и дедупликация", () => {
  const p3 = add("1", "a.mkv", { priority: 3 });
  const p0 = add("2", "b.mkv", { priority: 0 });
  const p2 = add("3", "c.mkv", { priority: 2 });

  assert.equal(take().job_id, p0.job_id);
  assert.equal(take().job_id, p2.job_id);
  assert.equal(take().job_id, p3.job_id);
  assert.equal(take(), null);
});

test("13б. Дедупликация в очереди по ad_id и по hash", () => {
  const j = add("1", "a.mkv", { priority: 3 });
  const r = hls.enqueue({ ad_id: "1", path: "a.mkv", size: 5000, priority: 1, canonical_id: null }, NOW);
  assert.equal(r.deduped, true);
  assert.equal(r.job.job_id, j.job_id);
  assert.equal(r.job.priority, 1);
  assert.equal(store.allJobs().size, 1);

  hls._reset();
  const h = "0123456789abcdef0123456789abcdef01234567";
  add("1", "a.mkv", { hash: h });
  const r2 = hls.enqueue(
    { ad_id: "2", path: "a.mkv", size: 5000, hash: h, priority: 2, canonical_id: null },
    NOW
  );
  assert.equal(r2.deduped, true);
  assert.equal(store.allJobs().size, 1);
});

test("14. Аренда задания: истечение и продление через progress", () => {
  const j = add("1", "a.mkv");
  const n = take();
  assert.equal(n.job_id, j.job_id);
  assert.equal(n.leased_until, sec(NOW) + hls.LEASE_S);
  assert.equal(store.getJob(j.job_id).state, "running");
  assert.equal(take(), null);

  const t = NOW + (hls.LEASE_S + 1) * 1000;
  assert.equal(hls.next(t).job_id, j.job_id);

  const prog = hls.progress({ job_id: j.job_id, progress: 0.5, eta_s: 100 }, t + 1000);
  assert.equal(prog.leased_until, sec(t + 1000) + hls.LEASE_S);
});

test("15. Переход v1-copy -> v1 -> pending_delete -> evicted", () => {
  const a = add("100", "M/M.mp4");
  take();
  const r = encodeDone(a, probeBody(fp(1), 7200000, copyVideo));
  assert.equal(r.profile, "v1-copy");
  assert.equal(store.getAsset(a.hls_id).active_profile, "v1-copy");

  const st1 = hls.statusFor("100", "M/M.mp4");
  assert.equal(st1.status, "ready");
  assert.equal(st1.profile, "v1-copy");

  assert.equal(store.allJobs().size, 1);
  const queuedV1 = [...store.allJobs().values()][0];
  assert.equal(queuedV1.profile, "v1");
  assert.equal(queuedV1.priority, 4);
  assert.equal(queuedV1.hls_id, a.hls_id);

  const v = take();
  assert.deepEqual(hls.handleProbe(v.job_id, probeBody(fp(1), 7200000, copyVideo), NOW), {
    action: "encode",
    hls_id: a.hls_id,
    profile: "v1"
  });

  const doneV1 = hls.done(
    {
      job_id: v.job_id,
      hls_id: a.hls_id,
      profile: "v1",
      manifest_sha256: "b".repeat(64),
      bytes: 2000
    },
    NOW
  );
  assert.equal(doneV1.active_profile, "v1");

  assert.deepEqual(hls.pendingDeletes(), { items: [{ hls_id: a.hls_id, profile: "v1-copy" }] });
  assert.equal(hls.evicted([{ hls_id: a.hls_id, profile: "v1-copy", last_access: 1 }]), 1);
  assert.deepEqual(hls.pendingDeletes(), { items: [] });

  const st2 = hls.statusFor("100", "M/M.mp4");
  assert.equal(st2.status, "ready");
  assert.equal(st2.profile, "v1");
  assert.equal(store.getAsset(a.hls_id).profiles["v1-copy"], undefined);

  hls.evicted([{ hls_id: a.hls_id, profile: "v1", last_access: 1 }]);
  assert.equal(store.getAsset(a.hls_id), null);
  assert.equal(hls.statusFor("100", "M/M.mp4").status, "none");
});

test("16. failed: повторная попытка и окончательная ошибка", () => {
  const j = add("1", "a.mkv");
  take();
  assert.equal(hls.handleProbe(j.job_id, probeBody(fp(1), 7200000), NOW).action, "encode");

  hls.failed({ job_id: j.job_id, stage: "encode", error: "x", retryable: true }, NOW);
  assert.equal(store.getJob(j.job_id).state, "queued");
  assert.equal(store.getJob(j.job_id).attempts, 1);

  hls.next(NOW + 301000);
  hls.failed({ job_id: j.job_id, stage: "encode", error: "x", retryable: false }, NOW + 301000);
  assert.equal(store.getJob(j.job_id).state, "failed");
  assert.equal(hls.statusFor("1", "a.mkv").status, "none");

  const re = hls.enqueue(
    { ad_id: "1", path: "a.mkv", size: 5000, priority: 0, canonical_id: null },
    NOW + 301000
  );
  assert.equal(re.job.state, "queued");
  assert.equal(re.job.priority, 0);
});

test("17. forget: удаление привязки раздачи сохраняет ассет для будущего alias", () => {
  const a = add("100", "M/M.mkv");
  take();
  encodeDone(a, probeBody(fp(1), 7200000));
  const u = add("100", "M/other.mkv");

  require("../src/services/cleanup").forget("100");

  assert.equal(hls.statusFor("100", "M/M.mkv").status, "none");
  assert.notEqual(store.getAsset(a.hls_id), null);
  assert.equal(store.getJob(u.job_id), null);

  const b = add("200", "M/M.mkv");
  take();
  assert.deepEqual(hls.handleProbe(b.job_id, probeBody(fp(1), 7200000), NOW), {
    action: "alias",
    hls_id: a.hls_id,
    shift_ms: 0
  });
});

test("18. onLibrary: постановка в очередь только готовых видеофайлов", () => {
  const magnet = { id: 555, hash: null, statusCode: 1, files: [] };
  assert.equal(hls.onLibrary([magnet], NOW), 0);

  const readyMagnet = {
    id: 555,
    hash: null,
    statusCode: 4,
    files: [
      { path: "M/M.mkv", type: "file", size: 100 },
      { path: "M/sample.mkv", type: "file", size: 1 },
      { path: "M/a.srt", type: "file", size: 1 }
    ]
  };
  assert.equal(hls.onLibrary([readyMagnet], NOW), 1);
  assert.equal(store.allJobs().size, 1);
  const job = [...store.allJobs().values()][0];
  assert.equal(job.path, "M/M.mkv");
  assert.equal(job.priority, 2);

  assert.equal(hls.onLibrary([readyMagnet], NOW), 0);
});

test("19. cdnStats и getCdnStats", () => {
  const body = require("../../docs/hls-fixtures/cdn-stats-request.json");
  hls.cdnStats(body, NOW);
  const stats = hls.getCdnStats();
  assert.equal(stats.disk_total, body.disk_total);
  assert.equal(stats.disk_used, body.disk_used);
  assert.equal(stats.assets, body.assets);
  assert.deepEqual(stats.profiles, body.profiles);
  assert.equal(stats.at, body.at);
});

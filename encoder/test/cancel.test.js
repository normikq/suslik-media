'use strict';

// Тесты отмены задания на сайте (docs/HLS.md §19):
// Часть A — юнит-тесты API (JobCancelled, cancelCode) и processJob (без ffmpeg).
// Часть B — интеграционные тесты отмены во время кодирования видео и звука (с ffmpeg).

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const { createApi, ApiError, JobCancelled, cancelCode } = require('../src/api');
const { processJob } = require('../src/job');
const { createStub } = require('../stub/server');
const { runLoop } = require('../src/worker');
const { loadConfig } = require('../src/config');
const { createLogger } = require('../src/log');

// ── Часть A: юнит-тесты ───────────────────────────────────────────────────────────────────────────────────────────────

test('api: 410 cancelled и 404 unknown_job → JobCancelled, прочее → ApiError', async () => {
  let reply = { status: 200, body: { ok: true } };
  const server = http.createServer((req, res) => {
    req.resume();
    req.on('end', () => {
      const isObj = typeof reply.body === 'object' && reply.body !== null;
      res.writeHead(reply.status, { 'Content-Type': isObj ? 'application/json' : 'text/plain' });
      res.end(isObj ? JSON.stringify(reply.body) : String(reply.body));
    });
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  const api = createApi({ siteUrl: `http://127.0.0.1:${port}`, token: 't', timeoutMs: 5000 });

  try {
    reply = { status: 410, body: { code: 'cancelled' } };
    await assert.rejects(
      () => api.progress({ job_id: 'j' }),
      (err) => err instanceof JobCancelled && err.code === 'cancelled' && err.status === 410,
    );

    reply = { status: 404, body: { code: 'unknown_job' } };
    await assert.rejects(
      () => api.progress({ job_id: 'j' }),
      (err) => err instanceof JobCancelled && err.code === 'unknown_job' && err.status === 404,
    );

    reply = { status: 404, body: { error: 'not found' } };
    await assert.rejects(
      () => api.progress({ job_id: 'j' }),
      (err) => err instanceof ApiError && !(err instanceof JobCancelled) && err.status === 404,
    );

    reply = { status: 404, body: 'Not Found' };
    await assert.rejects(
      () => api.progress({ job_id: 'j' }),
      (err) => err instanceof ApiError && !(err instanceof JobCancelled) && err.status === 404,
    );

    reply = { status: 410, body: { code: 'other' } };
    await assert.rejects(
      () => api.progress({ job_id: 'j' }),
      (err) => err instanceof ApiError && !(err instanceof JobCancelled) && err.status === 410,
    );

    reply = { status: 500, body: { code: 'cancelled' } };
    await assert.rejects(
      () => api.progress({ job_id: 'j' }),
      (err) => err instanceof ApiError && !(err instanceof JobCancelled) && err.status === 500,
    );

    reply = { status: 200, body: { ok: true } };
    assert.deepEqual(await api.progress({ job_id: 'j' }), { ok: true });

    // Остальные методы задания тоже бросают JobCancelled при 410 cancelled
    reply = { status: 410, body: { code: 'cancelled' } };
    for (const fn of [
      () => api.probe({ job_id: 'j' }),
      () => api.done({ job_id: 'j' }),
      () => api.audioDone({ job_id: 'j' }),
      () => api.failed({ job_id: 'j' }),
    ]) {
      await assert.rejects(
        fn,
        (err) => err instanceof JobCancelled && err.code === 'cancelled' && err.status === 410,
      );
    }

    // next() никогда не бросает JobCancelled
    await assert.rejects(
      () => api.next(),
      (err) => err instanceof ApiError && !(err instanceof JobCancelled) && err.status === 410,
    );
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('cancelCode: разбор статуса и тела ответа', () => {
  assert.equal(cancelCode(410, '{"code":"cancelled"}'), 'cancelled');
  assert.equal(cancelCode(404, '{"code":"unknown_job"}'), 'unknown_job');
  assert.equal(cancelCode(404, 'x'), null);
  assert.equal(cancelCode(500, '{"code":"cancelled"}'), null);
  assert.equal(cancelCode(410, '{"code":"unknown_job"}'), null);
});

test('processJob: снято на первом progress → cancelled без failed, папка пуста', async () => {
  const workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sm-cancel-unit-'));
  const cfg = {
    workDir,
    progressSec: 10,
    maxSourceBytes: 1e12,
    ffprobe: 'ffprobe',
    ffmpeg: 'ffmpeg',
    reconnectDelayMax: 30,
  };
  const failed = [];
  let cancelErr = new JobCancelled('x', 410, 'cancelled');
  const api = {
    progress: async () => { throw cancelErr; },
    failed: async (b) => { failed.push(b); },
    probe: async () => { throw new Error('не должно вызываться'); },
    done: async () => { throw new Error('не должно вызываться'); },
    audioDone: async () => { throw new Error('не должно вызываться'); },
  };
  const lines = [];
  const log = { info: (...a) => lines.push(a.join(' ')), warn() {}, error() {} };

  try {
    const videoJob = {
      job_id: 'j_c1',
      mirror_url: 'http://127.0.0.1:1/x',
      size: 1000,
      path: 'a.mkv',
      priority: 0,
    };
    const r1 = await processJob(videoJob, { cfg, api, log });
    assert.equal(r1.result, 'cancelled');
    assert.equal(r1.code, 'cancelled');
    assert.deepEqual(failed, []);
    assert.deepEqual(fs.readdirSync(cfg.workDir), []);
    assert.ok(lines.some((l) => /\[job\] j_c1 отменено на сайте/.test(l)));

    cancelErr = new JobCancelled('x', 404, 'unknown_job');
    const audioJob = {
      job_id: 'j_c2',
      type: 'audio',
      hls_id: '0123456789abcdef',
      profile: 'v1',
      duration_ms: 10000,
      tracks: [{ key: 'custom:1', dir: 'x0', mirror_url: 'http://127.0.0.1:1/a.mka', offset_ms: 0 }],
    };
    const r2 = await processJob(audioJob, { cfg, api, log });
    assert.equal(r2.result, 'cancelled');
    assert.equal(r2.code, 'unknown_job');
    assert.equal(r2.type, 'audio');
    assert.deepEqual(failed, []);
  } finally {
    fs.rmSync(workDir, { recursive: true, force: true });
  }
});

// ── Часть B: интеграция с ffmpeg и заглушкой сервера ──────────────────────────────────────────────────────────────────

const FFMPEG = process.env.SM_FFMPEG || 'ffmpeg';
const FFPROBE = process.env.SM_FFPROBE || 'ffprobe';

function sh(cmd, args) {
  const r = spawnSync(cmd, args, { encoding: 'utf8', windowsHide: true, maxBuffer: 64 * 1024 * 1024 });
  return { code: r.status ?? -1, out: r.stdout || '', err: r.stderr || '' };
}

const haveFfmpeg = sh(FFMPEG, ['-hide_banner', '-version']).code === 0 && sh(FFPROBE, ['-hide_banner', '-version']).code === 0;

test('остановка воркера — по-прежнему failed retryable=true', { skip: !haveFfmpeg && 'ffmpeg/ffprobe не найдены' }, async () => {
  const workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sm-cancel-abort-'));
  const failed = [];
  const api = {
    progress: async () => {},
    failed: async (b) => { failed.push(b); },
  };
  const log = { info() {}, warn() {}, error() {} };
  const cfg = {
    workDir,
    progressSec: 10,
    maxSourceBytes: 1e12,
    ffprobe: FFPROBE,
    ffmpeg: FFMPEG,
    reconnectDelayMax: 30,
  };
  const ac = new AbortController();
  ac.abort();
  const job = {
    job_id: 'j_c3',
    type: 'audio',
    hls_id: '0123456789abcdef',
    profile: 'v1',
    duration_ms: 10000,
    tracks: [{ key: 'custom:1', dir: 'x0', mirror_url: 'http://127.0.0.1:1/a.mka', offset_ms: 0 }],
  };

  try {
    const r = await processJob(job, { cfg, api, log, signal: ac.signal });
    assert.equal(r.result, 'aborted');
    assert.equal(failed.length, 1);
    assert.equal(failed[0].retryable, true);
  } finally {
    fs.rmSync(workDir, { recursive: true, force: true });
  }
});

async function waitFor(cond, timeoutMs, msg) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (cond()) return;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error(msg);
}

function lastEncode(state) {
  const entries = state.progress.filter((p) => p.stage === 'encode');
  return entries.length > 0 ? entries[entries.length - 1].progress : 0;
}

test('интеграция: отмена на сайте во время кодирования', { skip: !haveFfmpeg && 'ffmpeg/ffprobe не найдены' }, async (t) => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'sm-cancel-'));
  t.after(() => {
    if (!process.env.SM_TEST_KEEP) fs.rmSync(tmp, { recursive: true, force: true });
    else t.diagnostic(`файлы: ${tmp}`);
  });

  let seq = 0;

  async function runCancel({ mediaFile, profile, audio, env }) {
    const stub = await createStub({ mediaFile, profile, jobs: 1, token: 'test-token', audio });
    const id = ++seq;
    const cdn = path.join(tmp, `cdn-${id}`);
    const cfg = loadConfig({
      SM_SITE_URL: stub.url,
      HLS_WORKER_TOKEN: 'test-token',
      SM_FFMPEG: FFMPEG,
      SM_FFPROBE: FFPROBE,
      SM_ENCODER: 'x265',
      SM_UPLOAD_MODE: 'local',
      SM_UPLOAD_LOCAL_DIR: cdn,
      SM_WORK_DIR: path.join(tmp, `work-${id}`),
      SM_PROGRESS_S: '1',
      SM_ERROR_S: '30',
      SM_IDLE_S: '30',
      ...env,
    });
    const ac = new AbortController();
    const results = [];
    let tResult = 0;
    let tCancel = 0;
    const loop = runLoop({
      cfg,
      api: createApi(cfg),
      log: createLogger({ dir: path.join(tmp, 'logs'), console: !!process.env.SM_TEST_VERBOSE }),
      signal: ac.signal,
      ffmpegVersion: 'test',
      onResult: (r) => {
        results.push(r);
        tResult = Date.now();
      },
    });

    try {
      await waitFor(
        () => stub.state.progress.some((p) => p.stage === 'encode' && p.progress > 0),
        180000,
        'кодирование не началось',
      );
      tCancel = Date.now();
      const res = await fetch(`${stub.url}/stub/cancel`);
      assert.equal(res.status, 200);
      await waitFor(() => results.length >= 1 && stub.state.next >= 2, 30000, 'воркер не взял следующее задание');
    } finally {
      ac.abort();
      await loop;
      await stub.close();
    }

    return { stub, results, elapsedMs: tResult - tCancel, workDir: cfg.workDir, cdn };
  }

  await t.test('видео', async (st) => {
    const mediaFile = path.join(tmp, 'long.mkv');
    const gen = sh(FFMPEG, [
      '-y', '-hide_banner', '-loglevel', 'error',
      '-f', 'lavfi', '-i', 'testsrc2=size=1280x720:rate=24:duration=120',
      '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000:duration=120',
      '-map', '0', '-map', '1',
      '-c:v', 'libx264', '-preset', 'ultrafast', '-g', '48', '-pix_fmt', 'yuv420p',
      '-c:a', 'aac',
      mediaFile,
    ]);
    assert.equal(gen.code, 0, gen.err);

    const { stub, results, elapsedMs, workDir, cdn } = await runCancel({
      mediaFile,
      profile: 'v1',
      env: { SM_ENCODER: 'x265', SM_SW_PRESET: 'medium' },
    });

    assert.equal(results[0].result, 'cancelled', JSON.stringify(results[0]));
    assert.equal(results[0].stage, 'encode');
    assert.ok(elapsedMs < 5000, `слишком долгая остановка: ${elapsedMs} мс`);
    const p = lastEncode(stub.state);
    assert.ok(p < 0.9, `progress слишком велик: ${p}`);
    assert.deepEqual(stub.state.failed, []);
    assert.deepEqual(stub.state.done, []);
    assert.ok(stub.state.cancelHits >= 1);
    assert.ok(stub.state.next >= 2);
    assert.deepEqual(fs.readdirSync(workDir).filter((e) => e !== '.lock'), []);
    assert.equal(fs.existsSync(path.join(cdn, stub.hlsId)), false);
    st.diagnostic(`остановлено через ${elapsedMs} мс, progress ${p}`);
  });

  await t.test('звук', async () => {
    const mediaFile = path.join(tmp, 'long.mka');
    const gen = sh(FFMPEG, [
      '-y', '-hide_banner', '-loglevel', 'error',
      '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000:duration=60',
      '-c:a', 'ac3', '-ac', '6',
      mediaFile,
    ]);
    assert.equal(gen.code, 0, gen.err);

    const { stub, results, elapsedMs, workDir, cdn } = await runCancel({
      mediaFile,
      profile: 'audio',
      audio: { duration_ms: 3600000, tracks: [{ offset_ms: 0 }, { offset_ms: 0 }, { offset_ms: 0 }] },
    });

    assert.equal(results[0].result, 'cancelled', JSON.stringify(results[0]));
    assert.equal(results[0].type, 'audio');
    assert.equal(results[0].stage, 'encode');
    assert.ok(elapsedMs < 5000, `слишком долгая остановка: ${elapsedMs} мс`);
    const p = lastEncode(stub.state);
    assert.ok(p < 0.9, `progress слишком велик: ${p}`);
    assert.deepEqual(stub.state.failed, []);
    assert.deepEqual(stub.state.audioDone, []);
    assert.ok(stub.state.next >= 2);
    assert.deepEqual(fs.readdirSync(workDir).filter((e) => e !== '.lock'), []);
    assert.equal(fs.existsSync(path.join(cdn, stub.hlsId)), false);
  });
});

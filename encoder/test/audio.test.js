'use strict';

// Тесты задания «звук» (audio):
// Часть A — юнит-тесты buildAudioArgs, checkAudioJob, classifyFfmpegError и формата audio-done (без ffmpeg).
// Часть B — интеграционные тесты через заглушку сервера на синтетическом 5.1 AC3 (.mka).

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const { loadConfig } = require('../src/config');
const { createApi } = require('../src/api');
const { runLoop } = require('../src/worker');
const { createLogger } = require('../src/log');
const { parseMediaPlaylist, validateByteRangePlaylist, totalDuration } = require('../src/playlist');
const { dirBytes } = require('../src/manifest');
const { createStub } = require('../stub/server');
const { buildAudioArgs, checkAudioJob, classifyFfmpegError } = require('../src/audiojob');
const { audioTmpName, checkAnswer, fill, rsyncTarget } = require('../src/upload');
const { processJob } = require('../src/job');

const FIXTURES = path.resolve(__dirname, '..', '..', 'docs', 'hls-fixtures');
const fixture = (name) => JSON.parse(fs.readFileSync(path.join(FIXTURES, name), 'utf8'));

const after = (args, flag) => args[args.indexOf(flag) + 1];

// ── Часть A: юнит-тесты ───────────────────────────────────────────────────────────────────────────────────────────────

test('buildAudioArgs: offset_ms (-2000, +1500, 0, undefined) и общие флаги', () => {
  const cfg = { reconnectDelayMax: 30 };
  const durationMs = 36000;
  const dir = path.join('/w', 'v1', 'x0');
  const mirrorUrl = 'https://site.example.org/api/file/proxy/a.mka?link=1';

  const assertCommon = (args) => {
    assert.ok(args.join(' ').includes('-reconnect 1 -reconnect_streamed 1 -reconnect_on_network_error 1'));
    assert.equal(after(args, '-t'), '36.000');
    assert.equal(after(args, '-ac'), '2');
    assert.equal(after(args, '-b:a'), '128000');
    assert.equal(after(args, '-c:a'), 'aac');
    assert.equal(after(args, '-profile:a'), 'aac_low');
    assert.equal(after(args, '-hls_time'), '6');
    assert.equal(after(args, '-hls_flags'), 'independent_segments+single_file');
    assert.equal(after(args, '-hls_playlist_type'), 'vod');
    assert.equal(after(args, '-hls_segment_type'), 'fmp4');
    assert.equal(after(args, '-map'), '0:a:0');
    assert.equal(after(args, '-hls_segment_filename'), path.join(dir, 'audio.m4s'));
    assert.equal(args[args.length - 1], path.join(dir, 'index.m3u8'));
  };

  // 1) offset_ms = -2000: -ss 2.000 до -i, без adelay
  const neg = buildAudioArgs(cfg, { mirror_url: mirrorUrl, offset_ms: -2000 }, durationMs, dir);
  assertCommon(neg);
  assert.ok(neg.includes('-ss'));
  assert.equal(after(neg, '-ss'), '2.000');
  assert.ok(neg.indexOf('-ss') < neg.indexOf('-i'));
  assert.equal(after(neg, '-af'), 'apad');
  assert.equal(neg.some((a) => String(a).startsWith('adelay')), false);

  // 2) offset_ms = 1500: без -ss, adelay=1500:all=1,apad
  const pos = buildAudioArgs(cfg, { mirror_url: mirrorUrl, offset_ms: 1500 }, durationMs, dir);
  assertCommon(pos);
  assert.equal(pos.includes('-ss'), false);
  assert.equal(after(pos, '-af'), 'adelay=1500:all=1,apad');

  // 3) offset_ms = 0 и undefined: без -ss, только apad
  for (const offset_ms of [0, undefined]) {
    const zero = buildAudioArgs(cfg, { mirror_url: mirrorUrl, offset_ms }, durationMs, dir);
    assertCommon(zero);
    assert.equal(zero.includes('-ss'), false);
    assert.equal(after(zero, '-af'), 'apad');
  }
});

test('checkAudioJob: валидное задание и отбраковка некорректных полей', () => {
  const valid = fixture('next-job-audio.json');
  assert.equal(checkAudioJob(valid), null);

  const badDir = structuredClone(valid);
  badDir.tracks[0].dir = 'x8';
  assert.equal(typeof checkAudioJob(badDir), 'string');

  const dupDir = structuredClone(valid);
  dupDir.tracks[1].dir = dupDir.tracks[0].dir;
  assert.equal(typeof checkAudioJob(dupDir), 'string');

  const emptyTracks = structuredClone(valid);
  emptyTracks.tracks = [];
  assert.equal(typeof checkAudioJob(emptyTracks), 'string');

  const badHlsId = structuredClone(valid);
  badHlsId.hls_id = 'zz';
  assert.equal(typeof checkAudioJob(badHlsId), 'string');

  const badDur = structuredClone(valid);
  badDur.duration_ms = 0;
  assert.equal(typeof checkAudioJob(badDur), 'string');

  const nineTracks = structuredClone(valid);
  nineTracks.tracks = Array.from({ length: 9 }, (_, i) => ({ ...valid.tracks[0], key: `k${i}`, dir: `x${i}` }));
  assert.equal(typeof checkAudioJob(nineTracks), 'string');
});

test('classifyFfmpegError: повторяемые и неповторяемые ошибки', () => {
  assert.equal(classifyFfmpegError('Server returned 404 Not Found').retryable, false);
  assert.equal(classifyFfmpegError('Invalid data found when processing input').retryable, false);
  assert.equal(classifyFfmpegError("Stream map '0:a:0' matches no streams.").retryable, false);
  assert.equal(classifyFfmpegError('Connection timed out').retryable, true);
});

test('audio-done: ключи дорожек в фикстуре audio-done-request.json', () => {
  const req = fixture('audio-done-request.json');
  assert.ok(Array.isArray(req.tracks) && req.tracks.length > 0);
  for (const t of req.tracks) {
    assert.deepEqual(Object.keys(t), ['key', 'dir', 'codec', 'channels', 'bytes']);
  }
});

test('выгрузка звука: имя <hls_id>.<profile>.a<rand>, команда finalize-audio, ответ CDN', () => {
  const name = audioTmpName('178e77a7ddb10079', 'v1');
  assert.match(name, /^178e77a7ddb10079\.v1\.a[0-9a-f]{8}$/);
  const cfg = loadConfig({});
  assert.equal(cfg.upload.finalizeAudioCmd, 'finalize-audio {name}');
  assert.equal(loadConfig({ SM_CDN_FINALIZE_AUDIO_CMD: 'fa {hls_id} {profile} {name}' }).upload.finalizeAudioCmd, 'fa {hls_id} {profile} {name}');
  assert.equal(fill(cfg.upload.finalizeAudioCmd, { name, tmp: name }), `finalize-audio ${name}`);
  // путь rsync — от корня rrsync (он уже .tmp/ на CDN), без «.tmp/» спереди
  assert.equal(rsyncTarget('', name), name);
  assert.ok(checkAnswer('ok 178e77a7ddb10079/v1 254432000', '178e77a7ddb10079', 'v1'));
  assert.ok(!checkAnswer('ok 178e77a7ddb10079/v1-copy 1', '178e77a7ddb10079', 'v1'));
  assert.ok(!checkAnswer('error no profile', '178e77a7ddb10079', 'v1'));
});

test('задание неизвестного типа → failed без повтора', async () => {
  const sent = [];
  const api = { progress: async () => {}, failed: async (b) => { sent.push(b); } };
  const log = { info() {}, warn() {}, error() {} };
  const cfg = { workDir: fs.mkdtempSync(path.join(os.tmpdir(), 'sm-type-')), progressSec: 10, maxSourceBytes: 1 };
  const r = await processJob({ job_id: 'j1', type: 'subs' }, { cfg, api, log });
  assert.equal(r.result, 'failed');
  assert.equal(sent[0].retryable, false);
  assert.match(sent[0].error, /тип/);
  const r2 = await processJob({ job_id: 'j2', type: 'audio', hls_id: 'zz' }, { cfg, api, log });
  assert.equal(r2.result, 'failed');
  assert.equal(sent[1].retryable, false);
  fs.rmSync(cfg.workDir, { recursive: true, force: true });
});

// ── Часть B: интеграция с ffmpeg и заглушкой сервера ──────────────────────────────────────────────────────────────────

const FFMPEG = process.env.SM_FFMPEG || 'ffmpeg';
const FFPROBE = process.env.SM_FFPROBE || 'ffprobe';

function sh(cmd, args) {
  const r = spawnSync(cmd, args, { encoding: 'utf8', windowsHide: true, maxBuffer: 64 * 1024 * 1024 });
  return { code: r.status ?? -1, out: r.stdout || '', err: r.stderr || '' };
}

const haveFfmpeg = sh(FFMPEG, ['-hide_banner', '-version']).code === 0 && sh(FFPROBE, ['-hide_banner', '-version']).code === 0;

let tmp;
let sample;

/** Наибольшая громкость первых 1,3 с дорожки, дБ. */
function startVolume(prof, dir) {
  const r = sh(FFMPEG, ['-hide_banner', '-nostats', '-i', path.join(prof, dir, 'audio.m4s'), '-t', '1.3', '-af', 'volumedetect', '-f', 'null', '-']);
  const m = /max_volume: (-?[\d.]+) dB/.exec(r.err);
  assert.ok(m, `не найден max_volume в stderr ${dir}: ${r.err}`);
  return Number(m[1]);
}
let runSeq = 0;

async function runAudio(tracks, extraEnv = {}, cdnName = null) {
  const id = ++runSeq;
  const stub = await createStub({
    mediaFile: sample,
    profile: 'audio',
    token: 'test-token',
    audio: { duration_ms: 36000, tracks },
  });
  const cdn = path.join(tmp, cdnName || `cdn-${id}`);
  const cfg = loadConfig({
    SM_SITE_URL: stub.url,
    HLS_WORKER_TOKEN: 'test-token',
    SM_FFMPEG: FFMPEG,
    SM_FFPROBE: FFPROBE,
    SM_ENCODER: 'x264',
    SM_UPLOAD_MODE: 'local',
    SM_UPLOAD_LOCAL_DIR: cdn,
    SM_WORK_DIR: path.join(tmp, `work-${id}`),
    SM_ONCE: '1',
    SM_PROGRESS_S: '1',
    SM_ERROR_S: '1',
    ...extraEnv,
  });
  const log = createLogger({ dir: path.join(tmp, 'logs'), console: !!process.env.SM_TEST_VERBOSE });
  const results = [];
  try {
    await runLoop({ cfg, api: createApi(cfg), log, ffmpegVersion: 'test', onResult: (r) => results.push(r) });
  } finally {
    await stub.close();
  }
  return { stub, cdn, results, prof: path.join(cdn, stub.hlsId, 'v1') };
}

test('интеграция audio: 5.1 AC3 → AAC 2.0 fMP4, сдвиги и ошибки', { skip: !haveFfmpeg && 'ffmpeg/ffprobe не найдены' }, async (t) => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'sm-audio-test-'));
  sample = path.join(tmp, 'sample.mka');

  // Синтетический 5.1 AC3 34 с в контейнере Matroska (.mka)
  const gen = sh(FFMPEG, [
    '-y', '-hide_banner', '-loglevel', 'error',
    '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000:duration=34',
    '-c:a', 'ac3', '-ac', '6',
    sample,
  ]);
  assert.equal(gen.code, 0, gen.err);

  t.after(() => { if (!process.env.SM_TEST_KEEP) fs.rmSync(tmp, { recursive: true, force: true }); else t.diagnostic(`файлы: ${tmp}`); });

  await t.test('offset −2000 и +1500', async () => {
    const { stub, results, prof } = await runAudio([{ offset_ms: -2000 }, { offset_ms: 1500 }]);
    assert.equal(results[0].result, 'done', JSON.stringify(results[0]));
    assert.deepEqual(stub.state.failed, []);
    assert.deepEqual(fs.readdirSync(prof).sort(), ['x0', 'x1']);

    for (const dir of ['x0', 'x1']) {
      const m3u8 = fs.readFileSync(path.join(prof, dir, 'index.m3u8'), 'utf8');
      assert.match(m3u8, /#EXT-X-MAP:URI="audio\.m4s",BYTERANGE="\d+@0"/);

      const m4s = path.join(prof, dir, 'audio.m4s');
      const pl = parseMediaPlaylist(m3u8);
      assert.deepEqual(validateByteRangePlaylist(pl, { fileSize: fs.statSync(m4s).size, file: 'audio.m4s' }), []);
      assert.ok(Math.abs(totalDuration(pl) - 36) < 1, `длительность ${totalDuration(pl)}`);

      const pr = sh(FFPROBE, ['-v', 'error', '-show_entries', 'stream=codec_name,profile,channels', '-of', 'json', m4s]);
      assert.equal(pr.code, 0, pr.err);
      const st = JSON.parse(pr.out).streams[0];
      assert.equal(st.codec_name, 'aac');
      assert.equal(st.profile, 'LC');
      assert.equal(st.channels, 2);
    }

    // тишина в начале x1 (+1500 мс), звук с самого начала x0 (−2000 мс)
    assert.ok(startVolume(prof, 'x1') < -60, 'x1 должна начинаться с тишины');
    assert.ok(startVolume(prof, 'x0') > -40, 'x0 должна звучать с самого начала');

    assert.equal(stub.state.audioDone.length, 1);
    const body = stub.state.audioDone[0];
    const issued = stub.state.issued[0];
    assert.equal(body.job_id, issued.job_id);
    assert.equal(body.hls_id, stub.hlsId);
    assert.equal(body.profile, 'v1');
    assert.deepEqual(
      body.tracks.map((x) => [x.key, x.dir, x.codec, x.channels]),
      [[issued.tracks[0].key, 'x0', 'mp4a.40.2', 2], [issued.tracks[1].key, 'x1', 'mp4a.40.2', 2]],
    );
    for (const tr of body.tracks) {
      assert.equal(tr.bytes, dirBytes(path.join(prof, tr.dir)));
    }
    assert.deepEqual(Object.keys(body).sort(), Object.keys(fixture('audio-done-request.json')).sort());
    assert.equal(stub.state.progress.some((p) => p.stage === 'encode'), true);
  });

  await t.test('новый сдвиг — замена той же xN, остальной профиль не тронут', async () => {
    const first = await runAudio([{ offset_ms: 0 }], {}, 'cdn-replace');
    assert.equal(first.results[0].result, 'done', JSON.stringify(first.results[0]));
    assert.ok(startVolume(first.prof, 'x0') > -40);
    // «видео» профиля, выложенное раньше: выгрузка звука его не трогает
    fs.mkdirSync(path.join(first.prof, '1080p'), { recursive: true });
    fs.writeFileSync(path.join(first.prof, '1080p', 'video.m4s'), 'video');
    fs.writeFileSync(path.join(first.prof, 'manifest.json'), '{}');

    const second = await runAudio([{ offset_ms: 1500 }], {}, 'cdn-replace');
    assert.equal(second.results[0].result, 'done', JSON.stringify(second.results[0]));
    assert.equal(second.prof, first.prof);
    assert.deepEqual(fs.readdirSync(second.prof).sort(), ['1080p', 'manifest.json', 'x0']);
    assert.equal(fs.readFileSync(path.join(second.prof, '1080p', 'video.m4s'), 'utf8'), 'video');
    assert.ok(startVolume(second.prof, 'x0') < -60, 'x0 перекодирована с новым сдвигом');
    assert.deepEqual(fs.readdirSync(path.join(second.cdn, '.tmp')), []);
  });

  await t.test('нет файла', async () => {
    const { stub, results, prof } = await runAudio([{ offset_ms: 0 }, { offset_ms: 0, missing: true }]);
    assert.equal(results[0].result, 'failed');
    assert.equal(stub.state.failed[0].stage, 'encode');
    assert.equal(stub.state.failed[0].retryable, false);
    assert.match(stub.state.failed[0].error, /x1/);
    assert.match(stub.state.failed[0].error, /не найден/);
    assert.deepEqual(stub.state.audioDone, []);
    assert.equal(fs.existsSync(prof), false);
  });
});

'use strict';

// Полный цикл на синтетическом образце через заглушку сервера:
// testsrc2 1080p + 2 звуковые дорожки (AC3 5.1) + SRT в MKV, ~36 с → next → ffprobe → отпечаток → probe →
// encode (v1, затем v1-copy) → проверка → manifest → «CDN» в локальной папке → done.
// Кодировщик: SM_TEST_ENCODER, иначе nvenc (если работает), иначе x265 (ultrafast). Без ffmpeg тест пропускается.

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');

const { loadConfig } = require('../src/config');
const { createApi } = require('../src/api');
const { runLoop } = require('../src/worker');
const { createLogger } = require('../src/log');
const { parseMediaPlaylist, validateByteRangePlaylist, subsPlaylist } = require('../src/playlist');
const { fingerprintBuffer } = require('../src/fingerprint');
const { dirBytes } = require('../src/manifest');
const { createStub } = require('../stub/server');

const FFMPEG = process.env.SM_FFMPEG || 'ffmpeg';
const FFPROBE = process.env.SM_FFPROBE || 'ffprobe';
const DURATION = 36;

function sh(cmd, args) {
  const r = spawnSync(cmd, args, { encoding: 'utf8', windowsHide: true, maxBuffer: 64 * 1024 * 1024 });
  return { code: r.status ?? -1, out: r.stdout || '', err: r.stderr || '' };
}

const haveFfmpeg = sh(FFMPEG, ['-hide_banner', '-version']).code === 0 && sh(FFPROBE, ['-hide_banner', '-version']).code === 0;

function pickEncoder() {
  if (process.env.SM_TEST_ENCODER) return process.env.SM_TEST_ENCODER;
  if (!sh(FFMPEG, ['-hide_banner', '-encoders']).out.includes('hevc_nvenc')) return 'x265';
  // кодировщик есть в сборке, но видеокарты может не быть — пробуем кадр
  const r = sh(FFMPEG, ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=256x144:duration=0.2', '-c:v', 'hevc_nvenc', '-f', 'null', '-']);
  return r.code === 0 ? 'nvenc' : 'x265';
}

let tmp;
let sample;
let encoder;

function keyframes(file) {
  const r = sh(FFPROBE, ['-v', 'error', '-skip_frame', 'nokey', '-select_streams', 'v:0', '-show_entries', 'frame=pts_time', '-of', 'csv=p=0', file]);
  assert.equal(r.code, 0, r.err);
  return r.out.split(/\r?\n/).map((s) => parseFloat(s)).filter((x) => Number.isFinite(x));
}

async function runWorker(profile, extraEnv = {}) {
  const stub = await createStub({ mediaFile: sample, profile, token: 'test-token' });
  const cdn = path.join(tmp, `cdn-${profile}`);
  const cfg = loadConfig({
    SM_SITE_URL: stub.url,
    HLS_WORKER_TOKEN: 'test-token',
    SM_ENCODER: encoder,
    SM_FFMPEG: FFMPEG,
    SM_FFPROBE: FFPROBE,
    SM_SW_PRESET: 'ultrafast',
    SM_UPLOAD_MODE: 'local',
    SM_UPLOAD_LOCAL_DIR: cdn,
    SM_WORK_DIR: path.join(tmp, `work-${profile}`),
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
  return { stub, cdn, results, out: path.join(cdn, stub.hlsId, profile) };
}

function checkPlaylist(dir, file) {
  const text = fs.readFileSync(path.join(dir, 'index.m3u8'), 'utf8');
  assert.match(text, /#EXT-X-MAP:URI="[^"]+",BYTERANGE="\d+@0"/, `${dir}: EXT-X-MAP с BYTERANGE`);
  assert.match(text, /#EXT-X-BYTERANGE:\d+@\d+/, `${dir}: EXT-X-BYTERANGE`);
  const pl = parseMediaPlaylist(text);
  assert.deepEqual(validateByteRangePlaylist(pl, { fileSize: fs.statSync(path.join(dir, file)).size, file }), [], dir);
  return pl;
}

test('интеграция: образец → v1 и v1-copy через заглушку', { skip: !haveFfmpeg && 'нет ffmpeg/ffprobe' }, async (t) => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'sm-enc-'));
  encoder = pickEncoder();
  t.diagnostic(`кодировщик: ${encoder}; ffmpeg: ${sh(FFMPEG, ['-hide_banner', '-version']).out.split('\n')[0]}`);
  t.after(() => { if (!process.env.SM_TEST_KEEP) fs.rmSync(tmp, { recursive: true, force: true }); else t.diagnostic(`файлы: ${tmp}`); });

  // образец: H.264 (ключевые кадры каждые 2 с — годится и для v1-copy), 2 × AC3 5.1, SRT
  const srt = path.join(tmp, 'sample.srt');
  fs.writeFileSync(srt, '1\n00:00:01,000 --> 00:00:04,000\nПривет\n\n2\n00:00:20,000 --> 00:00:22,500\nМир\n');
  sample = path.join(tmp, 'sample.mkv');
  const gen = sh(FFMPEG, ['-hide_banner', '-loglevel', 'error', '-y',
    '-f', 'lavfi', '-i', `testsrc2=size=1920x1080:rate=24000/1001:duration=${DURATION}`,
    '-f', 'lavfi', '-i', `sine=frequency=440:sample_rate=48000:duration=${DURATION}`,
    '-f', 'lavfi', '-i', `sine=frequency=660:sample_rate=48000:duration=${DURATION}`,
    '-i', srt,
    '-map', '0', '-map', '1', '-map', '2', '-map', '3',
    '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '28', '-maxrate', '6M', '-bufsize', '12M', '-g', '48', '-pix_fmt', 'yuv420p',
    '-c:a', 'ac3', '-ac', '6', '-c:s', 'srt',
    '-metadata:s:a:0', 'language=rus', '-metadata:s:a:0', 'title=Дубляж', '-disposition:a:0', 'default',
    '-metadata:s:a:1', 'language=eng', '-disposition:a:1', '0',
    '-metadata:s:s:0', 'language=rus', '-metadata:s:s:0', 'title=Полные',
    sample]);
  assert.equal(gen.code, 0, gen.err);
  const sampleFp = fingerprintBuffer(fs.readFileSync(sample));

  await t.test(`v1 (${encoder})`, async () => {
    const { stub, results, out } = await runWorker('v1');
    assert.equal(results.length, 1);
    assert.equal(results[0].result, 'done', JSON.stringify(results[0]));
    assert.deepEqual(stub.state.failed, []);

    // probe: отпечаток, длительность, потоки как probe-request.json
    const pr = stub.state.probe[0];
    assert.equal(pr.fingerprint, sampleFp);
    assert.ok(Math.abs(pr.duration_ms - DURATION * 1000) < 200, `duration_ms ${pr.duration_ms}`);
    assert.deepEqual(pr.streams.map((s) => s.codec_type), ['video', 'audio', 'audio', 'subtitle']);
    const v = pr.streams[0];
    assert.equal(v.codec_name, 'h264');
    assert.ok(v.keyframe_interval_max_s > 1.9 && v.keyframe_interval_max_s < 2.1, `kf ${v.keyframe_interval_max_s}`);
    assert.ok(stub.state.progress.length >= 1, 'progress');

    // плейлисты: BYTERANGE, MAP, непрерывные смещения до конца файла
    const p1080 = checkPlaylist(path.join(out, '1080p'), 'video.m4s');
    const p720 = checkPlaylist(path.join(out, '720p'), 'video.m4s');
    checkPlaylist(path.join(out, 'a0'), 'audio.m4s');
    checkPlaylist(path.join(out, 'a1'), 'audio.m4s');
    assert.deepEqual(p1080.segments.map((s) => s.duration), p720.segments.map((s) => s.duration));

    // ключевые кадры 1080p и 720p на одних временах, шаг 6 с (6,006 при 24000/1001)
    const k1 = keyframes(path.join(out, '1080p', 'video.m4s'));
    const k2 = keyframes(path.join(out, '720p', 'video.m4s'));
    assert.equal(k1.length, Math.ceil(DURATION / 6), `ключевых кадров 1080p: ${k1}`);
    assert.deepEqual(k1, k2);
    for (let i = 1; i < k1.length; i++) assert.ok(Math.abs(k1[i] - k1[i - 1] - 6.006) < 0.05, `шаг ${k1[i] - k1[i - 1]}`);

    // субтитры
    const vtt = fs.readFileSync(path.join(out, 's0', 'subs.vtt'), 'utf8');
    assert.match(vtt, /^WEBVTT/);
    assert.match(vtt, /Привет/);
    const m = JSON.parse(fs.readFileSync(path.join(out, 'manifest.json'), 'utf8'));
    assert.equal(fs.readFileSync(path.join(out, 's0', 'index.m3u8'), 'utf8'), subsPlaylist(m.duration_ms));

    // manifest.json
    assert.equal(m.hls_id, stub.hlsId);
    assert.equal(m.profile, 'v1');
    assert.equal(m.source.fingerprint, sampleFp);
    assert.equal(m.source.size, fs.statSync(sample).size);
    assert.deepEqual(m.video.map((x) => [x.dir, x.width, x.height]), [['1080p', 1920, 1080], ['720p', 1280, 720]]);
    const codecRe = encoder === 'x264' ? /^avc1\.[0-9a-f]{6}$/ : /^hvc1\.1\.6\.L\d+\.90$/;
    for (const x of m.video) {
      assert.match(x.codec, codecRe);
      assert.ok(x.bandwidth >= x.avg_bandwidth && x.avg_bandwidth > 0);
      assert.equal(x.fps, 23.976);
    }
    assert.deepEqual(m.audio.map((a) => [a.dir, a.src_index, a.codec, a.channels, a.language, a.name, a.default]),
      [['a0', 1, 'mp4a.40.2', 2, 'ru', 'Русский (Дубляж)', true], ['a1', 2, 'mp4a.40.2', 2, 'en', 'English', false]]);
    assert.deepEqual(m.subtitles, [{ dir: 's0', src_index: 3, language: 'ru', name: 'Русские (полные)', forced: false }]);
    assert.deepEqual(m.skipped, []);
    assert.equal(m.tonemapped, false);
    assert.equal(m.encoder, encoder);

    // done: sha256 manifest.json и размер профиля
    const done = stub.state.done[0];
    assert.equal(done.hls_id, stub.hlsId);
    assert.equal(done.profile, 'v1');
    assert.equal(done.manifest_sha256, crypto.createHash('sha256').update(fs.readFileSync(path.join(out, 'manifest.json'))).digest('hex'));
    // T4: сервер требует manifest целиком в done
    assert.deepEqual(done.manifest, JSON.parse(fs.readFileSync(path.join(out, 'manifest.json'), 'utf8')));
    assert.equal(done.bytes, dirBytes(out));
  });

  await t.test('v1-copy (H.264 без перекодирования)', async () => {
    const { stub, results, out } = await runWorker('v1-copy');
    assert.equal(results[0].result, 'done', JSON.stringify(results[0]));
    assert.deepEqual(fs.readdirSync(out).sort(), ['1080p', 'a0', 'a1', 'manifest.json', 's0']);
    const pl = checkPlaylist(path.join(out, '1080p'), 'video.m4s');
    assert.ok(pl.segments.length >= 5);
    const m = JSON.parse(fs.readFileSync(path.join(out, 'manifest.json'), 'utf8'));
    assert.equal(m.profile, 'v1-copy');
    assert.equal(m.encoder, 'copy');
    assert.equal(m.video.length, 1);
    assert.match(m.video[0].codec, /^avc1\.42/); // ultrafast → Constrained Baseline
    assert.equal(m.audio.length, 2);
    assert.equal(stub.state.done[0].profile, 'v1-copy');
  });

  await t.test('alias: без кодирования и без done', async () => {
    const { stub, results } = await runWorker('alias');
    assert.equal(results[0].result, 'alias');
    assert.equal(results[0].hls_id, '5d0c1f2a9e7b3c44');
    assert.deepEqual(stub.state.done, []);
    assert.deepEqual(stub.state.failed, []);
  });

  await t.test('wait → повтор probe → encode', async () => {
    const { stub, results } = await runWorker('wait', { SM_ENCODER: 'x264' });
    assert.equal(results[0].result, 'done', JSON.stringify(results[0]));
    assert.equal(stub.state.probe.length, 2);
  });

  await t.test('лимит SM_MAX_SOURCE_GB → failed, retryable=false', async () => {
    const { stub, results } = await runWorker('v1', { SM_MAX_SOURCE_GB: '0.001' });
    assert.equal(results[0].result, 'failed');
    assert.equal(stub.state.failed.length, 1);
    assert.equal(stub.state.failed[0].retryable, false);
    assert.deepEqual(stub.state.probe, []);
  });
});

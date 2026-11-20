'use strict';

// Синтетический HDR10 (PQ, bt2020, 10 бит, mastering display) 4K x265 → v1 через заглушку:
// путь cpu и путь libplacebo (если на машине есть Vulkan-видеокарта, иначе пропуск с причиной);
// выход bt709 yuv420p, ключевые кадры 1080p/720p совпадают, средняя яркость в разумных пределах,
// время обоих путей — в диагностике.

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
const { parseMediaPlaylist, validateByteRangePlaylist } = require('../src/playlist');
const { detectTonemap, describeTonemap } = require('../src/tonemap');
const { createStub } = require('../stub/server');

const FFMPEG = process.env.SM_FFMPEG || 'ffmpeg';
const FFPROBE = process.env.SM_FFPROBE || 'ffprobe';
const DURATION = 12;
// 1440p по умолчанию: x265 на 4K просит много памяти и падал (malloc) на машинах с малым запасом; 4K — SM_TEST_HDR_SIZE=3840x2160
const SIZE = process.env.SM_TEST_HDR_SIZE || '2560x1440';

function sh(cmd, args) {
  const r = spawnSync(cmd, args, { encoding: 'utf8', windowsHide: true, maxBuffer: 64 * 1024 * 1024 });
  return { code: r.status ?? -1, out: r.stdout || '', err: r.stderr || '' };
}

const haveFfmpeg = sh(FFMPEG, ['-hide_banner', '-version']).code === 0 && sh(FFPROBE, ['-hide_banner', '-version']).code === 0;
const haveTools = haveFfmpeg
  && /\bzscale\b/.test(sh(FFMPEG, ['-hide_banner', '-filters']).out)
  && sh(FFMPEG, ['-hide_banner', '-encoders']).out.includes('libx265');

function pickEncoder() {
  if (process.env.SM_TEST_ENCODER) return process.env.SM_TEST_ENCODER;
  if (!sh(FFMPEG, ['-hide_banner', '-encoders']).out.includes('hevc_nvenc')) return 'x265';
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

function checkPlaylist(dir, file) {
  const text = fs.readFileSync(path.join(dir, 'index.m3u8'), 'utf8');
  assert.match(text, /#EXT-X-MAP:URI="[^"]+",BYTERANGE="\d+@0"/, `${dir}: EXT-X-MAP с BYTERANGE`);
  assert.match(text, /#EXT-X-BYTERANGE:\d+@\d+/, `${dir}: EXT-X-BYTERANGE`);
  const pl = parseMediaPlaylist(text);
  assert.deepEqual(validateByteRangePlaylist(pl, { fileSize: fs.statSync(path.join(dir, file)).size, file }), [], dir);
  return pl;
}

function videoInfo(file) {
  const r = sh(FFPROBE, ['-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=pix_fmt,color_transfer,color_primaries,color_space,width,height', '-of', 'json', file]);
  assert.equal(r.code, 0, r.err);
  return JSON.parse(r.out).streams[0];
}

function meanLuma(file) {
  const r = spawnSync(FFMPEG, ['-hide_banner', '-loglevel', 'error', '-i', file, '-vf', 'fps=1,scale=64:36,format=gray', '-f', 'rawvideo', 'pipe:1'], {
    encoding: 'buffer',
    windowsHide: true,
    maxBuffer: 64 * 1024 * 1024,
  });
  assert.equal(r.status, 0, r.stderr ? r.stderr.toString() : '');
  const buf = r.stdout;
  let sum = 0;
  for (let i = 0; i < buf.length; i++) sum += buf[i];
  return buf.length ? sum / buf.length : 0;
}

async function runWorker(name, extraEnv = {}, { tonemapPath } = {}) {
  const stub = await createStub({ mediaFile: sample, profile: 'v1', token: 'test-token' });
  const cdn = path.join(tmp, `cdn-${name}`);
  const cfg = loadConfig({
    SM_SITE_URL: stub.url,
    HLS_WORKER_TOKEN: 'test-token',
    SM_ENCODER: encoder,
    SM_FFMPEG: FFMPEG,
    SM_FFPROBE: FFPROBE,
    SM_SW_PRESET: 'ultrafast',
    SM_UPLOAD_MODE: 'local',
    SM_UPLOAD_LOCAL_DIR: cdn,
    SM_WORK_DIR: path.join(tmp, `work-${name}`),
    SM_ONCE: '1',
    SM_PROGRESS_S: '1',
    SM_ERROR_S: '1',
    ...extraEnv,
  });
  cfg.tonemapPath = tonemapPath;
  const log = createLogger({ dir: path.join(tmp, 'logs'), console: !!process.env.SM_TEST_VERBOSE });
  const results = [];
  const t0 = Date.now();
  try {
    await runLoop({ cfg, api: createApi(cfg), log, ffmpegVersion: 'test', onResult: (r) => results.push(r) });
  } finally {
    await stub.close();
  }
  const ms = Date.now() - t0;
  return { stub, results, out: path.join(cdn, stub.hlsId, 'v1'), ms, encodeMs: results[0]?.encode_ms };
}

function checkOutput(out, label) {
  const tiers = [['1080p', 1920, 1080], ['720p', 1280, 720]];
  for (const [dir, w, h] of tiers) {
    const seg = path.join(out, dir, 'video.m4s');
    const info = videoInfo(seg);
    assert.equal(info.pix_fmt, 'yuv420p', `${label} ${dir} pix_fmt`);
    assert.equal(info.color_transfer, 'bt709', `${label} ${dir} color_transfer`);
    assert.equal(info.color_primaries, 'bt709', `${label} ${dir} color_primaries`);
    assert.equal(info.width, w, `${label} ${dir} width`);
    assert.equal(info.height, h, `${label} ${dir} height`);
    checkPlaylist(path.join(out, dir), 'video.m4s');
  }

  const k1 = keyframes(path.join(out, '1080p', 'video.m4s'));
  const k2 = keyframes(path.join(out, '720p', 'video.m4s'));
  assert.deepEqual(k1, k2, `${label}: ключевые кадры 1080p и 720p`);
  assert.equal(k1.length, Math.ceil(DURATION / 6), `${label}: количество ключевых кадров ${k1.length}`);
  for (let i = 1; i < k1.length; i++) {
    assert.ok(Math.abs(k1[i] - k1[i - 1] - 6.006) < 0.05, `${label}: шаг ${k1[i] - k1[i - 1]}`);
  }

  const m = JSON.parse(fs.readFileSync(path.join(out, 'manifest.json'), 'utf8'));
  assert.equal(m.tonemapped, true, `${label}: manifest.tonemapped`);
  assert.deepEqual(m.video.map((x) => [x.dir, x.width, x.height]), tiers, `${label}: manifest.video`);

  const y = meanLuma(path.join(out, '1080p', 'video.m4s'));
  assert.ok(y > 25 && y < 230, `${label}: средняя яркость ${y}`);
  return y;
}

test('HDR10 → SDR: cpu и libplacebo', {
  skip: (!haveFfmpeg && 'нет ffmpeg/ffprobe') || (!haveTools && 'нет zscale или libx265 в ffmpeg'),
}, async (t) => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'sm-hdr-'));
  encoder = pickEncoder();
  t.diagnostic(`кодировщик: ${encoder}; ffmpeg: ${sh(FFMPEG, ['-hide_banner', '-version']).out.split('\n')[0]}`);
  t.after(() => { if (!process.env.SM_TEST_KEEP) fs.rmSync(tmp, { recursive: true, force: true }); else t.diagnostic(`файлы: ${tmp}`); });

  sample = path.join(tmp, 'hdr.mkv');
  const gen = sh(FFMPEG, [
    '-hide_banner', '-loglevel', 'error', '-y',
    '-f', 'lavfi', '-i', `testsrc2=size=${SIZE}:rate=24000/1001:duration=${DURATION}`,
    '-f', 'lavfi', '-i', `sine=frequency=440:sample_rate=48000:duration=${DURATION}`,
    '-map', '0', '-map', '1',
    '-vf', 'zscale=tin=bt709:pin=bt709:min=bt709:t=smpte2084:p=bt2020:m=bt2020nc:r=tv:npl=203,format=yuv420p10le',
    '-c:v', 'libx265', '-preset', 'ultrafast', '-x265-params',
    'log-level=error:keyint=48:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):max-cll=1000,400:hdr10=1:hdr10-opt=1',
    '-c:a', 'ac3', '-metadata:s:a:0', 'language=eng', sample,
  ]);
  assert.equal(gen.code, 0, gen.err);

  const probeSample = sh(FFPROBE, ['-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=color_transfer,color_primaries,pix_fmt', '-of', 'json', sample]);
  assert.equal(probeSample.code, 0, probeSample.err);
  const sampleStream = JSON.parse(probeSample.out).streams[0];
  assert.equal(sampleStream.color_transfer, 'smpte2084');

  const probeCfg = loadConfig({ SM_FFMPEG: FFMPEG, SM_FFPROBE: FFPROBE, SM_TONEMAP: 'auto' });
  const tm = await detectTonemap(probeCfg);
  t.diagnostic(`тонмаппинг на этой машине: ${describeTonemap(tm)}`);

  let yCpu;
  let timeCpu;

  await t.test('cpu: уменьшение до 1080p, потом zscale+tonemap', async () => {
    const r = await runWorker('cpu', {}, { tonemapPath: { mode: 'cpu', device: null, reason: 'тест' } });
    assert.equal(r.results[0].result, 'done', JSON.stringify(r.results[0]));
    assert.deepEqual(r.stub.state.failed, []);
    yCpu = checkOutput(r.out, 'cpu');
    timeCpu = r.encodeMs;
    t.diagnostic(`cpu: ffmpeg ${(r.encodeMs / 1000).toFixed(1)} с (задание целиком ${(r.ms / 1000).toFixed(1)} с), яркость ${yCpu.toFixed(1)}`);
  });

  await t.test('libplacebo: масштаб + тонмаппинг на видеокарте', {
    skip: tm.mode !== 'libplacebo' && `libplacebo недоступен: ${tm.reason}`,
  }, async () => {
    const r = await runWorker('libplacebo', {}, { tonemapPath: tm });
    assert.equal(r.results[0].result, 'done', JSON.stringify(r.results[0]));
    assert.deepEqual(r.stub.state.failed, []);
    const yGpu = checkOutput(r.out, 'libplacebo');
    t.diagnostic(`libplacebo: ffmpeg ${(r.encodeMs / 1000).toFixed(1)} с (задание целиком ${(r.ms / 1000).toFixed(1)} с), яркость ${yGpu.toFixed(1)}`);
    if (yCpu !== undefined) {
      assert.ok(Math.abs(yGpu - yCpu) < 60, `яркость cpu=${yCpu.toFixed(1)} vs libplacebo=${yGpu.toFixed(1)}`);
      t.diagnostic(`кодирование ${SIZE}, ${DURATION} с: cpu ${(timeCpu / 1000).toFixed(1)} с, libplacebo ${(r.encodeMs / 1000).toFixed(1)} с`);
    }
  });
});

'use strict';

// План кодирования по probe-request.json: ступени, тонмаппинг, звук, субтитры, пропуски; аргументы ffmpeg.

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const { buildPlan, buildFfmpegArgs, videoTiers } = require('../src/plan');
const { copyCandidate, normalizeProbe } = require('../src/probe');
const { lang2, trackName } = require('../src/langs');
const { parseFfmpegVersion, versionOk } = require('../src/worker');
const { fill, rsyncTarget } = require('../src/upload');

const FIX = path.resolve(__dirname, '..', '..', 'docs', 'hls-fixtures');
const probeReq = JSON.parse(fs.readFileSync(path.join(FIX, 'probe-request.json'), 'utf8'));
const manifest = JSON.parse(fs.readFileSync(path.join(FIX, 'manifest.json'), 'utf8'));
const cfg = { encoder: 'nvenc', hwdec: true, nvencPreset: 'p5', swPreset: 'medium', cq: 24, reconnectDelayMax: 30 };

test('v1 по фикстуре: как manifest.json', () => {
  const plan = buildPlan(probeReq, 'v1');
  assert.deepEqual(plan.video.map((t) => [t.dir, t.width, t.height]), manifest.video.map((v) => [v.dir, v.width, v.height]));
  assert.equal(plan.tonemap, true);
  assert.equal(Math.round(plan.fps * 1000) / 1000, 23.976);
  assert.deepEqual(plan.audio, manifest.audio.map(({ dir, src_index, language, name, default: d }) => ({ dir, src_index, language, name, default: d })));
  assert.deepEqual(plan.subs, manifest.subtitles);
  assert.deepEqual(plan.skipped, manifest.skipped);
});

test('ступени: 4K вписывается, 720p-исходник — одна ступень, без увеличения', () => {
  assert.deepEqual(videoTiers({ w: 3840, h: 2160 }).map((t) => [t.dir, t.width, t.height]), [['1080p', 1920, 1080], ['720p', 1280, 720]]);
  assert.deepEqual(videoTiers({ w: 1440, h: 1080 }).map((t) => [t.width, t.height]), [[1440, 1080], [960, 720]]);
  assert.deepEqual(videoTiers({ w: 1280, h: 536 }).map((t) => [t.dir, t.width, t.height]), [['720p', 1280, 536]]);
});

test('аргументы ffmpeg v1: ключевые кадры каждые 6 с, hvc1, fMP4 single_file, чтение по HTTP', () => {
  const plan = buildPlan(probeReq, 'v1');
  const a = buildFfmpegArgs(cfg, plan, 'https://site.example.org/api/file/proxy/x.mkv?link=1', '/w/v1');
  const s = a.join(' ');
  assert.match(s, /-reconnect 1 -reconnect_streamed 1 -reconnect_on_network_error 1/);
  assert.equal(a.filter((x) => x === 'expr:gte(t,n_forced*6)').length, 2);
  assert.equal(a.filter((x) => x === 'hevc_nvenc').length, 2);
  assert.match(s, /-no-scenecut 1/);
  assert.match(s, /-maxrate 7000000 -bufsize 14000000/);
  assert.match(s, /-maxrate 4000000 -bufsize 8000000/);
  assert.equal(a.filter((x) => x === 'independent_segments+single_file').length, 4); // 2 видео + 2 звука
  assert.match(s, /tonemap=tonemap=hable/);
  assert.match(s, /-map 0:3 -c:s webvtt/);
  assert.doesNotMatch(s, /-map 0:4/); // PGS пропущен
  assert.ok(a.includes('-hwaccel'));
});

function copyProbe(width, height) {
  const p = JSON.parse(JSON.stringify(probeReq));
  Object.assign(p.streams[0], { codec_name: 'h264', pix_fmt: 'yuv420p', color_transfer: 'bt709', profile: 'High', width, height });
  p.format.bit_rate = 8e6;
  return p;
}

test('v1-copy 1080p: копия + 720p с ключевыми кадрами исходника, звук в AAC', () => {
  const p = copyProbe(1920, 1080);
  // H.264 — не кандидат (21.11: копия только из HEVC); план v1-copy строится и для него (старые задания, тесты аргументов)
  assert.equal(copyCandidate(normalizeProbe({ format: { ...p.format, duration: '10' }, streams: [{ ...p.streams[0], disposition: {} }] })), false);
  const ph = JSON.parse(JSON.stringify(p));
  ph.streams[0].codec_name = 'hevc';
  assert.equal(copyCandidate(normalizeProbe({ format: { ...ph.format, duration: '10' }, streams: [{ ...ph.streams[0], disposition: {} }] })), true);
  const plan = buildPlan(p, 'v1-copy');
  assert.deepEqual(plan.video.map((t) => [t.dir, t.width, t.height, !!t.copy]), [['1080p', 1920, 1080, true], ['720p', 1280, 720, false]]);

  // nvenc: -hwaccel cuda (декодирование для 720p), копия без фильтров, 720p — scale и force_key_frames source
  const a = buildFfmpegArgs(cfg, plan, '/src.mkv', '/w/v1-copy');
  const s = a.join(' ');
  assert.ok(a.includes('-hwaccel'));
  assert.match(s, /-c:v copy -tag:v avc1/);
  assert.equal(a[a.indexOf('-filter_complex') + 1], '[0:0]format=yuv420p,scale=1280:720,setsar=1[c720p]');
  const enc = a.slice(a.indexOf('[c720p]', a.indexOf('-filter_complex') + 2));
  const e = enc.join(' ');
  assert.match(e, /-c:v hevc_nvenc .*-cq 24 .*-maxrate 4000000 -bufsize 8000000/);
  assert.match(e, /-no-scenecut 1 -g (\d+) -force_key_frames source -forced-idr 1/);
  assert.ok(Number(/-g (\d+)/.exec(e)[1]) >= 1000);
  assert.doesNotMatch(s, /expr:gte/); // своих ключевых кадров у 720p нет
  assert.equal(a.filter((x) => x === 'independent_segments+single_file').length, 4); // 2 видео + 2 звука
  assert.match(s, /1080p.index\.m3u8 -map \[c720p\]/);
  assert.equal((s.match(/-c:a aac/g) || []).length, 2);
  assert.doesNotMatch(s, /-reconnect/); // локальный файл

  // x265 и x264: те же ключевые кадры, без scenecut
  const x265 = buildFfmpegArgs({ ...cfg, encoder: 'x265' }, plan, '/src.mkv', '/w').join(' ');
  assert.match(x265, /-c:v libx265 .*-force_key_frames source -forced-idr 1 -x265-params keyint=\d+:min-keyint=1:scenecut=0:open-gop=0/);
  assert.doesNotMatch(x265, /-hwaccel/);
  const x264 = buildFfmpegArgs({ ...cfg, encoder: 'x264' }, plan, '/src.mkv', '/w').join(' ');
  assert.match(x264, /-c:v libx264 .*-force_key_frames source -forced-idr 1 -g \d+ -keyint_min 1 -sc_threshold 0 -x264-params scenecut=0:open-gop=0/);
  // без hwdec — без cuda
  assert.ok(!buildFfmpegArgs({ ...cfg, hwdec: false }, plan, '/src.mkv', '/w').includes('-hwaccel'));
});

test('v1-copy: широкий кадр и анаморф вписываются в 1280×720, чётные размеры', () => {
  assert.deepEqual(buildPlan(copyProbe(1920, 800), 'v1-copy').video.map((t) => [t.dir, t.width, t.height]), [['1080p', 1920, 800], ['720p', 1280, 534]]);
  assert.deepEqual(buildPlan(copyProbe(1440, 1080), 'v1-copy').video.map((t) => [t.dir, t.width, t.height]), [['1080p', 1440, 1080], ['720p', 960, 720]]);
  // анаморф 1440x1080 с SAR 4:3 → 1920x1080 на экране → 720p 1280x720
  const raw = { streams: [{ index: 0, sample_aspect_ratio: '4:3' }] };
  assert.deepEqual(buildPlan(copyProbe(1440, 1080), 'v1-copy', raw).video.map((t) => [t.width, t.height]), [[1440, 1080], [1280, 720]]);
});

test('v1-copy 720p и ниже: только копия, без декодирования', () => {
  for (const [w, h] of [[1280, 720], [1280, 536], [720, 576]]) {
    const plan = buildPlan(copyProbe(w, h), 'v1-copy');
    assert.deepEqual(plan.video.map((t) => [t.dir, t.width, t.height, t.copy]), [['720p', w, h, true]]);
    const a = buildFfmpegArgs(cfg, plan, '/src.mkv', '/w');
    assert.ok(!a.includes('-hwaccel'));
    assert.ok(!a.includes('-filter_complex'));
    assert.ok(!a.includes('-force_key_frames'));
    assert.equal(a.filter((x) => x === 'copy').length, 1);
  }
});

test('copyCandidate: H.264, HDR, 10 бит, > 1080, > 8 Мбит — нет', () => {
  const mk = (v, br = 8e6) => ({ format: { bit_rate: br }, streams: [{ codec_type: 'video', codec_name: 'hevc', pix_fmt: 'yuv420p', width: 1920, height: 1080, color_transfer: '', dovi: false, ...v }] });
  assert.equal(copyCandidate(mk({})), true);
  assert.equal(copyCandidate(mk({ pix_fmt: 'yuv420p10le' })), false);
  assert.equal(copyCandidate(mk({ color_transfer: 'smpte2084' })), false);
  assert.equal(copyCandidate(mk({ dovi: true })), false);
  assert.equal(copyCandidate(mk({ height: 1600, width: 3840 })), false);
  assert.equal(copyCandidate(mk({}, 20e6)), false);
  assert.equal(copyCandidate(mk({}, 9e6)), false);
  assert.equal(copyCandidate(mk({ codec_name: 'h264' })), false);
  assert.equal(copyCandidate(mk({}, null)), false);
  assert.equal(copyCandidate(mk({ codec_name: 'vc1' })), false);
});

test('языки и имена дорожек', () => {
  assert.equal(lang2('rus'), 'ru');
  assert.equal(lang2('und'), '');
  assert.equal(trackName('rus', 'Дубляж', 'audio', 0), 'Русский (Дубляж)');
  assert.equal(trackName('eng', '', 'audio', 1), 'English');
  assert.equal(trackName('rus', 'Полные', 'subtitle', 0), 'Русские (полные)');
  assert.equal(trackName('', '', 'audio', 2), 'Дорожка 3');
});

test('версия ffmpeg ≥ 6.1', () => {
  assert.equal(parseFfmpegVersion('ffmpeg version 9.0.2-full_build-www.gyan.dev Copyright').short, '9.0.2');
  assert.equal(versionOk(parseFfmpegVersion('ffmpeg version 6.1.1')), true);
  assert.equal(versionOk(parseFfmpegVersion('ffmpeg version n6.0')), false);
  assert.equal(versionOk(parseFfmpegVersion('ffmpeg version 4.4.2-0ubuntu')), false);
  assert.equal(versionOk(parseFfmpegVersion('ffmpeg version N-112233-gabc')), true);
});

test('шаблон finalize', () => {
  assert.equal(fill('finalize {name} {hls_id} {profile}', { name: 'abc.v1.1234', hls_id: 'abc', profile: 'v1' }), 'finalize abc.v1.1234 abc v1');
});

test('rsyncTarget: путь от корня rrsync (/srv/hls/.tmp/) — по умолчанию просто имя', () => {
  const n = '025efdf2bf922af6.v1.ab12cd34';
  assert.equal(rsyncTarget('', n), n);
  assert.equal(rsyncTarget(undefined, n), n);
  assert.equal(rsyncTarget('.', n), n);
  assert.equal(rsyncTarget('./', n), n);
  assert.equal(rsyncTarget('sub/', n), `sub/${n}`);
  // прежнее значение по умолчанию «.tmp» на CDN давало /srv/hls/.tmp/.tmp/<имя> → rsync exit 11
  assert.notEqual(rsyncTarget('', n), `.tmp/${n}`);
});

test('качество по умолчанию: nvenc -cq 21 (20.11, было 24), x265/x264 — 23', () => {
  const { loadConfig } = require('../src/config');
  assert.equal(loadConfig({ SM_SITE_URL: 'https://s.example.org', HLS_WORKER_TOKEN: 't' }).cq, 21);
  assert.equal(loadConfig({ SM_SITE_URL: 'https://s.example.org', HLS_WORKER_TOKEN: 't', SM_ENCODER: 'x265' }).cq, 23);
  assert.equal(loadConfig({ SM_SITE_URL: 'https://s.example.org', HLS_WORKER_TOKEN: 't', SM_CQ: '24' }).cq, 24);
});

test('масштаб на видеокарте (gpuScale): scale_cuda, кадры в памяти GPU; иначе — по-старому', () => {
  const g = { ...cfg, gpuScale: true };
  const sdr = (w, h, extra = {}) => {
    const p = copyProbe(w, h);
    Object.assign(p.streams[0], extra);
    return p;
  };
  // v1 из 4K SDR 10 бит: верхняя ступень и 8 бит одним scale_cuda, 720p — от неё
  const plan = buildPlan(sdr(3840, 2160, { codec_name: 'hevc', pix_fmt: 'yuv420p10le' }), 'v1');
  const a = buildFfmpegArgs(g, plan, '/src.mkv', '/w');
  assert.deepEqual(a.slice(a.indexOf('-hwaccel'), a.indexOf('-hwaccel') + 4), ['-hwaccel', 'cuda', '-hwaccel_output_format', 'cuda']);
  assert.equal(a[a.indexOf('-filter_complex') + 1],
    '[0:0]scale_cuda=w=1920:h=1080:format=yuv420p,split=2[g0][g1];[g0]setsar=1[v0];[g1]scale_cuda=w=1280:h=720,setsar=1[v1]');
  assert.equal(a.filter((x) => x === 'hevc_nvenc').length, 2);
  assert.doesNotMatch(a.join(' '), /-color_primaries/);

  // одна ступень (720p-исходник), черезстрочное — bwdif_cuda
  const one = buildPlan(sdr(1280, 720), 'v1');
  one.interlaced = true;
  const b = buildFfmpegArgs(g, one, '/src.mkv', '/w');
  assert.equal(b[b.indexOf('-filter_complex') + 1], '[0:0]bwdif_cuda=mode=send_frame:deint=interlaced,scale_cuda=w=1280:h=720:format=yuv420p,setsar=1[v0]');

  // v1-copy: 720p через scale_cuda, копия как раньше
  const c = buildFfmpegArgs(g, buildPlan(sdr(1920, 1080), 'v1-copy'), '/src.mkv', '/w');
  assert.equal(c[c.indexOf('-filter_complex') + 1], '[0:0]scale_cuda=w=1280:h=720:format=yuv420p,setsar=1[c720p]');
  assert.match(c.join(' '), /-c:v copy -tag:v avc1 .*-map \[c720p\] -c:v hevc_nvenc .*-force_key_frames source/);

  // не на GPU: HDR (тонмаппинг), H.264 10 бит (NVDEC не умеет), выключено, повтор после сбоя (plan.gpu = false)
  const hdr = buildFfmpegArgs(g, buildPlan(probeReq, 'v1'), '/src.mkv', '/w');
  assert.ok(!hdr.includes('-hwaccel_output_format'));
  const hi10 = buildFfmpegArgs(g, buildPlan(sdr(1920, 1080, { pix_fmt: 'yuv420p10le' }), 'v1'), '/src.mkv', '/w');
  assert.ok(!hi10.includes('-hwaccel_output_format'));
  assert.ok(!buildFfmpegArgs(cfg, plan, '/src.mkv', '/w').includes('-hwaccel_output_format'));
  const retry = { ...plan, gpu: false };
  const r = buildFfmpegArgs(g, retry, '/src.mkv', '/w');
  assert.ok(!r.includes('-hwaccel_output_format'));
  assert.match(r[r.indexOf('-filter_complex') + 1], /^\[0:0\]format=yuv420p,split=2/);
});

test('самопроверка scale_cuda: без ffmpeg — причина, а не исключение', async () => {
  const { gpuScaleSelfTest } = require('../src/worker');
  assert.match(await gpuScaleSelfTest({ ffmpeg: 'ffmpeg-которого-нет' }), /^ffmpeg exit -1: /);
});

test('в исходниках нет управляющих символов (\b в регулярке, вписанный как backspace)', () => {
  const dir = path.join(__dirname, '..', 'src');
  for (const f of fs.readdirSync(dir).filter((x) => x.endsWith('.js'))) {
    assert.doesNotMatch(fs.readFileSync(path.join(dir, f), 'utf8'), /[\x00-\x08\x0b\x0c\x0e-\x1f]/, f);
  }
});

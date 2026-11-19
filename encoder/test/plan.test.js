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

test('v1-copy: одна дорожка -c:v copy, звук в AAC', () => {
  const p = JSON.parse(JSON.stringify(probeReq));
  Object.assign(p.streams[0], { codec_name: 'h264', pix_fmt: 'yuv420p', color_transfer: 'bt709', profile: 'High' });
  p.format.bit_rate = 8e6;
  assert.equal(copyCandidate(normalizeProbe({ format: { ...p.format, duration: '10' }, streams: [{ ...p.streams[0], disposition: {} }] })), true);
  const plan = buildPlan(p, 'v1-copy');
  assert.equal(plan.video.length, 1);
  const s = buildFfmpegArgs({ ...cfg, encoder: 'x265' }, plan, '/src.mkv', '/w/v1-copy').join(' ');
  assert.match(s, /-c:v copy -tag:v avc1/);
  assert.doesNotMatch(s, /-reconnect/); // локальный файл
  assert.equal((s.match(/-c:a aac/g) || []).length, 2);
});

test('copyCandidate: HDR, 10 бит, > 1080, > 12 Мбит — нет', () => {
  const mk = (v, br = 8e6) => ({ format: { bit_rate: br }, streams: [{ codec_type: 'video', codec_name: 'hevc', pix_fmt: 'yuv420p', width: 1920, height: 1080, color_transfer: '', dovi: false, ...v }] });
  assert.equal(copyCandidate(mk({})), true);
  assert.equal(copyCandidate(mk({ pix_fmt: 'yuv420p10le' })), false);
  assert.equal(copyCandidate(mk({ color_transfer: 'smpte2084' })), false);
  assert.equal(copyCandidate(mk({ dovi: true })), false);
  assert.equal(copyCandidate(mk({ height: 1600, width: 3840 })), false);
  assert.equal(copyCandidate(mk({}, 20e6)), false);
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

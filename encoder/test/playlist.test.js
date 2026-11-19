'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const {
  parseMediaPlaylist,
  validateByteRangePlaylist,
  totalDuration,
  bitrates,
  subsPlaylist,
  parseAttrs,
} = require('../src/playlist');

const {
  codecFromInit,
  hevcCodec,
  avcCodec,
  reverseBits32,
} = require('../src/mp4');

const FIXTURES = path.resolve(__dirname, '..', '..', 'docs', 'hls-fixtures');

test('parseMediaPlaylist и validateByteRangePlaylist на фикстуре media-1080p.m3u8', () => {
  const text = fs.readFileSync(path.join(FIXTURES, 'media-1080p.m3u8'), 'utf8');
  const pl = parseMediaPlaylist(text);

  assert.equal(pl.version, 7);
  assert.equal(pl.targetDuration, 6);
  assert.equal(pl.playlistType, 'VOD');
  assert.equal(pl.independent, true);
  assert.equal(pl.endList, true);
  assert.deepEqual(pl.map, { uri: 'video.m4s', length: 1290, offset: 0 });

  assert.equal(pl.segments.length, 3);
  assert.deepEqual(
    pl.segments.map((s) => s.offset),
    [1290, 3713745, 6697757]
  );
  assert.deepEqual(
    pl.segments.map((s) => s.length),
    [3712455, 2984012, 1802211]
  );
  assert.deepEqual(
    pl.segments.map((s) => s.duration),
    [6.006, 6.006, 4.171]
  );

  assert.deepEqual(validateByteRangePlaylist(pl), []);
  assert.deepEqual(validateByteRangePlaylist(pl, { fileSize: 6697757 + 1802211 }), []);

  const sizeErrors = validateByteRangePlaylist(pl, { fileSize: 123456 });
  assert.equal(sizeErrors.length, 1);
  assert.match(sizeErrors[0], /размер/i);

  assert.ok(Math.abs(totalDuration(pl) - 16.183) < 1e-9);
});

test('validateByteRangePlaylist выявляет ошибки в сломанных плейлистах', () => {
  const base = fs.readFileSync(path.join(FIXTURES, 'media-1080p.m3u8'), 'utf8');

  // Разрыв в смещениях
  const gapPl = parseMediaPlaylist(base.replace('@3713745', '@3713746'));
  const gapErrors = validateByteRangePlaylist(gapPl);
  assert.ok(gapErrors.length > 0);
  assert.ok(gapErrors.some((e) => e.includes('смещение')));

  // Отсутствует EXT-X-MAP
  const noMapText = base
    .split(/\r?\n/)
    .filter((line) => !line.startsWith('#EXT-X-MAP:'))
    .join('\n');
  const noMapErrors = validateByteRangePlaylist(parseMediaPlaylist(noMapText));
  assert.ok(noMapErrors.some((e) => e.includes('нет EXT-X-MAP')));

  // Отсутствует EXT-X-ENDLIST
  const noEndListText = base
    .split(/\r?\n/)
    .filter((line) => line.trim() !== '#EXT-X-ENDLIST')
    .join('\n');
  const noEndListErrors = validateByteRangePlaylist(parseMediaPlaylist(noEndListText));
  assert.ok(noEndListErrors.length > 0);

  // Сегмент без BYTERANGE
  const noByteRangeText = base.replace('#EXT-X-BYTERANGE:3712455@1290\n', '').replace('#EXT-X-BYTERANGE:3712455@1290\r\n', '');
  const noByteRangeErrors = validateByteRangePlaylist(parseMediaPlaylist(noByteRangeText));
  assert.ok(noByteRangeErrors.some((e) => e.includes('BYTERANGE')));
});

test('BYTERANGE без @offset продолжает смещение от предыдущего сегмента', () => {
  const text = [
    '#EXTM3U',
    '#EXT-X-VERSION:7',
    '#EXT-X-TARGETDURATION:6',
    '#EXT-X-PLAYLIST-TYPE:VOD',
    '#EXT-X-MAP:URI="video.m4s",BYTERANGE="50@0"',
    '#EXTINF:5.0,',
    '#EXT-X-BYTERANGE:200@50',
    'video.m4s',
    '#EXTINF:5.0,',
    '#EXT-X-BYTERANGE:100',
    'video.m4s',
    '#EXT-X-ENDLIST',
  ].join('\n');

  const pl = parseMediaPlaylist(text);
  assert.equal(pl.segments.length, 2);
  assert.equal(pl.segments[1].offset, 250);
  assert.deepEqual(validateByteRangePlaylist(pl, { fileSize: 350 }), []);
});

test('subsPlaylist генерирует точное содержимое фикстуры subs-s0.m3u8', () => {
  const expected = fs.readFileSync(path.join(FIXTURES, 'subs-s0.m3u8'), 'utf8');
  const generated = subsPlaylist(7951200);
  assert.equal(generated, expected);

  const pl = parseMediaPlaylist(generated);
  assert.equal(pl.targetDuration, 7952);
  assert.equal(pl.segments.length, 1);
  assert.equal(pl.segments[0].duration, 7951.2);
  assert.equal(pl.segments[0].uri, 'subs.vtt');
});

test('bitrates вычисляет пиковый и средний битрейт на фикстуре media-1080p.m3u8', () => {
  const text = fs.readFileSync(path.join(FIXTURES, 'media-1080p.m3u8'), 'utf8');
  const pl = parseMediaPlaylist(text);

  const expectedPeak = Math.round(
    Math.max(...pl.segments.map((s) => (s.length * 8) / s.duration))
  );
  const totalBytes = pl.segments.reduce((sum, s) => sum + s.length, 0);
  const totalDur = pl.segments.reduce((sum, s) => sum + s.duration, 0);
  const expectedAvg = Math.round((totalBytes * 8) / totalDur);

  const res = bitrates(pl);
  assert.equal(res.peak, expectedPeak);
  assert.equal(res.avg, expectedAvg);
});

test('parseAttrs разбирает строку атрибутов и строку EXT-X-STREAM-INF из master-v1.m3u8', () => {
  assert.deepEqual(parseAttrs('URI="video.m4s",BYTERANGE="1290@0"'), {
    URI: 'video.m4s',
    BYTERANGE: '1290@0',
  });

  const masterText = fs.readFileSync(path.join(FIXTURES, 'master-v1.m3u8'), 'utf8');
  const streamInfLine = masterText
    .split(/\r?\n/)
    .find((line) => line.startsWith('#EXT-X-STREAM-INF:'));
  assert.ok(streamInfLine, 'Строка #EXT-X-STREAM-INF: должна присутствовать в master-v1.m3u8');

  const attrs = parseAttrs(streamInfLine.slice('#EXT-X-STREAM-INF:'.length));
  assert.equal(attrs.BANDWIDTH, '5400000');
  assert.equal(attrs.CODECS, 'hvc1.1.6.L120.90,mp4a.40.2');
  assert.equal(attrs.RESOLUTION, '1920x800');
});

test('mp4: reverseBits32, hevcCodec, avcCodec и codecFromInit', () => {
  assert.equal(reverseBits32(0x60000000), 6);

  const hvcCMain = Buffer.from([1, 0x01, 0x60, 0, 0, 0, 0x90, 0, 0, 0, 0, 0, 120]);
  assert.equal(hevcCodec(hvcCMain), 'hvc1.1.6.L120.90');

  const hvcCMain10High = Buffer.from([1, 0x22, 0x20, 0, 0, 0, 0xb0, 0, 0, 0, 0, 0, 150]);
  assert.equal(hevcCodec(hvcCMain10High), 'hvc1.2.4.H150.B0');

  assert.equal(avcCodec(Buffer.from([1, 0x64, 0x00, 0x28])), 'avc1.640028');

  const boxSize = Buffer.alloc(4);
  boxSize.writeUInt32BE(8 + hvcCMain.length, 0);
  const initBuf = Buffer.concat([
    Buffer.alloc(16),
    boxSize,
    Buffer.from('hvcC', 'latin1'),
    hvcCMain,
  ]);
  assert.equal(codecFromInit(initBuf), 'hvc1.1.6.L120.90');

  assert.equal(codecFromInit(Buffer.alloc(64)), null);
});

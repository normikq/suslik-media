'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const FIXTURES_DIR = __dirname;
const MiB = 1024 * 1024;
const HALF_MiB = 524288;

// Вычисление hls_id: первые 16 hex-символов SHA-1 от входной строки
function hlsId(input) {
  return crypto.createHash('sha1').update(input).digest('hex').slice(0, 16);
}

// Подпись CDN-ссылки: MD5(exp + id + ' ' + secret) в base64url
function cdnSig(exp, id, secret) {
  return crypto.createHash('md5').update(String(exp) + id + ' ' + secret).digest('base64url');
}

// Подпись master.m3u8: HMAC-SHA256(secret, 'hls:' + exp + ':' + id) в hex
function masterSig(exp, id, secret) {
  return crypto.createHmac('sha256', secret).update(`hls:${exp}:${id}`).digest('hex');
}

// Детерминированный генератор байтов для тестовых буферов
function genBytes(size) {
  const buf = Buffer.alloc(size);
  for (let i = 0; i < size; i++) {
    buf[i] = Math.imul(i, 2654435761) >>> 24;
  }
  return buf;
}

// Режим отпечатка: 'whole' для файлов меньше 3 MiB, иначе 'three'
function fpMode(size) {
  return size < 3 * MiB ? 'whole' : 'three';
}

// Смещения трёх кусков по 1 MiB для режима 'three'
function fpOffsets(size) {
  return [0, Math.floor(size / 2) - HALF_MiB, size - MiB];
}

// Отпечаток файла: SHA-256 от '<size>:' + весь буфер (whole) или три куска по 1 MiB (three)
function fingerprint(buf) {
  const size = buf.length;
  const parts = fpMode(size) === 'whole'
    ? [buf]
    : fpOffsets(size).map((offset) => buf.subarray(offset, offset + MiB));
  return crypto
    .createHash('sha256')
    .update(Buffer.concat([Buffer.from(`${size}:`, 'utf8'), ...parts]))
    .digest('hex');
}

function readFixture(name) {
  return fs.readFileSync(path.join(FIXTURES_DIR, name), 'utf8');
}

function readJson(name) {
  return JSON.parse(readFixture(name));
}

const vectors = readJson('vectors.json');

test('1) vectors.hls_id: формат input и значение hlsId', () => {
  assert.ok(Array.isArray(vectors.hls_id) && vectors.hls_id.length > 0);
  for (const item of vectors.hls_id) {
    const prefix = item.infohash ?? `ad:${item.ad_id}`;
    const expectedInput = `${prefix}|${item.path}|${item.size}`;
    assert.equal(item.input, expectedInput);
    assert.equal(hlsId(item.input), item.hls_id);
    assert.match(item.hls_id, /^[0-9a-f]{16}$/);
  }
});

test('2) vectors.sign: подпись CDN и формат base64url', () => {
  assert.ok(Array.isArray(vectors.sign) && vectors.sign.length > 0);
  for (const item of vectors.sign) {
    const sig = cdnSig(item.exp, item.hls_id, item.secret);
    assert.equal(sig, item.sig);
    assert.match(item.sig, /^[A-Za-z0-9_-]{22}$/);
  }
});

test('3) vectors.master_sign: подпись master.m3u8 и 64 hex-символа', () => {
  assert.ok(Array.isArray(vectors.master_sign) && vectors.master_sign.length > 0);
  for (const item of vectors.master_sign) {
    const sig = masterSig(item.exp, item.hls_id, item.session_secret);
    assert.equal(sig, item.sig);
    assert.match(item.sig, /^[0-9a-f]{64}$/);
  }
});

test('4) vectors.fingerprint: режимы, смещения, хэши кусков и итоговый отпечаток', () => {
  assert.equal(fpMode(3 * MiB), 'three');
  assert.equal(fpMode(3 * MiB - 1), 'whole');

  assert.ok(Array.isArray(vectors.fingerprint) && vectors.fingerprint.length > 0);
  for (const item of vectors.fingerprint) {
    assert.equal(fpMode(item.size), item.mode);
    const buf = genBytes(item.size);
    if (item.mode === 'three') {
      const expectedOffsets = fpOffsets(item.size);
      assert.deepEqual(item.offsets, expectedOffsets);
      const expectedChunks = expectedOffsets.map((offset) =>
        crypto.createHash('sha256').update(buf.subarray(offset, offset + MiB)).digest('hex')
      );
      assert.deepEqual(item.chunk_sha256, expectedChunks);
    }
    assert.equal(fingerprint(buf), item.fingerprint);
  }
});

test('5) master-v1.m3u8: заголовок, подписи URI, группы aud/sub и кодеки hvc1', () => {
  const content = readFixture('master-v1.m3u8');
  const lines = content.split(/\r?\n/);
  assert.equal(lines[0], '#EXTM3U');

  const urlRegex = /^https:\/\/[^/]+\/hls\/([^/]+)\/([0-9]+)\/([0-9a-f]{16})\/(v1|v1-copy)\/(.+)$/;
  const secret = vectors.sign[0].secret;
  const uris = [];
  let hasAudGroup = false;
  let hasSubGroup = false;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.startsWith('#EXT-X-MEDIA:')) {
      if (line.includes('GROUP-ID="aud"')) hasAudGroup = true;
      if (line.includes('GROUP-ID="sub"')) hasSubGroup = true;
      const uriMatch = line.match(/URI="([^"]+)"/);
      if (uriMatch) uris.push(uriMatch[1]);
    } else if (line.startsWith('#EXT-X-STREAM-INF:')) {
      const codecsMatch = line.match(/CODECS="([^"]+)"/);
      assert.ok(codecsMatch, `Отсутствует CODECS в строке: ${line}`);
      const codecs = codecsMatch[1].split(',').map((c) => c.trim());
      // видео v1 — HEVC (hvc1), звук — AAC-LC
      assert.ok(codecs[0].startsWith('hvc1'), `Видеокодек не hvc1: ${codecs[0]}`);
      for (const codec of codecs.slice(1)) {
        assert.equal(codec, 'mp4a.40.2', `Звук не AAC-LC: ${codec}`);
      }
      const nextLine = lines[i + 1];
      assert.ok(nextLine && !nextLine.startsWith('#'), 'Ожидался URI после #EXT-X-STREAM-INF');
      uris.push(nextLine.trim());
    }
  }

  assert.ok(hasAudGroup, 'Отсутствует GROUP-ID="aud"');
  assert.ok(hasSubGroup, 'Отсутствует GROUP-ID="sub"');
  assert.ok(uris.length > 0, 'Не найдены URI в master-v1.m3u8');

  for (const uri of uris) {
    const m = uri.match(urlRegex);
    assert.ok(m, `URI не соответствует шаблону: ${uri}`);
    const [, sig, exp, id] = m;
    assert.equal(sig, cdnSig(exp, id, secret));
  }
});

test('6) media-1080p.m3u8: непрерывность BYTERANGE, теги VOD/ENDLIST и TARGETDURATION', () => {
  const content = readFixture('media-1080p.m3u8');
  const lines = content.split(/\r?\n/);

  assert.ok(lines.includes('#EXT-X-ENDLIST'), 'Отсутствует #EXT-X-ENDLIST');
  assert.ok(lines.includes('#EXT-X-PLAYLIST-TYPE:VOD'), 'Отсутствует #EXT-X-PLAYLIST-TYPE:VOD');

  const targetMatch = content.match(/^#EXT-X-TARGETDURATION:(\d+)/m);
  assert.ok(targetMatch, 'Отсутствует #EXT-X-TARGETDURATION');
  const targetDuration = Number(targetMatch[1]);

  const mapMatch = content.match(/^#EXT-X-MAP:.*BYTERANGE="(\d+)@(\d+)"/m);
  assert.ok(mapMatch, 'Отсутствует #EXT-X-MAP с BYTERANGE');
  const mapLen = Number(mapMatch[1]);
  const mapOff = Number(mapMatch[2]);

  let expectedOff = mapOff + mapLen;
  let byterangeCount = 0;

  for (const line of lines) {
    const brMatch = line.match(/^#EXT-X-BYTERANGE:(\d+)@(\d+)$/);
    if (brMatch) {
      const len = Number(brMatch[1]);
      const off = Number(brMatch[2]);
      assert.equal(off, expectedOff, `Разрыв BYTERANGE: ожидалось ${expectedOff}, получено ${off}`);
      expectedOff = off + len;
      byterangeCount++;
    }

    const extinfMatch = line.match(/^#EXTINF:([0-9.]+)/);
    if (extinfMatch) {
      const dur = Number(extinfMatch[1]);
      assert.ok(
        Math.round(dur) <= targetDuration,
        `EXTINF ${dur} превышает TARGETDURATION ${targetDuration} (RFC 8216 4.3.3.1)`
      );
    }
  }

  assert.ok(byterangeCount > 0, 'Не найдено ни одного #EXT-X-BYTERANGE');
});

test('7) subs-s0.m3u8: ровно один #EXTINF и следующая непустая строка subs.vtt', () => {
  const content = readFixture('subs-s0.m3u8');
  const lines = content.split(/\r?\n/);
  const extinfIndices = [];

  for (let i = 0; i < lines.length; i++) {
    if (lines[i].startsWith('#EXTINF:')) {
      extinfIndices.push(i);
    }
  }

  assert.equal(extinfIndices.length, 1, 'Ожидался ровно один #EXTINF');
  const nextNonEmpty = lines.slice(extinfIndices[0] + 1).find((l) => l.trim() !== '');
  assert.equal(nextNonEmpty, 'subs.vtt');
});

test('8) JSON-фикстуры: парсинг и валидация схем', () => {
  const jsonFiles = fs.readdirSync(FIXTURES_DIR).filter((f) => f.endsWith('.json'));
  assert.ok(jsonFiles.length > 0);

  for (const file of jsonFiles) {
    const data = readJson(file);
    assert.ok(data !== null && typeof data === 'object', `Некорректный JSON: ${file}`);

    if (file.startsWith('variant-url-')) {
      const { hls } = data;
      assert.ok(hls && typeof hls === 'object');
      assert.ok(['none', 'encoding', 'restoring', 'ready'].includes(hls.status));
      assert.equal(typeof hls.direct_ok, 'boolean');
      assert.ok(hls.direct && typeof hls.direct === 'object');
      assert.equal(typeof hls.direct.ext, 'string');
      assert.equal(typeof hls.direct.vcodec, 'string');
      assert.equal(typeof hls.direct.acodec, 'string');

      if (hls.status === 'ready') {
        const parsed = new URL(hls.url);
        const pathMatch = parsed.pathname.match(/^\/api\/hls\/([0-9a-f]{16})\/master\.m3u8$/);
        assert.ok(pathMatch, `Неожиданный путь: ${parsed.pathname}`);
        const id = pathMatch[1];
        const sig = parsed.searchParams.get('sig');
        const exp = parsed.searchParams.get('exp');
        assert.match(sig, /^[0-9a-f]{64}$/);
        assert.equal(sig, masterSig(Number(exp), id, vectors.master_sign[0].session_secret));
      }
    } else if (file.startsWith('probe-response-')) {
      assert.ok(['alias', 'encode', 'wait'].includes(data.action));
      if (data.action === 'encode') {
        assert.ok(['v1', 'v1-copy'].includes(data.profile));
      }
    } else if (file === 'manifest.json') {
      assert.match(data.hls_id, /^[0-9a-f]{16}$/);
      assert.equal(data.source.fingerprint, vectors.fingerprint[2].fingerprint);
    }
  }
});

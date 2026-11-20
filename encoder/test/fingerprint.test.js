'use strict';

// Отпечаток (§7) = vectors.json → fingerprint: и по буферу, и по HTTP Range через «зеркало» заглушки.

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');

const { fingerprintBuffer, fingerprintUrl, fingerprintRanges, MiB } = require('../src/fingerprint');
const { serveFile } = require('../stub/server');

const vectors = JSON.parse(fs.readFileSync(path.resolve(__dirname, '..', '..', 'docs', 'hls-fixtures', 'vectors.json'), 'utf8'));

// byte[i] = uint32(i * 2654435761) >> 24 (vectors.json → generator)
function gen(size) {
  const b = Buffer.alloc(size);
  for (let i = 0; i < size; i++) b[i] = Math.imul(i, 2654435761) >>> 24;
  return b;
}

test('диапазоны отпечатка совпадают со смещениями векторов', () => {
  for (const v of vectors.fingerprint) {
    const r = fingerprintRanges(v.size);
    if (v.mode === 'whole') assert.deepEqual(r, [[0, v.size]]);
    else assert.deepEqual(r.map(([a]) => a), v.offsets);
    if (v.mode === 'three') for (const [a, b] of r) assert.equal(b - a, MiB);
  }
});

test('отпечаток буфера = vectors.json', () => {
  for (const v of vectors.fingerprint) {
    const buf = gen(v.size);
    assert.equal(fingerprintBuffer(buf), v.fingerprint, `size ${v.size}`);
    if (v.chunk_sha256) {
      v.offsets.forEach((o, i) => {
        assert.equal(crypto.createHash('sha256').update(buf.subarray(o, o + MiB)).digest('hex'), v.chunk_sha256[i]);
      });
    }
  }
});

test('отпечаток по HTTP Range = vectors.json, читаются только куски', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sm-fp-'));
  const ranges = [];
  const server = http.createServer((req, res) => {
    ranges.push(req.headers.range);
    serveFile(req, res, path.join(dir, decodeURIComponent(new URL(req.url, 'http://x').pathname.slice(1))));
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    for (const v of vectors.fingerprint) {
      const name = `f${v.size}.bin`;
      fs.writeFileSync(path.join(dir, name), gen(v.size));
      ranges.length = 0;
      const fp = await fingerprintUrl(`${base}/${name}?link=x&exp=1&sig=y`, v.size);
      assert.equal(fp, v.fingerprint, `size ${v.size}`);
      if (v.mode === 'three') {
        assert.deepEqual(ranges, v.offsets.map((o) => `bytes=${o}-${o + MiB - 1}`));
      } else {
        assert.deepEqual(ranges, [`bytes=0-${v.size - 1}`]);
      }
    }
    // 4xx (нет файла, истёкшая подпись) — ошибка без бесконечных повторов
    await assert.rejects(fingerprintUrl(`${base}/nope.bin`, 5 * MiB, { retries: 1 }), /HTTP 404/);
  } finally {
    server.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

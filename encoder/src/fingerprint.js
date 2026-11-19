'use strict';

// Отпечаток контента (docs/HLS.md §7):
// кусок 1 МиБ; файл < 3 МиБ — целиком, иначе смещения 0, floor(size/2) − 512 КиБ, size − 1 МиБ;
// fingerprint = sha256( utf8("<size>:") ‖ c1 ‖ c2 ‖ c3 ) hex.

const crypto = require('node:crypto');

const MiB = 1024 * 1024;
const KiB = 1024;

/** Диапазоны [start, endExclusive], которые входят в отпечаток. */
function fingerprintRanges(size) {
  if (!Number.isSafeInteger(size) || size < 0) throw new Error(`плохой размер: ${size}`);
  if (size < 3 * MiB) return [[0, size]];
  const mid = Math.floor(size / 2) - 512 * KiB;
  return [[0, MiB], [mid, mid + MiB], [size - MiB, size]];
}

function fingerprintFromChunks(size, chunks) {
  const h = crypto.createHash('sha256');
  h.update(Buffer.from(`${size}:`, 'utf8'));
  for (const c of chunks) h.update(c);
  return h.digest('hex');
}

/** Отпечаток буфера целиком (тесты, локальные файлы). */
function fingerprintBuffer(buf) {
  const chunks = fingerprintRanges(buf.length).map(([a, b]) => buf.subarray(a, b));
  return fingerprintFromChunks(buf.length, chunks);
}

/** Читает [start, end) по HTTP Range; проверяет 206 и длину. */
async function fetchRange(url, start, end, { signal, retries = 3, headers = {} } = {}) {
  if (end <= start) return Buffer.alloc(0);
  let lastErr;
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const res = await fetch(url, { headers: { ...headers, Range: `bytes=${start}-${end - 1}` }, signal, redirect: 'follow' });
      if (res.status !== 206 && !(res.status === 200 && start === 0)) {
        const err = new Error(`Range ${start}-${end - 1}: HTTP ${res.status}`);
        err.status = res.status;
        // 4xx (кроме 408/429) — повтор не поможет
        if (res.status >= 400 && res.status < 500 && res.status !== 408 && res.status !== 429) { err.fatal = true; throw err; }
        throw err;
      }
      // 200 на запрос с нуля (сервер без Range) — берём только нужную часть, остальное обрываем
      const want = end - start;
      const parts = [];
      let got = 0;
      const reader = res.body.getReader();
      while (got < want) {
        const { done, value } = await reader.read();
        if (done) break;
        parts.push(Buffer.from(value));
        got += value.length;
      }
      try { await reader.cancel(); } catch { /* ignore */ }
      const buf = Buffer.concat(parts);
      if (buf.length < want) throw new Error(`Range ${start}-${end - 1}: получено ${buf.length} из ${want} байт`);
      return buf.subarray(0, want);
    } catch (e) {
      lastErr = e;
      if (e.fatal || signal?.aborted) break;
      await new Promise((r) => setTimeout(r, 1000 * (attempt + 1)));
    }
  }
  throw lastErr;
}

/** Отпечаток файла на зеркале: три (или один) Range-запроса. */
async function fingerprintUrl(url, size, opts = {}) {
  const chunks = [];
  for (const [a, b] of fingerprintRanges(size)) chunks.push(await fetchRange(url, a, b, opts));
  return fingerprintFromChunks(size, chunks);
}

module.exports = { fingerprintRanges, fingerprintFromChunks, fingerprintBuffer, fingerprintUrl, fetchRange, MiB };

#!/usr/bin/env node
'use strict';

// Заглушка основного сервера для локальной проверки воркера (до T4): ответы из docs/hls-fixtures/,
// «зеркало» — локальный файл с поддержкой Range.
//
//   node stub/server.js <видеофайл> [--port 8787] [--profile v1|v1-copy|alias|wait] [--jobs 1] [--token test-token]
//
// Затем воркер: SM_SITE_URL=http://127.0.0.1:8787 HLS_WORKER_TOKEN=test-token SM_UPLOAD_MODE=local
//               SM_UPLOAD_LOCAL_DIR=./work-cdn SM_ONCE=1 node index.js

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const FIXTURES = path.resolve(__dirname, '..', '..', 'docs', 'hls-fixtures');
const fixture = (name) => JSON.parse(fs.readFileSync(path.join(FIXTURES, name), 'utf8'));

function serveFile(req, res, file) {
  let st;
  try { st = fs.statSync(file); } catch { res.writeHead(404).end(); return; }
  const size = st.size;
  const range = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range || '');
  let start = 0;
  let end = size - 1;
  let status = 200;
  if (range) {
    if (range[1] === '') { start = Math.max(0, size - Number(range[2])); } else {
      start = Number(range[1]);
      if (range[2] !== '') end = Math.min(size - 1, Number(range[2]));
    }
    if (start >= size || start > end) {
      res.writeHead(416, { 'Content-Range': `bytes */${size}` }).end();
      return;
    }
    status = 206;
  }
  const headers = { 'Content-Length': end - start + 1, 'Accept-Ranges': 'bytes', 'Content-Type': 'application/octet-stream' };
  if (status === 206) headers['Content-Range'] = `bytes ${start}-${end}/${size}`;
  res.writeHead(status, headers);
  if (req.method === 'HEAD') { res.end(); return; }
  const s = fs.createReadStream(file, { start, end });
  s.pipe(res);
  res.on('close', () => s.destroy());
}

function readJson(req, limit = 256 * 1024) {
  return new Promise((resolve, reject) => {
    let n = 0;
    const parts = [];
    req.on('data', (d) => { n += d.length; if (n > limit) { reject(new Error('слишком большое тело')); req.destroy(); } else parts.push(d); });
    req.on('end', () => { try { resolve(parts.length ? JSON.parse(Buffer.concat(parts).toString('utf8')) : {}); } catch (e) { reject(e); } });
    req.on('error', reject);
  });
}

/**
 * @param opts.mediaFile  локальный файл, который отдаёт «зеркало»
 * @param opts.profile    v1 | v1-copy | alias | wait (wait → один раз wait, потом encode v1)
 * @param opts.jobs       сколько заданий выдать через /next
 */
function createStub({ mediaFile, profile = 'v1', jobs = 1, token = 'test-token', port = 0, host = '127.0.0.1', waitS = 1, log = () => {} } = {}) {
  const state = { next: 0, probe: [], progress: [], done: [], failed: [], issued: [] };
  let left = jobs;
  let waited = false;
  const size = fs.statSync(mediaFile).size;
  const name = path.basename(mediaFile);
  const base = fixture('next-job.json');
  const srcPath = `Sample/${name}`;
  const hlsId = crypto.createHash('sha1').update(`${base.hash}|${srcPath}|${size}`).digest('hex').slice(0, 16);

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x');
    const send = (code, body) => {
      if (body === undefined) { res.writeHead(code).end(); return; }
      const t = JSON.stringify(body);
      res.writeHead(code, { 'Content-Type': 'application/json' }).end(t);
    };
    try {
      if (url.pathname.startsWith('/media/')) { serveFile(req, res, mediaFile); return; }
      if (url.pathname === '/stub/state') { send(200, state); return; }
      if (!url.pathname.startsWith('/internal/hls/')) { send(404, { error: 'not found' }); return; }
      if (req.headers.authorization !== `Bearer ${token}`) { send(401, { error: 'unauthorized' }); return; }
      const ep = url.pathname.slice('/internal/hls/'.length);
      if (ep === 'next' && req.method === 'GET') {
        state.next++;
        if (left <= 0) { send(204); return; }
        left--;
        const addr = server.address();
        const job = {
          ...base,
          job_id: `j_stub_${state.issued.length + 1}`,
          path: srcPath,
          size,
          mirror_url: `http://${host}:${addr.port}/media/${encodeURIComponent(name)}?link=stub&exp=0&sig=stub`,
        };
        state.issued.push(job);
        log(`next → ${job.job_id}`);
        send(200, job);
        return;
      }
      if (req.method !== 'POST') { send(405, { error: 'method' }); return; }
      const body = await readJson(req);
      if (ep === 'probe') {
        state.probe.push(body);
        let r;
        if (profile === 'alias') r = fixture('probe-response-alias.json');
        else if (profile === 'wait' && !waited) { waited = true; r = { ...fixture('probe-response-wait.json'), retry_after_s: waitS }; }
        else if (profile === 'v1-copy') r = { ...fixture('probe-response-encode-copy.json'), hls_id: hlsId };
        else r = { ...fixture('probe-response-encode.json'), hls_id: hlsId };
        log(`probe ${body.job_id} → ${r.action} ${r.profile || ''}`);
        send(200, r);
        return;
      }
      if (ep === 'progress') { state.progress.push(body); send(200, { ok: true }); return; }
      if (ep === 'done') { state.done.push(body); log(`done ${JSON.stringify(body)}`); send(200, { ok: true }); return; }
      if (ep === 'failed') { state.failed.push(body); log(`failed ${JSON.stringify(body)}`); send(200, { ok: true }); return; }
      send(404, { error: 'not found' });
    } catch (e) {
      send(400, { error: e.message });
    }
  });

  return new Promise((resolve) => {
    server.listen(port, host, () => {
      const p = server.address().port;
      resolve({ url: `http://${host}:${p}`, state, hlsId, close: () => new Promise((r) => { server.closeAllConnections?.(); server.close(() => r()); }) });
    });
  });
}

module.exports = { createStub, serveFile };

if (require.main === module) {
  const argv = process.argv.slice(2);
  const opt = (k, d) => { const i = argv.indexOf(`--${k}`); return i >= 0 ? argv[i + 1] : d; };
  const file = argv.find((a, i) => !a.startsWith('--') && !(i > 0 && argv[i - 1].startsWith('--')));
  if (!file) {
    console.error('usage: node stub/server.js <видеофайл> [--port 8787] [--profile v1|v1-copy|alias|wait] [--jobs 1] [--token test-token]');
    process.exit(2);
  }
  createStub({
    mediaFile: path.resolve(file), port: Number(opt('port', 8787)), profile: opt('profile', 'v1'),
    jobs: Number(opt('jobs', 1)), token: opt('token', 'test-token'), log: (m) => console.log(`[stub] ${m}`),
  }).then((s) => console.log(`[stub] ${s.url} (hls_id ${s.hlsId})`));
}

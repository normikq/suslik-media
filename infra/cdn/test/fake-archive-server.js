#!/usr/bin/env node
// fake-archive-server.js — заглушка rclone serve http для test/archive-stream-offline.sh: раздаёт каталог ROOT с Range (206/Content-Range), 404 на отсутствующее, пишет журнал запросов.
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');

const [rootArg, portArg, logFile] = process.argv.slice(2);
if (!rootArg || !portArg || !logFile) {
  console.error('Использование: node fake-archive-server.js ROOT PORT LOGFILE');
  process.exit(1);
}

const ROOT = path.resolve(rootArg);
const PORT = Number(portArg);

function parseRange(header, size) {
  if (!header || !header.startsWith('bytes=')) return null;
  const spec = header.slice(6).trim();
  if (!spec || spec.includes(',')) return null; // мульти-диапазоны игнорируем

  const match = /^(\d*)-(\d*)$/.exec(spec);
  if (!match) return null;
  const [, startStr, endStr] = match;
  if (startStr === '' && endStr === '') return null;

  let start;
  let end;
  if (startStr === '') {
    const suffix = Number(endStr);
    if (!Number.isFinite(suffix) || suffix <= 0) return null;
    if (size === 0) return { unsatisfiable: true };
    start = Math.max(0, size - suffix);
    end = size - 1;
  } else {
    start = Number(startStr);
    end = endStr === '' ? size - 1 : Number(endStr);
    if (!Number.isFinite(start) || !Number.isFinite(end)) return null;
    if (start >= size) return { unsatisfiable: true };
    if (end < start) return null;
    if (end >= size) end = size - 1;
  }

  return { start, end };
}

const server = http.createServer((req, res) => {
  const rawUrl = req.url || '/';
  const rangeHeader = req.headers.range;
  fs.appendFileSync(logFile, `${req.method} ${rawUrl} ${rangeHeader || '-'}\n`);

  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { 'Content-Type': 'text/plain' });
    res.end('Method Not Allowed\n');
    return;
  }

  const rawPath = rawUrl.split('?')[0];
  let decodedPath;
  try {
    decodedPath = decodeURIComponent(rawPath);
  } catch {
    res.writeHead(400, { 'Content-Type': 'text/plain' });
    res.end('Bad Request\n');
    return;
  }

  const target = path.resolve(ROOT, '.' + decodedPath);
  if (target !== ROOT && !target.startsWith(ROOT + path.sep)) {
    res.writeHead(400, { 'Content-Type': 'text/plain' });
    res.end('Bad Request\n');
    return;
  }

  let stat;
  try {
    stat = fs.statSync(target);
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    if (req.method !== 'HEAD') res.end('Not Found\n');
    else res.end();
    return;
  }

  if (stat.isDirectory()) {
    const entries = fs.readdirSync(target);
    const items = entries.map((name) => `<a href="${name}">${name}</a>`).join('\n');
    const html = `<!doctype html><html><body><pre>\n${items}\n</pre></body></html>\n`;
    res.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Content-Length': Buffer.byteLength(html),
    });
    if (req.method !== 'HEAD') res.end(html);
    else res.end();
    return;
  }

  const size = stat.size;
  const baseHeaders = {
    'Accept-Ranges': 'bytes',
    'Content-Type': 'application/x-fake',
    'Cache-Control': 'max-age=999',
    'Last-Modified': stat.mtime.toUTCString(),
  };

  const range = parseRange(rangeHeader, size);
  if (range && range.unsatisfiable) {
    res.writeHead(416, {
      ...baseHeaders,
      'Content-Range': `bytes */${size}`,
    });
    res.end();
    return;
  }

  if (range) {
    const { start, end } = range;
    res.writeHead(206, {
      ...baseHeaders,
      'Content-Range': `bytes ${start}-${end}/${size}`,
      'Content-Length': end - start + 1,
    });
    if (req.method === 'HEAD') {
      res.end();
      return;
    }
    fs.createReadStream(target, { start, end }).pipe(res);
    return;
  }

  res.writeHead(200, {
    ...baseHeaders,
    'Content-Length': size,
  });
  if (req.method === 'HEAD' || size === 0) {
    res.end();
    return;
  }
  fs.createReadStream(target).pipe(res);
});

server.listen(PORT, '127.0.0.1', () => {
  const addr = server.address();
  console.log(`listening 127.0.0.1:${addr.port}`);
});

function shutdown() {
  setTimeout(() => process.exit(0), 500).unref(); // keep-alive соединения nginx не держат выход
  server.close(() => process.exit(0));
}

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

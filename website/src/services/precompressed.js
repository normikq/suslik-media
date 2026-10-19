"use strict";

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const zlib = require("zlib");
const config = require("../config");

const PRECOMPRESSED = { "/index.html": "index.html", "/redesign.css": "redesign.css" };
const precompressedCache = new Map(); // name -> { mtime, raw, gz, br, etag, type }

// CSP страницы: встроенные <script> разрешены только такими, какие они есть в файле (sha256), — внедрённый скрипт
// (XSS через название торрента и т. п.) не выполнится. Хеши пересчитываются при замене index.html.
function pageCsp(html) {
  const hashes = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)].map(
    m => `'sha256-${crypto.createHash("sha256").update(m[1], "utf8").digest("base64")}'`
  );
  return [
    "default-src 'self'",
    `script-src 'self' ${hashes.join(" ")}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob:",
    "font-src 'self' data:",
    "media-src 'self' blob:",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'self'",
    "frame-ancestors 'none'"
  ].join("; ");
}

function precompressed(name) {
  const file = path.join(config.PUBLIC_DIR, name);
  let st;
  try {
    st = fs.statSync(file);
  } catch {
    return null;
  }
  const hit = precompressedCache.get(name);
  if (hit && hit.mtime === st.mtimeMs) return hit;
  const raw = fs.readFileSync(file);
  const entry = {
    mtime: st.mtimeMs,
    raw,
    csp: name.endsWith(".html") ? pageCsp(raw.toString("utf8")) : null,
    gz: zlib.gzipSync(raw, { level: 9 }),
    br: zlib.brotliCompressSync(raw, { params: { [zlib.constants.BROTLI_PARAM_QUALITY]: 9 } }),
    etag: `"${crypto.createHash("sha1").update(raw).digest("base64url").slice(0, 22)}"`,
    type: name.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8"
  };
  precompressedCache.set(name, entry);
  return entry;
}

function sendPrecompressed(req, res, name) {
  const e = precompressed(name);
  if (!e) return false;
  if (e.csp) res.setHeader("Content-Security-Policy", e.csp);
  res.setHeader("Content-Type", e.type);
  res.setHeader("ETag", e.etag);
  res.setHeader("Vary", "Accept-Encoding");
  res.setHeader(
    "Cache-Control",
    name.endsWith(".css") && req.query && req.query.v ? "public, max-age=31536000, immutable" : "no-cache"
  );
  if (req.fresh) {
    res.status(304).end();
    return true;
  }
  const ae = String(req.headers["accept-encoding"] || "");
  const body = /\bbr\b/.test(ae)
    ? (res.setHeader("Content-Encoding", "br"), e.br)
    : /\bgzip\b/.test(ae)
      ? (res.setHeader("Content-Encoding", "gzip"), e.gz)
      : e.raw;
  res.setHeader("Content-Length", body.length);
  res.end(req.method === "HEAD" ? undefined : body);
  return true;
}

module.exports = {
  PRECOMPRESSED,
  pageCsp,
  precompressed,
  sendPrecompressed
};

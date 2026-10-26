"use strict";

const crypto = require("crypto");
const https = require("https");
const axios = require("axios");
const config = require("../config");
const { safeHttpsUrl, contentDisposition } = require("../utils/helpers");

const MIRROR_CSP = "sandbox; default-src 'none'";

function makeMirrorToken(link, expires) {
  return crypto.createHmac("sha256", config.SESSION_SECRET).update(`mirror:${expires}:${link}`).digest("hex");
}

function isValidMirrorToken(link, expires, token) {
  const now = Math.floor(Date.now() / 1000);
  if (
    !/^\d+$/.test(String(expires || "")) ||
    Number(expires) < now ||
    Number(expires) > now + config.MIRROR_TTL + 60 ||
    typeof token !== "string" ||
    !/^[0-9a-f]{64}$/i.test(token)
  ) {
    return false;
  }
  try {
    return crypto.timingSafeEqual(Buffer.from(makeMirrorToken(link, expires), "hex"), Buffer.from(token, "hex"));
  } catch {
    return false;
  }
}

function isAllDebridFileLink(parsed) {
  return (
    !!parsed &&
    ["alldebrid.com", "www.alldebrid.com"].includes(parsed.hostname.toLowerCase()) &&
    parsed.pathname.startsWith("/f/")
  );
}

function makePlaylistToken(id, idxStr, expires) {
  return crypto.createHmac("sha256", config.SESSION_SECRET).update(`playlist:${expires}:${id}:${idxStr}`).digest("hex");
}

function isValidPlaylistToken(id, idxStr, expires, token) {
  const now = Math.floor(Date.now() / 1000);
  if (
    !/^\d+$/.test(String(expires || "")) ||
    Number(expires) < now ||
    Number(expires) > now + config.MIRROR_TTL + 60 ||
    typeof token !== "string" ||
    !/^[0-9a-f]{64}$/i.test(token)
  ) {
    return false;
  }
  try {
    return crypto.timingSafeEqual(Buffer.from(makePlaylistToken(id, idxStr, expires), "hex"), Buffer.from(token, "hex"));
  } catch {
    return false;
  }
}

function mirrorLog(req, msg) {
  if (config.MIRROR_DEBUG) {
    console.log(
      `[зеркало] ${req.method} ${req.originalUrl.split("?")[0]} range=${req.headers.range || "-"} ua="${req.headers["user-agent"] || "-"}" -> ${msg}`
    );
  }
}

function mirrorAuth(req, res, next) {
  if (req.session && req.session.authenticated) {
    mirrorLog(req, "auth: session");
    return next();
  }
  const parsed = safeHttpsUrl(typeof req.query.link === "string" ? req.query.link.trim() : "");
  if (parsed && isValidMirrorToken(parsed.toString(), String(req.query.exp || ""), String(req.query.sig || ""))) {
    mirrorLog(req, "auth: signature ok");
    return next();
  }
  mirrorLog(req, `401 (link=${parsed ? "ok" : "BAD"} exp=${req.query.exp ? "present" : "MISSING"} sig=${req.query.sig ? "present" : "MISSING"})`);
  return res.status(401).send("Ссылка через зеркало устарела или недействительна. Создайте новую на сайте.");
}

const unlockCache = new Map(); // link -> { url, name, until }
const unlockInflight = new Map(); // link -> Promise<entry>

class UnlockError extends Error {}

async function resolveDirect(key, fresh) {
  if (!fresh) {
    const hit = unlockCache.get(key);
    if (hit && hit.until > Date.now()) return hit;
  } else {
    unlockCache.delete(key);
  }
  let pending = unlockInflight.get(key);
  if (!pending) {
    pending = (async () => {
      const unlocked = await axios.post(
        `${config.ALLDEBRID_API_BASE}/v4/link/unlock`,
        new URLSearchParams({ link: key }),
        {
          headers: {
            Authorization: `Bearer ${config.ALLDEBRID_API_KEY}`,
            "Content-Type": "application/x-www-form-urlencoded"
          },
          timeout: 20000,
          validateStatus: () => true
        }
      );
      if (unlocked.status < 200 || unlocked.status >= 300 || unlocked.data?.status === "error") {
        throw new UnlockError(unlocked.data?.error?.message || "Не удалось получить прямую ссылку на этот файл.");
      }
      const found = safeHttpsUrl(unlocked.data?.data?.link || "");
      if (!found || (found.hostname.toLowerCase().endsWith("alldebrid.com") && found.pathname.startsWith("/f/"))) {
        throw new UnlockError("Не удалось получить прямой URL файла.");
      }
      const apiName = typeof unlocked.data?.data?.filename === "string" ? unlocked.data.data.filename : "";
      let urlName = "";
      try {
        urlName = decodeURIComponent(found.pathname.split("/").pop() || "");
      } catch {}
      const entry = { url: found.toString(), name: apiName || urlName, until: Date.now() + 10 * 60 * 1000 };
      unlockCache.set(key, entry);
      if (unlockCache.size > 500) {
        for (const [k, v] of unlockCache) {
          if (v.until < Date.now()) unlockCache.delete(k);
        }
        // Все ещё живы (много файлов за 10 минут): вытесняются самые старые записи.
        while (unlockCache.size > 500) unlockCache.delete(unlockCache.keys().next().value);
      }
      return entry;
    })().finally(() => unlockInflight.delete(key));
    unlockInflight.set(key, pending);
  }
  return pending;
}

// Keep-alive towards the file host: a seek or reconnect does not pay a new TCP+TLS handshake every time.
const upstreamAgent = new https.Agent({ keepAlive: true, maxSockets: 64, maxFreeSockets: 8, keepAliveMsecs: 15000 });

async function openUpstream(key, rangeHeaders) {
  let lastErr;
  for (let attempt = 0; attempt < 2; attempt++) {
    const entry = await resolveDirect(key, attempt > 0);
    try {
      const upstream = await axios.get(entry.url, {
        responseType: "stream",
        headers: rangeHeaders,
        timeout: 30000,
        maxRedirects: 5,
        decompress: false,
        httpsAgent: upstreamAgent,
        validateStatus: status => (status >= 200 && status < 300) || status === 206 || status === 416
      });
      return { entry, upstream };
    } catch (err) {
      lastErr = err;
      unlockCache.delete(key); // expired / rotated direct URL or a flaky host: resolve again once
      if (err && err.response && err.response.data && typeof err.response.data.destroy === "function") {
        err.response.data.destroy();
      }
    }
  }
  throw lastErr;
}

async function handleMirrorAccel(req, res, next) {
  if (!config.MIRROR_ACCEL || req.get("X-Mirror-Retry")) return next();
  const parsed = safeHttpsUrl(typeof req.query.link === "string" ? req.query.link.trim() : "");
  if (!isAllDebridFileLink(parsed)) return res.status(400).send("Некорректная ссылка на медиафайл.");
  let entry;
  try {
    entry = await resolveDirect(parsed.toString(), false);
  } catch (err) {
    mirrorLog(req, `502 ${err.message}`);
    return res.status(502).send(err instanceof UnlockError ? err.message : "Не удалось получить ссылку на файл.");
  }
  const u = new URL(entry.url);
  mirrorLog(req, `accel -> ${u.host}`);
  const disp = contentDisposition(req.params.name || entry.name);
  if (disp) res.setHeader("Content-Disposition", disp);
  res.setHeader("Cache-Control", "private, no-store");
  res.setHeader("Content-Security-Policy", MIRROR_CSP);
  // nginx unescapes the path of X-Accel-Redirect once and sends the captured path to the file host as it is: a name
  // with %20 / Cyrillic / %3F would go out raw (a space in the request line -> 400). Escaping "%" once more gives the
  // host exactly the original path.
  res.setHeader("X-Accel-Redirect", `/_alld/${u.protocol.replace(":", "")}/${u.host}${u.pathname.replace(/%/g, "%25")}${u.search}`);
  res.end();
}

async function handleMirrorStream(req, res) {
  const parsed = safeHttpsUrl(typeof req.query.link === "string" ? req.query.link.trim() : "");
  if (!isAllDebridFileLink(parsed)) return res.status(400).send("Некорректная ссылка на медиафайл.");
  const key = parsed.toString();
  if (req.get("X-Mirror-Retry")) unlockCache.delete(key); // nginx: the cached direct link failed, get a fresh one

  let upstream;
  let idleTimer;
  const stopIdle = () => clearTimeout(idleTimer);

  try {
    const headers = {};
    if (typeof req.headers.range === "string") headers.Range = req.headers.range;
    if (typeof req.headers["if-range"] === "string") headers["If-Range"] = req.headers["if-range"];

    // The player may already be gone (it reconnects aggressively): do not start work for a closed socket.
    let clientGone = false;
    res.on("close", () => { clientGone = true; });

    let entry;
    try {
      ({ entry, upstream } = await openUpstream(key, headers));
    } catch (err) {
      if (err instanceof UnlockError) {
        mirrorLog(req, `502 ${err.message}`);
        return res.status(502).send(err.message);
      }
      throw err;
    }

    if (clientGone) {
      upstream.data.destroy();
      return;
    }

    mirrorLog(
      req,
      `upstream ${upstream.status} type=${upstream.headers["content-type"] || "-"} len=${upstream.headers["content-length"] || "-"}`
    );
    res.status(upstream.status);
    for (const name of ["content-type", "content-length", "content-range", "accept-ranges", "last-modified", "etag"]) {
      if (upstream.headers[name]) res.setHeader(name, upstream.headers[name]);
    }

    // Some file hosts omit a useful MIME type; provide a conservative type for disc images/streams.
    if (!upstream.headers["content-type"] || /^application\/octet-stream$/i.test(upstream.headers["content-type"])) {
      const requestedName = String(req.params.name || entry.name || "").toLowerCase();
      if (requestedName.endsWith(".iso")) res.setHeader("Content-Type", "application/x-iso9660-image");
      else if (requestedName.endsWith(".m2ts") || requestedName.endsWith(".ts")) res.setHeader("Content-Type", "video/mp2t");
    }

    // Prefer the real file name: URL path name (set by the site) > AllDebrid filename > upstream header.
    const disp = contentDisposition(req.params.name || entry.name);
    if (disp) res.setHeader("Content-Disposition", disp);
    else if (upstream.headers["content-disposition"]) res.setHeader("Content-Disposition", upstream.headers["content-disposition"]);

    if (!upstream.headers["accept-ranges"]) res.setHeader("Accept-Ranges", "bytes");
    res.setHeader("Cache-Control", "private, no-store");
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Content-Security-Policy", MIRROR_CSP);
    res.setHeader("X-Accel-Buffering", "no"); // nginx: stream, do not buffer video to disk

    // HEAD (an external player, curl -I): the headers only. Express runs GET routes for HEAD and drops the body, but
    // the whole file was still pulled from the host.
    if (req.method === "HEAD") {
      upstream.data.destroy();
      return res.end();
    }

    res.flushHeaders();
    const data = upstream.data;

    // Idle watchdog. Not armed while we are waiting for the *client* (backpressure): a player with a full
    // buffer legitimately stops reading for a long time.
    const armIdle = () => {
      stopIdle();
      idleTimer = setTimeout(() => {
        if (data.destroyed) return;
        if (data.isPaused() || res.writableNeedDrain) return armIdle();
        mirrorLog(req, "upstream idle, closing so the player reconnects");
        data.destroy(new Error("upstream idle"));
      }, config.STREAM_IDLE_MS);
      idleTimer.unref?.();
    };

    data.on("data", armIdle);
    data.on("end", stopIdle);
    data.on("close", stopIdle);
    armIdle();

    data.on("error", () => {
      stopIdle();
      if (!res.headersSent) res.status(502);
      res.destroy();
    });

    res.on("close", () => {
      stopIdle();
      if (!res.writableEnded && !data.destroyed) data.destroy();
    });

    data.pipe(res);
  } catch (err) {
    stopIdle();
    mirrorLog(req, `502 ${err.message}`);
    if (upstream?.data && !upstream.data.destroyed) upstream.data.destroy();
    if (!res.headersSent) res.status(502).send("Не удалось передать этот файл через сервер. Попробуйте прямую ссылку.");
    else res.destroy();
  }
}

module.exports = {
  MIRROR_CSP,
  UnlockError,
  makeMirrorToken,
  isValidMirrorToken,
  isAllDebridFileLink,
  makePlaylistToken,
  isValidPlaylistToken,
  mirrorLog,
  mirrorAuth,
  resolveDirect,
  openUpstream,
  handleMirrorAccel,
  handleMirrorStream
};

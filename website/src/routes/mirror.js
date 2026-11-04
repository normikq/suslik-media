"use strict";

const express = require("express");
const config = require("../config");
const { apiLimiter, mirrorUrlLimiter, mirrorLimiter } = require("../middleware/rate-limit");
const { requireLogin } = require("../middleware/auth");
const { safeHttpsUrl, cleanFileName, contentDisposition, publicBase } = require("../utils/helpers");
const { getMagnetFileList } = require("../services/alldebrid");
const {
  makeMirrorToken,
  isValidMirrorToken,
  isAllDebridFileLink,
  makePlaylistToken,
  isValidPlaylistToken,
  mirrorAuth,
  handleMirrorAccel,
  handleMirrorStream
} = require("../services/mirror");
const { markOpened, markLinkOpened } = require("../services/cleanup");
const ca = require("../services/custom-audio");

const router = express.Router();

const PLAYLIST_MAX_ITEMS = 200;
const variantUrlCache = new Map();
const VARIANT_CACHE_TTL_MS = 15 * 60 * 1000;
const VARIANT_CACHE_MAX = 1000;

router.get("/api/file/mirror-url", requireLogin, mirrorUrlLimiter, (req, res) => {
  const parsed = safeHttpsUrl(typeof req.query.link === "string" ? req.query.link.trim() : "");
  if (!isAllDebridFileLink(parsed)) return res.status(400).json({ error: "Некорректная ссылка на медиафайл." });
  const link = parsed.toString();
  markLinkOpened(link);
  const name = cleanFileName(typeof req.query.name === "string" ? req.query.name.split(/[\\/]/).pop() : "");
  const expires = String(Math.floor(Date.now() / 1000) + config.MIRROR_TTL);
  res.setHeader("Cache-Control", "no-store");
  res.json({
    url: `${publicBase(req)}/api/file/proxy${name ? "/" + encodeURIComponent(name) : ""}?link=${encodeURIComponent(link)}&exp=${expires}&sig=${makeMirrorToken(link, expires)}`
  });
});

async function handleVariantUrl(req, res) {
  const src = req.method === "POST" ? (req.body || {}) : (req.query || {});
  let adId = Number(src.ad_id);
  let filePath = typeof src.path === "string" ? src.path.trim() : "";
  const variantKey = typeof src.variant_key === "string" ? src.variant_key.trim() : (typeof src.key === "string" ? src.key.trim() : "");
  if (variantKey) {
    const pipeIdx = variantKey.indexOf("|");
    if (pipeIdx > 0) {
      const parsedAd = Number(variantKey.slice(0, pipeIdx));
      const parsedPath = variantKey.slice(pipeIdx + 1).trim();
      if (Number.isSafeInteger(parsedAd) && parsedAd > 0 && parsedPath) {
        adId = parsedAd;
        filePath = parsedPath;
      }
    }
  }
  if (!Number.isSafeInteger(adId) || adId <= 0 || !filePath) {
    return res.status(400).json({ error: "Некорректный ad_id или путь к файлу варианта." });
  }

  const cacheKey = `${adId}|${filePath}`;
  const now = Date.now();
  const cached = variantUrlCache.get(cacheKey);
  if (cached && cached.expiresAt > now + 60 * 1000) {
    markLinkOpened(cached.sourceUrl);
    res.setHeader("Cache-Control", "no-store");
    const base = publicBase(req);
    return res.json({
      ok: true,
      url: `${base}${cached.proxyPath}`,
      source_url: cached.sourceUrl,
      name: cached.name,
      path: cached.path,
      size: cached.size,
      ad_id: cached.adId,
      variant_key: cacheKey,
      audio: (cached.audio || []).map(a => ({
        id: a.id,
        title: a.title,
        lang: a.lang,
        offset_ms: a.offset_ms,
        url: `${base}${a.proxyPath}`
      })),
      subtitles: (cached.subtitles || []).map(s => ({
        name: s.name,
        path: s.path,
        url: `${base}${s.proxyPath}`
      }))
    });
  }

  try {
    const list = await getMagnetFileList(adId);
    if (!list || !list.length) {
      return res.status(404).json({ error: "Для этой раздачи не найдено файлов." });
    }
    const norm = p => (typeof p === "string" ? p.replace(/\\/g, "/").replace(/^\/+|\/+$/g, "") : "");
    const targetNorm = norm(filePath);
    const hasSep = filePath.includes("/") || filePath.includes("\\");
    const f = list.find(it => {
      if (!it || it.type !== "file") return false;
      const ip = norm(it.path);
      if (ip === targetNorm) return true;
      if (typeof it.path === "string" && it.path.toLowerCase() === filePath.toLowerCase()) return true;
      if (targetNorm && ip.endsWith("/" + targetNorm)) return true;
      if (!hasSep) {
        return it.name === filePath || (typeof it.name === "string" && it.name.toLowerCase() === filePath.toLowerCase());
      }
      return false;
    });
    if (!f || !f.sourceLink) {
      return res.status(404).json({ error: "Файл варианта не найден в раздаче." });
    }
    const parsed = safeHttpsUrl(f.sourceLink);
    if (!isAllDebridFileLink(parsed)) {
      return res.status(502).json({ error: "Некорректная ссылка на медиафайл." });
    }
    const link = parsed.toString();
    markLinkOpened(link);
    const name = cleanFileName(typeof f.path === "string" ? f.path.split(/[\\/]/).pop() : (f.name || "video"));
    const expires = String(Math.floor(Date.now() / 1000) + config.MIRROR_TTL);
    const proxyPath = `/api/file/proxy${name ? "/" + encodeURIComponent(name) : ""}?link=${encodeURIComponent(link)}&exp=${expires}&sig=${makeMirrorToken(link, expires)}`;
    const base = publicBase(req);

    let customAudio = [];
    try {
      const { items } = await ca.trackLinks(adId);
      if (Array.isArray(items)) {
        customAudio = items.filter(it => !it.track.targetVideoPath || it.track.targetVideoPath === f.path).map(({ track: t, link: aLink }) => {
          const aName = cleanFileName(ca.baseName(t.audioPath)) || "audio";
          const aProxyPath = `/api/file/proxy/${encodeURIComponent(aName)}?link=${encodeURIComponent(aLink)}&exp=${expires}&sig=${makeMirrorToken(aLink, expires)}`;
          return {
            id: t.id,
            title: t.title,
            lang: t.lang,
            offset_ms: t.offsetMs || 0,
            proxyPath: aProxyPath,
            url: `${base}${aProxyPath}`
          };
        });
      }
    } catch {}

    const subExts = [".srt", ".vtt", ".ass", ".ssa", ".sub"];
    const allSubs = list.filter(it => it && it.type === "file" && it.sourceLink && typeof it.path === "string" && subExts.some(ext => it.path.toLowerCase().endsWith(ext)));

    const baseName = p => { const s = norm(p); const i = s.lastIndexOf("/"); return i < 0 ? s : s.slice(i + 1); };
    const dirName = p => { const s = norm(p); const i = s.lastIndexOf("/"); return i < 0 ? "" : s.slice(0, i); };
    const stripExt = name => { const i = name.lastIndexOf("."); return i < 0 ? name : name.slice(0, i); };
    const isLetterOrDigit = c => /\d/.test(c) || c.toLowerCase() !== c.toUpperCase();
    const sameBase = (name, base) => name.startsWith(base) && (name.length === base.length || !isLetterOrDigit(name[base.length]));

    let matchedSubs = [];
    if (allSubs.length > 0) {
      const vPath = norm(f.path);
      const vDir = dirName(vPath);
      const vBase = stripExt(baseName(vPath)).toLowerCase();
      const byName = allSubs.filter(s => sameBase(stripExt(baseName(s.path)).toLowerCase(), vBase));
      if (byName.length > 0) {
        matchedSubs = byName.slice(0, 8);
      } else {
        const videoCount = list.filter(it => it && it.type === "file" && typeof it.path === "string" && /\.(mkv|mp4|avi|mov|webm|m4v|m2ts|ts|iso)$/i.test(it.path) && !/(^|[\/._ -])sample([\/._ -]|$)/i.test(it.path)).length;
        if (videoCount <= 1) {
          matchedSubs = allSubs.slice(0, 8);
        } else {
          matchedSubs = allSubs.filter(s => {
            const d = dirName(s.path);
            return d === vDir || (d.startsWith(vDir ? vDir + "/" : "") && baseName(d).toLowerCase().startsWith("sub"));
          }).slice(0, 8);
        }
      }
    }

    const customSubs = matchedSubs.map(s => {
      const sParsed = safeHttpsUrl(s.sourceLink);
      if (!isAllDebridFileLink(sParsed)) return null;
      const sLink = sParsed.toString();
      const sName = cleanFileName(typeof s.path === "string" ? s.path.split(/[\\/]/).pop() : (s.name || "subtitles"));
      const sProxyPath = `/api/file/proxy/${encodeURIComponent(sName)}?link=${encodeURIComponent(sLink)}&exp=${expires}&sig=${makeMirrorToken(sLink, expires)}`;
      return {
        name: sName,
        path: s.path,
        proxyPath: sProxyPath,
        url: `${base}${sProxyPath}`
      };
    }).filter(Boolean);

    if (variantUrlCache.size >= VARIANT_CACHE_MAX) {
      const oldest = variantUrlCache.keys().next().value;
      variantUrlCache.delete(oldest);
    }

    variantUrlCache.set(cacheKey, {
      proxyPath,
      sourceUrl: link,
      name,
      path: f.path,
      size: f.size || 0,
      adId,
      audio: customAudio.map(a => ({ id: a.id, title: a.title, lang: a.lang, offset_ms: a.offset_ms, proxyPath: a.proxyPath })),
      subtitles: customSubs.map(s => ({ name: s.name, path: s.path, proxyPath: s.proxyPath })),
      expiresAt: now + VARIANT_CACHE_TTL_MS
    });

    res.setHeader("Cache-Control", "no-store");
    res.json({
      ok: true,
      url: `${base}${proxyPath}`,
      source_url: link,
      name,
      path: f.path,
      size: f.size || 0,
      ad_id: adId,
      variant_key: cacheKey,
      audio: customAudio.map(a => ({ id: a.id, title: a.title, lang: a.lang, offset_ms: a.offset_ms, url: a.url })),
      subtitles: customSubs.map(s => ({ name: s.name, path: s.path, url: s.url }))
    });
  } catch (err) {
    res.status(502).json({ error: err.message || "Не удалось разрешить ссылку варианта." });
  }
}

router.post("/api/file/variant-url", requireLogin, mirrorUrlLimiter, handleVariantUrl);
router.get("/api/file/variant-url", requireLogin, mirrorUrlLimiter, handleVariantUrl);

// Body: { magnetId, title, items: [{ index, link }] }. `link` is checked against the server's own list,
// so a stale browser snapshot (library changed since) is rejected instead of playing the wrong files.
router.post("/api/playlist", requireLogin, apiLimiter, async (req, res) => {
  const magnetId = Number(req.body?.magnetId);
  const items = Array.isArray(req.body?.items) ? req.body.items : [];
  if (!Number.isSafeInteger(magnetId) || magnetId <= 0) return res.status(400).json({ error: "Некорректный идентификатор magnet-ссылки." });
  if (!items.length || items.length > PLAYLIST_MAX_ITEMS) {
    return res.status(400).json({ error: `Плейлист должен содержать от 1 до ${PLAYLIST_MAX_ITEMS} серий.` });
  }
  try {
    const list = await getMagnetFileList(magnetId);
    if (!list) return res.status(404).json({ error: "Для этой magnet-ссылки не найдено файлов." });
    for (const item of items) {
      const f = Number.isInteger(item?.index) ? list[item.index] : null;
      if (!f || f.type !== "file" || !f.sourceLink || f.sourceLink !== item.link) {
        return res.status(409).json({ error: "Медиатека изменилась. Нажмите «Обновить» и повторите." });
      }
    }
    markOpened(magnetId);
    const idxStr = items.map(i => i.index).join(".");
    const expires = String(Math.floor(Date.now() / 1000) + config.MIRROR_TTL);
    const title = cleanFileName(typeof req.body?.title === "string" ? req.body.title : "") || "playlist";
    res.setHeader("Cache-Control", "no-store");
    res.json({
      url: `${publicBase(req)}/api/playlist/${magnetId}/${encodeURIComponent(title)}.m3u?f=${idxStr}&exp=${expires}&sig=${makePlaylistToken(magnetId, idxStr, expires)}`,
      count: items.length
    });
  } catch {
    res.status(502).json({ error: "Не удалось подготовить плейлист. Попробуйте ещё раз." });
  }
});

router.get(
  "/api/playlist/:id/:name",
  (req, res, next) => mirrorLimiter(req, res, next),
  async (req, res) => {
    const id = Number(req.params.id);
    const idxStr = typeof req.query.f === "string" ? req.query.f : "";
    if (
      !Number.isSafeInteger(id) ||
      !/^\d+(\.\d+){0,199}$/.test(idxStr) ||
      !isValidPlaylistToken(id, idxStr, String(req.query.exp || ""), String(req.query.sig || ""))
    ) {
      return res.status(401).send("Плейлист устарел или недействителен. Создайте новый на сайте.");
    }
    try {
      const list = await getMagnetFileList(id);
      if (!list) return res.status(404).send("Файлы не найдены.");
      const base = publicBase(req);
      const expires = String(Math.floor(Date.now() / 1000) + config.MIRROR_TTL);
      const lines = ["#EXTM3U"];
      for (const raw of idxStr.split(".")) {
        const f = list[Number(raw)];
        const parsed = f && f.type === "file" ? safeHttpsUrl(f.sourceLink || "") : null;
        if (!isAllDebridFileLink(parsed)) continue;
        const link = parsed.toString();
        const name = cleanFileName(f.path.split("/").pop()) || f.name;
        lines.push(
          `#EXTINF:-1,${name.replace(/\.[a-z0-9]{2,5}$/i, "")}`,
          `${base}/api/file/proxy/${encodeURIComponent(name)}?link=${encodeURIComponent(link)}&exp=${expires}&sig=${makeMirrorToken(link, expires)}`
        );
      }
      res.setHeader("Content-Type", "audio/x-mpegurl; charset=utf-8");
      res.setHeader("Content-Disposition", contentDisposition(req.params.name));
      res.setHeader("Cache-Control", "private, no-store");
      res.setHeader("X-Content-Type-Options", "nosniff");
      res.send("\uFEFF" + lines.join("\r\n") + "\r\n");
    } catch {
      res.status(502).send("Не удалось построить плейлист.");
    }
  }
);

router.get(["/api/file/proxy", "/api/file/proxy/:name"], mirrorAuth, mirrorLimiter, handleMirrorAccel, handleMirrorStream);

module.exports = router;

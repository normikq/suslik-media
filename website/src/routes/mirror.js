"use strict";

const express = require("express");
const config = require("../config");
const { apiLimiter, mirrorUrlLimiter, mirrorLimiter } = require("../middleware/rate-limit");
const { requireLogin, deviceId } = require("../middleware/auth");
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
const { isLibraryAdmin } = require("../services/downloads");
const { getUser, SHARED_USER } = require("../services/users");
const ca = require("../services/custom-audio");
const { findFile, pickAudio, pickSubtitles } = require("../services/sidecars");

function authorOf(req, res) {
  if (!req.customAudioAuthor) {
    req.customAudioAuthor = req.user && req.user.id !== SHARED_USER.id ? req.user.id : `dev_${deviceId(req, res)}`;
  }
  return req.customAudioAuthor;
}

function canEditCustomTrack(req, res, track) {
  return isLibraryAdmin(req) || track.addedBy === authorOf(req, res);
}

async function resolveCustomAudioForVariant(adId, filePath, req, res, base) {
  const expires = String(Math.floor(Date.now() / 1000) + config.MIRROR_TTL);
  try {
    const { items } = await ca.trackLinks(adId, filePath);
    if (!Array.isArray(items)) return [];
    return items.filter(it => it && it.track).map(({ track: t, link: aLink }) => {
      const aName = cleanFileName(ca.baseName(t.audioPath)) || "audio";
      const aProxyPath = `/api/file/proxy/${encodeURIComponent(aName)}?link=${encodeURIComponent(aLink)}&exp=${expires}&sig=${makeMirrorToken(aLink, expires)}`;
      const author = /^u_\d+$/.test(t.addedBy) ? getUser(t.addedBy) : null;
      const offsetVal = typeof t.offsetMs === "number" ? t.offsetMs : 0;
      return {
        id: t.id,
        movieMagnetId: t.movieMagnetId || adId,
        audioMagnetId: t.audioMagnetId,
        videoPath: t.videoPath,
        video_path: t.videoPath,
        audioPath: t.audioPath,
        audio_path: t.audioPath,
        path: t.audioPath,
        title: t.title,
        lang: t.lang,
        offsetMs: offsetVal,
        offset_ms: offsetVal,
        addedBy: t.addedBy,
        addedByName: author ? author.name : null,
        createdAt: t.createdAt,
        mine: canEditCustomTrack(req, res, t),
        url: `${base}${aProxyPath}`
      };
    });
  } catch {
    return [];
  }
}

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

// Подписанный путь зеркала для файла раздачи.
function mirrorPath(link, name, expires) {
  return `/api/file/proxy${name ? "/" + encodeURIComponent(name) : ""}?link=${encodeURIComponent(link)}&exp=${expires}&sig=${makeMirrorToken(link, expires)}`;
}

// Файл рядом с видео (озвучка, субтитры) -> запись кэша с относительным путём зеркала; null, если ссылка не от AllDebrid.
function sidecarEntry(item, expires, fallback) {
  const parsed = safeHttpsUrl(item.sourceLink);
  if (!isAllDebridFileLink(parsed)) return null;
  const name = cleanFileName(item.path.split(/[\\/]/).pop()) || fallback;
  return { name, path: item.path, proxyPath: mirrorPath(parsed.toString(), name, expires) };
}

// Кэш хранит всё, что не зависит от запроса (пути без хоста); адрес сервера и «мои» озвучки добавляются при выдаче.
function renderVariant(entry, base, customAudio) {
  return {
    ok: true,
    url: `${base}${entry.proxyPath}`,
    source_url: entry.sourceUrl,
    name: entry.name,
    path: entry.path,
    size: entry.size,
    ad_id: entry.adId,
    variant_key: entry.key,
    audio: [
      ...entry.audio.map(a => ({
        id: `sidecar:${a.path}`,
        title: a.name,
        path: a.path,
        audio_path: a.path,
        video_path: "",
        offset_ms: 0,
        url: `${base}${a.proxyPath}`
      })),
      ...customAudio
    ],
    subtitles: entry.subtitles.map(s => ({ name: s.name, path: s.path, url: `${base}${s.proxyPath}` }))
  };
}

function pruneVariantCache(now) {
  for (const [k, e] of variantUrlCache) if (e.expiresAt <= now) variantUrlCache.delete(k);
  while (variantUrlCache.size >= VARIANT_CACHE_MAX) variantUrlCache.delete(variantUrlCache.keys().next().value);
}

// Ключ варианта — "<adId>|<путь файла>" (как watchKey в приложениях); можно передать и ad_id + path.
function parseVariantRequest(body) {
  let adId = Number(body.ad_id);
  let filePath = typeof body.path === "string" ? body.path.trim() : "";
  const variantKey = typeof body.variant_key === "string" ? body.variant_key.trim() : (typeof body.key === "string" ? body.key.trim() : "");
  const bar = variantKey.indexOf("|");
  if (bar > 0) {
    const parsedAd = Number(variantKey.slice(0, bar));
    const parsedPath = variantKey.slice(bar + 1).trim();
    if (Number.isSafeInteger(parsedAd) && parsedAd > 0 && parsedPath) { adId = parsedAd; filePath = parsedPath; }
  }
  if (!Number.isSafeInteger(adId) || adId <= 0 || !filePath) return null;
  return { adId, filePath };
}

// Только POST: путь файла не должен попадать в строку запроса и логи nginx.
router.post("/api/file/variant-url", requireLogin, mirrorUrlLimiter, async (req, res) => {
  const reqInfo = parseVariantRequest(req.body || {});
  if (!reqInfo) return res.status(400).json({ error: "Некорректный ad_id или путь к файлу варианта." });
  const { adId, filePath } = reqInfo;
  const key = `${adId}|${filePath}`;
  const now = Date.now();
  const base = publicBase(req);
  res.setHeader("Cache-Control", "no-store");

  let entry = variantUrlCache.get(key);
  if (entry && entry.expiresAt <= now) { variantUrlCache.delete(key); entry = null; }

  try {
    if (!entry) {
      const list = await getMagnetFileList(adId);
      if (!list || !list.length) return res.status(404).json({ error: "Для этой раздачи не найдено файлов." });
      const f = findFile(list, filePath);
      if (!f || !f.sourceLink) return res.status(404).json({ error: "Файл варианта не найден в раздаче." });
      const parsed = safeHttpsUrl(f.sourceLink);
      if (!isAllDebridFileLink(parsed)) return res.status(502).json({ error: "Некорректная ссылка на медиафайл." });
      const link = parsed.toString();
      const expSec = Math.floor(now / 1000) + config.MIRROR_TTL;
      const expires = String(expSec);
      const name = cleanFileName(f.path.split(/[\\/]/).pop() || f.name || "video");
      entry = {
        key,
        adId,
        sourceUrl: link,
        name,
        path: f.path,
        size: f.size || 0,
        proxyPath: mirrorPath(link, name, expires),
        audio: pickAudio(list, f.path).map(s => sidecarEntry(s, expires, "audio")).filter(Boolean),
        subtitles: pickSubtitles(list, f.path).map(s => sidecarEntry(s, expires, "subtitles")).filter(Boolean),
        // кэш живёт не дольше подписи ссылок (с запасом минуту, чтобы плеер успел начать)
        expiresAt: Math.min(now + VARIANT_CACHE_TTL_MS, expSec * 1000 - 60 * 1000)
      };
      pruneVariantCache(now);
      variantUrlCache.set(key, entry);
    }
    markOpened(adId);
    markLinkOpened(entry.sourceUrl);
    // озвучки пользователей не кэшируем: «mine» зависит от того, кто спрашивает
    const customAudio = await resolveCustomAudioForVariant(adId, entry.path, req, res, base);
    res.json(renderVariant(entry, base, customAudio));
  } catch (err) {
    res.status(502).json({ error: err.message || "Не удалось разрешить ссылку варианта." });
  }
});

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

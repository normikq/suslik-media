"use strict";

const express = require("express");
const axios = require("axios");
const config = require("../config");
const { apiLimiter, mirrorUrlLimiter, mirrorLimiter } = require("../middleware/rate-limit");
const { requireLogin } = require("../middleware/auth");
const { safeHttpsUrl, cleanFileName, contentDisposition } = require("../utils/helpers");
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

const router = express.Router();

const PLAYLIST_MAX_ITEMS = 200;

// Resolve an AllDebrid magnet file's /f/ page into a true download URL.
// Never return the original account-page URL as if it were a direct link.
router.post("/api/file/unlock", requireLogin, apiLimiter, async (req, res) => {
  const raw = typeof req.body?.link === "string" ? req.body.link.trim() : "";
  const parsed = safeHttpsUrl(raw);
  if (!parsed || !["alldebrid.com", "www.alldebrid.com"].includes(parsed.hostname.toLowerCase()) || !parsed.pathname.startsWith("/f/")) {
    return res.status(400).json({ error: "Некорректная ссылка на файл AllDebrid." });
  }
  markLinkOpened(parsed.toString()); // фильм открыли: автоочистка его не тронет ещё 10 дней
  try {
    const response = await axios.post(
      `${config.ALLDEBRID_API_BASE}/v4/link/unlock`,
      new URLSearchParams({ link: parsed.toString() }),
      {
        headers: {
          Authorization: `Bearer ${config.ALLDEBRID_API_KEY}`,
          "Content-Type": "application/x-www-form-urlencoded"
        },
        timeout: 20000,
        validateStatus: () => true
      }
    );
    if (response.status < 200 || response.status >= 300 || response.data?.status === "error") {
      return res.status(502).json({ error: response.data?.error?.message || "AllDebrid не смог создать прямую ссылку на скачивание этого файла." });
    }
    const result = response.data?.data || {};
    const direct = typeof result.link === "string" ? safeHttpsUrl(result.link) : null;
    if (!direct || (direct.hostname.toLowerCase().endsWith("alldebrid.com") && direct.pathname.startsWith("/f/"))) {
      return res.status(502).json({
        error: "AllDebrid вернул страницу файла вместо прямой ссылки для скачивания. API не предоставил ссылку для воспроизведения."
      });
    }
    res.json({
      ok: true,
      url: direct.toString(),
      filename: result.filename || "Ссылка для скачивания",
      filesize: result.filesize || null,
      host: result.host || null
    });
  } catch (err) {
    res.status(502).json({ error: "Не удалось получить прямую ссылку AllDebrid. Попробуйте ещё раз." });
  }
});

router.get("/api/file/mirror-url", requireLogin, mirrorUrlLimiter, (req, res) => {
  const parsed = safeHttpsUrl(typeof req.query.link === "string" ? req.query.link.trim() : "");
  if (!isAllDebridFileLink(parsed)) return res.status(400).json({ error: "Некорректная ссылка на файл AllDebrid." });
  const link = parsed.toString();
  markLinkOpened(link);
  const name = cleanFileName(typeof req.query.name === "string" ? req.query.name.split(/[\\/]/).pop() : "");
  const expires = String(Math.floor(Date.now() / 1000) + config.MIRROR_TTL);
  res.setHeader("Cache-Control", "no-store");
  res.json({
    url: `https://${req.get("host")}/api/file/proxy${name ? "/" + encodeURIComponent(name) : ""}?link=${encodeURIComponent(link)}&exp=${expires}&sig=${makeMirrorToken(link, expires)}`
  });
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
      url: `https://${req.get("host")}/api/playlist/${magnetId}/${encodeURIComponent(title)}.m3u?f=${idxStr}&exp=${expires}&sig=${makePlaylistToken(magnetId, idxStr, expires)}`,
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
      const host = req.get("host");
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
          `https://${host}/api/file/proxy/${encodeURIComponent(name)}?link=${encodeURIComponent(link)}&exp=${expires}&sig=${makeMirrorToken(link, expires)}`
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

router.get(["/api/file/proxy", "/api/file/proxy/:name"], mirrorAuth, mirrorLimiter, handleMirrorAccel);
router.get(["/api/file/proxy", "/api/file/proxy/:name"], mirrorAuth, handleMirrorStream);

module.exports = router;

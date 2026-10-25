"use strict";

const express = require("express");
const path = require("path");
const crypto = require("crypto");
const axios = require("axios");
const config = require("../config");
const { apiLimiter } = require("../middleware/rate-limit");
const { requireLogin } = require("../middleware/auth");
const { adUploadMagnet, adUploadTorrent, flattenMagnetFiles } = require("../services/alldebrid");
const { reserveDownloadSlot, releaseTrackedDownload, canRemove, isLibraryAdmin } = require("../services/downloads");
const { deleteMagnet } = require("../services/cleanup");
const { getLibrary, libraryChanged } = require("../services/library");

const router = express.Router();

// Magnet support: submit a magnet, check its status, then retrieve available files.
router.post("/api/magnets", requireLogin, apiLimiter, async (req, res) => {
  const magnet = typeof req.body?.magnet === "string" ? req.body.magnet.trim() : "";
  if (!magnet || magnet.length > 5000 || !magnet.startsWith("magnet:?")) {
    return res.status(400).json({ error: "Введите корректную ссылку magnet:?." });
  }
  const slot = reserveDownloadSlot(req, res);
  if (!slot) return;
  try {
    const item = await adUploadMagnet(magnet);
    slot.attach(item.id, Boolean(item.ready) || Number(item.statusCode) === 4);
    const hash = typeof item.hash === "string" ? item.hash.trim().toLowerCase() : null;
    const sha256 = hash ? crypto.createHash("sha256").update(hash).digest("hex") : null;
    res.json({ ok: true, id: item.id, name: item.name || "Magnet", ready: Boolean(item.ready), hash, sha256 });
  } catch (err) {
    slot.release();
    res.status(err.status || 502).json({ error: err.message || "Не удалось отправить magnet-ссылку." });
  }
});

// Upload a .torrent file to AllDebrid; each IP shares the same two-download limit.
router.post(
  "/api/torrents",
  requireLogin,
  apiLimiter,
  express.raw({ type: "application/x-bittorrent", limit: "1mb" }),
  async (req, res) => {
    const bytes = req.body;
    let rawName = typeof req.get("X-Torrent-Filename") === "string" ? req.get("X-Torrent-Filename") : "upload.torrent";
    try {
      rawName = decodeURIComponent(rawName);
    } catch {}
    const filename = path.basename(rawName).replace(/[\r\n"\\]/g, "_").slice(0, 180);
    if (!Buffer.isBuffer(bytes) || bytes.length === 0) {
      return res.status(400).json({ error: "Выберите непустой файл .torrent." });
    }
    if (bytes.length > 1024 * 1024) return res.status(413).json({ error: "Торрент-файл должен быть не больше 1 МБ." });
    if (!filename.toLowerCase().endsWith(".torrent")) {
      return res.status(400).json({ error: "Поддерживаются только файлы .torrent." });
    }
    const slot = reserveDownloadSlot(req, res);
    if (!slot) return;
    try {
      const item = await adUploadTorrent(bytes, filename);
      slot.attach(item.id, Boolean(item.ready) || Number(item.statusCode) === 4);
      const hash = typeof item.hash === "string" ? item.hash.trim().toLowerCase() : null;
      const sha256 = hash ? crypto.createHash("sha256").update(hash).digest("hex") : null;
      res.json({
        ok: true,
        id: item.id,
        name: item.name || filename.replace(/\.torrent$/i, "") || "Торрент",
        ready: Boolean(item.ready),
        hash,
        sha256,
        size: item.size || 0,
        filename
      });
    } catch (err) {
      slot.release();
      res.status(err.status || 502).json({ error: err.message || "Не удалось загрузить торрент-файл." });
    }
  }
);

router.post(["/api/magnets/:id/cancel", "/api/magnets/:id/delete"], requireLogin, apiLimiter, async (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isSafeInteger(id) || id <= 0) {
    return res.status(400).json({ error: "Некорректный идентификатор magnet-ссылки." });
  }
  if (!isLibraryAdmin(req)) {
    // Состояние раздачи — с сервера, не со слов клиента: готовую удаляет только админ
    let statusCode;
    try {
      const { data } = await getLibrary();
      statusCode = (data.magnets || []).find(m => m.id === id)?.statusCode;
    } catch {}
    if (!canRemove(id, req, statusCode)) {
      return res.status(403).json({
        error: "Удалять фильмы из библиотеки может только админ. Отменить можно свою незаконченную загрузку; " +
          "фильмы, которые долго никто не открывает, удаляются сами."
      });
    }
  }
  try {
    const message = await deleteMagnet(id);
    libraryChanged();
    res.json({ ok: true, id, message });
  } catch (err) {
    res.status(err.status || 502).json({ error: err.message || "Не удалось удалить раздачу." });
  }
});

router.get("/api/magnets/:id", requireLogin, apiLimiter, async (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isSafeInteger(id) || id <= 0) {
    return res.status(400).json({ error: "Некорректный идентификатор magnet-ссылки." });
  }
  try {
    const response = await axios.post(
      `${config.ALLDEBRID_API_BASE}/v4.1/magnet/status`,
      new URLSearchParams({ id: String(id) }),
      {
        headers: {
          Authorization: `Bearer ${config.ALLDEBRID_API_KEY}`,
          "Content-Type": "application/x-www-form-urlencoded"
        },
        timeout: 15000,
        validateStatus: () => true
      }
    );
    if (response.status < 200 || response.status >= 300 || response.data?.status === "error") {
      return res.status(502).json({ error: response.data?.error?.message || "Не удалось получить состояние magnet-ссылки." });
    }
    const magnets = response.data?.data?.magnets;
    const list = Array.isArray(magnets) ? magnets : magnets && typeof magnets === "object" ? [magnets] : [];
    let magnet = list.find(item => String(item.id) === String(id));

    // Some API responses may omit the id-filtered result temporarily. Retry using GET query parameters.
    if (!magnet) {
      const fallback = await axios.get(`${config.ALLDEBRID_API_BASE}/v4.1/magnet/status`, {
        params: { id },
        headers: { Authorization: `Bearer ${config.ALLDEBRID_API_KEY}` },
        timeout: 15000,
        validateStatus: () => true
      });
      const fallbackMagnets = fallback.data?.data?.magnets;
      const fallbackList = Array.isArray(fallbackMagnets)
        ? fallbackMagnets
        : fallbackMagnets && typeof fallbackMagnets === "object"
          ? [fallbackMagnets]
          : [];
      magnet = fallbackList.find(item => String(item.id) === String(id));
    }
    if (!magnet) {
      console.warn("В ответе AllDebrid отсутствует запрошенный идентификатор magnet-ссылки", id, "response keys:", Object.keys(response.data?.data || {}));
      return res.status(502).json({
        error: "AllDebrid не вернул состояние этой magnet-ссылки. Возможно, она ещё регистрируется. Повторите попытку через несколько секунд."
      });
    }
    const statusCode = Number.isFinite(Number(magnet.statusCode)) ? Number(magnet.statusCode) : 0;
    // AllDebrid status 4 is ready; 5+ are terminal errors. Free the IP slot in either case.
    if (statusCode >= 4) releaseTrackedDownload(id);
    res.json({
      id: magnet.id,
      filename: magnet.filename,
      status: magnet.status || "Обработка",
      statusCode,
      downloaded: magnet.downloaded || 0,
      size: magnet.size || 0,
      ready: statusCode === 4
    });
  } catch (err) {
    res.status(502).json({ error: err.message || "Не удалось получить состояние magnet-ссылки." });
  }
});

router.get("/api/magnets/:id/files", requireLogin, apiLimiter, async (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isSafeInteger(id) || id <= 0) {
    return res.status(400).json({ error: "Некорректный идентификатор magnet-ссылки." });
  }
  try {
    const response = await axios.post(
      `${config.ALLDEBRID_API_BASE}/v4/magnet/files`,
      new URLSearchParams({ "id[]": String(id) }),
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
      return res.status(502).json({ error: response.data?.error?.message || "Не удалось получить список файлов magnet-ссылки." });
    }
    const rawMagnets = response.data?.data?.magnets;
    const magnetList = Array.isArray(rawMagnets) ? rawMagnets : rawMagnets && typeof rawMagnets === "object" ? [rawMagnets] : [];
    const root = magnetList.find(m => Number(m.id) === id) || magnetList[0];
    if (!root) return res.status(404).json({ error: "Для этой magnet-ссылки не найдено файлов." });

    const resolvedFiles = flattenMagnetFiles(root)
      .filter(f => f.type === "file" && f.sourceLink)
      .map(f => ({ name: f.path, url: null, sourceUrl: f.sourceLink, size: f.size }));
    res.json({ id, filename: root.filename || root.name || "Файлы раздачи", status: root.status || "Ready", files: resolvedFiles });
  } catch (err) {
    res.status(502).json({ error: err.message || "Не удалось получить список файлов magnet-ссылки." });
  }
});

module.exports = router;

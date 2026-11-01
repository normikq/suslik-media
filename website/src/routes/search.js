"use strict";

const express = require("express");
const crypto = require("crypto");
const config = require("../config");
const { searchLimiter, apiLimiter } = require("../middleware/rate-limit");
const { requireLogin, requireAdmin, deviceId } = require("../middleware/auth");
const { cleanFileName, adFail } = require("../utils/helpers");
const { reserveDownloadSlot } = require("../services/downloads");
const { adUploadMagnet, adUploadTorrent, fetchTorrentFromIndexer } = require("../services/alldebrid");
const torrentSearch = require("../../search");
const aiDiscovery = require("../services/aidiscovery");

const router = express.Router();

router.get("/api/search", requireLogin, searchLimiter, async (req, res) => {
  res.setHeader("Cache-Control", "private, no-store");
  try {
    res.json(await torrentSearch.search(typeof req.query.q === "string" ? req.query.q : ""));
  } catch (err) {
    res.status(err.status || 502).json({
      error: err.status ? err.message : "Не удалось выполнить поиск. Попробуйте ещё раз."
    });
  }
});

router.post("/api/search/add", requireLogin, apiLimiter, async (req, res) => {
  const r = torrentSearch.getResult(req.body?.id);
  if (!r) return res.status(404).json({ error: "Результат устарел. Повторите поиск." });
  if (!config.ALLDEBRID_API_KEY) return res.status(503).json({ error: "Облачный сервис не настроен на сервере." });
  const slot = reserveDownloadSlot(req, res);
  if (!slot) return;
  try {
    // Attempts in order of cost; the first one AllDebrid accepts wins, the last error is reported otherwise.
    const fileName = (cleanFileName(r.title) || "download") + ".torrent";
    const attempts = [];
    if (r.magnet) attempts.push(() => adUploadMagnet(r.magnet));
    if (r.lazy) {
      attempts.push(async () => {
        // closed trackers (rutracker/kinozal): magnet from the topic page / info hash
        const lz = await torrentSearch.resolveLazy(r);
        if (!lz.magnet) throw adFail(502, "Не удалось получить magnet-ссылку со страницы раздачи.");
        return adUploadMagnet(lz.magnet);
      });
      // Через TorAPI (без аккаунта) .torrent недоступен: остаётся только magnet, его ошибка и показывается.
      if (r.lazy.via !== "torapi") {
        attempts.push(async () => adUploadTorrent(await torrentSearch.downloadLazy(r), fileName));
      }
    }
    if (r.torrentUrl) {
      attempts.push(async () => {
        const got = await fetchTorrentFromIndexer(r.torrentUrl);
        return got.magnet ? adUploadMagnet(got.magnet) : adUploadTorrent(got.bytes, fileName);
      });
    }
    let item = null;
    let lastErr = null;
    for (const attempt of attempts) {
      try {
        item = await attempt();
        break;
      } catch (err) {
        lastErr = err;
      }
    }
    if (!item) throw lastErr || adFail(400, "У этого результата нет magnet-ссылки или торрент-файла.");
    slot.attach(item.id, Boolean(item.ready) || Number(item.statusCode) === 4);
    const hash = typeof item.hash === "string" ? item.hash.trim().toLowerCase() : r.infoHash || null;
    const sha256 = hash ? crypto.createHash("sha256").update(hash).digest("hex") : null;
    res.json({ ok: true, id: item.id, name: item.name || r.title, ready: Boolean(item.ready), hash, sha256 });
  } catch (err) {
    slot.release();
    res.status(err.status || 502).json({ error: err.message || "Не удалось добавить результат в библиотеку." });
  }
});

// «Подбор по настроению»: { query, fresh? } -> { items: [{ title, original, year, kind, reason, genres, poster, rating,
// overview, tmdbTitle, lang }], cached }. lang — язык оригинала по TMDB ("ru" и т. п.) или null. poster — путь зеркала /api/poster/img/w342/… или null.
// 429 { reason: "cooldown" | "daily", retryAfterSec }; 503 { reason: "busy" | "disabled" }; 400 { reason: "empty" }.
// Владелец кулдауна — пользователь или устройство (cookie sdev ставится здесь же, если её ещё нет).
router.post("/api/search/ai-discovery", requireLogin, apiLimiter, async (req, res) => {
  res.set("Cache-Control", "private, no-store");
  const query = typeof req.body?.query === "string" ? req.body.query : "";
  try {
    res.json(await aiDiscovery.discover(query, deviceId(req, res), Date.now(), { fresh: req.body?.fresh === true }));
  } catch (err) {
    if (err instanceof aiDiscovery.AiError) {
      if (err.code === "cooldown" || err.code === "daily") {
        const sec = Math.max(1, Math.ceil(err.retryAfterMs / 1000));
        res.set("Retry-After", String(sec));
        return res.status(429).json({ error: err.message, reason: err.code, retryAfterSec: sec });
      }
      if (err.code === "empty") return res.status(400).json({ error: err.message, reason: err.code });
      if (err.code === "busy" || err.code === "disabled") return res.status(503).json({ error: err.message, reason: err.code });
      return res.status(502).json({ error: err.message, reason: "bad" });
    }
    console.error("[aidiscovery]", err.message);
    res.status(500).json({ error: "Непредвиденная ошибка сервера." });
  }
});

router.get("/api/search/ai-discovery/stats", requireAdmin, (req, res) => {
  res.set("Cache-Control", "no-store");
  res.json(aiDiscovery.stats());
});

module.exports = router;

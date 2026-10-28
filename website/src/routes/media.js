"use strict";

const express = require("express");
const { mediaInfoLimiter, posterLimiter } = require("../middleware/rate-limit");
const { requireLogin } = require("../middleware/auth");
const { getMediaMetadata } = require("../services/metadata");
const { searchPoster, fetchPosterImage } = require("../services/posters");

const router = express.Router();

router.post("/api/media-metadata", requireLogin, mediaInfoLimiter, async (req, res) => {
  const raw = typeof req.body?.link === "string" ? req.body.link.trim() : "";
  const magnetId = Number(req.body?.magnetId);
  const wantedPath = typeof req.body?.path === "string" ? req.body.path : "";
  try {
    const { metadata, cached } = await getMediaMetadata(raw, magnetId, wantedPath);
    res.json({ ok: true, metadata, cached });
  } catch (err) {
    if (err.userMessage) return res.status(502).json({ error: err.userMessage });
    if (err.status) return res.status(err.status).json({ error: err.message });
    const message = /ENOENT/.test(err.message)
      ? "На сервере не установлен ffprobe."
      : /SIGKILL|timed out/i.test(err.message)
        ? "Анализ видео занял слишком много времени."
        : "Не удалось прочитать сведения о видео.";
    res.status(502).json({ error: message });
  }
});

router.get("/api/poster", requireLogin, posterLimiter, async (req, res) => {
  const query = typeof req.query.q === "string" ? req.query.q : "";
  try {
    const season = /^\d{1,2}$/.test(String(req.query.season || "")) ? Number(req.query.season) : 0;
    const body = await searchPoster(query, { hero: req.query.hero === "1", season });
    // Найденный постер браузер помнит неделю, «постера нет» — сутки (TMDB мог его ещё не завести).
    res.set("Cache-Control", `private, max-age=${body.poster ? 604800 : 86400}`);
    res.json(body);
  } catch (err) {
    res.status(err.status || 502).json({ error: err.message || "Не удалось найти постер." });
  }
});

router.get("/api/poster/img/:size/:file", requireLogin, posterLimiter, async (req, res) => {
  const { size, file } = req.params;
  try {
    const cached = await fetchPosterImage(size, file);
    const type = file.endsWith(".png") ? "image/png" : file.endsWith(".webp") ? "image/webp" : "image/jpeg";
    res.set({
      "Content-Type": type,
      "Cache-Control": "private, max-age=2592000, immutable",
      "X-Content-Type-Options": "nosniff"
    });
    res.sendFile(cached, err => {
      if (err && !res.headersSent) res.status(404).end();
    });
  } catch (err) {
    if (err.status === 400) return res.status(400).end();
    res.status(502).end();
  }
});

module.exports = router;

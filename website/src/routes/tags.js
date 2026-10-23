"use strict";

const express = require("express");
const { apiLimiter } = require("../middleware/rate-limit");
const { requireLogin } = require("../middleware/auth");
const { getLibrary } = require("../services/library");
const { snapshot } = require("../services/mediatags");

const router = express.Router();

// { tags: { "<id раздачи>": ["Dolby Vision", "HDR", "DTS-HD MA"] }, pending: сколько ещё разбирается }
// Метки для постеров (services/mediatags.js): известные отдаются сразу, остальные разбираются в фоне — клиент спрашивает снова, пока pending > 0.
router.get("/api/tags", requireLogin, apiLimiter, async (req, res) => {
  try {
    const { data, stale } = await getLibrary();
    const magnets = Array.isArray(data.magnets) ? data.magnets : [];
    res.set("Cache-Control", "no-store");
    res.json(snapshot(magnets, !stale && magnets.length > 0));
  } catch {
    res.status(502).json({ error: "Не удалось получить метки." });
  }
});

module.exports = router;

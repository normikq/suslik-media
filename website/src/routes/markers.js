"use strict";

const express = require("express");
const { apiLimiter } = require("../middleware/rate-limit");
const { requireLogin } = require("../middleware/auth");
const { markersFor, parseQuery } = require("../services/markers");

const router = express.Router();

// Заставка и титры серии из открытых баз (services/markers.js), когда в самом файле нет глав с ними.
// ?title=&alt=&season=&episode=&duration= (duration — в секундах, необязательно) →
// { intro: { startMs, endMs } | null, credits: { startMs, endMs } | null, source: "aniskip" | null }
async function handler(req, res) {
  const q = parseQuery(req.query);
  if (!q) return res.status(400).json({ error: "Некорректный запрос." });
  try {
    res.set("Cache-Control", "private, max-age=3600");
    res.json(await markersFor(q));
  } catch {
    res.status(502).json({ error: "Не удалось получить метки." });
  }
}

router.get(["/api/markers", "/api/file/markers"], requireLogin, apiLimiter, handler);

module.exports = router;

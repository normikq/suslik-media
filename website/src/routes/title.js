"use strict";

const express = require("express");
const { posterLimiter } = require("../middleware/rate-limit");
const { requireLogin } = require("../middleware/auth");
const library = require("../services/library");
const aiTitle = require("../services/aititle");
const tmdb = require("../services/tmdb-details");

const router = express.Router();

// Раздача медиатеки по id (с принятым названием от ИИ) или null. Подменяется в тестах (setMagnetLookup).
let magnetById = async id => {
  const { data } = await library.getLibrary();
  const m = (data.magnets || []).find(x => String(x.id) === String(id));
  if (!m) return null;
  const ai = aiTitle.resultFor(m.id);
  return ai ? { ...m, aiTitle: ai } : m;
};

// Сведения о фильме для страницы фильма (docs/HLS-CLIENT.md «Страница фильма»): { found:false } или поля TMDB
// (services/tmdb-details.js). Раздачи нет в медиатеке — 404.
router.get("/api/title/:adId", requireLogin, posterLimiter, async (req, res) => {
  const id = String(req.params.adId || "");
  if (!/^\d{1,15}$/.test(id)) return res.status(400).json({ error: "Некорректный номер раздачи." });
  let m;
  try {
    m = await magnetById(id);
  } catch {
    return res.status(502).json({ error: "Не удалось загрузить медиатеку." });
  }
  if (!m) return res.status(404).json({ error: "Раздача не найдена." });
  try {
    const body = await tmdb.detailsFor(m);
    res.set("Cache-Control", `private, max-age=${body.found ? 86400 : 3600}`);
    res.json(body);
  } catch (err) {
    res.status(err.status || 502).json({ error: err.message || "Не удалось получить сведения о фильме." });
  }
});

router.setMagnetLookup = fn => {
  magnetById = fn;
};

module.exports = router;

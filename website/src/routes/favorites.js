"use strict";

const express = require("express");
const { apiLimiter } = require("../middleware/rate-limit");
const { requireLogin, deviceId } = require("../middleware/auth");
const { favoritesOf, setFavorite } = require("../services/favorites");
const { shelfOf, setOnShelf } = require("../services/shelf");

const router = express.Router();

router.get("/api/favorites", requireLogin, apiLimiter, (req, res) => {
  res.json({ ids: [...(favoritesOf(req) || [])] });
});

router.post("/api/favorites", requireLogin, apiLimiter, (req, res) => {
  const id = String(req.body?.id ?? "").trim();
  if (!/^\d{1,20}$/.test(id)) return res.status(400).json({ error: "Некорректный id раздачи." });
  const dev = deviceId(req, res);
  const on = req.body?.favorite !== false;
  setFavorite(dev, id, on);
  res.json({ ok: true, id, favorite: on });
});

// «Мои фильмы»: личный список раздач. Убрать — только из своего списка, раздача остаётся в общей библиотеке
router.get("/api/shelf", requireLogin, apiLimiter, (req, res) => {
  res.json({ ids: [...(shelfOf(req) || [])] });
});

router.post("/api/shelf", requireLogin, apiLimiter, (req, res) => {
  const id = String(req.body?.id ?? "").trim();
  if (!/^\d{1,20}$/.test(id)) return res.status(400).json({ error: "Некорректный id раздачи." });
  const owner = deviceId(req, res);
  const on = req.body?.mine !== false;
  setOnShelf(owner, id, on);
  res.json({ ok: true, id, mine: on });
});

module.exports = router;

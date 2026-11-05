"use strict";

const express = require("express");
const { apiLimiter } = require("../middleware/rate-limit");
const { requireLogin } = require("../middleware/auth");
const { apply, changesSince, getProgress, getTracks, OWNER_RE } = require("../services/sync");

const router = express.Router();

// Синхронизация прогресса и выбранных дорожек (services/sync.js) — только по аккаунту Discord:
// при входе по общему паролю у всех один пользователь "shared"
function owner(req, res) {
  const id = req.user && req.user.id;
  if (OWNER_RE.test(String(id || ""))) return id;
  res.status(403).json({ error: "Синхронизация работает только при входе через Discord." });
  return null;
}

// Что изменилось с since (мс сервера из прошлого ответа; 0 — всё)
router.get("/api/sync", requireLogin, apiLimiter, (req, res) => {
  const id = owner(req, res);
  if (!id) return;
  res.set("Cache-Control", "no-store");
  const out = changesSince(id, req.query.since);
  if (typeof req.query.key === "string" || typeof req.query.cid === "string") {
    const key = typeof req.query.key === "string" ? req.query.key : null;
    const cid = typeof req.query.cid === "string" ? req.query.cid : null;
    const trackKey = typeof req.query.track_key === "string" ? req.query.track_key : (key && /^\d+\|/.test(key) ? "m" + key.split("|")[0] : key);
    out.progress = getProgress(id, key, cid);
    out.trackChoice = getTracks(id, trackKey, cid);
  }
  res.json(out);
});

router.get("/api/sync/lookup", requireLogin, apiLimiter, (req, res) => {
  const id = owner(req, res);
  if (!id) return;
  const key = typeof req.query.key === "string" ? req.query.key : null;
  const cid = typeof req.query.cid === "string" ? req.query.cid : null;
  const trackKey = typeof req.query.track_key === "string" ? req.query.track_key : (key && /^\d+\|/.test(key) ? "m" + key.split("|")[0] : key);
  res.set("Cache-Control", "no-store");
  res.json({
    progress: getProgress(id, key, cid),
    tracks: getTracks(id, trackKey, cid)
  });
});

// Отдать свои изменения и сразу получить чужие: { since, watch: [...], tracks: [...] }
router.post("/api/sync", requireLogin, apiLimiter, (req, res) => {
  const id = owner(req, res);
  if (!id) return;
  const since = req.body?.since;
  const accepted = apply(id, req.body);
  res.set("Cache-Control", "no-store");
  res.json({ ...changesSince(id, since), accepted });
});

module.exports = router;

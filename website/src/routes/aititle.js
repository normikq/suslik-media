"use strict";

const express = require("express");
const { apiLimiter } = require("../middleware/rate-limit");
const { requireLogin, requireAdmin, deviceId } = require("../middleware/auth");
const { getLibrary } = require("../services/library");
const { ownerChecker } = require("../services/downloads");
const aiTitle = require("../services/aititle");

const router = express.Router();

const OWNER_RE = /^(?:[a-f0-9]{32}|u_\d{1,25})$/;

// Готовая раздача, которую добавил этот пользователь (или админ). Иначе ответ уже отправлен и вернётся null.
async function ownMagnet(req, res) {
  const id = String(req.params.id || "");
  if (!/^\d{1,20}$/.test(id)) {
    res.status(400).json({ error: "Некорректный id раздачи." });
    return null;
  }
  let data;
  try {
    ({ data } = await getLibrary());
  } catch {
    res.status(502).json({ error: "Не удалось загрузить медиатеку." });
    return null;
  }
  const m = (data.magnets || []).find(x => String(x.id) === id);
  if (!m || Number(m.statusCode) !== 4) {
    res.status(404).json({ error: "Раздача не найдена или ещё не готова." });
    return null;
  }
  if (!ownerChecker(req)(id)) {
    res.status(403).json({ error: "Определить через ИИ может только тот, кто добавил раздачу." });
    return null;
  }
  return m;
}

// Спросить ИИ: { aiTitle: { base, season, source: "ai", model, at, status }, cached }.
// 429 { reason: "cooldown" | "daily", retryAfterSec } — подождать; 503 { reason: "busy" } — сервис занят, попытка не списана.
router.post("/api/library/:id/ai-title", requireLogin, apiLimiter, async (req, res) => {
  res.set("Cache-Control", "no-store");
  if (!aiTitle.enabled()) return res.status(503).json({ error: "Определение через ИИ на сервере не настроено.", reason: "disabled" });
  const owner = deviceId(req, null);
  if (!OWNER_RE.test(owner)) return res.status(403).json({ error: "Сначала выполните вход." });
  const m = await ownMagnet(req, res);
  if (!m) return;
  try {
    const out = await aiTitle.suggest(m, owner);
    res.json(out);
  } catch (err) {
    if (err instanceof aiTitle.AiError) {
      if (err.code === "cooldown" || err.code === "daily") {
        const sec = Math.max(1, Math.ceil(err.retryAfterMs / 1000));
        res.set("Retry-After", String(sec));
        return res.status(429).json({ error: err.message, reason: err.code, retryAfterSec: sec });
      }
      if (err.code === "busy" || err.code === "disabled") return res.status(503).json({ error: err.message, reason: err.code });
      return res.status(502).json({ error: err.message, reason: "bad" });
    }
    console.error("[aititle]", err.message);
    res.status(500).json({ error: "Непредвиденная ошибка сервера." });
  }
});

// Владелец принял или отклонил предложение: { accept: true | false } -> { aiTitle }
router.post("/api/library/:id/ai-title/decision", requireLogin, apiLimiter, async (req, res) => {
  res.set("Cache-Control", "no-store");
  if (typeof req.body?.accept !== "boolean") return res.status(400).json({ error: "Нужно поле accept: true или false." });
  const m = await ownMagnet(req, res);
  if (!m) return;
  const r = aiTitle.decide(m.id, req.body.accept);
  if (!r) return res.status(404).json({ error: "Предложения ИИ для этой раздачи нет." });
  res.json({ aiTitle: r });
});

// Счётчики для замера: вызовы, 429/503, токены за день, доля принятых
router.get("/api/ai-title/stats", requireAdmin, (req, res) => {
  res.set("Cache-Control", "no-store");
  res.json(aiTitle.stats());
});

module.exports = router;

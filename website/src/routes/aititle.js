"use strict";

const express = require("express");
const { apiLimiter } = require("../middleware/rate-limit");
const { requireLogin, requireAdmin, deviceId } = require("../middleware/auth");
const { getLibrary } = require("../services/library");
const { ownerChecker, isLibraryAdmin } = require("../services/downloads");
const aiTitle = require("../services/aititle");

const router = express.Router();

const OWNER_RE = /^(?:[a-f0-9]{32}|u_\d{1,25})$/;
const MAX_IDS = 10;

// Готовые раздачи из ids, которые добавил этот пользователь (или админ). Ни одной — ответ уже отправлен, вернётся null.
async function ownMagnets(req, res, ids) {
  ids = [...new Set(ids.map(String))];
  if (!ids.length || ids.length > MAX_IDS || ids.some(id => !/^\d{1,20}$/.test(id))) {
    res.status(400).json({ error: "Некорректный список раздач." });
    return null;
  }
  let data;
  try {
    ({ data } = await getLibrary());
  } catch {
    res.status(502).json({ error: "Не удалось загрузить медиатеку." });
    return null;
  }
  const want = new Set(ids);
  const ready = (data.magnets || []).filter(x => want.has(String(x.id)) && Number(x.statusCode) === 4);
  if (!ready.length) {
    res.status(404).json({ error: "Раздача не найдена или ещё не готова." });
    return null;
  }
  const own = ownerChecker(req);
  const mine = ready.filter(m => own(m.id));
  if (!mine.length) {
    res.status(403).json({ error: "Определить через ИИ может только тот, кто добавил раздачу." });
    return null;
  }
  return mine;
}

async function ask(req, res, ids) {
  res.set("Cache-Control", "no-store");
  if (!aiTitle.enabled()) return res.status(503).json({ error: "Определение через ИИ на сервере не настроено.", reason: "disabled" });
  const owner = deviceId(req, null);
  if (!OWNER_RE.test(owner)) return res.status(403).json({ error: "Сначала выполните вход." });
  const list = await ownMagnets(req, res, ids);
  if (!list) return null;
  const admin = isLibraryAdmin(req);
  try {
    const out = await aiTitle.suggestMany(list, owner, Date.now(), { noCooldown: admin, force: admin && req.body?.force === true });
    return { ...out, aiTitleReadyAt: admin ? 0 : aiTitle.readyAt(owner) };
  } catch (err) {
    if (err instanceof aiTitle.AiError) {
      if (err.code === "cooldown" || err.code === "daily") {
        const sec = Math.max(1, Math.ceil(err.retryAfterMs / 1000));
        res.set("Retry-After", String(sec));
        res.status(429).json({ error: err.message, reason: err.code, retryAfterSec: sec });
      } else if (err.code === "busy" || err.code === "disabled") {
        res.status(503).json({ error: err.message, reason: err.code });
      } else {
        res.status(502).json({ error: err.message, reason: "bad" });
      }
      return null;
    }
    console.error("[aititle]", err.message);
    res.status(500).json({ error: "Непредвиденная ошибка сервера." });
    return null;
  }
}

// Спросить ИИ о раздачах одной плитки (версии, сезоны) одним вызовом: { ids: [..], force? } ->
// { results: [{ id, aiTitle: { base, season, source: "ai", model, at, status } }], cached, aiTitleReadyAt }.
// Чужие и неготовые из ids пропускаются. force (только админ) — спросить заново.
// 429 { reason: "cooldown" | "daily", retryAfterSec } — подождать; 503 { reason: "busy" } — сервис занят, попытка не списана.
// Админу библиотеки кулдауна нет (дневной лимит — есть).
router.post("/api/library/ai-title", requireLogin, apiLimiter, async (req, res) => {
  const ids = Array.isArray(req.body?.ids) ? req.body.ids : [];
  const out = await ask(req, res, ids);
  if (out) res.json(out);
});

// Одна раздача: то же, плюс aiTitle этой раздачи
router.post("/api/library/:id/ai-title", requireLogin, apiLimiter, async (req, res) => {
  const out = await ask(req, res, [req.params.id]);
  if (out) res.json({ ...out, aiTitle: out.results[0] ? out.results[0].aiTitle : null });
});

// Владелец принял или отклонил предложение: { accept: true | false } -> { aiTitle }
router.post("/api/library/:id/ai-title/decision", requireLogin, apiLimiter, async (req, res) => {
  res.set("Cache-Control", "no-store");
  if (typeof req.body?.accept !== "boolean") return res.status(400).json({ error: "Нужно поле accept: true или false." });
  const list = await ownMagnets(req, res, [req.params.id]);
  if (!list) return;
  const r = aiTitle.decide(list[0], req.body.accept);
  if (!r) return res.status(404).json({ error: "Предложения ИИ для этой раздачи нет." });
  res.json({ aiTitle: r });
});

// Несколько решений за один запрос (плитка из нескольких раздач): { decisions: [{ id, accept }] } -> { results: [{ id, aiTitle }] }.
// Раздачи без предложения ИИ пропускаются, как и чужие.
router.post("/api/library/ai-title/decision", requireLogin, apiLimiter, async (req, res) => {
  res.set("Cache-Control", "no-store");
  const decisions = Array.isArray(req.body?.decisions) ? req.body.decisions : [];
  if (!decisions.length || decisions.some(d => !d || typeof d.accept !== "boolean")) {
    return res.status(400).json({ error: "Нужен список decisions: [{ id, accept: true | false }]." });
  }
  const list = await ownMagnets(req, res, decisions.map(d => d.id));
  if (!list) return;
  const accept = new Map(decisions.map(d => [String(d.id), d.accept]));
  const results = [];
  for (const m of list) {
    const r = aiTitle.decide(m, accept.get(String(m.id)));
    if (r) results.push({ id: Number(m.id), aiTitle: r });
  }
  res.json({ results });
});

// Счётчики для замера: вызовы, 429/503, токены за день, доля принятых
router.get("/api/ai-title/stats", requireAdmin, (req, res) => {
  res.set("Cache-Control", "no-store");
  res.json(aiTitle.stats());
});

module.exports = router;

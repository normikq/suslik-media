"use strict";

const express = require("express");
const config = require("../config");
const { loginLimiter, apiLimiter } = require("../middleware/rate-limit");
const { requireLogin, verifyPassword } = require("../middleware/auth");
const { alldebridRequest } = require("../services/alldebrid");

const router = express.Router();

router.post("/api/login", loginLimiter, (req, res) => {
  if (!config.SITE_PASSWORD) return res.status(503).json({ error: "На сервере не задан SITE_PASSWORD." });
  const password = typeof req.body?.password === "string" ? req.body.password : "";
  if (!password || password.length > 256) {
    return res.status(401).json({ error: "Неверный пароль." });
  }

  if (!verifyPassword(password)) {
    return res.status(401).json({ error: "Неверный пароль." });
  }

  req.session.regenerate(regenErr => {
    if (regenErr) return res.status(500).json({ error: "Не удалось создать сеанс." });
    req.session.authenticated = true;
    req.session.save(err => {
      if (err) return res.status(500).json({ error: "Не удалось создать сеанс." });
      res.json({ ok: true });
    });
  });
});

router.post("/api/logout", requireLogin, (req, res) => {
  req.session.destroy(() => {
    res.clearCookie("movieportal.sid");
    res.json({ ok: true });
  });
});

router.get("/api/session", (req, res) => {
  res.json({ authenticated: Boolean(req.session?.authenticated) });
});

router.get("/api/status", requireLogin, apiLimiter, async (req, res) => {
  try {
    const data = await alldebridRequest("/user");
    res.json({
      ok: true,
      account: data.data?.user
        ? {
            username: data.data.user.username,
            premium: data.data.user.isPremium
          }
        : null
    });
  } catch (err) {
    res.status(err.status || 502).json({ error: err.message || "Не удалось связаться с AllDebrid." });
  }
});

module.exports = router;

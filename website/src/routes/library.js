"use strict";

const express = require("express");
const { apiLimiter } = require("../middleware/rate-limit");
const { requireLogin } = require("../middleware/auth");
const { getLibrary } = require("../services/library");
const { withRemovable } = require("../services/favorites");

const router = express.Router();

router.get("/api/library", requireLogin, apiLimiter, async (req, res) => {
  try {
    const { data, stale } = await getLibrary();
    if (stale) {
      res.setHeader("Cache-Control", "no-store");
      return res.json({ ...withRemovable(data, req), stale: true });
    }
    res.setHeader("Cache-Control", "private, no-cache"); // revalidate; Express adds an ETag, so unchanged = 304
    res.json(withRemovable(data, req));
  } catch (err) {
    console.error("Не удалось получить медиатеку:", err.message);
    res.status(502).json({
      error: err.userMessage || "Не удалось загрузить медиатеку. Попробуйте обновить её через некоторое время."
    });
  }
});

module.exports = router;

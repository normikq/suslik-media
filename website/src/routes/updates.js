"use strict";

const express = require("express");
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const config = require("../config");
const { apiLimiter, reportLimiter, anonReportLimiter, serviceLimiter } = require("../middleware/rate-limit");
const { requireLogin, isTester, updateTokenOk, secretEquals } = require("../middleware/auth");
const { readCookie } = require("../utils/helpers");
const {
  UPDATE_KINDS,
  isUpdatePlatform,
  completeUpdates,
  updateSha256,
  versionOf,
  cmpVersion,
  vparts,
  latestUpdate,
  clearBetas,
  cleanReleases,
  readChangelog,
  saveChangelog,
  saveCrashReport,
  listCrashReports
} = require("../services/updates");

const router = express.Router();

router.get("/api/app/changelog", apiLimiter, (req, res) => {
  const platform = String(req.query.platform || "");
  if (!isUpdatePlatform(platform)) return res.status(400).json({ error: "platform: android | desktop" });
  let upto = typeof req.query.upto === "string" && /^\d+(\.\d+){0,3}$/.test(req.query.upto) ? req.query.upto : "";
  if (!upto) {
    try {
      upto = latestUpdate(platform)?.version || "";
    } catch {}
  }
  const limit = Math.min(10, Math.max(1, parseInt(req.query.limit, 10) || 3));
  const releases = readChangelog(platform)
    .filter(r => !upto || cmpVersion(vparts(r.version), vparts(upto)) <= 0)
    .slice(0, limit);
  res.setHeader("Cache-Control", "no-cache");
  res.json({ releases });
});

router.post("/api/app/changelog", serviceLimiter, (req, res) => {
  if (!updateTokenOk(req)) {
    return res.status(403).json({ error: "Загрузка выключена или неверный X-Update-Token." });
  }
  const platform = String(req.query.platform || "");
  if (!isUpdatePlatform(platform)) return res.status(400).json({ error: "platform: android | desktop" });
  const list = cleanReleases(req.body);
  if (!list.length) return res.status(400).json({ error: "Ожидается JSON-массив [{ version, title, items: [...] }]" });
  try {
    saveChangelog(platform, list);
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
  res.json({ ok: true, versions: list.map(r => r.version) });
});

router.get("/api/app/update", requireLogin, apiLimiter, async (req, res) => {
  const platform = String(req.query.platform || "");
  if (!isUpdatePlatform(platform)) return res.status(400).json({ error: "platform: android | desktop" });
  const stable = latestUpdate(platform);
  // Тестировщик получает бету, если она новее того, что вышло для всех.
  const beta = isTester(req) ? latestUpdate(platform, config.BETA_DIR) : null;
  const u = beta && (!stable || cmpVersion(beta.v, stable.v) > 0) ? beta : stable;
  if (!u) return res.json({ available: false });
  const ch = u === beta ? "beta" : "stable";
  const sha256 = await updateSha256(u.full, u.size, u.at);
  res.json({
    available: true,
    channel: ch,
    version: u.version,
    size: u.size,
    sha256,
    notes: u.notes,
    file: u.name,
    publishedAt: Math.round(u.at),
    url: `/api/app/download/${platform}/${encodeURIComponent(u.name)}${ch === "beta" ? "?ch=beta" : ""}`
  });
});

// Проверка кода тестировщика (поле в «Настройках»).
router.get("/api/app/beta", requireLogin, apiLimiter, (req, res) => res.json({ tester: isTester(req) }));

// Первая установка беты на устройство, где ещё нет поля для кода
router.get("/api/app/beta-download/:platform", requireLogin, serviceLimiter, (req, res) => {
  if (!secretEquals(String(req.query.code || ""), config.BETA_CODE)) {
    return res.status(403).json({ error: "Неверный код тестировщика." });
  }
  const u = latestUpdate(String(req.params.platform), config.BETA_DIR);
  if (!u) return res.status(404).json({ error: "В бета-канале сейчас нет версии для этой платформы." });
  res.setHeader("Cache-Control", "no-store");
  res.setHeader("X-Accel-Limit-Rate", config.APP_DOWNLOAD_RATE_LIMIT);
  res.download(u.full, u.name, { dotfiles: "deny" });
});

router.get("/api/app/download/:platform/:name", requireLogin, (req, res) => {
  const beta = req.query.ch === "beta";
  if (beta && !isTester(req)) return res.status(403).json({ error: "Бета-версии доступны только тестировщикам." });
  const u = latestUpdate(String(req.params.platform), beta ? config.BETA_DIR : config.UPDATES_DIR);
  if (!u || u.name !== req.params.name) {
    return res.status(404).json({ error: "Файл обновления не найден (возможно, уже вышла новее)." });
  }
  res.setHeader("Cache-Control", "no-store");
  res.setHeader("X-Accel-Limit-Rate", config.APP_DOWNLOAD_RATE_LIMIT);
  res.download(u.full, u.name, { dotfiles: "deny" }); // поддерживает Range: прерванная загрузка докачивается
});

router.post("/api/app/upload", serviceLimiter, (req, res) => {
  if (req.body && Object.keys(req.body).length) {
    return res.status(400).json({ error: 'Укажите -H "Content-Type: application/octet-stream"' });
  }
  if (!updateTokenOk(req)) {
    return res.status(403).json({ error: "Загрузка выключена или неверный X-Update-Token." });
  }
  const name = path
    .basename(String(req.get("X-File-Name") || ""))
    .replace(/[^\w .()+-]/g, "_")
    .slice(0, 120);
  if (!name || !versionOf(name) || !Object.values(UPDATE_KINDS).some(re => re.test(name))) {
    return res.status(400).json({
      error: "X-File-Name: имя с версией и расширением .apk / .msi / .exe, например suslik-media-1.3.4.apk"
    });
  }
  const dir = String(req.get("X-Channel") || "").toLowerCase() === "beta" ? config.BETA_DIR : config.UPDATES_DIR; // по умолчанию — всем
  const tmp = path.join(dir, `.upload-${crypto.randomBytes(6).toString("hex")}`);
  const out = fs.createWriteStream(tmp);
  let size = 0;
  const LIMIT = 500 * 1024 * 1024;
  req.on("data", c => {
    size += c.length;
    if (size > LIMIT) {
      req.destroy();
      out.destroy();
      fs.rm(tmp, { force: true }, () => {});
    }
  });
  req.pipe(out); // на диск потоком — без буфера в памяти
  out.on("finish", () => {
    if (size === 0 || size > LIMIT) {
      fs.rm(tmp, { force: true }, () => {});
      return res.status(400).json({ error: "Пустой или слишком большой файл." });
    }
    fs.rename(tmp, path.join(dir, name), err => {
      if (err) return res.status(500).json({ error: err.message });
      completeUpdates.add(path.join(dir, name));
      // сразу убрать прошлые версии этой платформы (в своём канале)
      for (const platform of Object.keys(UPDATE_KINDS)) {
        if (UPDATE_KINDS[platform].test(name)) {
          latestUpdate(platform, dir);
          // Релиз для всех заменяет все беты этой платформы: их номера не связаны с релизными, и старая бета с большим
          // номером иначе продолжала бы предлагаться тестировщикам (а следующая бета с меньшим номером тут же удалялась бы).
          if (dir !== config.BETA_DIR) clearBetas(platform);
        }
      }
      res.json({ ok: true, file: name, size, channel: dir === config.BETA_DIR ? "beta" : "stable" });
    });
  });
  out.on("error", err => {
    fs.rm(tmp, { force: true }, () => {});
    if (!res.headersSent) res.status(500).json({ error: err.message });
  });
});

router.get("/api/app/status", serviceLimiter, (req, res) => {
  if (!updateTokenOk(req)) return res.status(403).json({ error: "Нужен X-Update-Token." });
  const list = dir => {
    try {
      return fs.readdirSync(dir).filter(n => /\.(apk|msi|exe)$/i.test(n));
    } catch {
      return [];
    }
  };
  const pick = u => (u ? { file: u.name, version: u.version } : null);
  const out = {
    rev: config.SERVER_REV,
    node: process.version,
    betaCodeSet: !!config.BETA_CODE,
    stable: {},
    beta: {}
  };
  for (const p of Object.keys(UPDATE_KINDS)) {
    out.stable[p] = pick(latestUpdate(p));
    out.beta[p] = pick(latestUpdate(p, config.BETA_DIR));
  }
  out.files = { stable: list(config.UPDATES_DIR), beta: list(config.BETA_DIR) };
  res.json(out);
});

router.post("/api/app/promote", serviceLimiter, (req, res) => {
  if (!updateTokenOk(req)) return res.status(403).json({ error: "Неверный X-Update-Token." });
  const platform = String(req.query.platform || "");
  if (!isUpdatePlatform(platform)) return res.status(400).json({ error: "platform: android | desktop" });
  const b = latestUpdate(platform, config.BETA_DIR);
  if (!b) return res.status(404).json({ error: "В бета-канале нет версии для " + platform });
  // A beta older than the release would be moved and deleted at once as an «old version» while answering ok.
  const st = latestUpdate(platform);
  if (st && cmpVersion(b.v, st.v) <= 0) {
    return res.status(409).json({ error: `Бета ${b.version} не новее релиза ${st.version}.` });
  }
  const dest = path.join(config.UPDATES_DIR, b.name);
  try {
    fs.renameSync(b.full, dest);
    completeUpdates.add(dest);
    const notes = b.full.replace(/\.[^.]+$/, ".txt");
    if (fs.existsSync(notes)) fs.renameSync(notes, dest.replace(/\.[^.]+$/, ".txt"));
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
  latestUpdate(platform); // убрать прошлую версию для всех
  console.log(`[updates] ${b.name}: бета → всем`);
  res.json({ ok: true, file: b.name, version: b.version });
});

router.post(
  "/api/app/report",
  reportLimiter,
  anonReportLimiter,
  express.text({ type: () => true, limit: "1mb" }),
  (req, res) => {
    const platform = String(req.get("X-Platform") || "").replace(/[^a-z]/gi, "").slice(0, 12) || "unknown";
    const version = String(req.get("X-App-Version") || "").replace(/[^\w.-]/g, "").slice(0, 20) || "0";
    const kind = String(req.get("X-Report-Kind") || "report").replace(/[^\w-]/g, "").slice(0, 30) || "report";
    const known = !!(req.session && req.session.authenticated);
    let text = typeof req.body === "string" ? req.body : "";
    if (!text.trim()) return res.status(400).json({ error: "Пустой отчёт." });
    if (!known && text.length > 64 * 1024) text = "…" + text.slice(-64 * 1024);
    // Journals can carry signed mirror addresses (valid for 12 hours): the signature is not kept.
    text = text.replace(/([?&](?:sig|code)=)[^&\s"']+/g, "$1…");
    const device = readCookie(req, "sdev");
    const ua = req.get("user-agent");
    try {
      saveCrashReport({ platform, version, kind, known, device, ua }, text);
    } catch (err) {
      return res.status(500).json({ error: err.message });
    }
    res.json({ ok: true });
  }
);

router.get("/api/app/reports", serviceLimiter, (req, res) => {
  if (!updateTokenOk(req)) return res.status(403).json({ error: "Неверный X-Update-Token." });
  const limit = Math.min(50, Math.max(1, parseInt(req.query.limit, 10) || 10));
  const tail = Math.min(200_000, Math.max(500, parseInt(req.query.tail, 10) || 6000));
  res.setHeader("Cache-Control", "no-store");
  res.json(listCrashReports(limit, tail));
});

module.exports = router;

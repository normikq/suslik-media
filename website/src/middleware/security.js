"use strict";

const config = require("../config");
const { readCookie } = require("../utils/helpers");

function securityHeaders(req, res, next) {
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("X-Frame-Options", "DENY");
  res.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
  res.setHeader("Referrer-Policy", "same-origin");
  res.setHeader("Cross-Origin-Opener-Policy", "same-origin");
  res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
  // HSTS только по https (за nginx с TRUST_PROXY=1 req.secure это видит). Без includeSubDomains: на домене есть и другие службы.
  if (req.secure) res.setHeader("Strict-Transport-Security", "max-age=15552000");
  next();
}

function setupTrustProxy(app) {
  if (config.TRUST_PROXY) {
    const tp = String(config.TRUST_PROXY).trim().toLowerCase();
    app.set("trust proxy", tp === "true" ? true : /^\d+$/.test(tp) ? Number(tp) : config.TRUST_PROXY);
  }
}

// Защита от CSRF для запросов с cookie сеанса: SameSite=Lax не спасает от страниц на соседних поддоменах
// (на домене есть и другие службы). Изменяющий запрос из браузера должен прийти с этого же адреса.
// Приложения с ключом устройства и запросы без cookie (ТВ, CI, вход по коду) не затрагиваются.
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);
function sameOriginWrites(req, res, next) {
  if (SAFE_METHODS.has(req.method)) return next();
  if (req.get("X-Device-Token") || /^Bearer\s+\S/i.test(req.get("Authorization") || "")) return next();
  if (!readCookie(req, "movieportal.sid")) return next();
  const deny = () => res.status(403).json({ error: "Запрос отклонён: он пришёл не со страницы сайта." });
  // Sec-Fetch-Site шлют все современные браузеры; ему и верим, если он есть
  const site = req.get("Sec-Fetch-Site");
  if (site) return site === "same-origin" || site === "none" ? next() : deny();
  // Старый браузер: сверяем Origin с адресом сервера. Без Origin — не браузерная форма (приложение по cookie)
  const origin = req.get("Origin");
  if (!origin) return next();
  let host = "";
  try { host = new URL(origin).host; } catch {}
  const hosts = [req.get("host"), req.get("x-forwarded-host")].filter(Boolean).map(h => String(h).split(",")[0].trim());
  return host && hosts.includes(host) ? next() : deny();
}

module.exports = {
  securityHeaders,
  sameOriginWrites,
  setupTrustProxy
};

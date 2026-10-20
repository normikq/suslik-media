"use strict";

const config = require("../config");

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

module.exports = {
  securityHeaders,
  setupTrustProxy
};

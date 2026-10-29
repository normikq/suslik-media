"use strict";

function clientIp(req) {
  return req.ip || req.socket?.remoteAddress || "unknown";
}

function readCookie(req, name) {
  const m = new RegExp(`(?:^|;\\s*)${name}=([^;]+)`).exec(req.headers?.cookie || "");
  return m ? m[1] : "";
}

function safeHttpsUrl(value) {
  try {
    const u = new URL(value);
    return u.protocol === "https:" ? u : null;
  } catch {
    return null;
  }
}

// A readable file name (with extension) in the URL path and Content-Disposition lets browsers and
// players pick the right container/title instead of calling every download "proxy".
function cleanFileName(name) {
  return String(name || "").replace(/[\\/\x00-\x1f\x7f"<>|?*:]/g, "_").replace(/^\.+/, "").trim().slice(0, 150);
}

function contentDisposition(name) {
  const clean = cleanFileName(name);
  if (!clean) return "";
  const ascii = clean.replace(/[^\x20-\x7e]/g, "_").replace(/%/g, "_");
  return `inline; filename="${ascii}"; filename*=UTF-8''${encodeURIComponent(clean).replace(/['()*]/g, c => "%" + c.charCodeAt(0).toString(16).toUpperCase())}`;
}

// Путь сервера, если nginx отдаёт его не из корня (`/suslik`): nginx сообщает его заголовком X-Forwarded-Prefix.
// Заголовку верим только за доверенным прокси (TRUST_PROXY); nginx старого адреса без пути его очищает.
// Один Node так обслуживает сразу два адреса: старый в корне и новый под путём (docs/MIGRATION.md).
const PREFIX_RE = /^(?:\/[A-Za-z0-9_~-][A-Za-z0-9._~-]*)+$/;
function basePath(req) {
  if (!req?.app?.get?.("trust proxy")) return "";
  const p = String(req.get?.("x-forwarded-prefix") || "").split(",")[0].trim().replace(/\/+$/, "");
  return PREFIX_RE.test(p) ? p : "";
}

// Внешний адрес сервера для ссылок, которые уходят в приложения и плееры: https://домен[/путь]
function publicBase(req) {
  return `https://${req.get("host")}${basePath(req)}`;
}

function adFail(status, message) {
  const e = new Error(message);
  e.status = status;
  return e;
}

module.exports = {
  clientIp,
  readCookie,
  safeHttpsUrl,
  cleanFileName,
  contentDisposition,
  basePath,
  publicBase,
  adFail
};

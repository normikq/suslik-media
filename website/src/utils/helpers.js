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
  adFail
};

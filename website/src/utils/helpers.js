"use strict";

function clientIp(req) {
  return req.ip || req.socket?.remoteAddress || "unknown";
}

function readCookie(req, name) {
  // Имя — как есть, не шаблон: точка в «movieportal.sid» иначе совпала бы с любым символом
  const safe = String(name).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const m = new RegExp(`(?:^|;\\s*)${safe}=([^;]+)`).exec(req.headers?.cookie || "");
  return m ? m[1] : "";
}

// Адрес, куда серверу ходить нельзя: локальный, частная сеть, link-local (метаданные облака 169.254.169.254) и т. п.
function isPrivateAddress(ip) {
  let a = String(ip || "").toLowerCase().replace(/^\[|\]$/g, "");
  const mapped = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/.exec(a);
  if (mapped) a = mapped[1];
  const v4 = /^(\d+)\.(\d+)\.(\d+)\.(\d+)$/.exec(a);
  if (v4) {
    const [x, y] = [Number(v4[1]), Number(v4[2])];
    return x === 0 || x === 10 || x === 127 || (x === 100 && y >= 64 && y <= 127) || (x === 169 && y === 254) ||
      (x === 172 && y >= 16 && y <= 31) || (x === 192 && y === 168) || x >= 224;
  }
  if (!a.includes(":")) return false;
  return a === "::" || a === "::1" || /^f[cd]/.test(a) || /^fe[89ab]/.test(a) || /^ff/.test(a);
}

// lookup для http(s).Agent и axios: имя, которое разрешилось во внутренний адрес, не соединяется (защита от SSRF,
// в том числе через редирект — проверка идёт на каждом соединении, а не только по первому адресу)
function publicOnlyLookup(hostname, options, callback) {
  if (typeof options === "function") {
    callback = options;
    options = {};
  }
  require("dns").lookup(hostname, options, (err, address, family) => {
    if (err) return callback(err);
    const list = Array.isArray(address) ? address : [{ address, family }];
    const bad = list.find(a => isPrivateAddress(a.address));
    if (bad) {
      return callback(Object.assign(new Error(`Адрес ${hostname} ведёт во внутреннюю сеть.`), { code: "EPRIVATEADDR" }));
    }
    callback(null, address, family);
  });
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

// Внешний адрес сервера для ссылок, которые уходят в приложения и плееры: https://домен[/путь].
// http — только для запроса по http на локальный или домашний адрес (сервер разработчика без nginx):
// за nginx без TRUST_PROXY req.protocol всегда http, поэтому по умолчанию — https, как раньше
const LOCAL_HOST_RE = /^(?:localhost|127(?:\.\d{1,3}){3}|\[::1\]|10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2})(?::\d+)?$/i;
function publicBase(req) {
  const host = String(req.get("host") || "");
  const proto = !req.secure && LOCAL_HOST_RE.test(host) ? "http" : "https";
  return `${proto}://${host}${basePath(req)}`;
}

// Отправка в поток событий (SSE). Клиент на медленной сети не успевает читать — буфер сокета растёт без предела.
// Больше SSE_MAX_BUFFER непрочитанного — закрываем поток: клиент переподключится и получит свежий снимок (init)
const SSE_MAX_BUFFER = 1024 * 1024;
function sseSender(res) {
  return text => {
    if (res.destroyed || res.writableEnded) return;
    if (res.writableLength > SSE_MAX_BUFFER) return res.destroy();
    res.write(text);
  };
}

function adFail(status, message) {
  const e = new Error(message);
  e.status = status;
  return e;
}

module.exports = {
  clientIp,
  readCookie,
  isPrivateAddress,
  publicOnlyLookup,
  safeHttpsUrl,
  cleanFileName,
  contentDisposition,
  basePath,
  publicBase,
  sseSender,
  adFail
};

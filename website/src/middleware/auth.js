"use strict";

const fs = require("fs");
const crypto = require("crypto");
const session = require("express-session");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");
const { readCookie } = require("../utils/helpers");

const { SHARED_USER, getUser, touchUser, verifyDeviceToken, hasDeviceHash, hashToken } = require("../services/users");

// Sessions survive a server restart (the default MemoryStore forgets everyone and grows without bound).
// One shared password means very few sessions, so a JSON file is plenty for this server.
class FileSessionStore extends session.Store {
  constructor(file) {
    super();
    this.map = new Map();
    // Недавно завершённые сеансы: запрос, начатый до отзыва, не должен записать сеанс обратно (set после destroy)
    this.gone = new Map();
    try {
      const saved = JSON.parse(fs.readFileSync(file, "utf8"));
      const now = Date.now();
      for (const [sid, entry] of Object.entries(saved)) {
        if (entry && entry.expires > now) this.map.set(sid, entry);
      }
    } catch {}
    this.save = makeJsonWriter(file, () => {
      const now = Date.now();
      for (const [sid, entry] of this.map) {
        if (entry.expires < now) this.map.delete(sid);
      }
      while (this.map.size > 500) this.map.delete(this.map.keys().next().value);
      return Object.fromEntries(this.map);
    });
  }

  _expiry(sess) {
    const e = sess && sess.cookie && sess.cookie.expires;
    const t = e ? new Date(e).getTime() : NaN;
    return Number.isFinite(t) ? t : Date.now() + config.SESSION_MAX_AGE_MS;
  }

  get(sid, cb) {
    const entry = this.map.get(sid);
    if (!entry) return cb(null, null);
    if (entry.expires < Date.now()) {
      this.map.delete(sid);
      return cb(null, null);
    }
    try {
      cb(null, JSON.parse(entry.data));
    } catch (err) {
      cb(err);
    }
  }

  set(sid, sess, cb) {
    if (this.gone.has(sid)) return cb && cb(null);
    this.map.set(sid, { data: JSON.stringify(sess), expires: this._expiry(sess) });
    this.save();
    cb && cb(null);
  }

  touch(sid, sess, cb) {
    const entry = this.map.get(sid);
    if (entry) {
      entry.expires = this._expiry(sess);
      this.save(30000); // rolling cookies: write at most every 30 s
    }
    cb && cb(null);
  }

  // Входы пользователя в браузере: id — начало хеша сеанса (сам идентификатор сеанса наружу не отдаётся).
  // Время последней активности — по сроку: cookie «скользящая», срок = последняя активность + SESSION_MAX_AGE_MS.
  listFor(uid) {
    const out = [];
    const now = Date.now();
    for (const [sid, entry] of this.map) {
      if (entry.expires < now) continue;
      let data;
      try { data = JSON.parse(entry.data); } catch { continue; }
      if (!data || !data.authenticated || (data.uid || "shared") !== uid || (data.meta && data.meta.app)) continue;
      out.push({
        sid, id: crypto.createHash("sha256").update(sid).digest("hex").slice(0, 16),
        ua: data.meta && data.meta.ua || "", created_at: data.meta && data.meta.at || 0,
        last_seen: entry.expires - config.SESSION_MAX_AGE_MS
      });
    }
    return out;
  }

  // Сеансы-cookie приложения (получены при привязке по коду) вместе с её ключом устройства: отзыв ключа закрывает и их.
  // dev — хеш ключа; сеансы старой версии без привязки (dev не записан) закрываются у того же пользователя целиком:
  // приложения всё равно входят по своему ключу устройства.
  destroyAppSessions(uid, dev) {
    let n = 0;
    for (const [sid, entry] of this.map) {
      let data;
      try { data = JSON.parse(entry.data); } catch { continue; }
      if (!data || !data.meta || !data.meta.app || (data.uid || "shared") !== uid) continue;
      if (data.meta.dev && data.meta.dev !== dev) continue;
      this._drop(sid); n++;
    }
    if (n) this.save();
    return n;
  }

  _drop(sid) {
    this.map.delete(sid);
    const now = Date.now();
    for (const [s, t] of this.gone) if (t < now - 10 * 60 * 1000) this.gone.delete(s);
    this.gone.set(sid, now);
  }

  destroy(sid, cb) {
    this._drop(sid);
    this.save();
    cb && cb(null);
  }
}

const sessionStore = new FileSessionStore(config.SESSIONS_FILE);

function createSessionMiddleware() {
  return session({
    name: "movieportal.sid",
    store: sessionStore,
    secret: config.SESSION_SECRET,
    resave: false,
    saveUninitialized: false,
    rolling: true, // Refresh cookie expiration while the authenticated session is active.
    cookie: {
      httpOnly: true,
      sameSite: "lax", // Lax allows OAuth callback redirects from discord.com to send the session cookie
      secure: config.COOKIE_SECURE,
      maxAge: config.SESSION_MAX_AGE_MS
    }
  });
}

// Вход по общему паролю выключен (PASSWORD_AUTH_ENABLED=false): всё, что вошло по паролю — сессии и устройства,
// привязанные из такой сессии, — больше не действует, а не только новые входы
function sharedAccessOff(user) {
  return user && user.id === SHARED_USER.id && !config.PASSWORD_AUTH_ENABLED;
}

function resolveAuth(req) {
  // 1. Проверка по токену устройства (для нативных клиентов без cookies)
  const deviceToken = req.get("X-Device-Token") || (req.get("Authorization") || "").replace(/^Bearer\s+/i, "");
  if (deviceToken) {
    const verified = verifyDeviceToken(deviceToken);
    if (verified && verified.user && !sharedAccessOff(verified.user)) {
      req.user = verified.user;
      req.device = verified.device;
      // Приложение шлёт и ключ, и cookie-сеанс. Сеанс, выданный до пометки приложений (без meta.dev), привязываем
      // к ключу сейчас: иначе после отзыва ключа приложение продолжало бы работать по cookie
      const sess = req.session;
      if (sess && sess.authenticated && !(sess.meta && sess.meta.dev)) {
        sess.meta = { ...(sess.meta || {}), app: true, dev: hashToken(deviceToken) };
      }
      return true;
    }
  }

  // 2. Проверка по сессии
  if (req.session && req.session.authenticated) {
    // Сеанс приложения живёт, пока жив её ключ устройства: отозвали ключ — выходит и сеанс
    const dev = req.session.meta && req.session.meta.dev;
    if (dev && !hasDeviceHash(dev)) {
      req.session.destroy?.(() => {});
      return false;
    }
    const user = req.session.uid ? getUser(req.session.uid) : SHARED_USER;
    if (!user || user.role === "denied" || sharedAccessOff(user)) {
      req.session.destroy?.(() => {});
      return false;
    }
    req.user = user;
    if (user.id !== SHARED_USER.id) touchUser(user.id);
    return true;
  }

  return false;
}

function requireLogin(req, res, next) {
  if (resolveAuth(req)) return next();
  return res.status(401).json({ error: "Сначала выполните вход." });
}

function requireAdmin(req, res, next) {
  if (!resolveAuth(req)) return res.status(401).json({ error: "Сначала выполните вход." });
  // Админ — только Discord-пользователь с ролью: общий пароль (id "shared") админом не считается, как в isLibraryAdmin
  if (req.user && req.user.role === "admin" && req.user.id !== SHARED_USER.id) return next();
  return res.status(403).json({ error: "Недостаточно прав администратора." });
}

// Чьё «личное» (избранное, свои раздачи): пользователь Discord — его id; вход по общему паролю — устройство
// (cookie sdev): у всех парольных один id "shared", и тогда избранное и права на удаление стали бы общими
function deviceId(req, res) {
  if (req.user && req.user.id && req.user.id !== SHARED_USER.id) {
    return req.user.id;
  }
  let id = readCookie(req, "sdev");
  if (!/^[a-f0-9]{32}$/.test(id)) {
    id = crypto.randomBytes(16).toString("hex");
    if (res) {
      res.cookie("sdev", id, {
        httpOnly: true,
        sameSite: "lax",
        secure: config.COOKIE_SECURE,
        maxAge: 5 * 365 * 24 * 3600 * 1000,
        path: "/"
      });
    }
  }
  return id;
}

function verifyPassword(password) {
  if (!config.SITE_PASSWORD || typeof password !== "string" || !password || password.length > 256) {
    return false;
  }
  if (/^\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}$/.test(config.SITE_PASSWORD)) {
    try {
      const bcrypt = require("bcryptjs");
      return bcrypt.compareSync(password, config.SITE_PASSWORD);
    } catch {
      return false;
    }
  }
  const hashGiven = crypto.createHash("sha256").update(password).digest();
  const hashExpected = crypto.createHash("sha256").update(config.SITE_PASSWORD).digest();
  return crypto.timingSafeEqual(hashGiven, hashExpected);
}

// Сравнение секрета без утечки его длины по времени: сравниваются хеши одинаковой длины
function secretEquals(given, secret) {
  if (!secret) return false;
  const a = crypto.createHash("sha256").update(String(given)).digest();
  const b = crypto.createHash("sha256").update(String(secret)).digest();
  return crypto.timingSafeEqual(a, b);
}

function isTester(req) {
  return secretEquals(req.get("X-Beta-Code") || "", config.BETA_CODE);
}

function updateTokenOk(req) {
  return secretEquals(req.get("X-Update-Token") || "", config.UPDATE_TOKEN);
}

module.exports = {
  FileSessionStore,
  sessionStore,
  createSessionMiddleware,
  resolveAuth,
  requireLogin,
  requireAdmin,
  secretEquals,
  deviceId,
  verifyPassword,
  isTester,
  updateTokenOk
};

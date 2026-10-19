"use strict";

const fs = require("fs");
const crypto = require("crypto");
const session = require("express-session");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");
const { readCookie } = require("../utils/helpers");

// Sessions survive a server restart (the default MemoryStore forgets everyone and grows without bound).
// One shared password means very few sessions, so a JSON file is plenty for this server.
class FileSessionStore extends session.Store {
  constructor(file) {
    super();
    this.map = new Map();
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

  destroy(sid, cb) {
    this.map.delete(sid);
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
      sameSite: "strict",
      secure: config.COOKIE_SECURE,
      maxAge: config.SESSION_MAX_AGE_MS
    }
  });
}

function requireLogin(req, res, next) {
  if (req.session && req.session.authenticated) return next();
  return res.status(401).json({ error: "Сначала выполните вход." });
}

function deviceId(req, res) {
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

function isTester(req) {
  const code = config.BETA_CODE;
  const given = String(req.get("X-Beta-Code") || "");
  return !!code && given.length === code.length && crypto.timingSafeEqual(Buffer.from(given), Buffer.from(code));
}

function updateTokenOk(req) {
  const token = config.UPDATE_TOKEN;
  const given = String(req.get("X-Update-Token") || "");
  return !!token && given.length === token.length && crypto.timingSafeEqual(Buffer.from(given), Buffer.from(token));
}

module.exports = {
  FileSessionStore,
  sessionStore,
  createSessionMiddleware,
  requireLogin,
  deviceId,
  verifyPassword,
  isTester,
  updateTokenOk
};

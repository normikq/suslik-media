"use strict";

const path = require("path");
const crypto = require("crypto");
const fs = require("fs");

const ROOT_DIR = path.resolve(__dirname, "..");
const PORT = Number(process.env.PORT || 3000);
const SITE_PASSWORD = process.env.SITE_PASSWORD || "";
const SESSION_SECRET = process.env.SESSION_SECRET || crypto.randomBytes(32).toString("hex");
const ALLDEBRID_API_KEY = process.env.ALLDEBRID_API_KEY || "";
const ALLDEBRID_API_BASE = process.env.ALLDEBRID_API_BASE || "https://api.alldebrid.com";
const TMDB_API_KEY = process.env.TMDB_API_KEY || "";
const COOKIE_SECURE = process.env.COOKIE_SECURE === "true";
const OWNER_LOCK = process.env.OWNER_LOCK !== "false";
const TRUST_PROXY = process.env.TRUST_PROXY;
const BETA_CODE = process.env.BETA_CODE || "";
const UPDATE_TOKEN = process.env.UPDATE_TOKEN || "";
const LIBRARY_CACHE_MS = Number(process.env.LIBRARY_CACHE_MS || 20000);
const MIRROR_TTL = Math.max(1, Number(process.env.MIRROR_TTL_HOURS || 12)) * 3600;
const MIRROR_DEBUG = process.env.MIRROR_DEBUG === "1";
const STREAM_IDLE_MS = Number(process.env.STREAM_IDLE_MS || 25000);
const MIRROR_ACCEL = process.env.MIRROR_ACCEL === "1";
const FFPROBE_PATH = process.env.FFPROBE_PATH || "ffprobe";
const APP_DOWNLOAD_RATE_LIMIT = "5242880";
const SERVER_REV = "2026-10-20 discord-1";
const SESSION_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;
const SITE_PREFIX = (process.env.SITE_PREFIX || "").replace(/\/+$/, "");

// Discord OAuth2 & Access Control
const DISCORD_CLIENT_ID = process.env.DISCORD_CLIENT_ID || "";
const DISCORD_CLIENT_SECRET = process.env.DISCORD_CLIENT_SECRET || "";
const DISCORD_BOT_TOKEN = process.env.DISCORD_BOT_TOKEN || "";
const DISCORD_GUILD_ID = process.env.DISCORD_GUILD_ID || "";
const DISCORD_ROLE_ALLOW = (process.env.DISCORD_ROLE_ALLOW || "").split(",").map(s => s.trim()).filter(Boolean);
const DISCORD_ROLE_ADMIN = (process.env.DISCORD_ROLE_ADMIN || "").split(",").map(s => s.trim()).filter(Boolean);
const DISCORD_ALLOWED_USERS = (process.env.DISCORD_ALLOWED_USERS || "").split(",").map(s => s.trim()).filter(Boolean);
const DISCORD_OWNER_ID = process.env.DISCORD_OWNER_ID || "";
const DISCORD_REDIRECT_URI = process.env.DISCORD_REDIRECT_URI || "";
const DISCORD_ENABLED = Boolean(DISCORD_CLIENT_ID && DISCORD_CLIENT_SECRET);
const PASSWORD_AUTH_ENABLED = process.env.PASSWORD_AUTH_ENABLED !== "false" && Boolean(SITE_PASSWORD);

// Where small runtime files live (sessions, library cache, metadata cache). Defaults to the website folder.
const DATA_DIR = process.env.DATA_DIR ? path.resolve(process.env.DATA_DIR) : ROOT_DIR;
const PUBLIC_DIR = path.join(ROOT_DIR, "public");
const UPDATES_DIR = process.env.UPDATES_DIR ? path.resolve(process.env.UPDATES_DIR) : path.join(DATA_DIR, "updates");
const BETA_DIR = path.join(UPDATES_DIR, "beta");
const CHANGELOG_DIR = path.join(DATA_DIR, "changelog");
const REPORTS_DIR = path.join(DATA_DIR, "reports");
const POSTER_DIR = path.join(DATA_DIR, "poster-cache");

// Ensure runtime directories exist
for (const dir of [DATA_DIR, UPDATES_DIR, BETA_DIR, CHANGELOG_DIR, REPORTS_DIR, POSTER_DIR]) {
  try { fs.mkdirSync(dir, { recursive: true }); } catch {}
}

// Data files
const OWNERS_FILE = path.join(DATA_DIR, "owners.json");
const FAVORITES_FILE = path.join(DATA_DIR, "favorites.json");
const TREE_CACHE_FILE = path.join(DATA_DIR, "library-files-cache.json");
const SESSIONS_FILE = path.join(DATA_DIR, "sessions.json");
const MEDIA_CACHE_FILE = path.join(DATA_DIR, "media-metadata-cache.json");
const USERS_FILE = path.join(DATA_DIR, "users.json");
const DEVICES_FILE = path.join(DATA_DIR, "devices.json");
const SHELF_FILE = path.join(DATA_DIR, "shelf.json");
// (Комнаты совместного просмотра живут только в памяти: после перезапуска приложения входят заново)

if (!process.env.SESSION_SECRET) {
  console.warn("ПРЕДУПРЕЖДЕНИЕ: SESSION_SECRET не задан в .env (сгенерирован временный случайный ключ). Сеансы и временные ссылки зеркала сбросятся при перезапуске сервера.");
}
if (DISCORD_ENABLED && !(DISCORD_BOT_TOKEN && DISCORD_GUILD_ID) && DISCORD_ALLOWED_USERS.length === 0) {
  console.warn("ПРЕДУПРЕЖДЕНИЕ: вход через Discord включён, но не заданы ни бот (DISCORD_BOT_TOKEN + DISCORD_GUILD_ID), ни DISCORD_ALLOWED_USERS: войти сможет только DISCORD_OWNER_ID.");
}
if (!SITE_PASSWORD) {
  console.warn("ПРЕДУПРЕЖДЕНИЕ: SITE_PASSWORD не задан. Вход будет недоступен, пока пароль не будет указан в .env.");
}

module.exports = {
  ROOT_DIR,
  PORT,
  SITE_PASSWORD,
  SESSION_SECRET,
  ALLDEBRID_API_KEY,
  ALLDEBRID_API_BASE,
  TMDB_API_KEY,
  COOKIE_SECURE,
  OWNER_LOCK,
  TRUST_PROXY,
  BETA_CODE,
  UPDATE_TOKEN,
  LIBRARY_CACHE_MS,
  MIRROR_TTL,
  MIRROR_DEBUG,
  STREAM_IDLE_MS,
  MIRROR_ACCEL,
  FFPROBE_PATH,
  APP_DOWNLOAD_RATE_LIMIT,
  SERVER_REV,
  SESSION_MAX_AGE_MS,
  DATA_DIR,
  PUBLIC_DIR,
  UPDATES_DIR,
  BETA_DIR,
  CHANGELOG_DIR,
  REPORTS_DIR,
  POSTER_DIR,
  OWNERS_FILE,
  FAVORITES_FILE,
  TREE_CACHE_FILE,
  SESSIONS_FILE,
  MEDIA_CACHE_FILE,
  USERS_FILE,
  DEVICES_FILE,
  SHELF_FILE,
  SITE_PREFIX,
  DISCORD_CLIENT_ID,
  DISCORD_CLIENT_SECRET,
  DISCORD_BOT_TOKEN,
  DISCORD_GUILD_ID,
  DISCORD_ROLE_ALLOW,
  DISCORD_ROLE_ADMIN,
  DISCORD_ALLOWED_USERS,
  DISCORD_OWNER_ID,
  DISCORD_REDIRECT_URI,
  DISCORD_ENABLED,
  PASSWORD_AUTH_ENABLED
};

"use strict";

const crypto = require("crypto");
const { getUser } = require("./users");

// Вход из Discord Activity. Activity открыта в iframe Discord (адрес <id>.discordsays.com): cookie там сторонние и
// браузеры их режут, поэтому страница ходит с ключом в заголовке (Authorization: Bearer sa_…), как приложения.
// Ключ живёт 12 часов и только в памяти сервера: после перезапуска Activity молча входит снова (Discord помнит согласие).
// В списке «Мои устройства» его нет — это не устройство.

const TOKEN_TTL_MS = 12 * 60 * 60 * 1000;
const PREFIX = "sa_";
const tokens = new Map(); // sha256(ключ) -> { uid, expires }

function hash(token) {
  return crypto.createHash("sha256").update(token).digest("hex");
}

function sweep(now = Date.now()) {
  for (const [h, t] of tokens) if (t.expires <= now) tokens.delete(h);
}
setInterval(sweep, 30 * 60 * 1000).unref?.();

function issueActivityToken(uid) {
  sweep();
  // Пусть один человек не копит ключи: больше 20 действующих — старые уходят
  const mine = [...tokens].filter(([, t]) => t.uid === uid);
  for (const [h] of mine.slice(0, Math.max(0, mine.length - 19))) tokens.delete(h);
  const token = PREFIX + crypto.randomBytes(24).toString("hex");
  tokens.set(hash(token), { uid, expires: Date.now() + TOKEN_TTL_MS });
  return { token, expires_in: Math.floor(TOKEN_TTL_MS / 1000) };
}

function isActivityToken(token) {
  return typeof token === "string" && token.startsWith(PREFIX) && token.length < 100;
}

// Пользователь по ключу или null (истёк, неизвестен, у пользователя больше нет доступа)
function verifyActivityToken(token) {
  if (!isActivityToken(token)) return null;
  const h = hash(token);
  const t = tokens.get(h);
  if (!t) return null;
  if (t.expires <= Date.now()) {
    tokens.delete(h);
    return null;
  }
  const user = getUser(t.uid);
  if (!user || user.role === "denied") {
    tokens.delete(h);
    return null;
  }
  return user;
}

function revokeActivityToken(token) {
  if (isActivityToken(token)) tokens.delete(hash(token));
}

module.exports = { issueActivityToken, verifyActivityToken, revokeActivityToken, isActivityToken, TOKEN_TTL_MS };

"use strict";

const fs = require("fs");
const crypto = require("crypto");
const path = require("path");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");
const { getAvatarUrl, checkGuildMember } = require("./discord");

// Загрузка пользователей
let usersMap = new Map();
try {
  const data = JSON.parse(fs.readFileSync(config.USERS_FILE, "utf8"));
  for (const [id, u] of Object.entries(data)) {
    if (u && typeof u === "object") usersMap.set(id, u);
  }
} catch {}

const saveUsers = makeJsonWriter(config.USERS_FILE, () => Object.fromEntries(usersMap));

// Загрузка устройств (токены устройств для TV / ПК)
let devicesMap = new Map();
try {
  const data = JSON.parse(fs.readFileSync(config.DEVICES_FILE, "utf8"));
  for (const [hash, d] of Object.entries(data)) {
    if (d && typeof d === "object") devicesMap.set(hash, d);
  }
} catch {}

const saveDevices = makeJsonWriter(config.DEVICES_FILE, () => Object.fromEntries(devicesMap));

// Временные сеансы связки устройств (Device Flow)
// user_code -> { code, user_code, name, expires_at, status, device_token, user_id }
const pendingDeviceCodes = new Map();

// Периодическая очистка протухших кодов связки
setInterval(() => {
  const now = Date.now();
  for (const [key, item] of pendingDeviceCodes) {
    if (item.expires_at < now) pendingDeviceCodes.delete(key);
  }
}, 60000).unref?.();

function hashToken(token) {
  return crypto.createHash("sha256").update(String(token)).digest("hex");
}

const SHARED_USER = Object.freeze({
  id: "shared",
  name: "Общий доступ",
  username: "shared",
  avatar: null,
  role: "admin"
});

function normalizeUserCode(input) {
  return String(input || "").trim().toUpperCase().replace(/[^A-Z0-9]/g, "");
}

function getUser(id) {
  if (!id) return null;
  if (id === "shared") return SHARED_USER;
  return usersMap.get(id) || null;
}

function getUserByDiscordId(discordId) {
  const dId = String(discordId);
  for (const u of usersMap.values()) {
    if (u.discord_id === dId) return u;
  }
  return null;
}

function findOrCreateUser(discordProfile, initialRole = "allow") {
  const discordId = String(discordProfile.id);
  let user = getUserByDiscordId(discordId);
  const now = Date.now();

  const name = discordProfile.global_name || discordProfile.username || "Пользователь";
  const avatar = getAvatarUrl(discordProfile);

  if (user) {
    user.username = discordProfile.username || user.username;
    user.name = name;
    user.avatar = avatar || user.avatar;
    user.role = initialRole;
    user.last_seen = now;
    user.role_checked_at = now;
  } else {
    user = {
      id: `u_${discordId}`,
      discord_id: discordId,
      username: discordProfile.username || "",
      name,
      avatar,
      role: initialRole,
      created_at: now,
      last_seen: now,
      role_checked_at: now
    };
    usersMap.set(user.id, user);
  }

  saveUsers();
  return user;
}

function touchUser(userId) {
  if (userId === "shared") return;
  const user = getUser(userId);
  if (user) {
    user.last_seen = Date.now();
    saveUsers(10000); // Debounced save
    checkRoleIfNeeded(user);
  }
}

// Фоновая проверка роли (раз в 15 минут)
const pendingRoleChecks = new Set();

function checkRoleIfNeeded(user) {
  if (!user || !user.discord_id) return;
  const now = Date.now();
  const ROLE_CHECK_INTERVAL_MS = 15 * 60 * 1000;

  if (now - (user.role_checked_at || 0) < ROLE_CHECK_INTERVAL_MS) return;
  if (pendingRoleChecks.has(user.id)) return;

  pendingRoleChecks.add(user.id);
  checkGuildMember(user.discord_id)
    .then(res => {
      user.role_checked_at = Date.now();
      if (!res.allowed) {
        user.role = "denied";
        console.warn(`[auth] Доступ для пользователя ${user.name} (${user.discord_id}) отозван: ${res.reason}`);
      } else {
        user.role = res.role;
      }
      saveUsers();
    })
    .catch(err => {
      // Сетевая ошибка Discord API: не отзываем доступ
      console.warn(`[auth] Ошибка при проверке роли ${user.name}:`, err.message);
    })
    .finally(() => {
      pendingRoleChecks.delete(user.id);
    });
}

// Device Flow
function createDevicePairing(deviceName = "Клиент") {
  const code = crypto.randomBytes(24).toString("hex"); // Секретный токен поллинга
  const rawCode = crypto.randomBytes(3).toString("hex").toUpperCase(); // 6 hex символов, например "A3F821"
  const userCode = `${rawCode.slice(0, 3)}-${rawCode.slice(3)}`; // "A3F-821"

  const entry = {
    code,
    user_code: userCode,
    normalized_code: normalizeUserCode(userCode),
    name: String(deviceName).slice(0, 64),
    status: "pending",
    expires_at: Date.now() + 10 * 60 * 1000, // 10 минут
    device_token: null,
    user_id: null
  };

  pendingDeviceCodes.set(code, entry);
  return {
    code,
    user_code: userCode,
    expires_in: 600,
    interval: 2
  };
}

function findPendingPairing(inputCode) {
  const norm = normalizeUserCode(inputCode);
  if (!norm) return null;
  const now = Date.now();
  for (const item of pendingDeviceCodes.values()) {
    if (item.normalized_code === norm && item.expires_at > now && item.status === "pending") {
      return item;
    }
  }
  return null;
}

function authorizeDevicePairing(inputCode, userId) {
  const item = findPendingPairing(inputCode);
  if (!item) return { ok: false, error: "Код не найден или истёк." };

  const user = getUser(userId);
  if (!user || user.role === "denied") {
    return { ok: false, error: "Пользователь не найден или доступ заблокирован." };
  }

  const rawToken = crypto.randomBytes(32).toString("hex");
  const tokenHash = hashToken(rawToken);

  devicesMap.set(tokenHash, {
    user_id: user.id,
    name: item.name || "Устройство",
    created_at: Date.now(),
    last_seen: Date.now()
  });
  saveDevices();

  item.status = "authorized";
  item.device_token = rawToken;
  item.user_id = user.id;

  return { ok: true, device_name: item.name };
}

function pollDevicePairing(code) {
  const item = pendingDeviceCodes.get(code);
  if (!item || item.expires_at < Date.now()) {
    pendingDeviceCodes.delete(code);
    return { status: "expired" };
  }

  if (item.status === "authorized") {
    pendingDeviceCodes.delete(code);
    const user = getUser(item.user_id);
    return {
      status: "authorized",
      device_token: item.device_token,
      user: user
        ? {
            id: user.id,
            name: user.name,
            username: user.username,
            avatar: user.avatar,
            role: user.role
          }
        : null
    };
  }

  return { status: "pending" };
}

function verifyDeviceToken(token) {
  if (!token || typeof token !== "string") return null;
  const hash = hashToken(token);
  const device = devicesMap.get(hash);
  if (!device) return null;

  const user = getUser(device.user_id);
  if (!user || user.role === "denied") return null;

  device.last_seen = Date.now();
  saveDevices(30000);
  touchUser(user.id);

  return { user, device };
}

function revokeDeviceToken(token) {
  if (!token) return false;
  const hash = hashToken(token);
  const deleted = devicesMap.delete(hash);
  if (deleted) saveDevices();
  return deleted;
}

// Миграция данных старого sdev в пользователя
function adoptDevice(sdevId, userId) {
  if (!sdevId || !userId || sdevId === userId) return;

  // 1. Избранное
  try {
    const favFile = config.FAVORITES_FILE;
    if (fs.existsSync(favFile)) {
      const favs = JSON.parse(fs.readFileSync(favFile, "utf8"));
      if (Array.isArray(favs[sdevId]) && favs[sdevId].length > 0) {
        const userFavs = new Set(Array.isArray(favs[userId]) ? favs[userId] : []);
        for (const id of favs[sdevId]) userFavs.add(id);
        favs[userId] = Array.from(userFavs);
        delete favs[sdevId];
        fs.writeFileSync(favFile, JSON.stringify(favs, null, 2));
      }
    }
  } catch (err) {
    console.warn("[adopt] Не удалось перенести избранное:", err.message);
  }

  // 2. Владелец раздач
  try {
    const ownersFile = config.OWNERS_FILE;
    if (fs.existsSync(ownersFile)) {
      const owners = JSON.parse(fs.readFileSync(ownersFile, "utf8"));
      let changed = false;
      for (const [id, owner] of Object.entries(owners)) {
        if (owner === sdevId) {
          owners[id] = userId;
          changed = true;
        }
      }
      if (changed) {
        fs.writeFileSync(ownersFile, JSON.stringify(owners, null, 2));
      }
    }
  } catch (err) {
    console.warn("[adopt] Не удалось перенести раздачи:", err.message);
  }
}

module.exports = {
  SHARED_USER,
  getUser,
  getUserByDiscordId,
  findOrCreateUser,
  touchUser,
  checkRoleIfNeeded,
  createDevicePairing,
  findPendingPairing,
  authorizeDevicePairing,
  pollDevicePairing,
  verifyDeviceToken,
  revokeDeviceToken,
  adoptDevice
};

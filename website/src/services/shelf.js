"use strict";

const fs = require("fs");
const path = require("path");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");

// 1. Метаданные раздач (видимость, автор)
// ad_id -> { added_by, visibility: "server" | "private", added_at }
let magnetsMeta = new Map();
const MAGNETS_META_FILE = path.join(config.DATA_DIR, "magnets-meta.json");
try {
  const data = JSON.parse(fs.readFileSync(MAGNETS_META_FILE, "utf8"));
  for (const [id, m] of Object.entries(data)) {
    if (m && typeof m === "object") magnetsMeta.set(String(id), m);
  }
} catch {}
const saveMagnetsMeta = makeJsonWriter(MAGNETS_META_FILE, () => Object.fromEntries(magnetsMeta));

// 2. Личные полки пользователей
// userId -> { [ad_id]: { hidden: boolean, progress: { file_idx, pos, dur, time } } }
let shelfData = new Map();
try {
  const data = JSON.parse(fs.readFileSync(config.SHELF_FILE, "utf8"));
  for (const [uid, items] of Object.entries(data)) {
    if (items && typeof items === "object") shelfData.set(uid, items);
  }
} catch {}
const saveShelf = makeJsonWriter(config.SHELF_FILE, () => Object.fromEntries(shelfData));

function recordMagnet(adId, addedBy, visibility = "server") {
  const idStr = String(adId);
  const existing = magnetsMeta.get(idStr);
  if (!existing) {
    magnetsMeta.set(idStr, {
      added_by: addedBy || "shared",
      visibility: visibility === "private" ? "private" : "server",
      added_at: Date.now()
    });
    saveMagnetsMeta();
  }
}

function getMagnetMeta(adId) {
  return magnetsMeta.get(String(adId)) || null;
}

function canSee(user, adId) {
  if (!user) return false;
  if (user.role === "admin") return true;

  const idStr = String(adId);
  const meta = magnetsMeta.get(idStr);
  if (!meta) return true; // По умолчанию все старые раздачи публичные

  if (meta.visibility === "private") {
    return meta.added_by === user.id;
  }

  // Проверяем, не скрыл ли пользователь раздачу лично для себя
  const userShelf = shelfData.get(user.id);
  if (userShelf && userShelf[idStr]?.hidden) {
    return false;
  }

  return true;
}

function canDeleteMagnet(adId, user, req) {
  if (!user) return true; // Fallback на старую проверку владельца по IP / sdev
  if (user.role === "admin") return true;

  const idStr = String(adId);
  const meta = magnetsMeta.get(idStr);
  if (meta && meta.added_by === user.id) return true;

  // Проверка по owners.json (для раздач, добавленных до Discord)
  try {
    if (fs.existsSync(config.OWNERS_FILE)) {
      const owners = JSON.parse(fs.readFileSync(config.OWNERS_FILE, "utf8"));
      if (owners[idStr] && owners[idStr] === user.id) return true;
    }
  } catch {}

  return false;
}

function setShelfHidden(userId, adId, hidden = true) {
  if (!userId) return;
  const idStr = String(adId);
  let userShelf = shelfData.get(userId);
  if (!userShelf) {
    userShelf = {};
    shelfData.set(userId, userShelf);
  }
  if (!userShelf[idStr]) userShelf[idStr] = {};
  userShelf[idStr].hidden = Boolean(hidden);
  saveShelf();
}

function getUserShelf(userId) {
  return shelfData.get(userId) || {};
}

module.exports = {
  recordMagnet,
  getMagnetMeta,
  canSee,
  canDeleteMagnet,
  setShelfHidden,
  getUserShelf
};

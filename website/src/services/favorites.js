"use strict";

const fs = require("fs");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");
const { deviceId } = require("../middleware/auth");
const { removeChecker } = require("./downloads");
const { shelfOf } = require("./shelf");

// ---- Избранное -------------------------------------------------------------------------------
// У каждого владельца своё избранное: отмеченные раздачи показываются в библиотеке первыми.
// Владелец — пользователь Discord (u_<id>) или устройство (cookie "sdev") при входе по общему паролю, см. deviceId.
const OWNER_RE = /^(?:[a-f0-9]{32}|u_\d{1,25})$/;
const favorites = new Map(); // owner -> Set(magnet id)
try {
  for (const [dev, ids] of Object.entries(JSON.parse(fs.readFileSync(config.FAVORITES_FILE, "utf8")))) {
    if (OWNER_RE.test(dev) && Array.isArray(ids)) {
      favorites.set(dev, new Set(ids.map(String).slice(0, 2000)));
    }
  }
} catch {}

const persistFavorites = makeJsonWriter(
  config.FAVORITES_FILE,
  () => Object.fromEntries([...favorites].map(([d, set]) => [d, [...set]])),
  1000
);

function favoritesOf(req) {
  const id = deviceId(req, null); // без нового cookie: у кого его нет, у того и избранного нет
  return OWNER_RE.test(id) ? (favorites.get(id) || null) : null;
}

/** Избранное владельца списком id (для первого заполнения «Моих фильмов»). */
function favoriteIds(owner) {
  return [...(favorites.get(owner) || [])];
}

// Для каждого устройства свои права: кнопка «Отменить/Удалить» показывается только владельцу раздачи.
function withRemovable(data, req) {
  const fav = favoritesOf(req);
  const mine = shelfOf(req);
  const canRemove = removeChecker(req);
  return {
    ...data,
    magnets: (data.magnets || []).map(m => ({
      ...m,
      canRemove: canRemove(m.id),
      favorite: Boolean(fav && fav.has(String(m.id))),
      mine: Boolean(mine && mine.has(String(m.id))) // в «Моих фильмах» (services/shelf.js)
    }))
  };
}

function setFavorite(dev, id, on) {
  let set = favorites.get(dev);
  if (!set) {
    set = new Set();
    favorites.set(dev, set);
  }
  if (on) {
    set.add(id);
    if (set.size > 2000) set.delete(set.values().next().value);
  } else {
    set.delete(id);
  }
  if (!set.size) favorites.delete(dev);
  if (favorites.size > 5000) favorites.delete(favorites.keys().next().value);
  persistFavorites();
  return on;
}

// Первый вход через Discord: избранное этого устройства переходит пользователю
function adoptFavorites(fromDev, toUser) {
  const from = favorites.get(fromDev);
  if (!from || fromDev === toUser) return 0;
  const to = favorites.get(toUser) || new Set();
  for (const id of from) to.add(id);
  favorites.set(toUser, to);
  favorites.delete(fromDev);
  persistFavorites();
  return from.size;
}

module.exports = {
  favoritesOf,
  favoriteIds,
  withRemovable,
  setFavorite,
  adoptFavorites
};

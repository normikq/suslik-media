"use strict";

const fs = require("fs");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");
const { readCookie } = require("../utils/helpers");
const { removeChecker } = require("./downloads");

// ---- Избранное -------------------------------------------------------------------------------
// У каждого устройства (cookie "sdev") своё избранное: отмеченные раздачи показываются в библиотеке первыми.
const favorites = new Map(); // dev -> Set(magnet id)
try {
  for (const [dev, ids] of Object.entries(JSON.parse(fs.readFileSync(config.FAVORITES_FILE, "utf8")))) {
    if (/^[a-f0-9]{32}$/.test(dev) && Array.isArray(ids)) {
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
  const id = readCookie(req, "sdev");
  return /^[a-f0-9]{32}$/.test(id) ? (favorites.get(id) || null) : null;
}

// Для каждого устройства свои права: кнопка «Отменить/Удалить» показывается только владельцу раздачи.
function withRemovable(data, req) {
  const fav = favoritesOf(req);
  const canRemove = removeChecker(req);
  return {
    ...data,
    magnets: (data.magnets || []).map(m => ({
      ...m,
      canRemove: canRemove(m.id),
      favorite: Boolean(fav && fav.has(String(m.id)))
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

module.exports = {
  favoritesOf,
  withRemovable,
  setFavorite
};

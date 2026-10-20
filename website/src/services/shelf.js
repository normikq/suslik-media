"use strict";

const fs = require("fs");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");
const { deviceId } = require("../middleware/auth");
const { readCookie } = require("../utils/helpers");
const { ownedBy } = require("./downloads");

// ---- «Мои фильмы» — личная библиотека ------------------------------------------------------------
// Раздачи в AllDebrid общие (один аккаунт), у каждого владельца — свой список: что он добавил сам и что взял из общей
// библиотеки. Убрать из списка — только у себя: в AllDebrid и в общей библиотеке раздача остаётся.
// Владелец — как у избранного: пользователь Discord (u_<id>) или устройство (cookie "sdev") при входе по общему паролю.
// Хранится shelf.json: { owner: [ad_id, …] }. Пустой список тоже хранится: он значит «уже заведён» (см. ensure).
const OWNER_RE = /^(?:[a-f0-9]{32}|u_\d{1,25})$/;
const MAX_ITEMS = 5000;
const shelves = new Map(); // owner -> Set(magnet id)
try {
  for (const [owner, ids] of Object.entries(JSON.parse(fs.readFileSync(config.SHELF_FILE, "utf8")))) {
    if (OWNER_RE.test(owner) && Array.isArray(ids)) {
      shelves.set(owner, new Set(ids.map(String).filter(id => /^\d{1,20}$/.test(id)).slice(0, MAX_ITEMS)));
    }
  }
} catch {}

const persist = makeJsonWriter(
  config.SHELF_FILE,
  () => Object.fromEntries([...shelves].map(([o, set]) => [o, [...set]])),
  1000
);

// Первое обращение: в список попадает то, что владелец уже добавил (owners.json) и отметил избранным —
// до «Моих фильмов» избранное и было личным списком. Дальше список ведётся сам.
function ensure(owner) {
  let set = shelves.get(owner);
  if (set) return set;
  set = new Set();
  for (const id of ownedBy(owner)) set.add(id);
  for (const id of require("./favorites").favoriteIds(owner)) set.add(id);
  shelves.set(owner, set);
  persist();
  return set;
}

function ownerOf(req) {
  const id = deviceId(req, null);
  // Без cookie устройства deviceId выдумывает новый id на каждый запрос: такому списка нет (и пустой не заводится)
  if (!id.startsWith("u_") && readCookie(req, "sdev") !== id) return null;
  return OWNER_RE.test(id) ? id : null;
}

/** Список запроса (Set) или null, если владелец неизвестен. */
function shelfOf(req) {
  const owner = ownerOf(req);
  return owner ? ensure(owner) : null;
}

function setOnShelf(owner, id, on) {
  if (!OWNER_RE.test(String(owner || ""))) return false;
  const key = String(id);
  const set = ensure(owner);
  if (on) {
    set.add(key);
    if (set.size > MAX_ITEMS) set.delete(set.values().next().value);
  } else {
    set.delete(key);
  }
  if (shelves.size > 5000) shelves.delete(shelves.keys().next().value);
  persist();
  return on;
}

/** Раздача удалена из AllDebrid: её больше нет ни в чьём списке. */
function dropEverywhere(id) {
  const key = String(id);
  let changed = false;
  for (const set of shelves.values()) if (set.delete(key)) changed = true;
  if (changed) persist();
}

// Первый вход через Discord: список этого устройства переходит пользователю
function adoptShelf(fromDev, toUser) {
  if (!OWNER_RE.test(String(fromDev || "")) || !OWNER_RE.test(String(toUser || "")) || fromDev === toUser) return 0;
  const from = shelves.get(fromDev);
  if (!from) return 0;
  const to = ensure(toUser);
  for (const id of from) to.add(id);
  shelves.delete(fromDev);
  persist();
  return from.size;
}

module.exports = {
  shelfOf,
  ownerOf,
  setOnShelf,
  dropEverywhere,
  adoptShelf
};

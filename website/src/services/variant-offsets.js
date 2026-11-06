"use strict";

// Подстройка времени между двумя раздачами одного фильма для комнат «Вместе»: место на раздаче b = место на a + ms.
// Ставит её гость кнопками «к ведущему» (поверх сдвига по разнице длин, rooms.js timelineShiftMs), она запоминается
// для пары раздач и в следующий раз включается у всех сама. Хранится одна запись на пару: ключ "a\nb" с a < b.
// variant-offsets.json: { "a\nb": ms }

const fs = require("fs");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");

const KEY_RE = /^\d{1,20}\|.{1,900}$/s;
const MAX_OFFSET_MS = 10 * 60 * 1000;
const MAX_PAIRS = 20000;

const store = new Map();

function pairOf(from, to) {
  return from < to ? { key: `${from}\n${to}`, sign: 1 } : { key: `${to}\n${from}`, sign: -1 };
}

function validKey(k) { return typeof k === "string" && KEY_RE.test(k); }

/** Место на to = место на from + результат; null — для пары ничего не сохранено. */
function get(from, to) {
  if (!validKey(from) || !validKey(to)) return null;
  if (from === to) return 0;
  const { key, sign } = pairOf(from, to);
  return store.has(key) ? sign * store.get(key) : null;
}

/** Запомнить подстройку пары; null — неверные ключи или значение. */
function set(from, to, ms) {
  const v = Math.round(Number(ms));
  if (!validKey(from) || !validKey(to) || from === to || !Number.isFinite(v) || Math.abs(v) > MAX_OFFSET_MS) return null;
  const { key, sign } = pairOf(from, to);
  if (!store.has(key) && store.size >= MAX_PAIRS) store.delete(store.keys().next().value);
  store.set(key, sign * v);
  persist();
  return v;
}

function load() {
  try {
    for (const [key, ms] of Object.entries(JSON.parse(fs.readFileSync(config.VARIANT_OFFSETS_FILE, "utf8")) || {})) {
      const [a, b] = key.split("\n");
      if (validKey(a) && validKey(b) && Number.isSafeInteger(ms) && Math.abs(ms) <= MAX_OFFSET_MS) store.set(key, ms);
    }
  } catch {}
}
load();

const persist = makeJsonWriter(config.VARIANT_OFFSETS_FILE, () => Object.fromEntries(store));

module.exports = { get, set, MAX_OFFSET_MS };

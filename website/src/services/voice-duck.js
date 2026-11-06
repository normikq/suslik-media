"use strict";

const fs = require("fs");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");

// ---- Приглушение фильма, когда говорят в Discord: настройка аккаунта ----
// Говорящих видит только клиент Discord (Activity), громкость меняет каждый плеер сам. Настройку видят и Activity
// (меню «Приглушение»), и плееры (init потока комнаты, settings_changed), поэтому она на сервере, а не в браузере.
// level — насколько глушить, %: 10 — громкость × 0,9, 100 — тишина. Шаг 10.
// min_ms — сколько человек должен говорить без перерыва, чтобы фильм затих (короткий шорох не глушит): 0–2000, шаг 100.
// Хранится voice-duck.json: { "u_<discord id>": { enabled, level, min_ms } }; кто ничего не менял — DEFAULTS, без записи.
const DEFAULTS = Object.freeze({ enabled: true, level: 10, min_ms: 400 });
const MIN_MS_MAX = 2000;
const OWNER_RE = /^u_\d{1,25}$/;
const MAX_OWNERS = 5000;

const settings = new Map(); // owner -> { enabled, level, min_ms }

function clean(v) {
  if (!v || typeof v !== "object") return null;
  if (typeof v.enabled !== "boolean") return null;
  const level = Number(v.level);
  if (!Number.isInteger(level) || level < 0 || level > 100 || level % 10 !== 0) return null;
  // Запись до появления порога — с порогом по умолчанию
  const minMs = v.min_ms === undefined ? DEFAULTS.min_ms : Number(v.min_ms);
  if (!Number.isInteger(minMs) || minMs < 0 || minMs > MIN_MS_MAX || minMs % 100 !== 0) return null;
  return { enabled: v.enabled, level, min_ms: minMs };
}

try {
  for (const [owner, v] of Object.entries(JSON.parse(fs.readFileSync(config.VOICE_DUCK_FILE, "utf8")))) {
    const c = clean(v);
    if (OWNER_RE.test(owner) && c) settings.set(owner, c);
  }
} catch {}

const persist = makeJsonWriter(config.VOICE_DUCK_FILE, () => Object.fromEntries(settings), 1000);

/** Настройка аккаунта; у входа по общему паролю и неизвестных — по умолчанию. */
function getVoiceDuck(userId) {
  return { ...(settings.get(String(userId || "")) || DEFAULTS) };
}

/** Сохраняет; null — неверное значение или не аккаунт Discord. */
function setVoiceDuck(userId, value) {
  const owner = String(userId || "");
  // Чего нет в запросе (старая страница без min_ms) — как сейчас у аккаунта
  const cur = getVoiceDuck(owner);
  const v = value && typeof value === "object" ? value : {};
  const c = clean({ enabled: v.enabled ?? cur.enabled, level: v.level ?? cur.level, min_ms: v.min_ms ?? cur.min_ms });
  if (!OWNER_RE.test(owner) || !c) return null;
  settings.delete(owner);
  settings.set(owner, c);
  if (settings.size > MAX_OWNERS) settings.delete(settings.keys().next().value);
  persist();
  return { ...c };
}

module.exports = { getVoiceDuck, setVoiceDuck, VOICE_DUCK_DEFAULTS: DEFAULTS };

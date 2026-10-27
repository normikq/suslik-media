"use strict";

const fs = require("fs");
const crypto = require("crypto");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");

// ---- «Определить через ИИ» -------------------------------------------------------------------------------------
// Регулярки остаются основным путём. Когда они ошиблись, тот, кто добавил раздачу, может один раз спросить Gemini:
// модель получает название раздачи и пути видеофайлов (без них не понять сезон) и возвращает { base, season }.
// Ответ не детерминирован даже при temperature 0, поэтому считается один раз и дальше берётся из хранилища:
// по ключу из нормализованного названия и состава файлов (та же раздача, добавленная заново, — без нового вызова).
// Результат — только предложение (status "suggested"): цифры в названии модель может понять неверно («Тачки 2» →
// «Тачки»). В группировку медиатеки он идёт, когда владелец его подтвердил ("accepted"); отклонённый ("rejected")
// и отброшенный проверкой ("none") тоже хранятся: второй вызов на ту же раздачу не делается.
// Кулдаун — на пользователя (u_<id> или устройство), дневной лимит — на весь сервер: сбой клиента не сожжёт бюджет.
// Ответы 429/503 от Gemini, таймаут и сеть попытку не списывают и кулдаун не запускают.
// Хранится ai-titles.json: { r: { key: результат }, i: { magnetId: key }, c: { owner: когда звал }, d: счётчики дня, s: итоги }.
const API_BASE = "https://generativelanguage.googleapis.com/v1beta";
const TIMEOUT_MS = 20000;
const MAX_FILES = 150;
const MAX_PATH = 200;
const MAX_RESULTS = 5000;
const OWNER_RE = /^(?:[a-f0-9]{32}|u_\d{1,25})$/;
const VIDEO_EXT = /\.(mkv|mp4|avi|mov|webm|m4v|m2ts|ts|iso|wmv|mpg|mpeg|vob)$/i;
const STATUSES = new Set(["suggested", "accepted", "rejected", "none"]);

const PROMPT = [
  "Ты получаешь название торрент-раздачи и список путей её видеофайлов.",
  "Верни JSON: base — название фильма или сериала так, как оно написано в раздаче (если есть русское — русское),",
  "без года, качества, кодеков, озвучки, номера сезона и серии; цифры, которые входят в само название (\"Тачки 2\",",
  "\"1+1\"), оставь. season — номер сезона, если раздача — ровно один сезон сериала, иначе null",
  "(фильм, несколько сезонов, сборник)."
].join(" ");

const RESPONSE_SCHEMA = {
  type: "OBJECT",
  properties: {
    base: { type: "STRING" },
    season: { type: "INTEGER", nullable: true }
  },
  required: ["base"]
};

let results = new Map(); // key -> { base, season, model, at, status, by }
let byId = new Map(); // magnet id (строка) -> key
let cooldowns = new Map(); // owner -> когда последний раз звал модель
let day = { d: "", calls: 0, e429: 0, e503: 0, fail: 0, inTok: 0, outTok: 0 };
let totals = { calls: 0, accepted: 0, rejected: 0, none: 0, e429: 0, e503: 0 };
const inflight = new Map(); // key -> Promise

function load() {
  try {
    const saved = JSON.parse(fs.readFileSync(config.AI_TITLE_FILE, "utf8")) || {};
    for (const [k, r] of Object.entries(saved.r || {})) if (/^[a-f0-9]{40}$/.test(k) && r && STATUSES.has(r.status)) results.set(k, r);
    for (const [id, k] of Object.entries(saved.i || {})) if (/^\d{1,20}$/.test(id) && results.has(k)) byId.set(id, k);
    for (const [o, at] of Object.entries(saved.c || {})) if (OWNER_RE.test(o) && Number.isFinite(at)) cooldowns.set(o, at);
    if (saved.d && typeof saved.d.d === "string") day = { ...day, ...saved.d };
    if (saved.s && typeof saved.s === "object") totals = { ...totals, ...saved.s };
  } catch {}
}
load();

const persist = makeJsonWriter(
  config.AI_TITLE_FILE,
  () => ({
    r: Object.fromEntries(results),
    i: Object.fromEntries(byId),
    c: Object.fromEntries(cooldowns),
    d: day,
    s: totals
  }),
  1000
);

function enabled() {
  return Boolean(config.GEMINI_API_KEY);
}

const norm = s => String(s || "").toLowerCase().replace(/ё/g, "е").replace(/[^\p{L}\p{N}]+/gu, " ").trim();

/** Пути видеофайлов раздачи (без sample), по алфавиту, не больше MAX_FILES. */
function videoPaths(files) {
  return (Array.isArray(files) ? files : [])
    .filter(f => f && f.type !== "folder" && VIDEO_EXT.test(String(f.path || "")) && !/(^|[\/._ -])sample([\/._ -]|$)/i.test(f.path))
    .map(f => String(f.path).slice(0, MAX_PATH))
    .sort()
    .slice(0, MAX_FILES);
}

/** Ключ результата: нормализованное название и состав видеофайлов. */
function keyOf(name, files) {
  const paths = videoPaths(files).map(norm);
  return crypto.createHash("sha1").update(norm(name) + "\n" + paths.join("\n")).digest("hex");
}

/** Номер сезона n упомянут в названии или путях: S01, «Season 1», «1 сезон», «Сезон 1», диапазон «1-3 сезон». */
function seasonMentioned(n, texts) {
  const S = "(?:season|сезон\\p{L}*)";
  const num = String(n);
  const pad = "0*" + num + "(?!\\d)";
  const direct = [
    new RegExp("(?<![\\p{L}\\d])s" + pad, "iu"),
    new RegExp(S + "\\s*[:#№._-]?\\s*" + pad, "iu"),
    new RegExp("(?<!\\d)0*" + num + "\\s*(?:-?(?:й|ый|ой))?[\\s._-]*" + S, "iu")
  ];
  const ranges = [
    new RegExp("(?<!\\d)(\\d{1,2})\\s*[-–]\\s*(\\d{1,2})[\\s._-]*" + S, "giu"),
    new RegExp(S + "[\\s._:-]*(\\d{1,2})\\s*[-–]\\s*(\\d{1,2})(?!\\d)", "giu"),
    new RegExp("(?<![\\p{L}\\d])s(\\d{1,2})\\s*[-–]\\s*s?(\\d{1,2})(?!\\d)", "giu")
  ];
  for (const t of texts) {
    if (direct.some(re => re.test(t))) return true;
    for (const re of ranges) {
      for (const m of t.matchAll(re)) if (Number(m[1]) <= n && n <= Number(m[2])) return true;
    }
  }
  return false;
}

/**
 * Проверка ответа модели по исходному названию и путям. Возвращает { base, season } или null (ответ отброшен):
 * пустое или слишком длинное название, ни одного его слова нет в раздаче, сезон не упомянут ни в названии, ни в путях.
 */
function validate(answer, name, files) {
  if (!answer || typeof answer !== "object") return null;
  const base = typeof answer.base === "string" ? answer.base.replace(/\s+/g, " ").trim() : "";
  if (!base || base.length > 200 || /[\u0000-\u001f\u007f]/.test(base)) return null;
  const paths = videoPaths(files);
  const hay = " " + norm([name, ...paths].join(" ")) + " ";
  const all = norm(base).split(" ").filter(Boolean);
  const words = all.some(w => w.length >= 2) ? all.filter(w => w.length >= 2) : all; // «1+1» — из одних цифр
  if (!words.length || !words.some(w => hay.includes(" " + w + " "))) return null;
  let season = answer.season;
  if (season === undefined || season === null) season = null;
  else if (!Number.isInteger(season) || season < 0 || season > 99) return null;
  else if (!seasonMentioned(season, [String(name), ...paths])) return null;
  return { base, season };
}

function today(now) {
  return new Date(now).toISOString().slice(0, 10);
}

function rollDay(now) {
  const d = today(now);
  if (day.d !== d) day = { d, calls: 0, e429: 0, e503: 0, fail: 0, inTok: 0, outTok: 0 };
}

/** Через сколько мс владелец снова может позвать модель (0 — уже можно). */
function cooldownLeft(owner, now = Date.now()) {
  const at = cooldowns.get(owner);
  if (!at) return 0;
  return Math.max(0, at + config.AI_TITLE_COOLDOWN_MIN * 60000 - now);
}

/** Когда владелец снова может позвать модель (мс эпохи) или 0. Для ответа медиатеки: не меняется с каждым запросом. */
function readyAt(owner, now = Date.now()) {
  return cooldownLeft(owner, now) > 0 ? cooldowns.get(owner) + config.AI_TITLE_COOLDOWN_MIN * 60000 : 0;
}

function publicView(r) {
  if (!r) return null;
  return { base: r.base || "", season: r.season ?? null, source: "ai", model: r.model, at: r.at, status: r.status };
}

/** Сохранённый результат раздачи (или null). */
function resultFor(id) {
  const k = byId.get(String(id));
  return k ? publicView(results.get(k)) : null;
}

class AiError extends Error {
  constructor(code, message, extra = {}) {
    super(message);
    this.code = code;
    Object.assign(this, extra);
  }
}

/** Один вызов Gemini. Бросает AiError: "busy" (429/503/таймаут/сеть — не списывается) или "bad" (прочее). */
async function callModel(name, files) {
  const paths = videoPaths(files);
  const text = "Название раздачи: " + String(name).slice(0, 500) + "\nФайлы:\n" + (paths.length ? paths.join("\n") : "(нет)");
  const url = `${API_BASE}/models/${encodeURIComponent(config.AI_TITLE_MODEL)}:generateContent`;
  let res;
  try {
    res = await globalThis.fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": config.GEMINI_API_KEY },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: PROMPT }] },
        contents: [{ role: "user", parts: [{ text }] }],
        generationConfig: { temperature: 0, responseMimeType: "application/json", responseSchema: RESPONSE_SCHEMA }
      }),
      signal: AbortSignal.timeout(TIMEOUT_MS)
    });
  } catch (err) {
    throw new AiError("busy", "Сервис ИИ не ответил. Попробуйте позже.", { cause: err.name || "network" });
  }
  if (res.status === 429 || res.status === 503) {
    throw new AiError("busy", "Сервис ИИ сейчас перегружен. Попробуйте позже.", { status: res.status });
  }
  let body = null;
  try { body = await res.json(); } catch {}
  if (!res.ok) throw new AiError("bad", "Сервис ИИ вернул ошибку.", { status: res.status });
  const usage = body && body.usageMetadata ? body.usageMetadata : null;
  const raw = body?.candidates?.[0]?.content?.parts?.map(p => p.text || "").join("") || "";
  let answer = null;
  try { answer = JSON.parse(raw); } catch {}
  return { answer, usage };
}

/**
 * Определить раздачу m ({ id, name, files }) для владельца owner. Уже есть результат (по id или по ключу) — он,
 * без вызова. Возвращает { aiTitle, cached }. Бросает AiError: "disabled", "cooldown" (retryAfterMs), "daily"
 * (retryAfterMs), "busy", "bad".
 */
async function suggest(m, owner, now = Date.now()) {
  if (!enabled()) throw new AiError("disabled", "Определение через ИИ на сервере не настроено.");
  const id = String(m.id);
  const key = keyOf(m.name, m.files);
  const known = results.get(byId.get(id)) || results.get(key);
  if (known) {
    if (!byId.has(id)) { byId.set(id, key); persist(); }
    return { aiTitle: publicView(known), cached: true };
  }
  if (inflight.has(key)) return inflight.get(key);
  const left = cooldownLeft(owner, now);
  if (left > 0) throw new AiError("cooldown", "ИИ можно спрашивать раз в " + config.AI_TITLE_COOLDOWN_MIN + " минут.", { retryAfterMs: left });
  rollDay(now);
  if (day.calls >= config.AI_TITLE_DAILY_MAX) {
    const midnight = Date.parse(today(now) + "T00:00:00Z") + 24 * 3600 * 1000;
    throw new AiError("daily", "Дневной лимит запросов к ИИ исчерпан. Попробуйте завтра.", { retryAfterMs: midnight - now });
  }
  const job = (async () => {
    // Место в лимите и кулдаун заняты на время вызова: два запроса подряд не проскочат оба
    day.calls++;
    cooldowns.set(owner, now);
    let out;
    try {
      out = await callModel(m.name, m.files);
    } catch (err) {
      if (err.status === 429) { day.e429++; totals.e429++; }
      else if (err.status === 503) { day.e503++; totals.e503++; }
      if (err.code === "busy") {
        day.calls--;
        cooldowns.delete(owner);
      } else {
        day.fail++; // прочие ошибки (ключ, запрос) списываются: сломанная настройка не дёргается без конца
      }
      persist();
      console.warn(`[aititle] ${m.id}: ${err.status || err.cause || err.code}`);
      throw err;
    }
    totals.calls++;
    const u = out.usage || {};
    day.inTok += Number(u.promptTokenCount) || 0;
    day.outTok += Number(u.candidatesTokenCount) || 0;
    console.log(`[aititle] ${m.id}: usage in=${u.promptTokenCount ?? "?"} out=${u.candidatesTokenCount ?? "?"} total=${u.totalTokenCount ?? "?"} files=${videoPaths(m.files).length}`);
    const ok = validate(out.answer, m.name, m.files);
    const r = ok
      ? { base: ok.base, season: ok.season, model: config.AI_TITLE_MODEL, at: now, status: "suggested", by: owner }
      : { base: "", season: null, model: config.AI_TITLE_MODEL, at: now, status: "none", by: owner };
    if (!ok) { totals.none++; console.warn(`[aititle] ${m.id}: ответ отброшен проверкой: ${JSON.stringify(out.answer)?.slice(0, 200)}`); }
    results.set(key, r);
    byId.set(id, key);
    trim();
    persist();
    return { aiTitle: publicView(r), cached: false };
  })().finally(() => inflight.delete(key));
  inflight.set(key, job);
  return job;
}

/** Владелец подтвердил или отклонил предложение. Возвращает новый результат или null (нечего решать). */
function decide(id, accept) {
  const key = byId.get(String(id));
  const r = key && results.get(key);
  if (!r || r.status === "none") return null;
  const next = accept ? "accepted" : "rejected";
  if (r.status !== next) {
    if (r.status === "accepted" || r.status === "rejected") totals[r.status] = Math.max(0, (totals[r.status] || 0) - 1);
    totals[next] = (totals[next] || 0) + 1;
    r.status = next;
    persist();
  }
  return publicView(r);
}

function trim() {
  if (results.size <= MAX_RESULTS) return;
  const order = [...results].sort((a, b) => a[1].at - b[1].at);
  const gone = new Set(order.slice(0, results.size - MAX_RESULTS).map(([k]) => k));
  for (const k of gone) results.delete(k);
  for (const [id, k] of byId) if (gone.has(k)) byId.delete(id);
}

/** Раздача удалена из AllDebrid: связь id → результат больше не нужна (сам результат остаётся по ключу). */
function dropMagnet(id) {
  if (byId.delete(String(id))) persist();
}

/** Счётчики для админа (этап замера). */
function stats(now = Date.now()) {
  rollDay(now);
  let suggested = 0;
  for (const r of results.values()) if (r.status === "suggested") suggested++;
  return { enabled: enabled(), model: config.AI_TITLE_MODEL, today: { ...day }, totals: { ...totals, suggested }, stored: results.size };
}

function _reset() {
  results = new Map();
  byId = new Map();
  cooldowns = new Map();
  day = { d: "", calls: 0, e429: 0, e503: 0, fail: 0, inTok: 0, outTok: 0 };
  totals = { calls: 0, accepted: 0, rejected: 0, none: 0, e429: 0, e503: 0 };
  inflight.clear();
}

module.exports = {
  AiError,
  enabled,
  keyOf,
  videoPaths,
  seasonMentioned,
  validate,
  cooldownLeft,
  readyAt,
  resultFor,
  suggest,
  decide,
  dropMagnet,
  stats,
  _reset
};

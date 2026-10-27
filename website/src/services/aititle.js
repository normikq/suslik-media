"use strict";

const fs = require("fs");
const crypto = require("crypto");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");

// ---- «Определить через ИИ» -------------------------------------------------------------------------------------
// Регулярки остаются основным путём. Когда они ошиблись, тот, кто добавил раздачу, может спросить Gemini: модель
// получает название раздачи и пути видеофайлов (без них не понять сезон) и возвращает { base, season }. Раздачи одной
// плитки (версии и сезоны, слитые по названию) уходят одним вызовом: у каждой свой ответ, кулдаун тратится один раз.
// Ответ не детерминирован даже при temperature 0, поэтому считается один раз и дальше берётся из хранилища:
// по ключу из нормализованного названия и состава файлов (та же раздача, добавленная заново, — без нового вызова).
// Результат — только предложение (status "suggested"): цифры в названии модель может понять неверно («Тачки 2» →
// «Тачки»). Название плитки, постер и группировка берут его, когда владелец подтвердил ("accepted"); отклонённый
// ("rejected") и пустой ("none") тоже хранятся. Спросить заново (force) может только админ библиотеки.
// Кулдаун — на пользователя (u_<id> или устройство), дневной лимит — на весь сервер: сбой клиента не сожжёт бюджет.
// Ответы 429/503 от Gemini, таймаут и сеть попытку не списывают и кулдаун не запускают.
// Хранится ai-titles.json: { r: { key: результат }, i: { magnetId: key }, c: { owner: когда звал }, d: счётчики дня, s: итоги }.
const API_BASE = "https://generativelanguage.googleapis.com/v1beta";
const TIMEOUT_MS = 20000;
const MAX_FILES = 150;
const MAX_BATCH = 10; // раздач в одном вызове
const BATCH_FILES = 300; // путей на весь вызов (у каждой раздачи не меньше 20)
const MAX_PATH = 200;
const MAX_RESULTS = 5000;
const OWNER_RE = /^(?:[a-f0-9]{32}|u_\d{1,25})$/;
const VIDEO_EXT = /\.(mkv|mp4|avi|mov|webm|m4v|m2ts|ts|iso|wmv|mpg|mpeg|vob)$/i;
const STATUSES = new Set(["suggested", "accepted", "rejected", "none"]);
const KINDS = new Set(["movie", "series", "collection"]);
// Версия ответа: 2 — с типом раздачи (kind), 3 — сезон без пометок в раздаче («Show 2 - 01», «Show - 01»).
// Ответ старой версии можно спросить заново (stale в ответе медиатеки).
const VERSION = 3;

const PROMPT = [
  "Ты получаешь одну или несколько торрент-раздач: номер, название и пути видеофайлов. Для каждой раздачи верни объект:",
  "n — её номер; base — название фильма или сериала, по которому его можно найти в TMDB: русское, если оно есть",
  "в раздаче; если название записано транслитом (\"Kak.priruchit.lisu\") — по-русски кириллицей (\"Как приручить лису\");",
  "иначе оригинальное. Слова через пробел (не через точки и подчёркивания), без года, качества, кодеков, озвучки,",
  "студии, номера сезона и серии; цифры, которые входят в само название (\"Тачки 2\", \"1+1\"), оставь.",
  "season — номер сезона, если раздача — ровно один сезон сериала (смотри и на пути: S02E01 — сезон 2), иначе null.",
  "Сезон бывает записан без пометок: \"Сериал 2 - 01.mkv\" — второй сезон (цифра после названия), если это не часть",
  "самого названия; если пометок нет вовсе и это первый сезон — 1.",
  "(фильм, несколько сезонов, сборник). kind — что это за раздача: \"movie\" — один фильм (с допами, трейлерами,",
  "частями одного фильма), \"series\" — серии сериала, аниме или шоу (один или несколько сезонов), \"collection\" —",
  "несколько разных фильмов (франшиза, трилогия, подборка)."
].join(" ");

const RESPONSE_SCHEMA = {
  type: "OBJECT",
  properties: {
    items: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          n: { type: "INTEGER" },
          base: { type: "STRING" },
          season: { type: "INTEGER", nullable: true },
          kind: { type: "STRING", enum: ["movie", "series", "collection"] }
        },
        required: ["n", "base", "kind"]
      }
    }
  },
  required: ["items"]
};

let results = new Map(); // key -> { base, season, model, at, status, by }
let byId = new Map(); // magnet id (строка) -> key
let cooldowns = new Map(); // owner -> когда последний раз звал модель
let day = { d: "", calls: 0, e429: 0, e503: 0, fail: 0, inTok: 0, outTok: 0 };
let totals = { calls: 0, accepted: 0, rejected: 0, none: 0, e429: 0, e503: 0 };
const inflight = new Map(); // key -> Promise

function load(saved) {
  try {
    if (!saved) saved = JSON.parse(fs.readFileSync(config.AI_TITLE_FILE, "utf8")) || {};
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

/** Пути видеофайлов раздачи (без sample), по алфавиту, не больше max. */
function videoPaths(files, max = MAX_FILES) {
  return (Array.isArray(files) ? files : [])
    .filter(f => f && f.type !== "folder" && VIDEO_EXT.test(String(f.path || "")) && !/(^|[\/._ -])sample([\/._ -]|$)/i.test(f.path))
    .map(f => String(f.path).slice(0, MAX_PATH))
    .sort()
    .slice(0, max);
}

/** Ключ результата: нормализованное название и состав видеофайлов. */
function keyOf(name, files) {
  const paths = videoPaths(files).map(norm);
  return crypto.createHash("sha1").update(norm(name) + "\n" + paths.join("\n")).digest("hex");
}

/** Номер сезона n упомянут в названии или путях: S01, «Season 1», «1 сезон», «Сезон 1», диапазон «1-3 сезон». */
// Любая пометка сезона в тексте: S01, «Season 2», «2 сезон», «Сезон 2», «1-3 сезон»
const ANY_SEASON = /(?<![\p{L}\d])s\d{1,2}(?!\d)|(?:season|сезон\p{L}*)[\s._:#№-]*\d{1,2}(?!\d)|(?<!\d)\d{1,2}[\s._-]*(?:-?(?:й|ый|ой))?[\s._-]*(?:season|сезон)/iu;
// Числа, которые не номер сезона: год, разрешение, кодек, битрейт, звук 5.1
const NOT_SEASON = /(?:2160|1080|720|480|576)[pi]|[xh][ .]?26[45]|\d+(?:[.,]\d+)?\s?(?:fps|kbps|mbps|bit|gb|mb)|(?<!\d)(?:19|20)\d{2}(?!\d)|(?<!\d)[1-9]\.[01](?!\d)/giu;

/**
 * Сезон n без пометок в раздаче: пометок нет ни в названии, ни в путях, и n — первый сезон или отдельное число
 * в названии раздачи вне скобок («Cyberpunk Edgerunners 2 [2160p]» — 2), не год и не разрешение.
 */
function seasonUnmarked(n, name, paths) {
  if ([name, ...paths].some(t => ANY_SEASON.test(t))) return false;
  if (n === 1) return true;
  // вне [скобок] и (скобок): там качество, озвучка, «Multi-14»
  const nums = String(name).replace(/\[[^\]]*\]|\([^)]*\)/g, " ").replace(NOT_SEASON, " ").match(/(?<!\d)\d{1,2}(?!\d)/g) || [];
  return nums.some(x => Number(x) === n);
}

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
 * Проверка ответа модели. Возвращает { base, season } или null (ответа нет, название пустое или слишком длинное).
 * Точки и подчёркивания вместо пробелов («Kak.priruchit.lisu») заменяются пробелами. Сезон без пометок принимается
 * по seasonUnmarked; если пометки есть, сезон должен с ними совпадать. Сезон, которого нет ни в названии,
 * ни в путях (S02, «2 сезон», «1-3 сезон»…), не принимается: остаётся null, название — да.
 * Название может не совпадать с раздачей (перевод, кириллица вместо транслита): его всё равно подтверждает владелец.
 * kind — "movie" | "series" | "collection" или null (неизвестный); у фильма и сборника сезона нет.
 */
function validate(answer, name, files) {
  if (!answer || typeof answer !== "object") return null;
  let base = typeof answer.base === "string" ? answer.base.trim() : "";
  if (!/\s/.test(base)) base = base.replace(/[._]+/g, " ");
  base = base.replace(/\s+/g, " ").trim();
  if (!base || base.length > 200 || /[\u0000-\u001f\u007f]/.test(base)) return null;
  let season = answer.season;
  if (!Number.isInteger(season) || season < 0 || season > 99) season = null;
  else if (!seasonMentioned(season, [String(name), ...videoPaths(files)]) && !seasonUnmarked(season, String(name), videoPaths(files))) season = null;
  const kind = KINDS.has(answer.kind) ? answer.kind : null;
  if (kind === "movie" || kind === "collection") season = null;
  return { base, season, kind };
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
  // ответы до 27.10 могли прийти с точками вместо пробелов («Kak.priruchit.lisu»)
  const base = String(r.base || "");
  return { base: /\s/.test(base) ? base : base.replace(/[._]+/g, " ").trim(), season: r.season ?? null, kind: KINDS.has(r.kind) ? r.kind : null, source: "ai", model: r.model, at: r.at, status: r.status, stale: (r.v || 1) < VERSION };
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

/**
 * Один вызов Gemini на несколько раздач ([{ name, files }]). Возвращает { answers: Map(номер с 1 -> ответ), usage }.
 * Бросает AiError: "busy" (429/503/таймаут/сеть — не списывается) или "bad" (прочее).
 */
async function callModel(list) {
  const per = Math.min(MAX_FILES, Math.max(20, Math.floor(BATCH_FILES / list.length)));
  const text = list.map((m, i) => {
    const paths = videoPaths(m.files, per);
    return "Раздача " + (i + 1) + "\nНазвание: " + String(m.name).slice(0, 500) + "\nФайлы:\n" + (paths.length ? paths.join("\n") : "(нет)");
  }).join("\n\n");
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
  let parsed = null;
  try { parsed = JSON.parse(raw); } catch {}
  const answers = new Map();
  const items = Array.isArray(parsed?.items) ? parsed.items : (parsed && typeof parsed === "object" && parsed.base ? [{ n: 1, ...parsed }] : []);
  for (const it of items) if (it && Number.isInteger(it.n) && !answers.has(it.n)) answers.set(it.n, it);
  return { answers, usage, raw };
}

/**
 * Определить раздачи magnets ([{ id, name, files }], обычно версии одной плитки) для владельца owner.
 * У кого результат уже есть (по id или по ключу) — он, без вызова; остальные (до MAX_BATCH) — одним вызовом.
 * Возвращает { results: [{ id, aiTitle }], cached } (cached — модель не звали). Бросает AiError: "disabled",
 * "cooldown" (retryAfterMs), "daily" (retryAfterMs), "busy", "bad".
 * noCooldown (админ библиотеки) — без кулдауна, дневной лимит действует; force (админ) — спросить заново и у тех,
 * у кого ответ уже есть.
 */
async function suggestMany(magnets, owner, now = Date.now(), { noCooldown = false, force = false } = {}) {
  if (!enabled()) throw new AiError("disabled", "Определение через ИИ на сервере не настроено.");
  const out = new Map(); // id -> key
  const todo = new Map(); // key -> magnet
  for (const m of magnets) {
    const id = String(m.id);
    const key = keyOf(m.name, m.files);
    out.set(id, key);
    // ответ старой версии (без типа раздачи) — как будто его нет: спросить можно ещё раз
    const fresh = k => results.has(k) && (results.get(k).v || 1) >= VERSION;
    const known = force ? null : (fresh(byId.get(id)) ? byId.get(id) : (fresh(key) ? key : null));
    if (known) {
      out.set(id, known);
      if (byId.get(id) !== known) { byId.set(id, known); persist(); }
    } else if (!todo.has(key) && todo.size < MAX_BATCH) {
      todo.set(key, m);
    }
  }
  const answer = cached => ({
    results: [...out].filter(([, k]) => results.has(k)).map(([id, k]) => ({ id: Number(id), aiTitle: publicView(results.get(k)) })),
    cached
  });
  if (!todo.size) return answer(true);
  const jobKey = [...todo.keys()].sort().join(",");
  if (inflight.has(jobKey)) { await inflight.get(jobKey).catch(() => {}); return answer(true); }
  const left = noCooldown ? 0 : cooldownLeft(owner, now);
  if (left > 0) throw new AiError("cooldown", "ИИ можно спрашивать раз в " + config.AI_TITLE_COOLDOWN_MIN + " минут.", { retryAfterMs: left });
  rollDay(now);
  if (day.calls >= config.AI_TITLE_DAILY_MAX) {
    const midnight = Date.parse(today(now) + "T00:00:00Z") + 24 * 3600 * 1000;
    throw new AiError("daily", "Дневной лимит запросов к ИИ исчерпан. Попробуйте завтра.", { retryAfterMs: midnight - now });
  }
  const list = [...todo];
  const job = (async () => {
    // Место в лимите и кулдаун заняты на время вызова: два запроса подряд не проскочат оба
    day.calls++;
    if (!noCooldown) cooldowns.set(owner, now);
    let res;
    try {
      res = await callModel(list.map(([, m]) => m));
    } catch (err) {
      if (err.status === 429) { day.e429++; totals.e429++; }
      else if (err.status === 503) { day.e503++; totals.e503++; }
      if (err.code === "busy") {
        day.calls--;
        if (!noCooldown) cooldowns.delete(owner);
      } else {
        day.fail++; // прочие ошибки (ключ, запрос) списываются: сломанная настройка не дёргается без конца
      }
      persist();
      console.warn(`[aititle] ${list.map(([, m]) => m.id).join(",")}: ${err.status || err.cause || err.code}`);
      throw err;
    }
    totals.calls++;
    const u = res.usage || {};
    day.inTok += Number(u.promptTokenCount) || 0;
    day.outTok += Number(u.candidatesTokenCount) || 0;
    console.log(`[aititle] ${list.map(([, m]) => m.id).join(",")}: usage in=${u.promptTokenCount ?? "?"} out=${u.candidatesTokenCount ?? "?"} total=${u.totalTokenCount ?? "?"} releases=${list.length}`);
    list.forEach(([key, m], i) => {
      const ok = validate(res.answers.get(i + 1), m.name, m.files);
      const r = ok
        ? { base: ok.base, season: ok.season, kind: ok.kind, model: config.AI_TITLE_MODEL, at: now, status: "suggested", by: owner, v: VERSION }
        : { base: "", season: null, kind: null, model: config.AI_TITLE_MODEL, at: now, status: "none", by: owner, v: VERSION };
      if (!ok) { totals.none++; console.warn(`[aititle] ${m.id}: нет ответа: ${String(res.raw).slice(0, 300)}`); }
      results.set(key, r);
      byId.set(String(m.id), key);
      out.set(String(m.id), key);
    });
    trim();
    persist();
  })().finally(() => inflight.delete(jobKey));
  inflight.set(jobKey, job);
  await job;
  return answer(false);
}

/** Одна раздача: { aiTitle, cached }. */
async function suggest(m, owner, now = Date.now(), opts = {}) {
  const r = await suggestMany([m], owner, now, opts);
  return { aiTitle: r.results[0] ? r.results[0].aiTitle : null, cached: r.cached };
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
  seasonUnmarked,
  validate,
  cooldownLeft,
  readyAt,
  resultFor,
  suggest,
  suggestMany,
  _load: load,
  decide,
  dropMagnet,
  stats,
  _reset
};

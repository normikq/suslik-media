"use strict";

// RuTracker из Discord Activity через ПК (docs/DISCORD-ACTIVITY.md, «Поиск раздач»).
// У сервера своего аккаунта RuTracker может не быть, а у ПК он есть (свои cookie, фоновый браузер). Каждый
// вошедший ПК (Qt) держит лёгкий поток «помощника» (GET /api/rutracker/helper/events) — в комнате он или нет.
// Activity просит сервер, сервер шлёт rutracker_request помощникам тех, кто сейчас в этой комнате (у пульта или
// в плеере), ПК ищет у себя и отвечает POST /api/rutracker/helper/reply.
// «add» — раздача без magnet: .torrent качает тот же ПК (target — отпечаток его client_id) и сам кладёт в AllDebrid.

const crypto = require("crypto");
const rooms = require("./rooms");

const SEARCH_MS = 25000; // RuTracker через фоновый браузер ПК бывает медленным
const ADD_MS = 45000;
const MAX_PENDING_PER_ROOM = 4;
const MAX_HITS = 100;

const pending = new Map(); // request_id → { roomId, kind, target, userId, done, timer }

const helpers = new Map(); // отпечаток client_id → { key, clientId, userId, name, send }

/** Поток помощника ПК; возвращает «отключить». Новый поток того же ПК заменяет старый. */
function addHelper(user, clientId, send) {
  const key = rooms.clientKey(clientId);
  const h = { key, clientId, userId: user.id, name: user.name, send };
  helpers.set(key, h);
  return () => { if (helpers.get(key) === h) helpers.delete(key); };
}

/** Помощники тех, кто сейчас в комнате: у пульта Activity или в плеере. */
function roomHelpers(room, target) {
  const users = new Set(room.watchers ? room.watchers.keys() : []);
  for (const m of room.members.values()) users.add(m.id);
  return [...helpers.values()].filter(h => users.has(h.userId) && (!target || h.key === target));
}

function str(v, max) {
  return typeof v === "string" ? v.trim().slice(0, max) : "";
}
function int(v) {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0;
}

/** Ответ ПК — чужие данные: только известные поля, с ограничениями. */
function cleanHit(h) {
  if (!h || typeof h !== "object") return null;
  const title = str(h.title, 400);
  const topicId = /^\d{1,12}$/.test(String(h.topic_id ?? "")) ? String(h.topic_id) : "";
  const magnet = str(h.magnet, 3000);
  const okMagnet = /^magnet:\?xt=urn:btih:[0-9a-z]{32,40}(&|$)/i.test(magnet) ? magnet : "";
  if (!title || (!topicId && !okMagnet)) return null;
  return {
    title,
    size: int(h.size),
    seeders: int(h.seeders),
    leechers: int(h.leechers),
    date: str(h.date, 40),
    kind: ["movie", "series", "anime", "cartoon", "other"].includes(h.kind) ? h.kind : "movie",
    topic_id: topicId || null,
    magnet: okMagnet || null,
    badges: Array.isArray(h.badges) ? h.badges.map(b => str(b, 24)).filter(Boolean).slice(0, 8) : []
  };
}

function finish(id, value) {
  const p = pending.get(id);
  if (!p) return false;
  pending.delete(id);
  clearTimeout(p.timer);
  p.done(value);
  return true;
}

/**
 * Запрос к ПК. Promise всегда выполняется: { ok, ... } от ПК или { ok:false, reason } —
 * "no_pc" (ни у кого в комнате не включён ПК), "busy" (слишком много запросов), "timeout" (ПК не ответили).
 */
function ask(room, user, kind, data) {
  const hs = roomHelpers(room, data.target);
  if (!hs.length) return Promise.resolve({ ok: false, reason: "no_pc" });
  let inRoom = 0;
  for (const p of pending.values()) if (p.roomId === room.id) inRoom++;
  if (inRoom >= MAX_PENDING_PER_ROOM) return Promise.resolve({ ok: false, reason: "busy" });
  const requestId = "rq_" + crypto.randomBytes(8).toString("hex");
  return new Promise(resolve => {
    const timer = setTimeout(() => finish(requestId, { ok: false, reason: "timeout" }), kind === "add" ? ADD_MS : SEARCH_MS);
    timer.unref?.();
    pending.set(requestId, { roomId: room.id, kind, allowed: new Set(hs.map(h => h.key)), done: resolve, timer, asked: hs.length, errors: [] });
    const msg = JSON.stringify({ event: "rutracker_request", data: { request_id: requestId, kind, by: user.name, ...data }, time: Date.now() });
    for (const h of hs) {
      try { h.send(msg); } catch { /* оборванный поток уберёт «close» */ }
    }
  });
}

function search(room, user, query) {
  return ask(room, user, "search", { query });
}

function add(room, user, { target, topicId, title }) {
  return ask(room, user, "add", { target, topic_id: topicId, title });
}

/**
 * Ответ ПК: body { client_id, request_id, error?, hits? (search), id?, ready? (add) }.
 * Принимается только от помощника, которого спрашивали, и под тем же аккаунтом, что и его поток.
 */
function reply(user, body) {
  const p = pending.get(String(body?.request_id || ""));
  if (!p) return { ok: false, status: 404, error: "Запрос устарел." };
  const clientId = String(body.client_id || "");
  const key = rooms.clientKey(clientId);
  const h = key && helpers.get(key);
  if (!h || h.clientId !== clientId || h.userId !== user.id || !p.allowed.has(key)) return { ok: false, status: 403, error: "Запрос другому компьютеру." };
  const error = str(body.error, 300);
  if (p.kind === "search") {
    if (error) {
      // Ошибка одного ПК — ещё не ответ: другой ПК в комнате может найти. Ошиблись все — это и ответ
      p.errors.push(error);
      if (p.errors.length >= p.asked) finish(body.request_id, { ok: false, reason: "failed", error: p.errors[0] });
      return { ok: true };
    }
    const hits = (Array.isArray(body.hits) ? body.hits : []).slice(0, MAX_HITS).map(cleanHit).filter(Boolean);
    finish(body.request_id, { ok: true, hits, pc: { key, name: h.name } });
    return { ok: true };
  }
  const id = Number(body.id);
  if (error || !Number.isSafeInteger(id) || id <= 0) finish(body.request_id, { ok: false, reason: "failed", error: error || "ПК не смог добавить раздачу." });
  else finish(body.request_id, { ok: true, id, ready: body.ready === true });
  return { ok: true };
}

function _reset() {
  for (const id of [...pending.keys()]) finish(id, { ok: false, reason: "timeout" });
}

module.exports = { search, add, reply, addHelper, cleanHit, _reset };

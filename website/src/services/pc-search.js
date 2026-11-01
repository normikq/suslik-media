"use strict";

// RuTracker из Discord Activity через ПК в комнате (docs/DISCORD-ACTIVITY.md, «Поиск раздач»).
// У сервера своего аккаунта RuTracker может не быть, а у ПК он есть (свои cookie, фоновый браузер). Activity просит
// сервер, сервер шлёт в поток комнаты rutracker_request, ПК ищет у себя и отвечает POST …/rutracker/reply.
// «add» — раздача без magnet: .torrent качает тот же ПК (target — отпечаток его client_id) и сам кладёт в AllDebrid.

const crypto = require("crypto");
const rooms = require("./rooms");

const SEARCH_MS = 25000; // RuTracker через фоновый браузер ПК бывает медленным
const ADD_MS = 45000;
const MAX_PENDING_PER_ROOM = 4;
const MAX_HITS = 100;

const pending = new Map(); // request_id → { roomId, kind, target, userId, done, timer }

/** ПК в комнате (client_id приложения Qt и прежнего ПК начинается с «pc»). */
function pcMembers(room) {
  return [...room.members.values()].filter(m => /^pc/i.test(String(m.client_id || "")));
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
 * Запрос к ПК комнаты. Promise всегда выполняется: { ok, ... } от ПК или { ok:false, reason } —
 * "no_pc" (в комнате нет ПК), "busy" (слишком много запросов), "timeout" (ни один ПК не ответил).
 */
function ask(room, user, kind, data) {
  const pcs = pcMembers(room).filter(m => !data.target || rooms.clientKey(m.client_id) === data.target);
  if (!pcs.length) return Promise.resolve({ ok: false, reason: "no_pc" });
  let inRoom = 0;
  for (const p of pending.values()) if (p.roomId === room.id) inRoom++;
  if (inRoom >= MAX_PENDING_PER_ROOM) return Promise.resolve({ ok: false, reason: "busy" });
  const requestId = "rq_" + crypto.randomBytes(8).toString("hex");
  return new Promise(resolve => {
    const timer = setTimeout(() => finish(requestId, { ok: false, reason: "timeout" }), kind === "add" ? ADD_MS : SEARCH_MS);
    timer.unref?.();
    pending.set(requestId, { roomId: room.id, kind, target: data.target || null, userId: user.id, done: resolve, timer, asked: pcs.length, errors: [] });
    rooms.broadcast(room.id, "rutracker_request", { request_id: requestId, kind, by: user.name, ...data });
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
 * Принимается только от ПК этой комнаты под тем же аккаунтом, что и его client_id; для add — только от нужного ПК.
 */
function reply(roomId, user, body) {
  const p = pending.get(String(body?.request_id || ""));
  if (!p || p.roomId !== roomId) return { ok: false, status: 404, error: "Запрос устарел." };
  const room = rooms.getRoom(roomId);
  const member = room && room.members.get(String(body.client_id || ""));
  if (!member || member.id !== user.id || !/^pc/i.test(member.client_id)) return { ok: false, status: 403, error: "Вы не в этой комнате." };
  const key = rooms.clientKey(member.client_id);
  if (p.target && p.target !== key) return { ok: false, status: 403, error: "Запрос другому компьютеру." };
  const error = str(body.error, 300);
  if (p.kind === "search") {
    if (error) {
      // Ошибка одного ПК — ещё не ответ: другой ПК в комнате может найти. Ошиблись все — это и ответ
      p.errors.push(error);
      if (p.errors.length >= p.asked) finish(body.request_id, { ok: false, reason: "failed", error: p.errors[0] });
      return { ok: true };
    }
    const hits = (Array.isArray(body.hits) ? body.hits : []).slice(0, MAX_HITS).map(cleanHit).filter(Boolean);
    finish(body.request_id, { ok: true, hits, pc: { key, name: member.name } });
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

module.exports = { search, add, reply, cleanHit, pcMembers, _reset };

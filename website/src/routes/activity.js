"use strict";

// Discord Activity: совместный просмотр прямо в голосовом канале (docs/DISCORD-ACTIVITY.md).
// Страница (public/activity/) — пульт комнаты: что играет, медиатека, голосование, кнопки «Открыть в приложении».
// Само видео идёт в приложениях (ПК, Android, ТВ): раздачи тяжёлые, iframe Discord их не потянет.

const express = require("express");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const config = require("../config");
const { requireLogin, resolveAuth } = require("../middleware/auth");
const { activityAuthLimiter, roomLimiter, apiLimiter, searchLimiter } = require("../middleware/rate-limit");
const { DISCORD_FRAME_ANCESTORS, isActivityPage } = require("../middleware/security");
const { exchangeOAuthCode, fetchMe, revokeOAuthToken, checkGuildMember, inviteMessage, sendChannelMessage } = require("../services/discord");
const { findOrCreateUser } = require("../services/users");
const { issueActivityToken, revokeActivityToken } = require("../services/activity");
const { basePath } = require("../utils/helpers");
const rooms = require("../services/rooms");
const pcSearch = require("../services/pc-search");

const router = express.Router();

const PAGE_CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob: https://cdn.discordapp.com",
  "font-src 'self' data:",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
  DISCORD_FRAME_ANCESTORS
].join("; ");

// Скрипты и стили с ?v= сервер отдаёт «навсегда» (app.js, immutable), поэтому v — хеш содержимого файла:
// новая выкладка меняет адрес, и Discord не держит старый activity.js. Считается один раз за запуск сервера.
const assetHashes = new Map();
function assetHash(rel) { // rel — от public/: «activity/activity.js», «searchcore.js»
  if (!assetHashes.has(rel)) {
    let v = "0";
    try { v = crypto.createHash("sha256").update(fs.readFileSync(path.join(config.PUBLIC_DIR, rel))).digest("hex").slice(0, 10); } catch { /* нет файла */ }
    assetHashes.set(rel, v);
  }
  return assetHashes.get(rel);
}
function withAssetVersions(html) {
  return html.replace(/((?:src|href)=")((?:\.\.\/)?)((?:activity\/)?[\w.-]+\.(?:js|css))\?v=\w+/g,
    (_, attr, up, rel) => `${attr}${up}${rel}?v=${assetHash(rel)}`);
}

const pages = new Map();
function pageHtml(file) {
  if (!pages.has(file)) {
    let html = "";
    try { html = withAssetVersions(fs.readFileSync(path.join(config.PUBLIC_DIR, file), "utf8")); } catch { /* нет файла */ }
    pages.set(file, html);
  }
  return pages.get(file);
}

function sendPage(res, file, csp) {
  const html = pageHtml(file);
  if (!html) return res.status(404).end();
  res.setHeader("Content-Security-Policy", csp);
  res.setHeader("Cache-Control", "no-cache");
  res.type("html").send(html);
}

// Discord открывает корень сопоставления адресов с frame_id и instance_id («/?activity» — напрямую, для проверки).
// Страница лежит в корне: её относительные адреса (activity/…, api/…) работают и за путём сервера (/media/…)
function activityPage(req, res, next) {
  if (!isActivityPage(req)) return next();
  sendPage(res, "activity/index.html", PAGE_CSP);
}

// Адрес сайта для ссылок наружу (браузер, приложения). Внутри Activity страница видна как <id>.discordsays.com —
// туда приложение не сходит. ACTIVITY_SITE_URL в .env главнее; иначе адрес из запроса, иначе из DISCORD_REDIRECT_URI.
function siteUrl(req) {
  if (config.ACTIVITY_SITE_URL) return config.ACTIVITY_SITE_URL;
  const host = String(req.get("host") || "");
  if (host && !/discordsays\.com$/i.test(host.split(":")[0])) {
    return `${req.protocol}://${host}${basePath(req)}${config.SITE_PREFIX || ""}`;
  }
  try {
    const u = new URL(config.DISCORD_REDIRECT_URI);
    return `${u.origin}${u.pathname.replace(/\/api\/auth\/discord\/callback\/?$/, "")}`.replace(/\/+$/, "");
  } catch {
    return "";
  }
}

// 0. ID приложения Discord для рукопожатия с клиентом (публичный: он и так в ссылке входа)
router.get("/api/activity/config", apiLimiter, (req, res) => {
  res.json({ ok: true, client_id: config.DISCORD_CLIENT_ID || "", enabled: config.DISCORD_ENABLED });
});

// 1. Вход: одноразовый код из commands.authorize Discord SDK → ключ Activity.
// Если человек разрешил rpc.activities.write (статус «Смотрит …» в профиле Discord), токен Discord не отзывается, а
// отдаётся странице: с ним она зовёт commands.authenticate и потом SET_ACTIVITY. Сервер его не хранит.
router.post("/api/auth/discord/activity", activityAuthLimiter, async (req, res) => {
  if (!config.DISCORD_ENABLED) return res.status(503).json({ error: "Вход через Discord не настроен на сервере." });
  const code = typeof req.body?.code === "string" ? req.body.code.trim() : "";
  if (!code || code.length > 200) return res.status(400).json({ error: "Discord не прислал код входа." });
  let accessToken = null;
  try {
    const tokenData = await exchangeOAuthCode(code, null);
    accessToken = tokenData.access_token;
    const me = await fetchMe(accessToken);
    const scopes = String(tokenData.scope || "").split(/\s+/);
    const presence = scopes.includes("rpc.activities.write") ? accessToken : null;
    if (!presence) revokeOAuthToken(accessToken);
    accessToken = null;
    const check = await checkGuildMember(me.id);
    if (!check.allowed) {
      if (presence) revokeOAuthToken(presence);
      const error =
        check.reason === "not_in_guild"
          ? "Нет доступа: ваш аккаунт Discord не состоит на сервере Suslik Media."
          : check.reason === "no_role"
            ? "Нет доступа: у вашего аккаунта нет нужной роли на сервере Discord."
            : "Нет доступа к медиатеке.";
      return res.status(403).json({ error });
    }
    const user = findOrCreateUser(me, check.role);
    const { token, expires_in } = issueActivityToken(user.id);
    res.setHeader("Cache-Control", "no-store");
    res.json({
      ok: true,
      token,
      expires_in,
      site_url: siteUrl(req),
      user: { id: user.id, name: user.name, avatar: user.avatar, role: user.role },
      ...(presence ? { discord_token: presence } : {})
    });
  } catch (err) {
    if (accessToken) revokeOAuthToken(accessToken);
    console.error("Вход из Discord Activity:", err.response?.status || "", err.message);
    res.status(502).json({ error: "Не удалось войти через Discord. Попробуйте открыть Activity ещё раз." });
  }
});

router.post("/api/auth/discord/activity/logout", (req, res) => {
  revokeActivityToken((req.get("Authorization") || "").replace(/^Bearer\s+/i, ""));
  res.json({ ok: true });
});

// 2. Комната голосового канала: первый, кто открыл Activity, — ведущий
router.post("/api/activity/room", requireLogin, roomLimiter, (req, res) => {
  if (!req.activity) return res.status(403).json({ error: "Только из Discord Activity." });
  const room = rooms.getOrCreateChannelRoom(req.body?.channel_id, req.user);
  if (!room) return res.status(400).json({ error: "Activity открыта не в голосовом канале." });
  res.json({ ok: true, room: rooms.getRoomPublicData(room), me: { id: req.user.id, name: req.user.name, avatar: req.user.avatar } });
});

// 3. Поток событий пульта: без входа в плеер комнаты (иначе ПК того же человека не смог бы войти)
router.get("/api/activity/rooms/:id/events", requireLogin, (req, res) => {
  const room = rooms.getRoom(req.params.id);
  if (!room || !room.channel_id) return res.status(404).json({ error: "Комната не найдена." });

  res.setHeader("Content-Type", "text/event-stream");
  // no-transform: модуль compression иначе копит поток для сжатия (браузер всегда шлёт Accept-Encoding)
  res.setHeader("Cache-Control", "no-cache, no-transform");
  res.setHeader("Connection", "keep-alive");
  res.setHeader("X-Accel-Buffering", "no");
  res.flushHeaders?.();
  res.on("error", () => {});
  const send = text => {
    if (!res.destroyed && !res.writableEnded) res.write(text);
  };
  // Сначала «у пульта» (остальным — activity_changed), потом свой поток: себе — снимок init, где ты уже есть
  const leave = rooms.addWatcher(room, req.user);
  const removeListener = rooms.addListener(room.id, msg => send(`data: ${msg}\n\n`));
  send(`data: ${JSON.stringify({ event: "init", room: rooms.getRoomPublicData(room), server_time: Date.now() })}\n\n`);
  const pingTimer = setInterval(() => {
    if (!resolveAuth(req)) return res.end();
    send(": ping\n\n");
  }, 20000);
  pingTimer.unref?.();
  res.on("close", () => {
    clearInterval(pingTimer);
    removeListener();
    leave();
  });
});

// Тот же поток событий, но опросом: если прокси Discord держит поток пачкой (тогда страница переходит на опрос)
router.get("/api/activity/rooms/:id", requireLogin, (req, res) => {
  const room = rooms.getRoom(req.params.id);
  if (!room || !room.channel_id) return res.status(404).json({ error: "Комната не найдена." });
  res.setHeader("Cache-Control", "no-store");
  res.json({ ok: true, room: rooms.getRoomPublicData(room), server_time: Date.now() });
});

// 4. Предложения и голосование
function reply(res, result) {
  if (!result.ok) return res.status(400).json({ error: result.error });
  res.json({ ok: true });
}
router.post("/api/activity/rooms/:id/suggest", requireLogin, roomLimiter, (req, res) =>
  reply(res, rooms.suggestFile(req.params.id, req.user, req.body?.file)));
router.post("/api/activity/rooms/:id/unsuggest", requireLogin, roomLimiter, (req, res) =>
  reply(res, rooms.unsuggestFile(req.params.id, req.user, String(req.body?.key || ""))));
router.post("/api/activity/rooms/:id/poll", requireLogin, roomLimiter, (req, res) =>
  reply(res, rooms.startPoll(req.params.id, req.user)));
router.post("/api/activity/rooms/:id/poll/cancel", requireLogin, roomLimiter, (req, res) =>
  reply(res, rooms.cancelPoll(req.params.id, req.user)));
router.post("/api/activity/rooms/:id/vote", requireLogin, roomLimiter, (req, res) =>
  reply(res, rooms.vote(req.params.id, req.user, String(req.body?.key || ""))));
// «Итог сейчас» и «Рулетка» — только ведущий
router.post("/api/activity/rooms/:id/poll/finish", requireLogin, roomLimiter, (req, res) =>
  reply(res, rooms.finishPollNow(req.params.id, req.user)));
router.post("/api/activity/rooms/:id/roulette", requireLogin, roomLimiter, (req, res) =>
  reply(res, rooms.spinRoulette(req.params.id, req.user, rooms.spinMs(req.body?.seconds))));

// «Позвать в чат»: бот пишет в текстовый чат голосового канала код комнаты и ссылку /join/<код>.
// Бот не настроен или не может писать в канал — { fallback: true, link }: страница копирует ссылку сама.
// Одно приглашение на комнату раз в INVITE_GAP_MS, чтобы чат не засыпали.
const INVITE_GAP_MS = 30 * 1000;
const lastInvite = new Map(); // room id -> когда звали
router.post("/api/activity/rooms/:id/invite", requireLogin, roomLimiter, async (req, res) => {
  const room = rooms.getRoom(req.params.id);
  if (!room || !room.channel_id) return res.status(404).json({ error: "Комната не найдена." });
  if (!room.watchers.has(req.user.id)) return res.status(403).json({ error: "Позвать может тот, кто в комнате." });
  const site = siteUrl(req);
  const link = site ? `${site}/join/${room.id}` : "";
  const fallback = reason => res.json({ ok: true, fallback: true, reason, link });
  if (!config.DISCORD_BOT_TOKEN) return fallback("no_bot");
  const now = Date.now();
  const left = (lastInvite.get(room.id) || 0) + INVITE_GAP_MS - now;
  if (left > 0) return res.status(429).json({ error: `Приглашение уже отправлено. Ещё раз — через ${Math.ceil(left / 1000)} с.`, link });
  lastInvite.set(room.id, now);
  for (const [id, at] of lastInvite) if (at + INVITE_GAP_MS < now) lastInvite.delete(id);
  try {
    await sendChannelMessage(room.channel_id, inviteMessage({ code: room.id, film: room.file && room.file.name, by: req.user.name, link }));
    res.json({ ok: true, sent: true, link });
  } catch (err) {
    lastInvite.delete(room.id);
    const st = err.response?.status;
    console.warn("Приглашение в чат канала:", st || "", err.response?.data?.message || err.message);
    fallback(st === 403 || st === 404 ? "no_access" : "failed");
  }
});

// 5. Поиск раздач: серверные трекеры Activity берёт обычным /api/search, а RuTracker — у ПК комнаты (services/pc-search.js)
const PC_REASONS = {
  no_pc: "В комнате нет ПК с Suslik Media: RuTracker ищет приложение на компьютере, вошедшее в эту комнату.",
  busy: "ПК уже ищет. Подождите несколько секунд.",
  timeout: "ПК в комнате не ответил.",
  failed: "ПК не смог выполнить запрос."
};
function channelRoom(req, res) {
  const room = rooms.getRoom(req.params.id);
  if (!room || !room.channel_id) { res.status(404).json({ error: "Комната не найдена." }); return null; }
  return room;
}
function pcAnswer(res, r) {
  res.setHeader("Cache-Control", "no-store");
  if (r.ok) return res.json(r);
  res.json({ ok: false, reason: r.reason, error: r.error || PC_REASONS[r.reason] || PC_REASONS.failed });
}
router.post("/api/activity/rooms/:id/rutracker/search", requireLogin, searchLimiter, async (req, res) => {
  const room = channelRoom(req, res);
  if (!room) return;
  const query = typeof req.body?.query === "string" ? req.body.query.trim().slice(0, 200) : "";
  if (query.length < 2) return res.status(400).json({ error: "Введите хотя бы два символа." });
  pcAnswer(res, await pcSearch.search(room, req.user, query));
});
// Раздача RuTracker без magnet: .torrent есть только у того ПК, что её нашёл, — он её и добавляет
router.post("/api/activity/rooms/:id/rutracker/add", requireLogin, apiLimiter, async (req, res) => {
  const room = channelRoom(req, res);
  if (!room) return;
  const target = typeof req.body?.pc === "string" ? req.body.pc.slice(0, 64) : "";
  const topicId = /^\d{1,12}$/.test(String(req.body?.topic_id ?? "")) ? String(req.body.topic_id) : "";
  if (!target || !topicId) return res.status(400).json({ error: "Нет номера раздачи." });
  const title = typeof req.body?.title === "string" ? req.body.title.trim().slice(0, 200) : "";
  pcAnswer(res, await pcSearch.add(room, req.user, { target, topicId, title }));
});
// Ответ ПК (приложение Qt): только участник этой комнаты со своим client_id
router.post("/api/rooms/:id/rutracker/reply", requireLogin, roomLimiter, (req, res) => {
  const r = pcSearch.reply(String(req.params.id), req.user, req.body);
  if (!r.ok) return res.status(r.status || 400).json({ error: r.error });
  res.json({ ok: true });
});

// 6. Страница «Открыть в приложении»: Activity открывает её в браузере (commands.openExternalLink), а она —
// suslik://room/<код> (ПК и Android). Здесь же код для ТВ и ссылки на скачивание. Входа не нужно: только код.
let joinTemplate = null;
function joinPage() {
  if (joinTemplate === null) {
    try { joinTemplate = withAssetVersions(fs.readFileSync(path.join(config.PUBLIC_DIR, "activity", "join.html"), "utf8")); } catch { joinTemplate = ""; }
  }
  return joinTemplate;
}
router.get("/join/:code", apiLimiter, (req, res) => {
  // /join/842-190/ → без слэша: иначе относительные ../activity/… уходят мимо
  if (req.path.endsWith("/")) return res.redirect(301, `${basePath(req)}${config.SITE_PREFIX || ""}${req.path.replace(/\/+$/, "")}`);
  const code = String(req.params.code || "").replace(/[^0-9a-z]/gi, "").toLowerCase().slice(0, 24);
  const html = joinPage();
  if (!code || !html) return res.status(404).type("text").send("Нет такой комнаты.");
  const pretty = /^\d{6}$/.test(code) ? `${code.slice(0, 3)}-${code.slice(3)}` : code;
  res.setHeader("Cache-Control", "no-store");
  res.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
  res.type("html").send(html.replaceAll("{{CODE}}", code).replaceAll("{{PRETTY}}", pretty));
});

module.exports = { router, activityPage, siteUrl };

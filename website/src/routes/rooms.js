"use strict";

const express = require("express");
const { requireLogin, resolveAuth } = require("../middleware/auth");
const { roomLimiter } = require("../middleware/rate-limit");
const { sseSender } = require("../utils/helpers");
const {
  createRoom,
  getRoom,
  getRoomPublicData,
  addListener,
  joinRoom,
  findUserActiveRoom,
  moveDevice,
  moveAccount,
  registerMemberStream,
  unregisterMemberStream,
  previewMember,
  publicMember,
  leaveRoom,
  handleRoomAction
} = require("../services/rooms");

const router = express.Router();

// 1. Создание новой комнаты совместного просмотра
router.post("/api/rooms", requireLogin, roomLimiter, (req, res) => {
  const { file, controls, client_id } = req.body || {};
  // При общем пароле ведущего отличает только устройство: без client_id комнату не создать (все приложения его шлют)
  if (req.user && req.user.id === "shared" && !(typeof client_id === "string" && client_id.trim())) {
    return res.status(400).json({ error: "Не хватает client_id устройства: обновите приложение." });
  }
  // Перемещение аккаунта и устройства: старые комнаты освобождаются (выключился свет, другое устройство)
  moveAccount(req.user, client_id, null);
  moveDevice(client_id, null);

  const room = createRoom(req.user, file, controls, client_id);
  res.json({ ok: true, room: getRoomPublicData(room) });
});

// 2. Получение информации о комнате
router.get("/api/rooms/:id", requireLogin, (req, res) => {
  const room = getRoom(req.params.id);
  if (!room) return res.status(404).json({ error: "Комната не найдена." });
  res.json({ ok: true, room: getRoomPublicData(room) });
});

// 3. Вход в комнату
router.post("/api/rooms/:id/join", requireLogin, roomLimiter, (req, res) => {
  const room = getRoom(req.params.id);
  if (!room) return res.status(404).json({ error: "Комната не найдена." });

  const { client_id } = req.body || {};
  // Вход с нового устройства того же аккаунта (или переподключение): старое зависшее место освобождается,
  // при отключении питания на ПК или смене устройства человек спокойно входит с телефона
  moveAccount(req.user, client_id, room.id);
  moveDevice(client_id, room.id);

  // В список участников попадают с открытием потока событий (/events), здесь только «кто я»
  res.json({ ok: true, room: getRoomPublicData(room), member: previewMember(req.user, client_id) });
});

// 4. Выход из комнаты
router.post("/api/rooms/:id/leave", requireLogin, (req, res) => {
  const { client_id } = req.body || {};
  if (client_id) {
    leaveRoom(req.params.id, client_id, true);
  } else if (req.user && req.user.id && req.user.id !== "shared") {
    const room = getRoom(req.params.id);
    if (room) {
      for (const m of room.members.values()) {
        if (m && String(m.id) === String(req.user.id)) {
          leaveRoom(req.params.id, m.client_id, true);
        }
      }
    }
  }
  res.json({ ok: true });
});

// 5. Отправка команды управления (play, pause, seek, rate, buffering)
router.post("/api/rooms/:id/action", requireLogin, roomLimiter, (req, res) => {
  const result = handleRoomAction(req.params.id, req.user, req.body);
  if (!result.ok) {
    return res.status(400).json({ error: result.error });
  }
  res.json(result);
});

// 6. Поток событий реального времени (Server-Sent Events)
router.get("/api/rooms/:id/events", requireLogin, (req, res) => {
  const room = getRoom(req.params.id);
  if (!room) return res.status(404).json({ error: "Комната не найдена." });

  const clientId = req.query.client_id;

  // Включаем TCP keepalive для раннего обнаружения внезапного обрыва связи (отключение питания, перезагрузка роутера)
  if (req.socket && typeof req.socket.setKeepAlive === "function") {
    req.socket.setKeepAlive(true, 10000);
  }

  // Если аккаунт уже активно смотрит с другого устройства в этой или другой комнате —
  // фоновое авто-переподключение старого вытесненного устройства не должно перебивать новый сеанс
  const active = findUserActiveRoom(req.user, clientId, room.id);
  if (active) {
    const error =
      active.reason === "other_device_same_room"
        ? "Этот аккаунт уже подключен к комнате с другого устройства."
        : "Этот аккаунт уже находится в активной комнате.";
    return res.status(403).json({ error });
  }

  const joined = joinRoom(room.id, req.user, clientId);
  if (!joined || !joined.member) {
    return res.status(400).json({ error: joined?.error || "Не удалось подключиться к комнате." });
  }

  res.setHeader("Content-Type", "text/event-stream");
  res.setHeader("Cache-Control", "no-cache");
  res.setHeader("Connection", "keep-alive");
  res.setHeader("X-Accel-Buffering", "no"); // nginx отдаёт события сразу, а не пачкой
  res.flushHeaders?.();

  registerMemberStream(room.id, joined.member.client_id, res);

  // Отправляем текущее состояние сразу при подключении
  // Себе — и свой client_id, остальные видят только отпечаток (key)
  const me = { ...publicMember(joined.member), client_id: joined.member.client_id };
  res.write(`data: ${JSON.stringify({ event: "init", room: getRoomPublicData(room), member: me })}\n\n`);

  // Запись в оборванный поток — не повод падать: ошибку сокета гасим, закрытие разберёт «close»
  res.on("error", () => {});
  const send = sseSender(res);

  const removeListener = addListener(room.id, msg => send(`data: ${msg}\n\n`));

  const pingTimer = setInterval(() => {
    // Вход отозван (бот снял роль, вышли, пароль отключён): поток закрывается, а не слушает комнату дальше.
    // Заодно так раз в 15 минут перепроверяется роль у тех, кто только смотрит.
    if (!resolveAuth(req)) return res.end();
    send(": ping\n\n");
  }, 20000);

  // Обрыв соединения (res, а не req: у req «close» бывает и раньше, когда запрос просто дочитан)
  res.on("close", () => {
    clearInterval(pingTimer);
    removeListener();
    unregisterMemberStream(room.id, joined.member.client_id, res);
    // Только своё место: устройство могло уже перейти в другую комнату и вернуться сюда новым участником (moveDevice)
    if (joined && joined.member && room.members.get(joined.member.client_id) === joined.member) {
      leaveRoom(room.id, joined.member.client_id);
    }
  });
});

module.exports = router;

"use strict";

const express = require("express");
const { requireLogin } = require("../middleware/auth");
const {
  createRoom,
  getRoom,
  getRoomPublicData,
  addListener,
  joinRoom,
  previewMember,
  leaveRoom,
  handleRoomAction
} = require("../services/rooms");

const router = express.Router();

// 1. Создание новой комнаты совместного просмотра
router.post("/api/rooms", requireLogin, (req, res) => {
  const { file, controls, client_id } = req.body || {};
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
router.post("/api/rooms/:id/join", requireLogin, (req, res) => {
  const room = getRoom(req.params.id);
  if (!room) return res.status(404).json({ error: "Комната не найдена." });

  const { client_id } = req.body || {};
  // В список участников попадают с открытием потока событий (/events), здесь только «кто я»
  res.json({ ok: true, room: getRoomPublicData(room), member: previewMember(req.user, client_id) });
});

// 4. Выход из комнаты
router.post("/api/rooms/:id/leave", requireLogin, (req, res) => {
  const { client_id } = req.body || {};
  if (client_id) leaveRoom(req.params.id, client_id, true);
  res.json({ ok: true });
});

// 5. Отправка команды управления (play, pause, seek, rate, buffering)
router.post("/api/rooms/:id/action", requireLogin, (req, res) => {
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
  const joined = joinRoom(room.id, req.user, clientId);

  res.setHeader("Content-Type", "text/event-stream");
  res.setHeader("Cache-Control", "no-cache");
  res.setHeader("Connection", "keep-alive");
  res.setHeader("X-Accel-Buffering", "no"); // nginx отдаёт события сразу, а не пачкой
  res.flushHeaders?.();

  // Отправляем текущее состояние сразу при подключении
  const { streams, ...me } = joined.member;
  res.write(`data: ${JSON.stringify({ event: "init", room: getRoomPublicData(room), member: me })}\n\n`);

  const removeListener = addListener(room.id, msg => {
    res.write(`data: ${msg}\n\n`);
  });

  const pingTimer = setInterval(() => {
    res.write(": ping\n\n");
  }, 20000);

  // Обрыв соединения (res, а не req: у req «close» бывает и раньше, когда запрос просто дочитан)
  res.on("close", () => {
    clearInterval(pingTimer);
    removeListener();
    if (joined && joined.member) {
      leaveRoom(room.id, joined.member.client_id);
    }
  });
});

module.exports = router;

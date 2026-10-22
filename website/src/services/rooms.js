"use strict";

const crypto = require("crypto");

// Хранилище активных комнат совместного просмотра в памяти
// roomId -> Room
const rooms = new Map();

// SSE / WebSocket слушатели для каждой комнаты
// roomId -> Set<res/ws>
const roomListeners = new Map();

// Пустая комната живёт столько, потом удаляется (и та, куда после создания так никто и не вошёл)
const EMPTY_ROOM_TTL_MS = 30 * 60 * 1000;

// client_id устройства — его пропуск (по нему ведущий при общем пароле, «грузится», «вышел»): другим его не показываем,
// в общих данных только отпечаток. Своё устройство приложение узнаёт, посчитав отпечаток своего client_id.
function clientKey(clientId) {
  if (typeof clientId !== "string" || !clientId) return null;
  return crypto.createHash("sha256").update(clientId).digest("hex").slice(0, 16);
}

function publicMember({ streams, buffering_since, client_id, ...m }) {
  return { ...m, key: clientKey(client_id) };
}

// Комната ждёт, пока кто-то грузится (waiting): её время стоит на месте
function getEstimatedPos(state) {
  if (!state.playing || state.waiting) return state.pos;
  const now = Date.now();
  const elapsedSec = ((now - state.anchor_server_ms) / 1000) * state.rate;
  return Math.max(0, state.pos + elapsedSec);
}

function createRoom(hostUser, file = null, controls = "all", hostClientId = null) {
  const id = crypto.randomBytes(6).toString("hex"); // 12 hex chars

  const room = {
    id,
    host_id: hostUser ? hostUser.id : "shared",
    host_name: hostUser ? hostUser.name : "Хост",
    // Устройство создателя: при общем пароле у всех один id "shared", ведущего отличает только оно
    host_client_id: typeof hostClientId === "string" && hostClientId ? hostClientId.slice(0, 64) : null,
    file: file, // { ad_id, idx, name }
    controls: controls === "host" ? "host" : "all",
    state: {
      playing: false,
      pos: 0,
      rate: 1.0,
      anchor_server_ms: Date.now(),
      waiting: false,
      seq: 1
    },
    members: new Map(), // clientId -> { id, name, avatar, buffering, last_seen }
    created_at: Date.now(),
    empty_since: Date.now() // 0 — в комнате кто-то есть
  };

  rooms.set(id, room);
  roomListeners.set(id, new Set());
  return room;
}

function getRoom(roomId) {
  const r = rooms.get(roomId);
  if (!r) return null;
  return r;
}

function getRoomPublicData(room) {
  return {
    id: room.id,
    host_id: room.host_id,
    host_name: room.host_name,
    host_key: clientKey(room.host_client_id),
    file: room.file,
    controls: room.controls,
    state: {
      ...room.state,
      current_pos: getEstimatedPos(room.state),
      server_time: Date.now()
    },
    members: Array.from(room.members.values()).map(publicMember),
    member_count: room.members.size
  };
}

function addListener(roomId, sendFn) {
  let list = roomListeners.get(roomId);
  if (!list) {
    list = new Set();
    roomListeners.set(roomId, list);
  }
  list.add(sendFn);
  return () => {
    list.delete(sendFn);
  };
}

function broadcast(roomId, event, data) {
  const list = roomListeners.get(roomId);
  if (!list || list.size === 0) return;
  const payload = JSON.stringify({ event, data, time: Date.now() });
  for (const send of list) {
    try {
      send(payload);
    } catch {}
  }
}

function memberOf(user, clientId) {
  const cid =
    typeof clientId === "string" && clientId
      ? clientId.slice(0, 64)
      : user
        ? `${user.id}_${crypto.randomBytes(4).toString("hex")}`
        : crypto.randomBytes(8).toString("hex");
  return {
    client_id: cid,
    id: user ? user.id : "guest",
    name: user ? user.name : "Гость",
    avatar: user ? user.avatar : "",
    buffering: false,
    last_seen: Date.now()
  };
}

// Поиск активной комнаты, где уже подключен пользователь или устройство.
// Правило: 1 аккаунт = 1 активная комната (нельзя заходить с другого устройства, пока активно первое).
function findUserActiveRoom(user, clientId = null, targetRoomId = null) {
  const userId = user && user.id ? String(user.id) : null;
  const isAccount = Boolean(userId && userId !== "shared" && userId !== "guest");
  const normClientId = typeof clientId === "string" && clientId ? clientId.trim().slice(0, 64) : null;

  if (!isAccount && !normClientId) return null;

  for (const room of rooms.values()) {
    for (const member of room.members.values()) {
      if (!member) continue;
      if (member.streams !== undefined && member.streams <= 0) continue;

      const memberUserId = member.id ? String(member.id) : null;

      // 1. Проверка по аккаунту: 1 аккаунт = 1 активная комната
      if (isAccount && memberUserId === userId) {
        // Аккаунт уже активен в другой комнате
        if (!targetRoomId || room.id !== targetRoomId) {
          return {
            inRoom: true,
            room,
            member,
            reason: "other_room"
          };
        }
        // В той же комнате, но с другого устройства (или client_id не передан)
        if (!normClientId || member.client_id !== normClientId) {
          return {
            inRoom: true,
            room,
            member,
            reason: "other_device_same_room"
          };
        }
      }

      // 2. Проверка по client_id устройства: одно устройство не может быть в двух разных комнатах
      if (normClientId && member.client_id === normClientId) {
        if (!targetRoomId || room.id !== targetRoomId) {
          return {
            inRoom: true,
            room,
            member,
            reason: "same_device_other_room"
          };
        }
      }
    }
  }

  return null;
}

// Участник комнаты — это открытый поток событий (SSE): закрылся последний поток устройства — его нет в комнате.
// Так в списке не остаются «призраки» тех, кто вышел или у кого оборвалась связь.
function joinRoom(roomId, user, clientId = null) {
  const room = getRoom(roomId);
  if (!room) return null;

  const active = findUserActiveRoom(user, clientId, roomId);
  if (active) {
    const error =
      active.reason === "other_device_same_room"
        ? "Этот аккаунт уже подключен к комнате с другого устройства."
        : active.reason === "same_device_other_room"
          ? "Это устройство уже подключено к другой комнате."
          : "Этот аккаунт уже находится в активной комнате.";
    return { ok: false, error, room: null, member: null };
  }

  const fresh = memberOf(user, clientId);
  const existing = room.members.get(fresh.client_id);
  const member = existing ? Object.assign(existing, { last_seen: fresh.last_seen }) : { ...fresh, streams: 0 };
  member.streams++;
  room.members.set(member.client_id, member);
  room.empty_since = 0;
  if (!existing) {
    broadcast(roomId, "member_joined", { member: publicMember(member), total: room.members.size });
  }
  return { ok: true, room, member };
}

// Кто я в этой комнате (для ответа на «войти»): в список попадает только с открытием потока событий
function previewMember(user, clientId) {
  return memberOf(user, clientId);
}

// Комната, где никого не осталось, встаёт на паузу: иначе её время «идёт» в пустоте
// Дольше этого чужая загрузка комнату не держит: сломанный плеер не останавливает всех навсегда
const BUFFERING_LIMIT_MS = 30 * 1000;

// Кто-то грузится — комната ждёт (время стоит), все готовы — идёт дальше с того же места. true — состояние изменилось
function updateWaiting(room) {
  const now = Date.now();
  const someone = Array.from(room.members.values()).some(
    m => m.buffering && now - (m.buffering_since || now) < BUFFERING_LIMIT_MS
  );
  const s = room.state;
  if (someone === Boolean(s.waiting)) return false;
  s.pos = getEstimatedPos(s);
  s.waiting = someone;
  s.anchor_server_ms = now;
  s.seq++;
  return true;
}

function pauseIfEmpty(room) {
  if (room.members.size > 0 || !room.state.playing) return;
  room.state.pos = getEstimatedPos(room.state);
  room.state.waiting = false;
  room.state.playing = false;
  room.state.anchor_server_ms = Date.now();
  room.state.seq++;
}

// Ведущий ушёл (закрыл приложение, пропала связь) и не вернулся за минуту: ведущим становится тот, кто в комнате
// дольше всех — иначе комнатой никто не может управлять. Минута — чтобы короткий обрыв связи ведущего не сменил.
const HOST_GRACE_MS = 60 * 1000;

function hostPresent(room) {
  for (const m of room.members.values()) {
    if (isRoomHost(room, { id: m.id }, m.client_id)) return true;
  }
  return false;
}

// true — ведущий сменился (и все об этом узнали)
function promoteIfHostGone(room) {
  if (rooms.get(room.id) !== room || room.members.size === 0 || hostPresent(room)) return false;
  const next = room.members.values().next().value; // Map хранит порядок входа
  room.host_id = next.id;
  room.host_name = next.name;
  room.host_client_id = next.client_id;
  broadcast(room.id, "state_changed", getRoomPublicData(room));
  return true;
}

function isRoomHost(room, user, clientId) {
  if (!user || user.id !== room.host_id) return false;
  // Общий пароль: один id на всех, ведущий — устройство, создавшее комнату
  if (room.host_id === "shared" && room.host_client_id) return clientId === room.host_client_id;
  return true;
}

// force: участник нажал «Выйти» (все его потоки уже не в счёт); иначе закрылся один поток
function leaveRoom(roomId, clientId, force = false) {
  const room = getRoom(roomId);
  if (!room) return;

  const member = room.members.get(clientId);
  if (member) {
    member.streams = force ? 0 : Math.max(0, (member.streams || 1) - 1);
    if (member.streams === 0) {
      room.members.delete(clientId);
      broadcast(roomId, "member_left", { key: clientKey(clientId), total: room.members.size });
      if (room.members.size === 0) room.empty_since = Date.now();
      else if (!hostPresent(room)) setTimeout(() => promoteIfHostGone(room), HOST_GRACE_MS).unref?.();
      pauseIfEmpty(room);
      // Ушёл тот, кого ждали: комната идёт дальше
      if (updateWaiting(room)) broadcast(roomId, "state_changed", getRoomPublicData(room));
    }
  }
}

// Удаляет комнаты, пустые дольше EMPTY_ROOM_TTL_MS (в том числе созданные, но так и не открытые)
function sweepRooms(now = Date.now()) {
  for (const [id, room] of rooms) {
    if (room.members.size === 0 && room.empty_since && now - room.empty_since > EMPTY_ROOM_TTL_MS) {
      rooms.delete(id);
      roomListeners.delete(id);
    }
  }
}
setInterval(sweepRooms, 5 * 60 * 1000).unref?.();

function handleRoomAction(roomId, user, action) {
  const room = getRoom(roomId);
  if (!room) return { ok: false, error: "Комната не найдена." };

  // «У меня грузится / готово» — от любого участника, без прав на управление
  if (action.type === "buffering") {
    const m = action.client_id ? room.members.get(action.client_id) : null;
    if (m) {
      const b = Boolean(action.buffering);
      if (Boolean(m.buffering) !== b) {
        m.buffering = b;
        m.buffering_since = b ? Date.now() : 0;
        if (b) {
          const t = setTimeout(() => {
            if (rooms.get(roomId) === room && updateWaiting(room)) broadcast(roomId, "state_changed", getRoomPublicData(room));
          }, BUFFERING_LIMIT_MS + 100);
          t.unref?.();
        }
        updateWaiting(room);
        broadcast(roomId, "state_changed", getRoomPublicData(room));
      }
    }
    return { ok: true, room: getRoomPublicData(room) };
  }

  const isHost = isRoomHost(room, user, action.client_id);
  // Фильм комнаты выбирает только ведущий; пауза и перемотка — по настройке комнаты
  if (action.type === "change_file" && !isHost) {
    return { ok: false, error: "Фильм выбирает только тот, кто создал комнату." };
  }
  if (room.controls === "host" && !isHost) {
    return { ok: false, error: "Только ведущий может управлять воспроизведением." };
  }
  // Пауза, продолжение и перемотка двигают всех: только ведущий (пауза гостя остаётся у него на компьютере)
  if (!isHost && ["play", "pause", "seek", "rate"].includes(action.type)) {
    return {
      ok: false,
      error: action.type === "seek" || action.type === "rate"
        ? "Перематывает только тот, кто создал комнату."
        : "Паузу ставит и снимает только тот, кто создал комнату."
    };
  }

  const state = room.state;
  const now = Date.now();

  switch (action.type) {
    case "play": {
      const pos = typeof action.pos === "number" ? Math.max(0, action.pos) : getEstimatedPos(state);
      state.pos = pos;
      state.playing = true;
      state.anchor_server_ms = now;
      state.seq++;
      break;
    }
    case "pause": {
      state.pos = typeof action.pos === "number" ? Math.max(0, action.pos) : getEstimatedPos(state);
      state.playing = false;
      state.anchor_server_ms = now;
      state.seq++;
      break;
    }
    case "seek": {
      state.pos = Math.max(0, Number(action.pos) || 0);
      state.anchor_server_ms = now;
      state.seq++;
      break;
    }
    case "rate": {
      state.pos = getEstimatedPos(state);
      state.rate = Math.max(0.25, Math.min(3.0, Number(action.rate) || 1.0));
      state.anchor_server_ms = now;
      state.seq++;
      break;
    }
    case "change_file": {
      room.file = action.file; // { ad_id, idx, name }
      state.pos = 0;
      state.playing = false;
      // Новый фильм: прежние загрузки не в счёт
      state.waiting = false;
      for (const m of room.members.values()) { m.buffering = false; m.buffering_since = 0; }
      state.anchor_server_ms = now;
      state.seq++;
      break;
    }
    default:
      return { ok: false, error: `Неизвестное действие: ${action.type}` };
  }

  const roomData = getRoomPublicData(room);
  broadcast(roomId, "state_changed", roomData);
  return { ok: true, room: roomData };
}

module.exports = {
  createRoom,
  getRoom,
  getRoomPublicData,
  addListener,
  joinRoom,
  findUserActiveRoom,
  previewMember,
  publicMember,
  clientKey,
  isRoomHost,
  leaveRoom,
  sweepRooms,
  promoteIfHostGone,
  handleRoomAction
};

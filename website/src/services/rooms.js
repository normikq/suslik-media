"use strict";

const crypto = require("crypto");
const variantOffsets = require("./variant-offsets");

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

function publicMember({ streams, buffering_since, client_id, timeline_key, ...m }) {
  return { ...m, key: clientKey(client_id) };
}

// Подстройки времени между раздачей ведущего и раздачами остальных (сохранённые гостями, services/variant-offsets.js):
// [{ from: ключ ведущего, to: ключ участника, offset_ms }] — место на to = место на from + offset_ms
function variantOffsetsOf(room) {
  if (!room.file) return [];
  let hostKey = room.file.key;
  for (const m of room.members.values()) {
    if (isRoomHost(room, { id: m.id }, m.client_id) && m.variant_key) { hostKey = m.variant_key; break; }
  }
  const out = [];
  const seen = new Set([hostKey]);
  for (const m of room.members.values()) {
    const k = m.variant_key;
    if (!k || seen.has(k)) continue;
    seen.add(k);
    const ms = variantOffsets.get(hostKey, k);
    if (ms != null) out.push({ from: hostKey, to: k, offset_ms: ms });
  }
  return out;
}

// Комната ждёт, пока кто-то грузится (waiting): её время стоит на месте
function getEstimatedPos(state) {
  if (!state.playing || state.waiting) return state.pos;
  const now = Date.now();
  const elapsedSec = ((now - state.anchor_server_ms) / 1000) * state.rate;
  return Math.max(0, state.pos + elapsedSec);
}

function createRoom(hostUser, file = null, controls = "all", hostClientId = null, fixedId = null) {
  const id = fixedId || crypto.randomBytes(6).toString("hex"); // 12 hex chars
  const normHostClientId = typeof hostClientId === "string" && hostClientId.trim() ? hostClientId.trim().slice(0, 64) : null;

  const room = {
    id,
    host_id: hostUser ? hostUser.id : "shared",
    host_name: hostUser ? hostUser.name : "Хост",
    // Устройство создателя: при общем пароле у всех один id "shared", ведущего отличает только оно
    host_client_id: normHostClientId,
    file: cleanFile(file), // { key, name, ad_id }: из тела запроса — та же проверка, что у change_file
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
    empty_since: Date.now(), // 0 — в комнате кто-то есть
    // Комната голосового канала Discord (Activity): id канала, кто смотрит Activity (не участники плеера),
    // предложенные фильмы и голосование
    channel_id: null,
    watchers: new Map(), // user.id -> { id, name, avatar, streams, seen_until, expire } (addWatcher / touchWatcher)
    candidates: [], // [{ key, name, by, by_name, at }]
    poll: null, // { ends_at, votes: Map(user.id -> key), timer }
    spin: null, // «Рулетка»: { key, name, started_at, ends_at, timer } — барабан крутится у всех, потом победитель включается
    speakers: new Map() // кто говорит в голосовом канале (приглушение): user.id -> { until, timer } (reportSpeaking)
  };

  rooms.set(id, room);
  roomListeners.set(id, new Set());

  const hostUserId = hostUser && hostUser.id ? String(hostUser.id) : null;
  const isAccount = Boolean(hostUserId && hostUserId !== "shared" && hostUserId !== "guest");
  if (isAccount && normHostClientId) {
    setAccountActiveDevice(hostUserId, normHostClientId, id);
  }

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
    member_count: room.members.size,
    variant_offsets: variantOffsetsOf(room),
    // Только у комнаты голосового канала (Activity); приложения эти поля не читают
    ...(room.channel_id ? { channel: true, ...activityPublicData(room) } : {})
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
  const normClientId = typeof clientId === "string" && clientId.trim() ? clientId.trim().slice(0, 64) : null;
  const cid =
    normClientId ||
    (user
      ? `${user.id}_${crypto.randomBytes(4).toString("hex")}`
      : crypto.randomBytes(8).toString("hex"));
  return {
    client_id: cid,
    id: user ? user.id : "guest",
    name: user ? user.name : "Гость",
    avatar: user ? user.avatar : "",
    buffering: false,
    last_seen: Date.now()
  };
}

// Активное выбранное устройство аккаунта (userId -> { clientId, roomId, at })
// Запоминается при явном действии (POST /rooms или POST /join) или при входе в комнату.
// Защищает от гонки: когда старое устройство (ПК) вытеснено и получает обрыв SSE,
// оно авто-переподключается через 1 с, пока новое устройство (телефон) ещё устанавливает TLS (2-5 с).
// Все попытки старого устройства войти получают 403, побеждает выбранное новое устройство.
const accountActiveDevices = new Map();
const PENDING_DEVICE_TTL_MS = 30 * 1000;

function isDeviceInAnyRoom(clientId) {
  if (!clientId) return false;
  for (const room of rooms.values()) {
    if (room.members.has(clientId)) return true;
  }
  return false;
}

function setAccountActiveDevice(userId, clientId, roomId = null) {
  if (!userId || userId === "shared" || userId === "guest") return;
  const cid = typeof clientId === "string" && clientId.trim() ? clientId.trim().slice(0, 64) : null;
  if (!cid) {
    accountActiveDevices.delete(String(userId));
    return;
  }
  accountActiveDevices.set(String(userId), {
    clientId: cid,
    roomId: roomId || null,
    at: Date.now()
  });
}

function getAccountActiveDevice(userId) {
  if (!userId || userId === "shared" || userId === "guest") return null;
  return accountActiveDevices.get(String(userId)) || null;
}

function clearAccountActiveDevice(userId, clientId = null) {
  if (!userId || userId === "shared" || userId === "guest") return;
  const uid = String(userId);
  if (!clientId) {
    accountActiveDevices.delete(uid);
    return;
  }
  const cur = accountActiveDevices.get(uid);
  if (cur && cur.clientId === clientId) {
    accountActiveDevices.delete(uid);
  }
}

// Выбранное устройство отключилось без «Выйти» (обрыв связи): отсчёт льготы начинается заново
function touchAccountActiveDevice(userId, clientId) {
  if (!userId || userId === "shared" || userId === "guest" || !clientId) return;
  const cur = accountActiveDevices.get(String(userId));
  if (cur && cur.clientId === clientId) cur.at = Date.now();
}

// Поиск активной комнаты, где уже подключен пользователь с другого устройства.
// Правило: 1 аккаунт = 1 активная комната (нельзя заходить с другого устройства, пока активно первое).
// То же устройство (тот же client_id: приложения хранят его между запусками) — не помеха: оно перешло в другую комнату
// или вернулось после перезапуска, пока сервер ещё не заметил обрыв старого потока. Прежнее место снимает moveDevice.
function findUserActiveRoom(user, clientId = null, targetRoomId = null) {
  const userId = user && user.id ? String(user.id) : null;
  const isAccount = Boolean(userId && userId !== "shared" && userId !== "guest");
  const normClientId = typeof clientId === "string" && clientId.trim() ? clientId.trim().slice(0, 64) : null;

  if (!isAccount && !normClientId) return null;

  // 1. Проверка выбранного активного устройства аккаунта:
  // Если пользователь явно переключился на другое устройство (POST /join или POST /rooms),
  // старое устройство блокируется даже в промежутке до того, как новое открыло поток /events.
  if (isAccount) {
    const activeDev = accountActiveDevices.get(userId);
    if (activeDev && activeDev.clientId) {
      const isConnected = isDeviceInAnyRoom(activeDev.clientId);
      const isPendingValid = Date.now() - activeDev.at < PENDING_DEVICE_TTL_MS;
      if (isConnected || isPendingValid) {
        if (!normClientId || normClientId !== activeDev.clientId) {
          const inSame = Boolean(targetRoomId && activeDev.roomId && targetRoomId === activeDev.roomId);
          return {
            inRoom: true,
            room: activeDev.roomId ? rooms.get(activeDev.roomId) || null : null,
            member: null,
            reason: inSame ? "other_device_same_room" : "other_room"
          };
        }
      } else {
        accountActiveDevices.delete(userId);
      }
    }
  }

  // 2. Проверка по текущим подключенным участникам комнат:
  for (const room of rooms.values()) {
    for (const member of room.members.values()) {
      if (!member) continue;
      if (member.streams !== undefined && member.streams <= 0) continue;
      if (normClientId && member.client_id === normClientId) continue;

      const memberUserId = member.id ? String(member.id) : null;

      // Проверка по аккаунту: 1 аккаунт = 1 активная комната
      if (isAccount && memberUserId === userId) {
        // Если текущее устройство — это явно выбранное активное устройство аккаунта,
        // оно имеет приоритет и вытеснит старые устройства в joinRoom
        const activeDev = accountActiveDevices.get(userId);
        if (activeDev && normClientId && activeDev.clientId === normClientId) {
          continue;
        }

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

    }
  }

  return null;
}

// Одно устройство — одна комната: вошло в другую — из прежних оно вышло (старый поток мог ещё не оборваться)
function moveDevice(clientId, targetRoomId) {
  const cid = typeof clientId === "string" && clientId ? clientId.trim().slice(0, 64) : null;
  if (!cid) return;
  for (const room of Array.from(rooms.values())) {
    if (room.id !== targetRoomId && room.members.has(cid)) leaveRoom(room.id, cid, true);
  }
}

// SSE слушатели конкретных устройств участников: roomId:clientId -> Set<res>
// Чтобы при вытеснении старого устройства (вход с телефона, обрыв) сразу закрыть его зависший сокет
const memberStreams = new Map();

function registerMemberStream(roomId, clientId, res) {
  if (!roomId || !clientId || !res) return;
  const key = `${roomId}:${clientId}`;
  let set = memberStreams.get(key);
  if (!set) {
    set = new Set();
    memberStreams.set(key, set);
  }
  set.add(res);
}

function unregisterMemberStream(roomId, clientId, res) {
  const key = `${roomId}:${clientId}`;
  const set = memberStreams.get(key);
  if (!set) return;
  set.delete(res);
  if (set.size === 0) memberStreams.delete(key);
}

function closeMemberStreams(roomId, clientId) {
  const key = `${roomId}:${clientId}`;
  const set = memberStreams.get(key);
  if (!set || set.size === 0) return;
  memberStreams.delete(key);
  for (const res of set) {
    try {
      if (!res.destroyed && !res.writableEnded) {
        res.end();
      }
      res.destroy?.();
    } catch {}
  }
}

// Один аккаунт — одно активное устройство и комната: если вошёл с нового устройства (или в другую комнату),
// прежнее устройство этого аккаунта освобождает место (старый поток мог оборваться, выключился свет, сел аккумулятор).
// То же устройство в той же комнате — это переподключение, его не трогаем.
function moveAccount(user, newClientId, targetRoomId = null) {
  const userId = user && user.id ? String(user.id) : null;
  const isAccount = Boolean(userId && userId !== "shared" && userId !== "guest");
  if (!isAccount) return;
  const normNewClientId = typeof newClientId === "string" && newClientId.trim() ? newClientId.trim().slice(0, 64) : null;

  if (normNewClientId) {
    setAccountActiveDevice(userId, normNewClientId, targetRoomId);
  }

  for (const room of Array.from(rooms.values())) {
    for (const member of Array.from(room.members.values())) {
      if (!member || String(member.id) !== userId) continue;
      // Тот же client_id в той же целевой комнате — это повторный вход того же устройства
      if (normNewClientId && member.client_id === normNewClientId && room.id === targetRoomId) continue;

      const oldClientId = member.client_id;
      leaveRoom(room.id, oldClientId, true);
      closeMemberStreams(room.id, oldClientId);
    }
  }
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
        : "Этот аккаунт уже находится в активной комнате.";
    return { ok: false, error, code: 403, room: null, member: null };
  }
  moveDevice(clientId, roomId);

  const userId = user && user.id ? String(user.id) : null;
  const isAccount = Boolean(userId && userId !== "shared" && userId !== "guest");

  // Если у аккаунта оставались другие устройства в комнатах — вытесняем их
  if (isAccount) {
    moveAccount(user, clientId, roomId);
  }

  const fresh = memberOf(user, clientId);
  const existing = room.members.get(fresh.client_id);
  const member = existing ? Object.assign(existing, { last_seen: fresh.last_seen }) : { ...fresh, streams: 0 };
  member.streams++;
  room.members.set(member.client_id, member);
  room.empty_since = 0;

  if (isAccount && fresh.client_id) {
    setAccountActiveDevice(userId, fresh.client_id, roomId);
  }

  // Если в комнату вошёл ведущий (хост аккаунта) — обновляем host_client_id на его текущее подключенное устройство
  let hostChanged = false;
  if (room.host_id && room.host_id !== "shared" && String(room.host_id) === userId) {
    if (room.host_client_id !== fresh.client_id) {
      room.host_client_id = fresh.client_id;
      hostChanged = true;
    }
  }

  if (!existing) {
    broadcast(roomId, "member_joined", { member: publicMember(member), total: room.members.size });
  }
  if (hostChanged) {
    broadcast(roomId, "state_changed", getRoomPublicData(room));
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
  // В Activity ведущий может быть только «у пульта» (без плеера): он всё равно на месте
  return room.watchers.has(room.host_id);
}

// true — ведущий сменился (и все об этом узнали)
function promoteIfHostGone(room) {
  if (rooms.get(room.id) !== room || (room.members.size === 0 && room.watchers.size === 0) || hostPresent(room)) return false;
  // Map хранит порядок входа: сначала тот, кто в комнате с плеером, иначе первый у пульта Activity
  const next = room.members.size > 0 ? room.members.values().next().value : room.watchers.values().next().value;
  room.host_id = next.id;
  room.host_name = next.name;
  room.host_client_id = next.client_id || null;
  broadcast(room.id, "state_changed", getRoomPublicData(room));
  return true;
}

function isRoomHost(room, user, clientId) {
  if (!user || user.id !== room.host_id) return false;
  // Общий пароль: один id на всех, ведущий — только устройство, создавшее комнату. Комната без него (создана без
  // client_id) ведущего не имеет: иначе ведущими были бы все, кто вошёл по общему паролю
  if (room.host_id === "shared") return Boolean(room.host_client_id) && clientId === room.host_client_id;
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
      closeMemberStreams(roomId, clientId);
      room.members.delete(clientId);
      if (member.id) {
        // Явный выход («Выйти», переход в другую комнату) снимает выбор устройства. Обрыв потока — нет: пока устройство
        // переподключается, ещё PENDING_DEVICE_TTL_MS его место не займёт старое, повторяющее /events
        if (force) clearAccountActiveDevice(member.id, clientId);
        else touchAccountActiveDevice(member.id, clientId);
      }
      // При общем пароле ведущего отличает только host_client_id, и joinRoom его не вернёт: не обнуляем
      if (room.host_id !== "shared" && room.host_client_id === clientId) {
        room.host_client_id = null;
      }
      broadcast(roomId, "member_left", { key: clientKey(clientId), total: room.members.size });
      if (room.members.size === 0 && room.watchers.size === 0) room.empty_since = Date.now();
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
    if (room.members.size === 0 && room.watchers.size === 0 && room.empty_since && now - room.empty_since > EMPTY_ROOM_TTL_MS) {
      rooms.delete(id);
      roomListeners.delete(id);
      if (room.poll && room.poll.timer) clearTimeout(room.poll.timer);
      if (room.spin && room.spin.timer) clearTimeout(room.spin.timer);
      if (room.channel_id && channelRooms.get(room.channel_id) === id) channelRooms.delete(room.channel_id);
    }
  }
}
setInterval(sweepRooms, 5 * 60 * 1000).unref?.();

const VARIANT_KEY_RE = /^\d{1,20}\|.{1,900}$/s;

// Раздачи одного фильма разной длины: лишнее обычно в начале (заставка релиз-группы, логотипы), поэтому место на
// своей раздаче = место ведущего + (своя длина − длина ведущего). Меньше 1,5 с — хвост кадров, не сдвиг; больше 2 мин —
// другой монтаж, разницей длин его не выровнять. Те же числа — timelineShiftMs в android/core и desktop-qt/core.
const SHIFT_MIN_MS = 1500;
const SHIFT_MAX_MS = 120 * 1000;
function timelineShiftMs(myDurationMs, hostDurationMs) {
  if (!(myDurationMs > 0) || !(hostDurationMs > 0)) return 0;
  const d = Math.round(myDurationMs - hostDurationMs);
  return Math.abs(d) >= SHIFT_MIN_MS && Math.abs(d) <= SHIFT_MAX_MS ? d : 0;
}

// Длина раздачи из запроса, мс: от 1 с до 24 ч, иначе null
function durationOf(raw) {
  const d = Math.round(Number(raw));
  return Number.isFinite(d) && d >= 1000 && d <= 24 * 3600 * 1000 ? d : null;
}

// Ключ варианта файла комнаты: формы "<adId>|<путь>"; если у файла есть список пресетов — только из него (или сам файл).
function variantKeyFor(file, raw) {
  const key = typeof raw === "string" ? raw.trim() : "";
  if (!VARIANT_KEY_RE.test(key)) return null;
  if (file && Array.isArray(file.variants) && file.variants.length) {
    return key === file.key || file.variants.some(v => v.key === key) ? key : null;
  }
  return key;
}

// Участник, от имени которого пришло действие: аккаунт управляет только собой (client_id гостя наружу не отдаётся).
function actingMember(room, user, action) {
  const m = action.client_id ? room.members.get(action.client_id) : null;
  if (!m) return { member: null };
  const isAccount = user && user.id && user.id !== "shared" && user.id !== "guest";
  if (isAccount && m.id && String(m.id) !== String(user.id)) {
    return { member: null, error: "Недостаточно прав для управления состоянием другого участника." };
  }
  return { member: m };
}

function handleRoomAction(roomId, user, action) {
  const room = getRoom(roomId);
  if (!room) return { ok: false, error: "Комната не найдена." };

  // «У меня грузится / готово» — от любого участника, без прав на управление
  if (action.type === "buffering") {
    const { member: m, error } = actingMember(room, user, action);
    if (error) return { ok: false, error };
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

  // Смена пресета качества участником (независимый выбор потока). variant — описание своей раздачи, которой нет в
  // списке ведущего (гость добавил её сам): она дописывается в список комнаты. duration_ms — длина раздачи участника:
  // по ней гости на других раздачах сдвигают своё время (timelineShiftMs в клиентах)
  if (action.type === "variant_change") {
    const { member: m, error } = actingMember(room, user, action);
    if (error) return { ok: false, error };
    if (m) {
      let changed = false;
      let vk = variantKeyFor(room.file, action.variant_key);
      if (!vk && room.file && Array.isArray(room.file.variants) && action.variant && typeof action.variant === "object") {
        const own = cleanVariant({ ...action.variant, key: action.variant_key });
        if (own && VARIANT_KEY_RE.test(own.key) && room.file.variants.length < MAX_VARIANTS) {
          room.file.variants = [...room.file.variants, own];
          vk = own.key;
          changed = true;
        }
      }
      if (!vk) return { ok: false, error: "Такого варианта у этого фильма нет." };
      if (m.variant_key !== vk) {
        m.variant_key = vk;
        changed = true;
      }
      const d = durationOf(action.duration_ms);
      if (d && (m.duration_ms !== d || m.timeline_key !== vk)) {
        // Ведущий перешёл на другую раздачу: время комнаты переезжает на её шкалу, и все, ведущий тоже, перематываются
        // одной командой. Сдвиг — сохранённая подстройка пары (variant_offset), иначе разница длин (лишнее — в начале)
        if (m.timeline_key && m.timeline_key !== vk && m.duration_ms && room.file && isRoomHost(room, user, action.client_id)) {
          const stored = variantOffsets.get(m.timeline_key, vk);
          const shift = stored != null ? stored : timelineShiftMs(d, m.duration_ms);
          if (shift !== 0) {
            room.state.pos = Math.max(0, getEstimatedPos(room.state) + shift / 1000);
            room.state.anchor_server_ms = Date.now();
            room.state.seq++;
          }
        }
        m.duration_ms = d;
        m.timeline_key = vk;
        changed = true;
      }
      if (changed) broadcast(roomId, "state_changed", getRoomPublicData(room));
    }
    return { ok: true, room: getRoomPublicData(room) };
  }

  // Гость подстроил время своей раздачи к раздаче ведущего: offset_ms — место на variant_key минус место на
  // host_variant_key. Запоминается для пары раздач (services/variant-offsets.js) и включается у всех
  if (action.type === "variant_offset") {
    const { member: m, error } = actingMember(room, user, action);
    if (error) return { ok: false, error };
    if (!m || !room.file) return { ok: false, error: "Сейчас в комнате нет фильма." };
    const from = variantKeyFor(room.file, action.host_variant_key);
    const to = variantKeyFor(room.file, action.variant_key);
    if (!from || !to || from === to) return { ok: false, error: "Такого варианта у этого фильма нет." };
    if (variantOffsets.set(from, to, action.offset_ms) == null) return { ok: false, error: "Неверная подстройка." };
    broadcast(roomId, "state_changed", getRoomPublicData(room));
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
      // Файл приходит из тела запроса: та же проверка, что у голосования и рулетки. null — фильм закрыт
      const file = action.file == null ? null : cleanFile(action.file);
      if (action.file != null && !file) return { ok: false, error: "Неверный формат файла." };
      room.file = file; // { key, name, ad_id }
      state.pos = 0;
      state.playing = false;
      // Новый фильм: прежние загрузки не в счёт
      state.waiting = false;
      // Ключ качества прежнего фильма никому не подходит: у остальных он сбрасывается, клиент пришлёт свой заново
      for (const m of room.members.values()) { m.buffering = false; m.buffering_since = 0; m.variant_key = null; m.duration_ms = null; m.timeline_key = null; }
      const actor = action.client_id ? room.members.get(action.client_id) : null;
      if (actor) {
        if (!file) {
          actor.variant_key = null;
        } else if (variantKeyFor(file, action.variant_key)) {
          actor.variant_key = variantKeyFor(file, action.variant_key);
        } else if (actor.variant_key && Array.isArray(file.variants)) {
          const exact = file.variants.find(v => v.key === actor.variant_key);
          if (exact) {
            actor.variant_key = exact.key;
          } else {
            const prevMag = String(actor.variant_key).split("|")[0];
            const byMag = prevMag ? file.variants.find(v => String(v.key || "").split("|")[0] === prevMag) : null;
            actor.variant_key = byMag ? byMag.key : file.key;
          }
        } else {
          actor.variant_key = file.key;
        }
      }
      state.anchor_server_ms = now;
      state.seq++;
      break;
    }
    default:
      return { ok: false, error: `Неизвестное действие: ${action.type}` };
  }

  const roomData = getRoomPublicData(room);
  broadcast(roomId, "state_changed", roomData);
  if (action.type === "change_file" && room.file) {
    broadcast(roomId, "refresh_library", { file: room.file });
  }
  return { ok: true, room: roomData };
}


// ---- Комната голосового канала Discord (Activity) ----
// Одна комната на голосовой канал: первый, кто открыл Activity, — ведущий. id комнаты — шесть цифр: это и есть код
// для ТВ и приложений («842-190»: приложения при входе убирают всё, кроме букв и цифр). Тот, кто смотрит Activity,
// не участник плеера (иначе его же ПК не вошёл бы: «один аккаунт — одна комната»), а «у пульта» (watchers).

const channelRooms = new Map(); // channel_id -> room id
const POLL_MS = 45 * 1000;
const MAX_CANDIDATES = 8;
// Все, кто у пульта, проголосовали: ждать конца 45 секунд незачем — итог через несколько секунд (можно ещё передумать)
const ALL_VOTED_MS = 5 * 1000;
// Барабан «Рулетки» крутится столько у всех, потом победитель включается в комнате.
// Ведущий может выбрать длительность сам: от 2 до 30 секунд
const SPIN_MS = 4000;
const SPIN_MIN_S = 2, SPIN_MAX_S = 30;
/** Длительность барабана в мс из выбора ведущего (секунды); не число — по умолчанию. */
function spinMs(seconds) {
  const s = Number(seconds);
  if (seconds == null || seconds === "" || !Number.isFinite(s)) return SPIN_MS;
  return Math.round(Math.min(SPIN_MAX_S, Math.max(SPIN_MIN_S, s)) * 1000);
}

function shortRoomId() {
  for (let i = 0; i < 50; i++) {
    const id = String(crypto.randomInt(100000, 1000000));
    if (!rooms.has(id)) return id;
  }
  return crypto.randomBytes(6).toString("hex");
}

function getOrCreateChannelRoom(channelId, user) {
  const ch = String(channelId || "");
  if (!/^\d{5,25}$/.test(ch) || !user || !user.id) return null;
  const existing = rooms.get(channelRooms.get(ch));
  if (existing) return existing;
  const room = createRoom(user, null, "all", null, shortRoomId());
  room.channel_id = ch;
  channelRooms.set(ch, room.id);
  return room;
}

function activityPublicData(room) {
  const poll = room.poll;
  let tally = null;
  let votes = null;
  if (poll) {
    tally = {};
    votes = {};
    for (const [uid, key] of poll.votes) {
      tally[key] = (tally[key] || 0) + 1;
      votes[uid] = key;
    }
  }
  return {
    watchers: Array.from(room.watchers.values()).map(({ id, name, avatar }) => ({ id, name, avatar })),
    candidates: room.candidates.map(c => ({ ...c })),
    poll: poll ? { ends_at: poll.ends_at, tally, votes, all_voted: allVoted(room) } : null,
    spin: room.spin ? { key: room.spin.key, name: room.spin.name, started_at: room.spin.started_at, ends_at: room.spin.ends_at } : null,
    voice: voicePublicData(room)
  };
}

// ---- Приглушение: кто говорит в голосовом канале (docs/DISCORD-ACTIVITY.md, «Приглушение») ----
// Говорящих видит клиент Discord у каждого, кто открыл Activity (SPEAKING_START / SPEAKING_STOP); все пульты канала
// шлют одно и то же, сервер сводит в один список. Говорящий числится ещё SPEAK_LINGER_MS после конца фразы
// (гистерезис: паузы между словами не дёргают громкость). Пока человек говорит, пульт повторяет «говорит» раз в 5 с;
// без повторов (пульт закрыли посреди фразы, «замолчал» потерялся) запись живёт SPEAK_TTL_MS. Плеерам уходит только смена списка:
// voice_changed { active, speakers: [user.id] } — каждый плеер сам глушит, если говорит не он.
// Кто и когда говорил, не хранится и не пишется в журнал.
const SPEAK_LINGER_MS = 1500;
const SPEAK_TTL_MS = 12 * 1000;
const MAX_SPEAKERS = 50;

function voicePublicData(room) {
  const speakers = room.speakers ? Array.from(room.speakers.keys()) : [];
  return { active: speakers.length > 0, speakers };
}

function removeSpeaker(room, id, entry) {
  if (room.speakers.get(id) !== entry) return;
  room.speakers.delete(id);
  if (rooms.get(room.id) === room) broadcast(room.id, "voice_changed", voicePublicData(room));
}

/**
 * Событие речи от пульта комнаты голосового канала. userId — участник канала (u_<discord id>), speaking — начал
 * или закончил говорить. Сообщить может только тот, кто сейчас у пульта этой комнаты.
 */
function reportSpeaking(roomId, reporter, userId, speaking, now = Date.now()) {
  const room = rooms.get(roomId);
  if (!room || !room.channel_id) return { ok: false, status: 404, error: "Комната не найдена." };
  if (!reporter || !room.watchers.has(reporter.id)) return { ok: false, status: 403, error: "Только у пульта комнаты." };
  const id = String(userId || "");
  if (!/^u_\d{5,25}$/.test(id)) return { ok: false, status: 400, error: "Нет участника канала." };
  const old = room.speakers.get(id);
  if (!old && !speaking) return { ok: true, changed: false };
  if (!old && room.speakers.size >= MAX_SPEAKERS) return { ok: true, changed: false };
  if (old) clearTimeout(old.timer);
  const entry = { until: now + (speaking ? SPEAK_TTL_MS : SPEAK_LINGER_MS), timer: null };
  entry.timer = setTimeout(() => removeSpeaker(room, id, entry), entry.until - now);
  entry.timer.unref?.();
  room.speakers.set(id, entry);
  if (!old) broadcast(room.id, "voice_changed", voicePublicData(room));
  return { ok: true, changed: !old };
}

/** Снять говорящего сразу, без задержки (бот сказал: его нет в голосовом канале комнаты). */
function dropSpeaker(roomId, userId) {
  const room = rooms.get(roomId);
  const entry = room && room.speakers ? room.speakers.get(String(userId || "")) : null;
  if (!entry) return false;
  clearTimeout(entry.timer);
  removeSpeaker(room, String(userId), entry);
  return true;
}

/** Человек изменил свою настройку (приглушение): его плеерам в комнатах — settings_changed, чужие пропускают. */
function notifyUserSettings(userId, data) {
  const uid = String(userId || "");
  if (!uid) return;
  for (const room of rooms.values()) {
    let here = room.watchers.has(uid);
    if (!here) for (const m of room.members.values()) if (m && String(m.id) === uid) { here = true; break; }
    if (here) broadcast(room.id, "settings_changed", { for_user: uid, ...data });
  }
}

// Проголосовали все, кто сейчас у пульта (голосуют только из Activity)
function allVoted(room) {
  if (!room.poll || room.watchers.size === 0) return false;
  for (const id of room.watchers.keys()) if (!room.poll.votes.has(id)) return false;
  return true;
}

function activityChanged(room) {
  broadcast(room.id, "activity_changed", { ...activityPublicData(room), host_id: room.host_id, host_name: room.host_name });
}

// Открыт поток событий Activity: человек «у пульта». Возвращает функцию «ушёл» (можно звать сколько угодно раз)
function addWatcher(room, user) {
  const w = watcherOf(room, user);
  const isNew = w.fresh;
  w.fresh = false;
  w.streams++;
  if (isNew) activityChanged(room);
  let gone = false;
  return () => {
    if (gone) return;
    gone = true;
    w.streams--;
    dropWatcherIfGone(room, w);
  };
}

// Страница на опросе (прокси Discord держит поток пачкой или поток всё время рвётся): каждый GET комнаты держит
// человека «у пульта» ещё WATCHER_POLL_MS — иначе без потока он пропал бы из голосования, «Позвать в чат»
// и проверки ведущего, а комната считалась бы пустой
const WATCHER_POLL_MS = 15 * 1000;
function touchWatcher(room, user) {
  const w = watcherOf(room, user);
  const isNew = w.fresh;
  w.fresh = false;
  w.seen_until = Date.now() + WATCHER_POLL_MS;
  clearTimeout(w.expire);
  w.expire = setTimeout(() => dropWatcherIfGone(room, w), WATCHER_POLL_MS + 50);
  w.expire.unref?.();
  if (isNew) activityChanged(room);
}

function watcherOf(room, user) {
  let w = room.watchers.get(user.id);
  if (!w) {
    w = { id: user.id, name: user.name, avatar: user.avatar || "", streams: 0, seen_until: 0, expire: null, fresh: true };
    room.watchers.set(user.id, w);
  }
  room.empty_since = 0;
  return w;
}

function dropWatcherIfGone(room, w) {
  if (w.streams > 0 || w.seen_until > Date.now() || room.watchers.get(w.id) !== w) return;
  clearTimeout(w.expire);
  room.watchers.delete(w.id);
  if (rooms.get(room.id) !== room) return;
  if (room.members.size === 0 && room.watchers.size === 0) room.empty_since = Date.now();
  else if (!hostPresent(room)) setTimeout(() => promoteIfHostGone(room), HOST_GRACE_MS).unref?.();
  activityChanged(room);
}

function cleanVariant(v) {
  if (!v || typeof v !== "object") return null;
  const key = typeof v.key === "string" ? v.key.slice(0, 1000) : "";
  if (!key || key.indexOf("|") <= 0) return null;
  const label = typeof v.label === "string" ? v.label.slice(0, 300) : "";
  const quality = typeof v.quality === "string" ? v.quality.slice(0, 50) : (typeof v.resolution === "string" ? v.resolution.slice(0, 50) : "1080p");
  const audio = typeof v.audio === "string" ? v.audio.slice(0, 100) : (typeof v.audio_tech === "string" ? v.audio_tech.slice(0, 100) : "");
  const bitrate = Number(v.bitrate ?? v.bitrate_mbps) || 0;
  const offset_ms = Number(v.offset_ms) || 0;
  const score = Number(v.score) || 0;
  const hardware_flags = Array.isArray(v.hardware_flags)
    ? v.hardware_flags.filter(x => typeof x === "string" && x.trim()).map(x => x.trim().slice(0, 30))
    : [];
  return {
    key,
    label: label || quality,
    quality,
    audio,
    bitrate: Math.round(bitrate * 10) / 10,
    offset_ms,
    score,
    hardware_flags
  };
}

const MAX_VARIANTS = 30;

function cleanFile(file) {
  if (!file || typeof file !== "object") return null;
  const key = typeof file.key === "string" ? file.key.slice(0, 1000) : "";
  const bar = key.indexOf("|");
  const adId = Number(key.slice(0, bar));
  if (bar <= 0 || !Number.isSafeInteger(adId) || adId <= 0 || bar === key.length - 1) return null;
  const name = typeof file.name === "string" && file.name.trim() ? file.name.trim().slice(0, 300) : key.slice(bar + 1);
  const res = { key, name, ad_id: adId };
  if (typeof file.canonical_id === "string" && file.canonical_id.trim()) {
    res.canonical_id = file.canonical_id.trim().slice(0, 200);
  }
  if (typeof file.edition === "string" && file.edition.trim()) {
    res.edition = file.edition.trim().slice(0, 50);
  }
  if (Array.isArray(file.variants) && file.variants.length > 0) {
    const cleaned = file.variants.slice(0, MAX_VARIANTS).map(cleanVariant).filter(Boolean);
    if (cleaned.length > 0) res.variants = cleaned;
  }
  return res;
}

function channelRoomOrError(roomId) {
  const room = getRoom(roomId);
  if (!room) return { error: "Комната не найдена." };
  if (!room.channel_id) return { error: "Это не комната голосового канала." };
  return { room };
}

// «Предложить к просмотру»: любой, кто в комнате; пока идёт голосование, список не меняется
function suggestFile(roomId, user, file) {
  const { room, error } = channelRoomOrError(roomId);
  if (!room) return { ok: false, error };
  if (room.poll) return { ok: false, error: "Голосование уже идёт: дождитесь итога." };
  if (room.spin) return { ok: false, error: "Крутится рулетка: дождитесь итога." };
  const f = cleanFile(file);
  if (!f) return { ok: false, error: "Не тот файл." };
  const same = room.candidates.find(c => c.key === f.key || (c.canonical_id && f.canonical_id && c.canonical_id === f.canonical_id));
  if (same) {
    // тот же фильм в другом релизе: пополняем список пресетов, чтобы голосующий мог выбрать любой
    if (f.variants) {
      const have = new Set((same.variants || []).map(v => v.key));
      const extra = f.variants.filter(v => !have.has(v.key));
      if (extra.length) {
        same.variants = [...(same.variants || []), ...extra].slice(0, MAX_VARIANTS);
        activityChanged(room);
      }
    }
  } else {
    if (room.candidates.length >= MAX_CANDIDATES) return { ok: false, error: `Не больше ${MAX_CANDIDATES} фильмов в списке.` };
    const cand = {
      key: f.key,
      name: f.name,
      by: user.id,
      by_name: user.name,
      at: Date.now()
    };
    if (f.canonical_id) cand.canonical_id = f.canonical_id;
    if (f.edition) cand.edition = f.edition;
    if (typeof file.poster === "string" && (/^\/api\/poster\/img\/[a-zA-Z0-9_\-\.]+$/.test(file.poster) || /^[a-zA-Z0-9_\-\.]+\.(jpg|jpeg|png|webp)$/i.test(file.poster))) {
      cand.poster = file.poster.slice(0, 500);
    }
    const yr = Number(file.year);
    if (Number.isInteger(yr) && yr >= 1888 && yr <= 2100) cand.year = yr;
    if (typeof file.original === "string" && file.original.trim()) {
      cand.original = file.original.trim().slice(0, 200);
    }
    if (f.variants) cand.variants = f.variants;
    room.candidates.push(cand);
    activityChanged(room);
  }
  return { ok: true };
}

// Убрать из списка: кто предложил или ведущий
function unsuggestFile(roomId, user, key) {
  const { room, error } = channelRoomOrError(roomId);
  if (!room) return { ok: false, error };
  if (room.poll) return { ok: false, error: "Голосование уже идёт: дождитесь итога." };
  if (room.spin) return { ok: false, error: "Крутится рулетка: дождитесь итога." };
  const c = room.candidates.find(x => x.key === key);
  if (!c) return { ok: true };
  if (c.by !== user.id && !isRoomHost(room, user, null)) return { ok: false, error: "Убрать фильм может тот, кто его предложил, или ведущий." };
  room.candidates = room.candidates.filter(x => x !== c);
  activityChanged(room);
  return { ok: true };
}

function startPoll(roomId, user, ms = POLL_MS) {
  const { room, error } = channelRoomOrError(roomId);
  if (!room) return { ok: false, error };
  if (!isRoomHost(room, user, null)) return { ok: false, error: "Голосование начинает ведущий." };
  if (room.poll) return { ok: true };
  if (room.spin) return { ok: false, error: "Крутится рулетка: дождитесь итога." };
  if (room.candidates.length < 2) return { ok: false, error: "Для голосования нужно хотя бы два фильма." };
  const timer = setTimeout(() => finishPoll(room), ms);
  timer.unref?.();
  room.poll = { ends_at: Date.now() + ms, votes: new Map(), timer };
  activityChanged(room);
  return { ok: true };
}

function vote(roomId, user, key) {
  const { room, error } = channelRoomOrError(roomId);
  if (!room) return { ok: false, error };
  if (!room.poll) return { ok: false, error: "Голосование уже закончилось." };
  if (!room.candidates.some(c => c.key === key)) return { ok: false, error: "Такого фильма нет в списке." };
  room.poll.votes.set(user.id, key);
  // Все у пульта проголосовали — итог через ALL_VOTED_MS, а не в конце минуты
  if (allVoted(room) && room.poll.ends_at - Date.now() > ALL_VOTED_MS) {
    clearTimeout(room.poll.timer);
    room.poll.ends_at = Date.now() + ALL_VOTED_MS;
    room.poll.timer = setTimeout(() => finishPoll(room), ALL_VOTED_MS);
    room.poll.timer.unref?.();
  }
  activityChanged(room);
  return { ok: true };
}

// Итог: больше всех голосов; поровну — тот, кого предложили раньше. Победитель сразу ставится в комнату
function pollWinner(room) {
  const tally = new Map();
  for (const key of room.poll.votes.values()) tally.set(key, (tally.get(key) || 0) + 1);
  let best = null;
  let bestVotes = 0;
  for (const c of room.candidates) {
    const n = tally.get(c.key) || 0;
    if (n > bestVotes) {
      best = c;
      bestVotes = n;
    }
  }
  return best ? { candidate: best, votes: bestVotes } : null;
}

function finishPoll(room) {
  if (rooms.get(room.id) !== room || !room.poll) return null;
  clearTimeout(room.poll.timer);
  const win = pollWinner(room);
  room.poll = null;
  if (win) {
    room.candidates = [];
    handleRoomAction(room.id, { id: room.host_id }, { type: "change_file", file: cleanFile(win.candidate), client_id: room.host_client_id });
  }
  broadcast(room.id, "poll_result", win ? { key: win.candidate.key, name: win.candidate.name, votes: win.votes } : { key: null });
  activityChanged(room);
  return win;
}

// «Итог сейчас»: ведущий не ждёт конца таймера
function finishPollNow(roomId, user) {
  const { room, error } = channelRoomOrError(roomId);
  if (!room) return { ok: false, error };
  if (!isRoomHost(room, user, null)) return { ok: false, error: "Подвести итог может ведущий." };
  if (!room.poll) return { ok: false, error: "Голосование уже закончилось." };
  if (room.poll.votes.size === 0) return { ok: false, error: "Ещё никто не проголосовал." };
  finishPoll(room);
  return { ok: true };
}

// «Рулетка»: ведущий выбирает случайно из предложенных. Победителя выбирает сервер (у всех один и тот же),
// у всех SPIN_MS крутится барабан, потом фильм включается в комнате — как итог голосования
function spinRoulette(roomId, user, ms = SPIN_MS) {
  const { room, error } = channelRoomOrError(roomId);
  if (!room) return { ok: false, error };
  if (!isRoomHost(room, user, null)) return { ok: false, error: "Рулетку крутит ведущий." };
  if (room.spin) return { ok: true };
  if (room.poll) return { ok: false, error: "Сначала закончите голосование." };
  if (room.candidates.length < 2) return { ok: false, error: "Для рулетки нужно хотя бы два фильма." };
  const win = room.candidates[crypto.randomInt(room.candidates.length)];
  const timer = setTimeout(() => finishSpin(room), ms);
  timer.unref?.();
  const now = Date.now();
  room.spin = { key: win.key, name: win.name, started_at: now, ends_at: now + ms, timer };
  activityChanged(room);
  return { ok: true, key: win.key };
}

function finishSpin(room) {
  if (rooms.get(room.id) !== room || !room.spin) return null;
  clearTimeout(room.spin.timer);
  const win = room.candidates.find(c => c.key === room.spin.key);
  room.spin = null;
  if (win) {
    room.candidates = [];
    handleRoomAction(room.id, { id: room.host_id }, { type: "change_file", file: cleanFile(win), client_id: room.host_client_id });
  }
  broadcast(room.id, "poll_result", win ? { key: win.key, name: win.name, votes: null, roulette: true } : { key: null, roulette: true });
  activityChanged(room);
  return win || null;
}

// Ведущий передумал, пока крутится барабан: фильм не включается, список остаётся. Страницы останавливают барабан
// по событию spin_cancelled (просто spin: null приходит и при обычном итоге — его барабан должен доиграть)
function cancelSpin(roomId, user) {
  const { room, error } = channelRoomOrError(roomId);
  if (!room) return { ok: false, error };
  if (!isRoomHost(room, user, null)) return { ok: false, error: "Рулетку останавливает ведущий." };
  if (room.spin) {
    clearTimeout(room.spin.timer);
    room.spin = null;
    broadcast(room.id, "spin_cancelled", {});
    activityChanged(room);
  }
  return { ok: true };
}

function cancelPoll(roomId, user) {
  const { room, error } = channelRoomOrError(roomId);
  if (!room) return { ok: false, error };
  if (!isRoomHost(room, user, null)) return { ok: false, error: "Голосование отменяет ведущий." };
  if (room.poll) {
    clearTimeout(room.poll.timer);
    room.poll = null;
    activityChanged(room);
  }
  return { ok: true };
}

module.exports = {
  createRoom,
  getRoom,
  broadcast,
  getRoomPublicData,
  addListener,
  joinRoom,
  findUserActiveRoom,
  moveDevice,
  moveAccount,
  getAccountActiveDevice,
  setAccountActiveDevice,
  clearAccountActiveDevice,
  registerMemberStream,
  unregisterMemberStream,
  closeMemberStreams,
  previewMember,
  publicMember,
  clientKey,
  isRoomHost,
  leaveRoom,
  sweepRooms,
  promoteIfHostGone,
  handleRoomAction,
  timelineShiftMs,
  getOrCreateChannelRoom,
  reportSpeaking,
  dropSpeaker,
  notifyUserSettings,
  addWatcher,
  touchWatcher,
  suggestFile,
  unsuggestFile,
  startPoll,
  vote,
  finishPoll,
  finishPollNow,
  spinRoulette,
  spinMs,
  finishSpin,
  cancelPoll,
  cancelSpin,
  cleanFile
};

"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-discord-test-"));

const test = require("node:test");
const assert = require("node:assert/strict");

const config = require("../src/config");
const { getAvatarUrl, checkGuildMember } = require("../src/services/discord");
const {
  getUser,
  findOrCreateUser,
  createDevicePairing,
  authorizeDevicePairing,
  pollDevicePairing,
  verifyDeviceToken
} = require("../src/services/users");
const { createRoom, getRoomPublicData, joinRoom, handleRoomAction } = require("../src/services/rooms");

test("discord: getAvatarUrl returns correct CDN link or default avatar", () => {
  const custom = { id: "123456789", avatar: "abcdef123456" };
  assert.equal(getAvatarUrl(custom), "https://cdn.discordapp.com/avatars/123456789/abcdef123456.png?size=128");

  const animated = { id: "123456789", avatar: "a_abcdef123456" };
  assert.equal(getAvatarUrl(animated), "https://cdn.discordapp.com/avatars/123456789/a_abcdef123456.gif?size=128");

  const noAvatar = { id: "123456789", avatar: null };
  assert.match(getAvatarUrl(noAvatar), /^https:\/\/cdn\.discordapp\.com\/embed\/avatars\/\d+\.png$/);
});

test("users & device flow: pairing creation, authorization and polling", () => {
  const profile = { id: "987654321", username: "testuser", global_name: "Test User", avatar: "abc" };
  const user = findOrCreateUser(profile, "admin");

  assert.equal(user.id, "u_987654321");
  assert.equal(user.name, "Test User");
  assert.equal(user.role, "admin");

  // Device flow
  const pairing = createDevicePairing("Desktop App");
  assert.ok(pairing.code);
  assert.ok(pairing.user_code);

  // Poll before authorization
  const pending = pollDevicePairing(pairing.code);
  assert.equal(pending.status, "pending");

  // Authorize
  const authRes = authorizeDevicePairing(pairing.user_code, user.id);
  assert.equal(authRes.ok, true);

  // Poll after authorization
  const pollAuth = pollDevicePairing(pairing.code);
  assert.equal(pollAuth.status, "authorized");
  assert.ok(pollAuth.device_token);
  assert.equal(pollAuth.user.id, user.id);

  // Verify device token
  const verified = verifyDeviceToken(pollAuth.device_token);
  assert.ok(verified);
  assert.equal(verified.user.id, user.id);
  assert.equal(verified.device.name, "Desktop App");
});

test("rooms & watch together: state transitions and synchronization", () => {
  const host = { id: "u_1", name: "Host User" };
  const room = createRoom(host, { ad_id: 10, idx: 0, name: "Film.mkv" }, "all");

  assert.equal(room.host_id, "u_1");
  assert.equal(room.file.name, "Film.mkv");

  // Join room
  const joinRes = joinRoom(room.id, { id: "u_2", name: "Guest User" });
  assert.ok(joinRes.member);

  // Initial state
  const data0 = getRoomPublicData(room);
  assert.equal(data0.state.playing, false);
  assert.equal(data0.state.pos, 0);

  // Action: play
  const resPlay = handleRoomAction(room.id, host, { type: "play", pos: 5 });
  assert.equal(resPlay.ok, true);
  assert.equal(resPlay.room.state.playing, true);
  assert.equal(resPlay.room.state.pos, 5);

  // Action: seek
  const resSeek = handleRoomAction(room.id, host, { type: "seek", pos: 120 });
  assert.equal(resSeek.ok, true);
  assert.equal(resSeek.room.state.pos, 120);

  // Action: pause
  const resPause = handleRoomAction(room.id, host, { type: "pause", pos: 125 });
  assert.equal(resPause.ok, true);
  assert.equal(resPause.room.state.playing, false);
  assert.equal(resPause.room.state.pos, 125);
});

test("rooms: only the host picks the film, an empty room pauses, members are open streams", () => {
  const { leaveRoom, previewMember } = require("../src/services/rooms");
  const host = { id: "u_10", name: "Host" };
  const guest = { id: "u_11", name: "Guest" };
  const room = createRoom(host, null, "all", "pc_host");

  // «Войти» ещё не делает участником: только поток событий
  assert.equal(previewMember(guest, "pc_guest").id, "u_11");
  assert.equal(room.members.size, 0);
  joinRoom(room.id, host, "pc_host");
  joinRoom(room.id, guest, "pc_guest");
  joinRoom(room.id, guest, "pc_guest"); // второй поток того же устройства (переподключение)
  assert.equal(getRoomPublicData(room).members.length, 2);
  assert.equal(getRoomPublicData(room).members[0].streams, undefined);

  const film = { key: "1|a.mkv", name: "A" };
  const byGuest = handleRoomAction(room.id, guest, { type: "change_file", file: film, client_id: "pc_guest" });
  assert.equal(byGuest.ok, false);
  assert.equal(handleRoomAction(room.id, host, { type: "change_file", file: film, client_id: "pc_host" }).ok, true);
  assert.equal(room.file.key, "1|a.mkv");
  // Паузу, продолжение и перемотку для всех делает только ведущий
  assert.equal(handleRoomAction(room.id, host, { type: "play", pos: 10, client_id: "pc_host" }).ok, true);
  assert.equal(handleRoomAction(room.id, guest, { type: "seek", pos: 500, client_id: "pc_guest" }).ok, false);
  assert.equal(handleRoomAction(room.id, guest, { type: "pause", pos: 900, client_id: "pc_guest" }).ok, false);
  assert.equal(handleRoomAction(room.id, guest, { type: "play", pos: 900, client_id: "pc_guest" }).ok, false);
  assert.equal(room.state.playing, true);
  assert.ok(room.state.pos >= 10 && room.state.pos < 20);

  // Один поток гостя закрылся — он ещё в комнате; «Выйти» убирает сразу
  leaveRoom(room.id, "pc_guest");
  assert.equal(room.members.size, 2);
  leaveRoom(room.id, "pc_guest", true);
  assert.equal(room.members.size, 1);

  // Ушёл последний — комната на паузе, время не идёт
  assert.equal(room.state.playing, true);
  leaveRoom(room.id, "pc_host");
  assert.equal(room.members.size, 0);
  assert.equal(room.state.playing, false);
  assert.ok(room.state.pos >= 10);
});

test("rooms: with the shared password the creating device is the host", () => {
  const shared = { id: "shared", name: "Общий доступ" };
  const room = createRoom(shared, null, "all", "pc_a");
  const film = { key: "2|b.mkv", name: "B" };
  assert.equal(handleRoomAction(room.id, shared, { type: "change_file", file: film, client_id: "pc_b" }).ok, false);
  assert.equal(handleRoomAction(room.id, shared, { type: "change_file", file: film, client_id: "pc_a" }).ok, true);
});

test("rooms: client ids are not shown to others (a guest cannot act as the host's device)", () => {
  const { clientKey } = require("../src/services/rooms");
  const shared = { id: "shared", name: "Общий доступ" };
  const room = createRoom(shared, null, "all", "pc_secret_host");
  joinRoom(room.id, shared, "pc_secret_host");
  joinRoom(room.id, shared, "pc_guest2");
  const pub = JSON.stringify(getRoomPublicData(room));
  assert.equal(pub.includes("pc_secret_host"), false);
  assert.equal(pub.includes("pc_guest2"), false);
  const data = getRoomPublicData(room);
  assert.equal(data.host_key, clientKey("pc_secret_host"));
  assert.deepEqual(data.members.map(m => m.key).sort(), [clientKey("pc_secret_host"), clientKey("pc_guest2")].sort());
  // Отпечаток вместо client_id не даёт прав ведущего
  const film = { key: "3|c.mkv", name: "C" };
  assert.equal(handleRoomAction(room.id, shared, { type: "change_file", file: film, client_id: data.host_key }).ok, false);
});

test("rooms: an empty room is removed after 30 minutes, even if nobody ever came in", () => {
  const { getRoom, sweepRooms, leaveRoom } = require("../src/services/rooms");
  const host = { id: "u_30", name: "Host" };
  const never = createRoom(host, null, "all", "pc_x");
  const used = createRoom(host, null, "all", "pc_y");
  joinRoom(used.id, host, "pc_y");
  const later = Date.now() + 31 * 60 * 1000;
  sweepRooms(later);
  assert.equal(getRoom(never.id), null);
  assert.ok(getRoom(used.id)); // в ней кто-то есть
  leaveRoom(used.id, "pc_y");
  sweepRooms(Date.now() + 10 * 60 * 1000);
  assert.ok(getRoom(used.id)); // пуста недавно
  sweepRooms(Date.now() + 31 * 60 * 1000);
  assert.equal(getRoom(used.id), null);
});

test("rooms: the host is gone for good — the longest member becomes the host; a short drop changes nothing", () => {
  const { leaveRoom, promoteIfHostGone, isRoomHost } = require("../src/services/rooms");
  const host = { id: "u_40", name: "Host" };
  const g1 = { id: "u_41", name: "First" };
  const g2 = { id: "u_42", name: "Second" };
  const room = createRoom(host, null, "host", "pc_h40");
  joinRoom(room.id, host, "pc_h40");
  joinRoom(room.id, g1, "pc_g41");
  joinRoom(room.id, g2, "pc_g42");

  // Ведущий на месте — никого не повышают
  assert.equal(promoteIfHostGone(room), false);
  // Обрыв и возврат за минуту — ведущий тот же
  leaveRoom(room.id, "pc_h40");
  joinRoom(room.id, host, "pc_h40");
  assert.equal(promoteIfHostGone(room), false);
  assert.equal(room.host_id, "u_40");

  // Ушёл совсем — ведущим становится тот, кто вошёл раньше остальных
  leaveRoom(room.id, "pc_h40", true);
  assert.equal(promoteIfHostGone(room), true);
  assert.equal(room.host_id, "u_41");
  assert.equal(isRoomHost(room, g1, "pc_g41"), true);
  assert.equal(handleRoomAction(room.id, g1, { type: "play", pos: 1, client_id: "pc_g41" }).ok, true);
  assert.equal(handleRoomAction(room.id, g2, { type: "pause", pos: 2, client_id: "pc_g42" }).ok, false);
});

test("discord: a whitelisted user is not an admin by a role list (role ids are not user ids)", async () => {
  const was = { users: config.DISCORD_ALLOWED_USERS, admin: config.DISCORD_ROLE_ADMIN, bot: config.DISCORD_BOT_TOKEN };
  try {
    config.DISCORD_ALLOWED_USERS = ["555"];
    config.DISCORD_ROLE_ADMIN = ["555"]; // совпадение id пользователя и роли не делает админом
    config.DISCORD_BOT_TOKEN = "";
    assert.deepEqual(await checkGuildMember("555"), { allowed: true, role: "allow" });
  } finally {
    config.DISCORD_ALLOWED_USERS = was.users;
    config.DISCORD_ROLE_ADMIN = was.admin;
    config.DISCORD_BOT_TOKEN = was.bot;
  }
});

test("favorites: per Discord user, per device with the shared password; the device's data moves on sign-in", () => {
  const { setFavorite, favoritesOf, withRemovable } = require("../src/services/favorites");
  const { recordOwner } = require("../src/services/downloads");
  const { deviceId } = require("../src/middleware/auth");
  const users = require("../src/services/users");
  const sdev = "0123456789abcdef0123456789abcdef";
  const cookieReq = (user, dev) => ({ user, headers: { cookie: dev ? `sdev=${dev}` : "" } });

  // Общий пароль: у каждого устройства своё, а не одно «shared» на всех
  const sharedA = cookieReq(users.SHARED_USER, sdev);
  const sharedB = cookieReq(users.SHARED_USER, "f".repeat(32));
  assert.equal(deviceId(sharedA, null), sdev);
  setFavorite(deviceId(sharedA, null), "101", true);
  recordOwner("101", deviceId(sharedA, null), "1.1.1.1");
  assert.deepEqual([...favoritesOf(sharedA)], ["101"]);
  assert.equal(favoritesOf(sharedB), null);

  // Вход через Discord с этого устройства: избранное и раздачи — пользователю
  const u = users.findOrCreateUser({ id: "4242", username: "fav" }, "allow");
  users.adoptDevice(sdev, u.id);
  const asUser = cookieReq(u, sdev);
  assert.equal(deviceId(asUser, null), u.id);
  assert.deepEqual([...favoritesOf(asUser)], ["101"]);
  assert.equal(withRemovable({ magnets: [{ id: 101, statusCode: 1 }] }, asUser).magnets[0].canRemove, true);
  assert.equal(withRemovable({ magnets: [{ id: 101, statusCode: 1 }] }, sharedA).magnets[0].canRemove, false);
  // То, что отмечено под пользователем, ему и отдаётся
  setFavorite(u.id, "202", true);
  assert.deepEqual([...favoritesOf(asUser)].sort(), ["101", "202"]);
});

test("rooms: the room waits while someone is loading, then goes on from the same place", () => {
  const { leaveRoom } = require("../src/services/rooms");
  const host = { id: "u_20", name: "Host" };
  const guest = { id: "u_21", name: "Guest" };
  const room = createRoom(host, null, "all", "pc_h");
  joinRoom(room.id, host, "pc_h");
  joinRoom(room.id, guest, "pc_g");
  handleRoomAction(room.id, host, { type: "play", pos: 100, client_id: "pc_h" });

  // Гость грузится (после перемотки) — комната ждёт, время стоит; «грузится» может сообщить любой
  const r1 = handleRoomAction(room.id, guest, { type: "buffering", buffering: true, client_id: "pc_g" });
  assert.equal(r1.ok, true);
  assert.equal(room.state.waiting, true);
  assert.equal(room.state.playing, true); // намерение «играть» не потеряно
  const frozen = getRoomPublicData(room).state.current_pos;
  assert.ok(frozen >= 100 && frozen < 101);

  // Готов — комната идёт дальше с того же места
  handleRoomAction(room.id, guest, { type: "buffering", buffering: false, client_id: "pc_g" });
  assert.equal(room.state.waiting, false);
  assert.ok(Math.abs(room.state.pos - frozen) < 0.01);

  // Ушёл тот, кого ждали, — комната не стоит
  handleRoomAction(room.id, guest, { type: "buffering", buffering: true, client_id: "pc_g" });
  assert.equal(room.state.waiting, true);
  leaveRoom(room.id, "pc_g", true);
  assert.equal(room.state.waiting, false);
});

test("PASSWORD_AUTH_ENABLED=false logs out everyone who came in with the password", () => {
  const { resolveAuth } = require("../src/middleware/auth");
  const users = require("../src/services/users");
  const fakeReq = (session, token) => {
    let destroyed = false;
    const s = session ? { ...session, destroy: cb => { destroyed = true; cb && cb(); } } : null;
    return { req: { session: s, get: h => (h === "X-Device-Token" ? token || "" : "") }, destroyed: () => destroyed };
  };

  // Устройство, привязанное из парольной сессии, и устройство пользователя Discord
  const p1 = users.createDevicePairing("ТВ по паролю");
  users.authorizeDevicePairing(p1.user_code, "shared");
  const sharedToken = users.pollDevicePairing(p1.code).device_token;
  const discordUser = users.findOrCreateUser({ id: "777", username: "d" }, "allow");
  const p2 = users.createDevicePairing("ПК Discord");
  users.authorizeDevicePairing(p2.user_code, discordUser.id);
  const discordToken = users.pollDevicePairing(p2.code).device_token;

  const was = config.PASSWORD_AUTH_ENABLED;
  try {
    config.PASSWORD_AUTH_ENABLED = true;
    assert.equal(resolveAuth(fakeReq({ authenticated: true }).req), true);
    assert.equal(resolveAuth(fakeReq(null, sharedToken).req), true);

    config.PASSWORD_AUTH_ENABLED = false;
    const pw = fakeReq({ authenticated: true });
    assert.equal(resolveAuth(pw.req), false);
    assert.equal(pw.destroyed(), true); // сессия по паролю удалена
    assert.equal(resolveAuth(fakeReq({ authenticated: true, uid: "shared" }).req), false);
    assert.equal(resolveAuth(fakeReq(null, sharedToken).req), false);
    // Вход через Discord работает как прежде
    assert.equal(resolveAuth(fakeReq({ authenticated: true, uid: discordUser.id }).req), true);
    assert.equal(resolveAuth(fakeReq(null, discordToken).req), true);
  } finally {
    config.PASSWORD_AUTH_ENABLED = was;
  }
});

test("discord: without a bot and a whitelist only the owner gets in (not every Discord account)", async () => {
  const was = { users: config.DISCORD_ALLOWED_USERS, bot: config.DISCORD_BOT_TOKEN, guild: config.DISCORD_GUILD_ID, owner: config.DISCORD_OWNER_ID };
  try {
    config.DISCORD_ALLOWED_USERS = [];
    config.DISCORD_BOT_TOKEN = "";
    config.DISCORD_GUILD_ID = "";
    config.DISCORD_OWNER_ID = "777";
    assert.equal((await checkGuildMember("12345")).allowed, false);
    assert.deepEqual(await checkGuildMember("777"), { allowed: true, role: "admin" });
  } finally {
    config.DISCORD_ALLOWED_USERS = was.users;
    config.DISCORD_BOT_TOKEN = was.bot;
    config.DISCORD_GUILD_ID = was.guild;
    config.DISCORD_OWNER_ID = was.owner;
  }
});

test("shelf («Мои фильмы»): personal, starts from own torrents and favourites, removal is only for me", () => {
  const { shelfOf, setOnShelf, dropEverywhere } = require("../src/services/shelf");
  const { setFavorite, withRemovable } = require("../src/services/favorites");
  const { recordOwner } = require("../src/services/downloads");
  const users = require("../src/services/users");
  const devA = "a".repeat(32);
  const devB = "b".repeat(32);
  const req = (user, dev) => ({ user, headers: { cookie: dev ? `sdev=${dev}` : "" } });
  const a = req(users.SHARED_USER, devA);
  const b = req(users.SHARED_USER, devB);

  // Первое обращение: своё добавленное и избранное
  recordOwner("301", devA, "1.1.1.1");
  setFavorite(devA, "302", true);
  assert.deepEqual([...shelfOf(a)].sort(), ["301", "302"]);
  assert.equal(shelfOf(req(users.SHARED_USER, null)), null); // без cookie устройства списка нет

  // Повторное добавление чужой раздачи не меняет её владельца
  recordOwner("301", devB, "2.2.2.2");
  assert.equal(withRemovable({ magnets: [{ id: 301, statusCode: 1 }] }, a).magnets[0].canRemove, true);
  assert.equal(withRemovable({ magnets: [{ id: 301, statusCode: 1 }] }, b).magnets[0].canRemove, false);

  // Взял из общей библиотеки — только себе
  setOnShelf(devB, "303", true);
  assert.equal(withRemovable({ magnets: [{ id: 303 }] }, b).magnets[0].mine, true);
  assert.equal(withRemovable({ magnets: [{ id: 303 }] }, a).magnets[0].mine, false);

  // Убрал из «Моих фильмов» — у других остаётся
  setOnShelf(devA, "303", true);
  setOnShelf(devB, "303", false);
  assert.equal(shelfOf(b).has("303"), false);
  assert.equal(shelfOf(a).has("303"), true);

  // Раздачу удалили из AllDebrid — её нет ни у кого
  dropEverywhere("303");
  assert.equal(shelfOf(a).has("303"), false);

  // Вход через Discord: список устройства переходит пользователю
  const u = users.findOrCreateUser({ id: "5151", username: "shelf" }, "allow");
  users.adoptDevice(devA, u.id);
  assert.deepEqual([...shelfOf(req(u, devA))].sort(), ["301", "302"]);
});

test("shelf: whatever is added (magnet, torrent, search) lands in «Мои фильмы» of the one who added it", () => {
  const { reserveDownloadSlot } = require("../src/services/downloads");
  const { shelfOf } = require("../src/services/shelf");
  const users = require("../src/services/users");
  const u = users.findOrCreateUser({ id: "6161", username: "adder" }, "allow");
  const req = { user: u, headers: { cookie: "" }, ip: "3.3.3.3", socket: {} };
  const res = { status() { return this; }, json() {}, cookie() {} };
  const slot = reserveDownloadSlot(req, res);
  slot.attach(401, true);
  assert.equal(shelfOf(req).has("401"), true);
});

test("cleanup: films nobody opened for 10 days go away, and the library is kept under its limit", () => {
  const { pickForCleanup, markOpened } = require("../src/services/cleanup");
  const day = 24 * 3600 * 1000;
  const t0 = Date.UTC(2026, 0, 1);
  const lib = [{ id: 501 }, { id: 502 }, { id: 503 }, { id: 504 }];
  // Первый раз видим — отсчёт с этого момента, ничего не удаляется
  assert.deepEqual(pickForCleanup(lib, t0, { idleDays: 10, maxItems: 800 }), []);
  // Через 11 дней: 502 открывали на 5-й день — остаётся, остальные — нет
  const realNow = Date.now;
  try {
    Date.now = () => t0 + 5 * day;
    markOpened(502);
  } finally {
    Date.now = realNow;
  }
  const idle = pickForCleanup(lib, t0 + 11 * day, { idleDays: 10, maxItems: 800 });
  assert.deepEqual(idle.map(x => x.id).sort(), [501, 503, 504]);
  assert.ok(idle.every(x => x.reason === "idle"));
  // Лимит: остаётся 2 — уходят давно не открытые, при равенстве — добавленные раньше
  const limit = pickForCleanup(lib, t0 + 6 * day, { idleDays: 10, maxItems: 2 });
  assert.deepEqual(limit.map(x => x.id), [501, 503]);
  assert.ok(limit.every(x => x.reason === "limit"));
});

test("rooms: 1 account = 1 active room (cannot join from another device into same or other room)", () => {
  const { createRoom, joinRoom, leaveRoom, findUserActiveRoom } = require("../src/services/rooms");
  const user = { id: "u_50", name: "MultiDeviceUser" };
  const otherUser = { id: "u_51", name: "OtherUser" };

  const room1 = createRoom(user, null, "all", "pc_1");
  const room2 = createRoom(otherUser, null, "all", "pc_other");

  // 1. Первый вход с устройства pc_1 успешен
  const join1 = joinRoom(room1.id, user, "pc_1");
  assert.equal(join1.ok, true);
  assert.ok(join1.member);

  // 2. Попытка войти в ту же комнату с другого устройства (pc_2) тем же аккаунтом блокируется
  const joinSameRoomOtherDev = joinRoom(room1.id, user, "pc_2");
  assert.equal(joinSameRoomOtherDev.ok, false);
  assert.equal(joinSameRoomOtherDev.error, "Этот аккаунт уже подключен к комнате с другого устройства.");

  // 3. Попытка войти в другую комнату (room2) с устройства pc_2 тем же аккаунтом блокируется
  const joinOtherRoomOtherDev = joinRoom(room2.id, user, "pc_2");
  assert.equal(joinOtherRoomOtherDev.ok, false);
  assert.equal(joinOtherRoomOtherDev.error, "Этот аккаунт уже находится в активной комнате.");

  // 4. Попытка создать комнату (проверка findUserActiveRoom с targetRoomId = null) блокируется
  const activeCheck = findUserActiveRoom(user, "pc_2", null);
  assert.ok(activeCheck);
  assert.equal(activeCheck.reason, "other_room");

  // 5. Переподключение с того же устройства (pc_1) в ту же комнату разрешено (второй поток)
  const reconnectSameDev = joinRoom(room1.id, user, "pc_1");
  assert.equal(reconnectSameDev.ok, true);

  // 6. Другой пользователь (otherUser) может войти в room1
  const joinOtherUser = joinRoom(room1.id, otherUser, "pc_other");
  assert.equal(joinOtherUser.ok, true);

  // 7. Пользователь user выходит из room1 (все потоки закрыты)
  leaveRoom(room1.id, "pc_1", true);

  // 8. Теперь вход с устройства pc_2 разрешён
  const joinAfterLeave = joinRoom(room2.id, user, "pc_2");
  assert.equal(joinAfterLeave.ok, true);
  assert.ok(joinAfterLeave.member);

  // Очистка
  leaveRoom(room1.id, "pc_other", true);
  leaveRoom(room2.id, "pc_2", true);
});

test("rooms: same device cannot be in two different rooms at the same time", () => {
  const { createRoom, joinRoom, leaveRoom } = require("../src/services/rooms");
  const shared = { id: "shared", name: "Общий доступ" };
  const r1 = createRoom(shared, null, "all", "tv_1");
  const r2 = createRoom(shared, null, "all", "tv_2");

  const j1 = joinRoom(r1.id, shared, "tv_1");
  assert.equal(j1.ok, true);

  // То же устройство tv_1 пытается войти в r2, пока активно в r1
  const j2 = joinRoom(r2.id, shared, "tv_1");
  assert.equal(j2.ok, false);
  assert.equal(j2.error, "Это устройство уже подключено к другой комнате.");

  // Другое устройство tv_2 с тем же shared аккаунтом может войти в r1
  const j3 = joinRoom(r1.id, shared, "tv_2");
  assert.equal(j3.ok, true);

  leaveRoom(r1.id, "tv_1", true);
  leaveRoom(r1.id, "tv_2", true);
});

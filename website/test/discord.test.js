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

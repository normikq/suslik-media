'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const C = require('../js/partycore.js');
const P = C.protocol, R = C.room;

test('clientKey: sha-256 от client_id, 16 hex — как rooms.js clientKey', () => {
  for (const s of ['', 'tv_0123456789ab', 'a'.repeat(200), 'Привет, мир']) {
    assert.equal(C.sha256hex(s), crypto.createHash('sha256').update(s).digest('hex'));
  }
  assert.equal(P.clientKey('tv_abc'), crypto.createHash('sha256').update('tv_abc').digest('hex').slice(0, 16));
  assert.match(P.newClientId('tv'), /^tv_[0-9a-f]{12}$/);
});

test('normalizeCode: только буквы и цифры, нижний регистр', () => {
  assert.equal(P.normalizeCode('  AB-12 cd '), 'ab12cd');
  assert.equal(P.normalizeCode('---'), '');
});

const roomJson = (seq, file, extra) => Object.assign({
  id: 'abc123', host_id: 'u_1', host_key: 'k1', host_name: 'Аня', controls: 'all',
  file: file ? { key: file, name: 'Фильм' } : null,
  state: { playing: true, pos: 10, rate: 1, anchor_server_ms: 1000, seq, waiting: false, server_time: 1500 },
  members: [{ key: 'k1', id: 'u_1', name: 'Аня' }, { key: 'k2', id: 'u_2', name: '', buffering: true }]
}, extra || {});

test('parseRoom и события потока', () => {
  const u = P.parseRoom(roomJson(3, '42|a.mkv'));
  assert.equal(u.id, 'abc123');
  assert.equal(u.file.adId, 42);
  assert.equal(u.state.seq, 3);
  assert.equal(u.serverTime, 1500);
  assert.equal(u.members[1].name, 'Гость');
  assert.equal(u.members[1].buffering, true);
  const init = P.parseEventLine('data: ' + JSON.stringify({ event: 'init', member: { id: 'u_2' }, room: roomJson(1, null) }));
  assert.equal(init.type, 'init');
  assert.equal(init.myUserId, 'u_2');
  assert.equal(P.parseEventLine(': ping'), null);
  assert.equal(P.parseEventLine('data: {oops'), null);
  const left = P.parseEvent(JSON.stringify({ event: 'member_left', data: { key: 'k2' } }));
  assert.deepEqual(left, { type: 'member_left', key: 'k2' });
});

test('merge: состояние только новее по seq; фильм ведущего держится до подтверждения', () => {
  let r = R.merge(R.empty(), P.parseRoom(roomJson(5, '42|a.mkv')));
  assert.equal(r.state.seq, 5);
  r = R.merge(r, P.parseRoom(roomJson(4, '42|a.mkv', { state: { playing: false, pos: 0, seq: 4 } })));
  assert.equal(r.state.seq, 5, 'старый ответ не откатывает состояние');
  r = R.putFile(r, P.fileRef('42|b.mkv', 'Серия 2'));
  r = R.merge(r, P.parseRoom(roomJson(6, '42|a.mkv')));
  assert.equal(r.file.key, '42|b.mkv', 'ответ на прежнее действие не возвращает прошлую серию');
  assert.equal(r.state.seq, 5);
  r = R.merge(r, P.parseRoom(roomJson(7, '42|b.mkv')));
  assert.equal(r.pendingFileKey, null);
  assert.equal(r.state.seq, 7);
  r = R.reduce(r, { type: 'member_left', key: 'k2' });
  assert.equal(r.members.length, 1);
  assert.equal(R.isHost(r, 'u_1', 'zz'), true);
  assert.equal(R.isHost(r, 'u_2', 'k2'), false);
});

test('isHost при общем пароле: только устройство создателя', () => {
  const r = R.merge(R.empty(), P.parseRoom(roomJson(1, null, { host_id: 'shared', host_key: 'k1' })));
  assert.equal(R.isHost(r, 'shared', 'k1'), true);
  assert.equal(R.isHost(r, 'shared', 'k2'), false);
});

test('targetMs: идёт от якоря, стоит на паузе и в ожидании', () => {
  const s = { playing: true, pos: 10, rate: 1, anchorServerMs: 1000, seq: 1, waiting: false };
  assert.equal(C.targetMs(s, 3000), 12000);
  assert.equal(C.targetMs(Object.assign({}, s, { waiting: true }), 3000), 10000);
  assert.equal(C.targetMs(Object.assign({}, s, { playing: false }), 3000), 10000);
  const clock = new C.Clock();
  clock.addSample(100, 300, 10200); // rtt 200 → offset 10200 - 200
  clock.addSample(100, 500, 99999); // хуже — не берём
  assert.equal(clock.serverNow(1000), 11000);
});

// ---- движок: комната и плеер-заглушки ----

function setup(opts) {
  const sent = [];
  const room = { state: Object.assign({ playing: false, pos: 0, rate: 1, anchorServerMs: 0, seq: 1, waiting: false }, opts.state || {}) };
  const link = {
    state: () => room.state, canControl: () => opts.host, canSeek: () => opts.host,
    targetMs: () => opts.target(), othersBuffering: () => false,
    takeStartedByMe: () => false,
    play: ms => sent.push(['play', ms]), pause: ms => sent.push(['pause', ms]), seek: ms => sent.push(['seek', ms]),
    reportBuffering: () => {}
  };
  const notices = [];
  const engine = new C.Engine(link, null, n => notices.push(n));
  const player = {
    timeMs: 0, busy: false, engineMs: 0, seekStamp: 0, paused: true, running: true,
    applyPaused(p) { this.paused = p; }, seekTo(ms) { this.timeMs = ms; this.engineMs = ms; this.seekStamp++; }
  };
  return { room, engine, player, sent, notices };
}

test('гость догоняет комнату перемоткой и пуском', () => {
  let target = 60000;
  const t = setup({ host: false, target: () => target, state: { playing: true, seq: 2 } });
  t.engine.tick(t.player, '1|a', 1000);
  assert.equal(t.player.timeMs, 60000);
  assert.equal(t.player.paused, false);
  assert.deepEqual(t.sent, []);
});

test('ведущий: пауза здесь — пауза у комнаты; перемотка здесь — комната туда же', () => {
  const t = setup({ host: true, target: () => 0, state: { playing: false, seq: 1 } });
  t.engine.tick(t.player, '1|a', 1000);           // вошли: комната на паузе в 0
  t.player.paused = false;
  t.engine.tick(t.player, '1|a', 1400);
  assert.deepEqual(t.sent.pop(), ['play', 0]);
  t.player.timeMs = 120000; t.player.engineMs = 120000; t.player.seekStamp++;
  t.engine.tick(t.player, '1|a', 1800);
  assert.deepEqual(t.sent.pop(), ['play', 120000]);
});

test('гость: пауза только у себя, комнате не отправляется', () => {
  const t = setup({ host: false, target: () => 5000, state: { playing: true, seq: 1 } });
  t.player.timeMs = 5000; t.player.engineMs = 5000; t.player.paused = false;
  t.engine.tick(t.player, '1|a', 1000);
  t.player.paused = true;
  t.engine.tick(t.player, '1|a', 1400);
  assert.deepEqual(t.sent, []);
  assert.equal(t.player.paused, true);
});

test('гостю — подсказка, что ведущий перемотал', () => {
  let target = 5000;
  const t = setup({ host: false, target: () => target, state: { playing: false, pos: 5, seq: 1 } });
  t.player.timeMs = 5000;
  t.engine.tick(t.player, '1|a', 1000);
  t.room.state = { playing: false, pos: 600, rate: 1, anchorServerMs: 0, seq: 2, waiting: false };
  target = 600000;
  t.engine.tick(t.player, '1|a', 1400);
  assert.deepEqual(t.notices, [{ type: 'seeked', posMs: 600000 }]);
  assert.equal(t.player.timeMs, 600000);
});

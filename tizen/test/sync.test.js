'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { Store, WatchSync } = require('../js/sync.js');

function memory() {
  const m = new Map();
  return {
    get length() { return m.size; },
    key: i => [...m.keys()][i],
    getItem: k => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: k => m.delete(k)
  };
}

test('первый раз — всё своё и since 0, дальше — только новое', () => {
  const ls = memory();
  ls.setItem('watch:1|a.mkv', JSON.stringify({ pos: 100, len: 1000, at: 10 }));
  ls.setItem('watch:2|b.mkv', JSON.stringify({ pos: 200, len: 1000, at: 20 }));
  ls.setItem('device_token', 'x');
  const sync = new WatchSync(new Store(ls));
  const sent = sync.request('u_1');
  assert.equal(sent.body.since, 0);
  assert.deepEqual(sent.body.watch.map(e => e.key), ['2|b.mkv', '1|a.mkv']);
  sync.onResponse(sent, { watch: [], now: 500 });
  assert.match(ls.getItem('sync_cursor'), /^u_1\|500\|\d+$/); // третье поле — время ТВ, до которого всё выгружено
  const next = sync.request('u_1');
  assert.equal(next.body.since, 500);
  assert.equal(next.body.watch.length, 0);
  // другой пользователь — снова всё
  assert.equal(sync.request('u_2').body.since, 0);
});

test('пришедшее новее своего ложится, старое — нет', () => {
  const ls = memory();
  ls.setItem('watch:1|a.mkv', JSON.stringify({ pos: 100, len: 1000, at: 50 }));
  ls.setItem('sync_cursor', 'u_1|10');
  const sync = new WatchSync(new Store(ls));
  const sent = sync.request('u_1');
  const n = sync.onResponse(sent, { now: 99, watch: [
    { key: '1|a.mkv', pos: 999, len: 1000, at: 40 },   // старее своего
    { key: '3|c.mkv', pos: 300, len: 900, at: 60 },    // новое
    { key: 'bad', pos: 1, len: 1, at: 70 }             // не ключ медиатеки
  ] });
  assert.equal(n, 1);
  assert.equal(JSON.parse(ls.getItem('watch:1|a.mkv')).pos, 100);
  assert.equal(JSON.parse(ls.getItem('watch:3|c.mkv')).pos, 300);
});

test('невыгруженное переживает перезапуск приложения', () => {
  const ls = memory();
  const first = new WatchSync(new Store(ls));
  first.onResponse(first.request('u_1'), { now: 1000, watch: [] });
  const mark = Number(ls.getItem('sync_cursor').split('|')[2]);
  ls.setItem('watch:1|a.mkv', JSON.stringify({ pos: 1, len: 0, at: mark + 5 })); // смотрели, ТВ выключили до отправки
  ls.setItem('watch:2|old.mkv', JSON.stringify({ pos: 1, len: 0, at: mark - 5 })); // это уже ушло раньше
  const sent = new WatchSync(new Store(ls)).request('u_1');
  assert.equal(sent.body.since, 1000);
  assert.deepEqual(sent.body.watch.map(e => e.key), ['1|a.mkv']);
});

test('курсор без третьего поля (старый) — после перезапуска берётся всё из хранилища', () => {
  const ls = memory();
  ls.setItem('sync_cursor', 'u_1|10');
  ls.setItem('watch:1|a.mkv', JSON.stringify({ pos: 1, len: 0, at: 50 }));
  assert.deepEqual(new WatchSync(new Store(ls)).request('u_1').body.watch.map(e => e.key), ['1|a.mkv']);
});

test('курсор пишется только после последней пачки', () => {
  const ls = memory();
  for (let i = 0; i < 503; i++) ls.setItem(`watch:${i}|a.mkv`, JSON.stringify({ pos: 1, len: 0, at: 100 + i }));
  const sync = new WatchSync(new Store(ls));
  const a = sync.request('u_1');
  assert.equal(a.more, true);
  assert.equal(a.body.watch.length, 500);
  sync.onResponse(a, { now: 1000, watch: [] });
  assert.equal(ls.getItem('sync_cursor'), null); // оборвись здесь — следующий запуск начнёт заново
  const b = sync.request('u_1'); // тот же процесс: очередь не пересобирается, уходит остаток
  assert.equal(b.body.since, 0);
  assert.equal(b.body.watch.length, 3);
  assert.equal(b.more, false);
  sync.onResponse(b, { now: 1100, watch: [] });
  assert.match(ls.getItem('sync_cursor'), /^u_1\|1100\|\d+$/);
  assert.equal(sync.hasPending(), false);
});

test('метка курсора не двигается, пока очередь не пуста', () => {
  const ls = memory();
  ls.setItem('sync_cursor', 'u_1|10|77');
  const sync = new WatchSync(new Store(ls));
  const sent = sync.request('u_1');
  sync.noteWatch({ key: '1|a.mkv', pos: 1, len: 0, at: 5 }); // заметили, пока шёл запрос
  sync.onResponse(sent, { now: 20, watch: [] });
  assert.equal(ls.getItem('sync_cursor'), 'u_1|20|77');
});

test('сервер новее — старая запись из очереди убирается', () => {
  const ls = memory();
  ls.setItem('sync_cursor', 'u_1|10|0');
  const sync = new WatchSync(new Store(ls));
  sync.request('u_1');
  sync.noteWatch({ key: '1|a.mkv', pos: 1, len: 0, at: 100 });
  sync.onResponse({ user: 'u_1', watch: [], more: false, startedAt: 1 }, { now: 30, watch: [{ key: '1|a.mkv', pos: 900, len: 0, at: 200 }] });
  assert.equal(sync.hasPending(), false);
  assert.equal(JSON.parse(ls.getItem('watch:1|a.mkv')).pos, 900);
});

test('eligible: только аккаунт Discord', () => {
  assert.equal(WatchSync.eligible('u_123'), true);
  assert.equal(WatchSync.eligible('shared'), false);
  assert.equal(WatchSync.eligible(null), false);
});

test('версия в «Что нового» совпадает с config.xml', () => {
  const SM = require('../js/changelog.js');
  const xml = fs.readFileSync(__dirname + '/../config.xml', 'utf8');
  assert.equal(xml.match(/<widget[^>]* version="([^"]+)"/)[1], SM.VERSION);
});

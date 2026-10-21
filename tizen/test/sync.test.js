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
  assert.equal(ls.getItem('sync_cursor'), 'u_1|500');
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

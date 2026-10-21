/*
 * Прогресс просмотра по аккаунту Discord (сервер /api/sync): ТВ, ПК и телефон продолжают с одного места.
 * Перенос shared/.../sync/WatchSync.kt, только прогресс файлов: выбор озвучки и субтитров у AVPlay свой (номера
 * дорожек телевизора), его ТВ не отправляет и не берёт. Тесты — test/sync.test.js.
 *
 * Хранилище — localStorage: "watch:<magnetId|path>" → { pos, len, at } (как было в 0.1), "sync_cursor" — "u_…|мс".
 */
(function (g) {
  'use strict';
  var SM = g.SM = g.SM || {};
  var BATCH = 500;
  var WATCH_PREFIX = 'watch:';

  /** Хранилище поверх localStorage-подобного объекта [ls] (в тестах — свой). */
  function Store(ls) { this.ls = ls; }
  Store.prototype.watch = function (key) {
    try { var w = JSON.parse(this.ls.getItem(WATCH_PREFIX + key) || 'null'); return w && typeof w.pos === 'number' ? { key: key, pos: w.pos, len: w.len || 0, at: w.at || 0 } : null; }
    catch (e) { return null; }
  };
  Store.prototype.putWatch = function (e) {
    try { this.ls.setItem(WATCH_PREFIX + e.key, JSON.stringify({ pos: e.pos, len: e.len, at: e.at })); } catch (x) { /* полно */ }
  };
  Store.prototype.allWatch = function () {
    var out = [];
    for (var i = 0; i < this.ls.length; i++) {
      var k = this.ls.key(i);
      if (k && k.indexOf(WATCH_PREFIX) === 0) { var w = this.watch(k.substring(WATCH_PREFIX.length)); if (w) out.push(w); }
    }
    return out;
  };
  Store.prototype.getCursor = function () { try { return this.ls.getItem('sync_cursor'); } catch (e) { return null; } };
  Store.prototype.setCursor = function (v) { try { this.ls.setItem('sync_cursor', v); } catch (e) { /* полно */ } };

  /** Очередь своих изменений и разбор ответа (WatchSync). */
  function WatchSync(store) { this.store = store; this.pending = new Map(); }

  WatchSync.eligible = function (userId) { return typeof userId === 'string' && /^u_\d{1,25}$/.test(userId); };

  WatchSync.prototype.noteWatch = function (e) { this.pending.delete(e.key); this.pending.set(e.key, e); };
  WatchSync.prototype.hasPending = function () { return this.pending.size > 0; };

  WatchSync.prototype.since = function (user) {
    var c = this.store.getCursor();
    if (!c) return -1;
    var i = c.indexOf('|');
    if (i < 0 || c.substring(0, i) !== user) return -1;
    var n = Number(c.substring(i + 1));
    return isFinite(n) && c.substring(i + 1) !== '' ? n : -1;
  };

  /** Тело POST /api/sync: первый раз для этого пользователя — всё своё и since = 0, дальше — новое. */
  WatchSync.prototype.request = function (user) {
    var since = this.since(user), self = this;
    if (since < 0) {
      this.pending.clear();
      this.store.allWatch().sort(function (a, b) { return b.at - a.at; }).forEach(function (e) { self.pending.set(e.key, e); });
    }
    var w = [];
    this.pending.forEach(function (e) { if (w.length < BATCH) w.push(e); });
    return {
      user: user, watch: w, more: this.pending.size > w.length,
      body: { since: Math.max(0, since), watch: w.map(function (e) { return { key: e.key, pos: e.pos, len: e.len, at: e.at }; }), tracks: [] }
    };
  };

  /** Ответ сервера: новее своего ложится в хранилище. Возвращает, сколько записей поменялось. */
  WatchSync.prototype.onResponse = function (sent, res) {
    var self = this, changed = 0;
    sent.watch.forEach(function (e) { if (self.pending.get(e.key) === e) self.pending.delete(e.key); });
    (Array.isArray(res && res.watch) ? res.watch : []).forEach(function (o) {
      if (!o || typeof o !== 'object') return;
      var e = { key: String(o.key || ''), pos: o.pos == null ? -1 : Number(o.pos), len: Number(o.len) || 0, at: Number(o.at) || 0 };
      if (e.key.indexOf('|') < 0 || !(e.pos >= 0) || e.at <= 0) return;
      var mine = self.store.watch(e.key);
      if (mine && mine.at >= e.at) return;
      var p = self.pending.get(e.key);
      if (p && p.at >= e.at) return;
      self.store.putWatch(e); changed++;
    });
    var now = Number(res && res.now) || 0;
    if (now > 0) this.store.setCursor(sent.user + '|' + now);
    return changed;
  };

  // ---- на телевизоре: когда синхронизировать ----

  function client() {
    var sync = new WatchSync(new Store(g.localStorage)), user = null, busy = false, timer = 0;
    /** Отправить своё и забрать чужое; [done] — сколько поменялось (для перерисовки «Продолжить»). */
    function run(done) {
      if (!user || busy) return;
      busy = true;
      var sent = sync.request(user);
      SM.api.post('/api/sync', sent.body).then(function (res) {
        busy = false;
        var n = sync.onResponse(sent, res);
        if (sent.more) run(done); else if (done) done(n);
      }).catch(function (e) {
        busy = false;
        if (e && e.code === 403) user = null; // вошли не через Discord
      });
    }
    return {
      /** Пользователь из /api/session: синхронизация только для входа через Discord. */
      setUser: function (id) {
        user = WatchSync.eligible(id) ? id : null;
        clearInterval(timer);
        if (user) timer = setInterval(function () { if (sync.hasPending()) run(); }, 60000);
      },
      note: function (key, pos, len, at) { if (user) sync.noteWatch({ key: key, pos: pos, len: len, at: at }); },
      run: run,
      active: function () { return user != null; }
    };
  }

  SM.syncCore = { Store: Store, WatchSync: WatchSync };
  if (typeof module === 'object' && module.exports) module.exports = SM.syncCore;
  else SM.sync = client();
})(typeof window !== 'undefined' ? window : global);

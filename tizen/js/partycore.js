/*
 * Совместный просмотр без сети: комната, разбор ответов и потока сервера, часы, движок синхронизации плеера.
 * Перенос shared/.../core/party (PartyModels.kt, PartyProtocol.kt, PartySyncEngine.kt) один в один;
 * тесты — test/party.test.js. Сеть и экран — в party.js. Только ES2017 (Tizen 4.0).
 *
 * Отличие ТВ: AVPlay не меняет скорость плавно, поэтому малый дрейф (0,3–2 с) не выравнивается — только перемотка.
 */
(function (g) {
  'use strict';
  var SM = g.SM = g.SM || {};

  // ---- sha-256 (crypto.subtle в виджете Tizen 4.0 недоступен: страница не https) ----

  var K256 = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ];

  function utf8(s) {
    var out = [];
    var e = unescape(encodeURIComponent(s));
    for (var i = 0; i < e.length; i++) out.push(e.charCodeAt(i));
    return out;
  }

  function sha256hex(text) {
    var bytes = utf8(text), bitLen = bytes.length * 8;
    bytes.push(0x80);
    while (bytes.length % 64 !== 56) bytes.push(0);
    for (var s = 56; s >= 0; s -= 8) bytes.push(s >= 32 ? Math.floor(bitLen / Math.pow(2, s)) & 0xff : (bitLen >>> s) & 0xff);
    var H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    var w = new Array(64);
    function rotr(x, n) { return (x >>> n) | (x << (32 - n)); }
    for (var off = 0; off < bytes.length; off += 64) {
      for (var i = 0; i < 16; i++) w[i] = (bytes[off + i * 4] << 24) | (bytes[off + i * 4 + 1] << 16) | (bytes[off + i * 4 + 2] << 8) | bytes[off + i * 4 + 3];
      for (i = 16; i < 64; i++) {
        var s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
        var s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
      }
      var a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], gg = H[6], hh = H[7];
      for (i = 0; i < 64; i++) {
        var t1 = (hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & gg)) + K256[i] + w[i]) | 0;
        var t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
        hh = gg; gg = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
      H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + gg) | 0; H[7] = (H[7] + hh) | 0;
    }
    return H.map(function (x) { return ('0000000' + (x >>> 0).toString(16)).slice(-8); }).join('');
  }

  // ---- часы сервера (PartyClock) ----

  function Clock() { this.reset(); }
  Clock.prototype.reset = function () { this.offsetMs = 0; this.bestRttMs = Infinity; };
  Clock.prototype.addSample = function (sent, recv, server) {
    var rtt = Math.max(0, recv - sent);
    if (rtt < this.bestRttMs) { this.bestRttMs = rtt; this.offsetMs = server - (sent + Math.floor(rtt / 2)); }
  };
  Clock.prototype.serverNow = function (localNow) { return (localNow == null ? Date.now() : localNow) + this.offsetMs; };

  // ---- состояние и комната (PartyState, PartyRoom) ----

  var SHARED_USER = 'shared';
  var IDLE = { playing: false, pos: 0, rate: 1, anchorServerMs: 0, seq: 0, waiting: false };

  /** Где комната, мс, при часах сервера [serverNowMs]. */
  function targetMs(s, serverNowMs) {
    var base = s.pos * 1000;
    if (!s.playing || s.waiting) return Math.floor(base);
    return Math.floor(base + Math.max(0, serverNowMs - s.anchorServerMs) * s.rate);
  }

  function copy(o, patch) {
    var r = {};
    Object.keys(o).forEach(function (k) { r[k] = o[k]; });
    Object.keys(patch || {}).forEach(function (k) { r[k] = patch[k]; });
    return r;
  }

  function emptyRoom() {
    return { id: null, hostId: null, hostKey: null, hostName: '', hostOnly: false, file: null, state: IDLE, members: [], pendingFileKey: null };
  }

  var room = {
    empty: emptyRoom,
    isHost: function (r, myUserId, myKey) {
      return myUserId != null && myUserId === r.hostId && (r.hostId !== SHARED_USER || r.hostKey == null || r.hostKey === myKey);
    },
    isCreator: function (r, m) {
      return m.userId === r.hostId && (r.hostId !== SHARED_USER || r.hostKey == null || m.key === r.hostKey);
    },
    othersBuffering: function (r, myKey) { return r.members.some(function (m) { return m.buffering && m.key !== myKey; }); },
    putFile: function (r, f) { return copy(r, { file: f, pendingFileKey: f.key }); },
    fileRejected: function (r) { return copy(r, { pendingFileKey: null }); },
    /** Ответ сервера с комнатой: состояние — только более новое по seq, участники — если пришёл их список. */
    merge: function (r, u) {
      var head = { id: u.id || r.id, hostId: u.hostId, hostKey: u.hostKey, hostName: u.hostName, hostOnly: u.hostOnly, members: u.members || r.members };
      // Ведущий поставил фильм, сервер ещё не подтвердил: фильм и время в старых ответах — прежние, не берём их
      if (r.pendingFileKey != null && (u.file ? u.file.key : null) !== r.pendingFileKey) return copy(r, head);
      head.file = u.file;
      head.state = u.state && u.state.seq >= r.state.seq ? u.state : r.state;
      head.pendingFileKey = null;
      return copy(r, head);
    },
    reduce: function (r, e) {
      switch (e.type) {
        case 'init': return e.room ? room.merge(r, e.room) : r;
        case 'state_changed': return room.merge(r, e.room);
        case 'member_joined': return copy(r, { members: r.members.filter(function (m) { return m.key !== e.member.key; }).concat([e.member]) });
        case 'member_left': return copy(r, { members: r.members.filter(function (m) { return m.key !== e.key; }) });
        default: return r;
      }
    }
  };

  // ---- протокол (PartyProtocol) ----

  var ROOMS = '/api/rooms';

  function fileRef(key, title) { return { key: key, title: title || '', adId: Number(String(key).split('|')[0]) || 0 }; }

  function nonEmpty(v) { return typeof v === 'string' && v.trim() ? v : null; }

  function parseMember(m) {
    return {
      key: String(m.key || ''), userId: String(m.id || ''), name: nonEmpty(m.name) || 'Гость', buffering: m.buffering === true,
      avatar: typeof m.avatar === 'string' && m.avatar.indexOf('https://') === 0 ? m.avatar : null
    };
  }

  function parseRoom(o) {
    var s = o.state && typeof o.state === 'object' ? o.state : null;
    var f = o.file && typeof o.file === 'object' && nonEmpty(o.file.key) ? fileRef(o.file.key, String(o.file.name || '')) : null;
    return {
      id: nonEmpty(o.id), hostId: nonEmpty(o.host_id), hostKey: nonEmpty(o.host_key), hostName: String(o.host_name || ''),
      hostOnly: o.controls === 'host', file: f,
      state: s ? { playing: s.playing === true, pos: Number(s.pos) || 0, rate: s.rate == null ? 1 : Number(s.rate) || 1,
        anchorServerMs: Number(s.anchor_server_ms) || 0, seq: Number(s.seq) || 0, waiting: s.waiting === true } : null,
      members: Array.isArray(o.members) ? o.members.filter(function (m) { return m && typeof m === 'object'; }).map(parseMember) : null,
      serverTime: s ? Number(s.server_time) || 0 : 0
    };
  }

  function parseEvent(text) {
    var msg;
    try { msg = JSON.parse(text); } catch (e) { return null; }
    if (!msg || typeof msg !== 'object') return null;
    var d = msg.data && typeof msg.data === 'object' ? msg.data : null;
    switch (msg.event) {
      case 'init': return { type: 'init', myUserId: msg.member ? nonEmpty(msg.member.id) : null, room: msg.room ? parseRoom(msg.room) : null };
      case 'state_changed': return d ? { type: 'state_changed', room: parseRoom(d) } : null;
      case 'member_joined': return d && d.member ? { type: 'member_joined', member: parseMember(d.member) } : null;
      case 'member_left': return d && nonEmpty(d.key) ? { type: 'member_left', key: d.key } : null;
      default: return null;
    }
  }

  var protocol = {
    ROOMS: ROOMS,
    roomPath: function (id) { return ROOMS + '/' + id; },
    joinPath: function (id) { return ROOMS + '/' + id + '/join'; },
    leavePath: function (id) { return ROOMS + '/' + id + '/leave'; },
    actionPath: function (id) { return ROOMS + '/' + id + '/action'; },
    eventsPath: function (id, clientId) { return ROOMS + '/' + id + '/events?client_id=' + clientId; },
    newClientId: function (prefix) {
      var s = '';
      for (var i = 0; i < 12; i++) s += Math.floor(Math.random() * 16).toString(16);
      return prefix + '_' + s;
    },
    clientKey: function (clientId) { return sha256hex(clientId).substring(0, 16); },
    normalizeCode: function (code) { return String(code || '').trim().toLowerCase().replace(/[^0-9a-zа-яё]/g, ''); },
    createBody: function (clientId) { return { controls: 'all', client_id: clientId }; },
    clientBody: function (clientId) { return { client_id: clientId }; },
    play: function (ms) { return { type: 'play', pos: ms / 1000 }; },
    pause: function (ms) { return { type: 'pause', pos: ms / 1000 }; },
    seek: function (ms) { return { type: 'seek', pos: ms / 1000 }; },
    buffering: function (loading) { return { type: 'buffering', buffering: loading }; },
    changeFile: function (f) { return { type: 'change_file', file: { key: f.key, name: f.title, ad_id: f.adId } }; },
    roomOf: function (res) { return res && res.room && typeof res.room === 'object' ? parseRoom(res.room) : null; },
    hostIdOf: function (res) { return res && res.room ? nonEmpty(res.room.host_id) : null; },
    memberIdOf: function (res) { return res && res.member ? nonEmpty(res.member.id) : null; },
    parseRoom: parseRoom,
    parseEvent: parseEvent,
    parseEventLine: function (line) { return line.indexOf('data:') === 0 ? parseEvent(line.substring(5).trim()) : null; },
    fileRef: fileRef
  };

  // ---- движок синхронизации (PartySyncEngine) ----

  var TICK_MS = 400, OWN_MS = 2500, FROZEN_MS = 1000, WAIT_NOTICE_MS = 2000, SEEK_NOTICE_MS = 3000;
  var TOLERANCE_MS = 300, HARD_SEEK_MS = 2000;

  /**
   * [link] — комната: { state(), canControl(), canSeek(), targetMs(), othersBuffering(), takeStartedByMe(key),
   *   play(ms), pause(ms), seek(ms), reportBuffering(loading) }.
   * Плеер для tick/idle: { timeMs, busy, engineMs, seekStamp, paused, running, applyPaused(p), seekTo(ms) } (поля читаются).
   */
  function Engine(link, log, notice) {
    this.link = link;
    this.log = log || function () {};
    this.notice = notice || function () {};
    this.lastSeq = -1; this.ownUntil = 0; this.prevT = 0; this.prevP = true; this.prevAt = 0;
    this.pendingAt = 0; this.pendingTo = 0; this.pendingT = -1; this.stalledSince = 0;
    this.lastEngineMs = null; this.engineMovedAt = 0; this.localHold = false; this.seenSeek = null;
    this.applied = null; this.waitSince = 0; this.waitNoticed = false; this.lastTimeMs = 0;
  }

  Engine.prototype.idle = function (player) {
    if (this.seenSeek == null) this.seenSeek = player.seekStamp;
    this.lastSeq = -1; this.pendingAt = 0; this.stalledSince = 0; this.lastEngineMs = null;
    this.applied = null; this.waitSince = 0; this.waitNoticed = false;
    this.link.reportBuffering(false);
  };

  Engine.prototype.tick = function (player, key, now) {
    var link = this.link;
    if (this.seenSeek == null) this.seenSeek = player.seekStamp;
    if (player.busy) return;
    var t = player.timeMs, p = player.paused;
    this.lastTimeMs = t;

    // Пользователь перемотал здесь: комната идёт туда же (по метке перемотки плеера, а не по скачку времени)
    var stamp = player.seekStamp;
    if (stamp !== this.seenSeek) {
      this.seenSeek = stamp;
      var expected = this.prevP ? this.prevT : this.prevT + (now - this.prevAt);
      if (this.lastSeq >= 0 && Math.abs(t - expected) > 1500 && link.canSeek()) {
        this.log('[party] local seek at ' + t);
        if (p) link.pause(t); else link.play(t);
        this.lastSeq = link.state().seq + 1;
        this.pendingAt = now; this.pendingTo = t; this.pendingT = -1;
        this.ownUntil = now + OWN_MS;
        this.prevT = t; this.prevP = p; this.prevAt = now;
        link.reportBuffering(!p);
        return;
      }
    }

    // Наша перемотка к комнате: готова, когда движок снова идёт вперёд
    if (this.pendingAt > 0) {
      var e0 = player.engineMs;
      var d = e0 - this.pendingT;
      var moved = this.pendingT >= 0 && d >= 100 && d <= 2000 && player.running &&
        (Math.abs(e0 - this.pendingTo) < 5000 || now - this.pendingAt > 3000);
      if (moved || p || now - this.pendingAt > 30000) {
        this.log('[party] seek done in ' + (now - this.pendingAt) + ' ms');
        this.pendingAt = 0; this.ownUntil = now + OWN_MS;
      } else this.pendingT = e0;
    }
    // Сами грузимся, хотя должны играть: через 1 с комната ждёт и нас (стоящее время без паузы — тоже загрузка)
    var e = player.engineMs;
    if (p || e !== this.lastEngineMs) { this.lastEngineMs = e; this.engineMovedAt = now; }
    var frozen = now - this.engineMovedAt >= FROZEN_MS;
    this.stalledSince = !p && this.pendingAt === 0 && (!player.running || frozen)
      ? (this.stalledSince > 0 ? this.stalledSince : frozen ? this.engineMovedAt : now) : 0;
    var loading = this.pendingAt > 0 || (this.stalledSince > 0 && now - this.stalledSince > 1000);
    link.reportBuffering(loading && link.state().playing);

    // 1. Я только что поставил этот фильм в комнату: комната стартует с моего места
    if (link.takeStartedByMe(key)) {
      if (p) link.pause(t); else link.play(t);
      this.lastSeq = link.state().seq + 1; this.ownUntil = now + OWN_MS;
      if (!p && !player.running) { this.pendingAt = now; this.pendingTo = t; this.pendingT = -1; }
      this.prevT = t; this.prevP = p; this.prevAt = now;
      return;
    }

    // 2. Пауза / пуск здесь: ведущий — всей комнате, гость — только себе
    if (this.lastSeq >= 0 && !loading && p !== this.prevP) {
      if (link.canControl()) {
        this.log('[party] local ' + (p ? 'pause' : 'play') + ' at ' + t);
        if (p) link.pause(t); else link.play(t);
        this.lastSeq = link.state().seq + 1;
        this.ownUntil = now + OWN_MS;
        this.prevT = t; this.prevP = p; this.prevAt = now;
        return;
      }
      this.localHold = p;
      this.log(p ? '[party] guest pause (local only)' : '[party] guest goes on: back to the room');
      if (!p) this.lastSeq = -1;
    }

    // 3. Следуем за комнатой
    var s = link.state();
    var target = link.targetMs();
    var othersLoading = link.othersBuffering();
    var wantPaused = !s.playing || (s.waiting && !loading && othersLoading) || this.localHold;
    var nt = t, np = p;
    if (s.playing && s.waiting && othersLoading) {
      if (this.waitSince === 0) this.waitSince = now;
      else if (!this.waitNoticed && now - this.waitSince >= WAIT_NOTICE_MS) { this.waitNoticed = true; this.notice({ type: 'waiting' }); }
    } else { this.waitSince = 0; this.waitNoticed = false; }
    if (this.lastSeq < 0 || s.seq > this.lastSeq) {
      var before = this.lastSeq >= 0 ? this.applied : null;
      this.applied = s;
      if (before && !link.canControl()) { var n = noticeOf(before, s); if (n) this.notice(n); }
      this.lastSeq = s.seq;
      var alreadyGoing = this.pendingAt > 0 && Math.abs(this.pendingTo - target) < 1000;
      var needSeek = Math.abs(t - target) > 1000 && !alreadyGoing;
      var pauseNow = needSeek && s.playing && !this.localHold ? false : wantPaused;
      if (p !== pauseNow && !(loading && !pauseNow)) { player.applyPaused(pauseNow); np = pauseNow; }
      if (needSeek) nt = this.seekRoom(player, target, now);
      this.ownUntil = now + OWN_MS;
      this.log('[party] apply seq=' + s.seq + ' playing=' + s.playing + ' waiting=' + s.waiting + ' target=' + target + ' local=' + t);
    } else if (s.playing && s.waiting && !loading && !p && othersLoading) {
      player.applyPaused(true); np = true; this.ownUntil = now + 1000;
      this.log('[party] ready, waiting for the others at ' + t);
    } else if (s.playing && !s.waiting && !p && !loading && now > this.ownUntil && player.running) {
      // Плавной подстройки скорости у AVPlay нет: до 2 с расхождения терпим, дальше — перемотка
      if (Math.abs(t - target) > HARD_SEEK_MS) {
        nt = this.seekRoom(player, target, now); this.ownUntil = now + OWN_MS;
        this.log('[party] drift seek local=' + t + ' target=' + target);
      }
    }
    this.prevT = nt; this.prevP = np; this.prevAt = now;
  };

  Engine.prototype.seekRoom = function (player, target, now) {
    player.seekTo(target);
    this.pendingAt = now; this.pendingTo = target; this.pendingT = -1;
    this.seenSeek = player.seekStamp;
    return target;
  };

  /** Что изменил ведущий: перемотка (место не там, куда пришло бы само), иначе пауза или пуск. */
  function noticeOf(before, s) {
    var ran = before.playing && !before.waiting ? Math.floor(Math.max(0, s.anchorServerMs - before.anchorServerMs) * before.rate) : 0;
    var expected = Math.floor(before.pos * 1000) + ran;
    var pos = Math.floor(s.pos * 1000);
    if (Math.abs(pos - expected) > SEEK_NOTICE_MS) return { type: 'seeked', posMs: pos };
    if (before.playing && !s.playing) return { type: 'paused' };
    if (!before.playing && s.playing) return { type: 'resumed' };
    return null;
  }

  SM.partycore = {
    sha256hex: sha256hex, Clock: Clock, targetMs: targetMs, room: room, protocol: protocol, Engine: Engine,
    IDLE: IDLE, SHARED_USER: SHARED_USER, TICK_MS: TICK_MS, TOLERANCE_MS: TOLERANCE_MS
  };
  if (typeof module === 'object' && module.exports) module.exports = SM.partycore;
})(typeof window !== 'undefined' ? window : global);

/*
 * Совместный просмотр на ТВ: одна комната на приложение (как android/.../party/Party.kt). Сервер (/api/rooms) хранит,
 * что и где играет; изменения приходят потоком событий (SSE). Логика комнаты — partycore.js, здесь сеть и плеер.
 *
 * Поток читается XHR, а не EventSource: EventSource не умеет слать X-Device-Token, а cookie у ТВ нет.
 */
(function (g) {
  'use strict';
  var SM = g.SM, api = SM.api, core = SM.partycore, P = core.protocol, R = core.room;

  var clientId = P.newClientId('tv');
  var party = {
    room: R.empty(),
    roomId: null,
    myUserId: null,
    myKey: P.clientKey(clientId),
    connected: false,
    notice: null,          // почему приложение само вышло из комнаты (показывается на вкладке «Вместе»)
    startedByMe: null,     // я только что поставил этот фильм: мой плеер запускает комнату со своего места
    watching: 0,           // плеер показывает фильм комнаты
    clock: new core.Clock(),
    listeners: []
  };

  function changed() { party.listeners.forEach(function (f) { try { f(); } catch (e) { /* экран уже закрыт */ } }); }

  party.inRoom = function () { return party.roomId != null; };
  party.isHost = function () { return R.isHost(party.room, party.myUserId, party.myKey); };
  party.isGuest = function () { return party.inRoom() && !party.isHost(); };
  party.canControl = function () { return party.inRoom() && party.isHost(); };
  party.isCreator = function (m) { return R.isCreator(party.room, m); };
  party.targetMs = function () { return core.targetMs(party.room.state, party.clock.serverNow()); };
  party.othersBuffering = function () { return R.othersBuffering(party.room, party.myKey); };
  party.onChange = function (f) { party.listeners.push(f); return function () { party.listeners = party.listeners.filter(function (x) { return x !== f; }); }; };

  /** Запрос, ответ которого везёт server_time: ещё одна точка для часов. */
  function timed(promise) {
    var t0 = Date.now();
    return promise.then(function (res) {
      var u = P.roomOf(res);
      if (u && u.serverTime > 0) party.clock.addSample(t0, Date.now(), u.serverTime);
      return res;
    });
  }

  function roomOf(res) {
    var u = P.roomOf(res);
    if (!u || !u.id) throw { message: 'Сервер не прислал комнату.' };
    return u;
  }

  party.create = function () {
    return timed(api.post(P.ROOMS, P.createBody(clientId), false)).then(function (res) {
      party.myUserId = P.hostIdOf(res);
      return enter(roomOf(res));
    });
  };

  party.join = function (code) {
    var id = P.normalizeCode(code);
    if (!id) return Promise.reject({ message: 'Введите код комнаты.' });
    return timed(api.post(P.joinPath(id), P.clientBody(clientId))).then(function (res) {
      var me = P.memberIdOf(res);
      if (me) party.myUserId = me;
      return enter(roomOf(res));
    });
  };

  party.leave = function () {
    var id = party.roomId;
    if (!id) return;
    stopStream();
    party.roomId = null; party.startedByMe = null; party.room = R.empty();
    reported = false; outbox = [];
    api.post(P.leavePath(id), P.clientBody(clientId)).catch(function () {});
    changed();
  };

  function enter(update) {
    stopStream();
    party.notice = null;
    party.clock.reset();
    reported = false; outbox = [];
    party.room = R.merge(R.empty(), update);
    party.roomId = update.id;
    changed();
    // Ещё несколько точек для часов: побеждает самый быстрый ответ
    var id = update.id, n = 0;
    function more() {
      if (n++ >= 3 || party.roomId !== id) return Promise.resolve();
      return timed(api.get(P.roomPath(id))).catch(function () {}).then(more);
    }
    return more().then(function () { if (party.roomId === id) startStream(id); });
  }

  /** Ведущий ставит фильм в комнату (сервер ставит его на паузу в начале; мой плеер запустит). */
  party.putFilm = function (key, title) {
    if (!party.inRoom() || !party.isHost() || (party.room.file && party.room.file.key === key)) return;
    var f = P.fileRef(key, title);
    party.room = R.putFile(party.room, f);
    party.startedByMe = key;
    action(P.changeFile(f));
    changed();
  };

  party.play = function (ms) { action(P.play(ms)); };
  party.pause = function (ms) { action(P.pause(ms)); };
  party.seek = function (ms) { if (party.canControl()) action(P.seek(ms)); };

  /** Ведущий закрыл фильм комнаты: через 1,5 с, если никто его не открыл снова, комната на паузу. */
  party.pauseIfNobodyWatches = function (ms) {
    setTimeout(function () {
      if (party.watching === 0 && party.inRoom() && party.room.state.playing) party.pause(ms);
    }, 1500);
  };

  var reported = false;
  party.reportBuffering = function (loading) {
    if (!party.roomId || loading === reported) return;
    reported = loading;
    send(P.buffering(loading));
  };

  function action(body) { if (party.roomId && party.canControl()) send(body); }

  // Действия по одному, по порядку: «поставить фильм» должно дойти раньше «играть отсюда»
  var outbox = [], sending = false;
  function send(body) {
    body.client_id = clientId;
    outbox.push({ id: party.roomId, body: body });
    pump();
  }
  function pump() {
    if (sending || !outbox.length) return;
    var job = outbox.shift();
    sending = true;
    timed(api.post(P.actionPath(job.id), job.body)).then(function (res) {
      var u = P.roomOf(res);
      if (u && party.roomId === job.id) { party.room = R.merge(party.room, u); changed(); }
    }).catch(function () {
      if (job.body.type === 'change_file') party.room = R.fileRejected(party.room);
    }).then(function () { sending = false; pump(); });
  }

  // ---- поток событий ----

  var xhr = null, retryTimer = 0;

  function startStream(id) {
    var failures = 0;
    function connect() {
      if (party.roomId !== id) return;
      var x = new XMLHttpRequest(), seen = 0, buf = '';
      xhr = x;
      x.open('GET', api.BASE + P.eventsPath(id, clientId), true);
      x.setRequestHeader('Accept', 'text/event-stream');
      var t = api.token();
      if (t) x.setRequestHeader('X-Device-Token', t);
      x.onprogress = function () {
        if (xhr !== x) return;
        if (!party.connected && x.status >= 200 && x.status < 300) { party.connected = true; failures = 0; changed(); }
        var text = x.responseText;
        buf += text.substring(seen);
        seen = text.length;
        var lines = buf.split('\n');
        buf = lines.pop();
        var any = false;
        lines.forEach(function (line) {
          var e = P.parseEventLine(line.replace(/\r$/, ''));
          if (!e) return;
          if (e.type === 'init' && e.myUserId) party.myUserId = e.myUserId;
          party.room = R.reduce(party.room, e);
          any = true;
        });
        if (any) changed();
        // Ответ копится в памяти: после 2 МБ переподключаемся
        if (seen > 2 * 1024 * 1024) { x.abort(); xhr = null; party.connected = false; connect(); }
      };
      x.onloadend = function () {
        if (xhr !== x) return;
        xhr = null;
        party.connected = false;
        if (party.roomId !== id) return;
        if (x.status === 404) { party.leave(); party.notice = 'Комната закрыта.'; changed(); return; }
        if (x.status === 401 || x.status === 403) { party.leave(); party.notice = 'Вы вышли из комнаты: вход больше не действует. Войдите снова.'; changed(); return; }
        changed();
        retryTimer = setTimeout(connect, Math.min(15000, 1000 * Math.pow(2, Math.min(failures, 4))));
        failures++;
      };
      x.send(null);
    }
    connect();
  }

  function stopStream() {
    clearTimeout(retryTimer);
    if (xhr) { var x = xhr; xhr = null; try { x.abort(); } catch (e) { /* уже закрыт */ } }
    party.connected = false;
  }

  // ---- плеер в комнате (PlayerPartySync) ----

  var link = {
    state: function () { return party.room.state; },
    canControl: party.canControl,
    canSeek: party.canControl,
    targetMs: party.targetMs,
    othersBuffering: party.othersBuffering,
    takeStartedByMe: function (key) { if (party.startedByMe !== key) return false; party.startedByMe = null; return true; },
    play: party.play, pause: party.pause, seek: party.seek, reportBuffering: party.reportBuffering
  };

  function hostWho() { return party.room.hostName || 'Создатель комнаты'; }

  function noticeText(n) {
    switch (n.type) {
      case 'paused': return hostWho() + ' ставит паузу';
      case 'resumed': return hostWho() + ' продолжает';
      case 'seeked': return hostWho() + ' перематывает на ' + SM.ui.clock(n.posMs);
      case 'waiting':
        var names = party.room.members.filter(function (m) { return m.buffering && m.key !== party.myKey; }).map(function (m) { return m.name; });
        return names.length ? 'Ждём, пока загрузится у ' + names.join(', ') : null;
      default: return null;
    }
  }

  /**
   * Держит плеер в фильме комнаты, такт 400 мс. [cb]: { player() — SyncPlayer или null (ещё открывается),
   * key() — ключ того, что играет, keys() — ключи плейлиста, title(), playIndex(i), notice(text), log(text) }.
   */
  party.attach = function (cb) {
    var engine = new core.Engine(link, cb.log, function (n) { var t = noticeText(n); if (t) cb.notice(t); });
    var lastKey = null, switchedTo = null, episodeNotice = null, timer = 0, lastPlayer = null;
    party.watching++;
    function step() {
      var p = cb.player(), key = cb.key(), keys = cb.keys();
      if (p) lastPlayer = p;
      // Ведущий перешёл к другой серии: комната за ним
      if (key !== lastKey) {
        lastKey = key;
        var f = party.room.file;
        if (f && key && key !== f.key && party.isHost() && keys.indexOf(f.key) >= 0) party.putFilm(key, cb.title());
      }
      // Ведущий выбрал другую серию: этот плеер переходит на неё, один раз
      var rk = party.room.file ? party.room.file.key : null;
      if (rk == null || rk === key) switchedTo = null;
      else if (party.inRoom() && switchedTo !== rk && p) {
        var i = keys.indexOf(rk);
        if (i >= 0) {
          switchedTo = rk;
          if (!party.isHost()) episodeNotice = hostWho() + ' включает «' + party.room.file.title + '»';
          cb.playIndex(i);
          return;
        }
      }
      if (party.inRoom() && rk === key && key && p) {
        if (episodeNotice) { cb.notice(episodeNotice); episodeNotice = null; }
        engine.tick(p, key, Date.now());
      } else if (p) engine.idle(p);
    }
    timer = setInterval(step, core.TICK_MS);
    return {
      /** Плеер закрывается: если ведущий закрыл фильм комнаты, комната встаёт на паузу. */
      stop: function () {
        clearInterval(timer);
        party.watching--;
        if (lastPlayer) engine.idle(lastPlayer);
        if (party.isHost() && party.room.file && party.room.file.key === lastKey) party.pauseIfNobodyWatches(engine.lastTimeMs);
      },
      /** Это фильм комнаты, а я гость: перематывать и переключать серии нельзя. */
      guestLocked: function () { return party.isGuest() && party.room.file != null && party.room.file.key === cb.key(); }
    };
  };

  SM.party = party;
})(window);

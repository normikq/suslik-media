/*
 * Suslik Media в Discord Activity — пульт комнаты голосового канала (docs/DISCORD-ACTIVITY.md).
 * Видео здесь не играет: раздачи тяжёлые (MKV, DTS), их играют приложения. Здесь — что смотрим, кто ведущий,
 * медиатека («Включить в комнате», «Предложить»), голосование, «Следующая серия» и «Открыть в приложении».
 * Медиатека разбирается тем же model.js, что у ТВ (копия tizen/js/model.js; тест сверяет копии).
 */
(function () {
  'use strict';
  var D = window.SMDiscord, model = window.SM.model;
  var app = document.getElementById('app');

  var S = {
    clientId: '', token: '', site: '', me: null,
    room: null, offset: 0,
    items: [], shown: [], versions: {}, byId: {},
    tab: 'now', query: '', sheet: null,
    stream: null, streamOk: false, pollTimer: 0, retry: 0
  };

  // ---- значки: белые линии, как в приложениях ----
  var ICONS = {
    play: '<path d="M7 4.5v15l12-7.5z" fill="currentColor" stroke="none"/>',
    pause: '<rect x="6" y="5" width="4" height="14" rx="1.2" fill="currentColor" stroke="none"/><rect x="14" y="5" width="4" height="14" rx="1.2" fill="currentColor" stroke="none"/>',
    next: '<path d="M5 5v14l10-7z" fill="currentColor" stroke="none"/><path d="M18.5 5v14"/>',
    devices: '<rect x="2.5" y="4" width="13" height="10" rx="2"/><path d="M6 18h6M9 14v4"/><rect x="17" y="8" width="5" height="11" rx="1.5"/>',
    tv: '<rect x="3" y="5" width="18" height="12" rx="2"/><path d="M8 21h8M12 17v4"/>',
    vote: '<rect x="4" y="4" width="16" height="16" rx="3.5"/><path d="M8.5 12.2l2.4 2.4 4.8-5"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    close: '<path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/>',
    search: '<circle cx="11" cy="11" r="6.2"/><path d="M20 20l-4.6-4.6"/>',
    crown: '<path d="M4.5 18.5h15"/><path d="M5.5 15.5L4 7.5l4.8 3.6L12 5l3.2 6.1L20 7.5l-1.5 8z"/>',
    users: '<circle cx="9" cy="8" r="3.4"/><path d="M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6"/><path d="M15.5 4.8a3.4 3.4 0 010 6.4M17.5 14.3c2 .9 3.5 3 3.5 5.7"/>',
    film: '<rect x="3" y="4" width="18" height="16" rx="2.5"/><path d="M7.5 4v16M16.5 4v16M3 9h4.5M3 15h4.5M16.5 9H21M16.5 15H21"/>',
    grid: '<rect x="4" y="4" width="6.5" height="6.5" rx="1.5"/><rect x="13.5" y="4" width="6.5" height="6.5" rx="1.5"/><rect x="4" y="13.5" width="6.5" height="6.5" rx="1.5"/><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.5"/>',
    copy: '<rect x="8.5" y="8.5" width="11.5" height="11.5" rx="2.2"/><path d="M15.5 8.5V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7.5a2 2 0 002 2h2.5"/>',
    loading: '<path d="M12 3a9 9 0 109 9"/>'
  };
  function icon(name) {
    var s = document.createElement('span');
    s.className = 'ico';
    s.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + ICONS[name] + '</svg>';
    return s;
  }

  /** Элемент: h('div', { class, text, on: { click } }, [дети]). Текст — только через textContent. */
  function h(tag, attrs, kids) {
    var e = document.createElement(tag);
    attrs = attrs || {};
    Object.keys(attrs).forEach(function (k) {
      var v = attrs[k];
      if (v == null || v === false) return;
      if (k === 'class') e.className = v;
      else if (k === 'text') e.textContent = v;
      else if (k === 'on') Object.keys(v).forEach(function (ev) { e.addEventListener(ev, v[ev]); });
      else if (k === 'style') e.setAttribute('style', v);
      else e.setAttribute(k, v === true ? '' : v);
    });
    (kids || []).forEach(function (c) { if (c != null && c !== false) e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); });
    return e;
  }
  function btn(cls, iconName, text, onClick, extra) {
    var a = { class: 'btn ' + (cls || ''), type: 'button', on: { click: onClick } };
    if (extra) Object.keys(extra).forEach(function (k) { a[k] = extra[k]; });
    return h('button', a, [iconName ? icon(iconName) : null, text || null]);
  }

  // ---- уведомления ----
  var toastTimer = 0;
  function toast(text, bad) {
    var t = document.getElementById('toast');
    t.textContent = text;
    t.className = 'toast' + (bad ? ' bad' : '');
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.hidden = true; }, 4200);
  }
  function errText(e) {
    if (!e) return 'Что-то пошло не так.';
    if (typeof e === 'string') return e;
    return e.userMessage || e.message || 'Что-то пошло не так.';
  }

  // ---- сеть: ключ Activity в заголовке (cookie в iframe Discord не живут) ----
  function api(path, body, noAuth, retried) {
    var opts = { method: body === undefined ? 'GET' : 'POST', headers: {} };
    if (!noAuth && S.token) opts.headers.Authorization = 'Bearer ' + S.token;
    if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
    return fetch(path, opts).then(function (res) {
      if (res.status === 401 && !noAuth && !retried) return login().then(function () { return api(path, body, noAuth, true); });
      return res.json().catch(function () { return {}; }).then(function (j) {
        if (!res.ok) { var err = new Error(j.error || ('Ошибка сервера: ' + res.status)); err.userMessage = j.error; err.status = res.status; throw err; }
        return j;
      });
    });
  }

  var loggingIn = null;
  function login() {
    if (loggingIn) return loggingIn;
    loggingIn = D.authorize(S.clientId, ['identify']).then(function (a) {
      if (!a || !a.code) throw new Error('Discord не дал войти.');
      return api('api/auth/discord/activity', { code: a.code }, true);
    }, function (e) {
      throw new Error(e && e.code === 5000 ? 'Без разрешения Discord войти нельзя.' : 'Discord не дал войти: ' + errText(e));
    }).then(function (r) {
      S.token = r.token;
      S.site = r.site_url || '';
      S.me = r.user;
    }).then(function () { loggingIn = null; }, function (e) { loggingIn = null; throw e; });
    return loggingIn;
  }

  // ---- часы комнаты ----
  function serverNow() { return Date.now() + S.offset; }
  function noteServerTime(t) { if (t > 0) S.offset = t - Date.now(); }
  function roomPos() {
    var st = S.room && S.room.state;
    if (!st) return 0;
    if (!st.playing || st.waiting) return st.pos || 0;
    return Math.max(0, (st.pos || 0) + (serverNow() - st.anchor_server_ms) / 1000 * (st.rate || 1));
  }
  function clockText(sec) {
    sec = Math.floor(sec);
    var hh = Math.floor(sec / 3600), mm = Math.floor(sec % 3600 / 60), ss = sec % 60;
    return (hh ? hh + ':' + (mm < 10 ? '0' : '') : '') + mm + ':' + (ss < 10 ? '0' : '') + ss;
  }
  function prettyCode(id) { return /^\d{6}$/.test(id) ? id.slice(0, 3) + '-' + id.slice(3) : id; }
  function isHost() { return !!(S.room && S.me && S.room.host_id === S.me.id); }
  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  // ---- медиатека ----
  function loadLibrary() {
    return api('api/library').then(function (j) {
      var list = model.parseLibrary(j).filter(function (m) { return m.ready && !model.isVoiceOverOnly(m); });
      S.items = model.prepareItems(list).filter(function (it) { return it.playable.kind !== 'none'; });
      S.byId = {};
      S.items.forEach(function (it) { S.byId[it.m.id] = it; });
      var g = model.groupVersions(S.items, function (it) { return posters.pathOf(it.posterQueries); });
      S.shown = g.shown;
      S.versions = g.versions;
    });
  }
  function titleOf(it) { return model.tileTitle(it, function (q) { return posters.titles[q]; }); }

  /** Серии и фильмы раздачи по порядку: { file, label (полное), short (S01E05 или название) }. */
  function entriesOf(it) {
    var p = it.playable, name = titleOf(it);
    if (p.kind === 'movie') return [{ file: p.file, label: name, short: '', season: null }];
    if (p.kind !== 'series') return [];
    var out = [];
    p.seasons.forEach(function (s) {
      s.items.forEach(function (e) {
        out.push({
          file: e.file, label: model.episodeLabel(name, s, e), season: s.season,
          short: e.title || ((s.season === 0 ? 'Спец.' : 'S' + pad2(s.season)) + 'E' + pad2(e.episode))
        });
      });
    });
    return out;
  }
  function keyOf(it, entry) { return model.watchKey(it.m.id, entry.file); }

  /** Файл комнаты в медиатеке: раздача, серия, следующая серия. null — такого у нас нет. */
  function resolve(key) {
    if (!key) return null;
    var bar = key.indexOf('|'), it = S.byId[Number(key.slice(0, bar))];
    if (!it) return null;
    var path = key.slice(bar + 1), list = entriesOf(it);
    for (var i = 0; i < list.length; i++) {
      if (list[i].file.path === path) return { it: it, entry: list[i], next: list[i + 1] || null, series: it.playable.kind === 'series' };
    }
    return null;
  }

  // ---- постеры: /api/poster?q= → путь → картинка с ключом (blob) ----
  var posters = (function () {
    var paths = {}, titles = {}, urls = {}, waits = {}, queue = [], active = 0;
    try { paths = JSON.parse(localStorage.getItem('sm.act.paths') || '{}') || {}; titles = JSON.parse(localStorage.getItem('sm.act.titles') || '{}') || {}; } catch (e) { /* без хранилища */ }
    function save() {
      try { localStorage.setItem('sm.act.paths', JSON.stringify(paths)); localStorage.setItem('sm.act.titles', JSON.stringify(titles)); } catch (e) { /* без хранилища */ }
    }
    function pathOf(qs) {
      for (var i = 0; i < qs.length; i++) if (typeof paths[qs[i]] === 'string') return paths[qs[i]];
      return null;
    }
    function load(q) {
      var path = paths[q] !== undefined ? Promise.resolve(paths[q]) : api('api/poster?q=' + encodeURIComponent(q)).then(function (r) {
        paths[q] = typeof r.poster === 'string' && r.poster.indexOf('/api/poster/img/') === 0 ? r.poster : null;
        titles[q] = typeof r.title_ru === 'string' ? r.title_ru.trim() : '';
        save();
        return paths[q];
      });
      return path.then(function (p) {
        if (!p) return null;
        return fetch(p.replace(/^\//, ''), { headers: { Authorization: 'Bearer ' + S.token } }).then(function (res) {
          if (!res.ok) throw new Error('poster');
          return res.blob();
        }).then(function (b) { return URL.createObjectURL(b); });
      });
    }
    function done(q, u) {
      urls[q] = u;
      (waits[q] || []).forEach(function (cb) { if (u) cb(u); });
      delete waits[q];
      active--;
      pump();
    }
    function pump() {
      while (active < 3 && queue.length) {
        var q = queue.shift();
        active++;
        load(q).then(done.bind(null, q), done.bind(null, q, null));
      }
    }
    /** Картинка постера для [it] в [cb] (сразу, если уже есть). */
    function want(it, cb) {
      var q = it.posterQueries[0];
      if (!q) return;
      if (urls[q] !== undefined) { if (urls[q]) cb(urls[q]); return; }
      if (waits[q]) { waits[q].push(cb); return; }
      waits[q] = [cb];
      queue.push(q);
      pump();
    }
    return { pathOf: pathOf, want: want, titles: titles };
  })();

  function posterInto(el, it, asBg) {
    if (!it) return;
    posters.want(it, function (u) {
      el.style.backgroundImage = 'url("' + u + '")';
      if (!asBg) el.classList.add('has-image');
    });
  }

  // ---- поток событий комнаты (с переходом на опрос, если прокси Discord держит поток) ----
  function startStream() {
    if (S.stream) S.stream.abort();
    var ctrl = new AbortController(), id = S.room.id, gotData = false;
    S.stream = ctrl;
    var fallback = setTimeout(function () { if (!gotData) startPolling(); }, 7000);
    fetch('api/activity/rooms/' + id + '/events', { headers: { Authorization: 'Bearer ' + S.token }, signal: ctrl.signal })
      .then(function (res) {
        if (res.status === 401) return login().then(function () { throw new Error('relogin'); });
        if (!res.ok || !res.body) throw new Error('stream ' + res.status);
        var reader = res.body.getReader(), dec = new TextDecoder(), buf = '';
        function read() {
          return reader.read().then(function (r) {
            if (r.done) return;
            buf += dec.decode(r.value, { stream: true });
            var i;
            while ((i = buf.indexOf('\n')) >= 0) {
              var line = buf.slice(0, i).trim();
              buf = buf.slice(i + 1);
              if (line.indexOf('data:') !== 0) continue;
              if (!gotData) { gotData = true; S.retry = 0; stopPolling(); }
              try { onEvent(JSON.parse(line.slice(5))); } catch (e) { /* не JSON */ }
            }
            return read();
          });
        }
        return read();
      })
      .catch(function () { /* оборвалось: ниже переподключимся */ })
      .then(function () {
        clearTimeout(fallback);
        if (S.stream !== ctrl || ctrl.signal.aborted) return;
        startPolling();
        S.retry = Math.min(S.retry + 1, 6);
        setTimeout(function () { if (S.stream === ctrl) startStream(); }, 1000 * Math.pow(2, S.retry - 1));
      });
  }
  function startPolling() {
    if (S.pollTimer) return;
    S.pollTimer = setInterval(refreshRoom, 3000);
    refreshRoom();
  }
  function stopPolling() { clearInterval(S.pollTimer); S.pollTimer = 0; }
  var refreshing = false;
  function refreshRoom() {
    if (refreshing || !S.room) return;
    refreshing = true;
    api('api/activity/rooms/' + S.room.id).then(function (r) { noteServerTime(r.server_time); setRoom(r.room); }, function (e) {
      if (e.status === 404) reopenRoom();
    }).then(function () { refreshing = false; });
  }
  // Комнату удалили (сервер перезапустился, все ушли): та же кнопка Activity открывает новую
  function reopenRoom() {
    api('api/activity/room', { channel_id: D.info.channelId }).then(function (r) { setRoom(r.room); startStream(); }, function () {});
  }

  var memberRefresh = 0;
  function onEvent(msg) {
    switch (msg.event) {
      case 'init': noteServerTime(msg.server_time); setRoom(msg.room); break;
      case 'state_changed': if (msg.data) { noteServerTime(msg.data.state && msg.data.state.server_time); setRoom(msg.data); } break;
      case 'member_joined':
      case 'member_left':
        clearTimeout(memberRefresh);
        memberRefresh = setTimeout(refreshRoom, 300);
        break;
      case 'activity_changed': {
        var d = msg.data || {}, wasPoll = !!(S.room && S.room.poll);
        ['watchers', 'candidates', 'poll', 'host_id', 'host_name'].forEach(function (k) { if (k in d) S.room[k] = d[k]; });
        if (!wasPoll && S.room.poll) S.tab = 'poll'; // голосование началось — карточка выбора у всех
        render();
        break;
      }
      case 'poll_result': {
        var p = msg.data || {};
        if (p.key) { toast('Победил «' + (p.name || 'фильм') + '» — включаем в комнате.'); S.tab = 'now'; } else toast('Никто не проголосовал: фильм не сменился.');
        render();
        break;
      }
    }
  }

  function setRoom(room) {
    if (!room || !room.id) return;
    var prevKey = S.room && S.room.file ? S.room.file.key : null;
    if (room.state && room.state.server_time) noteServerTime(room.state.server_time);
    S.room = room;
    var key = room.file ? room.file.key : null;
    if (key && key !== prevKey && prevKey !== null && S.tab === 'library' && !isHost()) S.tab = 'now';
    render();
  }

  // ---- действия ----
  function action(body) {
    return api('api/rooms/' + S.room.id + '/action', body).then(function (r) { if (r.room) setRoom(r.room); }, function (e) { toast(errText(e), true); });
  }
  function putEntry(it, entry) {
    S.sheet = null;
    S.tab = 'now';
    return action({ type: 'change_file', file: { key: keyOf(it, entry), name: entry.label, ad_id: it.m.id } }).then(function () {
      toast('«' + entry.label + '» — в комнате. Откройте его в приложении.');
    });
  }
  function suggest(it, entry) {
    api('api/activity/rooms/' + S.room.id + '/suggest', { file: { key: keyOf(it, entry), name: entry.label } })
      .then(function () { toast('Предложено: «' + entry.label + '»'); }, function (e) { toast(errText(e), true); });
  }
  function roomCall(path, body) {
    return api('api/activity/rooms/' + S.room.id + '/' + path, body || {}).catch(function (e) { toast(errText(e), true); });
  }
  function openInApp() {
    if (!S.site) { toast('Сервер не знает свой адрес: ведущему нужно задать ACTIVITY_SITE_URL.', true); return; }
    var url = S.site + '/join/' + encodeURIComponent(S.room.id);
    D.openExternal(url).catch(function () { toast('Откройте в браузере: ' + url); });
  }
  function copyCode() {
    var code = prettyCode(S.room.id);
    var done = function () { toast('Код скопирован: ' + code); };
    try { navigator.clipboard.writeText(code).then(done, function () { toast('Код комнаты: ' + code); }); } catch (e) { toast('Код комнаты: ' + code); }
  }

  // ---- отрисовка ----
  function render() {
    if (!S.room) return;
    var top = h('div', { class: 'top' }, [
      h('div', { class: 'brand' }, [h('span', { class: 'brand-mark' }), h('span', { text: 'Вместе' })]),
      h('div', { class: 'grow' }),
      tabs(),
      h('button', { class: 'code-pill', type: 'button', title: 'Код комнаты для ТВ и приложений', on: { click: copyCode } },
        [icon('tv'), h('small', { text: 'Код' }), h('b', { text: prettyCode(S.room.id) })])
    ]);
    var page = h('div', { class: 'page' });
    if (S.tab === 'library') page.appendChild(viewLibrary());
    else if (S.tab === 'poll') page.appendChild(viewPoll());
    else page.appendChild(viewNow());
    var focusSearch = document.activeElement && document.activeElement.id === 'q';
    var caret = focusSearch ? document.activeElement.selectionStart : 0;
    app.replaceChildren(top, page);
    if (S.sheet) app.appendChild(viewSheet());
    if (focusSearch) { var q = document.getElementById('q'); if (q) { q.focus(); q.setSelectionRange(caret, caret); } }
  }

  function tabs() {
    var n = S.room.candidates ? S.room.candidates.length : 0;
    function tab(id, ico, text, badge) {
      return h('button', { class: 'tab' + (S.tab === id ? ' on' : ''), type: 'button', on: { click: function () { S.tab = id; render(); } } },
        [icon(ico), text, badge ? h('span', { class: 'badge', text: String(badge) }) : null]);
    }
    return h('div', { class: 'tabs' }, [
      tab('now', 'film', 'Сейчас'),
      tab('library', 'grid', 'Медиатека'),
      tab('poll', 'vote', S.room.poll ? 'Голосуем' : 'Голосование', S.room.poll ? '!' : n)
    ]);
  }

  function viewNow() {
    var r = S.room, f = r.file, info = f ? resolve(f.key) : null, host = isHost();
    var card = h('div', { class: 'panel' });
    if (f) {
      var bg = h('div', { class: 'now-bg' });
      var poster = h('div', { class: 'now-poster' });
      if (info) { posterInto(poster, info.it); posterInto(bg, info.it, true); }
      var st = r.state || {};
      var waiting = st.waiting, playing = st.playing && !waiting;
      var loaders = (r.members || []).filter(function (m) { return m.buffering; }).map(function (m) { return m.name; });
      var status = waiting ? 'Ждём: грузится у ' + (loaders.join(', ') || 'кого-то') : playing ? 'Идёт' : 'Пауза';
      var body = h('div', { class: 'now-body' }, [
        h('div', { class: 'now-label' }, [icon('film'), 'Сейчас в комнате']),
        h('h1', { class: 'now-title', text: info ? titleOf(info.it) : f.name || 'Фильм' }),
        info && info.series ? h('div', { class: 'now-sub', text: info.entry.short }) : null,
        !info ? h('div', { class: 'now-sub', text: 'Этого файла нет в медиатеке сервера.' }) : null,
        h('div', { class: 'row' }, [
          h('span', { class: 'status' }, [h('span', { class: 'dot' + (playing ? ' live' : waiting ? ' wait' : '') }), status]),
          h('span', { class: 'clock', id: 'clock', text: clockText(roomPos()) })
        ]),
        h('div', { class: 'spacer' }),
        h('div', { class: 'row' }, [
          btn('lamp', 'devices', 'Открыть на ПК или телефоне', openInApp),
          host ? btn('', playing || waiting ? 'pause' : 'play', playing || waiting ? 'Пауза у всех' : 'Продолжить у всех', function () {
            action({ type: playing || waiting ? 'pause' : 'play', pos: roomPos() });
          }) : null,
          host && info && info.next ? btn('blue', 'next', 'Следующая: ' + info.next.short, function () { putEntry(info.it, info.next); }) : null
        ]),
        h('div', { class: 'small muted', text: host
          ? 'Вы ведущий: фильм, пауза и перемотка — у вас. Видео идёт в приложении Suslik Media.'
          : 'Фильм выбирает ведущий — ' + (r.host_name || 'ведущий') + '. Видео идёт в приложении Suslik Media, оно само встанет на место комнаты.' })
      ]);
      card.appendChild(bg);
      card.appendChild(h('div', { class: 'now' }, [poster, body]));
    } else {
      card.appendChild(h('div', { class: 'now' }, [h('div', { class: 'now-body' }, [
        h('div', { class: 'now-label' }, [icon('film'), 'Сейчас в комнате']),
        h('h1', { class: 'now-title', text: 'Фильм ещё не выбран' }),
        h('div', { class: 'now-sub', text: host
          ? 'Выберите фильм в медиатеке и нажмите «Включить в комнате» — или соберите варианты и устройте голосование.'
          : 'Ведущий — ' + (r.host_name || 'ведущий') + '. Предложите фильм из медиатеки: ведущий может устроить голосование.' }),
        h('div', { class: 'spacer' }),
        h('div', { class: 'row' }, [btn('lamp', 'grid', host ? 'Выбрать в медиатеке' : 'Предложить фильм', function () { S.tab = 'library'; render(); })])
      ])]));
    }
    if (r.poll) {
      card = h('div', {}, [card, h('div', { class: 'panel' }, [h('div', { class: 'row' }, [
        icon('vote'), h('b', { text: 'Идёт голосование' }), h('span', { class: 'timer', id: 'timer', text: pollLeft() }),
        h('div', { class: 'spacer' }), btn('blue small', null, 'Голосовать', function () { S.tab = 'poll'; render(); })
      ])])]);
    }

    var side = h('div', {}, [
      h('div', { class: 'panel' }, [
        h('div', { class: 'h', text: 'Код для ТВ и приложений' }),
        h('div', { class: 'tv-code', text: prettyCode(r.id) }),
        h('div', { class: 'small muted', text: 'На телевизоре: «Вместе» → «Войти по коду». В приложении на ПК или телефоне — так же, или кнопкой «Открыть».' }),
        h('div', { class: 'row', style: 'margin-top:12px' }, [btn('small', 'copy', 'Скопировать', copyCode)])
      ]),
      viewPeople()
    ]);
    return h('div', { class: 'grid2' }, [card, side]);
  }

  function viewPeople() {
    var r = S.room, byId = {}, list = [];
    function person(id, name, avatar) {
      if (!byId[id]) { byId[id] = { id: id, name: name, avatar: avatar, here: false, player: false, buffering: false }; list.push(byId[id]); }
      return byId[id];
    }
    (r.watchers || []).forEach(function (w) { person(w.id, w.name, w.avatar).here = true; });
    (r.members || []).forEach(function (m) {
      var p = person(m.id, m.name, m.avatar);
      p.player = true;
      if (m.buffering) p.buffering = true;
    });
    var rows = list.map(function (p) {
      var av = h('div', { class: 'avatar', text: p.avatar ? '' : (p.name || '?').slice(0, 1).toUpperCase() });
      if (p.avatar && /^https:\/\/cdn\.discordapp\.com\//.test(p.avatar)) av.style.backgroundImage = 'url("' + p.avatar + '")';
      return h('div', { class: 'person' }, [
        av, h('span', { class: 'name', text: p.name + (S.me && p.id === S.me.id ? ' (вы)' : '') }),
        p.id === r.host_id ? h('span', { class: 'tag lamp' }, [icon('crown'), 'Ведущий']) : null,
        p.buffering ? h('span', { class: 'tag lamp', text: 'Грузится' }) : p.player ? h('span', { class: 'tag' }, [icon('devices'), 'Смотрит']) : null
      ]);
    });
    return h('div', { class: 'panel' }, [
      h('div', { class: 'h' }, ['В комнате · ' + list.length]),
      rows.length ? h('div', { class: 'people' }, rows) : h('div', { class: 'muted', text: 'Пока никого.' }),
      h('div', { class: 'small muted', style: 'margin-top:10px', text: '«Смотрит» — открыл фильм в приложении.' })
    ]);
  }

  function viewLibrary() {
    var q = S.query.trim().toLowerCase();
    var list = q ? S.shown.filter(function (it) {
      return it.searchText.indexOf(q) >= 0 || titleOf(it).toLowerCase().indexOf(q) >= 0;
    }) : S.shown;
    var input = h('input', { id: 'q', type: 'search', placeholder: 'Поиск по медиатеке', value: S.query, autocomplete: 'off',
      on: { input: function (e) { S.query = e.target.value; render(); } } });
    var tiles = list.slice(0, 240).map(function (it) {
      var name = titleOf(it), poster = h('div', { class: 'poster' }, [h('span', { class: 'poster-title', text: name })]);
      var cand = S.room.candidates && S.room.candidates.some(function (c) { return Number(c.key.split('|')[0]) === it.m.id; });
      if (cand) poster.appendChild(h('span', { class: 'mark tag lamp' }, [icon('vote'), 'В списке']));
      posterInto(poster, it);
      var p = it.playable;
      var sub = p.kind === 'series' ? model.seriesLine(p) : (model.itemYear(it) || '');
      return h('button', { class: 'tile', type: 'button', on: { click: function () { openSheet(it); } } }, [
        poster, h('div', { class: 'tname', text: name }), sub ? h('div', { class: 'tsub', text: String(sub) }) : null
      ]);
    });
    return h('div', {}, [
      h('label', { class: 'search' }, [icon('search'), input]),
      tiles.length ? h('div', { class: 'tiles' }, tiles) : h('div', { class: 'empty', text: q ? 'Ничего не нашлось.' : 'Медиатека пуста.' })
    ]);
  }

  function openSheet(it) {
    var versions = S.versions[it.m.id] || [it];
    S.sheet = { versions: versions, v: 0, season: null };
    render();
  }

  function viewSheet() {
    var sh = S.sheet, it = sh.versions[sh.v], host = isHost(), entries = entriesOf(it);
    var seasons = [];
    entries.forEach(function (e) { if (e.season != null && seasons.indexOf(e.season) < 0) seasons.push(e.season); });
    var curKey = S.room.file ? S.room.file.key : '';
    if (sh.season == null || seasons.indexOf(sh.season) < 0) {
      var cur = resolve(curKey);
      sh.season = cur && cur.it === it && cur.entry.season != null ? cur.entry.season : seasons[0];
    }
    var shownEntries = seasons.length > 1 ? entries.filter(function (e) { return e.season === sh.season; }) : entries;
    var poster = h('div', { class: 'now-poster', style: 'width:84px' });
    posterInto(poster, it);
    var close = function () { S.sheet = null; render(); };
    var kids = [
      h('div', { class: 'sheet-head' }, [poster, h('div', {}, [
        h('h2', { text: titleOf(it) }),
        h('div', { class: 'small muted', text: it.playable.kind === 'series' ? model.seriesLine(it.playable) : it.m.name })
      ]), btn('icon close', 'close', null, close, { title: 'Закрыть' })])
    ];
    if (sh.versions.length > 1) {
      kids.push(h('div', { class: 'h', text: 'Версия' }));
      kids.push(h('div', { class: 'chips' }, sh.versions.map(function (v, i) {
        var line = model.versionLine(v) || model.formatBytes(v.m.size);
        return h('button', { class: 'chip' + (i === sh.v ? ' on' : ''), type: 'button', title: v.m.name,
          on: { click: function () { sh.v = i; sh.season = null; render(); } } }, [line + ' · ' + model.formatBytes(v.m.size)]);
      })));
    }
    if (seasons.length > 1) {
      kids.push(h('div', { class: 'chips' }, seasons.map(function (s) {
        return h('button', { class: 'chip' + (s === sh.season ? ' on' : ''), type: 'button', on: { click: function () { sh.season = s; render(); } } },
          [s === 0 ? 'Спецвыпуски' : 'Сезон ' + s]);
      })));
    }
    kids.push(h('div', { class: 'files' }, shownEntries.map(function (e) {
      var key = keyOf(it, e), current = key === curKey;
      var inList = S.room.candidates && S.room.candidates.some(function (c) { return c.key === key; });
      return h('div', { class: 'file' + (current ? ' current' : '') }, [
        h('span', { class: 'fname', text: it.playable.kind === 'series' ? e.short : e.label, title: e.file.path }),
        current ? h('span', { class: 'tag lamp', text: 'Сейчас' }) : null,
        !inList && !S.room.poll ? btn('small', 'vote', 'Предложить', function () { suggest(it, e); }) : inList ? h('span', { class: 'tag', text: 'В списке' }) : null,
        host && !current ? btn('lamp small', 'play', 'Включить в комнате', function () { putEntry(it, e); }) : null
      ]);
    })));
    return h('div', { class: 'scrim', on: { click: function (ev) { if (ev.target === ev.currentTarget) close(); } } }, [h('div', { class: 'sheet' }, kids)]);
  }

  function pollLeft() {
    var p = S.room && S.room.poll;
    if (!p) return '';
    return clockText(Math.max(0, Math.ceil((p.ends_at - serverNow()) / 1000)));
  }

  function viewPoll() {
    var r = S.room, host = isHost(), p = r.poll, cands = r.candidates || [];
    var myVote = p && p.votes && S.me ? p.votes[S.me.id] : null;
    var total = 0;
    if (p && p.tally) Object.keys(p.tally).forEach(function (k) { total += p.tally[k]; });
    var rows = cands.map(function (c) {
      var info = resolve(c.key), n = p && p.tally ? p.tally[c.key] || 0 : 0;
      var mini = h('div', { class: 'mini' });
      if (info) posterInto(mini, info.it, true);
      var mine = myVote === c.key;
      var row = h('div', { class: 'cand' + (mine ? ' mine' : '') }, [
        p ? h('div', { class: 'bar', style: 'width:' + (total ? Math.round(n / total * 100) : 0) + '%' }) : null,
        mini,
        h('div', { class: 'cbody' }, [h('div', { class: 'cname', text: info ? info.entry.label : c.name }), h('div', { class: 'small muted', text: 'Предложил(а): ' + (c.by_name || '—') })]),
        p ? h('div', { class: 'votes', text: String(n) }) : null,
        p ? btn(mine ? 'blue small' : 'small', 'vote', mine ? 'Ваш голос' : 'Голосую', function () { roomCall('vote', { key: c.key }); })
          : (host || (S.me && c.by === S.me.id)) ? btn('icon small', 'close', null, function () { roomCall('unsuggest', { key: c.key }); }, { title: 'Убрать из списка' }) : null
      ]);
      return row;
    });
    var head;
    if (p) {
      head = h('div', { class: 'row' }, [
        h('div', {}, [h('div', { class: 'h', text: 'Голосование' }), h('div', { class: 'muted', text: 'Выберите фильм. Победитель сразу включится в комнате.' })]),
        h('div', { class: 'spacer' }), h('span', { class: 'timer', id: 'timer', text: pollLeft() }),
        host ? btn('small', 'close', 'Отменить', function () { roomCall('poll/cancel'); }) : null
      ]);
    } else {
      head = h('div', { class: 'row' }, [
        h('div', {}, [h('div', { class: 'h', text: 'Что посмотреть?' }), h('div', { class: 'muted', text: host
          ? 'Когда наберётся 2–4 варианта, начните голосование: 45 секунд, и победитель включится у всех.'
          : 'Предлагайте фильмы из медиатеки — ведущий запустит голосование.' })]),
        h('div', { class: 'spacer' }),
        host ? btn('lamp', 'vote', 'Начать голосование', function () { roomCall('poll'); }, { disabled: cands.length < 2 }) : null,
        btn('', 'plus', 'Предложить', function () { S.tab = 'library'; render(); })
      ]);
    }
    return h('div', { class: 'panel' }, [head, h('div', { style: 'height:14px' }),
      rows.length ? h('div', { class: 'cands' }, rows) : h('div', { class: 'empty', text: 'Пока ничего не предложено. Откройте фильм в медиатеке и нажмите «Предложить».' })]);
  }

  // Раз в секунду — только часы и таймер, без перерисовки страницы
  function tick() {
    var c = document.getElementById('clock');
    if (c) c.textContent = clockText(roomPos());
    var t = document.getElementById('timer');
    if (t) t.textContent = pollLeft();
  }

  // ---- запуск ----
  function bootLine(text, bad) {
    var l = document.getElementById('boot-line');
    if (l) { l.textContent = text; l.className = 'boot-line' + (bad ? ' bad' : ''); }
  }

  function boot() {
    if (!D.inDiscord) {
      bootLine('Это страница для Discord. Зайдите в голосовой канал, нажмите «Активности» и выберите Suslik Media.');
      return;
    }
    api('api/activity/config', undefined, true).then(function (cfg) {
      if (!cfg.client_id || !cfg.enabled) throw new Error('На сервере не настроен вход через Discord.');
      S.clientId = cfg.client_id;
      return D.start(S.clientId);
    }).then(function () {
      if (!D.info.channelId) throw new Error('Запустите Activity в голосовом канале: комната привязывается к нему.');
      bootLine('Входим через Discord…');
      return login();
    }).then(function () {
      bootLine('Открываем комнату канала…');
      return api('api/activity/room', { channel_id: D.info.channelId });
    }).then(function (r) {
      S.room = r.room;
      if (r.me) S.me = r.me;
      bootLine('Загружаем медиатеку…');
      return loadLibrary().catch(function (e) { toast('Медиатека не загрузилась: ' + errText(e), true); });
    }).then(function () {
      render();
      startStream();
      setInterval(tick, 1000);
      // Медиатека меняется (добавили раздачу): раз в 5 минут перечитываем
      setInterval(function () { loadLibrary().then(render, function () {}); }, 5 * 60 * 1000);
    }).catch(function (e) { bootLine(errText(e), true); });
  }

  document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && S.sheet) { S.sheet = null; render(); } });
  boot();
})();

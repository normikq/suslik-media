/*
 * Suslik Media в Discord Activity — пульт комнаты голосового канала (docs/DISCORD-ACTIVITY.md).
 * Видео здесь не играет: раздачи тяжёлые (MKV, DTS), их играют приложения. Здесь — что смотрим, кто ведущий,
 * медиатека («Включить в комнате», «Предложить», категории, карточка фильма), голосование и «Рулетка», «Посоветовать фильм» (ИИ),
 * «Следующая серия», «Открыть в приложении», «Позвать в чат» и статус «Смотрит …» в профиле Discord.
 * Медиатека разбирается тем же model.js, что у ТВ (копия tizen/js/model.js; тест сверяет копии).
 */
(function () {
  'use strict';
  var D = window.SMDiscord, model = window.SM.model, SC = window.SM.searchcore;
  var app = document.getElementById('app');

  var CATS = [['all', 'Все'], ['movie', 'Фильмы'], ['series', 'Сериалы'], ['cartoon', 'Мультфильмы'], ['anime', 'Аниме']];
  var SORTS = [['new', 'Сначала новые'], ['name', 'По названию (А–Я)'], ['year', 'По году (сначала свежие)'], ['rating', 'По рейтингу']];

  var S = {
    clientId: '', token: '', site: '', me: null,
    room: null, offset: 0,
    items: [], shown: [], versions: {}, byId: {},
    tab: 'now', query: '', sheet: null,
    // поиск раздач: mode — 'library' или 'trackers'; server — /api/search, pc — RuTracker с ПК комнаты
    find: { mode: 'library', q: '', asked: '', server: null, serverErr: '', providers: [], pc: null, busy: 0, notes: {},
      page: 0, filtersOpen: false, f: null },
    stream: null, streamOk: false, pollTimer: 0, retry: 0,
    // медиатека: категория и сортировка (помнятся в браузере)
    lib: { cat: 'all', sort: 'new' },
    // «Посоветовать фильм»: POST /api/search/ai-discovery (Gemini + TMDB, docs/AI-DISCOVERY.md)
    ai: { q: '', asked: '', busy: 0, items: null, err: '' },
    inviteFallback: false
  };
  try {
    var savedLib = JSON.parse(localStorage.getItem('sm.act.lib') || '{}') || {};
    if (CATS.some(function (c) { return c[0] === savedLib.cat; })) S.lib.cat = savedLib.cat;
    if (SORTS.some(function (c) { return c[0] === savedLib.sort; })) S.lib.sort = savedLib.sort;
  } catch (e) { /* без хранилища */ }

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
    loading: '<path d="M12 3a9 9 0 109 9"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.6 2.6 4 5.8 4 9s-1.4 6.4-4 9c-2.6-2.6-4-5.8-4-9s1.4-6.4 4-9z"/>',
    seeds: '<path d="M12 19V6M6.5 11.5L12 6l5.5 5.5"/>',
    filter: '<path d="M4 6h16M7 12h10M10 18h4"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    back: '<path d="M15 5l-7 7 7 7"/>',
    fwd: '<path d="M9 5l7 7-7 7"/>',
    dice: '<rect x="4" y="4" width="16" height="16" rx="3.5"/><circle cx="8.8" cy="8.8" r="1.1" fill="currentColor"/><circle cx="15.2" cy="8.8" r="1.1" fill="currentColor"/><circle cx="12" cy="12" r="1.1" fill="currentColor"/><circle cx="8.8" cy="15.2" r="1.1" fill="currentColor"/><circle cx="15.2" cy="15.2" r="1.1" fill="currentColor"/>',
    sparkles: '<path d="M11 3.5l1.9 5.1 5.1 1.9-5.1 1.9L11 17.5l-1.9-5.1L4 10.5l5.1-1.9z"/><path d="M18.5 15.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z"/>',
    chat: '<path d="M4.5 5.5h15a1 1 0 011 1v9a1 1 0 01-1 1H10l-4.5 3.5v-3.5h-1a1 1 0 01-1-1v-9a1 1 0 011-1z"/><path d="M8.5 10h7M8.5 13h4.5"/>',
    star: '<path d="M12 3.8l2.5 5.2 5.7.8-4.1 4 1 5.6L12 16.7l-5.1 2.7 1-5.6-4.1-4 5.7-.8z" fill="currentColor" stroke="none"/>',
    flag: '<path d="M5.5 21V4.5"/><path d="M5.5 4.5h11.5l-2.4 4 2.4 4H5.5"/>',
    sound: '<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z"/><path d="M15.5 9a4 4 0 010 6M18 6.5a7.5 7.5 0 010 11"/>',
    mute: '<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z"/><path d="M16 9.5l5 5M21 9.5l-5 5"/>'
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

  // Вход просит и rpc.activities.write — статус «Смотрит …» в профиле (presence ниже). Отказался в окне Discord —
  // второй раз спрашиваем только identify и больше статус не просим (помним в браузере).
  var loggingIn = null, PRESENCE_SCOPE = 'rpc.activities.write';
  function wantPresence() { try { return localStorage.getItem('sm.act.nopresence') !== '1'; } catch (e) { return true; } }
  function authorize() {
    var scopes = wantPresence() ? ['identify', PRESENCE_SCOPE] : ['identify'];
    return D.authorize(S.clientId, scopes).catch(function (e) {
      if (scopes.length < 2 || !(e && e.code === 5000)) throw e;
      try { localStorage.setItem('sm.act.nopresence', '1'); } catch (x) { /* без хранилища */ }
      return D.authorize(S.clientId, ['identify']);
    });
  }
  function login() {
    if (loggingIn) return loggingIn;
    loggingIn = authorize().then(function (a) {
      if (!a || !a.code) throw new Error('Discord не дал войти.');
      return api('api/auth/discord/activity', { code: a.code }, true);
    }, function (e) {
      throw new Error(e && e.code === 5000 ? 'Без разрешения Discord войти нельзя.' : 'Discord не дал войти: ' + errText(e));
    }).then(function (r) {
      S.token = r.token;
      S.site = r.site_url || '';
      S.me = r.user;
      if (r.discord_token) presence.auth(r.discord_token);
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
      classifyLibrary();
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
  // Как на ПК (Library::posterPath): запросы плитки по очереди — принятое название ИИ «Original (год)», потом
  // название и имя файла; у первого постера нет — спрашиваем следующий. «Постера нет» помним 3 дня, найденный — 30.
  // Вместе с путём помним сведения TMDB для категорий и сортировки (m): рейтинг, мультфильм ли, язык оригинала, год,
  // оригинальное название, открытый адрес постера (для статуса в Discord) и жанры.
  var posters = (function () {
    var DAY = 864e5, known = {}, titles = {}, urls = {}, waits = {}, queue = [], active = 0, asking = {};
    try {
      known = JSON.parse(localStorage.getItem('sm.act.posters') || '{}') || {};
      titles = JSON.parse(localStorage.getItem('sm.act.titles') || '{}') || {};
    } catch (e) { /* без хранилища */ }
    // Запись без сведений (m) — из прошлой версии страницы: спросим заново
    function fresh(q) {
      var k = known[q];
      return k && k.m && Date.now() - k.at < (k.p ? 30 : 3) * DAY ? k : null;
    }
    var saveTimer = 0;
    function save() {
      clearTimeout(saveTimer);
      saveTimer = setTimeout(function () {
        try { localStorage.setItem('sm.act.posters', JSON.stringify(known)); localStorage.setItem('sm.act.titles', JSON.stringify(titles)); } catch (e) { /* без хранилища */ }
      }, 500);
    }
    function pathOf(qs) {
      for (var i = 0; i < qs.length; i++) { var k = fresh(qs[i]); if (k && k.p) return k.p; }
      return null;
    }
    /** Сведения TMDB плитки: {} — постера нет ни по одному запросу, null — ещё не спрашивали. */
    function meta(it) {
      var qs = it.posterQueries || [];
      for (var i = 0; i < qs.length; i++) {
        var k = fresh(qs[i]);
        if (!k) return null;
        if (k.p) return k.m;
      }
      return {};
    }
    function ask(q) {
      var k = fresh(q);
      if (k) return Promise.resolve(k.p);
      if (asking[q]) return asking[q];
      asking[q] = api('api/poster?q=' + encodeURIComponent(q)).then(function (r) {
        delete asking[q];
        var p = typeof r.poster === 'string' && r.poster.indexOf('/api/poster/img/') === 0 ? r.poster : null;
        known[q] = { p: p, at: Date.now(), m: p ? {
          r: Number(r.rating) > 0 ? Number(r.rating) : 0, a: r.animation ? 1 : 0, l: typeof r.lang === 'string' ? r.lang : '',
          y: Number(r.year) || 0, o: typeof r.original === 'string' ? r.original : '', t: r.type === 'tv' ? 'tv' : 'movie',
          u: typeof r.poster_url === 'string' && r.poster_url.indexOf('https://') === 0 ? r.poster_url : '',
          g: Array.isArray(r.genres) ? r.genres.filter(function (x) { return typeof x === 'string'; }).slice(0, 4) : []
        } : {} };
        titles[q] = typeof r.title_ru === 'string' ? r.title_ru.trim() : '';
        save();
        return p;
      }, function (e) { delete asking[q]; throw e; });
      return asking[q];
    }
    // Первый запрос, у которого есть постер; сбой сети — не «постера нет»: в следующий раз спросим снова
    function hitFor(qs, i) {
      if (i >= qs.length) return Promise.resolve(null);
      return ask(qs[i]).then(function (p) { return p ? qs[i] : hitFor(qs, i + 1); });
    }
    function blobOf(p) {
      return fetch(p.replace(/^\//, ''), { headers: { Authorization: 'Bearer ' + S.token } }).then(function (res) {
        if (!res.ok) throw new Error('poster');
        return res.blob();
      }).then(function (b) { return URL.createObjectURL(b); });
    }
    function load(job) {
      if (job.path) return blobOf(job.path);
      return hitFor(job.qs, 0).then(function (q) { return q ? blobOf(known[q].p) : null; });
    }
    // Не больше 200 картинок в памяти: дольше всех не нужные освобождаются (как у ТВ)
    var order = [], LIMIT = 200;
    function remember(key, u) {
      urls[key] = u;
      if (!u) return;
      order.push(key);
      while (order.length > LIMIT) { var old = order.shift(); if (urls[old]) URL.revokeObjectURL(urls[old]); delete urls[old]; }
    }
    function done(key, u, failed) {
      if (!failed) remember(key, u);
      (waits[key] || []).forEach(function (cb) { if (u) cb(u); });
      delete waits[key];
      active--;
      pump();
    }
    function pump() {
      while (active < 3 && queue.length) {
        var job = queue.shift();
        active++;
        load(job).then(function (key) { return function (u) { done(key, u, false); }; }(job.key),
          function (key) { return function () { done(key, null, true); }; }(job.key));
      }
    }
    function enqueue(job, cb) {
      if (urls[job.key] !== undefined) { if (urls[job.key]) cb(urls[job.key]); return; }
      if (waits[job.key]) { waits[job.key].push(cb); return; }
      waits[job.key] = [cb];
      queue.push(job);
      pump();
    }
    /** Картинка постера для [it] в [cb] (сразу, если уже есть). */
    function want(it, cb) {
      var qs = it.posterQueries || [];
      if (qs.length) enqueue({ key: qs.join('\n'), qs: qs }, cb);
    }
    /** Картинка по пути зеркала /api/poster/img/… (постеры подборки ИИ). */
    function img(path, cb) {
      if (typeof path === 'string' && path.indexOf('/api/poster/img/') === 0) enqueue({ key: 'img:' + path, path: path }, cb);
    }

    // Сведения для всей медиатеки — в фоне, по два запроса (как Library.classifyAnimation на ПК): без них не работают
    // «Мультфильмы», «Аниме» и сортировка по рейтингу. [onChange] — пачкой, не чаще раза в 0,8 с.
    var cq = [], cActive = 0, cSeen = {}, cTimer = 0, cChange = null;
    function classify(list, onChange) {
      cChange = onChange;
      list.forEach(function (it) {
        var key = (it.posterQueries || []).join('\n');
        if (!key || cSeen[key] || meta(it) !== null) return;
        cSeen[key] = 1;
        cq.push(it);
      });
      cPump();
    }
    function cDone() {
      cActive--;
      if (!cTimer) cTimer = setTimeout(function () { cTimer = 0; if (cChange) cChange(); }, 800);
      cPump();
    }
    function cPump() {
      while (cActive < 2 && cq.length) {
        var it = cq.shift();
        cActive++;
        hitFor(it.posterQueries, 0).then(cDone, cDone);
      }
    }
    function pendingMeta() { return cq.length + cActive; }

    // Карточка фильма: описание по-русски, жанры и длительность (?hero=1 — ещё два запроса к TMDB, поэтому только по открытию)
    var det = {};
    function details(it) {
      var key = (it.posterQueries || []).join('\n');
      if (!key) return Promise.resolve(null);
      if (!det[key]) {
        det[key] = hitFor(it.posterQueries, 0).then(function (q) {
          return q ? api('api/poster?hero=1&q=' + encodeURIComponent(q)) : null;
        }).catch(function () { delete det[key]; return null; });
      }
      return det[key];
    }
    return { pathOf: pathOf, want: want, img: img, titles: titles, meta: meta, classify: classify, pendingMeta: pendingMeta, details: details };
  })();

  function posterInto(el, it, asBg) {
    if (!it) return;
    posters.want(it, function (u) {
      el.style.backgroundImage = 'url("' + u + '")';
      if (!asBg) el.classList.add('has-image');
    });
  }

  // ---- статус в Discord («Смотрит …» в профиле и в голосовом канале): SET_ACTIVITY ----
  // Нужен вход с rpc.activities.write и commands.authenticate токеном Discord (сервер отдаёт его только для этого).
  // Шлём только когда статус изменился, не чаще раза в 1,5 с; живой таймер Discord ведёт сам по timestamps.start.
  // Discord не принял — пробуем без картинки, потом как «Играет» (старые клиенты знают только этот тип).
  var presence = (function () {
    var ready = false, last = '', timer = 0, type = 3, withImage = true;
    function auth(token) {
      D.authenticate(token).then(function () { ready = true; last = ''; update(); }, function () { ready = false; });
    }
    function cut(t) { t = String(t || '').trim(); if (t.length > 128) t = t.slice(0, 127) + '…'; return t.length >= 2 ? t : t + '  '; }
    function build() {
      var r = S.room, f = r.file, info = f ? resolve(f.key) : null, people = (r.watchers || []).length || 1;
      var a = { type: type, instance: true };
      if (!f) {
        a.details = r.spin ? 'Крутят рулетку' : r.poll ? 'Голосуют за фильм' : 'Выбирают фильм';
        a.state = 'В комнате: ' + people + ' чел.';
        return a;
      }
      var title = info ? titleOf(info.it) : f.name || 'Фильм', year = info ? model.itemYear(info.it) : null;
      var st = r.state || {}, playing = st.playing && !st.waiting, ep = info && info.series ? info.entry.short : '';
      a.details = cut(title + (year && title.indexOf(String(year)) < 0 ? ' (' + year + ')' : ''));
      if (playing) {
        a.state = cut(ep || 'Смотрим вместе · ' + people + ' чел.');
        // секунды: с какого момента (на часах этого компьютера) фильм шёл бы с нуля
        a.timestamps = { start: Math.floor((st.anchor_server_ms - S.offset - (st.pos || 0) * 1000 / (st.rate || 1)) / 1000) };
      } else {
        a.state = cut((ep ? ep + ' · ' : '') + (st.waiting ? 'Ждём загрузку · ' : 'На паузе · ') + clockText(roomPos()));
      }
      var mt = info ? posters.meta(info.it) : null;
      if (withImage && mt && mt.u) a.assets = { large_image: mt.u, large_text: cut(title) };
      return a;
    }
    function send() {
      timer = 0;
      if (!ready || !S.room) return;
      var a = build(), k = JSON.stringify(a);
      if (k === last) return;
      last = k;
      D.setActivity(a).catch(function () {
        if (withImage && a.assets) withImage = false;
        else if (type !== 0) type = 0;
        else return;
        last = '';
        update();
      });
    }
    function update() { if (ready && !timer) timer = setTimeout(send, 1500); }
    return { auth: auth, update: update };
  })();

  // ---- категории и сортировка медиатеки (как на ПК: LibraryView.itemCategory) ----
  var ANIME_RE = /(^|[^a-zа-яё])(?:anime|аниме|anilibria)(?![a-zа-яё])|\[(?:horriblesubs|erai-raws|subsplease|judas)\]/i;
  // То же правило, что у сайта и ПК (website/classify.js): название раздачи, плюс жанр «Анимация» по TMDB
  var CARTOON_RE = /мульт(?!сериал)|анимаци|\bcartoons?\b|\banimation\b|\bPixar\b|\bDreamWorks\b|\bDisney\b/i;
  function categoryOf(it) {
    var text = it.m.name + ' ' + it.baseTitle, mt = posters.meta(it) || {};
    if (ANIME_RE.test(text) || (mt.a && mt.l === 'ja')) return 'anime';
    if (CARTOON_RE.test(text) || mt.a) return 'cartoon';
    if (model.isCollection(it.playable, it.m)) return 'movie';
    return it.playable.kind === 'series' ? 'series' : 'movie';
  }
  var CAT_LABEL = { movie: 'Фильм', series: 'Сериал', cartoon: 'Мультфильм', anime: 'Аниме' };
  function typeLabel(it) {
    if (model.isCollection(it.playable, it.m)) return 'Сборник';
    var c = categoryOf(it);
    if (c === 'cartoon' && it.playable.kind === 'series') return 'Мультсериал';
    return CAT_LABEL[c];
  }
  function yearOf(it) { var y = model.itemYear(it); if (y) return y; var mt = posters.meta(it); return mt && mt.y || 0; }
  function ratingOf(it) { var mt = posters.meta(it); return mt && mt.r || 0; }
  function setLib(k, v) {
    S.lib[k] = v;
    try { localStorage.setItem('sm.act.lib', JSON.stringify(S.lib)); } catch (e) { /* без хранилища */ }
    render();
  }
  /** Плитки медиатеки после поиска, категории и сортировки. */
  function libraryList() {
    var q = S.query.trim().toLowerCase(), cat = S.lib.cat, sort = S.lib.sort;
    var list = S.shown.filter(function (it) {
      if (q && it.searchText.indexOf(q) < 0 && titleOf(it).toLowerCase().indexOf(q) < 0) return false;
      return cat === 'all' || categoryOf(it) === cat;
    });
    var byId = function (a, b) { return b.m.id - a.m.id; };
    var cmp = sort === 'name' ? function (a, b) { return titleOf(a).localeCompare(titleOf(b), 'ru') || byId(a, b); }
      : sort === 'year' ? function (a, b) { return yearOf(b) - yearOf(a) || byId(a, b); }
      : sort === 'rating' ? function (a, b) { return ratingOf(b) - ratingOf(a) || byId(a, b); }
      : byId;
    return list.slice().sort(cmp);
  }
  /** Сведения TMDB нужны всей медиатеке (категории, рейтинг): спрашиваем в фоне, по готовности — перерисовка. */
  function classifyLibrary() {
    posters.classify(S.shown, function () {
      presence.update();
      var el = document.activeElement;
      if (S.tab === 'library' && S.find.mode === 'library' && !(el && el.tagName === 'SELECT')) render();
    });
  }
  function randomFrom(list, not) {
    var pool = list.length > 1 && not ? list.filter(function (it) { return it !== not; }) : list;
    return pool.length ? pool[Math.floor(Math.random() * pool.length)] : null;
  }
  /** «Случайный фильм»: из того, что видно в медиатеке (поиск и категория). */
  function rollRandom(not) {
    var list = libraryList();
    if (!list.length) { toast('В медиатеке нечего выбрать: смените категорию или поиск.', true); return; }
    openSheet(randomFrom(list, not), { random: true });
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
        var d = msg.data || {}, wasPoll = !!(S.room && S.room.poll), wasSpin = !!(S.room && S.room.spin);
        ['watchers', 'candidates', 'poll', 'spin', 'host_id', 'host_name'].forEach(function (k) { if (k in d) S.room[k] = d[k]; });
        // голосование или рулетка началась — карточка выбора у всех
        if ((!wasPoll && S.room.poll) || (!wasSpin && S.room.spin)) { S.tab = 'poll'; S.sheet = null; }
        render();
        break;
      }
      case 'poll_result': {
        var p = msg.data || {};
        // Барабан уже крутится у нас: победителя он покажет сам, в момент остановки (и потом переведёт на «Сейчас»)
        if (p.roulette && reel && reel.key === p.key) break;
        if (p.key) {
          toast((p.roulette ? 'Рулетка выбрала «' : 'Победил «') + (p.name || 'фильм') + '» — включаем в комнате.');
          S.tab = 'now';
        } else toast(p.roulette ? 'Рулетка не выбрала фильм.' : 'Никто не проголосовал: фильм не сменился.');
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
  // В iframe Discord navigator.clipboard закрыт (нет allow="clipboard-write"), поэтому сначала старый
  // execCommand('copy') — он работает по нажатию и в iframe; не вышло — clipboard; совсем никак — код в подсказке
  function copyText(text) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.cssText = 'position:fixed;top:0;left:0;opacity:0;pointer-events:none';
    document.body.appendChild(ta);
    ta.select();
    ta.setSelectionRange(0, text.length);
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(ta);
    if (ok) return Promise.resolve();
    try { return navigator.clipboard.writeText(text); } catch (e) { return Promise.reject(e); }
  }
  // «Позвать в чат»: бот сервера пишет в чат голосового канала; бота нет — ссылку копируем сами (сразу по нажатию,
  // пока Discord разрешает копирование, поэтому после первого «бота нет» сервер больше не спрашиваем)
  function joinLink() { return S.site ? S.site + '/join/' + encodeURIComponent(S.room.id) : ''; }
  function copyInvite(link) {
    if (!link) { toast('Сервер не знает свой адрес: позовите кодом ' + prettyCode(S.room.id) + '.', true); return; }
    copyText(link).then(function () { toast('Ссылка скопирована! Отправьте её в чат канала.'); },
      function () { toast('Отправьте в чат канала: ' + link); });
  }
  function invite() {
    if (S.inviteFallback) { copyInvite(joinLink()); return; }
    api('api/activity/rooms/' + S.room.id + '/invite', {}).then(function (r) {
      if (r.sent) { toast('Приглашение отправлено в чат канала!'); return; }
      if (r.reason === 'no_bot') S.inviteFallback = true;
      copyInvite(r.link || joinLink());
    }, function (e) { toast(errText(e), true); });
  }
  function copyCode() {
    var code = prettyCode(S.room.id);
    copyText(code).then(function () { toast('Код скопирован: ' + code); },
      function () { toast('Скопировать не дал Discord. Код комнаты: ' + code); });
  }


  // ---- отрисовка ----
  function render() {
    syncReel();
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
    var focusId = document.activeElement && document.activeElement.id, focusSearch = focusId === 'q' || focusId === 'aiq';
    var caret = focusSearch ? document.activeElement.selectionStart : 0;
    app.replaceChildren(top, page);
    if (S.sheet) app.appendChild(viewSheet());
    if (focusSearch) { var q = document.getElementById(focusId); if (q) { q.focus(); q.setSelectionRange(caret, caret); } }
    presence.update();
    if (reel && reel.phase === 'spin' && !spinning) {
      spinning = true;
      requestAnimationFrame(function f() { if (reelFrame()) requestAnimationFrame(f); else spinning = false; });
    }
  }
  var spinning = false;

  function tabs() {
    var n = S.room.candidates ? S.room.candidates.length : 0;
    function tab(id, ico, text, badge) {
      return h('button', { class: 'tab' + (S.tab === id ? ' on' : ''), type: 'button', on: { click: function () { S.tab = id; render(); } } },
        [icon(ico), text, badge ? h('span', { class: 'badge', text: String(badge) }) : null]);
    }
    return h('div', { class: 'tabs' }, [
      tab('now', 'film', 'Сейчас'),
      tab('library', 'grid', 'Медиатека'),
      tab('poll', S.room.spin || reel ? 'dice' : 'vote', S.room.spin || reel ? 'Рулетка' : S.room.poll ? 'Голосуем' : 'Голосование', S.room.poll || S.room.spin || reel ? '!' : n)
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
        h('div', { class: 'row' }, [
          btn('lamp', 'grid', host ? 'Выбрать в медиатеке' : 'Предложить фильм', function () { S.tab = 'library'; S.find.mode = 'library'; render(); }),
          btn('', 'dice', 'Случайный фильм', function () { rollRandom(); }),
          btn('', 'sparkles', 'Посоветовать фильм', function () { S.tab = 'library'; S.find.mode = 'ai'; render(); })
        ])
      ])]));
    }
    var main = [card];
    if (r.poll || r.spin) {
      main.push(h('div', { class: 'panel' }, [h('div', { class: 'row' }, [
        icon(r.spin ? 'dice' : 'vote'), h('b', { text: r.spin ? 'Крутится рулетка' : 'Идёт голосование' }),
        r.poll ? h('span', { class: 'timer', id: 'timer', text: pollLeft() }) : null,
        h('div', { class: 'spacer' }),
        host && r.poll ? finishButton(r.poll) : null,
        btn('blue small', null, r.spin ? 'Смотреть' : 'Голосовать', function () { S.tab = 'poll'; render(); })
      ])]));
    }
    if (!f) main.push(viewFresh());
    card = h('div', {}, main);

    var side = h('div', {}, [
      h('div', { class: 'panel' }, [
        h('div', { class: 'h', text: 'Код для ТВ и приложений' }),
        h('div', { class: 'tv-code', text: prettyCode(r.id) }),
        h('div', { class: 'small muted', text: 'На телевизоре: «Вместе» → «Войти по коду». В приложении на ПК или телефоне — так же, или кнопкой «Открыть».' }),
        h('div', { class: 'row', style: 'margin-top:12px' }, [btn('small', 'copy', 'Скопировать', copyCode),
          btn('small', 'chat', 'Позвать в чат', invite, { title: 'Отправить в чат голосового канала код и ссылку на комнату' })])
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

  // ---- поиск раздач на трекерах (docs/DISCORD-ACTIVITY.md, «Поиск раздач») ----
  // Серверные трекеры — обычный /api/search; RuTracker — у ПК комнаты (его cookie): сервер пересылает запрос в поток
  // комнаты, ПК отвечает. Добавленная раздача ждёт AllDebrid, потом «Включить в комнате» или «Предложить».
  function btihOf(magnet) { var m = /btih:([0-9a-z]+)/i.exec(magnet || ''); return m ? m[1].toLowerCase() : ''; }
  // Фильтры поиска помнятся в браузере (как на ПК): пресет, качество, тип, сортировка, сиды, RU, выключенные трекеры
  var FILTER_KEY = 'sm.act.find';
  var FILTER_DEFAULT = { preset: 'ALL', resolution: '', kind: 'video', sort: 'DEFAULT', onlySeeded: false, onlyRu: false, refine: '', off: [] };
  function filters() {
    var F = S.find;
    if (!F.f) {
      var saved = {};
      try { saved = JSON.parse(localStorage.getItem(FILTER_KEY) || '{}') || {}; } catch (e) { /* без хранилища */ }
      F.f = {};
      Object.keys(FILTER_DEFAULT).forEach(function (k) { F.f[k] = k in saved && typeof saved[k] === typeof FILTER_DEFAULT[k] ? saved[k] : FILTER_DEFAULT[k]; });
      if (!Array.isArray(F.f.off)) F.f.off = [];
    }
    return F.f;
  }
  function setFilter(k, v) {
    filters()[k] = v;
    S.find.page = 0;
    try { localStorage.setItem(FILTER_KEY, JSON.stringify(S.find.f)); } catch (e) { /* без хранилища */ }
    render();
  }
  var PC_SOURCE = 'RuTracker (ПК)';
  function trackerOn(name) { return filters().off.indexOf(name) < 0; }
  function toggleTracker(name) {
    var off = filters().off.slice(), i = off.indexOf(name);
    if (i >= 0) off.splice(i, 1); else off.push(name);
    setFilter('off', off);
  }

  function searchTrackers() {
    var F = S.find, q = F.q.trim();
    if (q.length < 2) { toast('Введите хотя бы два символа.', true); return; }
    var gen = ++F.busy, cleaned = SC.cleanQuery(q) || q;
    F.asked = q; F.server = null; F.serverErr = ''; F.notes = {}; F.page = 0;
    F.pc = trackerOn(PC_SOURCE) ? { wait: true } : { off: true };
    render();
    api('api/search?q=' + encodeURIComponent(cleaned)).then(function (r) {
      if (gen !== F.busy) return;
      F.server = (Array.isArray(r.results) ? r.results : []).map(function (x) {
        var hit = SC.toHit(x);
        if (!hit) return null;
        hit.uid = 's:' + x.id;
        hit.sources = Array.isArray(x.sources) && x.sources.length ? x.sources : [String(x.provider || '').split(' · ')[0]];
        return hit;
      }).filter(Boolean);
      F.providers = Array.isArray(r.providers) ? r.providers : [];
      render();
    }, function (e) { if (gen !== F.busy) return; F.server = []; F.serverErr = errText(e); render(); });
    if (F.pc.off) return;
    api('api/activity/rooms/' + S.room.id + '/rutracker/search', { query: cleaned }).then(function (r) {
      if (gen !== F.busy) return;
      F.pc = r.ok ? { hits: (r.hits || []).map(function (x, i) {
        var hit = SC.toHit({ id: 'p' + i, title: x.title, size: x.size, seeders: x.seeders, leechers: x.leechers, ru: true, kind: x.kind,
          provider: PC_SOURCE, date: x.date, magnet: x.magnet });
        hit.uid = 'p:' + (x.topic_id || btihOf(x.magnet) || i);
        hit.pc = r.pc; hit.topic_id = x.topic_id; hit.sources = [PC_SOURCE];
        hit.source = 'RuTracker · ПК ' + (r.pc && r.pc.name || '');
        return hit;
      }), pc: r.pc } : { error: r.error || 'ПК не ответил.' };
      render();
    }, function (e) { if (gen !== F.busy) return; F.pc = { error: errText(e) }; render(); });
  }
  /** Раздачи после фильтров: RuTracker с ПК (если сервер не нашёл ту же) и сервер; выключенные трекеры — мимо. */
  function trackerHits() {
    var F = S.find, f = filters(), all = [], seen = {};
    (F.server || []).forEach(function (h) { var b = btihOf(h.magnet); if (b) seen[b] = 1; });
    if (F.pc && F.pc.hits) F.pc.hits.forEach(function (h) { var b = btihOf(h.magnet); if (!(b && seen[b])) all.push(h); });
    all = all.concat(F.server || []);
    all = all.filter(function (h) { return h.sources.some(trackerOn); });
    return SC.filter(all, { query: F.asked, kind: f.kind, preset: f.preset, resolution: f.resolution || null,
      onlySeeded: f.onlySeeded, onlyRu: f.onlyRu, refine: f.refine, sort: f.sort });
  }
  function note(uid, text, bad) { S.find.notes[uid] = { text: text, bad: !!bad }; render(); }
  function addHit(hit, how) {
    note(hit.uid, 'Добавляю…');
    var p;
    if (hit.pc && hit.magnet) p = api('api/magnets', { magnet: hit.magnet });
    else if (hit.pc) {
      note(hit.uid, 'ПК качает .torrent с RuTracker…');
      p = api('api/activity/rooms/' + S.room.id + '/rutracker/add', { pc: hit.pc.key, topic_id: hit.topic_id, title: hit.title })
        .then(function (r) { if (!r.ok) throw new Error(r.error || 'ПК не смог добавить раздачу.'); return r; });
    } else p = api('api/search/add', { id: hit.id });
    p.then(function (r) { return waitReady(hit.uid, r.id, r.ready); })
      .then(function (id) { return libraryWith(id, 0).then(function () { useAdded(hit.uid, id, how); }); })
      .catch(function (e) { note(hit.uid, errText(e), true); });
  }
  /** Ждём, пока AllDebrid докачает: первую минуту раз в 4 с, потом раз в 15 с; не дольше 20 минут. */
  function waitReady(uid, id, ready) {
    if (ready) return Promise.resolve(id);
    note(uid, 'Добавлено, облако качает…');
    var started = Date.now();
    return new Promise(function (resolve, reject) {
      (function step() {
        setTimeout(function () {
          if (Date.now() - started > 20 * 60000) { reject(new Error('Долго качается: раздача появится в медиатеке, когда докачается.')); return; }
          api('api/magnets/' + id).then(function (st) {
            if (Number(st.statusCode) === 4 || st.ready) { resolve(id); return; }
            if (Number(st.statusCode) >= 5) { reject(new Error('Облако не скачало: ' + (st.status || 'ошибка'))); return; }
            var pct = st.size > 0 ? ' · ' + Math.floor(st.downloaded * 100 / st.size) + '%' : '';
            note(uid, (st.status || 'Облако качает') + pct);
            step();
          }, step);
        }, Date.now() - started < 60000 ? 4000 : 15000);
      })();
    });
  }
  /** Медиатека сервера обновляется не мгновенно: до трёх попыток через 2 с, пока раздача не появится. */
  function libraryWith(id, tries) {
    return loadLibrary().then(function () {
      if (S.byId[id] || tries >= 3) return;
      return new Promise(function (r) { setTimeout(r, 2000); }).then(function () { return libraryWith(id, tries + 1); });
    });
  }
  function useAdded(uid, id, how) {
    var it = S.byId[id];
    if (!it) { note(uid, 'Добавлено. В медиатеке появится, когда сервер её обработает.'); return; }
    var entries = entriesOf(it);
    note(uid, 'Готово: в медиатеке.');
    if (entries.length === 1) {
      if (how === 'play' && isHost()) putEntry(it, entries[0]); else suggest(it, entries[0]);
    } else {
      openSheet(it);
      toast('Выберите серию или файл.');
    }
  }
  var PAGE = 20;
  function select(value, options, onChange, label) {
    return h('select', { class: 'sel', 'aria-label': label, on: { change: function (e) { onChange(e.target.value); } } }, options.map(function (o) {
      return h('option', { value: o[0], selected: o[0] === value, text: o[1] });
    }));
  }
  function check(on, text, onChange) {
    return h('label', { class: 'check' }, [h('input', { type: 'checkbox', checked: on, on: { change: function (e) { onChange(e.target.checked); } } }), text]);
  }
  function activeFilters() {
    var f = filters(), n = 0;
    if (f.resolution) n++;
    if (f.kind !== 'video') n++;
    if (f.sort !== 'DEFAULT') n++;
    if (f.onlySeeded) n++;
    if (f.onlyRu) n++;
    if (f.refine.trim()) n++;
    return n + f.off.length;
  }
  function viewFilters() {
    var F = S.find, f = filters();
    var trackers = F.providers.map(function (p) { return { name: p.name, count: p.count, ok: p.ok, error: p.error }; });
    trackers.push({ name: PC_SOURCE, count: F.pc && F.pc.hits ? F.pc.hits.length : null, ok: !(F.pc && F.pc.error), error: F.pc && F.pc.error });
    return h('div', { class: 'filters' }, [
      h('div', { class: 'filter-row' }, [
        select(f.resolution, [['', 'Любое качество'], ['4K', '4K'], ['1080p', '1080p'], ['720p', '720p']], function (v) { setFilter('resolution', v); }, 'Качество'),
        select(f.kind, [['video', 'Фильмы и сериалы'], ['movie', 'Только фильмы'], ['series', 'Только сериалы'], ['cartoon', 'Только мультфильмы'], ['all', 'Всё подряд']],
          function (v) { setFilter('kind', v); }, 'Тип'),
        select(f.sort, [['DEFAULT', 'Сначала подходящие'], ['SEEDS_DESC', 'По сидам'], ['SIZE_ASC', 'Сначала лёгкие'], ['SIZE_DESC', 'Сначала тяжёлые'], ['BITRATE_ASC', 'Битрейт ниже']],
          function (v) { setFilter('sort', v); }, 'Сортировка')
      ]),
      h('div', { class: 'filter-row' }, [
        check(f.onlySeeded, 'Только с сидами', function (v) { setFilter('onlySeeded', v); }),
        check(f.onlyRu, 'Только русские трекеры', function (v) { setFilter('onlyRu', v); }),
        h('label', { class: 'search refine' }, [h('input', { type: 'search', placeholder: 'Уточнить: дубляж, hevc, remux…', value: f.refine,
          on: { change: function (e) { setFilter('refine', e.target.value); } } })])
      ]),
      h('div', { class: 'h', style: 'margin:12px 0 8px', text: 'Трекеры' }),
      h('div', { class: 'chips' }, trackers.map(function (t) {
        var on = trackerOn(t.name);
        return h('button', { class: 'chip tracker' + (on ? ' on' : '') + (t.ok ? '' : ' failed'), type: 'button', title: t.error || (on ? 'Выключить' : 'Включить'),
          on: { click: function () { toggleTracker(t.name); } } }, [on ? icon('check') : icon('close'), t.name + (t.count != null && t.ok ? ' · ' + t.count : '')]);
      })),
      F.providers.length ? null : h('div', { class: 'small muted', text: 'Список трекеров появится после первого поиска.' })
    ]);
  }
  function pager(total) {
    var F = S.find, pages = Math.ceil(total / PAGE);
    if (pages <= 1) return null;
    var go = function (p) { F.page = p; render(); var pg = document.querySelector('.page'); if (pg) window.scrollTo(0, 0); };
    var nums = [];
    for (var i = 0; i < pages; i++) {
      if (i === 0 || i === pages - 1 || Math.abs(i - F.page) <= 1) nums.push(i);
      else if (nums[nums.length - 1] !== -1) nums.push(-1);
    }
    return h('div', { class: 'pager' }, [
      btn('small', 'back', null, function () { go(F.page - 1); }, { disabled: F.page === 0, title: 'Назад' })
    ].concat(nums.map(function (n) {
      return n < 0 ? h('span', { class: 'muted', text: '…' }) :
        h('button', { class: 'chip' + (n === F.page ? ' on' : ''), type: 'button', on: { click: function () { go(n); } } }, [String(n + 1)]);
    })).concat([btn('small', 'fwd', null, function () { go(F.page + 1); }, { disabled: F.page >= pages - 1, title: 'Дальше' })]));
  }
  function hitCard(r, host) {
    var n = S.find.notes[r.uid], busy = n && !n.bad && n.text !== 'Готово: в медиатеке.';
    var clean = r.release.cleanTitle || r.title;
    var badges = SC.badges(r.release).map(function (b) { return h('span', { class: 'rb ' + b.tone, text: b.text }); });
    var meta = [
      h('span', { class: 'tag' + (r.pc ? ' lamp' : '') }, [icon(r.pc ? 'devices' : 'globe'), r.source]),
      h('span', { class: 'seeds' + (r.seeders > 0 ? ' live' : '') }, [icon('seeds'), String(r.seeders || 0)]),
      r.size ? h('span', { text: model.formatBytes(r.size) }) : null,
      r.kind === 'series' ? h('span', { text: 'сериал' }) : r.kind === 'cartoon' ? h('span', { text: 'мультфильм' }) : null,
      r.date ? h('span', { text: r.date }) : null
    ];
    return h('div', { class: 'hit' }, [
      h('div', { class: 'hit-title', text: clean }),
      clean !== r.title ? h('div', { class: 'hit-raw', text: r.title }) : null,
      badges.length ? h('div', { class: 'hit-badges' }, badges) : null,
      h('div', { class: 'hit-meta' }, meta),
      n ? h('div', { class: 'small ' + (n.bad ? 'bad' : 'muted'), text: n.text }) : null,
      busy ? null : h('div', { class: 'row' }, [
        host ? btn('lamp small', 'play', 'Включить в комнате', function () { addHit(r, 'play'); }) : null,
        S.room.poll || S.room.spin ? null : btn('small', 'vote', 'Предложить', function () { addHit(r, 'suggest'); })
      ])
    ]);
  }
  function viewTrackers() {
    var F = S.find, f = filters(), host = isHost();
    var input = h('input', { id: 'q', type: 'search', placeholder: 'Название фильма или сериала', value: F.q, autocomplete: 'off',
      on: { input: function (e) { F.q = e.target.value; }, keydown: function (e) { if (e.key === 'Enter') searchTrackers(); } } });
    var nf = activeFilters();
    var kids = [
      h('div', { class: 'search-row' }, [
        h('label', { class: 'search' }, [icon('search'), input]),
        btn('lamp', 'search', 'Найти', searchTrackers)
      ]),
      h('div', { class: 'chips presets' }, [
        ['ALL', 'Все'], ['BALANCED', 'Оптимальные · 1080p'], ['MAX_QUALITY', 'Макс. качество'], ['MOBILE', 'Для мобильной сети']
      ].map(function (p) {
        return h('button', { class: 'chip' + (f.preset === p[0] ? ' on' : ''), type: 'button', on: { click: function () { setFilter('preset', p[0]); } } }, [p[1]]);
      }).concat([h('div', { class: 'grow' }),
        h('button', { class: 'chip' + (F.filtersOpen ? ' on' : ''), type: 'button', on: { click: function () { F.filtersOpen = !F.filtersOpen; render(); } } },
          [icon('filter'), 'Фильтры' + (nf ? ' · ' + nf : '')])]))
    ];
    if (F.filtersOpen) kids.push(viewFilters());
    if (!F.asked) {
      kids.push(h('div', { class: 'empty', text: 'Поиск по трекерам сервера и по RuTracker через ПК, который вошёл в эту комнату.' }));
      return h('div', {}, kids);
    }
    var lines = [];
    lines.push(F.server === null ? 'Трекеры сервера: ищем…' : F.serverErr ? 'Трекеры сервера: ' + F.serverErr : 'Трекеры сервера: ' + F.server.length);
    lines.push(F.pc.off ? 'RuTracker с ПК: выключен в фильтрах' : F.pc.wait ? 'RuTracker с ПК: ищем…' : F.pc.error ? 'RuTracker с ПК: ' + F.pc.error :
      'RuTracker с ПК ' + (F.pc.pc && F.pc.pc.name || '') + ': ' + F.pc.hits.length);
    var hits = trackerHits(), total = (F.server || []).length + (F.pc && F.pc.hits ? F.pc.hits.length : 0);
    if (hits.length < total) lines.push('Под фильтры подходит ' + hits.length + ' из ' + total);
    kids.push(h('div', { class: 'small muted find-status' }, lines.map(function (l) { return h('div', { text: l }); })));
    if (!hits.length) {
      if (F.server !== null && !F.pc.wait) kids.push(h('div', { class: 'empty', text: total ? 'Под фильтры ничего не подходит.' : 'Раздач не нашлось.' }));
      return h('div', {}, kids);
    }
    var pages = Math.ceil(hits.length / PAGE);
    if (F.page >= pages) F.page = pages - 1;
    kids.push(h('div', { class: 'hits' }, hits.slice(F.page * PAGE, F.page * PAGE + PAGE).map(function (r) { return hitCard(r, host); })));
    kids.push(pager(hits.length));
    return h('div', {}, kids);
  }

  function viewLibrary() {
    var F = S.find;
    function mode(id, ico, text, onPick) {
      return h('button', { class: 'chip' + (F.mode === id ? ' on' : ''), type: 'button', on: { click: function () {
        F.mode = id;
        if (onPick) onPick();
        render();
      } } }, [icon(ico), text]);
    }
    var modes = h('div', { class: 'chips' }, [
      mode('library', 'grid', 'В медиатеке'),
      mode('trackers', 'globe', 'На трекерах', function () { if (!F.q && S.query) F.q = S.query; }),
      mode('ai', 'sparkles', 'Посоветовать')
    ]);
    if (F.mode === 'trackers') return h('div', {}, [modes, viewTrackers()]);
    if (F.mode === 'ai') return h('div', {}, [modes, viewAi()]);
    return h('div', {}, [modes, viewMediaLibrary()]);
  }

  /** Плитка фильма: постер, название, год или сезоны; ведущему у фильма — «Включить» в один клик. */
  function tileOf(it, quick) {
    var name = titleOf(it), poster = h('div', { class: 'poster' }, [h('span', { class: 'poster-title', text: name })]);
    var cand = S.room.candidates && S.room.candidates.some(function (c) { return Number(c.key.split('|')[0]) === it.m.id; });
    if (cand) poster.appendChild(h('span', { class: 'mark tag lamp' }, [icon('vote'), 'В списке']));
    var rating = ratingOf(it);
    if (rating) poster.appendChild(h('span', { class: 'rating' }, [icon('star'), rating.toFixed(1)]));
    posterInto(poster, it);
    var p = it.playable, entries = quick ? entriesOf(it) : [];
    if (quick && isHost() && entries.length === 1) {
      poster.appendChild(h('span', { class: 'quick', title: 'Включить в комнате', role: 'button', on: { click: function (ev) {
        ev.stopPropagation();
        putEntry(it, entries[0]);
      } } }, [icon('play')]));
    }
    var sub = p.kind === 'series' ? model.seriesLine(p) : (yearOf(it) || '');
    return h('button', { class: 'tile', type: 'button', on: { click: function () { openSheet(it); } } }, [
      poster, h('div', { class: 'tname', text: name }), sub ? h('div', { class: 'tsub', text: String(sub) }) : null
    ]);
  }

  /** «Сейчас», пока фильм не выбран: последние добавленные — включить или открыть в один клик. */
  function viewFresh() {
    var list = S.shown.slice().sort(function (a, b) { return b.m.id - a.m.id; }).slice(0, 8);
    if (!list.length) return null;
    return h('div', { class: 'panel' }, [
      h('div', { class: 'row', style: 'margin-bottom:12px' }, [
        h('div', { class: 'h', style: 'margin:0', text: 'Новое в медиатеке' }), h('div', { class: 'spacer' }),
        btn('small', 'grid', 'Вся медиатека', function () { S.tab = 'library'; S.find.mode = 'library'; render(); })
      ]),
      h('div', { class: 'rail' }, list.map(function (it) { return tileOf(it, true); }))
    ]);
  }

  function viewMediaLibrary() {
    var list = libraryList(), cat = S.lib.cat;
    var input = h('input', { id: 'q', type: 'search', placeholder: 'Поиск по медиатеке', value: S.query, autocomplete: 'off',
      on: { input: function (e) { S.query = e.target.value; render(); } } });
    var tiles = list.slice(0, 240).map(function (it) { return tileOf(it, false); });
    // Мультфильмы, аниме и рейтинг знает TMDB: пока сведения не пришли, список неполный — говорим об этом
    var pending = posters.pendingMeta(), needMeta = cat === 'cartoon' || cat === 'anime' || S.lib.sort === 'rating';
    return h('div', {}, [
      h('div', { class: 'search-row' }, [
        h('label', { class: 'search' }, [icon('search'), input]),
        btn('', 'dice', null, function () { rollRandom(); }, { title: 'Случайный фильм из того, что видно' })
      ]),
      h('div', { class: 'chips presets' }, CATS.map(function (c) {
        return h('button', { class: 'chip' + (cat === c[0] ? ' on' : ''), type: 'button', on: { click: function () { setLib('cat', c[0]); } } }, [c[1]]);
      }).concat([h('div', { class: 'grow' }), select(S.lib.sort, SORTS, function (v) { setLib('sort', v); }, 'Сортировка')])),
      needMeta && pending ? h('div', { class: 'small muted find-status', text: 'Уточняем жанры и рейтинг по TMDB: осталось ' + pending + '…' }) : null,
      tiles.length ? h('div', { class: 'tiles' }, tiles) : h('div', { class: 'empty', text: S.query.trim() || cat !== 'all' ? 'Ничего не нашлось.' : 'Медиатека пуста.' })
    ]);
  }

  // ---- «Посоветовать фильм»: подборка ИИ по настроению (docs/AI-DISCOVERY.md) ----
  // Что из подборки уже есть в медиатеке — «Включить» и «Предложить»; чего нет — «Найти раздачу» на трекерах.
  var VIBES = [
    ['🍿', 'Комедия для компании', 'смешная комедия для компании друзей на вечер'],
    ['😱', 'Триллер с финалом', 'захватывающий триллер с неожиданным финалом'],
    ['🚀', 'Масштабная фантастика', 'масштабная эпичная фантастика с размахом'],
    ['☕', 'Уютный вечер', 'уютное тёплое кино на вечер под пледом'],
    ['🎲', 'Кинорулетка', 'случайная подборка сильных фильмов разных жанров, стран и десятилетий', true]
  ];
  function runAi(q, fresh) {
    var A = S.ai;
    q = String(q || '').replace(/\s+/g, ' ').trim();
    if (q.length < 2) { toast('Опишите, что хочется посмотреть.', true); return; }
    var gen = ++A.busy;
    A.q = q; A.asked = q; A.err = '';
    render();
    api('api/search/ai-discovery', { query: q.slice(0, 300), fresh: !!fresh }).then(function (r) {
      if (gen !== A.busy) return;
      A.busy = 0;
      A.items = Array.isArray(r.items) ? r.items.filter(function (x) { return x && typeof x.title === 'string'; }) : [];
      if (!A.items.length) A.err = 'ИИ ничего не подобрал. Попробуйте описать иначе.';
      render();
    }, function (e) {
      if (gen !== A.busy) return;
      A.busy = 0;
      // прежняя подборка остаётся на экране, сверху — причина
      A.err = e.status === 404 ? 'Сервер пока не умеет подбирать фильмы: его нужно обновить.' : errText(e);
      render();
    });
  }
  function normTitle(t) { return String(t || '').toLowerCase().replace(/ё/g, 'е').replace(/[^a-zа-я0-9]+/g, ' ').trim(); }
  function fileName(p) { return p ? String(p).slice(String(p).lastIndexOf('/') + 1) : ''; }
  /** Ключи медиатеки для сверки (как SearchModel::libraryKeys на ПК): постер TMDB, названия, год, имя раздачи. */
  function libraryKeys() {
    return S.shown.map(function (it) {
      var ai = it.m.aiTitle, names = {};
      [it.baseTitle, titleOf(it), ai && ai.status === 'accepted' ? ai.original : ''].forEach(function (n) { var k = normTitle(n); if (k) names[k] = 1; });
      return { it: it, poster: fileName(posters.pathOf(it.posterQueries || [])), names: names, year: model.itemYear(it), text: ' ' + normTitle(it.m.name) + ' ' };
    });
  }
  function matchLibrary(keys, film) {
    var year = Number(film.year) || 0, poster = fileName(film.poster), wanted = [];
    [film.title, film.original, film.tmdbTitle].forEach(function (t) { var k = normTitle(t); if (k && wanted.indexOf(k) < 0) wanted.push(k); });
    if (!wanted.length) return null;
    function yearFits(y, need) { return !year || !y ? !need : Math.abs(y - year) <= 1; }
    var byName = null, byText = null;
    for (var i = 0; i < keys.length; i++) {
      var k = keys[i];
      if (poster && k.poster === poster) return k.it;
      if (!byName && yearFits(k.year, false) && wanted.some(function (w) { return k.names[w]; })) byName = k.it;
      if (!byName && !byText && yearFits(k.year, true) && wanted.some(function (w) { return w.length >= 5 && k.text.indexOf(' ' + w + ' ') >= 0; })) byText = k.it;
    }
    return byName || byText;
  }
  // Раздачи русских фильмов названы по-русски, остальные ищем по оригиналу (SearchModel::trackerTitle на ПК)
  function trackerTitle(film) {
    var o = film.original || '';
    if (!o || /[а-яё]/i.test(o) || ['ru', 'uk', 'be'].indexOf(film.lang) >= 0) return film.title;
    return o;
  }
  function findTorrent(film) {
    var F = S.find;
    F.mode = 'trackers';
    F.q = trackerTitle(film) + (film.year ? ' ' + film.year : '');
    searchTrackers();
  }
  function aiCard(film, it, host) {
    var poster = h('div', { class: 'ai-poster' });
    posters.img(film.poster, function (u) { poster.style.backgroundImage = 'url("' + u + '")'; });
    var entries = it ? entriesOf(it) : [];
    var line = [film.original && film.original !== film.title ? film.original : '', film.year || '', film.kind === 'series' ? 'сериал' : '']
      .filter(Boolean).join(' · ');
    var genres = Array.isArray(film.genres) ? film.genres.filter(function (g) { return typeof g === 'string'; }).slice(0, 3) : [];
    var actions;
    if (it) {
      actions = [
        host ? btn('lamp small', 'play', 'Включить в комнате', function () { if (entries.length === 1) putEntry(it, entries[0]); else openSheet(it); }) : null,
        S.room.poll || S.room.spin ? null : btn('small', 'vote', 'Предложить', function () { if (entries.length === 1) suggest(it, entries[0]); else openSheet(it); }),
        btn('small', null, 'Подробнее', function () { openSheet(it); })
      ];
    } else {
      actions = [btn('small', 'globe', 'Найти раздачу', function () { findTorrent(film); })];
    }
    return h('div', { class: 'ai-card' }, [poster, h('div', { class: 'ai-body' }, [
      h('div', { class: 'row', style: 'gap:8px' }, [
        h('div', { class: 'ai-title', text: film.title }),
        it ? h('span', { class: 'tag lamp', text: 'В медиатеке' }) : null,
        Number(film.rating) > 0 ? h('span', { class: 'ai-rating' }, [icon('star'), Number(film.rating).toFixed(1)]) : null
      ]),
      line ? h('div', { class: 'small muted', text: line }) : null,
      genres.length ? h('div', { class: 'small muted', text: genres.join(', ') }) : null,
      film.reason ? h('div', { class: 'ai-reason', text: film.reason }) : null,
      h('div', { class: 'spacer' }),
      h('div', { class: 'row', style: 'gap:6px' }, actions)
    ])]);
  }
  function viewAi() {
    var A = S.ai, host = isHost();
    var input = h('input', { id: 'aiq', type: 'search', placeholder: 'Свой запрос: настроение, сюжет или похожие фильмы…', value: A.q, autocomplete: 'off',
      on: { input: function (e) { A.q = e.target.value; }, keydown: function (e) { if (e.key === 'Enter') runAi(A.q); } } });
    var kids = [
      h('div', { class: 'search-row' }, [
        h('label', { class: 'search' }, [icon('sparkles'), input]),
        btn('lamp', 'sparkles', A.busy ? 'Подбираем…' : 'Подобрать', function () { runAi(A.q); }, { disabled: !!A.busy })
      ]),
      h('div', { class: 'chips' }, VIBES.map(function (v) {
        return h('button', { class: 'chip', type: 'button', disabled: !!A.busy, on: { click: function () { A.q = v[2]; runAi(v[2], v[3]); } } }, [v[0] + ' ' + v[1]]);
      }))
    ];
    if (A.busy) kids.push(h('div', { class: 'small muted find-status', text: 'ИИ подбирает фильмы…' }));
    if (A.err) kids.push(h('div', { class: 'small bad find-status', text: A.err }));
    if (!A.items) {
      kids.push(h('div', { class: 'empty', text: 'Опишите, что хочется посмотреть всей компанией, — ИИ подберёт фильмы и покажет, что уже есть в медиатеке.' }));
      return h('div', {}, kids);
    }
    var keys = libraryKeys(), inLib = 0;
    var cards = A.items.map(function (film) { var it = matchLibrary(keys, film); if (it) inLib++; return aiCard(film, it, host); });
    if (!A.busy && A.items.length) {
      kids.push(h('div', { class: 'row find-status' }, [
        h('span', { class: 'small muted', text: 'По запросу «' + A.asked + '»: ' + A.items.length + (inLib ? ' · ' + inLib + ' уже в медиатеке' : '') }),
        h('div', { class: 'spacer' }),
        btn('small', 'dice', 'Другие варианты', function () { runAi(A.asked, true); })
      ]));
    }
    kids.push(h('div', { class: 'ai-cards' + (A.busy ? ' dim' : '') }, cards));
    return h('div', {}, kids);
  }

  function openSheet(it, opts) {
    var versions = S.versions[it.m.id] || [it];
    S.sheet = { versions: versions, v: 0, season: null, det: null, random: !!(opts && opts.random), roll: Date.now() };
    var sh = S.sheet;
    posters.details(versions[0]).then(function (d) { if (S.sheet === sh) { sh.det = d; render(); } });
    render();
  }

  function runtimeText(min, series) {
    if (!min) return '';
    var t = min >= 60 ? Math.floor(min / 60) + ' ч' + (min % 60 ? ' ' + min % 60 + ' мин' : '') : min + ' мин';
    return series ? 'серия ~' + t : t;
  }

  function viewSheet() {
    var sh = S.sheet, it = sh.versions[sh.v], host = isHost(), entries = entriesOf(it), series = it.playable.kind === 'series';
    var seasons = [];
    entries.forEach(function (e) { if (e.season != null && seasons.indexOf(e.season) < 0) seasons.push(e.season); });
    var curKey = S.room.file ? S.room.file.key : '';
    if (sh.season == null || seasons.indexOf(sh.season) < 0) {
      var cur = resolve(curKey);
      sh.season = cur && cur.it === it && cur.entry.season != null ? cur.entry.season : seasons[0];
    }
    var shownEntries = seasons.length > 1 ? entries.filter(function (e) { return e.season === sh.season; }) : entries;
    var d = sh.det || {}, mt = posters.meta(it) || {}, name = titleOf(it);
    var poster = h('div', { class: 'sheet-poster' + (sh.random ? ' rolled' : '') });
    posterInto(poster, it);
    var close = function () { S.sheet = null; render(); };
    var original = d.original || mt.o || '';
    var rating = Number(d.rating) || mt.r || 0, year = yearOf(it);
    var genres = Array.isArray(d.genres) && d.genres.length ? d.genres : mt.g || [];
    var facts = [h('span', { class: 'tag' + (series ? '' : ' lamp'), text: typeLabel(it) }),
      year ? h('span', { text: String(year) }) : null,
      d.runtime ? h('span', { text: runtimeText(d.runtime, series) }) : null,
      series ? h('span', { text: model.seriesLine(it.playable) }) : null,
      rating ? h('span', { class: 'ai-rating' }, [icon('star'), rating.toFixed(1)]) : null];
    var one = entries.length === 1 ? entries[0] : null, oneKey = one ? keyOf(it, one) : '';
    var oneIn = one && S.room.candidates && S.room.candidates.some(function (c) { return c.key === oneKey; });
    var busy = S.room.poll || S.room.spin;
    var kids = [
      sh.random ? h('div', { class: 'now-label', style: 'margin-bottom:10px' }, [icon('dice'), 'Случайный выбор']) : null,
      h('div', { class: 'sheet-head' }, [poster, h('div', { class: 'sheet-info' }, [
        h('h2', { text: name }),
        original && normTitle(original) !== normTitle(name) ? h('div', { class: 'small muted', text: original }) : null,
        h('div', { class: 'facts' }, facts),
        genres.length ? h('div', { class: 'small muted', text: genres.join(', ') }) : null,
        h('p', { class: 'overview' + (d.overview ? '' : ' muted'), text: d.overview || (sh.det === null ? 'Загружаем описание…' : 'Описания нет.') }),
        h('div', { class: 'row' }, [
          one && host && oneKey !== curKey ? btn('lamp', 'play', 'Включить в комнате', function () { putEntry(it, one); }) : null,
          one && !busy && !oneIn ? btn('', 'vote', 'Предложить', function () { suggest(it, one); }) : null,
          oneIn ? h('span', { class: 'tag', text: 'В списке' }) : null,
          sh.random ? btn('', 'dice', 'Крутить ещё', function () { rollRandom(it); }) : null
        ])
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
    kids.push(h('div', { class: 'h', style: 'margin-top:6px', text: series ? 'Серии' : 'Файл' }));
    if (seasons.length > 1) {
      kids.push(h('div', { class: 'chips' }, seasons.map(function (s) {
        return h('button', { class: 'chip' + (s === sh.season ? ' on' : ''), type: 'button', on: { click: function () { sh.season = s; render(); } } },
          [s === 0 ? 'Спецвыпуски' : 'Сезон ' + s]);
      })));
    }
    kids.push(h('div', { class: 'files' }, shownEntries.map(function (e) {
      var key = keyOf(it, e), current = key === curKey;
      var inList = S.room.candidates && S.room.candidates.some(function (c) { return c.key === key; });
      // У фильма кнопки — выше, здесь сведения о файле
      if (one) {
        return h('div', { class: 'file' + (current ? ' current' : '') }, [
          h('span', { class: 'fname', text: model.basename(e.file.path), title: e.file.path }),
          h('span', { class: 'small muted', text: model.formatBytes(e.file.size) }),
          current ? h('span', { class: 'tag lamp', text: 'Сейчас' }) : null
        ]);
      }
      return h('div', { class: 'file' + (current ? ' current' : '') }, [
        h('span', { class: 'fname', text: series ? e.short : e.label, title: e.file.path }),
        current ? h('span', { class: 'tag lamp', text: 'Сейчас' }) : null,
        !inList && !busy ? btn('small', 'vote', 'Предложить', function () { suggest(it, e); }) : inList ? h('span', { class: 'tag', text: 'В списке' }) : null,
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

  /** «Итог сейчас» ведущему; все у пульта проголосовали — кнопка светится. */
  function finishButton(p) {
    var any = p.tally && Object.keys(p.tally).length > 0;
    return btn((p.all_voted ? 'lamp glow' : '') + ' small', 'flag', 'Итог сейчас', function () { roomCall('poll/finish'); },
      { disabled: !any, title: any ? 'Подвести итог, не дожидаясь таймера' : 'Ещё никто не проголосовал' });
  }

  function viewPoll() {
    var r = S.room, host = isHost(), p = r.poll, spin = r.spin || reel, cands = r.candidates || [];
    // Барабан рисуется по своему снимку списка: сервер очищает предложенные сразу после итога
    if (reel) { p = null; cands = reel.cands; }
    var lit = reel ? reelIndex() : -1, landed = !!reel && reel.phase === 'win';
    var myVote = p && p.votes && S.me ? p.votes[S.me.id] : null;
    var total = 0;
    if (p && p.tally) Object.keys(p.tally).forEach(function (k) { total += p.tally[k]; });
    var rows = cands.map(function (c) {
      var info = resolve(c.key), n = p && p.tally ? p.tally[c.key] || 0 : 0;
      var mini = h('div', { class: 'mini' });
      if (info) posterInto(mini, info.it, true);
      var mine = myVote === c.key;
      var isWin = landed && c.key === reel.key;
      var row = h('div', { class: 'cand' + (mine ? ' mine' : '') + (isWin ? ' winner' : '') + (!landed && cands.indexOf(c) === lit ? ' lit' : ''), 'data-key': c.key }, [
        p ? h('div', { class: 'bar', style: 'width:' + (total ? Math.round(n / total * 100) : 0) + '%' }) : null,
        mini,
        h('div', { class: 'cbody' }, [h('div', { class: 'cname', text: info ? info.entry.label : c.name }), h('div', { class: 'small muted', text: 'Предложил(а): ' + (c.by_name || '—') })]),
        p ? h('div', { class: 'votes', text: String(n) }) : null,
        isWin ? h('span', { class: 'win-badge' }, [icon('star'), 'Выпал']) : null,
        spin ? null : p ? btn(mine ? 'blue small' : 'small', 'vote', mine ? 'Ваш голос' : 'Голосую', function () { roomCall('vote', { key: c.key }); })
          : (host || (S.me && c.by === S.me.id)) ? btn('icon small', 'close', null, function () { roomCall('unsuggest', { key: c.key }); }, { title: 'Убрать из списка' }) : null
      ]);
      return row;
    });
    var head;
    if (spin) {
      var win = landed ? (resolve(reel.key) || null) : null;
      head = h('div', { class: 'row' }, [
        h('div', {}, landed
          ? [h('div', { class: 'h', text: 'Выпало: «' + (win ? win.entry.label : reel.name || 'фильм') + '»' }), h('div', { class: 'muted', text: 'Включаем в комнате…' })]
          : [h('div', { class: 'h', text: 'Рулетка' }), h('div', { class: 'muted', text: 'Крутим барабан… Выпавший фильм сразу включится в комнате.' })]),
        h('div', { class: 'spacer' }), muteButton(), icon('dice')
      ]);
    } else if (p) {
      head = h('div', { class: 'row' }, [
        h('div', {}, [h('div', { class: 'h', text: 'Голосование' }), h('div', { class: 'muted', text: p.all_voted
          ? 'Все проголосовали — итог через несколько секунд. Ещё можно передумать.'
          : 'Выберите фильм. Победитель сразу включится в комнате.' })]),
        h('div', { class: 'spacer' }), h('span', { class: 'timer', id: 'timer', text: pollLeft() }),
        host ? finishButton(p) : null,
        host ? btn('small', 'close', 'Отменить', function () { roomCall('poll/cancel'); }) : null
      ]);
    } else {
      head = h('div', { class: 'row' }, [
        h('div', {}, [h('div', { class: 'h', text: 'Что посмотреть?' }), h('div', { class: 'muted', text: host
          ? 'Когда наберётся 2–4 варианта, начните голосование (45 секунд) или доверьтесь рулетке — победитель включится у всех.'
          : 'Предлагайте фильмы из медиатеки — ведущий запустит голосование или рулетку.' })]),
        h('div', { class: 'spacer' }),
        host ? btn('lamp', 'vote', 'Начать голосование', function () { roomCall('poll'); }, { disabled: cands.length < 2 }) : null,
        host ? btn('', 'dice', 'Рулетка', function () { roomCall('roulette'); }, { disabled: cands.length < 2, title: 'Случайный фильм из предложенных' }) : null,
        btn('', 'plus', 'Предложить', function () { S.tab = 'library'; render(); }),
        muteButton()
      ]);
    }
    return h('div', { class: 'panel' }, [head, h('div', { style: 'height:14px' }),
      rows.length ? h('div', { class: 'cands' + (reel ? ' reel' : '') + (landed ? ' landed' : '') }, rows) : h('div', { class: 'empty', text: 'Пока ничего не предложено. Откройте фильм в медиатеке и нажмите «Предложить».' })]);
  }

  // ---- «Рулетка»: барабан, звук и эффект победителя ----
  // Победителя и время выбирает сервер (spin: key, started_at, ends_at). Барабан у всех идёт по часам сервера и встаёт
  // на выпавшем ровно в ends_at: в этот же миг — звук, подсветка и конфетти, без ожидания события об итоге.
  // Шаги подсветки заранее расписаны по времени: каждый следующий длиннее (замедление), последний ~0,4 с — видно,
  // как барабан «доползает» до фильма. Сервер сразу после итога чистит список, поэтому барабан рисует свой снимок.
  var OLD_SPIN_MS = 3200; // сервер без started_at
  var reel = null, reelSeen = '';

  /** Моменты смены подсветки (мс от старта): геометрически растущие шаги, последний ровно в dur. */
  function reelPlan(dur) {
    var d0 = 55, dl = Math.max(260, Math.min(460, dur * 0.11));
    if (dur <= dl * 2) return [dur];
    var N = Math.max(3, Math.round(1 + Math.log(dl / d0) / Math.log((dur - d0) / (dur - dl))));
    if (N * d0 >= dur) N = Math.max(1, Math.floor(dur / d0 / 2));
    var lo = 1.000001, hi = 3, r = 1;
    for (var it = 0; it < 60; it++) {
      r = (lo + hi) / 2;
      if (d0 * (Math.pow(r, N) - 1) / (r - 1) > dur) hi = r; else lo = r;
    }
    var times = [], t = 0;
    for (var i = 0; i < N; i++) { t += d0 * Math.pow(r, i); times.push(Math.round(t)); }
    times[N - 1] = dur;
    return times;
  }

  function syncReel() {
    var sp = S.room && S.room.spin;
    if (!sp || !sp.key || !sp.ends_at) return;
    var id = sp.key + '@' + sp.ends_at;
    if (reel ? reel.id === id : reelSeen === id) return; // этот барабан уже идёт или уже показан
    if (reel) clearTimeout(reel.timer);
    var cands = (S.room.candidates || []).slice(), win = -1;
    cands.forEach(function (c, i) { if (c.key === sp.key) win = i; });
    if (win < 0) { cands.push({ key: sp.key, name: sp.name }); win = cands.length - 1; }
    var start = sp.started_at || sp.ends_at - OLD_SPIN_MS, times = reelPlan(Math.max(300, sp.ends_at - start)), n = cands.length;
    reel = { id: id, key: sp.key, name: sp.name, start: start, times: times, n: n, cands: cands,
      first: ((win - times.length) % n + n) % n, k: 0, phase: 'spin', timer: 0 };
    reel.k = reelStep(); // открыли посреди вращения — без пачки щелчков за пропущенное
    reelSeen = id;
    S.tab = 'poll'; S.sheet = null;
  }
  function reelStep() {
    var el = serverNow() - reel.start, k = 0;
    while (k < reel.times.length && reel.times[k] <= el) k++;
    return k;
  }
  function reelIndex() { return (reel.first + (reel.phase === 'win' ? reel.times.length : reel.k)) % reel.n; }

  /** Кадр барабана; false — встал (или его нет). */
  function reelFrame() {
    if (!reel || reel.phase !== 'spin') return false;
    var k = reelStep(), N = reel.times.length;
    if (k >= N) { landReel(); return false; }
    if (k !== reel.k) {
      reel.k = k;
      var at = reelIndex(), rows = document.querySelectorAll('.cands.reel .cand');
      for (var j = 0; j < rows.length; j++) rows[j].classList.toggle('lit', j === at);
      sfx.tick(k / N); // один щелчок на шаг, даже если кадры шли редко
    }
    return true;
  }

  function landReel() {
    reel.phase = 'win';
    sfx.win();
    var info = resolve(reel.key);
    toast('Рулетка выбрала «' + (info ? info.entry.label : reel.name || 'фильм') + '» — включаем в комнате.');
    render();
    var el = document.querySelector('.cand.winner');
    if (el) confetti(el.getBoundingClientRect());
    var r = reel;
    r.timer = setTimeout(function () {
      if (reel !== r) return;
      reel = null;
      if (S.tab === 'poll') S.tab = 'now';
      render();
    }, reducedMotion() ? 1800 : 3000);
  }

  function reducedMotion() { try { return matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) { return false; } }

  /** Конфетти над карточкой победителя: один холст поверх страницы на ~1,7 с, потом удаляется. */
  function confetti(rect) {
    if (reducedMotion() || !rect.width) return;
    var cv = document.createElement('canvas'), dpr = Math.min(2, window.devicePixelRatio || 1);
    var W = window.innerWidth, H = window.innerHeight, c = cv.getContext('2d');
    if (!c) return;
    cv.className = 'confetti';
    cv.width = W * dpr; cv.height = H * dpr;
    document.body.appendChild(cv);
    c.scale(dpr, dpr);
    var colors = ['#ffb02e', '#ffd27a', '#60a5fa', '#7fe0b0', '#eef0f5', '#ff9b8f'], parts = [];
    var cx = rect.left + rect.width / 2, cy = rect.top + rect.height / 2;
    for (var i = 0; i < 90; i++) {
      var a = -Math.PI / 2 + (Math.random() - 0.5) * Math.PI * 1.15, v = 4 + Math.random() * 8;
      parts.push({ x: cx + (Math.random() - 0.5) * rect.width * 0.8, y: cy, vx: Math.cos(a) * v, vy: Math.sin(a) * v - 2,
        w: 4 + Math.random() * 5, h: 7 + Math.random() * 6, r: Math.random() * 6.28, vr: (Math.random() - 0.5) * 0.4, col: colors[i % colors.length] });
    }
    var t0 = performance.now(), last = t0, LIFE = 1700;
    requestAnimationFrame(function f(now) {
      var el = now - t0, k = Math.min(3, (now - last) / 16.7);
      last = now;
      c.clearRect(0, 0, W, H);
      if (el > LIFE || !cv.isConnected) { cv.remove(); return; }
      c.globalAlpha = el > LIFE - 450 ? (LIFE - el) / 450 : 1;
      for (var i = 0; i < parts.length; i++) {
        var p = parts[i], fl = Math.abs(Math.cos(p.r * 1.7));
        p.vy += 0.3 * k; p.vx *= Math.pow(0.985, k); p.vy *= Math.pow(0.985, k);
        p.x += p.vx * k; p.y += p.vy * k; p.r += p.vr * k;
        c.save(); c.translate(p.x, p.y); c.rotate(p.r);
        c.fillStyle = p.col; c.fillRect(-p.w / 2, -p.h * fl / 2, p.w, p.h * fl + 0.6);
        c.restore();
      }
      requestAnimationFrame(f);
    });
  }

  /** Звуки рулетки: синтез WebAudio, без файлов. Браузер разрешает звук после первого нажатия на странице. */
  var sfx = (function () {
    var ctx = null, out = null, noise = null, muted = false;
    try { muted = localStorage.getItem('sm.act.mute') === '1'; } catch (e) { /* без хранилища */ }
    function get() {
      if (!ctx) {
        var C = window.AudioContext || window.webkitAudioContext;
        if (!C) return null;
        try { ctx = new C(); } catch (e) { return null; }
        out = ctx.createGain();
        out.gain.value = 0.5; // общая громкость — умеренная
        out.connect(ctx.destination);
        noise = ctx.createBuffer(1, Math.floor(ctx.sampleRate * 0.05), ctx.sampleRate);
        var d = noise.getChannelData(0);
        for (var i = 0; i < d.length; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / d.length);
      }
      if (ctx.state === 'suspended') ctx.resume().catch(function () {});
      return ctx;
    }
    function unlock() { get(); }
    ['pointerdown', 'keydown', 'touchend'].forEach(function (e) { document.addEventListener(e, unlock, { capture: true, passive: true }); });
    function ready() { if (muted) return null; var c = get(); return c && c.state === 'running' ? c : null; }
    function tone(c, f, t, dur, type, vol, f2) {
      var o = c.createOscillator(), g = c.createGain();
      o.type = type;
      o.frequency.setValueAtTime(f, t);
      if (f2) o.frequency.exponentialRampToValueAtTime(f2, t + dur);
      g.gain.setValueAtTime(0.0001, t);
      g.gain.exponentialRampToValueAtTime(vol, t + 0.006);
      g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
      o.connect(g); g.connect(out);
      o.start(t); o.stop(t + dur + 0.03);
    }
    return {
      muted: function () { return muted; },
      setMuted: function (m) {
        muted = !!m;
        try { localStorage.setItem('sm.act.mute', muted ? '1' : '0'); } catch (e) { /* без хранилища */ }
      },
      /** Щелчок храповика; p — доля пути (0…1): к концу ниже и глуше. */
      tick: function (p) {
        var c = ready();
        if (!c) return;
        var t = c.currentTime, s = c.createBufferSource(), bp = c.createBiquadFilter(), g = c.createGain();
        s.buffer = noise;
        bp.type = 'bandpass'; bp.frequency.value = 3200 - 1400 * p; bp.Q.value = 1.6;
        g.gain.setValueAtTime(0.5, t);
        g.gain.exponentialRampToValueAtTime(0.0001, t + 0.035);
        s.connect(bp); bp.connect(g); g.connect(out);
        s.start(t); s.stop(t + 0.05);
        tone(c, 900 - 300 * p, t, 0.04, 'triangle', 0.1, 500);
      },
      /** Победа: короткое восходящее арпеджио и «искры» сверху. */
      win: function () {
        var c = ready();
        if (!c) return;
        var t = c.currentTime + 0.01;
        tone(c, 140, t, 0.18, 'sine', 0.3, 70);
        [523.25, 659.25, 783.99, 1046.5].forEach(function (f, i) {
          tone(c, f, t + i * 0.085, i === 3 ? 0.9 : 0.32, 'triangle', 0.2);
        });
        [2093, 2637, 3136, 2637, 3520].forEach(function (f, i) {
          tone(c, f, t + 0.34 + i * 0.07, 0.35, 'sine', 0.045);
        });
      }
    };
  })();

  function muteButton() {
    var m = sfx.muted();
    return btn('icon small', m ? 'mute' : 'sound', null, function () {
      sfx.setMuted(!m);
      if (m) sfx.tick(0);
      render();
    }, { title: m ? 'Включить звук рулетки' : 'Выключить звук рулетки', 'aria-pressed': m ? 'true' : 'false' });
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

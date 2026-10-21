/*
 * Экраны: вход по коду устройства, медиатека (все / «Мои»), раздача (фильм или серии), затем плеер (player.js).
 * Без фреймворка: экраны перерисовываются целиком, их немного. Только ES2017 (Tizen 4.0).
 */
(function (g) {
  'use strict';
  var SM = g.SM, api = SM.api, model = SM.model, support = SM.support, nav = SM.nav;
  var root = document.getElementById('screen');

  // ---- DOM ----

  function h(tag, attrs, children) {
    var el = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      var v = attrs[k];
      if (v == null || v === false) return;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = v;
      else if (k.indexOf('on') === 0) el.addEventListener(k.substring(2), v);
      else el.setAttribute(k, v === true ? '' : v);
    });
    (children || []).forEach(function (c) { if (c != null) el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); });
    return el;
  }
  function button(text, onclick, cls) { return h('button', { class: 'f btn ' + (cls || ''), onclick: onclick, text: text }); }

  function show(el) {
    root.innerHTML = '';
    root.appendChild(el);
    nav.root = root;
    nav.onKey = null;
  }

  var toastTimer = 0;
  function toast(text, ms) {
    var t = document.getElementById('toast');
    t.textContent = text;
    t.className = 'show';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.className = ''; }, ms || 5000);
  }

  /** Окно с вопросом поверх экрана: [buttons] — [{ text, run }]. «Назад» закрывает его. */
  function dialog(title, text, buttons) {
    var prevBack = nav.onBack, prevRoot = nav.root, prevFocus = document.activeElement;
    var box = h('div', { class: 'dialog' }, [h('div', { class: 'dialog-box' }, [
      h('h2', { text: title }), text ? h('p', { text: text }) : null,
      h('div', { class: 'row' }, buttons.map(function (b) { return button(b.text, function () { close(); if (b.run) b.run(); }); }))
    ])]);
    function close() {
      if (!box.parentNode) return;
      box.parentNode.removeChild(box);
      nav.root = prevRoot; nav.onBack = prevBack;
      if (prevFocus && document.body.contains(prevFocus)) nav.focus(prevFocus);
    }
    document.body.appendChild(box);
    nav.root = box; nav.onBack = close;
    nav.focusFirst(box);
    return close;
  }

  // ---- прогресс просмотра (на ТВ, ключ «раздача|путь», как на Android) ----

  var watch = {
    get: function (key) {
      try { var w = JSON.parse(localStorage.getItem('watch:' + key) || 'null'); return w ? model.watchInfo(w.pos, w.len, w.at) : null; } catch (e) { return null; }
    },
    put: function (key, pos, len) {
      try { localStorage.setItem('watch:' + key, JSON.stringify({ pos: pos, len: len, at: Date.now() })); } catch (e) { /* полно */ }
    }
  };

  // ---- вход ----

  var pollTimer = 0;

  function start() {
    if (!api.HOST) { show(h('div', { class: 'center' }, [h('h1', { text: 'Сборка без адреса сайта' }), h('p', { text: 'Соберите приложение с SITE_URL (см. tizen/README.md).' })])); return; }
    show(h('div', { class: 'center' }, [h('div', { class: 'spinner' }), h('p', { text: 'Подключение…' })]));
    api.get('/api/session').then(function (s) {
      if (s.authenticated) library(); else login();
    }).catch(function (e) {
      show(h('div', { class: 'center' }, [h('h1', { text: 'Нет связи с сервером' }), h('p', { text: api.describe(e) }), button('Повторить', start)]));
      nav.focusFirst();
    });
  }

  function login(error) {
    clearInterval(pollTimer);
    nav.onBack = exit;
    show(h('div', { class: 'center' }, [
      h('img', { class: 'logo', src: 'icon.png', alt: '' }),
      h('h1', { text: 'Suslik Media' }),
      error ? h('p', { class: 'error', text: error }) : h('p', { text: 'Войдите через Discord: на телефоне откроется страница, где нужно подтвердить код.' }),
      button('Войти через Discord', pair, 'primary')
    ]));
    nav.focusFirst();
  }

  function pair() {
    api.post('/api/auth/device', { name: 'Suslik Media Samsung TV', site_url: api.SITE.replace(/\/+$/, '') }, false).then(function (res) {
      var userCode = res.user_code, code = res.code;
      var url = res.verification_url && res.verification_url.indexOf('code=') >= 0 ? res.verification_url : api.SITE + 'link?code=' + userCode;
      nav.onBack = function () { login(); };
      show(h('div', { class: 'center' }, [
        h('h1', { text: 'Подтвердите вход на телефоне' }),
        h('p', { text: 'Откройте ссылку и введите код:' }),
        h('div', { class: 'link', text: url.replace(/^https?:\/\//, '') }),
        h('div', { class: 'code', text: userCode }),
        h('p', { class: 'muted', text: 'Код действует 10 минут.' }),
        button('Отмена', function () { login(); })
      ]));
      nav.focusFirst();
      pollTimer = setInterval(function () {
        api.post('/api/auth/device/poll', { code: code }).then(function (r) {
          if (r.status === 'authorized') { clearInterval(pollTimer); api.setToken(r.device_token || null); library(); }
          else if (r.status === 'expired') login('Код истёк, получите новый.');
        }).catch(function (e) {
          if (e && (e.code === 410 || e.code === 404)) login('Код истёк, получите новый.');
        });
      }, 2000);
    }).catch(function (e) { login(api.describe(e)); });
  }

  function exit() {
    try { g.tizen.application.getCurrentApplication().exit(); } catch (e) { /* не на ТВ */ }
  }

  // ---- медиатека ----

  var state = { items: [], tab: 'all', focusId: null };

  function library() {
    nav.onBack = exit;
    show(h('div', { class: 'center' }, [h('div', { class: 'spinner' }), h('p', { text: 'Загружаем медиатеку…' })]));
    api.get('/api/library').then(function (json) {
      state.items = model.prepareItems(model.parseLibrary(json));
      renderLibrary();
    }).catch(function (e) {
      if (e && e.code === 401) { api.setToken(null); login(); return; }
      show(h('div', { class: 'center' }, [h('h1', { text: 'Не удалось загрузить медиатеку' }), h('p', { text: api.describe(e) }), button('Повторить', library)]));
      nav.focusFirst();
    });
  }

  function renderLibrary() {
    nav.onBack = exit;
    var list = state.items.filter(function (it) { return state.tab === 'all' || it.m.mine; });
    // Сначала избранное и готовое, как на Android
    var order = new Map(list.map(function (it, i) { return [it, i]; })); // sort в Chromium 56 не устойчивый
    list.sort(function (a, b) { return (b.m.favorite - a.m.favorite) || (b.m.ready - a.m.ready) || order.get(a) - order.get(b); });
    var tabs = h('div', { class: 'tabs' }, [
      tab('Библиотека', 'all'), tab('Мои', 'mine'),
      h('div', { class: 'spacer' }), button('Обновить', library), button('Выйти', logout)
    ]);
    var grid = h('div', { class: 'grid' }, list.length ? list.map(tile) : [h('p', { class: 'muted', text: state.tab === 'mine' ? 'В «Моих» пока ничего нет.' : 'Медиатека пуста.' })]);
    show(h('div', { class: 'library' }, [tabs, grid]));
    var again = state.focusId != null && root.querySelector('[data-id="' + state.focusId + '"]');
    nav.focus(again || grid.querySelector('.f') || tabs.querySelector('.f'));
  }

  function tab(text, id) {
    return button(text, function () { state.tab = id; state.focusId = null; renderLibrary(); }, state.tab === id ? 'active' : '');
  }

  function logout() {
    dialog('Выйти из аккаунта?', null, [
      { text: 'Выйти', run: function () { api.post('/api/logout').catch(function () {}); api.setToken(null); login(); } },
      { text: 'Отмена' }
    ]);
  }

  function tile(it) {
    var m = it.m, main = model.mainVideo(m);
    var check = main ? support.checkFile(main.path) : { level: 'ok' };
    var poster = h('div', { class: 'poster' }, [h('span', { class: 'poster-title', text: it.baseTitle })]);
    var badges = [];
    if (!m.ready) badges.push(h('span', { class: 'badge', text: model.statusRu(m) + (m.downloading ? ' ' + Math.round(m.progress * 100) + '%' : '') }));
    if (check.level === 'no') badges.push(h('span', { class: 'badge bad', text: 'Не поддерживается на ТВ' }));
    else if (check.level === 'warn') badges.push(h('span', { class: 'badge warn', text: '⚠' }));
    var el = h('div', { class: 'f tile' + (m.ready ? '' : ' dim'), tabindex: '-1', 'data-id': String(m.id), onclick: function () {
      state.focusId = m.id;
      if (!m.ready) { toast('Раздача ещё не готова: ' + model.statusRu(m)); return; }
      details(it);
    } }, [poster, h('div', { class: 'badges' }, badges), h('div', { class: 'tile-name', text: it.baseTitle })]);
    posters.want(it.posterQueries[0], function (url) {
      poster.style.backgroundImage = 'url("' + url + '")';
      poster.classList.add('has-image');
    });
    return el;
  }

  /** Постеры: /api/poster?q= → путь картинки → blob (нужен токен). Не больше трёх запросов сразу. */
  var posters = (function () {
    var cache = {}, queue = [], active = 0;
    function pump() {
      while (active < 3 && queue.length) {
        var job = queue.shift();
        active++;
        load(job.q).then(function (url) { if (url) job.cb(url); }, function () {}).then(function () { active--; pump(); });
      }
    }
    function load(q) {
      if (cache[q] !== undefined) return Promise.resolve(cache[q]);
      return api.get('/api/poster?q=' + encodeURIComponent(q)).then(function (r) {
        var p = r.poster;
        if (typeof p !== 'string' || p.indexOf('/api/poster/img/') !== 0) { cache[q] = null; return null; }
        return api.blobUrl(p).then(function (u) { cache[q] = u; return u; });
      });
    }
    return { want: function (q, cb) { if (cache[q]) cb(cache[q]); else { queue.push({ q: q, cb: cb }); pump(); } } };
  })();

  // ---- раздача: фильм или серии ----

  function details(it) {
    var m = it.m, p = it.playable, entries = [];
    if (p.kind === 'movie') entries.push({ label: it.baseTitle, file: p.file });
    else if (p.kind === 'series') p.seasons.forEach(function (s) {
      s.items.forEach(function (e) { entries.push({ label: model.episodeLabel(it.baseTitle, s, e), file: e.file, season: s.season }); });
    });
    var rows = [], lastSeason = null;
    entries.forEach(function (en, i) {
      if (p.kind === 'series' && p.seasons.length > 1 && en.season !== lastSeason) {
        lastSeason = en.season;
        rows.push(h('h3', { text: en.season === 0 ? 'Спецвыпуски' : 'Сезон ' + en.season }));
      }
      rows.push(entryRow(it, entries, i));
    });
    if (!entries.length) rows.push(h('p', { class: 'muted', text: 'В раздаче нет видео.' }));
    nav.onBack = renderLibrary;
    show(h('div', { class: 'details' }, [
      h('h1', { text: it.baseTitle }),
      h('p', { class: 'muted', text: m.name + ' · ' + model.formatBytes(m.size) }),
      h('div', { class: 'entries' }, rows)
    ]));
    // Фокус на первую недосмотренную серию
    var resumeAt = 0;
    for (var i = 0; i < entries.length; i++) {
      var w = watch.get(model.watchKey(m.id, entries[i].file));
      if (w && !w.watched) { resumeAt = i; break; }
      if (w && w.watched) resumeAt = Math.min(i + 1, entries.length - 1);
    }
    var all = root.querySelectorAll('.entry');
    nav.focus(all[resumeAt] || all[0]);
  }

  function entryRow(it, entries, i) {
    var en = entries[i], m = it.m;
    var check = support.checkFile(en.file.path);
    var w = watch.get(model.watchKey(m.id, en.file));
    var dubs = model.externalAudioFor(m, en.file);
    var notes = [];
    if (check.level !== 'ok') notes.push(h('div', { class: 'note ' + (check.level === 'no' ? 'bad' : 'warn'), text: (check.level === 'no' ? '⛔ ' : '⚠ ') + check.text }));
    if (dubs.length) notes.push(h('div', { class: 'note grey', text: support.EXTERNAL_AUDIO_TEXT + ' (' + dubs.map(function (d) { return model.basename(d.path); }).join(', ') + ')' }));
    var bar = w ? h('div', { class: 'progress' }, [h('div', { style: 'width:' + Math.round(w.fraction * 100) + '%' })]) : null;
    return h('div', { class: 'f entry' + (check.level === 'no' ? ' unsupported' : ''), tabindex: '-1', onclick: function () { open(it, entries, i); } }, [
      h('div', { class: 'entry-main' }, [
        h('span', { class: 'entry-label', text: en.label }),
        h('span', { class: 'muted', text: (w && w.watched ? '✓ ' : '') + model.formatBytes(en.file.size) })
      ]),
      bar
    ].concat(notes));
  }

  function open(it, entries, i) {
    var en = entries[i];
    if (!en.file.link) { toast('Ссылка на файл ещё не готова. Обновите медиатеку.'); return; }
    var check = support.checkFile(en.file.path);
    var go = function () {
      SM.player.play({
        magnet: it.m, title: en.label, file: en.file, entries: entries, index: i, watch: watch, toast: toast, dialog: dialog,
        onClose: function () { details(it); }, onEntry: function (j) { open(it, entries, j); }
      });
    };
    if (check.level === 'no') dialog(support.MESSAGES.unsupported, check.text + '.', [{ text: 'Всё равно попробовать', run: go }, { text: 'Назад' }]);
    else go();
  }

  /** Кнопки пульта, которые без регистрации телевизор оставляет себе. */
  function registerKeys() {
    var keys = ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaRewind', 'MediaFastForward', 'ColorF2Yellow', 'Info'];
    keys.forEach(function (k) {
      try { g.tizen.tvinputdevice.registerKey(k); } catch (e) { /* не на ТВ или кнопки нет */ }
    });
  }

  SM.app = { start: start, h: h, button: button, toast: toast, dialog: dialog, show: show };
  registerKeys();
  start();
})(window);

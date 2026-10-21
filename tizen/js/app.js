/*
 * Экраны: вход по коду устройства (QR-код и ссылка), затем вкладки сверху — Медиатека («Продолжить просмотр» и все
 * раздачи), Мои, Поиск (search.js), Добавить (add.js), Вместе (together.js), Настройки; раздача (фильм или серии) и
 * плеер (player.js). Только ES2017 (Tizen 4.0).
 */
(function (g) {
  'use strict';
  var SM = g.SM, api = SM.api, model = SM.model, support = SM.support, nav = SM.nav, ui = SM.ui, party = SM.party;
  var h = ui.h, button = ui.button, toast = ui.toast, dialog = ui.dialog;
  var root = ui.root;

  // Вкладки других файлов (search.js, add.js, together.js) уже в SM.tabs; порядок — как на Android
  var TABS = [
    { id: 'home', title: 'Медиатека' }, { id: 'mine', title: 'Мои' }, { id: 'search', title: 'Поиск' },
    { id: 'add', title: 'Добавить' }, { id: 'party', title: 'Вместе' }, { id: 'settings', title: 'Настройки' }
  ];
  var tabs = SM.tabs = SM.tabs || {};

  var state = { items: [], loadedAt: 0, tab: 'home', focusKey: null, user: null };

  // Экран уходит: отписки (комната, таймеры вкладок)
  var leaving = [];
  function onLeave(f) { leaving.push(f); }
  function show(el) {
    var l = leaving; leaving = [];
    l.forEach(function (f) { try { f(); } catch (e) { /* ничего */ } });
    ui.show(el);
  }

  // ---- прогресс просмотра (ключ «раздача|путь», как на Android и ПК; с аккаунтом — sync.js) ----

  var watch = {
    raw: function (key) {
      try { return JSON.parse(localStorage.getItem('watch:' + key) || 'null'); } catch (e) { return null; }
    },
    get: function (key) { var w = watch.raw(key); return w ? model.watchInfo(w.pos, w.len, w.at) : null; },
    put: function (key, pos, len) {
      var at = Date.now();
      try { localStorage.setItem('watch:' + key, JSON.stringify({ pos: pos, len: len, at: at })); } catch (e) { /* полно */ }
      SM.sync.note(key, pos, len, at);
    }
  };

  // ---- вход ----

  var pollTimer = 0;

  function start() {
    if (!api.HOST) { show(h('div', { class: 'center' }, [h('h1', { text: 'Сборка без адреса сайта' }), h('p', { text: 'Соберите приложение с SITE_URL (см. tizen/README.md).' })])); return; }
    show(ui.spinner('Подключение…'));
    api.get('/api/session').then(function (s) {
      if (!s.authenticated) { login(); return; }
      signedIn(s.user || null);
    }).catch(function (e) {
      show(h('div', { class: 'center' }, [h('h1', { text: 'Нет связи с сервером' }), h('p', { text: api.describe(e) }), button('Повторить', start, 'primary')]));
      nav.focusFirst();
    });
  }

  function signedIn(user) {
    state.user = user;
    SM.sync.setUser(user && user.id);
    loadLibrary(true).then(function () {
      shell('home', true);
      SM.sync.run(function (n) { if (n && state.tab === 'home' && !SM.player.current()) refreshTab(); });
      whatsNewOnce();
    }, function () {});
  }

  function login(error) {
    clearInterval(pollTimer);
    nav.onBack = askExit;
    show(h('div', { class: 'center' }, [
      h('img', { class: 'logo', src: 'icon.png', alt: '' }),
      h('h1', { text: 'Suslik Media' }),
      error ? h('p', { class: 'error', text: error }) : h('p', { text: 'Вход через Discord: телефоном по QR-коду или по ссылке с кодом.' }),
      button('Войти', pair, 'primary')
    ]));
    nav.focusFirst();
  }

  function pair() {
    show(ui.spinner('Получаем код…'));
    nav.onBack = function () { login(); };
    api.post('/api/auth/device', { name: 'Suslik Media Samsung TV', site_url: api.SITE.replace(/\/+$/, '') }, false).then(function (res) {
      var userCode = res.user_code, code = res.code;
      var url = res.verification_url && res.verification_url.indexOf('code=') >= 0 ? res.verification_url : api.SITE + 'link?code=' + userCode;
      nav.onBack = function () { login(); };
      show(h('div', { class: 'pair' }, [
        h('div', { class: 'pair-qr' }, [ui.qr(url, 480), h('p', { class: 'muted', text: 'Наведите камеру телефона' })]),
        h('div', { class: 'pair-text' }, [
          h('h1', { text: 'Вход в Suslik Media' }),
          h('p', { text: 'Отсканируйте QR-код — код подставится сам. Или откройте ссылку и введите код:' }),
          h('div', { class: 'link', text: api.SITE.replace(/^https?:\/\//, '') + 'link' }),
          h('div', { class: 'code', text: userCode }),
          h('p', { class: 'muted', text: 'Код действует 10 минут. Экран обновится сам после входа.' }),
          button('Отмена', function () { login(); })
        ])
      ]));
      nav.focusFirst();
      pollTimer = setInterval(function () {
        api.post('/api/auth/device/poll', { code: code }).then(function (r) {
          if (r.status === 'authorized') {
            clearInterval(pollTimer);
            api.setToken(r.device_token || null);
            api.get('/api/session').then(function (s) { signedIn(s.user || null); }, function () { signedIn(null); });
          } else if (r.status === 'expired') login('Код истёк, получите новый.');
        }).catch(function (e) {
          if (e && (e.code === 410 || e.code === 404)) login('Код истёк, получите новый.');
        });
      }, 2000);
    }).catch(function (e) { login(api.describe(e)); });
  }

  function askExit() {
    dialog('Закрыть Suslik Media?', null, [{ text: 'Закрыть', run: exit }, { text: 'Отмена' }]);
  }

  function exit() {
    try { g.tizen.application.getCurrentApplication().exit(); } catch (e) { /* не на ТВ */ }
  }

  function logout() {
    dialog('Выйти из аккаунта?', 'Прогресс просмотра на телевизоре останется.', [
      { text: 'Выйти', run: function () {
        party.leave();
        api.post('/api/logout').catch(function () {});
        api.setToken(null);
        SM.sync.setUser(null);
        posters.clear();
        login();
      } },
      { text: 'Отмена' }
    ]);
  }

  // ---- медиатека ----

  var loading = null;
  /** Медиатека с сервера; [force] = false — не чаще раза в минуту. */
  function loadLibrary(force) {
    if (!force && Date.now() - state.loadedAt < 60000) return Promise.resolve(state.items);
    if (loading) return loading;
    if (!state.loadedAt) show(ui.spinner('Загружаем медиатеку…'));
    loading = api.get('/api/library').then(function (json) {
      loading = null;
      state.items = model.prepareItems(model.parseLibrary(json));
      state.loadedAt = Date.now();
      return state.items;
    }, function (e) {
      loading = null;
      if (e && e.code === 401) { api.setToken(null); login(); throw e; }
      if (!state.loadedAt) {
        show(h('div', { class: 'center' }, [h('h1', { text: 'Не удалось загрузить медиатеку' }), h('p', { text: api.describe(e) }), button('Повторить', start, 'primary')]));
        nav.focusFirst();
      } else toast('Медиатека не обновилась: ' + api.describe(e));
      throw e;
    });
    return loading;
  }

  /** Медиатека устарела (добавили раздачу): обновится при следующем открытии вкладки. */
  function libraryChanged() { state.loadedAt = 0; }

  // ---- оболочка с вкладками ----

  var contentEl = null;

  /** Вкладка [id]; [toContent] — фокус в содержимое (иначе на кнопку вкладки). */
  function shell(id, toContent) {
    state.tab = id;
    var bar = h('div', { class: 'top' }, [h('img', { class: 'top-logo', src: 'icon.png', alt: '' })].concat(TABS.map(function (t) {
      var label = t.title + (t.id === 'party' && party.inRoom() ? ' ●' : '');
      return h('button', { class: 'f tab' + (t.id === id ? ' active' : ''), 'data-tab': t.id, text: label, onclick: function () { switchTab(t.id); } });
    })).concat([h('div', { class: 'spacer' }), state.user ? h('div', { class: 'who', text: state.user.name || '' }) : null]));
    contentEl = h('div', { class: 'content' });
    show(h('div', { class: 'shell' }, [bar, contentEl]));
    nav.onBack = function () {
      var a = document.activeElement;
      if (a && bar.contains(a)) askExit();
      else nav.focus(bar.querySelector('.tab.active'));
    };
    renderTab(id);
    var target = toContent ? (rememberedFocus() || contentEl.querySelector('.f')) : null;
    nav.focus(target || bar.querySelector('.tab.active'));
  }

  function switchTab(id) {
    state.focusKey = null;
    if ((id === 'home' || id === 'mine') && Date.now() - state.loadedAt > 60000) {
      loadLibrary(false).then(function () { shell(id); }, function () { shell(id); });
      return;
    }
    shell(id);
  }

  function renderTab(id) {
    var l = leaving; leaving = [];
    l.forEach(function (f) { try { f(); } catch (e) { /* ничего */ } });
    contentEl.innerHTML = '';
    if (id === 'home') renderHome(contentEl);
    else if (id === 'mine') renderGrid(contentEl, state.items.filter(function (it) { return it.m.mine; }), 'В «Моих» пока ничего нет. Откройте раздачу и нажмите «В Мои».');
    else if (id === 'settings') renderSettings(contentEl);
    else if (tabs[id]) tabs[id].render(contentEl);
  }

  /** Перерисовать открытую вкладку, не теряя места фокуса. */
  function refreshTab() {
    if (!contentEl || !document.body.contains(contentEl)) return;
    var a = document.activeElement;
    var key = a && a.getAttribute && a.getAttribute('data-key');
    var inside = a && contentEl.contains(a);
    renderTab(state.tab);
    if (!inside) return; // фокус на вкладках или в окне поверх — не трогаем
    var again = key && contentEl.querySelector('[data-key="' + cssEscape(key) + '"]');
    nav.focus(again || contentEl.querySelector('.f'));
  }

  function cssEscape(s) { return String(s).replace(/["\\]/g, '\\$&'); }

  function rememberedFocus() {
    return state.focusKey != null ? contentEl.querySelector('[data-key="' + cssEscape(state.focusKey) + '"]') : null;
  }

  // ---- Медиатека: «Продолжить просмотр» и сетка ----

  function sorted(list) {
    var order = new Map(list.map(function (it, i) { return [it, i]; })); // sort в Chromium 56 не устойчивый
    return list.slice().sort(function (a, b) { return (b.m.favorite - a.m.favorite) || (b.m.ready - a.m.ready) || order.get(a) - order.get(b); });
  }

  function renderHome(box) {
    var cont = continueList();
    if (cont.length) {
      box.appendChild(h('h2', { class: 'section', text: 'Продолжить просмотр' }));
      box.appendChild(h('div', { class: 'strip' }, cont.map(continueCard)));
      box.appendChild(h('h2', { class: 'section', text: 'Все раздачи' }));
    }
    renderGrid(box, state.items, 'Медиатека пуста. Найдите фильм во вкладке «Поиск».');
  }

  function renderGrid(box, list, empty) {
    box.appendChild(list.length ? h('div', { class: 'grid' }, sorted(list).map(tile)) : h('p', { class: 'muted empty', text: empty }));
  }

  /** Серии и фильмы раздачи по порядку: { label, file, season }. */
  function entriesOf(it) {
    var p = it.playable, out = [];
    if (p.kind === 'movie') out.push({ label: it.baseTitle, file: p.file });
    else if (p.kind === 'series') p.seasons.forEach(function (s) {
      s.items.forEach(function (e) { out.push({ label: model.episodeLabel(it.baseTitle, s, e), file: e.file, season: s.season }); });
    });
    return out;
  }

  /**
   * Что продолжить: по каждой раздаче — последний открытый файл. Не досмотрен — с того же места, досмотрен — следующая
   * серия с начала. Новые сверху, не больше 12.
   */
  function continueList() {
    var out = [];
    state.items.forEach(function (it) {
      if (!it.m.ready) return;
      var entries = null, best = null;
      it.m.files.forEach(function (f) {
        var w = watch.raw(model.watchKey(it.m.id, f));
        if (w && w.at && (!best || w.at > best.w.at)) best = { f: f, w: w };
      });
      if (!best) return;
      entries = entriesOf(it);
      var i = -1;
      entries.forEach(function (en, j) { if (en.file.path === best.f.path) i = j; });
      if (i < 0) return;
      var info = model.watchInfo(best.w.pos, best.w.len, best.w.at);
      if (info.watched) {
        if (i + 1 >= entries.length) return;
        out.push({ it: it, entries: entries, index: i + 1, at: best.w.at, fraction: 0, next: true });
      } else if (best.w.pos > 30000) out.push({ it: it, entries: entries, index: i, at: best.w.at, fraction: info.fraction, next: false });
    });
    out.sort(function (a, b) { return b.at - a.at; });
    return out.slice(0, 12);
  }

  function continueCard(c) {
    var en = c.entries[c.index];
    var key = 'c' + c.it.m.id;
    var poster = h('div', { class: 'poster' }, [h('span', { class: 'poster-title', text: c.it.baseTitle })]);
    var el = h('div', { class: 'f card', tabindex: '-1', 'data-key': key, onclick: function () {
      state.focusKey = key;
      open(c.it, c.entries, c.index, { back: function () { shell('home', true); } });
    } }, [
      poster,
      h('div', { class: 'card-bar' }, [h('div', { style: 'width:' + Math.round(c.fraction * 100) + '%' })]),
      h('div', { class: 'tile-name', text: en.label }),
      h('div', { class: 'tile-sub', text: c.next ? 'Следующая серия' : 'Осталось ' + left(c) })
    ]);
    posterInto(poster, c.it);
    return el;
  }

  function left(c) {
    var w = watch.raw(model.watchKey(c.it.m.id, c.entries[c.index].file));
    return w && w.len > w.pos ? ui.clock(w.len - w.pos) : '';
  }

  function tile(it) {
    var m = it.m, main = model.mainVideo(m);
    var check = main ? support.checkFile(main.path) : { level: 'ok' };
    var poster = h('div', { class: 'poster' }, [h('span', { class: 'poster-title', text: it.baseTitle })]);
    var badges = [];
    if (!m.ready) badges.push(h('span', { class: 'badge', text: model.statusRu(m) + (m.downloading ? ' ' + Math.round(m.progress * 100) + '%' : '') }));
    if (check.level === 'no') badges.push(h('span', { class: 'badge bad', text: 'Не поддерживается' }));
    else if (check.level === 'warn') badges.push(h('span', { class: 'badge warn', text: '⚠' }));
    if (m.mine) badges.push(h('span', { class: 'badge mine', text: '★' }));
    var key = 't' + m.id;
    var el = h('div', { class: 'f tile' + (m.ready ? '' : ' dim'), tabindex: '-1', 'data-key': key, onclick: function () {
      state.focusKey = key;
      if (!m.ready) { toast('Раздача ещё не готова: ' + model.statusRu(m)); return; }
      details(it);
    } }, [poster, h('div', { class: 'badges' }, badges), h('div', { class: 'tile-name', text: it.baseTitle })]);
    posterInto(poster, it);
    return el;
  }

  function posterInto(poster, it) {
    posters.want(it.posterQueries[0], function (url) {
      poster.style.backgroundImage = 'url("' + url + '")';
      poster.classList.add('has-image');
    });
  }

  /** Постеры: /api/poster?q= → путь картинки → blob (нужен токен). Не больше трёх запросов сразу. */
  var posters = (function () {
    // Не больше 60 картинок в памяти (у ТВ 2018 года на страницу ~200 МБ): старые objectURL освобождаются
    var cache = {}, order = [], queue = [], active = 0, LIMIT = 60;
    function remember(q, url) {
      cache[q] = url;
      if (!url) return;
      order.push(q);
      while (order.length > LIMIT) { var old = order.shift(); if (cache[old]) URL.revokeObjectURL(cache[old]); delete cache[old]; }
    }
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
        if (typeof p !== 'string' || p.indexOf('/api/poster/img/') !== 0) { remember(q, null); return null; }
        return api.blobUrl(p).then(function (u) { remember(q, u); return u; });
      });
    }
    return {
      want: function (q, cb) { if (cache[q]) cb(cache[q]); else if (cache[q] !== null) { queue.push({ q: q, cb: cb }); pump(); } },
      clear: function () { order.forEach(function (q) { if (cache[q]) URL.revokeObjectURL(cache[q]); }); cache = {}; order = []; queue = []; }
    };
  })();

  // ---- раздача: фильм или серии ----

  function details(it) {
    var m = it.m, p = it.playable, entries = entriesOf(it);
    var rows = [], lastSeason = null;
    entries.forEach(function (en, i) {
      if (p.kind === 'series' && p.seasons.length > 1 && en.season !== lastSeason) {
        lastSeason = en.season;
        rows.push(h('h3', { text: en.season === 0 ? 'Спецвыпуски' : 'Сезон ' + en.season }));
      }
      rows.push(entryRow(it, entries, i));
    });
    if (!entries.length) rows.push(h('p', { class: 'muted', text: 'В раздаче нет видео.' }));
    var back = function () { shell(state.tab, true); };
    var resumeAt = resumeIndex(m, entries);
    var actions = [];
    if (entries.length) {
      var w = watch.get(model.watchKey(m.id, entries[resumeAt].file));
      var label = w && !w.watched && w.pos > 30000 ? '▶ Продолжить с ' + ui.clock(w.pos) : '▶ Смотреть';
      if (entries.length > 1) label += ' · ' + entries[resumeAt].label.replace(it.baseTitle, '').replace(/^[\s·—-]+/, '');
      actions.push(button(label, function () { open(it, entries, resumeAt, { back: function () { details(it); } }); }, 'primary play'));
    }
    var mineBtn = button(m.mine ? '★ В «Моих»' : '☆ В «Мои»', function () {
      var on = !m.mine;
      api.post('/api/shelf', { id: String(m.id), mine: on }).then(function () {
        m.mine = on;
        mineBtn.textContent = on ? '★ В «Моих»' : '☆ В «Мои»';
        toast(on ? 'Добавлено в «Мои»' : 'Убрано из «Моих»');
      }).catch(function (e) { toast(api.describe(e)); });
    });
    actions.push(mineBtn);
    var poster = h('div', { class: 'poster big' }, [h('span', { class: 'poster-title', text: it.baseTitle })]);
    posterInto(poster, it);
    nav.onBack = back;
    show(h('div', { class: 'details' }, [
      h('div', { class: 'details-side' }, [poster]),
      h('div', { class: 'details-main' }, [
        h('h1', { text: it.baseTitle }),
        h('p', { class: 'muted', text: m.name + ' · ' + model.formatBytes(m.size) + (p.kind === 'series' ? ' · серий: ' + entries.length : '') }),
        h('div', { class: 'actions' }, actions),
        h('div', { class: 'entries' }, entries.length > 1 ? rows : entries.length ? notesFor(m, entries[0].file) : rows)
      ])
    ]));
    nav.focus(root.querySelector('.play') || root.querySelector('.f'));
  }

  /** Первая недосмотренная серия (после досмотренных). */
  function resumeIndex(m, entries) {
    var at = 0, last = 0;
    for (var i = 0; i < entries.length; i++) {
      var w = watch.raw(model.watchKey(m.id, entries[i].file));
      if (w && w.at > last) { last = w.at; at = model.watchInfo(w.pos, w.len, w.at).watched ? Math.min(i + 1, entries.length - 1) : i; }
    }
    return at;
  }

  /** Предупреждения о файле: формат (support.js) и озвучки отдельными файлами. */
  function notesFor(m, file) {
    var check = support.checkFile(file.path), dubs = model.externalAudioFor(m, file), notes = [];
    if (check.level !== 'ok') notes.push(h('div', { class: 'note ' + (check.level === 'no' ? 'bad' : 'warn'), text: (check.level === 'no' ? '⛔ ' : '⚠ ') + check.text }));
    if (dubs.length) notes.push(h('div', { class: 'note grey', text: support.EXTERNAL_AUDIO_TEXT + ' (' + dubs.map(function (d) { return model.basename(d.path); }).join(', ') + ')' }));
    return notes;
  }

  function entryRow(it, entries, i) {
    var en = entries[i], m = it.m;
    var check = support.checkFile(en.file.path);
    var w = watch.get(model.watchKey(m.id, en.file));
    var notes = notesFor(m, en.file);
    var bar = w ? h('div', { class: 'progress' }, [h('div', { style: 'width:' + Math.round(w.fraction * 100) + '%' })]) : null;
    return h('div', { class: 'f entry' + (check.level === 'no' ? ' unsupported' : ''), tabindex: '-1', onclick: function () {
      open(it, entries, i, { back: function () { details(it); } });
    } }, [
      h('div', { class: 'entry-main' }, [
        h('span', { class: 'entry-label', text: en.label }),
        h('span', { class: 'muted', text: (w && w.watched ? '✓ ' : '') + model.formatBytes(en.file.size) })
      ]),
      bar
    ].concat(notes));
  }

  /** Открыть файл в плеере. [o]: { back — куда после закрытия, resumeAt — с какого места (мс), room — фильм комнаты }. */
  function open(it, entries, i, o) {
    var en = entries[i];
    if (!en.file.link) { toast('Ссылка на файл ещё не готова. Обновите медиатеку.'); return; }
    var key = model.watchKey(it.m.id, en.file);
    var rf = party.room.file;
    if (!o.room && party.isGuest() && rf && rf.key !== key) {
      dialog('Вы в комнате «Вместе»', 'Фильм комнаты выбирает ' + (party.room.hostName || 'её создатель') + '.', [
        { text: 'Смотреть фильм комнаты', run: followRoom },
        { text: 'Выйти из комнаты и открыть', run: function () { party.leave(); open(it, entries, i, o); } },
        { text: 'Отмена' }
      ]);
      return;
    }
    var check = support.checkFile(en.file.path);
    var go = function () {
      if (party.isHost()) party.putFilm(key, en.label);
      show(h('div')); // экран под плеером пустой: старый не держит фокус и подписки
      SM.player.play({
        magnet: it.m, title: en.label, file: en.file, entries: entries, index: i, watch: watch, toast: toast, dialog: dialog,
        resumeAt: o.resumeAt,
        onClose: function () { SM.sync.run(); o.back(); },
        onEntry: function (j) { open(it, entries, j, { back: o.back, room: o.room }); }
      });
    };
    if (check.level === 'no') dialog(support.MESSAGES.unsupported, check.text + '.', [{ text: 'Всё равно попробовать', run: go }, { text: 'Назад' }]);
    else go();
  }

  // ---- гость в комнате: плеер идёт за фильмом комнаты ----

  var followed = null;
  /** Открыть фильм комнаты (гость), с места комнаты. */
  function followRoom() {
    var f = party.room.file;
    if (!f) return;
    followed = f.key;
    if (SM.player.current() === f.key) return;
    var found = findKey(f.key);
    var go = function (hit) {
      if (!hit) { toast('Фильма комнаты нет в медиатеке (или он ещё загружается).'); return; }
      open(hit.it, hit.entries, hit.index, { back: function () { shell('party', true); }, room: true, resumeAt: party.targetMs() });
    };
    if (found) go(found);
    else loadLibrary(true).then(function () { go(findKey(f.key)); }, function () {});
  }

  function findKey(key) {
    var id = Number(key.split('|')[0]);
    for (var n = 0; n < state.items.length; n++) {
      var it = state.items[n];
      if (it.m.id !== id) continue;
      var entries = entriesOf(it);
      for (var i = 0; i < entries.length; i++) if (model.watchKey(id, entries[i].file) === key) return { it: it, entries: entries, index: i };
    }
    return null;
  }

  party.onChange(function () {
    var t = document.querySelector('.tab[data-tab="party"]');
    if (t) t.textContent = 'Вместе' + (party.inRoom() ? ' ●' : '');
    var f = party.room.file;
    if (!party.isGuest() || !f) { if (!party.inRoom()) followed = null; return; }
    if (f.key === followed) return;
    var playing = SM.player.current();
    // Другая серия того же сериала: плеер переключится сам (party.attach); другой фильм — открываем здесь
    if (playing && playing.split('|')[0] === f.key.split('|')[0]) { followed = f.key; return; }
    followRoom();
  });

  // ---- Настройки и «Что нового» ----

  function renderSettings(box) {
    var u = state.user;
    var rows = [
      h('div', { class: 'card-row' }, [
        h('h2', { text: 'Аккаунт' }),
        h('p', { text: u ? (u.name || u.username || 'Без имени') + (u.id === 'shared' ? ' (общий вход)' : '') : 'Вход выполнен' }),
        h('p', { class: 'muted', text: SM.sync.active() ? 'Прогресс просмотра общий с компьютером и телефоном.' : 'Прогресс просмотра хранится только на этом телевизоре (общий — при входе через Discord).' }),
        button('Выйти из аккаунта', logout)
      ]),
      h('div', { class: 'card-row' }, [
        h('h2', { text: 'Приложение' }),
        h('p', { text: 'Версия ' + SM.VERSION + ' для Samsung TV' }),
        h('div', { class: 'row left' }, [button('Что нового', function () { whatsNew(); }), button('Обновить медиатеку', function () {
          loadLibrary(true).then(function () { toast('Медиатека обновлена'); }, function () {});
        })])
      ]),
      h('div', { class: 'card-row' }, [
        h('h2', { text: 'Запас видео' }),
        h('p', { class: 'muted', text: 'Большой запас: плеер набирает 8 с видео перед стартом и 15 с после остановки — меньше рывков на тяжёлых файлах, ' +
          'но старт и перемотка дольше, а на некоторых телевизорах экран на это время замирает. Применяется при следующем открытии фильма.' }),
        h('div', { class: 'row left' }, [['std', 'Как у телевизора'], ['big', 'Большой']].map(function (o) {
          var on = (localStorage.getItem('tv_buffer') || 'std') === o[0];
          var b = button((on ? '● ' : '') + o[1], function () {
            try { localStorage.setItem('tv_buffer', o[0]); } catch (e) { /* нет хранилища */ }
            refreshTab();
          }, 'chip' + (on ? ' on' : ''));
          b.setAttribute('data-key', 'buf-' + o[0]);
          return b;
        }))
      ]),
      h('div', { class: 'card-row' }, [
        h('h2', { text: 'Что телевизор не воспроизводит' }),
        h('p', { class: 'muted', text: 'Звук DTS и TrueHD, видео AV1, образы дисков .iso; Blu-ray (.m2ts) и очень тяжёлые файлы могут идти рывками. ' +
          'Такие раздачи помечены ⚠ или ⛔. Озвучки и субтитры отдельными файлами пока недоступны. В плеере жёлтая кнопка показывает пробу: какие дорожки увидел телевизор.' })
      ])
    ];
    rows.forEach(function (r) { box.appendChild(r); });
  }

  function whatsNew(after) {
    var items = [];
    SM.CHANGELOG.forEach(function (v) {
      items.push(h('h3', { text: v.version + ' · ' + v.date }));
      items.push(h('ul', {}, v.items.map(function (t) { return h('li', { text: t }); })));
    });
    var prevBack = nav.onBack, prevRoot = nav.root, prevFocus = document.activeElement;
    var box = h('div', { class: 'dialog' }, [h('div', { class: 'dialog-box wide' }, [
      h('h2', { text: 'Что нового' }),
      h('div', { class: 'changelog' }, items),
      h('div', { class: 'row' }, [button('Понятно', close, 'primary')])
    ])]);
    function close() {
      if (!box.parentNode) return;
      box.parentNode.removeChild(box);
      nav.root = prevRoot; nav.onBack = prevBack;
      if (prevFocus && document.body.contains(prevFocus)) nav.focus(prevFocus); else nav.focusFirst(prevRoot);
      if (after) after();
    }
    document.body.appendChild(box);
    nav.root = box; nav.onBack = close;
    nav.focusFirst(box);
  }

  /** После обновления приложения — один раз «Что нового». */
  function whatsNewOnce() {
    var seen = null;
    try { seen = localStorage.getItem('seen_version'); localStorage.setItem('seen_version', SM.VERSION); } catch (e) { return; }
    if (seen && seen !== SM.VERSION) whatsNew();
  }

  /** Кнопки пульта, которые без регистрации телевизор оставляет себе. */
  function registerKeys() {
    var keys = ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaRewind', 'MediaFastForward', 'ColorF2Yellow', 'Info'];
    keys.forEach(function (k) {
      try { g.tizen.tvinputdevice.registerKey(k); } catch (e) { /* не на ТВ или кнопки нет */ }
    });
  }

  SM.app = {
    start: start, shell: shell, refreshTab: refreshTab, onLeave: onLeave, libraryChanged: libraryChanged, followRoom: followRoom,
    items: function () { return state.items; }, findKey: findKey, tabIs: function (id) { return state.tab === id && contentEl && document.body.contains(contentEl); }
  };
  registerKeys();
  start();
})(window);

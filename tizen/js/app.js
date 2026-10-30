/*
 * Экраны: вход по коду устройства (QR-код и ссылка), затем вкладки сверху — Медиатека («Продолжить просмотр» и все
 * раздачи), Мои, Поиск (search.js), Добавить (add.js), Вместе (together.js), Настройки; раздача (фильм или серии) и
 * плеер (player.js). Только ES2017 (Tizen 4.0).
 */
(function (g) {
  'use strict';
  var SM = g.SM, api = SM.api, model = SM.model, support = SM.support, nav = SM.nav, ui = SM.ui, party = SM.party;
  var tr = SM.tr || g.tr || function (k) { return k; };
  var h = ui.h, button = ui.button, toast = ui.toast, dialog = ui.dialog;
  var root = ui.root;

  // Вкладки других файлов (search.js, add.js, together.js) уже в SM.tabs; порядок — как на Android
  var TABS = [
    { id: 'home', title: tr('nav.library') }, { id: 'mine', title: tr('nav.mine') }, { id: 'search', title: tr('nav.search') },
    { id: 'add', title: tr('nav.add') }, { id: 'party', title: tr('nav.together') }, { id: 'settings', title: tr('nav.settings') }
  ];
  var tabs = SM.tabs = SM.tabs || {};

  var state = { items: [], loadedAt: 0, tab: 'home', focusKey: null, user: null, pages: {} };

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
    if (!api.HOST) { show(h('div', { class: 'center' }, [h('h1', { text: tr('auth.no_site_host') }), h('p', { text: tr('auth.no_site_host_desc') })])); return; }
    show(ui.spinner(tr('auth.connecting')));
    api.get('/api/session').then(function (s) {
      if (!s.authenticated) { login(); return; }
      signedIn(s.user || null);
    }).catch(function (e) {
      show(h('div', { class: 'center' }, [h('h1', { text: tr('common.connection_error') }), h('p', { text: api.describe(e) }), button(tr('common.retry'), start, 'primary')]));
      nav.focusFirst();
    });
  }

  function signedIn(user) {
    state.user = user;
    SM.sync.setUser(user && user.id);
    var syncChanged = function (n) { if (n && state.tab === 'home' && !SM.player.current()) refreshTab(); };
    SM.sync.onChanged = syncChanged; // периодический опрос, пока приложение открыто
    loadLibrary(true).then(function () {
      shell('home', true);
      SM.sync.run(syncChanged);
      whatsNewOnce();
    }, function () {});
  }

  function login(error) {
    clearInterval(pollTimer);
    nav.onBack = askExit;
    show(h('div', { class: 'center' }, [
      h('img', { class: 'logo', src: 'icon.png', alt: '' }),
      h('h1', { text: tr('auth.login_title') }),
      error ? h('p', { class: 'error', text: error }) : h('p', { text: tr('auth.login_intro') }),
      button(tr('auth.login_btn'), pair, 'primary')
    ]));
    nav.focusFirst();
  }

  function pair() {
    show(ui.spinner(tr('auth.getting_code')));
    nav.onBack = function () { login(); };
    api.post('/api/auth/device', { name: 'Suslik Media Samsung TV', site_url: api.SITE.replace(/\/+$/, '') }, false).then(function (res) {
      var userCode = res.user_code, code = res.code;
      var url = res.verification_url && res.verification_url.indexOf('code=') >= 0 ? res.verification_url : api.SITE + 'link?code=' + userCode;
      nav.onBack = function () { login(); };
      show(h('div', { class: 'pair' }, [
        h('div', { class: 'pair-qr' }, [ui.qr(url, 480), h('p', { class: 'muted', text: tr('auth.scan_qr') })]),
        h('div', { class: 'pair-text' }, [
          h('h1', { text: tr('auth.pair_title') }),
          h('p', { text: tr('auth.pair_desc') }),
          h('div', { class: 'link', text: api.SITE.replace(/^https?:\/\//, '') + 'link' }),
          h('div', { class: 'code', text: userCode }),
          h('p', { class: 'muted', text: tr('auth.pair_expiry') }),
          button(tr('common.cancel'), function () { login(); })
        ])
      ]));
      nav.focusFirst();
      var polling = false; // ответ медленнее 2 с: следующий опрос ждёт, а не копится
      pollTimer = setInterval(function () {
        if (polling) return;
        polling = true;
        api.post('/api/auth/device/poll', { code: code }).then(function (r) {
          polling = false;
          if (r.status === 'authorized') {
            clearInterval(pollTimer);
            api.setToken(r.device_token || null);
            api.get('/api/session').then(function (s) { signedIn(s.user || null); }, function () { signedIn(null); });
          } else if (r.status === 'expired') login(tr('auth.code_expired'));
        }).catch(function (e) {
          polling = false;
          if (e && (e.code === 410 || e.code === 404)) login(tr('auth.code_expired'));
        });
      }, 2000);
    }).catch(function (e) { login(api.describe(e)); });
  }

  function askExit() {
    dialog(tr('app.exit_confirm'), null, [{ text: tr('common.close'), run: exit }, { text: tr('common.cancel') }]);
  }

  function exit() {
    try { g.tizen.application.getCurrentApplication().exit(); } catch (e) { /* не на ТВ */ }
  }

  function logout() {
    dialog(tr('settings.logout_confirm'), tr('settings.logout_hint'), [
      { text: tr('settings.logout'), run: function () {
        party.leave();
        api.post('/api/logout').catch(function () {});
        api.setToken(null);
        SM.sync.setUser(null);
        posters.clear();
        login();
      } },
      { text: tr('common.cancel') }
    ]);
  }

  // ---- медиатека ----

  var loading = null;
  /** Медиатека с сервера; [force] = false — не чаще раза в минуту. */
  function loadLibrary(force) {
    if (!force && Date.now() - state.loadedAt < 60000) return Promise.resolve(state.items);
    if (loading) return loading;
    if (!state.loadedAt) show(ui.spinner(tr('library.refreshing')));
    loading = api.get('/api/library').then(function (json) {
      loading = null;
      // Раздачи только с озвучками (.mka) играют внутри фильма — плиткой их не показываем
      state.items = model.prepareItems(model.parseLibrary(json).filter(function (m) { return !model.isVoiceOverOnly(m); }));
      state.loadedAt = Date.now();
      posters.fill(state.items, function () { if (state.tab === 'home' || state.tab === 'mine') refreshTab(); });
      return state.items;
    }, function (e) {
      loading = null;
      if (e && e.code === 401) { api.setToken(null); login(); throw e; }
      if (!state.loadedAt) {
        show(h('div', { class: 'center' }, [h('h1', { text: tr('library.load_error') }), h('p', { text: api.describe(e) }), button(tr('common.retry'), start, 'primary')]));
        nav.focusFirst();
      } else toast(tr('library.update_error', { error: api.describe(e) }));
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
    else if (id === 'mine') renderGrid(contentEl, state.items.filter(function (it) { return it.m.mine; }), tr('library.empty_mine'), 'mine');
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
      box.appendChild(h('h2', { class: 'section', text: tr('library.hero_continue') }));
      box.appendChild(h('div', { class: 'strip' }, cont.map(continueCard)));
      box.appendChild(h('h2', { class: 'section', text: tr('library.all_releases') }));
    }
    renderGrid(box, state.items, tr('library.empty'), 'home');
  }

  /** Страница — 3 ряда постеров (слабому ТВ хватает памяти и времени); сколько их в ряду, столько и ставится. Остальные — на следующих страницах. */
  var PAGE_ROWS = 3;
  var TILE_STEP = 276; // плитка 248 + отступ 28 (css/app.css)
  function pageSize(box) { return Math.max(1, Math.floor((box.clientWidth || 1620) / TILE_STEP)) * PAGE_ROWS; }

  function renderGrid(box, list, empty, key) {
    if (!list.length) { box.appendChild(h('p', { class: 'muted empty', text: empty })); return; }
    var size = pageSize(box);
    // Одна плитка на несколько версий одного фильма / сериала; с версиями плитка спрашивает, какую открыть
    var grouped = model.groupVersions(sorted(list), function (it) { return posters.pathOf(it.posterQueries); });
    var all = grouped.shown, pages = Math.ceil(all.length / size);
    var page = Math.min(Math.max(0, state.pages[key] || 0), pages - 1);
    box.appendChild(h('div', { class: 'grid' }, all.slice(page * size, (page + 1) * size).map(function (it) { return tile(it, grouped.versions[it.m.id]); })));
    if (pages > 1) box.appendChild(pager(key, page, pages));
  }

  /** «‹ 1 2 3 … 9 ›» под сеткой: пультом влево/вправо, OK открывает страницу. */
  function pager(key, page, pages) {
    var bar = h('div', { class: 'pager' });
    function btn(label, n, active, off) {
      if (off) return h('span', { class: 'pager-btn off', text: label });
      return h('div', { class: 'f pager-btn' + (active ? ' active' : ''), tabindex: '-1', 'data-key': 'pg:' + label, text: label, onclick: function () {
        if (active) return;
        state.pages[key] = n;
        renderTab(state.tab);
        var again = contentEl.querySelector('[data-key="pg:' + label + '"]') || contentEl.querySelector('.pager .f');
        nav.focus(again);
      } });
    }
    bar.appendChild(btn('‹', page - 1, false, page === 0));
    var gap = false;
    for (var n = 0; n < pages; n++) {
      if (!(n === 0 || n === pages - 1 || Math.abs(n - page) <= 2)) {
        if (!gap) { bar.appendChild(h('span', { class: 'pager-gap', text: '…' })); gap = true; }
        continue;
      }
      gap = false;
      bar.appendChild(btn(String(n + 1), n, n === page, false));
    }
    bar.appendChild(btn('›', page + 1, false, page === pages - 1));
    return bar;
  }

  /**
   * Серии и фильмы раздачи по порядку: { label, file, season, room }. room — подпись фильма в комнате «Вместе»,
   * как в Discord на ПК: «Название · Сезон 1, серия 2»; фильм подборки — своим именем.
   */
  function entriesOf(it) {
    var p = it.playable, out = [], name = posters.titleOf(it) || it.baseTitle;
    if (p.kind === 'movie') out.push({ label: it.baseTitle, file: p.file, room: name });
    else if (p.kind === 'series') p.seasons.forEach(function (s) {
      s.items.forEach(function (e) {
        var room = e.title || name + ' · ' + (s.season === 0 ? tr('together.special', { episode: e.episode })
          : tr('together.season_episode', { season: s.season, episode: e.episode }));
        out.push({ label: model.episodeLabel(it.baseTitle, s, e), file: e.file, season: s.season, room: room });
      });
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
    var poster = h('div', { class: 'poster' }, [h('span', { class: 'poster-title', text: posters.titleOf(c.it) })]);
    var el = h('div', { class: 'f card', tabindex: '-1', 'data-key': key, onclick: function () {
      state.focusKey = key;
      open(c.it, c.entries, c.index, { back: function () { shell('home', true); } });
    } }, [
      poster,
      h('div', { class: 'card-bar' }, [h('div', { style: 'width:' + Math.round(c.fraction * 100) + '%' })]),
      h('div', { class: 'tile-name', text: en.label }),
      h('div', { class: 'tile-sub', text: c.next ? tr('library.next_episode') : tr('library.left_time', { time: left(c) }) })
    ]);
    posterInto(poster, c.it);
    return el;
  }

  function left(c) {
    var w = watch.raw(model.watchKey(c.it.m.id, c.entries[c.index].file));
    return w && w.len > w.pos ? ui.clock(w.len - w.pos) : '';
  }

  function tile(it, versions) {
    var m = it.m, main = model.mainVideo(m);
    var group = versions || [it];
    var check = main ? support.checkFile(main.path) : { level: 'ok' };
    var name = posters.titleOf(it);
    var poster = h('div', { class: 'poster' }, [h('span', { class: 'poster-title', text: name })]);
    var badges = [];
    if (!m.ready) badges.push(h('span', { class: 'badge', text: model.statusRu(m) + (m.downloading ? ' ' + Math.round(m.progress * 100) + '%' : '') }));
    if (check.level === 'no') badges.push(h('span', { class: 'badge bad', text: tr('library.badge_unsupported') }));
    else if (check.level === 'warn') badges.push(h('span', { class: 'badge warn', text: '⚠' }));
    else if (check.level === 'server') badges.push(h('span', { class: 'badge server', text: tr('library.badge_via_server') }));
    if (group.some(function (v) { return v.m.mine; })) badges.push(h('span', { class: 'badge mine', text: '★' }));
    if (group.length > 1) badges.push(h('span', { class: 'badge', text: versionsWord(group.length) }));
    var key = 't' + m.id;
    var el = h('div', { class: 'f tile' + (m.ready ? '' : ' dim'), tabindex: '-1', 'data-key': key, onclick: function () {
      state.focusKey = key;
      if (!m.ready) { toast(tr('library.not_ready', { status: model.statusRu(m) })); return; }
      if (group.length > 1) pickVersion(name, group); else details(it);
    } }, [poster, h('div', { class: 'badges' }, badges), h('div', { class: 'tile-name', text: name })]);
    posterInto(poster, it);
    return el;
  }

  function versionsWord(n) { return n % 10 >= 2 && n % 10 <= 4 && !(n % 100 >= 12 && n % 100 <= 14) ? n + ' версии' : n + ' версий'; }

  /** «Какую версию включить?»: строка на раздачу — серии и сезоны в ней, размер и имя; открывает её экран. */
  function pickVersion(title, versions) {
    // versions уже упорядочены groupVersions (sortVersions)
    dialog(tr('library.pick_version_title'), title + ' · ' + versionsWord(versions.length), versions.map(function (v) {
      var q = /2160p|4k/i.test(v.m.name) ? '4K' : /720p/i.test(v.m.name) ? '720p' : '1080p';
      var line = model.versionLine(v); // сезоны / фильмы сборника / год (versionLine в Model.kt)
      var head = (line ? line + ' • ' : '') + q;
      return { text: head + ' · ' + model.formatBytes(v.m.size) + ' · ' + shorten(v.m.name, 60), run: function () { details(v); } };
    }), 'versions');
  }
  function shorten(s, n) { return s.length > n ? s.substring(0, n - 1) + '…' : s; }

  // Постер грузится, когда плитка подъезжает к экрану (за 600 px): медиатека в сотни раздач не качает все картинки
  // разом и не вытесняет из памяти те, что на экране
  var seen = typeof IntersectionObserver === 'function' ? new IntersectionObserver(function (list) {
    list.forEach(function (e) {
      if (!(e.isIntersecting || e.intersectionRatio > 0)) return; // isIntersecting — только с Chromium 58
      seen.unobserve(e.target);
      if (e.target.wantPoster) { var f = e.target.wantPoster; e.target.wantPoster = null; f(); }
    });
  }, { rootMargin: '600px' }) : null;

  function posterInto(poster, it) {
    var q = it.posterQueries[0];
    var apply = function (url) {
      poster.style.backgroundImage = 'url("' + url + '")';
      poster.classList.add('has-image');
    };
    if (!seen || posters.has(q)) { posters.want(q, apply); return; }
    poster.wantPoster = function () { posters.want(q, apply); };
    seen.observe(poster);
  }

  /** Постеры: /api/poster?q= → путь картинки → blob (нужен токен). Не больше трёх запросов сразу. */
  var posters = (function () {
    // Не больше 60 картинок в памяти (у ТВ 2018 года на страницу ~200 МБ): дольше всех не показанные освобождаются.
    // Путь картинки на сервере ([paths]) помним всегда: повторный показ — без нового /api/poster
    var cache = {}, paths = {}, order = [], queue = [], active = 0, LIMIT = 60;
    // Пути постеров помним и между запусками: по ним сливаются версии одного фильма (один постер = один фильм)
    var PATHS_KEY = 'sm.posterPaths';
    try { paths = JSON.parse(localStorage.getItem(PATHS_KEY) || '{}') || {}; } catch (e) { paths = {}; }
    // Русские названия TMDB по запросу ('' — у TMDB нет): подпись плитки, если название ИИ не принято
    var TITLES_KEY = 'sm.posterTitles', titles = {};
    try { titles = JSON.parse(localStorage.getItem(TITLES_KEY) || '{}') || {}; } catch (e) { titles = {}; }
    function noteTitle(q, r) {
      titles[q] = r && typeof r.title_ru === 'string' ? r.title_ru.trim() : '';
      try { localStorage.setItem(TITLES_KEY, JSON.stringify(titles)); } catch (e) { /* место кончилось */ }
      return !!titles[q];
    }
    function titleOf(it) { return model.tileTitle(it, function (q) { return titles[q]; }); }
    var saveTimer = 0;
    function savePaths() {
      clearTimeout(saveTimer);
      saveTimer = setTimeout(function () { try { var keep = {}; Object.keys(paths).forEach(function (k) { if (paths[k]) keep[k] = paths[k]; }); localStorage.setItem(PATHS_KEY, JSON.stringify(keep)); } catch (e) { /* место кончилось */ } }, 1500);
    }
    function pathOf(queries) {
      for (var i = 0; i < queries.length; i++) if (typeof paths[queries[i]] === 'string') return paths[queries[i]];
      return null;
    }
    /** Спросить у сервера постеры готовых раздач, которых ещё нет (не больше 40 за раз, по одному); [done] — когда что-то нашлось. */
    var filling = false;
    function fill(items, done) {
      if (filling) return;
      var todo = [], seen = {};
      items.forEach(function (it) {
        var q = it.posterQueries[0];
        if (it.m.ready && q && !seen[q] && ((pathOf(it.posterQueries) == null && paths[q] === undefined) || titles[q] === undefined)) { seen[q] = 1; todo.push(q); }
      });
      todo = todo.slice(0, 40);
      if (!todo.length) return;
      filling = true;
      var n = 0;
      (function next() {
        if (!todo.length) { filling = false; if (n) done(); return; }
        var q = todo.shift();
        api.get('/api/poster?q=' + encodeURIComponent(q)).then(function (r) {
          var p = r.poster;
          paths[q] = typeof p === 'string' && p.indexOf('/api/poster/img/') === 0 ? p : null;
          if (paths[q]) { n++; savePaths(); }
          if (noteTitle(q, r)) n++;
        }, function () {}).then(function () { setTimeout(next, 150); });
      })();
    }
    function touch(q) { var i = order.indexOf(q); if (i >= 0) order.splice(i, 1); order.push(q); }
    function remember(q, url) {
      cache[q] = url;
      if (!url) return;
      touch(q);
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
      var path = paths[q] !== undefined ? Promise.resolve(paths[q]) : api.get('/api/poster?q=' + encodeURIComponent(q)).then(function (r) {
        var p = r.poster;
        paths[q] = typeof p === 'string' && p.indexOf('/api/poster/img/') === 0 ? p : null;
        savePaths();
        noteTitle(q, r);
        return paths[q];
      });
      return path.then(function (p) {
        if (!p) { cache[q] = null; return null; }
        return api.blobUrl(p).then(function (u) { remember(q, u); return u; });
      });
    }
    return {
      has: function (q) { return !!cache[q]; },
      pathOf: pathOf,
      titleOf: titleOf,
      fill: fill,
      want: function (q, cb) {
        if (cache[q]) { touch(q); cb(cache[q]); } else if (cache[q] !== null) { queue.push({ q: q, cb: cb }); pump(); }
      },
      /** Начался фильм: очередь постеров не делит канал с AVPlay. */
      drop: function () { queue = []; },
      clear: function () { order.forEach(function (q) { if (cache[q]) URL.revokeObjectURL(cache[q]); }); cache = {}; paths = {}; titles = {}; order = []; queue = []; }
    };
  })();

  // ---- раздача: фильм или серии ----

  function details(it) {
    var m = it.m, p = it.playable, entries = entriesOf(it);
    var rows = [], lastSeason = null;
    entries.forEach(function (en, i) {
      if (p.kind === 'series' && p.seasons.length > 1 && en.season !== lastSeason) {
        lastSeason = en.season;
        rows.push(h('h3', { text: en.season === 0 ? tr('player.specials') : tr('player.season', { season: en.season }) }));
      }
      rows.push(entryRow(it, entries, i));
    });
    if (!entries.length) rows.push(h('p', { class: 'muted', text: tr('library.no_video') }));
    var back = function () { shell(state.tab, true); };
    var resumeAt = resumeIndex(m, entries);
    var actions = [];
    if (entries.length) {
      var w = watch.get(model.watchKey(m.id, entries[resumeAt].file));
      var label = w && !w.watched && w.pos > 30000 ? tr('player.resume', { time: ui.clock(w.pos) }) : tr('player.play');
      if (entries.length > 1) label += ' · ' + entries[resumeAt].label.replace(it.baseTitle, '').replace(/^[\s·—-]+/, '');
      actions.push(button(label, function () { open(it, entries, resumeAt, { back: function () { details(it); } }); }, 'primary play'));
      // Начато — можно и с начала (с места — основная кнопка)
      if (w && !w.watched && w.pos > 30000) {
        actions.push(button(tr('player.from_beginning'), function () { open(it, entries, resumeAt, { back: function () { details(it); }, resumeAt: 0 }); }));
      }
    }
    var mineBtn = button(m.mine ? '★ ' + tr('library.in_mine') : '☆ ' + tr('library.to_mine'), function () {
      var on = !m.mine;
      api.post('/api/shelf', { id: String(m.id), mine: on }).then(function () {
        m.mine = on;
        mineBtn.textContent = on ? '★ ' + tr('library.in_mine') : '☆ ' + tr('library.to_mine');
        toast(on ? tr('library.added_to_mine') : tr('library.removed_from_mine'));
      }).catch(function (e) { toast(api.describe(e)); });
    });
    actions.push(mineBtn);
    var poster = h('div', { class: 'poster big' }, [h('span', { class: 'poster-title', text: posters.titleOf(it) })]);
    posterInto(poster, it);
    nav.onBack = back;
    show(h('div', { class: 'details' }, [
      h('div', { class: 'details-side' }, [poster]),
      h('div', { class: 'details-main' }, [
        h('h1', { text: posters.titleOf(it) }),
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
    if (check.level !== 'ok') notes.push(h('div', { class: 'note ' + ({ no: 'bad', server: 'grey' }[check.level] || 'warn'), text: ({ no: '⛔ ', server: '⇄ ' }[check.level] || '⚠ ') + check.text }));
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
    if (!en.file.link) { toast(tr('player.link_not_ready')); return; }
    var key = model.watchKey(it.m.id, en.file);
    var rf = party.room.file;
    if (!o.room && party.isGuest() && rf && rf.key !== key) {
      dialog(tr('together.in_room_dialog_title'), tr('together.in_room_dialog_desc', { host: (party.room.hostName || tr('together.host')) }), [
        { text: tr('together.watch_room_film'), run: followRoom },
        { text: tr('together.leave_and_open'), run: function () { party.leave(); open(it, entries, i, o); } },
        { text: tr('common.cancel') }
      ]);
      return;
    }
    var check = support.checkFile(en.file.path);
    var go = function () {
      if (party.isHost()) party.putFilm(key, en.room || en.label);
      show(h('div')); // экран под плеером пустой: старый не держит фокус и подписки
      posters.drop();
      SM.player.play({
        magnet: it.m, title: en.label, file: en.file, entries: entries, index: i, watch: watch, toast: toast, dialog: dialog,
        resumeAt: o.resumeAt,
        onClose: function () { SM.sync.run(); o.back(); },
        onEntry: function (j) { open(it, entries, j, { back: o.back, room: o.room }); }
      });
    };
    if (check.level === 'no') dialog(support.MESSAGES.unsupported, check.text + '.', [{ text: tr('player.try_anyway'), run: go }, { text: tr('common.back') }]);
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
      if (!hit) { toast(tr('together.room_film_not_found')); return; }
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
    if (t) t.textContent = tr('nav.together') + (party.inRoom() ? ' ●' : '');
    var f = party.room.file;
    // Фильма нет (ведущий закрыл): тот же фильм, поставленный снова, откроется снова
    if (party.joinedKey !== undefined) { followed = party.joinedKey; party.joinedKey = undefined; }
    if (!party.isGuest() || !f) { if (!party.inRoom() || !f) followed = null; return; }
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
        h('h2', { text: tr('settings.account') }),
        h('p', { text: u ? (u.name || u.username || 'Без имени') + (u.id === 'shared' ? tr('settings.shared_login') : '') : 'Вход выполнен' }),
        h('p', { class: 'muted', text: SM.sync.active() ? tr('settings.sync_on') : tr('settings.sync_off') }),
        button(tr('settings.logout'), logout)
      ]),
      h('div', { class: 'card-row' }, [
        h('h2', { text: tr('settings.app_title') }),
        h('p', { text: tr('settings.version_label', { version: SM.VERSION }) }),
        h('div', { class: 'row left' }, [button(tr('settings.whats_new'), function () { whatsNew(); }), button(tr('library.refresh'), function () {
          loadLibrary(true).then(function () { toast(tr('library.refreshed')); }, function () {});
        })])
      ]),
      h('div', { class: 'card-row' }, [
        h('h2', { text: tr('settings.buffer_title') }),
        h('p', { class: 'muted', text: tr('settings.buffer_desc') }),
        h('div', { class: 'row left' }, [['std', tr('settings.buffer_std')], ['big', tr('settings.buffer_big')]].map(function (o) {
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
        h('h2', { text: tr('settings.unsupported_formats_title') }),
        h('p', { class: 'muted', text: tr('settings.unsupported_formats_desc') })
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
      h('h2', { text: tr('settings.whats_new') }),
      h('div', { class: 'changelog' }, items),
      h('div', { class: 'row' }, [button(tr('common.understand'), close, 'primary')])
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
    var keys = ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaRewind', 'MediaFastForward', 'MediaTrackPrevious', 'MediaTrackNext',
      'ColorF2Yellow', 'Info'];
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

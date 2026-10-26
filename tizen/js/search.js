/*
 * Вкладка «Поиск»: раздачи по трекерам (/api/search), добавление в медиатеку (/api/search/add) и ход загрузки
 * (/api/magnets/<id>). Как android/.../library/SearchScreen.kt: результаты и фильтры живут, пока открыты другие вкладки.
 */
(function (g) {
  'use strict';
  var SM = g.SM, api = SM.api, ui = SM.ui, model = SM.model, core = SM.searchcore, h = ui.h, button = ui.button;

  var KINDS = [{ id: 'video', title: 'Фильмы и сериалы' }, { id: 'movie', title: 'Фильмы' }, { id: 'cartoon', title: 'Мультфильмы' },
    { id: 'series', title: 'Сериалы' }, { id: 'all', title: 'Всё' }];
  var KIND_RU = { series: 'сериал', movie: 'фильм', cartoon: 'мультфильм' };

  var RESOLUTIONS = [{ id: '', title: 'Любое' }, { id: '4K', title: '4K' }, { id: '1080p', title: '1080p' }, { id: '720p', title: '720p' }];
  var PAGE = 25;

  // Тот же поиск, что на ПК (searchcore.js: разбор релиза, пресеты, качество, сортировка); прямого поиска с ПК (RuTracker) на ТВ нет
  var s = { query: '', active: '', busy: false, status: null, ok: true, hits: [], kind: 'video', seeded: false, onlyRu: false, preset: 'ALL', resolution: '', sort: 'DEFAULT', refine: '', count: PAGE, notes: {} };

  function keyed(el, key) { el.setAttribute('data-key', key); return el; }

  function rerender() { if (SM.app.tabIs('search')) SM.app.refreshTab(); }

  function run(q) {
    q = String(q || '').trim();
    s.query = q;
    if (q.length < 2 || s.busy) { s.status = 'Введите минимум 2 символа.'; s.ok = false; rerender(); return; }
    s.active = core.cleanQuery(q) || q; s.count = PAGE;
    s.busy = true; s.status = 'Опрашиваем трекеры…'; s.ok = true; s.hits = [];
    rerender();
    api.get('/api/search?q=' + encodeURIComponent(s.active)).then(function (j) {
      s.hits = (Array.isArray(j.results) ? j.results : []).map(core.toHit).filter(function (x) { return x && !core.isNonVideo(x.title); });
      var failed = (Array.isArray(j.providers) ? j.providers : []).filter(function (p) { return p && !p.ok; }).length;
      s.status = !s.hits.length ? 'Ничего не найдено.' :
        'Найдено ' + s.hits.length + ' за ' + ((Number(j.tookMs) || 0) / 1000).toFixed(1).replace('.', ',') + ' с' + (failed ? ' · не ответили: ' + failed : '');
      s.ok = s.hits.length > 0;
    }).catch(function (e) { s.status = api.describe(e); s.ok = false; })
      .then(function () { s.busy = false; rerender(); });
  }

  function note(id, text, ok) { s.notes[id] = { text: text, ok: ok }; rerender(); }

  function add(hit) {
    if (s.notes[hit.id] && s.notes[hit.id].ok) return; // уже добавляется
    note(hit.id, 'Добавляю…', true);
    api.post('/api/search/add', { id: hit.id }, false).then(function (r) {
      if (r.ready) { note(hit.id, 'Готово — уже в медиатеке', true); SM.app.libraryChanged(); return; }
      note(hit.id, 'Добавлено, идёт загрузка…', true);
      SM.downloads.watch(r.id, function (st) {
        if (st.done) { note(hit.id, 'Готово — файлы в медиатеке', true); SM.app.libraryChanged(); }
        else if (st.failed) note(hit.id, 'Ошибка: ' + st.text, false);
        else note(hit.id, st.text, true);
      });
    }).catch(function (e) { note(hit.id, api.describe(e), false); });
  }

  function shown() {
    return core.filter(s.hits, { query: s.active, kind: s.kind, preset: s.preset, resolution: s.resolution || null, onlySeeded: s.seeded, onlyRu: s.onlyRu, refine: s.refine, sort: s.sort });
  }

  function chipRow(label, items, current, pick, prefix) {
    var chips = items.map(function (it, i) {
      var c = button(it.title, function () { pick(it.id); s.count = PAGE; SM.app.refreshTab(); }, 'chip' + (current === it.id ? ' on' : ''));
      c.setAttribute('data-key', prefix + i);
      return c;
    });
    return h('div', { class: 'chips' }, [h('span', { class: 'muted chip-label', text: label })].concat(chips));
  }

  function render(box) {
    var input = ui.field('Название фильма или сериала…', run, s.query);
    input.setAttribute('data-key', 'q');
    box.appendChild(h('div', { class: 'search-head' }, [
      input, keyed(button(s.busy ? 'Ищем…' : 'Найти', function () { run(input.value()); }, 'primary'), 'go')
    ]));
    var seeded = button((s.seeded ? '✓ ' : '') + 'Только с сидами', function () { s.seeded = !s.seeded; s.count = PAGE; SM.app.refreshTab(); }, 'chip' + (s.seeded ? ' on' : ''));
    seeded.setAttribute('data-key', 'seeded');
    var kinds = chipRow('Что искать', KINDS, s.kind, function (v) { s.kind = v; }, 'k');
    kinds.appendChild(seeded);
    var onlyRu = button((s.onlyRu ? '✓ ' : '') + 'Только RU-трекеры', function () { s.onlyRu = !s.onlyRu; s.count = PAGE; SM.app.refreshTab(); }, 'chip' + (s.onlyRu ? ' on' : ''));
    onlyRu.setAttribute('data-key', 'onlyru');
    kinds.appendChild(onlyRu);
    box.appendChild(kinds);
    if (s.status) box.appendChild(h('p', { class: s.ok ? 'muted' : 'error', text: s.status }));
    if (s.hits.length) {
      box.appendChild(chipRow('Пресет', core.PRESETS, s.preset, function (v) { s.preset = v; }, 'p'));
      box.appendChild(chipRow('Качество', RESOLUTIONS, s.resolution, function (v) { s.resolution = v; }, 'r'));
      box.appendChild(chipRow('Сортировка', core.SORTS, s.sort, function (v) { s.sort = v; }, 's'));
    }
    var list = shown();
    if (s.hits.length && list.length < s.hits.length) box.appendChild(h('p', { class: 'muted small', text: 'Скрыто фильтрами: ' + (s.hits.length - list.length) }));
    if (!s.hits.length && !s.status) box.appendChild(h('p', { class: 'muted', text: 'Один запрос — сразу по нескольким трекерам. Нажмите OK на результате, чтобы добавить раздачу в медиатеку.' }));
    var rows = list.slice(0, s.count).map(row);
    if (list.length > s.count) {
      rows.push(keyed(button('Показать ещё (' + (list.length - s.count) + ')', function () { s.count += PAGE; SM.app.refreshTab(); }), 'more'));
    }
    box.appendChild(h('div', { class: 'results' }, rows));
  }

  function row(x) {
    var n = s.notes[x.id];
    var check = SM.support.checkFile(x.title);
    var meta = [
      h('span', { class: x.seeders > 0 ? 'ok' : 'muted', text: '↑ ' + x.seeders }),
      h('span', { class: 'muted', text: '↓ ' + x.leechers }),
      h('span', { class: 'muted', text: model.formatBytes(x.size) }),
      KIND_RU[x.kind] ? h('span', { class: 'muted', text: KIND_RU[x.kind] }) : null,
      x.source ? h('span', { class: 'muted', text: x.source }) : null,
      x.date ? h('span', { class: 'muted', text: x.date }) : null
    ];
    return h('div', { class: 'f result', tabindex: '-1', 'data-key': 'r' + x.id, onclick: function () { add(x); } }, [
      h('div', { class: 'result-title', text: x.release.cleanTitle || x.title }),
      x.release.cleanTitle && x.release.cleanTitle !== x.title ? h('div', { class: 'muted small result-raw', text: x.title }) : null,
      h('div', { class: 'result-badges' }, core.badges(x.release).map(function (bd) { return h('span', { class: 'rb ' + bd.tone, text: bd.text }); })),
      h('div', { class: 'result-meta' }, meta),
      check.level !== 'ok' ? h('div', { class: 'note ' + ({ no: 'bad', server: 'grey' }[check.level] || 'warn'), text: ({ no: '⛔ ', server: '⇄ ' }[check.level] || '⚠ ') + check.text }) : null,
      n ? h('div', { class: 'note ' + (n.ok ? 'okc' : 'bad'), text: n.text }) : null
    ]);
  }

  /**
   * Ход загрузки раздачи на AllDebrid: опрос /api/magnets/<id> (первую минуту — раз в 4 с, потом раз в 15 с, до 60 раз).
   * [cb]({ text, done, failed, percent }).
   */
  SM.downloads = {
    watch: function (id, cb) {
      var started = Date.now(), n = 0, stopped = false;
      function next() {
        if (stopped || n++ >= 60) return;
        setTimeout(function () {
          api.get('/api/magnets/' + id).then(function (st) {
            var total = Number(st.size) || 0, done = Number(st.downloaded) || 0, code = Number(st.statusCode) || 0;
            if (code === 4 || st.ready) { cb({ done: true, text: 'Готово' }); return; }
            if (code >= 5) { cb({ failed: true, text: String(st.status || 'ошибка') }); return; }
            var pct = total > 0 ? Math.floor(done * 100 / total) : null;
            cb({ text: (st.status || 'Обработка') + (pct != null ? ' · ' + pct + '%' : ''), percent: pct });
            next();
          }, function () { next(); });
        }, Date.now() - started < 60000 ? 4000 : 15000);
      }
      next();
      return function () { stopped = true; };
    }
  };

  SM.tabs = SM.tabs || {};
  SM.tabs.search = { render: render };
})(window);

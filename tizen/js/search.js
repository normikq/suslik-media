/*
 * Вкладка «Поиск»: раздачи по трекерам (/api/search), добавление в медиатеку (/api/search/add) и ход загрузки
 * (/api/magnets/<id>). Как android/.../library/SearchScreen.kt: результаты и фильтры живут, пока открыты другие вкладки.
 */
(function (g) {
  'use strict';
  var SM = g.SM, api = SM.api, ui = SM.ui, model = SM.model, h = ui.h, button = ui.button;

  var KINDS = [{ id: 'video', title: 'Фильмы и сериалы' }, { id: 'movie', title: 'Фильмы' }, { id: 'cartoon', title: 'Мультфильмы' },
    { id: 'series', title: 'Сериалы' }, { id: 'all', title: 'Всё' }];
  var KIND_RU = { series: 'сериал', movie: 'фильм', cartoon: 'мультфильм' };

  var s = { query: '', busy: false, status: null, ok: true, hits: [], kind: 'video', seeded: false, notes: {} };

  function keyed(el, key) { el.setAttribute('data-key', key); return el; }

  function rerender() { if (SM.app.tabIs('search')) SM.app.refreshTab(); }

  function run(q) {
    q = String(q || '').trim();
    s.query = q;
    if (q.length < 2 || s.busy) { s.status = 'Введите минимум 2 символа.'; s.ok = false; rerender(); return; }
    s.busy = true; s.status = 'Опрашиваем трекеры…'; s.ok = true; s.hits = [];
    rerender();
    api.get('/api/search?q=' + encodeURIComponent(q)).then(function (j) {
      s.hits = (Array.isArray(j.results) ? j.results : []).filter(function (r) { return r && r.id; }).map(function (r) {
        var src = Array.isArray(r.sources) && r.sources.length > 1 ? r.sources.join(' + ') : String(r.provider || '');
        var d = typeof r.date === 'number' && r.date > 0 ? new Date(r.date).toLocaleDateString('ru-RU') : typeof r.date === 'string' ? r.date.substring(0, 10) : '';
        return { id: String(r.id), title: String(r.title || ''), size: Number(r.size) || 0, seeders: Number(r.seeders) || 0,
          leechers: Number(r.leechers) || 0, kind: String(r.kind || ''), source: src, date: d };
      });
      var failed = (Array.isArray(j.providers) ? j.providers : []).filter(function (p) { return p && !p.ok; }).length;
      s.status = !s.hits.length ? 'Ничего не найдено.' :
        'Найдено ' + (j.total || s.hits.length) + ' за ' + ((Number(j.tookMs) || 0) / 1000).toFixed(1).replace('.', ',') + ' с' + (failed ? ' · не ответили: ' + failed : '');
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
      note(hit.id, 'Добавлено, AllDebrid качает…', true);
      SM.downloads.watch(r.id, function (st) {
        if (st.done) { note(hit.id, 'Готово — файлы в медиатеке', true); SM.app.libraryChanged(); }
        else if (st.failed) note(hit.id, 'Ошибка: ' + st.text, false);
        else note(hit.id, st.text, true);
      });
    }).catch(function (e) { note(hit.id, api.describe(e), false); });
  }

  function shown() {
    return s.hits.filter(function (x) {
      return (s.kind === 'all' || (s.kind === 'video' && x.kind !== 'other') || x.kind === s.kind) && (!s.seeded || x.seeders > 0);
    });
  }

  function render(box) {
    var input = ui.field('Название фильма или сериала…', run, s.query);
    input.setAttribute('data-key', 'q');
    box.appendChild(h('div', { class: 'search-head' }, [
      input, keyed(button(s.busy ? 'Ищем…' : 'Найти', function () { run(input.value()); }, 'primary'), 'go')
    ]));
    var chips = KINDS.map(function (k) {
      return button(k.title, function () { s.kind = k.id; SM.app.refreshTab(); }, 'chip' + (s.kind === k.id ? ' on' : ''));
    });
    chips.forEach(function (c, i) { c.setAttribute('data-key', 'k' + i); });
    var seeded = button((s.seeded ? '✓ ' : '') + 'Только с сидами', function () { s.seeded = !s.seeded; SM.app.refreshTab(); }, 'chip' + (s.seeded ? ' on' : ''));
    seeded.setAttribute('data-key', 'seeded');
    box.appendChild(h('div', { class: 'chips' }, chips.concat([seeded])));
    if (s.status) box.appendChild(h('p', { class: s.ok ? 'muted' : 'error', text: s.status }));
    var list = shown();
    if (s.hits.length && list.length < s.hits.length) box.appendChild(h('p', { class: 'muted small', text: 'Скрыто фильтрами: ' + (s.hits.length - list.length) }));
    if (!s.hits.length && !s.status) box.appendChild(h('p', { class: 'muted', text: 'Один запрос — сразу по нескольким трекерам. Нажмите OK на результате, чтобы добавить раздачу в медиатеку.' }));
    box.appendChild(h('div', { class: 'results' }, list.slice(0, 100).map(row)));
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
      h('div', { class: 'result-title', text: x.title }),
      h('div', { class: 'result-meta' }, meta),
      check.level !== 'ok' ? h('div', { class: 'note ' + (check.level === 'no' ? 'bad' : 'warn'), text: (check.level === 'no' ? '⛔ ' : '⚠ ') + check.text }) : null,
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

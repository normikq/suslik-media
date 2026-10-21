/*
 * Вкладка «Добавить»: magnet-ссылка → AllDebrid (/api/magnets), история добавленного с этого ТВ с ходом загрузки и
 * отменой. Ссылки на файлы (https) здесь не принимаются: /api/unlock отдаёт прямую ссылку AllDebrid, а смотреть
 * можно только через зеркало (в РФ AllDebrid заблокирован). Набирать magnet пультом долго — экран предлагает телефон.
 */
(function (g) {
  'use strict';
  var SM = g.SM, api = SM.api, ui = SM.ui, h = ui.h, button = ui.button;
  var KEY = 'add_history';

  var status = null, ok = true, live = {}; // id -> { text, done, failed }

  function history() { try { return JSON.parse(localStorage.getItem(KEY) || '[]'); } catch (e) { return []; } }
  function save(list) { try { localStorage.setItem(KEY, JSON.stringify(list.slice(0, 20))); } catch (e) { /* полно */ } }

  function rerender() { if (SM.app.tabIs('add')) SM.app.refreshTab(); }

  function follow(id) {
    if (live[id]) return;
    live[id] = { text: 'Обработка' };
    SM.downloads.watch(id, function (st) {
      live[id] = { text: st.done ? 'Готово — в медиатеке' : st.failed ? 'Ошибка: ' + st.text : st.text, done: st.done, failed: st.failed };
      if (st.done) SM.app.libraryChanged();
      rerender();
    });
  }

  function submit(text) {
    var magnet = String(text || '').trim();
    if (magnet.indexOf('magnet:?') !== 0) { status = 'Вставьте ссылку, которая начинается с magnet:?'; ok = false; rerender(); return; }
    status = 'Отправляю…'; ok = true; rerender();
    api.post('/api/magnets', { magnet: magnet }, false).then(function (r) {
      var list = history().filter(function (x) { return x.id !== r.id; });
      list.unshift({ id: r.id, name: r.name || 'Magnet', at: Date.now() });
      save(list);
      if (r.ready) { live[r.id] = { text: 'Готово — в медиатеке', done: true }; SM.app.libraryChanged(); }
      else follow(r.id);
      status = r.ready ? 'Готово — в медиатеке' : 'Добавлено, AllDebrid качает…'; ok = true;
    }).catch(function (e) { status = api.describe(e); ok = false; }).then(rerender);
  }

  function cancel(x) {
    ui.dialog('Отменить загрузку?', x.name, [
      { text: 'Отменить загрузку', run: function () {
        api.post('/api/magnets/' + x.id + '/cancel', {}, false).then(function () {
          save(history().filter(function (y) { return y.id !== x.id; }));
          delete live[x.id];
          ui.toast('Отменено');
          SM.app.libraryChanged();
          rerender();
        }).catch(function (e) { ui.toast(api.describe(e)); });
      } },
      { text: 'Назад' }
    ]);
  }

  function render(box) {
    var input = ui.field('magnet:?xt=…', submit);
    input.setAttribute('data-key', 'm');
    var go = button('Добавить', function () { submit(input.value()); }, 'primary');
    go.setAttribute('data-key', 'go');
    box.appendChild(h('div', { class: 'add' }, [
      h('div', { class: 'add-main' }, [
        h('h2', { text: 'Добавить раздачу по magnet-ссылке' }),
        h('p', { class: 'muted', text: 'AllDebrid скачает раздачу, файлы появятся в медиатеке.' }),
        h('div', { class: 'search-head' }, [input, go]),
        status ? h('p', { class: ok ? 'muted' : 'error', text: status }) : null,
        h('h2', { text: 'Добавлено с телевизора' }),
        h('div', { class: 'results' }, historyRows())
      ]),
      h('div', { class: 'add-side' }, [
        ui.qr(api.SITE, 300),
        h('p', { class: 'muted', text: 'Набирать ссылку пультом долго: удобнее добавить с телефона — на сайте или в приложении. Раздача появится и здесь.' })
      ])
    ]));
  }

  function historyRows() {
    var list = history();
    if (!list.length) return [h('p', { class: 'muted', text: 'Пока ничего. Найти раздачу можно во вкладке «Поиск».' })];
    return list.map(function (x) {
      var st = live[x.id];
      if (!st && Date.now() - x.at < 2 * 86400000) follow(x.id);
      st = live[x.id] || { text: 'Добавлено ' + new Date(x.at).toLocaleDateString('ru-RU'), done: true };
      var row = h('div', { class: 'f result', tabindex: '-1', 'data-key': 'h' + x.id, onclick: function () {
        if (st.done) SM.app.shell('home', true); else cancel(x);
      } }, [
        h('div', { class: 'result-title', text: x.name }),
        h('div', { class: 'note ' + (st.failed ? 'bad' : st.done ? 'okc' : 'grey'), text: st.text + (st.done ? ' · OK — к медиатеке' : st.failed ? '' : ' · OK — отменить') })
      ]);
      return row;
    });
  }

  SM.tabs = SM.tabs || {};
  SM.tabs.add = { render: render };
})(window);

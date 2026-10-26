/*
 * Вкладка «Добавить»: magnet-ссылка → сервер (/api/magnets), история добавленного с этого ТВ с ходом загрузки и
 * отменой. Ссылки на файлы (https) здесь не принимаются, а смотреть можно через безопасное зеркало.
 * Набирать magnet пультом долго — экран предлагает телефон.
 */
(function (g) {
  'use strict';
  var SM = g.SM, api = SM.api, ui = SM.ui, h = ui.h, button = ui.button;
  var tr = SM.tr || g.tr || function (k) { return k; };
  var KEY = 'add_history';

  var status = null, ok = true, live = {}; // id -> { text, done, failed }

  function history() { try { return JSON.parse(localStorage.getItem(KEY) || '[]'); } catch (e) { return []; } }
  function save(list) { try { localStorage.setItem(KEY, JSON.stringify(list.slice(0, 20))); } catch (e) { /* полно */ } }

  function rerender() { if (SM.app.tabIs('add')) SM.app.refreshTab(); }

  function follow(id) {
    if (live[id]) return;
    live[id] = { text: tr('status.processing') };
    var stop = SM.downloads.watch(id, function (st) {
      if (!live[id]) return; // загрузку отменили: опрос уже остановлен, поздний ответ не возвращает строку
      live[id] = { text: st.done ? tr('add.ready_in_lib') : st.failed ? tr('common.error') + ': ' + st.text : st.text, done: st.done, failed: st.failed, stop: stop };
      if (st.done) SM.app.libraryChanged();
      rerender();
    });
    live[id].stop = stop;
  }

  function submit(text) {
    var magnet = String(text || '').trim();
    if (magnet.indexOf('magnet:?') !== 0) { status = tr('add.invalid_link'); ok = false; rerender(); return; }
    status = tr('add.sending'); ok = true; rerender();
    api.post('/api/magnets', { magnet: magnet }, false).then(function (r) {
      var list = history().filter(function (x) { return x.id !== r.id; });
      list.unshift({ id: r.id, name: r.name || 'Magnet', at: Date.now() });
      save(list);
      if (r.ready) { live[r.id] = { text: tr('add.ready_in_lib'), done: true }; SM.app.libraryChanged(); }
      else follow(r.id);
      status = r.ready ? tr('add.ready_in_lib') : tr('add.success'); ok = true;
    }).catch(function (e) { status = api.describe(e); ok = false; }).then(rerender);
  }

  function cancel(x) {
    ui.dialog(tr('add.cancel_confirm'), x.name, [
      { text: tr('add.cancel_btn'), run: function () {
        api.post('/api/magnets/' + x.id + '/cancel', {}, false).then(function () {
          save(history().filter(function (y) { return y.id !== x.id; }));
          if (live[x.id] && live[x.id].stop) live[x.id].stop();
          delete live[x.id];
          ui.toast(tr('add.cancelled'));
          SM.app.libraryChanged();
          rerender();
        }).catch(function (e) { ui.toast(api.describe(e)); });
      } },
      { text: tr('common.back') }
    ]);
  }

  function render(box) {
    var input = ui.field(tr('add.placeholder'), submit);
    input.setAttribute('data-key', 'm');
    var go = button(tr('add.submit'), function () { submit(input.value()); }, 'primary');
    go.setAttribute('data-key', 'go');
    box.appendChild(h('div', { class: 'add' }, [
      h('div', { class: 'add-main' }, [
        h('h2', { text: tr('add.title') }),
        h('p', { class: 'muted', text: tr('add.subtitle') }),
        h('div', { class: 'search-head' }, [input, go]),
        status ? h('p', { class: ok ? 'muted' : 'error', text: status }) : null,
        h('h2', { text: tr('add.tv_tag') }),
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
    if (!list.length) return [h('p', { class: 'muted', text: tr('add.history_empty') })];
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

/*
 * Вкладка «Вместе»: создать комнату или войти по коду, фильм комнаты, кто в комнате (как android/.../TogetherScreen.kt).
 * Ведущий выбирает фильм в медиатеке; гость смотрит фильм комнаты — плеер идёт за ней (party.js).
 */
(function (g) {
  'use strict';
  var SM = g.SM, ui = SM.ui, api = SM.api, party = SM.party, h = ui.h, button = ui.button;

  var busy = null, error = null;

  function keyed(el, key) { el.setAttribute('data-key', key); return el; }
  function rerender() { if (SM.app.tabIs('party')) SM.app.refreshTab(); }

  function create() {
    if (busy) return;
    busy = 'create'; error = null; rerender();
    party.create().catch(function (e) { error = api.describe(e); }).then(function () { busy = null; rerender(); });
  }

  function join(code) {
    if (busy) return;
    busy = 'join'; error = null; rerender();
    party.join(code).catch(function (e) { error = api.describe(e); }).then(function () { busy = null; rerender(); });
  }

  function render(box) {
    var off = party.onChange(function () { rerender(); });
    var timer = setInterval(updateTime, 1000);
    SM.app.onLeave(function () { off(); clearInterval(timer); });
    box.appendChild(h('div', { class: 'together' }, party.inRoom() ? roomView() : lobby()));
  }

  function lobby() {
    var code = ui.field('Код комнаты', join);
    keyed(code, 'code');
    return [
      h('h2', { text: 'Совместный просмотр' }),
      h('p', { class: 'muted', text: 'Один фильм, одна пауза и перемотка у всех в комнате — на телевизоре, компьютере и телефоне.' }),
      party.notice ? h('p', { class: 'warn-text', text: party.notice }) : null,
      h('div', { class: 'cards' }, [
        h('div', { class: 'card-row' }, [
          h('h2', { text: 'Новая комната' }),
          h('p', { class: 'muted', text: 'Создайте комнату и отправьте код друзьям. Фильм выберите в медиатеке — он станет фильмом комнаты.' }),
          keyed(button(busy === 'create' ? 'Создаём…' : 'Создать комнату', create, 'primary'), 'create')
        ]),
        h('div', { class: 'card-row' }, [
          h('h2', { text: 'Войти по коду' }),
          h('p', { class: 'muted', text: 'Код комнаты пришлёт тот, кто её создал.' }),
          h('div', { class: 'search-head' }, [code, keyed(button(busy === 'join' ? 'Входим…' : 'Войти', function () { join(code.value()); }), 'join')])
        ])
      ]),
      error ? h('p', { class: 'error', text: error }) : null
    ];
  }

  function stateText() {
    var s = party.room.state;
    if (s.playing && s.waiting) {
      var names = party.room.members.filter(function (m) { return m.buffering; }).map(function (m) { return m.name; });
      return 'Ждём, пока загрузится' + (names.length ? ' у ' + names.join(', ') : '');
    }
    return s.playing ? 'Идёт' : 'На паузе';
  }

  function updateTime() {
    var el = document.getElementById('room-time');
    if (el && party.room.file) el.textContent = stateText() + ' · ' + ui.clock(party.targetMs());
  }

  function roomView() {
    var r = party.room, f = r.file, host = party.isHost();
    var head = h('div', { class: 'room-head' }, [
      h('div', {}, [
        h('div', { class: 'muted', text: 'Код комнаты' }),
        h('div', { class: 'room-code', text: party.roomId })
      ]),
      h('div', { class: 'room-info' }, [
        h('p', { class: party.connected ? 'ok' : 'muted', text: (party.connected ? '● На связи' : '○ Подключаемся…') +
          (r.hostName ? ' · создал ' + r.hostName : '') + (r.hostOnly ? ' · управляет только создатель' : '') }),
        h('p', { class: 'muted', text: 'Пауза и перемотка у создателя — у всех. Отправьте код друзьям: вход — во вкладке «Вместе».' })
      ])
    ]);
    var film;
    if (!f) {
      film = [h('p', { class: 'muted', text: host ? 'Пока не выбран. Откройте фильм в медиатеке — он станет фильмом комнаты.'
        : 'Пока не выбран: фильм включает ' + (r.hostName || 'создатель комнаты') + '.' })];
      if (host) film.push(keyed(button('К медиатеке', function () { SM.app.shell('home', true); }, 'primary'), 'lib'));
    } else {
      var known = SM.app.findKey(f.key) != null;
      film = [
        h('p', { class: 'room-film', text: f.title || f.key.substring(f.key.lastIndexOf('/') + 1) }),
        h('p', { id: 'room-time', class: r.state.playing && r.state.waiting ? 'warn-text' : 'muted', text: stateText() + ' · ' + ui.clock(party.targetMs()) }),
        known ? null : h('p', { class: 'error', text: 'Этой раздачи нет в медиатеке (или она ещё загружается).' }),
        h('div', { class: 'row left' }, [
          keyed(button('▶ Смотреть вместе', SM.app.followRoom, 'primary'), 'watch'),
          host ? keyed(button('Другой фильм', function () { SM.app.shell('home', true); }), 'other') : null
        ])
      ];
    }
    var members = r.members.map(function (m) {
      return h('div', { class: 'member' }, [
        h('span', { text: m.name + (m.key === party.myKey ? ' (вы)' : '') + (party.isCreator(m) ? ' · создатель' : '') }),
        m.buffering ? h('span', { class: 'warn-text', text: ' · загружается' }) : null
      ]);
    });
    return [
      head,
      h('div', { class: 'cards' }, [
        h('div', { class: 'card-row' }, [h('h2', { text: 'Фильм' })].concat(film)),
        h('div', { class: 'card-row' }, [h('h2', { text: 'В комнате · ' + r.members.length })].concat(members))
      ]),
      keyed(button('Выйти из комнаты', function () { party.leave(); }), 'leave')
    ];
  }

  SM.tabs = SM.tabs || {};
  SM.tabs.party = { render: render };
})(window);

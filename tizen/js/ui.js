/*
 * Общие куски экранов: элементы, кнопки, поле ввода для пульта, окно с вопросом, всплывающая подсказка, QR-код.
 * Без фреймворка: экраны перерисовываются целиком. Только ES2017 (Tizen 4.0).
 */
(function (g) {
  'use strict';
  var SM = g.SM, nav = SM.nav;
  var root = document.getElementById('screen');

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
  function dialog(title, text, buttons, cls) {
    var prevBack = nav.onBack, prevRoot = nav.root, prevKey = nav.onKey, prevFocus = document.activeElement;
    var box = h('div', { class: 'dialog' }, [h('div', { class: 'dialog-box' + (cls ? ' ' + cls : '') }, [
      h('h2', { text: title }), text ? h('p', { text: text }) : null,
      h('div', { class: 'row' }, buttons.map(function (b, i) { return button(b.text, function () { close(); if (b.run) b.run(); }, i === 0 ? 'primary' : ''); }))
    ])]);
    function close() {
      if (!box.parentNode) return;
      box.parentNode.removeChild(box);
      nav.root = prevRoot; nav.onBack = prevBack; nav.onKey = prevKey;
      if (prevFocus && document.body.contains(prevFocus)) nav.focus(prevFocus); else nav.focusFirst(prevRoot);
    }
    document.body.appendChild(box);
    nav.root = box; nav.onBack = close; nav.onKey = null;
    nav.focusFirst(box);
    return close;
  }

  /**
   * Поле ввода для пульта: фокус стоит на рамке (.f), OK открывает экранную клавиатуру телевизора, «Готово» на ней
   * (или OK) — [onSubmit](текст), «Отмена» / «Назад» — снова рамка. Иначе клавиатура выскакивала бы при каждом проходе
   * стрелками мимо поля.
   */
  var IME_DONE = 65376, IME_CANCEL = 65385;
  function field(placeholder, onSubmit, value) {
    var input = h('input', { type: 'text', placeholder: placeholder, tabindex: '-1', autocomplete: 'off', spellcheck: 'false' });
    if (value) input.value = value;
    var box = h('div', { class: 'f field', tabindex: '-1' }, [input]);
    box.addEventListener('click', function () { input.focus(); });
    input.addEventListener('keydown', function (e) {
      if (e.keyCode === 13 || e.keyCode === IME_DONE) { e.preventDefault(); e.stopPropagation(); nav.focus(box); onSubmit(input.value); }
      else if (e.keyCode === IME_CANCEL || e.keyCode === 10009 || e.keyCode === 27) { e.preventDefault(); e.stopPropagation(); nav.focus(box); }
      else if (e.keyCode === 38 || e.keyCode === 40) { input.blur(); nav.focus(box); } // дальше стрелку обработает nav
    });
    box.input = input;
    box.value = function () { return input.value; };
    return box;
  }

  /** QR-код ссылки ([vendor/qrcode.js], MIT) на холсте: тёмные модули на белом с полями. */
  function qr(text, px) {
    var q = g.qrcode(0, 'M');
    q.addData(text);
    q.make();
    var n = q.getModuleCount(), quiet = 3, cell = Math.max(2, Math.floor(px / (n + quiet * 2)));
    var size = cell * (n + quiet * 2);
    var c = h('canvas', { class: 'qr', width: String(size), height: String(size) });
    var ctx = c.getContext('2d');
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, size, size);
    ctx.fillStyle = '#000';
    for (var r = 0; r < n; r++) for (var col = 0; col < n; col++) if (q.isDark(r, col)) ctx.fillRect((col + quiet) * cell, (r + quiet) * cell, cell, cell);
    return c;
  }

  function spinner(text) { return h('div', { class: 'center' }, [h('div', { class: 'spinner' }), h('p', { text: text })]); }

  /** Время, как в плеере: 1:05:09 или 5:09. */
  function clock(ms) {
    var s = Math.max(0, Math.floor((Number(ms) || 0) / 1000)), hh = Math.floor(s / 3600), mm = Math.floor(s / 60) % 60, ss = s % 60;
    return (hh ? hh + ':' + ('0' + mm).slice(-2) : mm) + ':' + ('0' + ss).slice(-2);
  }

  SM.ui = { root: root, h: h, button: button, show: show, toast: toast, dialog: dialog, field: field, qr: qr, spinner: spinner, clock: clock };
})(window);

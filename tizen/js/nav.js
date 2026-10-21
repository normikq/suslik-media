/*
 * Навигация пультом: стрелки переводят фокус на ближайший элемент с классом .f в нужную сторону.
 * Экраны сами решают, что делать с OK и «Назад» (SM.nav.onBack / onKey).
 */
(function (g) {
  'use strict';
  var SM = g.SM = g.SM || {};

  var KEY = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 10009, ESC: 27 };

  function visible(el) {
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && !el.disabled;
  }

  function candidates(root) {
    return Array.prototype.filter.call((root || document).querySelectorAll('.f'), visible);
  }

  /** Ближайший элемент в сторону [dir]: сначала по оси движения, с штрафом за смещение вбок. */
  function nearest(from, dir, root) {
    var a = from.getBoundingClientRect();
    var ax = a.left + a.width / 2, ay = a.top + a.height / 2;
    var best = null, bestScore = Infinity;
    candidates(root).forEach(function (el) {
      if (el === from) return;
      var b = el.getBoundingClientRect();
      var bx = b.left + b.width / 2, by = b.top + b.height / 2;
      var main, side;
      if (dir === 'left') { if (b.right > a.left + 1) return; main = a.left - b.right; side = Math.abs(by - ay); }
      else if (dir === 'right') { if (b.left < a.right - 1) return; main = b.left - a.right; side = Math.abs(by - ay); }
      else if (dir === 'up') { if (b.bottom > a.top + 1) return; main = a.top - b.bottom; side = Math.abs(bx - ax); }
      else { if (b.top < a.bottom - 1) return; main = b.top - a.bottom; side = Math.abs(bx - ax); }
      var score = main + side * 2;
      if (score < bestScore) { bestScore = score; best = el; }
    });
    return best;
  }

  function focus(el) {
    if (!el) return;
    el.focus();
    if (el.scrollIntoViewIfNeeded) el.scrollIntoViewIfNeeded(false); else el.scrollIntoView({ block: 'nearest' });
  }

  var nav = {
    KEY: KEY,
    root: null,          // где искать фокус (текущий экран или открытое окно)
    onBack: null,        // «Назад» на пульте
    onKey: null,         // экран забирает клавишу себе: вернуть true
    focus: focus,
    focusFirst: function (root) { focus((root || nav.root || document).querySelector('.f')); }
  };

  document.addEventListener('keydown', function (e) {
    if (nav.onKey && nav.onKey(e) === true) { e.preventDefault(); return; }
    var dir = { 37: 'left', 38: 'up', 39: 'right', 40: 'down' }[e.keyCode];
    if (dir) {
      var cur = document.activeElement;
      // В поле ввода стрелки влево/вправо двигают курсор
      if (cur && cur.tagName === 'INPUT' && (dir === 'left' || dir === 'right')) return;
      e.preventDefault();
      if (!cur || !cur.classList || !cur.classList.contains('f')) { nav.focusFirst(); return; }
      focus(nearest(cur, dir, nav.root));
    } else if (e.keyCode === KEY.ENTER) {
      var a = document.activeElement;
      if (a && a.classList && a.classList.contains('f') && a.tagName !== 'INPUT') { e.preventDefault(); a.click(); }
    } else if (e.keyCode === KEY.BACK || e.keyCode === KEY.ESC) {
      e.preventDefault();
      if (nav.onBack) nav.onBack();
    }
  });

  SM.nav = nav;
})(window);

/*
 * Suslik Media - Модуль локализации для Tizen TV (ES2017)
 */
(function (g) {
  'use strict';
  var strings = {};

  // 1. Попытка загрузить из файла локализации
  try {
    if (typeof XMLHttpRequest !== 'undefined') {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', 'locales/ru.json', false);
      xhr.send(null);
      if (xhr.status === 200 || xhr.status === 0) {
        strings = JSON.parse(xhr.responseText);
      }
    } else if (typeof require === 'function') {
      var path = require('path');
      var fs = require('fs');
      var p = path.join(__dirname, '../locales/ru.json');
      if (fs.existsSync(p)) {
        strings = JSON.parse(fs.readFileSync(p, 'utf8'));
      }
    }
  } catch (e) {
    // В случае ошибки чтения файла используется fallback
  }

  function tr(key, params) {
    var s = (strings && strings[key] != null) ? strings[key] : key;
    if (params && typeof params === 'object') {
      for (var p in params) {
        if (Object.prototype.hasOwnProperty.call(params, p)) {
          s = s.replace(new RegExp('\\{' + p + '\\}', 'g'), params[p]);
        }
      }
    }
    return s;
  }

  var SM = g.SM = g.SM || {};
  SM.tr = tr;
  SM.STRINGS = strings;
  g.tr = tr;

  if (typeof document !== 'undefined') {
    var applyI18n = function () {
      var list = document.querySelectorAll('[data-i18n]');
      for (var i = 0; i < list.length; i++) {
        var k = list[i].getAttribute('data-i18n');
        if (k && strings[k] != null) list[i].textContent = strings[k];
      }
    };
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', applyI18n);
    } else {
      applyI18n();
    }
    SM.applyI18n = applyI18n;
  }

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { tr: tr, STRINGS: strings };
  }
})(typeof window !== 'undefined' ? window : (typeof global !== 'undefined' ? global : this));

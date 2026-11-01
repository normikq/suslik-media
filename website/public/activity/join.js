// «Открыть в приложении» из Discord Activity: suslik://room/<код> (ПК — реестр, Android — intent-filter).
// Android Chrome надёжнее открывает intent:// с именем пакета.
(function () {
  'use strict';
  var box = document.getElementById('join'), a = document.getElementById('open');
  var code = String(box.getAttribute('data-code') || '').replace(/[^0-9a-z]/g, '');
  if (!code) return;
  var href = 'suslik://room/' + code;
  if (/Android/i.test(navigator.userAgent)) href = 'intent://room/' + code + '#Intent;scheme=suslik;package=ru.suslikovanora.media;end';
  a.setAttribute('href', href);
  // Сразу пробуем открыть (браузер спросит «Открыть приложение?»); не вышло — остаётся кнопка
  setTimeout(function () { location.href = href; }, 300);
})();

/*
 * JSON API сервера (как android/.../net/Api.kt). Вход — только токен устройства (X-Device-Token): cookie на ТВ не
 * нужны. Домен сайта приходит из сборки (js/site.js, секрет SITE_URL) — в репозитории его нет.
 * XHR, а не fetch: в Chromium 56 у fetch нет AbortController, а зависший запрос надо уметь бросить.
 */
(function (g) {
  'use strict';
  var SM = g.SM = g.SM || {};
  var HOST = SM.SITE_HOST || '';
  var BASE = 'https://' + HOST;
  var TOKEN_KEY = 'device_token';

  function HttpError(code, message) { this.code = code; this.message = message; }
  HttpError.prototype = Object.create(Error.prototype);

  function token() { try { return localStorage.getItem(TOKEN_KEY) || null; } catch (e) { return null; } }
  function setToken(v) { try { if (v) localStorage.setItem(TOKEN_KEY, v); else localStorage.removeItem(TOKEN_KEY); } catch (e) { /* нет хранилища */ } }

  /** Одна попытка. [limitMs] — сколько ждать ответа, потом запрос бросается (null — без лимита). */
  function attempt(method, path, body, limitMs, responseType) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open(method, BASE + path, true);
      if (limitMs) xhr.timeout = limitMs;
      if (responseType) xhr.responseType = responseType;
      var t = token();
      if (t) xhr.setRequestHeader('X-Device-Token', t);
      if (body != null) xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.onload = function () {
        if (responseType === 'blob') {
          if (xhr.status >= 200 && xhr.status < 300) resolve(xhr.response);
          else reject(new HttpError(xhr.status, 'Ошибка сервера (' + xhr.status + ')'));
          return;
        }
        var json = {};
        try { json = JSON.parse(xhr.responseText || '{}'); } catch (e) { json = {}; }
        if (xhr.status >= 200 && xhr.status < 300) resolve(json);
        else reject(new HttpError(xhr.status, json.error || 'Ошибка сервера (' + xhr.status + ')'));
      };
      xhr.ontimeout = function () { reject({ timeout: true, message: 'Сервер не ответил вовремя' }); };
      xhr.onerror = function () { reject({ network: true, message: 'Нет связи с сервером' }); };
      xhr.send(body != null ? JSON.stringify(body) : null);
    });
  }

  /** Ответа нет за 6/8/10 с — повтор (как Api.call на Android). [repeatable] = false — запрос нельзя слать дважды. */
  function call(method, path, body, repeatable) {
    if (repeatable === false) return attempt(method, path, body, 30000);
    var limits = [6000, 8000, 10000], n = 0;
    function next() {
      return attempt(method, path, body, limits[n]).catch(function (e) {
        if (e && e.timeout && ++n < limits.length) return next();
        throw e;
      });
    }
    return next();
  }

  function enc(s) { return encodeURIComponent(s); }

  SM.api = {
    HOST: HOST, BASE: BASE, SITE: BASE + '/media/', HttpError: HttpError,
    token: token, setToken: setToken,
    get: function (path) { return call('GET', path, null, true); },
    post: function (path, body, repeatable) { return call('POST', path, body || {}, repeatable); },
    /** Картинка с нашего сервера (постеры требуют входа): blob → objectURL, <img> сам заголовок не пошлёт. */
    blobUrl: function (path) {
      return attempt('GET', path, null, 15000, 'blob').then(function (b) { return URL.createObjectURL(b); });
    },
    /** Подписанная ссылка зеркала (живёт 12 ч). Прямые ссылки AllDebrid не используем: в РФ он заблокирован. */
    mirrorUrl: function (file) {
      var name = file.name.trim() ? file.name : SM.model.basename(file.path);
      return call('GET', '/api/file/mirror-url?link=' + enc(file.link) + '&name=' + enc(name), null, true)
        .then(function (r) { if (!r.url) throw { message: 'Сервер не дал ссылку на файл' }; return r.url; });
    },
    describe: function (e) { return (e && e.message) || 'Неизвестная ошибка'; }
  };
})(window);

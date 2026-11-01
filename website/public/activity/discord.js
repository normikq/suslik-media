/*
 * Связь с клиентом Discord изнутри Activity (iframe): то же, что делает @discord/embedded-app-sdk (2.x), но без сборщика —
 * рукопожатие, ожидание READY и команды postMessage-ом. Нужны только AUTHORIZE и OPEN_EXTERNAL_LINK.
 * Протокол: [opcode, payload]; 0 — рукопожатие, 1 — кадр (команда/ответ/событие), 2 — закрытие, 3 — HELLO (старый).
 */
(function (g) {
  'use strict';
  var HANDSHAKE = 0, FRAME = 1, CLOSE = 2;
  var ORIGINS = [g.location.origin, 'https://discord.com', 'https://discordapp.com', 'https://ptb.discord.com',
    'https://ptb.discordapp.com', 'https://canary.discord.com', 'https://canary.discordapp.com', 'null'];

  var params = new URLSearchParams(g.location.search);
  var info = {
    frameId: params.get('frame_id'),
    instanceId: params.get('instance_id'),
    channelId: params.get('channel_id'),
    guildId: params.get('guild_id'),
    platform: params.get('platform') === 'mobile' ? 'mobile' : 'desktop',
    mobileVersion: params.get('mobile_app_version') || ''
  };
  var inDiscord = !!(info.frameId && info.instanceId && g.parent && g.parent !== g);

  var source = null, target = '*', pending = {}, ready = null, readyResolve = null, onClose = null;

  function nonce() {
    if (g.crypto && g.crypto.randomUUID) return g.crypto.randomUUID();
    return Date.now().toString(36) + Math.random().toString(36).slice(2);
  }

  function onMessage(ev) {
    if (ORIGINS.indexOf(ev.origin) < 0) return;
    var t = ev.data;
    if (!Array.isArray(t)) return;
    var op = t[0], p = t[1] || {};
    if (op === CLOSE) { if (onClose) onClose(p); return; }
    if (op !== FRAME) return;
    if (p.cmd === 'DISPATCH') {
      if (p.evt === 'READY' && readyResolve) { readyResolve(p.data || {}); readyResolve = null; }
      return;
    }
    var w = p.nonce && pending[p.nonce];
    if (!w) return;
    delete pending[p.nonce];
    if (p.evt === 'ERROR') w.reject(p.data || { message: 'Discord: ошибка' });
    else w.resolve(p.data || {});
  }

  /** Рукопожатие; промис — когда клиент Discord прислал READY. */
  function start(clientId) {
    if (ready) return ready;
    if (!inDiscord) return (ready = Promise.reject(new Error('not_in_discord')));
    source = g.parent.opener || g.parent;
    target = document.referrer ? document.referrer : '*';
    g.addEventListener('message', onMessage);
    ready = new Promise(function (resolve, reject) {
      readyResolve = resolve;
      setTimeout(function () { if (readyResolve) { readyResolve = null; reject(new Error('Discord не ответил.')); } }, 20000);
    });
    var hs = { v: 1, encoding: 'json', client_id: clientId, frame_id: info.frameId };
    var mobileMajor = parseInt(info.mobileVersion.split('.')[0], 10);
    if (info.platform === 'desktop' || mobileMajor >= 250) hs.sdk_version = '2.4.0';
    source.postMessage([HANDSHAKE, hs], target);
    return ready;
  }

  function command(cmd, args) {
    if (!source) return Promise.reject(new Error('Discord ещё не готов.'));
    var n = nonce();
    return new Promise(function (resolve, reject) {
      pending[n] = { resolve: resolve, reject: reject };
      source.postMessage([FRAME, { cmd: cmd, args: args || {}, nonce: n }], target);
    });
  }

  g.SMDiscord = {
    info: info,
    inDiscord: inDiscord,
    start: start,
    /** Одноразовый код OAuth2 (окно согласия Discord покажет сам, если нужно). */
    authorize: function (clientId, scopes) {
      return command('AUTHORIZE', { client_id: clientId, response_type: 'code', state: '', prompt: 'none', scope: scopes });
    },
    /** Ссылка во внешнем браузере (Discord спросит «Покинуть Discord?»). */
    openExternal: function (url) { return command('OPEN_EXTERNAL_LINK', { url: url }); },
    onClose: function (f) { onClose = f; }
  };
})(window);

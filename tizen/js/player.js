/*
 * Плеер на AVPlay (webapis.avplay — аппаратный плеер телевизора). Читает подписанную ссылку зеркала напрямую:
 * локального BurstProxy на ТВ нет (веб-приложение не может слушать порт), запас держит сам AVPlay.
 *
 * Что телевизор не умеет, плеер говорит словами (support.js): DTS/TrueHD в меню звука отключены и при открытии
 * заменяются другой дорожкой; если звука, который ТВ играет, нет — окно «Звук этого файла телевизор не воспроизводит».
 * Жёлтая кнопка или Info — «проба»: кодеки, дорожки и события AVPlay (этап 0 плана: что играет этот телевизор).
 *
 * Звук, который ТВ не играет, и Blu-ray (.m2ts): файл идёт через сервер (/api/file/tv, ffmpeg) — видео как есть,
 * звук в AC3. Такой поток перематывается переоткрытием с нужного места (&t=), время фильма = место открытия + время
 * AVPlay; дорожка звука — одна, выбор другой — тоже переоткрытие. Не вышло — файл открывается как раньше, по зеркалу.
 *
 * Озвучки отдельным файлом (из раздачи и пользователей, /api/custom-audio): AVPlay второй файл звука не подключает,
 * поэтому их тоже склеивает сервер — тот же поток, звук из файла озвучки со сдвигом дорожки. Выбор помнится для
 * раздачи (следующая серия откроется с той же озвучкой, если она у серии есть).
 *
 * Пульт: OK / ▶❚❚ — пауза, ←/→ — 10 с, ⏪/⏩ — минута, ⏮/⏭ — соседняя серия, ↓ — звук и субтитры, ↑ — панель,
 * «Назад» / ■ — выход.
 */
(function (g) {
  'use strict';
  var SM = g.SM, api = SM.api, support = SM.support, model = SM.model, nav = SM.nav;
  var K = { ENTER: 13, LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, BACK: 10009, ESC: 27,
    PLAY_PAUSE: 10252, PLAY: 415, PAUSE: 19, STOP: 413, REW: 412, FF: 417, YELLOW: 405, INFO: 457,
    PREV: 10232, NEXT: 10233 };
  // Подпись ссылки зеркала живёт 12 ч: после 11 ч паузы или свёрнутого приложения ссылку берём новую
  var URL_TTL_MS = 11 * 3600 * 1000;

  var log = [];
  // Журнал прошлого просмотра: если телевизор завис и приложение перезапустили, в пробе видно, на чём встало
  var LOG_KEY = 'tv_player_log', prevLog = [], logSaveTimer = 0;
  function note(text) {
    var d = new Date();
    log.push(('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2) + ':' + ('0' + d.getSeconds()).slice(-2) + ' ' + text);
    if (log.length > 300) log.shift();
    if (!logSaveTimer) logSaveTimer = setTimeout(saveLog, 2000);
    if (cur && cur.diagOpen) renderDiag();
  }
  function saveLog() {
    logSaveTimer = 0;
    try { localStorage.setItem(LOG_KEY, JSON.stringify(log.slice(-150))); } catch (e) { /* нет хранилища */ }
  }
  function loadLog() {
    try { var v = JSON.parse(localStorage.getItem(LOG_KEY) || '[]'); return Array.isArray(v) ? v : []; } catch (e) { return []; }
  }

  // Вызовы AVPlay синхронные и держат страницу: каждый дольше 300 мс — в пробу с именем (журнал 21.10: 4K — UI stall 20 с)
  var avWrap = null;
  function av() {
    var p = g.webapis && g.webapis.avplay;
    if (!p) return null;
    if (avWrap) return avWrap;
    avWrap = {};
    Object.keys(p).concat(['open', 'close', 'stop', 'play', 'pause', 'prepareAsync', 'seekTo', 'jumpForward', 'jumpBackward',
      'getDuration', 'getState', 'getTotalTrackInfo', 'getCurrentStreamInfo', 'setSelectTrack', 'setStreamingProperty',
      'setDisplayRect', 'setDisplayMethod', 'setBufferingParam', 'setListener', 'setSilentSubtitle', 'suspend', 'restore'])
      .forEach(function (k) {
        if (avWrap[k] || typeof p[k] !== 'function') return;
        avWrap[k] = function () {
          var t0 = Date.now();
          try { return p[k].apply(p, arguments); } finally {
            var ms = Date.now() - t0;
            if (ms > 300) note('slow AVPlay.' + k + ': ' + ms + ' ms');
          }
        };
      });
    return avWrap;
  }
  function bigBuffer() { try { return localStorage.getItem('tv_buffer') === 'big'; } catch (e) { return false; } }

  // Сторож: таймер 0,5 с не срабатывал дольше 1,5 с — приложение висело (AVPlay держит поток страницы). В пробу.
  var lastBeat = Date.now();
  setInterval(function () {
    var now = Date.now(), gap = now - lastBeat;
    lastBeat = now;
    if (cur && gap > 1500) note('UI stall ' + gap + ' ms' + (cur.seekBusy ? ' (seek)' : cur.loading ? ' (loading)' : '') + (cur.diagOpen ? ' (probe open)' : ''));
  }, 500);
  function el(id) { return document.getElementById(id); }
  var h = function () { return SM.ui.h.apply(null, arguments); };
  var fmt = function (ms) { return SM.ui.clock(ms); };

  var cur = null; // открытый фильм

  function play(opts) {
    if (!av()) { opts.toast('Плеер AVPlay доступен только на телевизоре'); return; }
    close(true);
    var key = model.watchKey(opts.magnet.id, opts.file);
    var saved = opts.watch.get(key);
    cur = {
      opts: opts, key: key, url: null, pos: 0, dur: 0, paused: false, seekTarget: null, seekTimer: 0, hideTimer: 0,
      saveTimer: 0, retries: 0, menuOpen: false, diagOpen: false, audio: null, textTracks: [], subsOn: false,
      resume: saved && !saved.watched && saved.pos > 30000 ? saved.pos : 0, ended: false,
      prepared: false, loading: true, seekStamp: 0, seeking: false, slow: null, party: null,
      urlAt: 0, played: false, retryAt: 0, stalls: 0, stallMs: 0, stallFrom: 0, durAskedAt: 0,
      tc: null, viewer: Math.random().toString(36).slice(2, 10),
      dubs: model.externalAudioFor(opts.magnet, opts.file), custom: null
    };
    if (opts.resumeAt != null) cur.resume = opts.resumeAt;
    clearTimeout(logSaveTimer); logSaveTimer = 0;
    prevLog = log.length ? [] : loadLog(); // журнал в памяти пуст — приложение запущено заново (может, после зависания)
    log = [];
    note('open ' + opts.file.path + ' (' + model.formatBytes(opts.file.size) + ')');
    document.body.classList.add('playing');
    el('player').className = 'show';
    el('p-title').textContent = opts.title;
    el('subs').textContent = '';
    nav.root = el('player');
    nav.onKey = onKey;
    nav.onBack = function () { close(); };
    buffering(true, 0);
    attachParty(cur);
    showBar();
    var c = cur;
    var mirror = function () {
      api.mirrorUrl(opts.file).then(function (url) { if (cur === c) start(url, c.resume); })
        .catch(function (e) { if (cur === c) fail('network', api.describe(e)); });
    };
    var plain = function () {
      if (support.serverFirst(opts.file.path)) viaServer(c, c.resume, {}).then(function (ok) { if (!ok && cur === c) mirror(); });
      else mirror();
    };
    // Для раздачи выбрана озвучка отдельным файлом: ждём список озвучек пользователей (до 3 с) и открываем с ней
    var pref = dubPref(opts.magnet.id);
    var custom = loadCustom(c);
    if (!pref) { plain(); return; }
    var waited = false;
    var go = function () {
      if (waited || cur !== c) return;
      waited = true;
      var choice = model.externalChoices(opts.file, c.dubs, c.custom).filter(function (ch) { return ch.label === pref; })[0];
      if (!choice) { note('dub "' + pref + '": not for this file'); plain(); return; }
      note('dub "' + pref + '" remembered for this release');
      viaServer(c, c.resume, { force: true, ext: choice }).then(function (ok) {
        if (ok || cur !== c) return;
        c.opts.toast(c.noDubs ? 'Сервер ещё не умеет озвучки отдельным файлом — открываю без неё'
          : 'Озвучку «' + choice.label + '» сервер не подготовил — открываю без неё');
        plain();
      });
    };
    custom.then(go);
    setTimeout(go, 3000);
  }

  // ---- озвучки отдельным файлом ----

  // Выбранная озвучка по раздаче: { magnetId: название в меню }
  var DUB_KEY = 'tv_dub';
  function dubPrefs() { try { var v = JSON.parse(localStorage.getItem(DUB_KEY) || '{}'); return v && typeof v === 'object' ? v : {}; } catch (e) { return {}; } }
  function dubPref(magnetId) { return dubPrefs()[magnetId] || null; }
  function setDubPref(magnetId, label) {
    var all = dubPrefs();
    delete all[magnetId];
    if (label) all[magnetId] = label;
    var keys = Object.keys(all);
    if (keys.length > 50) delete all[keys[0]];
    try { localStorage.setItem(DUB_KEY, JSON.stringify(all)); } catch (e) { /* нет хранилища */ }
  }

  // Озвучки пользователей раздачи: один запрос на 10 минут (серии одной раздачи — один список)
  var customCache = null;
  function loadCustom(c) {
    var id = c.opts.magnet.id;
    if (customCache && customCache.id === id && Date.now() - customCache.at < 600000) {
      return customCache.p.then(function (tracks) { c.custom = tracks; });
    }
    var entry = { id: id, at: Date.now() };
    entry.p = api.customAudio(id).then(function (r) { return Array.isArray(r.tracks) ? r.tracks : []; }, function (e) {
      note('custom audio: ' + api.describe(e));
      if (customCache === entry) customCache = null; // ошибка — в следующий раз спросим снова
      return [];
    });
    customCache = entry;
    return entry.p.then(function (tracks) {
      c.custom = tracks;
      if (tracks.length) note('custom audio: ' + tracks.length + ' for the release');
    });
  }

  /** Озвучки отдельным файлом этого видео: то, что знает ТВ (в потоке с сервера — то, что прислал сервер). */
  function externalChoices(c) {
    if (!c.tc) return model.externalChoices(c.opts.file, c.dubs, c.custom);
    var byLink = {};
    c.dubs.forEach(function (f) { byLink[f.link] = model.dubLabel(c.opts.file, f); });
    return (c.tc.info.external || []).map(function (x) {
      return x.kind === 'file'
        ? { key: 'f:' + x.link, kind: 'file', link: x.link, label: byLink[x.link] || model.basename(x.link) }
        : { key: 'c:' + x.id, kind: 'custom', id: x.id, label: model.customName(x) };
    });
  }

  /** Выбор озвучки в меню: поток с сервера с этой озвучкой с текущего места. */
  function selectExternal(c, ch) {
    if (!c.prepared) { c.opts.toast('Подождите: файл ещё открывается'); return; }
    setDubPref(c.opts.magnet.id, ch.label);
    if (c.tc && model.externalIn(c.tc.info, ch)) {
      c.tc.ext = ch; c.tc.a = null;
      note('server audio → dub "' + ch.label + '"');
      c.prepared = false;
      start(serverUrl(c, c.pos), 0);
      return;
    }
    viaServer(c, c.pos, { force: true, ext: ch, user: true }).then(function (ok) {
      if (ok || cur !== c) return;
      if (c.prepared) buffering(false); // фильм так и идёт по-старому
      c.opts.toast(c.noDubs ? 'Сервер ещё не умеет озвучки отдельным файлом: его нужно обновить'
        : 'Сервер не смог подготовить озвучку «' + ch.label + '»');
    });
  }

  // ---- поток через сервер (звук → AC3) ----

  /**
   * Открыть файл через сервер с места [atMs]. [o]: { force — даже если сервер считает, что ТВ справится сам;
   * order — какая по счёту звуковая дорожка (как в AVPlay); ext — озвучка отдельным файлом (externalChoices);
   * user — выбрал человек в меню }. Обещание: true — поток открывается, false — нет.
   */
  function viaServer(c, atMs, o) {
    // Уже не вышло: не ходим по кругу «сервер → зеркало → сервер» (озвучку, выбранную в меню, всё же пробуем)
    if (c.serverFailed && !o.user) return Promise.resolve(false);
    note('server stream: asking' + (o.ext ? ' (dub "' + o.ext.label + '")' : ''));
    buffering(true, 0, 'Сервер читает файл…');
    return api.tvInfo(c.opts.file, tvExt(c)).then(function (r) {
      if (cur !== c) return false;
      var audio = Array.isArray(r.audio) ? r.audio : [];
      // Видео, которого у этого ТВ нет (AV1): ни зеркало, ни сервер не помогут — говорим сразу, а не чёрным экраном
      if (r.videoUnsupported) {
        note('video ' + r.videoUnsupported + ': not supported by this TV');
        fail('unsupported', 'видео ' + String(r.videoUnsupported).toUpperCase());
        return true;
      }
      var ext = o.ext ? model.externalIn(r, o.ext) : null;
      if (o.ext && !ext) {
        // Сервер без поддержки озвучек (external нет вовсе) или не нашёл эту: в пробу — что именно он прислал
        if (!Array.isArray(r.external)) note('server stream: the server does not support dubs (old server version)');
        else note('server stream: no dub "' + o.ext.label + '" on the server; server has: ' +
          (r.external.map(function (x) { return x.kind === 'file' ? 'file ' + model.basename(x.link) : 'custom ' + x.id + ' ' + x.title; }).join(', ') || 'none') +
          '; asked ' + (o.ext.kind === 'file' ? 'file ' + model.basename(o.ext.link) : 'custom ' + o.ext.id));
        c.noDubs = !Array.isArray(r.external);
        return false;
      }
      if (!ext && (!audio.length || (!r.stream && !o.force))) { note('server stream: not needed'); return false; }
      var pick = o.order != null && audio[o.order] ? audio[o.order].index : r.audioDefault;
      if (pick == null && audio.length) pick = audio[0].index;
      // Выбор в меню: фильм шёл, пока сервер отвечал, — с того места, где он сейчас
      var at = o.user && c.prepared ? c.pos : atMs;
      c.tc = { info: r, a: ext ? null : pick, ext: ext ? o.ext : null, base: 0, playedOk: false };
      if (r.durationMs > 0) c.dur = r.durationMs;
      var v = r.video || {};
      note('server stream: video ' + [v.codec, v.profile, v.width + 'x' + v.height, v.pixFmt, v.transfer, v.dv ? 'DV P' + v.dv : ''].filter(Boolean).join(' '));
      note('server stream: ' + (ext ? 'dub "' + o.ext.label + '" offset ' + (ext.offsetMs || 0) + ' ms' : 'audio #' + pick) +
        ' (' + audio.map(function (t) { return t.index + ':' + t.codec + (t.ok ? '' : '→ac3'); }).join(', ') +
        ((r.external || []).length ? ', dubs: ' + r.external.length : '') + ')');
      start(serverUrl(c, at), 0);
      return true;
    }, function (e) {
      note('server stream unavailable: ' + api.describe(e) + (e && e.code ? ' (' + e.code + ')' : ''));
      return false;
    });
  }

  /** Озвучки для tv-info: файлы из раздачи и раздача (озвучки пользователей сервер найдёт по пути видео). */
  function tvExt(c) { return { magnetId: c.opts.magnet.id, links: c.dubs.map(function (f) { return f.link; }) }; }

  /** Адрес потока с места [ms]: время, которое пришлёт AVPlay, — от этого места. */
  function serverUrl(c, ms) {
    var info = c.tc.info;
    var t = (c.tc.ext && model.externalIn(info, c.tc.ext)) ||
      info.audio.filter(function (x) { return x.index === c.tc.a; })[0] || info.audio[0];
    c.tc.base = Math.max(0, Math.round(ms || 0));
    c.pos = c.tc.base; // пока AVPlay не прислал время — место открытия (обрыв сразу после перемотки повторит его же)
    return t.url + '&t=' + (c.tc.base / 1000).toFixed(1) + '&s=' + c.viewer;
  }

  /** Переоткрыть с места [at]: новая ссылка зеркала или (поток через сервер) новый поток. */
  function reopenAt(c, at) {
    buffering(true, 0);
    var done = function (url) { if (cur === c) start(url, c.tc ? 0 : at); };
    var bad = function (e) { if (cur === c) fail('network', api.describe(e)); };
    if (c.tc) {
      api.tvInfo(c.opts.file, tvExt(c)).then(function (r) {
        if (cur !== c) return;
        // Свежие подписи (если в ответе есть то, что сейчас играет)
        if (Array.isArray(r.audio) && (c.tc.ext ? model.externalIn(r, c.tc.ext) : r.audio.length)) c.tc.info = r;
        done(serverUrl(c, at));
      }, bad);
    } else api.mirrorUrl(c.opts.file).then(done, bad);
  }

  function start(url, fromMs) {
    var p = av(), c = cur;
    c.url = url;
    c.urlAt = Date.now();
    // Переоткрытие (обрыв сети, новая ссылка): пока AVPlay снова не готов, плеер «грузится» — перемотка и пауза ждут
    c.prepared = false; c.loading = true; c.slow = null; c.seekBusy = false; c.seekNext = null;
    c.resumeSeek = null; c.resumeCover = false;
    clearInterval(c.saveTimer);
    buffering(true, 0, c.tc ? 'Сервер готовит поток…' : 'Открываю файл…');
    try {
      // close() из PLAYING/PAUSED без stop() на части прошивок Tizen 4.0 оставляет плеер в неясном состоянии
      try { p.stop(); } catch (e) { /* не играл */ }
      try { p.close(); } catch (e) { /* не был открыт */ }
      p.open(url);
      p.setDisplayRect(0, 0, 1920, 1080);
      p.setDisplayMethod('PLAYER_DISPLAY_MODE_LETTER_BOX');
      // «Большой запас» (Настройки): AVPlay ждёт 8 с видео перед стартом и 15 с после остановки. В 0.2.1 был всегда,
      // и телевизор на время загрузки мог зависнуть намертво (21.10) — теперь по умолчанию запас самого AVPlay
      if (bigBuffer()) {
        try {
          p.setBufferingParam('PLAYER_BUFFER_FOR_PLAY', 'PLAYER_BUFFER_SIZE_IN_SECOND', 8);
          p.setBufferingParam('PLAYER_BUFFER_FOR_RESUME', 'PLAYER_BUFFER_SIZE_IN_SECOND', 15);
          note('buffer: big (8 s / 15 s)');
        } catch (e) { note('setBufferingParam failed ' + (e.name || e)); }
      } else note('buffer: AVPlay default');
      // 4K: без этого режима часть телевизоров играет UHD-поток с пропусками кадров (настройка — до prepare)
      // Поток с сервера (MPEG-TS) телевизор видит как эфир: размер кадра — из ffprobe сервера, а не из имени файла
      var tcVideo = c.tc && c.tc.info.video;
      if (tcVideo ? tcVideo.width > 1920 || tcVideo.height > 1080 : /2160p|\b4k\b|\buhd\b/i.test(c.opts.file.path)) {
        try { p.setStreamingProperty('SET_MODE_4K', 'TRUE'); note('mode 4K'); } catch (e) { note('SET_MODE_4K failed ' + (e.name || e)); }
      }
      // Старый плеер после close() ещё может прислать событие: оно не должно трогать новый фильм
      p.setListener({
        onbufferingstart: function () {
          if (cur !== c) return;
          note('buffering start');
          c.loading = true; buffering(true, 0, 'Буферизация…');
          if (c.prepared && !c.seekBusy) { c.stalls++; c.stallFrom = Date.now(); }
        },
        onbufferingprogress: function (pct) { if (cur === c) buffering(true, pct); },
        onbufferingcomplete: function () {
          if (cur !== c) return;
          note('buffering complete');
          c.loading = false;
          if (c.resumeCover) buffering(true, 0); else buffering(false); // переход к месту продолжения ещё впереди
          if (c.stallFrom) { c.stallMs += Date.now() - c.stallFrom; c.stallFrom = 0; }
        },
        oncurrentplaytime: function (ms) {
          if (cur !== c) return;
          // Продолжение по зеркалу: фильм пошёл с начала — теперь переход к месту (время до него не наше)
          if (c.resumeSeek) { var rs = c.resumeSeek; c.resumeSeek = null; rs(); return; }
          if (c.resumeCover) return;
          c.pos = (c.tc ? c.tc.base : 0) + ms;
          if (!c.dur) duration(c);
          watchSpeed(c, ms);
          // Панель скрыта — время не перерисовываем (лишняя вёрстка 4 раза в секунду); showBar() обновит его сам
          if (c.seekTarget == null && el('p-bar').className === 'show') renderTime();
        },
        onstreamcompleted: function () { if (cur !== c) return; note('stream completed'); ended(); },
        onerror: function (type) { if (cur !== c) return; note('error ' + type); onError(type); },
        onevent: function (type, data) { note('event ' + type + ' ' + (data || '')); },
        onsubtitlechange: function (duration, text) { if (cur === c && c.subsOn) showSubtitle(text, duration); }
      });
    } catch (e) {
      note('open failed: ' + (e.name || '') + ' ' + (e.message || e));
      fail(support.errorKind(e.name) === 'other' ? 'unsupported' : support.errorKind(e.name), e.name);
      return;
    }
    // prepareAsync молчит (сервер или файл-хост не ответил): без сторожа «Загрузка…» висела бы вечно
    var prepGuard = setTimeout(function () {
      if (cur !== c || c.url !== url) return;
      note('prepare: no answer in ' + (c.tc ? 60 : 40) + ' s');
      onError('PREPARE_TIMEOUT');
    }, c.tc ? 60000 : 40000);
    var prepareOk = function () {
      clearTimeout(prepGuard);
      if (cur !== c || c.url !== url) return; // сторож уже переоткрыл: поздний ответ старого открытия не нужен
      if (!c.tc) { try { c.dur = p.getDuration(); } catch (e) { c.dur = 0; } } // у потока с сервера длительность — из tv-info
      note('prepared, duration ' + fmt(c.dur));
      inspectTracks();
      var started = false, resumeGuard = 0;
      var go = function () {
        if (cur !== c || started) return;
        started = true;
        clearTimeout(resumeGuard);
        try { p.play(); } catch (e) { note('play failed ' + (e.name || e)); }
        c.paused = false;
        c.prepared = true; c.loading = false; c.played = true;
        if (c.tc) { c.tc.playedOk = true; c.seekTarget = null; renderTime(); }
        c.resumeSeek = null; c.resumeCover = false;
        buffering(false);
        showBar();
        clearInterval(c.saveTimer);
        c.saveTimer = setInterval(save, 10000);
        // Перемотка (комната) пришла, пока поток с сервера открывался: теперь её можно сделать
        if (c.tcSeekNext != null) { var n = c.tcSeekNext; c.tcSeekNext = null; seekTo(c, n); }
      };
      if (fromMs > 0) {
        // Ответа на перемотку нет 15 с — запускаем с того места, где AVPlay стоит (иначе вечная «Загрузка…»).
        // Отсчёт — после возврата seekTo: на 4K по зеркалу сам вызов держит страницу ~19 с (журнал 21.10), и сторож,
        // заведённый до него, срабатывал сразу после — play() посреди перемотки, фильм стоял на 0 %
        var seek = function () {
          if (cur !== c || started) return;
          note('resume seek to ' + fmt(fromMs));
          var t0 = Date.now();
          try { p.seekTo(fromMs, function () { note('resume seek done (' + (Date.now() - t0) + ' ms)'); go(); }, function () { note('resume seek failed'); go(); }); }
          catch (e) { note('resume seek failed: ' + (e.name || e)); go(); }
          if (!started) resumeGuard = setTimeout(function () { note('resume seek: no answer'); go(); }, 15000);
        };
        buffering(true, 0, 'Переход к ' + fmt(fromMs) + '…');
        c.pos = fromMs;
        // Tizen 4.0: seekTo до первого кадра подвешивает плеер (журнал 21.10: 19 с на 4K) — сначала запуск с начала
        // под чёрной заставкой, после первого кадра — переход. Поток с сервера сюда не попадает: его место — &t=
        c.resumeCover = true;
        buffering(true, 0);
        note('resume at ' + fmt(fromMs) + ': play first, seek after the first frame');
        try { p.play(); } catch (e) { note('play before resume seek failed ' + (e.name || e)); }
        c.resumeSeek = seek;
        // Первого кадра нет 10 с — переходим всё равно
        resumeGuard = setTimeout(function () { if (c.resumeSeek) { c.resumeSeek = null; note('resume: no frame in 10 s'); seek(); } }, 10000);
      } else go();
    };
    var prepareFail = function (err) {
      clearTimeout(prepGuard);
      if (cur !== c || c.url !== url) return; // сторож уже переоткрыл: поздний ответ старого открытия не нужен
      note('prepare failed ' + (err && err.name ? err.name : err));
      onError(err && err.name ? err.name : err);
    };
    try { p.prepareAsync(prepareOk, prepareFail); }
    catch (e) { prepareFail(e); }
  }

  /** Дорожки после открытия: звук, который ТВ не играет, меняем; если играть нечего — говорим. */
  function inspectTracks() {
    var p = av(), c = cur, all = [], now = [];
    try { all = p.getTotalTrackInfo() || []; } catch (e) { note('getTotalTrackInfo failed'); }
    try { now = p.getCurrentStreamInfo() || []; } catch (e) { /* нет сведений */ }
    all.forEach(function (t) { note('track ' + t.index + ' ' + t.type + ' ' + t.extra_info); });
    c.textTracks = all.filter(function (t) { return t.type === 'TEXT'; });
    if (c.tc) { serverAudio(c); return; }
    var curAudio = null;
    now.forEach(function (t) { if (t.type === 'AUDIO') curAudio = t.index; });
    c.audio = support.audioPlan(all, curAudio);
    c.textTracks = all.filter(function (t) { return t.type === 'TEXT'; });
    if (!c.audio.tracks.length) return;
    if (!c.audio.playable.length) {
      // Звука, который ТВ играет, нет: тот же файл через сервер (звук → AC3), с того же места
      var at = c.pos || c.resume;
      viaServer(c, at, { force: true }).then(function (ok) {
        if (cur !== c) return;
        if (ok) c.opts.toast('Звук ' + c.audio.tracks.map(function (t) { return t.codec || '?'; }).join(', ') + ' телевизор не играет — его готовит сервер');
        else warnNoAudio();
      });
    } else if (c.audio.current && !c.audio.current.ok) {
      var bad = c.audio.current, next = c.audio.playable[0];
      selectAudio(next);
      c.opts.toast('Дорожка ' + bad.codec + ' не воспроизводится, включена ' + trackName(next));
    }
  }

  /** Дорожки потока с сервера: все играются (сервер перекодирует), выбрана одна — [c.tc.a] или озвучка [c.tc.ext]. */
  function serverAudio(c) {
    var tracks = c.tc.info.audio.map(function (t) {
      return { index: t.index, codec: t.codec + (t.ok ? '' : ' → AC3'), language: t.language, channels: t.channels, ok: true, server: true };
    });
    var current = c.tc.ext ? null : tracks.filter(function (t) { return t.index === c.tc.a; })[0] || null;
    c.audio = { tracks: tracks, playable: tracks, current: current };
  }

  function warnNoAudio() {
    var c = cur;
    c.opts.dialog(support.MESSAGES.noAudio,
      'Звук здесь только в ' + c.audio.tracks.map(function (t) { return t.codec || '?'; }).join(', ') + ', а этот телевизор такие дорожки не воспроизводит.',
      [{ text: 'Смотреть без звука' }, { text: 'Назад', run: function () { close(); } }]);
  }

  function selectAudio(t) {
    var c = cur;
    // Поток с сервера: в нём одна дорожка — другая значит новый поток с того же места
    if (t.server && !c.prepared) { c.opts.toast('Подождите: файл ещё открывается'); return; }
    if (t.server) { c.tc.a = t.index; c.tc.ext = null; note('server audio → #' + t.index); c.prepared = false; start(serverUrl(c, c.pos), 0); return; }
    try { av().setSelectTrack('AUDIO', t.index); cur.audio.current = t; note('audio → ' + t.index); }
    catch (e) { note('setSelectTrack AUDIO failed ' + (e.name || e)); }
  }

  function trackName(t) {
    return [t.language ? t.language.toUpperCase() : 'Дорожка ' + t.index, t.codec, t.channels ? t.channels + ' кан.' : ''].filter(Boolean).join(' · ');
  }

  // ---- ошибки ----

  function onError(type) {
    var c = cur;
    if (!c) return;
    var kind = support.errorKind(type);
    // Поток с сервера не открылся ни разу (ffmpeg, сервер занят): этот же файл — как раньше, по зеркалу
    if (c.tc && !c.tc.playedOk) {
      note('server stream failed (' + type + '), opening as is');
      var at = c.tc.base, dub = c.tc.ext;
      c.tc = null; c.dur = 0; c.serverFailed = true;
      c.opts.toast(dub ? 'Сервер не смог подготовить озвучку «' + dub.label + '» (' + type + ') — открываю без неё'
        : 'Сервер не смог подготовить файл (' + type + ') — открываю как есть');
      api.mirrorUrl(c.opts.file).then(function (url) { if (cur === c) start(url, at); }).catch(function (e) { if (cur === c) fail('network', api.describe(e)); });
      return;
    }
    // Сеть или истёкшая подпись зеркала (12 ч): новая ссылка и то же место, не больше двух раз подряд.
    // Подряд — значит без 2 минут спокойного просмотра между ошибками: иначе третий обрыв за длинный фильм был бы последним.
    if (c.retryAt && Date.now() - c.retryAt > 120000) c.retries = 0;
    // Файл уже играл: любая ошибка, кроме «кодек не поддерживается», скорее всего от сети или старой ссылки
    var retryable = kind === 'network' || (c.played && !/NOT_SUPPORTED|UNSUPPORTED/i.test(String(type)));
    if (retryable && c.retries < 2) {
      c.retries++;
      c.retryAt = Date.now();
      var at = c.pos || c.resume;
      note('retry ' + c.retries + ' at ' + fmt(at));
      buffering(true, 0);
      reopenAt(c, at);
      return;
    }
    fail(kind, String(type));
  }

  function fail(kind, detail) {
    var c = cur;
    if (!c) return;
    buffering(false);
    var text = kind === 'unsupported' ? 'Формат или кодек видео этот телевизор не воспроизводит.' : kind === 'network' ? 'Проверьте интернет и попробуйте ещё раз.' : '';
    c.opts.dialog(support.MESSAGES[kind] || support.MESSAGES.other, (text + (detail ? ' (' + detail + ')' : '')).trim(),
      [{ text: 'Назад', run: function () { close(); } }, { text: 'Проба', run: function () { if (cur) openDiag(cur); } }]);
  }

  // ---- управление ----

  function onKey(e) {
    var c = cur;
    if (!c) return false;
    if (document.querySelector('.dialog')) return false; // открыто окно: клавиши его
    if (c.menuOpen) {
      if (e.keyCode === K.BACK || e.keyCode === K.ESC) { closeMenu(); return true; }
      return false; // стрелки и OK — обычная навигация по меню
    }
    if (c.diagOpen) {
      // Проба открыта: ↑/↓ листают журнал (автопрокрутка встаёт, пока не вернулись вниз), остальное плееру не уходит
      var log = el('diag-log');
      if (e.keyCode === K.BACK || e.keyCode === K.ESC || e.keyCode === K.YELLOW || e.keyCode === K.INFO) closeDiag(c);
      else if (e.keyCode === K.UP) { log.scrollTop -= 120; c.diagPinned = log.scrollTop + log.clientHeight < log.scrollHeight - 4; }
      else if (e.keyCode === K.DOWN) { log.scrollTop += 120; c.diagPinned = log.scrollTop + log.clientHeight < log.scrollHeight - 4; }
      else if (e.keyCode === K.STOP) { closeDiag(c); close(); }
      else if (e.keyCode === K.ENTER) speedTest(c);
      return true;
    }
    switch (e.keyCode) {
      case K.ENTER: case K.PLAY_PAUSE: togglePause(); return true;
      case K.PLAY: if (c.paused) togglePause(); return true;
      case K.PAUSE: if (!c.paused) togglePause(); return true;
      case K.LEFT: seekBy(-10000); return true;
      case K.RIGHT: seekBy(10000); return true;
      case K.REW: seekBy(-60000); return true;
      case K.FF: seekBy(60000); return true;
      case K.UP: showBar(); return true;
      case K.DOWN: openMenu(); return true;
      case K.STOP: close(); return true;
      case K.PREV: skipEntry(-1); return true;
      case K.NEXT: skipEntry(1); return true;
      case K.YELLOW: case K.INFO: openDiag(c); return true;
      default: return false;
    }
  }

  function togglePause() {
    var p = av(), c = cur;
    if (!c.prepared) return; // AVPlay ещё открывает файл или перематывает к месту продолжения
    if (c.paused && Date.now() - c.urlAt > URL_TTL_MS) { reopen(c, 'link is old'); return; }
    try {
      if (c.paused) { p.play(); c.paused = false; } else { p.pause(); c.paused = true; save(); }
    } catch (e) { note('play/pause failed ' + (e.name || e)); }
    showBar();
  }

  /** Новая ссылка зеркала и то же место (подпись старой вот-вот истечёт). */
  function reopen(c, why) {
    var at = c.pos || c.resume;
    note('reopen at ' + fmt(at) + ' (' + why + ')');
    c.prepared = false;
    reopenAt(c, at); // поток с сервера — тоже с новой подписью
  }

  /** Соседняя серия (кнопки ⏮ ⏭ пульта). */
  function skipEntry(delta) {
    var c = cur, i = c.opts.index + delta;
    if (c.party && c.party.guestLocked()) { c.opts.toast('Серию выбирает создатель комнаты'); return; }
    if (i < 0 || i >= c.opts.entries.length) { c.opts.toast(delta > 0 ? 'Это последняя серия' : 'Это первая серия'); return; }
    var opts = c.opts;
    close(true);
    opts.onEntry(i);
  }

  /** Перемотка копится, пока жмут кнопку, и уходит в AVPlay одним seekTo через 0,6 с. */
  function seekBy(delta) {
    var c = cur;
    if (!c.prepared) return;
    if (c.party && c.party.guestLocked()) { c.opts.toast('Перематывает создатель комнаты'); return; }
    if (!c.dur) duration(c);
    var base = c.seekTarget != null ? c.seekTarget : c.pos;
    c.seekTarget = Math.max(0, c.dur > 6000 ? Math.min(c.dur - 3000, base + delta) : base + delta);
    renderTime();
    showBar();
    clearTimeout(c.seekTimer);
    c.seeking = true;
    c.seekTimer = setTimeout(function () {
      c.seeking = false;
      seekTo(c, c.seekTarget);
    }, 600);
  }

  /** Перемотка движка (пользователь или комната): метка [seekStamp] говорит комнате, что это перемотка. */
  function seekTo(c, to) {
    c.seekStamp++;
    c.seekTarget = to;
    c.slow = null;
    // Поток с сервера байтами не перематывается: новый поток с этого места (подпись живёт 12 ч)
    // stop()/close() AVPlay, пока он ещё открывает поток, на Tizen 4.0 может повесить приложение: ждём, пока откроется
    if (c.tc && !c.prepared) { c.tcSeekNext = to; note('seek ' + fmt(to) + ' waits for the stream to open'); return; }
    if (c.tc) { note('seek ' + fmt(c.pos) + ' → ' + fmt(to) + ' (new server stream)'); renderTime(); start(serverUrl(c, to), 0); return; }
    // Прошлая перемотка ещё идёт: AVPlay вторую не примет (InvalidStateError) — эта уйдёт после неё
    if (c.seekBusy) { c.seekNext = to; note('seek ' + fmt(to) + ' queued'); return; }
    c.seekBusy = true;
    var from = c.pos, started = Date.now(), done = false;
    note('seek ' + fmt(from) + ' → ' + fmt(to));
    function finish(ok, how) {
      if (done) return;
      done = true;
      clearTimeout(guard);
      c.seekBusy = false;
      if (cur !== c) return;
      note('seek ' + (ok ? 'done' : 'FAILED') + ' (' + how + ', ' + (Date.now() - started) + ' мс)');
      if (ok) c.pos = to;
      if (c.seekNext != null) { var n = c.seekNext; c.seekNext = null; seekTo(c, n); return; }
      c.seekTarget = null;
      renderTime();
      if (!ok) c.opts.toast('Телевизор не смог перемотать этот файл');
    }
    // Ответа нет 20 с — считаем, что не вышло (иначе перемотка заблокирована навсегда). Сторож заводится после
    // возврата seekTo/jump: сам вызов на тяжёлых файлах держит страницу до 20 с (см. resume seek)
    var guard = 0;
    var arm = function () { if (!done) guard = setTimeout(function () { finish(false, 'нет ответа'); }, 20000); };
    function jump(why) {
      // Запасной путь: прыжок от текущего места (у некоторых файлов seekTo не работает, а jump — да)
      var delta = to - from;
      note('seekTo failed (' + why + '), jump ' + Math.round(delta / 1000) + ' с');
      try {
        if (delta >= 0) av().jumpForward(delta, function () { finish(true, 'jump'); }, function () { finish(false, 'jump'); });
        else av().jumpBackward(-delta, function () { finish(true, 'jump'); }, function () { finish(false, 'jump'); });
      } catch (e) { finish(false, 'jump: ' + (e.name || e)); }
      clearTimeout(guard); arm();
    }
    try {
      av().seekTo(to, function () { finish(true, 'seekTo'); }, function (err) { jump(err && err.name ? err.name : String(err)); });
    } catch (e) { jump(e.name || String(e)); }
    if (!guard) arm();
  }

  /** Длительность: у некоторых файлов сразу после открытия AVPlay отдаёт 0, позже — настоящую. */
  function duration(c) {
    if (c.tc) return; // длительность сказал сервер; AVPlay видит только кусок с места открытия
    // У .m2ts длительность бывает 0 всё время: синхронный вызов AVPlay — не чаще раза в 5 с
    var now = Date.now();
    if (now - c.durAskedAt < 5000) return;
    c.durAskedAt = now;
    try { var d = av().getDuration(); if (d > 0 && d !== c.dur) { c.dur = d; note('duration ' + fmt(d)); } } catch (e) { /* нет */ }
  }

  /**
   * Телевизор не успевает декодировать (журнал 21.10: Blu-ray .m2ts — 1 кадр в секунду, без звука): время фильма идёт
   * заметно медленнее настоящего без буферизации. Тогда один раз говорим об этом словами и пишем в пробу.
   */
  function watchSpeed(c, ms) {
    if (c.paused || c.loading || c.seekBusy || c.seekTarget != null || !c.prepared) { c.slow = null; return; }
    var now = Date.now();
    if (!c.slow) { c.slow = { at: now, pos: ms, bad: 0 }; return; }
    var wall = now - c.slow.at;
    if (wall < 5000) return;
    var ratio = (ms - c.slow.pos) / wall;
    c.slow = { at: now, pos: ms, bad: ratio < 0.6 ? c.slow.bad + 1 : 0 };
    if (ratio < 0.9 || c.diagOpen) note('speed ' + Math.round(ratio * 100) + '% (' + fmt(ms) + ')');
    if (c.slow.bad === 2 && !c.slowWarned) {
      c.slowWarned = true;
      note('slow playback: ' + Math.round(ratio * 100) + '% of real time');
      var mbps = c.dur > 0 ? Math.round(c.opts.file.size * 8 / (c.dur / 1000) / 1e6) : 0;
      c.opts.toast('Видео идёт рывками: телевизор не успевает' + (mbps ? ' (файл ~' + mbps + ' Мбит/с)' : '') + '. ' + support.slowHint(c.opts.file.path, mbps), 10000);
    }
  }

  function ended() {
    var c = cur;
    if (!c || c.ended) return;
    if (c.party && c.party.guestLocked()) { note('room film ended: the host picks the next one'); c.paused = true; showBar(); return; }
    c.ended = true;
    c.opts.watch.put(c.key, c.dur, c.dur);
    var next = c.opts.index + 1;
    var opts = c.opts;
    close(true);
    if (next < opts.entries.length) opts.onEntry(next); else opts.onClose();
  }

  function save() {
    var c = cur;
    if (c && !c.ended && c.dur > 0 && c.pos > 0) c.opts.watch.put(c.key, c.pos, c.dur);
  }

  /** [silent] — без возврата на экран раздачи (сразу откроется другое). */
  function close(silent) {
    var c = cur;
    if (!c) return;
    save();
    clearInterval(c.saveTimer); clearInterval(c.diagTimer); clearTimeout(c.seekTimer); clearTimeout(c.hideTimer);
    if (c.party) c.party.stop();
    if (c.speedXhr) { try { c.speedXhr.abort(); } catch (e) { /* уже */ } }
    cur = null;
    try { av().stop(); } catch (e) { /* уже остановлен */ }
    try { av().close(); } catch (e) { /* уже закрыт */ }
    document.body.classList.remove('playing');
    el('player').className = '';
    el('diag').className = '';
    el('menu').className = '';
    el('subs').textContent = '';
    el('p-party').className = '';
    if (!silent) c.opts.onClose();
  }

  // ---- совместный просмотр ----

  /** В комнате плеер идёт за ней (party.js, тот же движок, что на ПК и Android). */
  function attachParty(c) {
    var party = SM.party;
    if (!party || !party.inRoom()) return;
    var keys = c.opts.entries.map(function (en) { return model.watchKey(c.opts.magnet.id, en.file); });
    var sp = {
      get timeMs() { return c.seekTarget != null ? c.seekTarget : c.pos; },
      get busy() { return c.seeking; },
      get engineMs() { return c.pos; },
      get seekStamp() { return c.seekStamp; },
      get paused() { return c.paused; },
      get running() { return c.prepared && !c.loading && !c.paused; },
      applyPaused: function (p) { if (cur === c && p !== c.paused) togglePause(); },
      seekTo: function (ms) { if (cur === c) seekTo(c, ms); }
    };
    c.party = party.attach({
      player: function () { return cur === c && c.prepared ? sp : null; },
      key: function () { return c.key; },
      keys: function () { return keys; },
      title: function () { return c.opts.title; },
      playIndex: function (i) { if (cur === c) { var o = c.opts; close(true); o.onEntry(i); } },
      notice: function (t) { if (cur === c) c.opts.toast(t); },
      log: note
    });
    el('p-party').className = 'show';
  }

  // ---- меню звука и субтитров ----

  function openMenu() {
    var c = cur;
    var menu = el('menu');
    menu.innerHTML = '';
    var items = [h('h2', { text: 'Звук' })];
    if (!c.audio || !c.audio.tracks.length) items.push(h('p', { class: 'muted', text: 'Нет сведений о дорожках' }));
    else c.audio.tracks.forEach(function (t) {
      var selected = !(c.tc && c.tc.ext) && c.audio.current && c.audio.current.index === t.index;
      items.push(SM.ui.button((selected ? '● ' : '') + trackName(t) + (t.ok ? '' : ' — через сервер'),
        function () {
          if (!t.ok) {
            // Дорожку, которую ТВ не играет, готовит сервер: тот же файл через него, с этой дорожкой
            var k = c.audio.tracks.indexOf(t);
            closeMenu();
            viaServer(c, c.pos, { force: true, order: k }).then(function (ok) { if (!ok && cur === c) c.opts.toast('Звук ' + t.codec + ' телевизор не воспроизводит'); });
            return;
          }
          setDubPref(c.opts.magnet.id, null); // дорожка фильма: следующие серии — тоже без озвучки отдельным файлом
          selectAudio(t); closeMenu();
        },
        (t.ok ? '' : 'disabled') + (selected ? ' sel' : '')));
    });
    var dubs = externalChoices(c);
    if (dubs.length || c.custom === null) items.push(h('h2', { text: 'Озвучки отдельным файлом' }));
    dubs.forEach(function (ch) {
      var on = !!(c.tc && c.tc.ext && c.tc.ext.key === ch.key);
      items.push(SM.ui.button((on ? '● ' : '') + ch.label + (ch.kind === 'custom' ? ' · от пользователя' : ''),
        function () { closeMenu(); if (!on) selectExternal(c, ch); }, on ? 'sel' : ''));
    });
    if (c.custom === null) items.push(h('p', { class: 'note grey', text: 'Озвучки пользователей загружаются…' }));
    if (dubs.length) items.push(h('p', { class: 'note grey', text: 'Озвучку с видео соединяет сервер: перемотка с ней чуть дольше' }));
    items.push(h('h2', { text: 'Субтитры' }));
    items.push(SM.ui.button((c.subsOn ? '' : '● ') + 'Выключены', function () { setSubs(null); closeMenu(); }, c.subsOn ? '' : 'sel'));
    c.textTracks.forEach(function (t) {
      var info = {};
      try { info = typeof t.extra_info === 'string' ? JSON.parse(t.extra_info) : (t.extra_info || {}); } catch (e) { info = {}; }
      var label = (info.track_lang || info.language || 'Дорожка ' + t.index) + (info.subtitle_type ? ' · ' + info.subtitle_type : '');
      var on = c.subsOn && c.subsIndex === t.index;
      items.push(SM.ui.button((on ? '● ' : '') + label, function () { setSubs(t.index); closeMenu(); }, on ? 'sel' : ''));
    });
    if (model.subtitlesFor(c.opts.magnet, c.opts.file).length) items.push(h('p', { class: 'note grey', text: 'Субтитры отдельным файлом на телевизоре пока не показываются' }));
    items.forEach(function (i) { menu.appendChild(i); });
    menu.className = 'show';
    c.menuOpen = true;
    nav.root = menu;
    nav.focus(menu.querySelector('.sel') || menu.querySelector('.f'));
  }

  function closeMenu() {
    if (!cur) return;
    cur.menuOpen = false;
    el('menu').className = '';
    nav.root = el('player');
  }

  function setSubs(index) {
    var c = cur, p = av();
    try {
      if (index == null) { p.setSilentSubtitle(true); c.subsOn = false; el('subs').textContent = ''; }
      else { p.setSelectTrack('TEXT', index); p.setSilentSubtitle(false); c.subsOn = true; c.subsIndex = index; }
      note('subtitles → ' + index);
    } catch (e) { note('subtitles failed ' + (e.name || e)); c.opts.toast('Не удалось включить субтитры'); }
  }

  var subsTimer = 0;
  /** Текст субтитра: без тегов, HTML-сущности AVPlay (&quot; &amp; &#39; &nbsp;) — обычными знаками. */
  function cleanSubs(t) {
    return String(t || '').replace(/<[^>]+>/g, '')
      .replace(/&quot;/g, '"').replace(/&apos;|&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>')
      .replace(/&nbsp;/g, ' ').replace(/&#(\d+);/g, function (m, n) { return String.fromCharCode(Number(n)); })
      .replace(/&amp;/g, '&');
  }
  function showSubtitle(text, duration) {
    var s = el('subs');
    s.textContent = cleanSubs(text);
    clearTimeout(subsTimer);
    subsTimer = setTimeout(function () { s.textContent = ''; }, Math.max(500, Number(duration) || 3000));
  }

  // ---- отрисовка ----

  function showBar() {
    var c = cur;
    if (!c) return;
    el('p-bar').className = 'show';
    renderTime();
    clearTimeout(c.hideTimer);
    if (!c.paused) c.hideTimer = setTimeout(function () { if (cur === c && !c.paused) el('p-bar').className = ''; }, 4000);
    el('p-state').textContent = c.paused ? '▶' : '❚❚'; // как на кнопке: что сделает OK
  }

  function renderTime() {
    var c = cur;
    if (!c) return;
    var t = c.seekTarget != null ? c.seekTarget : c.pos;
    el('p-time').textContent = fmt(t) + ' / ' + fmt(c.dur);
    el('p-fill').style.width = (c.dur ? Math.min(100, t / c.dur * 100) : 0) + '%';
  }

  // Что сейчас идёт при загрузке: подпись держится, пока её не сменит следующий этап
  var stage = 'Загрузка…';
  function buffering(on, pct, label) {
    if (label) stage = label;
    if (!on) stage = 'Загрузка…';
    el('p-buffer').className = on ? 'show' + (cur && cur.resumeCover ? ' cover' : '') : '';
    el('p-buffer-text').textContent = on && pct ? 'Буферизация ' + pct + '%' : stage;
  }

  /**
   * Замер скорости сети (OK в пробе): до 24 МБ файла с места просмотра по той же ссылке зеркала, не дольше 10 с.
   * Сравнение с битрейтом файла говорит, отчего рывки: сеть не успевает (скорость ниже битрейта) или декодер.
   * Фильм на это время на паузе — иначе замер делит канал с AVPlay.
   */
  function speedTest(c, noRange) {
    if (c.speedBusy || !c.url) return;
    // Поток с сервера байтами не читается (Range → 416): меряем по обычной ссылке зеркала на тот же файл
    if (c.tc && !c.speedUrl) {
      c.speedBusy = true;
      api.mirrorUrl(c.opts.file).then(function (u) { c.speedBusy = false; c.speedUrl = u; if (cur === c) speedTest(c); },
        function (e) { c.speedBusy = false; c.speed = 'не удалось: ' + api.describe(e); if (cur === c && c.diagOpen) renderDiag(); });
      return;
    }
    var testUrl = c.tc ? c.speedUrl : c.url;
    if (c.prepared && !c.paused) togglePause();
    c.speedBusy = true; c.speed = 'замер…';
    renderDiag();
    var size = c.opts.file.size || 0, LIMIT = 24 * 1024 * 1024;
    var from = c.dur > 0 && size > 0 ? Math.floor(size * Math.min(1, c.pos / c.dur)) : 0;
    if (size > 0 && from + LIMIT > size) from = Math.max(0, size - LIMIT);
    var x = new XMLHttpRequest(), t0 = Date.now(), first = 0, loaded = 0, status = 0, why = '';
    c.speedXhr = x;
    x.open('GET', testUrl.charAt(0) === '/' ? api.BASE + testUrl : testUrl, true);
    if (!noRange) x.setRequestHeader('Range', 'bytes=' + from + '-' + (from + LIMIT - 1));
    x.responseType = 'arraybuffer';
    x.timeout = 20000; // соединение не открылось за 20 с — так и пишем, а не висим
    x.onreadystatechange = function () { if (x.readyState >= 2 && !status) status = x.status; };
    x.onerror = function () { why = 'ошибка соединения'; };
    x.ontimeout = function () { why = 'нет ответа за 20 с'; };
    x.onprogress = function (e) {
      if (!first) first = Date.now();
      loaded = e.loaded;
      if (Date.now() - t0 > 10000) { try { x.abort(); } catch (err) { /* уже */ } }
    };
    x.onloadend = function () {
      c.speedBusy = false; c.speedXhr = null;
      if (cur !== c) return;
      var secs = (Date.now() - (first || t0)) / 1000;
      var mbps = secs > 0 ? loaded * 8 / secs / 1e6 : 0;
      var need = c.dur > 0 && size > 0 ? size * 8 / (c.dur / 1000) / 1e6 : 0;
      if (!loaded && !status && !noRange && why === 'ошибка соединения') {
        // Запрос с Range не ушёл совсем: пробуем без него (с начала файла) — так видно, в Range ли дело
        note('speed test: ' + why + ' за ' + (Date.now() - t0) + ' мс, повтор без Range');
        speedTest(c, true);
        return;
      }
      if (!loaded) c.speed = 'не удалось (' + (status ? 'HTTP ' + status : why || 'нет ответа') + ', ' + (Date.now() - t0) + ' мс' + (noRange ? ', без Range' : '') + ')';
      else c.speed = mbps.toFixed(1).replace('.', ',') + ' Мбит/с, первый байт за ' + ((first || t0) - t0) + ' мс' +
        (need ? (mbps < need * 1.3 ? ' — сеть не успевает за файлом (~' + Math.round(need) + ' Мбит/с)' : ' — сети хватает (файлу нужно ~' + Math.round(need) + ' Мбит/с)') : '');
      note('speed test: ' + c.speed + ' (' + model.formatBytes(loaded) + ', HTTP ' + status + (noRange ? ', без Range' : '') + ', ' + String(testUrl).split('?')[0].replace(/^https?:\/\/[^/]+/, '') + ')');
      if (cur === c && c.diagOpen) renderDiag();
    };
    x.send(null);
  }

  /** Проба: шапка обновляется раз в секунду, журнал — с каждой строкой. */
  function openDiag(c) {
    c.diagOpen = true;
    c.diagPinned = false;
    clearInterval(c.diagTimer);
    c.diagTimer = setInterval(function () { if (cur === c) renderDiag(); }, 1000);
    renderDiag();
  }

  function closeDiag(c) {
    c.diagOpen = false;
    clearInterval(c.diagTimer);
    el('diag').className = '';
  }

  function renderDiag() {
    var c = cur, p = av(), lines = [];
    if (!c) return;
    lines.push('Файл: ' + c.opts.file.path);
    lines.push('Проверка по имени: ' + (support.checkFile(c.opts.file.path).text || 'ok'));
    // Пока AVPlay грузится или перематывает, его синхронные вызовы могут держать страницу — не спрашиваем
    var busy = c.loading || c.seekBusy || !c.prepared;
    if (!busy) { try { lines.push('Состояние: ' + p.getState()); } catch (e) { /* нет */ } }
    else lines.push('Состояние: ' + (c.seekBusy ? 'перемотка' : 'загрузка'));
    lines.push('Поток: ' + (c.tc ? 'через сервер (ffmpeg), ' + (c.tc.ext ? 'озвучка «' + c.tc.ext.label + '»' : 'звук #' + c.tc.a) +
      ', открыт с ' + fmt(c.tc.base) : 'файл как есть (зеркало)'));
    lines.push('Запас: ' + (bigBuffer() ? 'большой (8 с / 15 с)' : 'как у телевизора') +
      ' · остановок на загрузку: ' + c.stalls + (c.stalls ? ' (всего ' + Math.round((c.stallMs + (c.stallFrom ? Date.now() - c.stallFrom : 0)) / 1000) + ' с)' : ''));
    if (c.speed) lines.push('Скорость сети: ' + c.speed);
    lines.push('OK — замер скорости сети (фильм встанет на паузу на ~10 с) · ↑/↓ — журнал · «Назад» — закрыть');
    lines.push('Длительность: ' + fmt(c.dur) + ', позиция: ' + fmt(c.pos) + (c.seekBusy ? ', идёт перемотка' : ''));
    if (c.dur > 0 && c.opts.file.size > 0) lines.push('Битрейт файла: ~' + Math.round(c.opts.file.size * 8 / (c.dur / 1000) / 1e6) + ' Мбит/с (размер ' + model.formatBytes(c.opts.file.size) + ')');
    try {
      if (!busy) (p.getCurrentStreamInfo() || []).forEach(function (t) { lines.push('Сейчас ' + t.type + ' #' + t.index + ': ' + t.extra_info); });
    } catch (e) { /* нет */ }
    el('diag-head').textContent = lines.join('\n');
    var body = el('diag-log');
    body.textContent = (prevLog.length ? '— прошлый просмотр —\n' + prevLog.join('\n') + '\n— сейчас —\n' : '') + log.join('\n');
    if (!c.diagPinned) body.scrollTop = body.scrollHeight; // свежие строки — внизу, пока не листали вверх
    el('diag').className = 'show';
  }

  // Приложение свернули: запоминаем место и ставим на паузу, при возврате плеер ждёт кнопку
  document.addEventListener('visibilitychange', function () {
    if (!cur) return;
    if (document.hidden) {
      save();
      try { av().pause(); cur.paused = true; } catch (e) { note('pause failed'); }
      try { av().suspend(); } catch (e) { note('suspend failed'); }
    } else {
      try { av().restore(); } catch (e) { note('restore failed'); }
      showBar();
    }
  });

  SM.player = { play: play, close: close, current: function () { return cur ? cur.key : null; }, log: function () { return log.slice(); } };
})(window);

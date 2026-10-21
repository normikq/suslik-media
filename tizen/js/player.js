/*
 * Плеер на AVPlay (webapis.avplay — аппаратный плеер телевизора). Читает подписанную ссылку зеркала напрямую:
 * локального BurstProxy на ТВ нет (веб-приложение не может слушать порт), запас держит сам AVPlay.
 *
 * Что телевизор не умеет, плеер говорит словами (support.js): DTS/TrueHD в меню звука отключены и при открытии
 * заменяются другой дорожкой; если звука, который ТВ играет, нет — окно «Звук этого файла телевизор не воспроизводит».
 * Жёлтая кнопка или Info — «проба»: кодеки, дорожки и события AVPlay (этап 0 плана: что играет этот телевизор).
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
  function note(text) {
    var d = new Date();
    log.push(('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2) + ':' + ('0' + d.getSeconds()).slice(-2) + ' ' + text);
    if (log.length > 300) log.shift();
    if (cur && cur.diagOpen) renderDiag();
  }

  function av() { return g.webapis && g.webapis.avplay; }
  function bigBuffer() { try { return localStorage.getItem('tv_buffer') === 'big'; } catch (e) { return false; } }

  // Сторож: таймер 0,5 с не срабатывал дольше 1,5 с — приложение висело (AVPlay держит поток страницы). В пробу.
  var lastBeat = Date.now();
  setInterval(function () {
    var now = Date.now(), gap = now - lastBeat;
    lastBeat = now;
    if (cur && gap > 1500) note('UI stall ' + gap + ' ms' + (cur.seekBusy ? ' (seek)' : cur.loading ? ' (loading)' : ''));
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
      urlAt: 0, played: false, retryAt: 0, stalls: 0, stallMs: 0, stallFrom: 0, durAskedAt: 0
    };
    if (opts.resumeAt != null) cur.resume = opts.resumeAt;
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
    api.mirrorUrl(opts.file).then(function (url) { if (cur && cur.opts === opts) start(url, cur.resume); })
      .catch(function (e) { if (cur && cur.opts === opts) fail('network', api.describe(e)); });
  }

  function start(url, fromMs) {
    var p = av(), c = cur;
    c.url = url;
    c.urlAt = Date.now();
    // Переоткрытие (обрыв сети, новая ссылка): пока AVPlay снова не готов, плеер «грузится» — перемотка и пауза ждут
    c.prepared = false; c.loading = true; c.slow = null; c.seekBusy = false; c.seekNext = null;
    clearInterval(c.saveTimer);
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
      if (/2160p|\b4k\b|\buhd\b/i.test(c.opts.file.path)) {
        try { p.setStreamingProperty('SET_MODE_4K', 'TRUE'); note('mode 4K'); } catch (e) { note('SET_MODE_4K failed ' + (e.name || e)); }
      }
      // Старый плеер после close() ещё может прислать событие: оно не должно трогать новый фильм
      p.setListener({
        onbufferingstart: function () {
          if (cur !== c) return;
          note('buffering start');
          c.loading = true; buffering(true, 0);
          if (c.prepared && !c.seekBusy) { c.stalls++; c.stallFrom = Date.now(); }
        },
        onbufferingprogress: function (pct) { if (cur === c) buffering(true, pct); },
        onbufferingcomplete: function () {
          if (cur !== c) return;
          note('buffering complete');
          c.loading = false; buffering(false);
          if (c.stallFrom) { c.stallMs += Date.now() - c.stallFrom; c.stallFrom = 0; }
        },
        oncurrentplaytime: function (ms) {
          if (cur !== c) return;
          c.pos = ms;
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
    var prepareOk = function () {
      if (cur !== c) return;
      try { c.dur = p.getDuration(); } catch (e) { c.dur = 0; }
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
        buffering(false);
        showBar();
        clearInterval(c.saveTimer);
        c.saveTimer = setInterval(save, 10000);
      };
      if (fromMs > 0) {
        note('resume at ' + fmt(fromMs));
        // Ответа на перемотку нет 15 с — запускаем с того места, где AVPlay стоит (иначе вечная «Загрузка…»)
        resumeGuard = setTimeout(function () { note('resume seek: no answer'); go(); }, 15000);
        try { p.seekTo(fromMs, go, function () { note('resume seek failed'); go(); }); }
        catch (e) { note('resume seek failed: ' + (e.name || e)); go(); }
      } else go();
    };
    var prepareFail = function (err) {
      if (cur !== c) return;
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
    var curAudio = null;
    now.forEach(function (t) { if (t.type === 'AUDIO') curAudio = t.index; });
    c.audio = support.audioPlan(all, curAudio);
    c.textTracks = all.filter(function (t) { return t.type === 'TEXT'; });
    if (!c.audio.tracks.length) return;
    if (!c.audio.playable.length) {
      warnNoAudio();
    } else if (c.audio.current && !c.audio.current.ok) {
      var bad = c.audio.current, next = c.audio.playable[0];
      selectAudio(next);
      c.opts.toast('Дорожка ' + bad.codec + ' не воспроизводится, включена ' + trackName(next));
    }
  }

  function warnNoAudio() {
    var c = cur;
    c.opts.dialog(support.MESSAGES.noAudio,
      'Звук здесь только в ' + c.audio.tracks.map(function (t) { return t.codec || '?'; }).join(', ') + ', а этот телевизор такие дорожки не воспроизводит.',
      [{ text: 'Смотреть без звука' }, { text: 'Назад', run: function () { close(); } }]);
  }

  function selectAudio(t) {
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
      api.mirrorUrl(c.opts.file).then(function (url) { if (cur === c) start(url, at); }).catch(function (e) { if (cur === c) fail('network', api.describe(e)); });
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
    buffering(true, 0);
    api.mirrorUrl(c.opts.file).then(function (url) { if (cur === c) start(url, at); })
      .catch(function (e) { if (cur === c) fail('network', api.describe(e)); });
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
    // Ответа нет 20 с — считаем, что не вышло (иначе перемотка заблокирована навсегда)
    var guard = setTimeout(function () { finish(false, 'нет ответа'); }, 20000);
    function jump(why) {
      // Запасной путь: прыжок от текущего места (у некоторых файлов seekTo не работает, а jump — да)
      var delta = to - from;
      note('seekTo failed (' + why + '), jump ' + Math.round(delta / 1000) + ' с');
      try {
        if (delta >= 0) av().jumpForward(delta, function () { finish(true, 'jump'); }, function () { finish(false, 'jump'); });
        else av().jumpBackward(-delta, function () { finish(true, 'jump'); }, function () { finish(false, 'jump'); });
      } catch (e) { finish(false, 'jump: ' + (e.name || e)); }
    }
    try {
      av().seekTo(to, function () { finish(true, 'seekTo'); }, function (err) { jump(err && err.name ? err.name : String(err)); });
    } catch (e) { jump(e.name || String(e)); }
  }

  /** Длительность: у некоторых файлов сразу после открытия AVPlay отдаёт 0, позже — настоящую. */
  function duration(c) {
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
      var selected = c.audio.current && c.audio.current.index === t.index;
      items.push(SM.ui.button((selected ? '● ' : '') + trackName(t) + (t.ok ? '' : ' — не поддерживается'),
        function () { if (!t.ok) { c.opts.toast('Звук ' + t.codec + ' телевизор не воспроизводит'); return; } selectAudio(t); closeMenu(); },
        (t.ok ? '' : 'disabled') + (selected ? ' sel' : '')));
    });
    var dubs = model.externalAudioFor(c.opts.magnet, c.opts.file);
    if (dubs.length) items.push(h('p', { class: 'note grey', text: support.EXTERNAL_AUDIO_TEXT }));
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
    el('p-state').textContent = c.paused ? '❚❚' : '▶';
  }

  function renderTime() {
    var c = cur;
    if (!c) return;
    var t = c.seekTarget != null ? c.seekTarget : c.pos;
    el('p-time').textContent = fmt(t) + ' / ' + fmt(c.dur);
    el('p-fill').style.width = (c.dur ? Math.min(100, t / c.dur * 100) : 0) + '%';
  }

  function buffering(on, pct) {
    el('p-buffer').className = on ? 'show' : '';
    el('p-buffer-text').textContent = on && pct ? 'Загрузка ' + pct + '%' : 'Загрузка…';
  }

  /**
   * Замер скорости сети (OK в пробе): до 24 МБ файла с места просмотра по той же ссылке зеркала, не дольше 10 с.
   * Сравнение с битрейтом файла говорит, отчего рывки: сеть не успевает (скорость ниже битрейта) или декодер.
   * Фильм на это время на паузе — иначе замер делит канал с AVPlay.
   */
  function speedTest(c) {
    if (c.speedBusy || !c.url) return;
    if (c.prepared && !c.paused) togglePause();
    c.speedBusy = true; c.speed = 'замер…';
    renderDiag();
    var size = c.opts.file.size || 0, LIMIT = 24 * 1024 * 1024;
    var from = c.dur > 0 && size > 0 ? Math.floor(size * Math.min(1, c.pos / c.dur)) : 0;
    if (size > 0 && from + LIMIT > size) from = Math.max(0, size - LIMIT);
    var x = new XMLHttpRequest(), t0 = Date.now(), first = 0, loaded = 0, status = 0;
    c.speedXhr = x;
    x.open('GET', c.url.charAt(0) === '/' ? api.BASE + c.url : c.url, true);
    x.setRequestHeader('Range', 'bytes=' + from + '-' + (from + LIMIT - 1));
    x.responseType = 'arraybuffer';
    x.onreadystatechange = function () { if (x.readyState >= 2 && !status) status = x.status; };
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
      if (!loaded) c.speed = 'не удалось (HTTP ' + (status || 'нет ответа') + ')';
      else c.speed = mbps.toFixed(1).replace('.', ',') + ' Мбит/с, первый байт за ' + ((first || t0) - t0) + ' мс' +
        (need ? (mbps < need * 1.3 ? ' — сеть не успевает за файлом (~' + Math.round(need) + ' Мбит/с)' : ' — сети хватает (файлу нужно ~' + Math.round(need) + ' Мбит/с)') : '');
      note('speed test: ' + c.speed + ' (' + model.formatBytes(loaded) + ', HTTP ' + status + ')');
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
    body.textContent = log.join('\n');
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

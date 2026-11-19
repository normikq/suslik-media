/* Встроенный плеер сайта: HLS (hls.js, в Safari — нативно) и прямой файл через зеркало.
 * Контракт ответа POST /api/file/variant-url — docs/HLS.md §9–10, устройство и ручная проверка — docs/HLS-CLIENT.md «T5».
 * Внешний файл без сборщика (ES2017): CSP страницы пускает только 'self' и хеши встроенных скриптов.
 * В Node грузится без DOM (module.exports) — чистые функции проверяет website/test/hls-player.test.js.
 * Приложение Android (window.__suslikApp >= 2 и SuslikNative) играет само — там модуль ничего не делает. */
(function (root, factory) {
  "use strict";
  var mod = factory(root);
  if (typeof module === "object" && module && module.exports) module.exports = mod;
  else root.SuslikHls = mod;
})(typeof window !== "undefined" ? window : globalThis, function (root) {
  "use strict";

  const HEVC_TYPE = 'video/mp4; codecs="hvc1.1.6.L120.90"';
  const AVC_TYPE = 'video/mp4; codecs="avc1.640028"';
  const HLS_MIME = "application/vnd.apple.mpegurl";
  const POLL_SHEET_MS = 60000; // окно фильма открыто, ассет кодируется — спрашиваем статус раз в минуту
  const POLL_PLAYING_MS = 90000; // смотрим зеркало — не стал ли готов HLS
  const SAVE_EVERY_MS = 30000;
  const NET_RETRIES = 3;

  // ---------- Чистые функции (тесты) ----------

  const VCODEC = {
    h264: "avc1.640028", avc: "avc1.640028", avc1: "avc1.640028",
    hevc: "hvc1.1.6.L120.90", h265: "hvc1.1.6.L120.90",
    vp9: "vp09.00.40.08", av1: "av01.0.08M.08"
  };
  // «Плохой» звук тоже пишем в codecs: тогда canPlayType честно скажет «нет».
  const ACODEC_MP4 = { aac: "mp4a.40.2", ac3: "ac-3", eac3: "ec-3", dts: "dtsc", truehd: "mlpa" };

  /** direct {ext, vcodec, acodec} из ответа variant-url → MIME для video.canPlayType; контейнер не для браузера — null. */
  function directMime(direct) {
    const d = direct || {};
    const ext = String(d.ext || "").toLowerCase().replace(/^\./, "");
    let container;
    if (ext === "mp4" || ext === "m4v") container = "video/mp4";
    else if (ext === "webm") container = "video/webm";
    else return null;
    const v = VCODEC[String(d.vcodec || "").toLowerCase()];
    if (!v) return container;
    const codecs = [v];
    const a = String(d.acodec || "").toLowerCase();
    if (container === "video/mp4" && ACODEC_MP4[a]) codecs.push(ACODEC_MP4[a]);
    if (container === "video/webm" && (a === "opus" || a === "vorbis")) codecs.push(a);
    return container + '; codecs="' + codecs.join(", ") + '"';
  }

  function shiftOf(v) {
    const n = Number(v);
    return Number.isFinite(n) ? Math.round(n) : 0;
  }
  /** Позиция в HLS-ассете = позиция в файле раздачи + shift_ms (docs/HLS.md §8). */
  function toHlsMs(fileMs, shiftMs) { return Math.max(0, Math.round(Number(fileMs) || 0) + shiftOf(shiftMs)); }
  function toFileMs(hlsMs, shiftMs) { return Math.max(0, Math.round(Number(hlsMs) || 0) - shiftOf(shiftMs)); }

  function etaMinutes(sec) {
    const n = Number(sec);
    return Number.isFinite(n) && n > 0 ? Math.max(1, Math.ceil(n / 60)) : null;
  }

  /** Чем играть HLS: "native" (Safari), "hlsjs" (MediaSource) или null — браузер не умеет кодек. */
  function hlsEngine(hls, caps) {
    // v1-copy копирует видео исходника: H.264-раздача даёт H.264 в HLS (docs/HLS.md §5)
    const avc = hls.profile === "v1-copy" && String((hls.direct || {}).vcodec || "").toLowerCase() === "h264";
    const mse = avc ? !!caps.avcMse : !!caps.hevcMse;
    const nat = !!caps.nativeHls && (avc ? !!caps.nativeAvc : !!caps.nativeHevc);
    if (nat && (caps.safari || !mse)) return "native";
    if (mse) return "hlsjs";
    return null;
  }

  /**
   * Что показать по ответу variant-url и возможностям браузера.
   * caps: {hevcMse, avcMse, nativeHls, nativeHevc, nativeAvc, safari, canPlay(mime) → bool}.
   * kind: legacy (блока hls нет — как раньше, внешние плееры) | hls | direct | wait | soon | unsupported.
   * wait — encoding или restoring (у restoring — restoring: true); при direct_ok оба дают direct с upgrade.
   */
  function pickSource(resp, caps) {
    const c = caps || {};
    const hls = resp && resp.hls;
    if (!hls || typeof hls !== "object") return { kind: "legacy" };
    const mime = directMime(hls.direct);
    const directOk = Boolean(hls.direct_ok && resp.url && mime && typeof c.canPlay === "function" && c.canPlay(mime));
    const direct = directOk ? { kind: "direct", url: resp.url, mime, shiftMs: 0 } : null;
    if (hls.status === "ready" && hls.url) {
      const engine = hlsEngine(hls, c);
      if (engine) {
        return { kind: "hls", url: hls.url, engine, shiftMs: shiftOf(hls.shift_ms), profile: hls.profile || "v1", fallback: direct };
      }
      return direct || { kind: "unsupported" };
    }
    if (direct) return Object.assign(direct, { upgrade: true }); // HLS может стать готов во время просмотра
    if (hls.status === "encoding") {
      const p = Number(hls.progress);
      return { kind: "wait", etaMin: etaMinutes(hls.eta), progress: Number.isFinite(p) ? Math.max(0, Math.min(1, p)) : null };
    }
    // Версия в холодном архиве, сервер уже попросил CDN вернуть её (docs/HLS.md §17): ждём, как при кодировании
    if (hls.status === "restoring") return { kind: "wait", restoring: true, etaMin: etaMinutes(hls.eta), progress: null };
    return { kind: "soon" };
  }

  /** Строка под кнопкой в окне фильма. */
  function messageFor(src) {
    switch (src && src.kind) {
      case "wait":
        if (src.restoring) return src.etaMin ? "Достаём из архива, примерно " + src.etaMin + " мин" : "Достаём из архива";
        return src.etaMin ? "Готовим для браузера, примерно " + src.etaMin + " мин" : "Готовим для браузера";
      case "soon": return "Скоро будет готово для браузера";
      case "unsupported": return "Этот браузер не показывает такое видео";
      default: return "";
    }
  }

  function attrs(line) {
    const out = {};
    const re = /([A-Z0-9-]+)=("([^"]*)"|[^,]*)/g;
    let m;
    while ((m = re.exec(line))) out[m[1]] = m[3] !== undefined ? m[3] : m[2];
    return out;
  }
  /** Дорожки из master.m3u8: звук, субтитры, варианты видео. */
  function parseMasterMedia(text) {
    const res = { audio: [], subtitles: [], variants: [] };
    const lines = String(text || "").split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim();
      if (line.startsWith("#EXT-X-MEDIA:")) {
        const a = attrs(line.slice(13));
        const t = { name: a.NAME || "", lang: a.LANGUAGE || "", default: a.DEFAULT === "YES", group: a["GROUP-ID"] || "", uri: a.URI || "" };
        if (a.TYPE === "AUDIO") res.audio.push(t);
        else if (a.TYPE === "SUBTITLES") res.subtitles.push(Object.assign(t, { forced: a.FORCED === "YES" }));
      } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
        const a = attrs(line.slice(18));
        let uri = "";
        for (let j = i + 1; j < lines.length; j++) {
          const u = lines[j].trim();
          if (u && !u.startsWith("#")) { uri = u; i = j; break; }
        }
        res.variants.push({ bandwidth: Number(a.BANDWIDTH) || 0, codecs: a.CODECS || "", resolution: a.RESOLUTION || "", uri });
      }
    }
    return res;
  }

  /** Индекс дорожки по прошлому выбору (имя, потом язык), иначе DEFAULT, иначе первая (или -1 для субтитров). */
  function pickTrack(tracks, choice, fallback) {
    const list = Array.isArray(tracks) ? tracks : [];
    if (choice) {
      if (choice.off) return -1;
      let i = list.findIndex(t => t && choice.name && t.name === choice.name);
      if (i < 0) i = list.findIndex(t => t && choice.lang && t.lang === choice.lang);
      if (i >= 0) return i;
    }
    const d = list.findIndex(t => t && t.default);
    if (d >= 0) return d;
    return list.length ? fallback : -1;
  }

  /**
   * Что делать с фатальной ошибкой hls.js. state: {net, media, refreshed}.
   * retry — startLoad(); refresh — новая подпись (variant-url) и перезапуск; recover — recoverMediaError();
   * swap — swapAudioCodec() + recoverMediaError(); fallback — на зеркало (если можно) или сообщение.
   */
  function hlsErrorAction(data, state) {
    if (!data || !data.fatal) return "ignore";
    const st = state || {};
    if (data.type === "networkError") {
      if ((st.net || 0) < NET_RETRIES) return "retry";
      return st.refreshed ? "fallback" : "refresh";
    }
    if (data.type === "mediaError") {
      if (!st.media) return "recover";
      if (st.media === 1) return "swap";
      return "fallback";
    }
    return "fallback";
  }

  /** С какого места начинать (мс файла): как карточка «С 10:54» в окне фильма. */
  function resumeMs(w) {
    if (!w || !(w.pos >= 5000)) return 0;
    if (w.len > 0 && w.pos >= w.len - 15000) return 0;
    return Math.round(w.pos);
  }

  // ---------- Браузер ----------

  const hasDom = typeof document !== "undefined";
  const SELF_SRC = hasDom && document.currentScript && document.currentScript.src ? document.currentScript.src : "";

  function isNativeApp() {
    if (typeof root.nativeApp === "function") { try { return !!root.nativeApp(); } catch (e) { /* ниже */ } }
    return Number(root.__suslikApp || 0) >= 2 && !!root.SuslikNative;
  }

  function detectCaps() {
    const v = document.createElement("video");
    const MS = root.ManagedMediaSource || root.MediaSource || root.WebKitMediaSource;
    const mse = t => { try { return !!(MS && MS.isTypeSupported && MS.isTypeSupported(t)); } catch (e) { return false; } };
    const can = t => { try { return !!v.canPlayType(t); } catch (e) { return false; } };
    const ua = (root.navigator && root.navigator.userAgent) || "";
    return {
      hevcMse: mse(HEVC_TYPE),
      avcMse: mse(AVC_TYPE),
      nativeHls: can(HLS_MIME),
      nativeHevc: can(HEVC_TYPE),
      nativeAvc: can(AVC_TYPE),
      safari: /Safari\//.test(ua) && !/Chrome|Chromium|CriOS|FxiOS|EdgiOS|Edg\/|Android/.test(ua),
      canPlay: can
    };
  }

  let hlsJsTask = null;
  function loadHlsJs() {
    if (root.Hls) return Promise.resolve(root.Hls);
    if (!hlsJsTask) {
      hlsJsTask = new Promise(resolve => {
        const sc = document.createElement("script");
        sc.src = new URL("vendor/hls.min.js", SELF_SRC || document.baseURI).toString();
        sc.async = true;
        sc.onload = () => resolve(root.Hls || null);
        sc.onerror = () => { hlsJsTask = null; resolve(null); };
        document.head.appendChild(sc);
      });
    }
    return hlsJsTask;
  }

  const CSS = [
    "#playSheet .ps-hls{display:flex;flex-direction:column;gap:8px;margin:10px 0 0}",
    "#playSheet .ps-hls-note{font:400 13px var(--ui,system-ui);color:#8e96a8}",
    "#playSheet .ps-hls-btn{display:flex;align-items:center;justify-content:center;gap:8px;height:44px;border-radius:12px;border:1px solid #2a3346;background:#161c28;color:#e8ecf4;font:600 13.5px var(--ui,system-ui);cursor:pointer}",
    "#playSheet .ps-hls-btn:hover{border-color:#4d86ff}",
    "#playSheet .ps-hls-btn[disabled]{opacity:.45;pointer-events:none}",
    ".shls{position:fixed;inset:0;z-index:10000;background:#000;display:flex;flex-direction:column}",
    ".shls video{flex:1 1 auto;width:100%;min-height:0;background:#000}",
    ".shls-bar{display:flex;flex-wrap:wrap;align-items:center;gap:8px;padding:8px 16px;background:#0b0e14;color:#e8ecf4;font:500 13px var(--ui,system-ui)}",
    ".shls-title{flex:1 1 160px;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}",
    ".shls-bar select,.shls-bar button{height:34px;border-radius:9px;border:1px solid #2a3346;background:#161c28;color:#e8ecf4;font:inherit;padding:0 10px;max-width:100%}",
    ".shls-note{padding:6px 16px;background:#0b0e14;color:#8e96a8;font:400 12.5px var(--ui,system-ui)}",
    ".shls-note:empty{display:none}",
    ".shls-note.err{color:#ff9b8f}"
  ].join("\n");
  function ensureStyle() {
    if (document.getElementById("shls-style")) return;
    const st = document.createElement("style");
    st.id = "shls-style";
    st.textContent = CSS;
    document.head.appendChild(st);
  }

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function requestVariant(api, adId, path) {
    return api("/api/file/variant-url", { method: "POST", body: JSON.stringify({ ad_id: String(adId), path }) });
  }

  /** «Открыть в приложении»: та же ссылка, что у кнопки «Смотреть» (приложение Android, PotPlayer, VLC). */
  function appButton(sheet) {
    const a = sheet.querySelector('a[data-act="player"]');
    const b = el("button", "ps-hls-btn secondary", "Открыть в приложении");
    b.type = "button";
    if (!a || a.classList.contains("disabled") || !a.getAttribute("href")) b.disabled = true;
    b.addEventListener("click", () => { if (a) a.click(); });
    return b;
  }

  // ---------- Окно фильма ----------

  /**
   * Вызов из psLoad (index.html) после того, как кнопка «Смотреть» готова.
   * ctx: {sheet, file, magnet, mirrorUrl, live(), api(path, opts), watch}.
   */
  async function attach(ctx) {
    if (!hasDom || !ctx || !ctx.sheet) return;
    const sheet = ctx.sheet;
    const token = (sheet._hlsToken = (sheet._hlsToken || 0) + 1);
    const old = sheet.querySelector(".ps-hls");
    if (old) old.remove();
    if (isNativeApp()) return; // приложение играет само, как раньше
    const m = ctx.magnet, f = ctx.file;
    if (!m || m.id == null || !f || f.path == null || typeof ctx.api !== "function") return;
    const alive = () => sheet._hlsToken === token && (!ctx.live || ctx.live());
    let resp;
    try { resp = await requestVariant(ctx.api, m.id, f.path); } catch (e) { return; } // как раньше: только внешние плееры
    if (!alive()) return;
    const caps = detectCaps();
    const src = pickSource(resp, caps);
    if (src.kind === "legacy") return;
    ensureStyle();
    const box = el("div", "ps-hls");
    const watch = sheet.querySelector('a[data-act="player"]');
    if (watch && watch.parentNode) watch.parentNode.insertBefore(box, watch.nextSibling);
    else sheet.querySelector(".ps-card").appendChild(box);
    if (src.kind === "hls" || src.kind === "direct") {
      const b = el("button", "ps-hls-btn", "▶ Смотреть в браузере");
      b.type = "button";
      b.addEventListener("click", () => openPlayer(ctx, resp, src, caps));
      box.appendChild(b);
      return;
    }
    box.append(el("div", "ps-hls-note", messageFor(src)), appButton(sheet));
    if (src.kind === "wait" || src.kind === "soon") {
      // пока окно открыто — следим, не стал ли готов
      const tick = async () => {
        if (!alive()) return;
        let r = null;
        try { r = await requestVariant(ctx.api, m.id, f.path); } catch (e) { /* следующий раз */ }
        if (!alive()) return;
        const next = r ? pickSource(r, caps) : src;
        if (next.kind === "hls" || next.kind === "direct") { attach(ctx); return; }
        const note = box.querySelector(".ps-hls-note");
        if (note && next.kind !== "legacy") note.textContent = messageFor(next);
        setTimeout(tick, POLL_SHEET_MS);
      };
      setTimeout(tick, POLL_SHEET_MS);
    }
  }

  // ---------- Плеер ----------

  let current = null;

  function openPlayer(ctx, resp, src, caps) {
    if (current) closePlayer(current);
    ensureStyle();
    const ov = el("div", "shls");
    ov.setAttribute("role", "dialog");
    ov.setAttribute("aria-modal", "true");
    ov.setAttribute("aria-label", "Плеер");
    const video = el("video");
    video.controls = true;
    video.playsInline = true;
    video.setAttribute("playsinline", "");
    video.preload = "auto";
    const bar = el("div", "shls-bar");
    const close = el("button", "", "Закрыть");
    close.type = "button";
    const titleText = (document.querySelector("#playSheet .ps-title") || {}).textContent || (ctx.file && ctx.file.name) || "";
    const title = el("div", "shls-title", titleText);
    const audioSel = el("select");
    audioSel.setAttribute("aria-label", "Озвучка");
    const subSel = el("select");
    subSel.setAttribute("aria-label", "Субтитры");
    audioSel.hidden = subSel.hidden = true;
    bar.append(close, title, audioSel, subSel);
    const note = el("div", "shls-note");
    ov.append(video, bar, note);
    document.body.appendChild(ov);

    const P = {
      ctx, resp, src, caps, ov, video, note, audioSel, subSel,
      hls: null, shiftMs: 0, pending: null, closed: false,
      errState: { net: 0, media: 0, refreshed: false },
      choice: { audio: null, sub: null },
      syncOff: false, lastSaved: -1, saveTimer: 0, pollTimer: 0,
      key: String(ctx.magnet.id) + "|" + ctx.file.path
    };
    current = P;

    const onKey = e => {
      if (e.key !== "Escape" || P.closed) return;
      if (document.fullscreenElement) return; // первый Esc — выход из полноэкранного
      e.stopImmediatePropagation();
      e.preventDefault();
      closePlayer(P);
    };
    const onHide = () => saveProgress(P, true);
    window.addEventListener("keydown", onKey, true);
    window.addEventListener("pagehide", onHide);
    P.unbind = () => { window.removeEventListener("keydown", onKey, true); window.removeEventListener("pagehide", onHide); };
    close.addEventListener("click", () => closePlayer(P));

    audioSel.addEventListener("change", () => setAudio(P, Number(audioSel.value)));
    subSel.addEventListener("change", () => setSub(P, Number(subSel.value)));
    video.addEventListener("pause", () => {
      saveProgress(P, true);
      if (P.pending && !video.ended && !P.closed) switchTo(P, P.pending, true);
    });
    video.addEventListener("ended", () => saveProgress(P, true));
    video.addEventListener("error", () => onVideoError(P));
    video.textTracks && video.textTracks.addEventListener && video.textTracks.addEventListener("addtrack", () => fillTracks(P));
    video.audioTracks && video.audioTracks.addEventListener && video.audioTracks.addEventListener("addtrack", () => fillTracks(P));
    P.saveTimer = setInterval(() => { if (!video.paused) saveProgress(P, false); }, SAVE_EVERY_MS);

    start(P, src, resumeMs(ctx.watch), false);
  }

  function closePlayer(P) {
    if (P.closed) return;
    saveProgress(P, true);
    P.closed = true;
    clearInterval(P.saveTimer);
    clearTimeout(P.pollTimer);
    teardown(P);
    if (P.unbind) P.unbind();
    P.ov.remove();
    if (current === P) current = null;
  }

  function teardown(P) {
    if (P.hls) { try { P.hls.destroy(); } catch (e) { /* уже */ } P.hls = null; }
    try { P.video.pause(); } catch (e) { /* нет */ }
    P.video.removeAttribute("src");
    while (P.video.firstChild) P.video.removeChild(P.video.firstChild);
    try { P.video.load(); } catch (e) { /* нет */ }
  }

  function fileMsNow(P) {
    const t = P.video.currentTime;
    return toFileMs(Number.isFinite(t) ? t * 1000 : 0, P.shiftMs);
  }

  function setNote(P, text, err) {
    P.note.textContent = text || "";
    P.note.classList.toggle("err", !!err);
    const old = P.note.querySelector("button");
    if (old) old.remove();
  }
  function fail(P, text) {
    teardown(P);
    setNote(P, text + " Откройте в приложении.", true);
    const sheet = P.ctx.sheet;
    const b = appButton(sheet);
    b.style.marginLeft = "8px";
    b.addEventListener("click", () => closePlayer(P));
    P.note.appendChild(b);
  }

  /** Запустить источник с места fileMs (время файла раздачи). */
  async function start(P, src, fileMs, paused) {
    teardown(P);
    P.src = src;
    P.shiftMs = src.kind === "hls" ? src.shiftMs : 0;
    P.errState = { net: 0, media: 0, refreshed: P.errState.refreshed };
    const at = toHlsMs(fileMs, P.shiftMs) / 1000;
    const video = P.video;
    const go = () => { if (!paused) { const p = video.play(); if (p && p.catch) p.catch(() => {}); } };
    const seekOnMeta = () => {
      const once = () => { video.removeEventListener("loadedmetadata", once); if (at > 0) { try { video.currentTime = at; } catch (e) { /* нет */ } } go(); };
      video.addEventListener("loadedmetadata", once);
    };
    clearTimeout(P.pollTimer);
    if (src.kind === "direct") {
      seekOnMeta();
      video.src = src.url;
      if (src.upgrade) schedulePoll(P);
      return;
    }
    let engine = src.engine;
    if (engine === "hlsjs") {
      const Hls = await loadHlsJs();
      if (P.closed || P.src !== src) return;
      if (!Hls || !Hls.isSupported || !Hls.isSupported()) {
        if (P.caps.nativeHls && P.caps.nativeHevc) engine = "native";
        else if (src.fallback) { setNote(P, ""); start(P, src.fallback, fileMs, paused); return; }
        else { fail(P, "Не удалось запустить плеер."); return; }
      } else {
        const hls = new Hls({ startPosition: at > 0 ? at : -1, enableWorker: true, capLevelToPlayerSize: true });
        P.hls = hls;
        const E = Hls.Events;
        hls.on(E.MANIFEST_PARSED, () => { fillTracks(P); go(); });
        hls.on(E.AUDIO_TRACKS_UPDATED, () => fillTracks(P));
        hls.on(E.SUBTITLE_TRACKS_UPDATED, () => fillTracks(P));
        hls.on(E.FRAG_LOADED, () => { P.errState.net = 0; });
        hls.on(E.ERROR, (_e, data) => onHlsError(P, hls, data));
        hls.loadSource(src.url);
        hls.attachMedia(video);
        return;
      }
    }
    // нативный HLS (Safari)
    seekOnMeta();
    video.src = src.url;
  }

  function onHlsError(P, hls, data) {
    if (P.hls !== hls || P.closed) return;
    const act = hlsErrorAction(data, P.errState);
    if (act === "ignore") return;
    if (act === "retry") {
      P.errState.net++;
      setTimeout(() => { if (P.hls === hls) hls.startLoad(); }, 1000 * P.errState.net);
    } else if (act === "refresh") {
      P.errState.refreshed = true;
      refreshAndRestart(P);
    } else if (act === "recover") {
      P.errState.media = 1;
      hls.recoverMediaError();
    } else if (act === "swap") {
      P.errState.media = 2;
      if (hls.swapAudioCodec) hls.swapAudioCodec();
      hls.recoverMediaError();
    } else {
      fallback(P);
    }
  }

  function onVideoError(P) {
    if (P.closed || P.hls || !P.video.getAttribute("src")) return; // ошибки hls.js идут через onHlsError
    if (P.src && P.src.kind === "hls") fallback(P);
    else fail(P, "Не удалось воспроизвести фильм в браузере.");
  }

  function fallback(P) {
    const pos = fileMsNow(P);
    const fb = P.src && P.src.fallback;
    if (fb) {
      setNote(P, "Переключились на прямой файл.");
      start(P, fb, pos, P.video.paused);
    } else {
      fail(P, "Не удалось воспроизвести фильм в браузере.");
    }
  }

  // Подпись истекла (410/403) или сеть сменилась — новая ссылка на тот же ассет.
  async function refreshAndRestart(P) {
    const pos = fileMsNow(P), paused = P.video.paused;
    let r = null;
    try { r = await requestVariant(P.ctx.api, P.ctx.magnet.id, P.ctx.file.path); } catch (e) { /* ниже */ }
    if (P.closed) return;
    const next = r ? pickSource(r, P.caps) : null;
    if (next && next.kind === "hls") { P.resp = r; start(P, next, pos, paused); }
    else fallback(P);
  }

  // Смотрим зеркало, а HLS ещё готовится: не стал ли готов. Переключаем не на лету — на паузе
  // (или при следующем запуске / следующей серии, там окно фильма само спросит variant-url).
  function schedulePoll(P) {
    clearTimeout(P.pollTimer);
    P.pollTimer = setTimeout(async () => {
      if (P.closed || !P.src || P.src.kind !== "direct") return;
      let r = null;
      try { r = await requestVariant(P.ctx.api, P.ctx.magnet.id, P.ctx.file.path); } catch (e) { /* следующий раз */ }
      if (P.closed || !P.src || P.src.kind !== "direct") return;
      const next = r ? pickSource(r, P.caps) : null;
      if (next && next.kind === "hls") {
        next.fallback = next.fallback || P.src;
        P.pending = next;
        setNote(P, "Версия для браузера готова — включим, когда поставите на паузу.");
        if (P.video.paused && !P.video.ended) switchTo(P, next, true);
        return;
      }
      schedulePoll(P);
    }, POLL_PLAYING_MS);
  }

  function switchTo(P, src, paused) {
    const pos = fileMsNow(P);
    P.pending = null;
    setNote(P, "");
    start(P, src, pos, paused);
  }

  // ---------- Дорожки ----------

  function listTracks(P) {
    const v = P.video;
    if (P.hls) {
      const a = (P.hls.audioTracks || []).map(t => ({ name: t.name || "", lang: t.lang || "", default: !!t.default }));
      const s = (P.hls.subtitleTracks || []).map(t => ({ name: t.name || "", lang: t.lang || "", default: !!t.default }));
      return { audio: a, subs: s, audioNow: P.hls.audioTrack, subNow: P.hls.subtitleTrack };
    }
    const a = [], s = [];
    let audioNow = -1, subNow = -1;
    if (v.audioTracks) {
      for (let i = 0; i < v.audioTracks.length; i++) {
        const t = v.audioTracks[i];
        a.push({ name: t.label || "", lang: t.language || "", default: false });
        if (t.enabled) audioNow = i;
      }
    }
    if (v.textTracks) {
      for (let i = 0; i < v.textTracks.length; i++) {
        const t = v.textTracks[i];
        if (t.kind !== "subtitles" && t.kind !== "captions") continue;
        s.push({ name: t.label || "", lang: t.language || "", default: false, idx: i });
        if (t.mode === "showing") subNow = s.length - 1;
      }
    }
    return { audio: a, subs: s, audioNow, subNow };
  }

  function trackLabel(t, i, kind) {
    return t.name || (t.lang ? t.lang.toUpperCase() : (kind === "audio" ? "Дорожка " : "Субтитры ") + (i + 1));
  }

  function fillTracks(P) {
    if (P.closed) return;
    const L = listTracks(P);
    const fill = (sel, list, now, kind) => {
      sel.replaceChildren();
      if (kind === "sub") { const o = el("option", "", "Без субтитров"); o.value = "-1"; sel.appendChild(o); }
      list.forEach((t, i) => { const o = el("option", "", trackLabel(t, i, kind)); o.value = String(i); sel.appendChild(o); });
      sel.value = String(now >= 0 ? now : (kind === "sub" ? -1 : 0));
      sel.hidden = kind === "audio" ? list.length < 2 : list.length === 0;
    };
    // прошлый выбор (до переключения зеркало → HLS) или DEFAULT из master
    const wantA = pickTrack(L.audio, P.choice.audio, 0);
    if (wantA >= 0 && wantA !== L.audioNow && L.audio.length > 1) { applyAudio(P, wantA); L.audioNow = wantA; }
    const wantS = pickTrack(L.subs, P.choice.sub, -1);
    if (wantS !== L.subNow && (P.choice.sub || wantS >= 0)) { applySub(P, wantS, L.subs); L.subNow = wantS; }
    fill(P.audioSel, L.audio, L.audioNow, "audio");
    fill(P.subSel, L.subs, L.subNow, "sub");
  }

  function applyAudio(P, i) {
    if (P.hls) { P.hls.audioTrack = i; return; }
    const at = P.video.audioTracks;
    if (at) for (let k = 0; k < at.length; k++) at[k].enabled = k === i;
  }
  function applySub(P, i, subs) {
    if (P.hls) { P.hls.subtitleDisplay = i >= 0; P.hls.subtitleTrack = i; return; }
    const tt = P.video.textTracks;
    if (!tt) return;
    const target = i >= 0 && subs[i] ? subs[i].idx : -1;
    for (let k = 0; k < tt.length; k++) {
      if (tt[k].kind === "subtitles" || tt[k].kind === "captions") tt[k].mode = k === target ? "showing" : "disabled";
    }
  }
  function setAudio(P, i) {
    const L = listTracks(P);
    const t = L.audio[i];
    if (!t) return;
    P.choice.audio = { name: t.name, lang: t.lang };
    applyAudio(P, i);
  }
  function setSub(P, i) {
    const L = listTracks(P);
    P.choice.sub = i < 0 ? { off: true } : (L.subs[i] ? { name: L.subs[i].name, lang: L.subs[i].lang } : null);
    applySub(P, i, L.subs);
  }

  // ---------- Позиция ----------

  // Как у приложений: POST /api/sync {watch:[{key:"<id раздачи>|<путь>", pos, len, at}]} во времени файла раздачи.
  // Сервер принимает только вход через Discord (по общему паролю — 403): тогда больше не шлём.
  function saveProgress(P, force) {
    if (P.syncOff || P.closed || !P.src) return;
    const v = P.video;
    if (!(v.currentTime > 0)) return;
    const pos = fileMsNow(P);
    if (!force && P.lastSaved >= 0 && Math.abs(pos - P.lastSaved) < 5000) return;
    if (pos < 5000 && P.lastSaved < 0) return; // только что открыли — не затираем место
    P.lastSaved = pos;
    const dur = Number.isFinite(v.duration) ? Math.round(v.duration * 1000) : 0;
    const len = dur > 0 ? toFileMs(dur, P.shiftMs) : 0;
    const body = JSON.stringify({ watch: [{ key: P.key, pos, len, at: Date.now() }] });
    let req;
    try { req = P.ctx.api("/api/sync", { method: "POST", body, keepalive: true }); } catch (e) { return; }
    if (req && req.catch) req.catch(e => { if (/Discord|403/.test(String(e && e.message))) P.syncOff = true; });
  }

  return {
    pickSource, directMime, hlsEngine, toHlsMs, toFileMs, etaMinutes, messageFor,
    parseMasterMedia, pickTrack, hlsErrorAction, resumeMs,
    attach, detectCaps
  };
});

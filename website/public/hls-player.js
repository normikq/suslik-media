/* Встроенный плеер сайта: HLS (hls.js, в Safari — нативно) и прямой файл через зеркало.
 * Контракт ответа POST /api/file/variant-url — docs/HLS.md §9–10, устройство и ручная проверка — docs/HLS-CLIENT.md «T5».
 * Два режима на общем коде: attach() — кнопка и плеер поверх окна фильма (index.html), mount() — плеер внутри
 * элемента страницы фильма /watch/<id> (контракт — docs/HLS-CLIENT.md «Встраиваемый режим»).
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
   * hls из архива (docs/HLS.md §20, hls.from_archive) — fromArchive: true, источник выбирается как обычно.
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
        const out = { kind: "hls", url: hls.url, engine, shiftMs: shiftOf(hls.shift_ms), profile: hls.profile || "v1", fallback: direct };
        if (hls.from_archive === true) out.fromArchive = true;
        return out;
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

  /** Подсказка, когда HLS идёт прямо из архива (docs/HLS.md §20). */
  const ARCHIVE_NOTE = "Из архива: старт и перемотка медленнее";

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

  function trackLabel(t, i, kind) {
    return (t && t.name) || (t && t.lang ? t.lang.toUpperCase() : (kind === "audio" ? "Дорожка " : "Субтитры ") + (i + 1));
  }

  // ---------- Качество (чистые функции) ----------

  const QUALITY_KEY = "suslik.hls.quality"; // localStorage: "auto" или подпись уровня ("1080p")
  const STD_LEVELS = [
    { label: "4K", w: 3840, h: 2160 },
    { label: "1440p", w: 2560, h: 1440 },
    { label: "1080p", w: 1920, h: 1080 },
    { label: "720p", w: 1280, h: 720 },
    { label: "480p", w: 854, h: 480 },
    { label: "360p", w: 640, h: 360 },
    { label: "240p", w: 426, h: 240 }
  ];

  /**
   * Подпись уровня hls.js ({width, height}) по ближайшему стандарту. Кадр часто обрезан по высоте
   * (1920×800, 1280×534) или по ширине — поэтому хватает, чтобы ширина или высота была ≥ 85 % стандарта:
   * 1040 → 1080p, 694 → 720p, 2160 → 4K. Размера нет — "".
   */
  function levelLabel(level) {
    const l = level || {};
    const w = Number(l.width) || 0, h = Number(l.height) || 0;
    if (w <= 0 && h <= 0) return "";
    for (const s of STD_LEVELS) if (w >= s.w * 0.85 || h >= s.h * 0.85) return s.label;
    return (h > 0 ? h : Math.round(w * 9 / 16)) + "p";
  }

  function levelBitrate(l) { return Number(l && (l.averageBitrate || l.bitrate || l.maxBitrate)) || 0; }

  /** Уровни для меню, от лучшего к худшему: [{index (в hls.levels), height, label}]. Одинаковые подписи различает битрейт. */
  function levelList(levels) {
    const list = (Array.isArray(levels) ? levels : []).map((l, index) => {
      const w = Number(l && l.width) || 0, h = Number(l && l.height) || 0;
      return { index, height: h, area: w && h ? w * h : h * h, bitrate: levelBitrate(l), label: levelLabel(l) || "Уровень " + (index + 1) };
    });
    list.sort((a, b) => (b.area - a.area) || (b.bitrate - a.bitrate) || (a.index - b.index));
    const count = {};
    list.forEach(x => { count[x.label] = (count[x.label] || 0) + 1; });
    list.forEach(x => {
      if (count[x.label] > 1 && x.bitrate) x.label += " · " + (Math.round(x.bitrate / 1e5) / 10) + " Мбит";
    });
    return list.map(x => ({ index: x.index, height: x.height, label: x.label }));
  }

  /** Меню качества — только у hls.js и только если уровней больше одного (Safari и зеркало уровней не дают, у v1-copy уровень один). */
  function showQualityMenu(levels, engine) {
    return engine === "hlsjs" && Array.isArray(levels) && levels.length > 1;
  }

  /** Прошлый выбор качества: "auto", подпись уровня или null (не выбирали / хранилище недоступно). */
  function readQuality(storage) {
    try {
      const v = storage && storage.getItem(QUALITY_KEY);
      return v ? String(v) : null;
    } catch (e) { return null; }
  }
  /** Запомнить выбор: подпись уровня или "auto" (null, "", -1 — тоже авто). true — записалось. */
  function saveQuality(storage, label) {
    const v = label && label !== -1 ? String(label) : "auto";
    try {
      if (!storage) return false;
      storage.setItem(QUALITY_KEY, v);
      return true;
    } catch (e) { return false; }
  }

  /** Индекс уровня для прошлого выбора (по подписи; из одинаковых — с большим битрейтом), иначе -1 (авто). */
  function startLevelFor(levels, saved) {
    if (!saved || saved === "auto" || !Array.isArray(levels)) return -1;
    let best = -1;
    levels.forEach((l, i) => {
      if (levelLabel(l) !== saved) return;
      if (best < 0 || levelBitrate(l) > levelBitrate(levels[best])) best = i;
    });
    return best;
  }

  /** Текст пункта «Авто»: «Авто (сейчас 1080p)», пока уровень не известен — «Авто». */
  function autoLabel(levels, levelNow) {
    const l = Array.isArray(levels) && levelNow >= 0 ? levels[levelNow] : null;
    const t = l ? levelLabel(l) : "";
    return t ? "Авто (сейчас " + t + ")" : "Авто";
  }

  /**
   * Настройки hls.js. Загрузка стартует вручную (autoStartLoad: false) — после MANIFEST_PARSED, когда известны уровни
   * и можно поставить запомненное качество до первого сегмента.
   */
  function hlsConfig(startSec) {
    return {
      startPosition: startSec > 0 ? startSec : -1,
      autoStartLoad: false,
      enableWorker: true,
      startLevel: -1, // авто: по оценке канала (abrEwmaDefaultEstimate), а не «первый уровень из манифеста»
      // capLevelToPlayerSize не ставим: на странице фильма плеер уже 1920 пикселей, и hls.js держал 720p даже на хорошем
      // канале (жалоба «качество не переключается»). Наши файлы лёгкие (v1: 1080p ≤ 5 Мбит), «Авто» решает по скорости канала.
      // Назад 90 с остаётся в памяти — короткая перемотка назад («что он сказал?») без повторной загрузки.
      // По умолчанию у hls.js назад хранится всё (Infinity): на двухчасовом фильме упираемся в квоту MediaSource
      // (~150 МБ на видео в Chrome) и браузер сам выкидывает куски. 90 с при 5 Мбит ≈ 56 МБ.
      backBufferLength: 90,
      // Вперёд 30 с обычно, до 120 с на быстром канале: хватает пережить провал скорости, а вместе с буфером
      // назад остаётся в квоте (≈ 56 + 75 МБ). Больше (по умолчанию 600 с) — качаем то, что при перемотке выбросим.
      maxBufferLength: 30,
      maxMaxBufferLength: 120,
      // Первая оценка канала 5 Мбит (по умолчанию 0,5 Мбит — старт с худшего уровня). ABR сравнивает с
      // AVERAGE-BANDWIDTH × 0,95: у 1080p v1 это ≈ 3,6 Мбит, так что на нормальном канале сразу 1080p,
      // на слабом первый же сегмент даст настоящую оценку и ABR опустится до 720p.
      abrEwmaDefaultEstimate: 5000000
    };
  }

  /** Сообщение, когда в ответе нет блока hls (HLS выключен или пользователь не в бете). */
  const LEGACY_MESSAGE = "Просмотр в браузере пока недоступен";

  /**
   * Состояние для onState встраиваемого плеера (страница фильма). Чистая функция.
   * s: {source (pickSource), error (текст), engine ("hlsjs"|"native"|null), levels (hls.levels), level (-1 авто),
   *     levelNow (что играет), tracks {audio, subs, audioNow, subNow} (listTracks), note (текст под плеером)}.
   * Возвращает {kind: hls|mirror|preparing|unsupported|error, message?, etaMin?, fromArchive?, levels, level, levelNow,
   *             qualityLabel, audio, audioIndex, subtitles, subtitleIndex} или null (ещё ничего не известно).
   * fromArchive: true — HLS идёт прямо из архива (§20), страница может показать ARCHIVE_NOTE.
   */
  function playerState(s) {
    const x = s || {};
    if (x.error) return { kind: "error", message: String(x.error) };
    const src = x.source;
    if (!src) return null;
    switch (src.kind) {
      case "wait": return { kind: "preparing", message: messageFor(src), etaMin: src.etaMin || null };
      case "soon": return { kind: "preparing", message: messageFor(src), etaMin: null };
      case "unsupported": return { kind: "unsupported", message: messageFor(src) };
      case "legacy": return { kind: "unsupported", message: LEGACY_MESSAGE };
      case "hls": case "direct": break;
      default: return null;
    }
    const st = { kind: src.kind === "hls" ? "hls" : "mirror" };
    if (x.note) st.message = String(x.note);
    if (src.kind === "hls" && src.fromArchive === true) st.fromArchive = true;
    const q = src.kind === "hls" && showQualityMenu(x.levels, x.engine);
    st.levels = q ? levelList(x.levels) : [];
    st.level = q && x.level >= 0 && x.level < x.levels.length ? x.level : -1;
    st.levelNow = q && x.levelNow >= 0 && x.levelNow < x.levels.length ? x.levelNow : -1;
    st.qualityLabel = q ? (st.level >= 0 ? levelLabel(x.levels[st.level]) : autoLabel(x.levels, st.levelNow)) : "";
    const t = x.tracks || {};
    const audio = Array.isArray(t.audio) ? t.audio : [], subs = Array.isArray(t.subs) ? t.subs : [];
    st.audio = audio.map((a, i) => ({ index: i, name: trackLabel(a, i, "audio"), lang: (a && a.lang) || "" }));
    st.audioIndex = t.audioNow >= 0 && t.audioNow < audio.length ? t.audioNow : (audio.length ? 0 : -1);
    st.subtitles = subs.map((a, i) => ({ index: i, name: trackLabel(a, i, "sub"), lang: (a && a.lang) || "" }));
    st.subtitleIndex = t.subNow >= 0 && t.subNow < subs.length ? t.subNow : -1;
    return st;
  }

  /** startAt встраиваемого режима (секунды файла) → мс. */
  function secToMs(sec) {
    const n = Number(sec);
    return Number.isFinite(n) && n > 0 ? Math.round(n * 1000) : 0;
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

  function localStore() { try { return root.localStorage || null; } catch (e) { return null; } }

  // Цвета и шрифт — переменные redesign.css (с запасными значениями, если страница без него).
  const CSS = [
    "#playSheet .ps-hls{display:flex;flex-direction:column;gap:8px;margin:10px 0 0}",
    "#playSheet .ps-hls-note{font:400 13px var(--ui,system-ui);color:var(--muted,#8e96a8)}",
    "#playSheet .ps-hls-btn{display:flex;align-items:center;justify-content:center;gap:8px;height:44px;border-radius:12px;border:1px solid #2a3346;background:var(--film,#161c28);color:var(--text,#eef0f5);font:600 13.5px var(--ui,system-ui);cursor:pointer}",
    "#playSheet .ps-hls-btn:hover{border-color:#4d86ff}",
    "#playSheet .ps-hls-btn[disabled]{opacity:.45;pointer-events:none}",
    ".shls{position:fixed;inset:0;z-index:10000;background:#000;display:flex;flex-direction:column}",
    ".shls-player{display:flex;flex-direction:column;color:var(--text,#eef0f5);font:500 13px var(--ui,system-ui)}",
    ".shls .shls-player{flex:1 1 auto;min-height:0}",
    ".shls-stage{position:relative;background:#000}",
    ".shls .shls-stage{flex:1 1 auto;min-height:0}",
    ".shls-stage video{display:block;width:100%;height:100%;background:#000}",
    ".shls-embed{width:100%;min-width:0}",
    ".shls-embed .shls-stage{height:0;padding-top:56.25%;border-radius:14px;overflow:hidden;border:1px solid var(--line,#222838)}",
    ".shls-embed .shls-stage video{position:absolute;inset:0}",
    ".shls-screen{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;padding:16px;text-align:center;background:rgba(6,9,17,.88);font:500 14px/1.4 var(--ui,system-ui)}",
    ".shls-screen[hidden]{display:none}",
    ".shls-screen.err .shls-msg{color:#ff9b8f}",
    ".shls-bar{display:flex;flex-wrap:wrap;align-items:center;gap:8px;padding:8px 16px;background:var(--booth,#10141e)}",
    ".shls-embed .shls-bar{padding:8px 0 0;background:transparent}",
    ".shls-title{flex:1 1 160px;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}",
    ".shls-bar select,.shls-bar button,.shls-btn{height:34px;border-radius:10px;border:1px solid var(--line2,#2e374c);background:var(--film,#161c28);color:var(--text,#eef0f5);font:inherit;padding:0 10px;max-width:100%;cursor:pointer}",
    ".shls-bar select:hover,.shls-bar button:hover,.shls-btn:hover{border-color:var(--glass-edge-hover,rgba(96,165,250,.45))}",
    ".shls-bar select:focus-visible,.shls-bar button:focus-visible,.shls-btn:focus-visible{outline:2px solid var(--electric,#2563eb);outline-offset:1px}",
    ".shls-btn[disabled]{opacity:.45;pointer-events:none}",
    ".shls-note{padding:6px 16px;background:var(--booth,#10141e);color:var(--muted,#8e96a8);font:400 12.5px var(--ui,system-ui)}",
    ".shls-embed .shls-note{padding:6px 0 0;background:transparent}",
    ".shls-note:empty{display:none}",
    "@media (max-width:600px){.shls-bar{gap:6px;padding:6px 10px}.shls-title{flex-basis:100%}.shls-bar select{flex:1 1 0;min-width:0}}"
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

  /** Запрос к API по умолчанию (как api() в index.html): адрес от места hls-player.js — сервер может быть не в корне. */
  function defaultApi(path, opts) {
    const o = opts || {};
    const url = new URL(String(path).replace(/^\//, ""), SELF_SRC || document.baseURI).toString();
    const init = Object.assign({ credentials: "same-origin" }, o, { headers: Object.assign({ "Content-Type": "application/json" }, o.headers || {}) });
    return fetch(url, init).then(r => r.json().catch(() => ({ error: "Некорректный ответ сервера" })).then(data => {
      if (!r.ok) throw new Error(data.error || "Не удалось выполнить запрос (" + r.status + ")");
      return data;
    }));
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
      b.addEventListener("click", () => openOverlay(ctx, resp, src, caps));
      box.appendChild(b);
      if (src.fromArchive) box.appendChild(el("div", "ps-hls-note", ARCHIVE_NOTE));
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

  // ---------- Плеер (общий для оверлея и встраиваемого режима) ----------

  /**
   * Рисует плеер внутри o.host и возвращает его состояние P. Источник ставит loadFile().
   * o: {host, overlay, api, controls, autoplay, title, barStart: [элементы в начало панели],
   *     appButton(P) → кнопка «Открыть в приложении» или null, onState, onEnded, onTime}.
   */
  function createPlayer(o) {
    ensureStyle();
    const box = el("div", "shls-player" + (o.overlay ? "" : " shls-embed"));
    const stage = el("div", "shls-stage");
    const video = el("video");
    video.controls = true; // перемотка, громкость, полноэкранный режим — у браузера
    video.playsInline = true;
    video.setAttribute("playsinline", "");
    video.preload = "auto";
    if (o.title) video.setAttribute("aria-label", o.title);
    const screen = el("div", "shls-screen");
    screen.hidden = true;
    stage.append(video, screen);
    box.appendChild(stage);
    let qualitySel = null, audioSel = null, subSel = null;
    if (o.controls) {
      const bar = el("div", "shls-bar");
      (o.barStart || []).forEach(n => bar.appendChild(n));
      const sel = (label) => { const s = el("select"); s.setAttribute("aria-label", label); s.title = label; s.hidden = true; return s; };
      qualitySel = sel("Качество");
      audioSel = sel("Озвучка");
      subSel = sel("Субтитры");
      bar.append(qualitySel, audioSel, subSel);
      box.appendChild(bar);
    }
    const note = el("div", "shls-note");
    box.appendChild(note);
    o.host.appendChild(box);

    const P = {
      o, api: o.api, box, video, screen, note, qualitySel, audioSel, subSel, caps: detectCaps(),
      hls: null, engine: null, shiftMs: 0, src: null, view: null, resp: null, pending: null, closed: false,
      adId: "", path: "", key: "", startMs: 0, token: 0, error: "", noteText: "",
      errState: { net: 0, media: 0, refreshed: false },
      choice: { audio: null, sub: null }, level: -1, levelNow: -1,
      syncOff: false, lastSaved: -1, saveTimer: 0, pollTimer: 0, prepTimer: 0, lastState: "", unbinds: []
    };

    const onHide = () => saveProgress(P, true);
    window.addEventListener("pagehide", onHide);
    P.unbinds.push(() => window.removeEventListener("pagehide", onHide));
    if (qualitySel) qualitySel.addEventListener("change", () => setQuality(P, Number(qualitySel.value)));
    if (audioSel) audioSel.addEventListener("change", () => setAudio(P, Number(audioSel.value)));
    if (subSel) subSel.addEventListener("change", () => setSub(P, Number(subSel.value)));
    video.addEventListener("pause", () => {
      saveProgress(P, true);
      if (P.pending && !video.ended && !P.closed) switchTo(P, P.pending, true);
    });
    video.addEventListener("ended", () => {
      saveProgress(P, true);
      if (typeof o.onEnded === "function" && !P.closed) { try { o.onEnded(); } catch (e) { /* страница */ } }
    });
    video.addEventListener("timeupdate", () => {
      if (typeof o.onTime === "function" && P.src && !P.closed) { try { o.onTime(fileMsNow(P) / 1000); } catch (e) { /* страница */ } }
    });
    video.addEventListener("error", () => onVideoError(P));
    video.textTracks && video.textTracks.addEventListener && video.textTracks.addEventListener("addtrack", () => fillTracks(P));
    video.audioTracks && video.audioTracks.addEventListener && video.audioTracks.addEventListener("addtrack", () => fillTracks(P));
    P.saveTimer = setInterval(() => { if (!video.paused) saveProgress(P, false); }, SAVE_EVERY_MS);
    return P;
  }

  function destroyPlayer(P) {
    if (P.closed) return;
    saveProgress(P, true);
    P.closed = true;
    P.token++;
    clearInterval(P.saveTimer);
    clearTimers(P);
    teardown(P);
    P.unbinds.forEach(f => f());
    P.box.remove();
  }

  function clearTimers(P) {
    clearTimeout(P.pollTimer);
    clearTimeout(P.prepTimer);
  }

  function teardown(P) {
    if (P.hls) { try { P.hls.destroy(); } catch (e) { /* уже */ } P.hls = null; }
    P.engine = null;
    P.levelNow = -1;
    try { P.video.pause(); } catch (e) { /* нет */ }
    P.video.removeAttribute("src");
    while (P.video.firstChild) P.video.removeChild(P.video.firstChild);
    try { P.video.load(); } catch (e) { /* нет */ }
  }

  function fileMsNow(P) {
    const t = P.video.currentTime;
    return toFileMs(Number.isFinite(t) ? t * 1000 : 0, P.shiftMs);
  }

  /** Сообщить странице состояние (только если оно поменялось). */
  function emit(P) {
    if (P.closed || typeof P.o.onState !== "function") return;
    const playing = P.view && (P.view.kind === "hls" || P.view.kind === "direct");
    const st = playerState({
      source: P.view, error: P.error, engine: P.engine,
      levels: P.hls ? P.hls.levels : null, level: P.level, levelNow: P.levelNow,
      tracks: playing ? listTracks(P) : null, note: P.noteText
    });
    if (!st) return;
    const j = JSON.stringify(st);
    if (j === P.lastState) return;
    P.lastState = j;
    try { P.o.onState(st); } catch (e) { /* ошибка страницы не ломает плеер */ }
  }

  function setNote(P, text) {
    P.noteText = text || "";
    P.note.textContent = P.noteText;
    emit(P);
  }

  /** Экран поверх видео: «Готовим…», «Этот браузер не показывает…», ошибка; пустой текст — убрать. */
  function showScreen(P, text, opts) {
    const s = P.screen;
    s.replaceChildren();
    s.hidden = !text;
    s.classList.toggle("err", !!(opts && opts.err));
    if (!text) return;
    s.appendChild(el("div", "shls-msg", text));
    const b = opts && opts.app && typeof P.o.appButton === "function" ? P.o.appButton(P) : null;
    if (b) s.appendChild(b);
  }

  function fail(P, text) {
    teardown(P);
    clearTimers(P);
    P.src = null;
    P.error = text;
    showScreen(P, text + " Откройте в приложении.", { err: true, app: true });
    emit(P);
  }

  /**
   * Открыть файл (adId, path) с места startMs (время файла). given: {resp, src} — уже спрошенный variant-url (оверлей).
   * Ждём кодирования — экран с сообщением и опрос раз в минуту; готово — запуск.
   */
  async function loadFile(P, adId, path, startMs, given) {
    if (P.closed) return;
    const token = ++P.token;
    if (P.src) saveProgress(P, true);
    clearTimers(P);
    teardown(P);
    Object.assign(P, {
      adId: String(adId), path: String(path), key: String(adId) + "|" + path, startMs: startMs || 0,
      src: null, view: null, resp: null, pending: null, error: "", lastSaved: -1, level: -1,
      errState: { net: 0, media: 0, refreshed: false }
    });
    setNote(P, "");
    let resp = given && given.resp, src = given && given.src;
    if (!src) {
      showScreen(P, "Загружаем…");
      try { resp = await requestVariant(P.api, adId, path); } catch (e) {
        if (token === P.token) fail(P, "Не удалось получить ссылку на фильм.");
        return;
      }
      if (token !== P.token) return;
      src = pickSource(resp, P.caps);
    }
    P.resp = resp;
    if (src.kind === "hls" || src.kind === "direct") {
      showScreen(P, "");
      start(P, src, P.startMs, !P.o.autoplay);
      return;
    }
    P.view = src;
    showScreen(P, src.kind === "legacy" ? LEGACY_MESSAGE : messageFor(src), { app: true });
    emit(P);
    if (src.kind === "wait" || src.kind === "soon") schedulePrep(P, token);
  }

  // Файл ещё готовится (встраиваемый режим): раз в минуту спрашиваем, не готов ли; готов — запускаем.
  function schedulePrep(P, token) {
    clearTimeout(P.prepTimer);
    P.prepTimer = setTimeout(async () => {
      if (token !== P.token) return;
      let r = null;
      try { r = await requestVariant(P.api, P.adId, P.path); } catch (e) { /* следующий раз */ }
      if (token !== P.token) return;
      const next = r ? pickSource(r, P.caps) : P.view;
      if (next.kind === "hls" || next.kind === "direct") {
        P.resp = r;
        showScreen(P, "");
        start(P, next, P.startMs, !P.o.autoplay);
        return;
      }
      if (next.kind !== "legacy") {
        P.view = next;
        showScreen(P, messageFor(next), { app: true });
        emit(P);
      }
      schedulePrep(P, token);
    }, POLL_SHEET_MS);
  }

  /** Запустить источник с места fileMs (время файла раздачи). */
  async function start(P, src, fileMs, paused) {
    teardown(P);
    P.src = src;
    P.view = src;
    P.error = "";
    P.shiftMs = src.kind === "hls" ? src.shiftMs : 0;
    P.errState = { net: 0, media: 0, refreshed: P.errState.refreshed };
    // §20: в оверлее подсказка под плеером; встраиваемому режиму хватает fromArchive в onState
    if (P.o.overlay && src.fromArchive && !P.noteText) setNote(P, ARCHIVE_NOTE);
    else if (P.noteText === ARCHIVE_NOTE && !src.fromArchive) setNote(P, "");
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
      fillTracks(P);
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
        const hls = new Hls(hlsConfig(at));
        P.hls = hls;
        P.engine = "hlsjs";
        const E = Hls.Events;
        hls.on(E.MANIFEST_PARSED, () => {
          if (P.hls !== hls) return;
          // запомненное качество ставим до первого сегмента; нет такого уровня — авто
          const lv = startLevelFor(hls.levels, readQuality(localStore()));
          P.level = showQualityMenu(hls.levels, "hlsjs") ? lv : -1;
          if (P.level >= 0) { hls.startLevel = P.level; hls.loadLevel = P.level; }
          hls.startLoad(at > 0 ? at : -1);
          fillTracks(P);
          go();
        });
        hls.on(E.LEVEL_SWITCHED, (_e, data) => {
          if (P.hls !== hls) return;
          P.levelNow = data && data.level >= 0 ? data.level : -1;
          fillQuality(P);
          emit(P);
        });
        hls.on(E.AUDIO_TRACKS_UPDATED, () => fillTracks(P));
        hls.on(E.SUBTITLE_TRACKS_UPDATED, () => fillTracks(P));
        hls.on(E.FRAG_LOADED, () => { P.errState.net = 0; });
        hls.on(E.ERROR, (_e, data) => onHlsError(P, hls, data));
        hls.loadSource(src.url);
        hls.attachMedia(video);
        emit(P);
        return;
      }
    }
    // нативный HLS (Safari): уровни выбирает сам браузер, меню качества нет
    P.engine = "native";
    seekOnMeta();
    video.src = src.url;
    fillTracks(P);
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
    const pos = fileMsNow(P), paused = P.video.paused, token = P.token;
    let r = null;
    try { r = await requestVariant(P.api, P.adId, P.path); } catch (e) { /* ниже */ }
    if (P.closed || token !== P.token) return;
    const next = r ? pickSource(r, P.caps) : null;
    if (next && next.kind === "hls") { P.resp = r; start(P, next, pos, paused); }
    else fallback(P);
  }

  // Смотрим зеркало, а HLS ещё готовится: не стал ли готов. Переключаем не на лету — на паузе
  // (или при следующем запуске / следующей серии, там окно фильма само спросит variant-url).
  function schedulePoll(P) {
    clearTimeout(P.pollTimer);
    const token = P.token;
    P.pollTimer = setTimeout(async () => {
      if (P.closed || token !== P.token || !P.src || P.src.kind !== "direct") return;
      let r = null;
      try { r = await requestVariant(P.api, P.adId, P.path); } catch (e) { /* следующий раз */ }
      if (P.closed || token !== P.token || !P.src || P.src.kind !== "direct") return;
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

  // ---------- Качество ----------

  /** Выбор качества: индекс в hls.levels или -1 (авто). Запоминается для следующих запусков. */
  function setQuality(P, i) {
    const hls = P.hls;
    if (!hls || P.closed || !showQualityMenu(hls.levels, P.engine)) return;
    const idx = Number(i);
    if (!(idx === -1 || (Number.isInteger(idx) && idx >= 0 && idx < hls.levels.length))) return;
    P.level = idx;
    saveQuality(localStore(), idx >= 0 ? levelLabel(hls.levels[idx]) : "auto");
    // Уровень — сразу: hls.js сбрасывает буфер вперёд и грузит ближайший сегмент нового качества.
    // Авто — без сброса: ABR решает со следующего сегмента.
    if (idx >= 0) hls.currentLevel = idx;
    else hls.loadLevel = -1;
    fillQuality(P);
    emit(P);
  }

  function fillQuality(P) {
    const sel = P.qualitySel;
    if (!sel) return;
    const levels = P.hls ? P.hls.levels : null;
    if (!showQualityMenu(levels, P.engine)) { sel.hidden = true; return; }
    sel.replaceChildren();
    const auto = el("option", "", autoLabel(levels, P.levelNow));
    auto.value = "-1";
    sel.appendChild(auto);
    levelList(levels).forEach(l => { const o = el("option", "", l.label); o.value = String(l.index); sel.appendChild(o); });
    sel.value = String(P.level >= 0 ? P.level : -1);
    sel.hidden = false;
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

  function fillTracks(P) {
    if (P.closed) return;
    const L = listTracks(P);
    const fill = (sel, list, now, kind) => {
      if (!sel) return;
      sel.replaceChildren();
      if (kind === "sub") { const o = el("option", "", "Без субтитров"); o.value = "-1"; sel.appendChild(o); }
      list.forEach((t, i) => { const o = el("option", "", trackLabel(t, i, kind)); o.value = String(i); sel.appendChild(o); });
      sel.value = String(now >= 0 ? now : (kind === "sub" ? -1 : 0));
      sel.hidden = kind === "audio" ? list.length < 2 : list.length === 0;
    };
    // прошлый выбор (до переключения зеркало → HLS или в прошлой серии) или DEFAULT из master
    const wantA = pickTrack(L.audio, P.choice.audio, 0);
    if (wantA >= 0 && wantA !== L.audioNow && L.audio.length > 1) { applyAudio(P, wantA); L.audioNow = wantA; }
    const wantS = pickTrack(L.subs, P.choice.sub, -1);
    if (wantS !== L.subNow && (P.choice.sub || wantS >= 0)) { applySub(P, wantS, L.subs); L.subNow = wantS; }
    fill(P.audioSel, L.audio, L.audioNow, "audio");
    fill(P.subSel, L.subs, L.subNow, "sub");
    fillQuality(P);
    emit(P);
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
    if (P.closed) return;
    const L = listTracks(P);
    const t = L.audio[i];
    if (!t) return;
    P.choice.audio = { name: t.name, lang: t.lang };
    applyAudio(P, i);
    if (P.audioSel) P.audioSel.value = String(i);
    emit(P);
  }
  function setSub(P, i) {
    if (P.closed) return;
    const L = listTracks(P);
    if (i >= 0 && !L.subs[i]) return;
    P.choice.sub = i < 0 ? { off: true } : { name: L.subs[i].name, lang: L.subs[i].lang };
    applySub(P, i, L.subs);
    if (P.subSel) P.subSel.value = String(i < 0 ? -1 : i);
    emit(P);
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
    try { req = P.api("/api/sync", { method: "POST", body, keepalive: true }); } catch (e) { return; }
    if (req && req.catch) req.catch(e => { if (/Discord|403/.test(String(e && e.message))) P.syncOff = true; });
  }

  // ---------- Оверлей из окна фильма ----------

  let current = null;

  function openOverlay(ctx, resp, src, caps) {
    if (current) closeOverlay(current);
    ensureStyle();
    const ov = el("div", "shls");
    ov.setAttribute("role", "dialog");
    ov.setAttribute("aria-modal", "true");
    ov.setAttribute("aria-label", "Плеер");
    document.body.appendChild(ov);
    const close = el("button", "", "Закрыть");
    close.type = "button";
    const titleText = (document.querySelector("#playSheet .ps-title") || {}).textContent || (ctx.file && ctx.file.name) || "";
    const P = createPlayer({
      host: ov, overlay: true, api: ctx.api, controls: true, autoplay: true, title: titleText,
      barStart: [close, el("div", "shls-title", titleText)],
      appButton: P => {
        const b = appButton(ctx.sheet);
        b.className = "shls-btn";
        b.addEventListener("click", () => closeOverlay(P));
        return b;
      }
    });
    P.caps = caps;
    P.ov = ov;
    current = P;
    const onKey = e => {
      if (e.key !== "Escape" || P.closed) return;
      if (document.fullscreenElement) return; // первый Esc — выход из полноэкранного
      e.stopImmediatePropagation();
      e.preventDefault();
      closeOverlay(P);
    };
    window.addEventListener("keydown", onKey, true);
    P.unbinds.push(() => window.removeEventListener("keydown", onKey, true));
    close.addEventListener("click", () => closeOverlay(P));
    loadFile(P, ctx.magnet.id, ctx.file.path, resumeMs(ctx.watch), { resp, src });
  }

  function closeOverlay(P) {
    destroyPlayer(P);
    if (P.ov) P.ov.remove();
    if (current === P) current = null;
  }

  // ---------- Встраиваемый режим (страница фильма /watch/<id>) ----------

  /**
   * SuslikHls.mount(container, opts) → controller. Плеер 16:9 внутри container, без оверлея.
   * opts: {adId, path, title?, startAt? (сек файла), autoplay?, api?, controls?, onState?(state), onEnded?(), onTime?(sec),
   *        onOpenApp?() — если задано, на экранах «Готовим…»/ошибки есть кнопка «Открыть в приложении»}.
   * controller: {load(adId, path, startAt?), setQuality(index|-1), setAudio(index), setSubtitle(index|-1),
   *              play(), pause(), currentTime() → сек файла, destroy()}.
   */
  function mount(container, opts) {
    if (!hasDom || !container || typeof container.appendChild !== "function") throw new Error("SuslikHls.mount: нужен элемент-контейнер");
    const o = opts || {};
    const P = createPlayer({
      host: container, overlay: false,
      api: typeof o.api === "function" ? o.api : defaultApi,
      controls: o.controls === true, autoplay: !!o.autoplay, title: o.title || "",
      barStart: o.controls === true && o.title ? [el("div", "shls-title", String(o.title))] : [],
      onState: o.onState, onEnded: o.onEnded, onTime: o.onTime,
      appButton: typeof o.onOpenApp === "function" ? () => {
        const b = el("button", "shls-btn", "Открыть в приложении");
        b.type = "button";
        b.addEventListener("click", () => { try { o.onOpenApp(); } catch (e) { /* страница */ } });
        return b;
      } : null
    });
    if (o.adId != null && o.path != null) loadFile(P, o.adId, o.path, secToMs(o.startAt));
    return {
      load(adId, path, startAt) { if (adId != null && path != null) loadFile(P, adId, path, secToMs(startAt)); },
      setQuality(i) { setQuality(P, i); },
      setAudio(i) { setAudio(P, Number(i)); },
      setSubtitle(i) { setSub(P, Number(i)); },
      play() {
        if (P.closed) return;
        P.o.autoplay = true; // ещё готовится — запустится, когда будет готово
        if (P.src) { const p = P.video.play(); if (p && p.catch) p.catch(() => {}); }
      },
      pause() {
        if (P.closed) return;
        P.o.autoplay = false;
        P.video.pause();
      },
      currentTime() { return P.src ? fileMsNow(P) / 1000 : P.startMs / 1000; },
      destroy() { destroyPlayer(P); }
    };
  }

  return {
    pickSource, directMime, hlsEngine, toHlsMs, toFileMs, etaMinutes, messageFor,
    parseMasterMedia, pickTrack, hlsErrorAction, resumeMs,
    levelLabel, levelList, showQualityMenu, readQuality, saveQuality, startLevelFor, autoLabel, hlsConfig,
    playerState, trackLabel, secToMs, QUALITY_KEY, ARCHIVE_NOTE,
    attach, mount, detectCaps
  };
});

/* Страница фильма сайта: /watch/<номер раздачи>?s=<сезон>&e=<серия> | ?f=<путь файла>, &t=<секунды>.
 * Сведения о фильме, выбор озвучки, субтитров, качества, версии, сезона и серии, встроенный плеер (hls-player.js).
 * Внешний файл (CSP: только 'self'); модель медиатеки — library-model.js (window.SuslikLibrary).
 * В Node грузится без DOM (module.exports) — чистые функции проверяет website/test/watch.test.js.
 * Устройство и ручная проверка — docs/HLS-CLIENT.md «Страница фильма». */
(function (root, factory) {
  "use strict";
  var mod = factory(root);
  if (typeof module === "object" && module && module.exports) module.exports = mod;
  else root.SuslikWatch = mod;
})(typeof window !== "undefined" ? window : globalThis, function (root) {
  "use strict";

  const NEXT_COUNTDOWN_S = 5;
  const LAST_EP_KEY = "lastEpisodeV1"; // как в index.html: id раздачи -> путь последней открытой серии
  const POSTER_STORE_KEY = "posterCacheV2"; // сохранённые ответы о постерах медиатеки (по ним сливаются версии)

  // ---------- Чистые функции (тесты) ----------

  /** Адрес страницы → номер раздачи, база сайта (/media) и корень API (как API_ROOT в index.html). */
  function sitePaths(pathname) {
    const p = String(pathname || "");
    const i = p.lastIndexOf("/watch/");
    if (i < 0) return { id: null, siteBase: "", apiRoot: "" };
    const siteBase = p.slice(0, i);
    const raw = p.slice(i + 7).split("/")[0];
    let id = null;
    try { id = decodeURIComponent(raw); } catch (e) { id = null; }
    return { id: /^\d{1,15}$/.test(id || "") ? id : null, siteBase, apiRoot: siteBase.replace(/\/media$/, "") };
  }

  /** ?f=<путь>&s=<сезон>&e=<серия>&t=<сек> → {f, s, e, t}; неверное — null. */
  function parseQuery(search) {
    const q = new URLSearchParams(String(search || "").replace(/^\?/, ""));
    const int = (v, max) => (v != null && /^\d{1,6}$/.test(v) && Number(v) <= max ? Number(v) : null);
    const f = q.get("f");
    let t = null;
    const rawT = q.get("t");
    if (rawT != null && /^\d+(?:\.\d+)?$/.test(rawT)) t = Math.floor(Number(rawT));
    return { f: f ? f : null, s: int(q.get("s"), 999), e: int(q.get("e"), 99999), t };
  }

  /** Вид раздачи для страницы: "movie" | "series" | "collection" | "none". */
  function kindOf(pl, isCollection) {
    if (!pl || pl.type === "none" || (pl.type === "movie" && !pl.file)) return "none";
    if (pl.type === "series") return isCollection ? "collection" : "series";
    return "movie";
  }

  /** Все пункты по порядку: у сериала — серии всех сезонов, у фильма — один пункт без сезона. */
  function flatItems(pl) {
    if (!pl) return [];
    if (pl.type === "series") return pl.seasons.flatMap(s => s.items);
    return pl.file ? [{ season: null, episode: null, file: pl.file }] : [];
  }

  /** Что открыть: ?f — по пути, ?s&e — серия, иначе сохранённый путь (savedPath), иначе первый пункт. */
  function pickItem(pl, q, savedPath) {
    const all = flatItems(pl);
    if (!all.length) return null;
    q = q || {};
    if (q.f) {
      const hit = all.find(x => x.file.path === q.f);
      if (hit) return hit;
    }
    if (q.s != null || q.e != null) {
      const season = pl.type === "series" ? pl.seasons.find(s => s.season === (q.s != null ? q.s : pl.seasons[0].season)) : null;
      if (season) {
        const hit = q.e != null ? season.items.find(x => x.episode === q.e) : season.items[0];
        if (hit) return hit;
      }
    }
    if (savedPath) {
      const hit = all.find(x => x.file.path === savedPath);
      if (hit) return hit;
    }
    return all[0];
  }

  /** Следующая серия (через границу сезона) или null. */
  function nextItem(pl, item) {
    const all = flatItems(pl);
    const i = item ? all.findIndex(x => x === item || x.file.path === item.file.path) : -1;
    return i >= 0 && i + 1 < all.length ? all[i + 1] : null;
  }

  /** Строка адреса для пункта: сериал — ?s=&e= (повтор номера в сезоне — ?f=), фильм — пусто. */
  function itemQuery(pl, item) {
    if (!pl || pl.type !== "series" || !item) return "";
    const season = pl.seasons.find(s => s.season === item.season);
    const same = season ? season.items.filter(x => x.episode === item.episode).length : 0;
    if (same === 1) return "?s=" + item.season + "&e=" + item.episode;
    return "?f=" + encodeURIComponent(item.file.path);
  }

  const basename = p => String(p || "").replace(/\\/g, "/").split("/").pop();

  /** Коротко о файле серии: имя без расширения и точек, не длиннее max. */
  function shortName(path, max) {
    max = max || 40;
    const s = basename(path).replace(/\.[a-z0-9]{2,5}$/i, "").replace(/[._]+/g, " ").replace(/\s+/g, " ").trim();
    return s.length > max ? s.slice(0, max - 1).trimEnd() + "…" : s;
  }

  function seasonName(n) { return n === 0 ? "Спецматериалы" : "Сезон " + n; }

  /** Заголовок блока просмотра: «Название 2 сезон 5 серия», у сборника — «Название — фильм», у фильма — название. */
  function headingText(title, item, kind) {
    if (!item || kind === "movie" || kind === "none") return title;
    if (kind === "collection") return title + " — " + (item.title || "Фильм " + item.episode);
    if (item.season === 0) return title + " · спецматериалы, " + item.episode;
    return title + " " + item.season + " сезон " + item.episode + " серия";
  }

  /** Подпись вкладки браузера. */
  function documentTitle(title, item, kind) {
    const t = title || "Фильм";
    if (kind === "series" && item && item.season != null)
      return t + " · S" + String(item.season).padStart(2, "0") + "E" + String(item.episode).padStart(2, "0") + " — Suslik Media";
    return t + " — Suslik Media";
  }

  /** Просмотр серии по /api/sync: {pct 0..100, done}; не смотрели — null. */
  function watchBadge(w) {
    if (!w || !(w.len > 0)) return null;
    const done = w.pos >= w.len - 15000;
    if (!done && !(w.pos >= 5000)) return null;
    return { done, pct: done ? 100 : Math.max(1, Math.min(99, Math.round((w.pos / w.len) * 100))) };
  }

  /** Озвучка: «Русский (rus)», без имени — «Дорожка N». */
  function trackLabel(t, i, word) {
    const name = t && typeof t.name === "string" ? t.name.trim() : "";
    const lang = t && typeof t.lang === "string" ? t.lang.trim() : "";
    if (name && lang && !name.toLowerCase().includes(lang.toLowerCase())) return name + " (" + lang + ")";
    return name || lang || (word || "Дорожка") + " " + (i + 1);
  }

  /** Пункты «Качество»: Авто (сейчас …) и уровни сверху вниз; уровней меньше двух — пусто (поле скрыто). */
  function qualityOptions(state) {
    const levels = state && Array.isArray(state.levels) ? state.levels : [];
    if (levels.length <= 1) return [];
    const label = l => (l && l.label) || (l && l.height ? l.height + "p" : "?");
    // levelNow — уровень, который играет сейчас; qualityLabel при «Авто» — готовая подпись плеера
    const nowIdx = typeof state.levelNow === "number" ? state.levelNow : typeof state.currentLevel === "number" ? state.currentLevel : -1;
    const now = levels.find(l => l.index === nowIdx);
    const auto = !(state.level >= 0) && typeof state.qualityLabel === "string" && /^Авто/.test(state.qualityLabel) ? state.qualityLabel : "";
    const out = [{ value: -1, text: auto || (now ? "Авто (сейчас " + label(now) + ")" : "Авто") }];
    for (const l of levels.slice().sort((a, b) => (b.height || 0) - (a.height || 0))) out.push({ value: l.index, text: label(l) });
    return out;
  }

  /** Пункты «Субтитры»: выкл и дорожки; дорожек нет — пусто (поле скрыто). */
  function subtitleOptions(state) {
    const subs = state && Array.isArray(state.subtitles) ? state.subtitles : [];
    if (!subs.length) return [];
    return [{ value: -1, text: "Выключены" }].concat(subs.map((s, i) => ({ value: s.index, text: trackLabel(s, i, "Субтитры") })));
  }

  /** Сообщение поверх плеера по его состоянию: null — плеер показывает видео сам. */
  function overlayFor(state) {
    if (!state || state.kind === "hls" || state.kind === "mirror") return null;
    if (state.kind === "preparing") {
      const eta = Number(state.etaMin);
      return { text: "Готовим для браузера" + (eta > 0 ? ", примерно " + Math.ceil(eta) + " мин" : "") + ". Пока можно открыть в приложении.", app: true };
    }
    if (state.kind === "unsupported") return { text: "Этот браузер не показывает такое видео.", app: true };
    return { text: state.message || "Не удалось открыть фильм.", app: true };
  }

  /** Внешний плеер для адреса зеркала (как playerHref в index.html): Android — intent, iOS — VLC, компьютер — PotPlayer. */
  function playerHref(url, ua) {
    ua = String(ua || "");
    if (/Android/i.test(ua)) {
      const u = new URL(url);
      return "intent://" + u.host + u.pathname + u.search + "#Intent;scheme=" + u.protocol.replace(":", "") +
        ";action=android.intent.action.VIEW;type=video/*;S.browser_fallback_url=" + encodeURIComponent(url) + ";end";
    }
    if (/iPhone|iPad|iPod/i.test(ua)) return "vlc-x-callback://x-callback-url/stream?url=" + encodeURIComponent(url);
    return "potplayer://" + url;
  }

  /** Качество раздачи для строки «В качестве» и подписи версии: 4K / 1080p / 720p. */
  function qualityOf(m) {
    const text = ((m && m.name) || "") + " " + ((m && m.files) || []).map(f => f.path || "").join(" ");
    if (/\b(?:2160p|4k|uhd)\b/i.test(text)) return "4K";
    if (/\b1080[pi]\b/i.test(text)) return "1080p";
    if (/\b720p\b/i.test(text)) return "720p";
    return "";
  }

  function formatBytes(value) {
    if (!value) return "";
    const units = ["Б", "КБ", "МБ", "ГБ", "ТБ"];
    let n = Number(value), i = 0;
    while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
    return (i === 0 ? String(Math.round(n)) : n.toFixed(1).replace(".", ",")) + " " + units[i];
  }

  /** Подпись версии: «4K · HDR · 58,2 ГБ». */
  function versionLabel(m, tags) {
    const parts = [qualityOf(m)].concat(tags || []).filter(Boolean);
    const size = formatBytes(m && m.size);
    if (size) parts.push(size);
    return parts.length ? parts.join(" · ") : String((m && m.name) || "Версия");
  }

  const groupDigits = n => String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, " ");

  function runtimeText(min, series) {
    const n = Number(min);
    if (!(n > 0)) return "";
    const h = Math.floor(n / 60), m = n % 60;
    const clock = (h ? h + " ч " : "") + (m || !h ? m + " мин" : "");
    return series ? clock + " (серия)" : n >= 60 ? n + " мин / " + clock : clock;
  }

  function dateText(iso) {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(iso || ""));
    if (!m) return "";
    const months = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"];
    return Number(m[3]) + " " + months[Number(m[2]) - 1] + " " + m[1] + " г.";
  }

  /**
   * Строки «подпись: значение» карточки фильма; пустые не попадают.
   * d — ответ /api/title (или {}), own — наше: {year, quality, sizeText, kind}.
   */
  function factRows(d, own) {
    d = d || {};
    own = own || {};
    const tv = d.type === "tv" || own.kind === "series";
    const rows = [];
    const add = (label, value) => { if (value) rows.push([label, value]); };
    if (d.rating) add("Рейтинг", d.rating.toFixed(1).replace(".", ",") + " TMDB" + (d.votes ? " (" + groupDigits(d.votes) + " голосов)" : ""));
    if (d.tagline) add("Слоган", "«" + d.tagline.replace(/^[«"]+|[»"]+$/g, "") + "»");
    add("Дата выхода", dateText(d.release_date) || (d.year || own.year ? String(d.year || own.year) + " г." : ""));
    add("Страна", (d.countries || []).join(", "));
    add(tv ? "Создатели" : "Режиссёр", (d.directors || []).join(", "));
    add("Жанр", (d.genres || []).join(", "));
    add("В качестве", own.quality);
    add("Возраст", d.age || "");
    add("Время", runtimeText(d.runtime_min, tv));
    const cast = d.cast || [];
    add("В ролях", cast.length ? cast.join(", ") + (d.cast_more ? " и другие" : "") : "");
    add("Размер", own.sizeText);
    return rows;
  }

  /** «Про что фильм «…»:» / «Про что сериал «…»:» */
  function aboutTitle(title, kind) {
    return (kind === "series" ? "Про что сериал «" : kind === "collection" ? "Про что «" : "Про что фильм «") + title + "»:";
  }

  // ---------- Страница ----------

  const hasDom = typeof document !== "undefined" && typeof window !== "undefined";
  const S = { ctl: null, item: null, pl: null, kind: "none", m: null, watch: new Map(), state: null, tags: [], next: null, mirror: new Map() };
  let P = { id: null, siteBase: "", apiRoot: "" };

  const $ = id => document.getElementById(id);
  const apiUrl = p => (typeof p === "string" && p.startsWith("/api/") ? P.apiRoot + p : p);
  async function api(path, options) {
    options = options || {};
    const response = await fetch(apiUrl(path), Object.assign({ credentials: "same-origin" }, options, {
      headers: Object.assign({ "Content-Type": "application/json" }, options.headers || {})
    }));
    const data = await response.json().catch(() => ({ error: "Некорректный ответ сервера" }));
    if (!response.ok) throw new Error(data.error || "Не удалось выполнить запрос (" + response.status + ")");
    return data;
  }
  const show = (el, on) => { if (el) el.classList.toggle("hidden", !on); };
  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function readJson(key) { try { return JSON.parse(localStorage.getItem(key) || "{}") || {}; } catch (e) { return {}; } }
  function saveLastEpisode(id, path) {
    try { const c = readJson(LAST_EP_KEY); c[id] = path; localStorage.setItem(LAST_EP_KEY, JSON.stringify(c)); } catch (e) { /* нет хранилища */ }
  }

  function only(sectionId) {
    for (const id of ["wpLoading", "wpLogin", "wpMissing", "wpPage"]) show($(id), id === sectionId);
  }
  function showMissing(text) {
    if (text) $("wpMissingText").textContent = text;
    document.title = "Не найдено — Suslik Media";
    only("wpMissing");
  }

  async function boot() {
    P = sitePaths(location.pathname);
    const home = P.siteBase + "/";
    for (const id of ["wpBack", "wpMissingBack", "wpLoginSite"]) $(id).href = home;
    if (!P.id) return showMissing("Неверный адрес страницы фильма.");
    let sess = null;
    try { sess = await api("/api/session"); } catch (e) { sess = null; }
    if (!sess || !sess.authenticated) {
      // Вход через Discord возвращает на эту страницу (return); вход по паролю — на главной, потом ссылка снова
      if (sess && sess.discord_enabled) {
        const a = $("wpLoginDiscord");
        a.href = P.apiRoot + "/api/auth/discord/start?return=" + encodeURIComponent(location.pathname + location.search);
        show(a, true);
      }
      document.title = "Вход — Suslik Media";
      return only("wpLogin");
    }
    let lib;
    try { lib = await api("/api/library"); } catch (e) { return showMissing(e.message); }
    const L = root.SuslikLibrary;
    const list = (lib.magnets || []).filter(m => !L.isVoiceOverOnly(m));
    L.setPosterDb(readJson(POSTER_STORE_KEY));
    const m = list.find(x => String(x.id) === P.id);
    if (!m) return showMissing();
    if (Number(m.statusCode) !== 4) return showMissing("Раздача ещё не готова: " + (m.status || "скачивается") + ". Загляните позже.");
    let pl;
    try { pl = L.buildPlaylist(m); } catch (e) { pl = null; }
    const kind = kindOf(pl, pl && L.isCollection(pl, m));
    if (kind === "none") return showMissing("В раздаче нет видео, которое можно открыть.");
    Object.assign(S, { m, pl, kind, list, L, title: L.tileTitle(m) });

    only("wpPage");
    renderHead({});
    renderVersions();
    loadTags();
    loadDetails();

    // Где остановились — до выбора серии (без ?s&e/?f): не дольше 3 с
    const q = parseQuery(location.search);
    await Promise.race([loadWatch(), new Promise(r => setTimeout(r, 3000))]);
    let saved = null;
    if (kind !== "movie") {
      let best = null;
      for (const it of flatItems(pl)) { const w = S.watch.get(m.id + "|" + it.file.path); if (w && (!best || w.at > best.at)) best = { at: w.at, path: it.file.path }; }
      saved = best ? best.path : readJson(LAST_EP_KEY)[m.id] || null;
    }
    const item = pickItem(pl, q, saved);
    if (kind !== "movie" && !location.search.match(/[?&](?:s|e|f)=/)) {
      const t = q.t != null ? "&t=" + q.t : "";
      const qs = itemQuery(pl, item);
      history.replaceState({ path: item.file.path }, "", location.pathname + (qs ? qs + t : t ? "?" + t.slice(1) : ""));
    }
    renderSeasons(item.season);
    select(item, { push: false, startAt: q.t, autoplay: false });

    window.addEventListener("popstate", () => {
      const it = pickItem(S.pl, parseQuery(location.search), null);
      if (it && it !== S.item) select(it, { push: false });
    });
    $("wpCopy").addEventListener("click", copyLink);
    $("wpNextCancel").addEventListener("click", cancelNext);
    $("wpNextNow").addEventListener("click", () => { const n = S.next && S.next.item; cancelNext(); if (n) select(n, { push: true, autoplay: true }); });
    $("wpQuality").addEventListener("change", e => { if (S.ctl) S.ctl.setQuality(Number(e.target.value)); });
    $("wpSubs").addEventListener("change", e => { if (S.ctl) S.ctl.setSubtitle(Number(e.target.value)); });
  }

  // ---------- Шапка и карточка ----------

  function renderHead(d) {
    S.details = d;
    const title = (d && d.found && d.title) || S.title;
    $("wpTitle").textContent = title;
    const orig = (d && d.original_title) || (S.m.aiTitle && S.m.aiTitle.status === "accepted" && S.m.aiTitle.original) || "";
    $("wpOrig").textContent = orig && orig.toLowerCase() !== title.toLowerCase() ? orig : "";
    show($("wpOrig"), $("wpOrig").textContent !== "");
    S.shownTitle = title;
    const tags = S.L.collapseTags(S.L.getItemTags(S.m));
    const quality = [qualityOf(S.m)].concat(tags).filter(Boolean).join(", ");
    const rows = factRows(d && d.found ? d : {}, {
      year: S.L.releaseYear(S.m), quality, sizeText: formatBytes(S.m.size), kind: S.kind
    });
    const tb = $("wpFacts");
    tb.replaceChildren();
    for (const [label, value] of rows) {
      const tr = el("tr");
      tr.append(el("th", null, label + ":"), el("td", null, value));
      tb.append(tr);
    }
    const badges = $("wpBadges");
    badges.replaceChildren();
    for (const b of [qualityOf(S.m)].concat(tags).filter(Boolean)) badges.append(el("span", "wp-badge", b));
    if (d && d.overview) {
      $("wpAboutTitle").textContent = aboutTitle(title, S.kind);
      $("wpOverview").textContent = d.overview;
      show($("wpAbout"), true);
    }
    renderNow();
  }

  function setPoster(url) {
    if (!url) return;
    const img = $("wpPosterImg");
    img.onload = () => { show(img, true); show($("wpPosterPh"), false); };
    img.src = apiUrl(url);
    img.alt = S.shownTitle || "";
  }

  async function loadDetails() {
    const pq = S.L.getPosterQueries(S.m)[0];
    const cachedPoster = S.L.posterPathOf(S.m);
    if (cachedPoster) setPoster(cachedPoster);
    let d = null;
    try { d = await api("/api/title/" + encodeURIComponent(S.m.id)); } catch (e) { d = null; }
    if (d && d.found) {
      renderHead(d);
      if (!cachedPoster && d.poster) setPoster(d.poster);
      return;
    }
    // Сведений TMDB нет (нет ключа, не нашлось, старый сервер) — то, что знает поиск постера: постер, описание, год, рейтинг
    if (!pq) return;
    try {
      const info = await api("/api/poster?hero=1&q=" + encodeURIComponent(pq));
      if (!cachedPoster && info.poster) setPoster(info.poster);
      const year = Number(info.year) || null;
      renderHead({ found: true, title: null, overview: info.overview || null, rating: Number(info.rating) || null, year, genres: info.genres || [], type: info.type });
      $("wpFactsNote").textContent = "Подробности о фильме сервер пока не нашёл.";
      show($("wpFactsNote"), !info.overview);
    } catch (e) { /* без описания */ }
  }

  function loadTags() {
    api("/api/tags").then(r => {
      if (!r || !r.tags) return;
      S.L.setServerTags(r.tags); // метки сервера по самому файлу (ffprobe) точнее, чем по названию
      renderHead(S.details || {});
      renderVersions();
    }).catch(() => {});
  }

  function loadWatch() {
    return api("/api/sync?since=0").then(d => {
      const map = new Map();
      for (const w of (d && d.watch) || []) map.set(String(w.key), w);
      S.watch = map;
      if (S.item) renderEpisodes();
    }).catch(() => {}); // вход по паролю — прогресса по аккаунту нет
  }

  // ---------- Версии, сезоны, серии ----------

  function renderVersions() {
    const { versions } = S.L.groupLibraryVersions(S.list);
    let group = null;
    for (const g of versions.values()) if (g.some(x => x.id === S.m.id)) group = g;
    const box = $("wpVersions");
    box.replaceChildren();
    show($("wpVersionsBox"), Boolean(group && group.length > 1));
    if (!group || group.length < 2) return;
    for (const v of group) {
      const tags = S.L.collapseTags(S.L.getItemTags(v));
      const a = el("a", "wp-chip" + (v.id === S.m.id ? " on" : ""), versionLabel(v, tags));
      a.href = "./" + encodeURIComponent(v.id);
      a.title = v.name || "";
      if (v.id === S.m.id) a.setAttribute("aria-current", "page");
      box.append(a);
    }
  }

  function renderSeasons(active) {
    const box = $("wpSeasons");
    box.replaceChildren();
    if (S.kind === "movie") { show($("wpSeasonsBox"), false); return; }
    show($("wpSeasonsBox"), true);
    S.seasonShown = active;
    if (S.kind === "series" && S.pl.seasons.length > 1) {
      for (const s of S.pl.seasons) {
        const b = el("button", "wp-tab" + (s.season === active ? " on" : ""), seasonName(s.season));
        b.type = "button";
        b.setAttribute("role", "tab");
        b.setAttribute("aria-selected", String(s.season === active));
        b.addEventListener("click", () => { S.seasonShown = s.season; renderSeasons(s.season); });
        box.append(b);
      }
    }
    show(box, box.childElementCount > 0);
    renderEpisodes();
  }

  function renderEpisodes() {
    const box = $("wpEpisodes");
    box.replaceChildren();
    if (S.kind === "movie") return;
    const season = S.kind === "series" ? S.pl.seasons.find(s => s.season === S.seasonShown) || S.pl.seasons[0] : null;
    const items = season ? season.items : flatItems(S.pl);
    items.forEach((it, i) => {
      const on = S.item && it.file.path === S.item.file.path;
      const b = el("button", "wp-ep" + (on ? " on" : ""));
      b.type = "button";
      if (on) b.setAttribute("aria-current", "true");
      const label = S.kind === "collection" ? (it.title || "Фильм " + (i + 1)) : it.episode + " серия";
      b.append(el("b", null, label));
      if (S.kind !== "collection") b.append(el("small", null, shortName(it.file.path, 34)));
      b.title = basename(it.file.path);
      const wb = watchBadge(S.watch.get(S.m.id + "|" + it.file.path));
      if (wb) {
        b.append(el("span", "wp-ep-mark" + (wb.done ? " done" : ""), wb.done ? "✓" : wb.pct + "%"));
        if (!wb.done) { const bar = el("i", "wp-ep-bar"); bar.style.width = wb.pct + "%"; b.append(bar); }
      }
      b.addEventListener("click", () => { if (!on) select(it, { push: true, autoplay: true }); });
      box.append(b);
    });
  }

  function renderNow() {
    const h = headingText(S.shownTitle || S.title, S.item, S.kind);
    $("wpNow").textContent = h;
    document.title = documentTitle(S.shownTitle || S.title, S.item, S.kind);
  }

  // ---------- Плеер ----------

  function select(item, opts) {
    opts = opts || {};
    cancelNext();
    S.item = item;
    S.state = null;
    renderControls(null);
    showOverlay(null);
    if (opts.push) history.pushState({ path: item.file.path }, "", location.pathname + itemQuery(S.pl, item));
    if (S.kind !== "movie") saveLastEpisode(S.m.id, item.file.path);
    if (S.kind === "series" && item.season !== S.seasonShown) renderSeasons(item.season);
    else renderEpisodes();
    renderNow();
    mountPlayer(item, opts.startAt != null ? opts.startAt : undefined, Boolean(opts.autoplay));
  }

  function mountPlayer(item, startAt, autoplay) {
    const H = root.SuslikHls;
    if (S.ctl) { S.ctl.load(String(S.m.id), item.file.path, startAt); return; }
    // В приложении Android фильм играет его плеер (как «Смотреть» в окне фильма), а не встроенный
    if (H && typeof H.mount === "function" && !nativeApp()) {
      S.ownScreens = true; // «Готовим…», «не показывает», ошибки и «Открыть в приложении» рисует сам плеер (onOpenApp)
      $("wpScreen").replaceChildren();
      S.ctl = H.mount($("wpScreen"), {
        adId: String(S.m.id), path: item.file.path, title: headingText(S.shownTitle || S.title, item, S.kind),
        startAt, autoplay, api, controls: false,
        onState, onEnded, onTime: sec => { S.pos = sec; }, onOpenApp: openApp
      });
      return;
    }
    legacyPlayer(item, nativeApp() ? "Фильм откроется в плеере приложения." : null);
  }

  // Плеер ещё старый (нет SuslikHls.mount): заглушка, «Смотреть в браузере» из окна фильма (attach) и внешний плеер
  function legacyPlayer(item, text) {
    const scr = $("wpScreen");
    const card = el("div", "wp-legacy ps-card");
    card.append(el("p", null, text || "Плеер обновляется. Пока можно смотреть так:"));
    const a = el("a", "wp-btn primary sheet-btn disabled", "▶ Смотреть");
    a.dataset.act = "player";
    a.rel = "noopener noreferrer";
    card.append(a);
    scr.replaceChildren(card);
    const token = (S.legacyToken = (S.legacyToken || 0) + 1);
    const live = () => S.legacyToken === token;
    mirrorUrl(item).then(url => {
      if (!live()) return;
      a.href = playerHref(url, navigator.userAgent);
      a.classList.remove("disabled");
      if (root.SuslikHls && typeof root.SuslikHls.attach === "function") {
        root.SuslikHls.attach({ sheet: card, file: item.file, magnet: S.m, mirrorUrl: url, live, api, watch: S.watch.get(S.m.id + "|" + item.file.path) || null });
      }
    }).catch(e => { if (live()) setStatus(e.message, true); });
  }

  function nativeApp() { return Number(root.__suslikApp || 0) >= 2 && !!root.SuslikNative; }

  // «Открыть в приложении» из плеера: тот же внешний плеер, что у кнопки «Смотреть» (PotPlayer, VLC, Android)
  function openApp() {
    const item = S.item;
    if (!item) return;
    mirrorUrl(item).then(url => { if (S.item === item) location.href = playerHref(url, navigator.userAgent); })
      .catch(e => setStatus(e.message, true));
  }

  // Адрес зеркала (подписанный, 12 ч) для внешнего плеера — один раз на файл
  function mirrorUrl(item) {
    const f = item.file;
    if (!f.sourceLink) return Promise.reject(new Error("Ссылка на файл ещё не готова — обновите медиатеку."));
    if (!S.mirror.has(f.path)) {
      const name = f.name || basename(f.path) || "video";
      const job = api("/api/file/mirror-url?link=" + encodeURIComponent(f.sourceLink) + "&name=" + encodeURIComponent(name))
        .then(r => { if (!r || !r.url) throw new Error("Сервер не подготовил фильм к просмотру."); return r.url; });
      job.catch(() => S.mirror.delete(f.path));
      S.mirror.set(f.path, job);
    }
    return S.mirror.get(f.path);
  }

  function onState(state) {
    S.state = state || null;
    renderControls(S.state);
    showOverlay(S.ownScreens ? null : overlayFor(S.state));
    setStatus(S.state && S.state.kind === "mirror" ? "Файл без подготовки для браузера: перемотка может быть медленнее."
      : S.state && S.state.fromArchive ? "Из архива: старт и перемотка медленнее." : "", false);
  }

  function showOverlay(o) {
    show($("wpOverlay"), Boolean(o));
    if (!o) return;
    $("wpOverlayText").textContent = o.text;
    const app = $("wpOverlayApp");
    show(app, Boolean(o.app));
    if (o.app && S.item) {
      app.removeAttribute("href");
      app.classList.add("disabled");
      const item = S.item;
      mirrorUrl(item).then(url => {
        if (S.item !== item) return;
        app.href = playerHref(url, navigator.userAgent);
        app.classList.remove("disabled");
      }).catch(() => {});
    }
  }

  function fillSelect(sel, options, value) {
    sel.replaceChildren();
    for (const o of options) { const op = el("option", null, o.text); op.value = String(o.value); sel.append(op); }
    if (value != null) sel.value = String(value);
  }

  function renderControls(state) {
    const audio = state && Array.isArray(state.audio) ? state.audio : [];
    const box = $("wpAudio");
    box.replaceChildren();
    show($("wpAudioBox"), audio.length > 1);
    if (audio.length > 1) {
      audio.forEach((t, i) => {
        const on = t.index === state.audioIndex;
        const b = el("button", "wp-choice" + (on ? " on" : ""), trackLabel(t, i));
        b.type = "button";
        b.setAttribute("role", "radio");
        b.setAttribute("aria-checked", String(on));
        b.addEventListener("click", () => { if (S.ctl && !on) S.ctl.setAudio(t.index); });
        box.append(b);
      });
    }
    const subs = subtitleOptions(state);
    show($("wpSubsBox"), subs.length > 0);
    if (subs.length) fillSelect($("wpSubs"), subs, typeof state.subtitleIndex === "number" ? state.subtitleIndex : -1);
    const q = qualityOptions(state);
    show($("wpQualityBox"), q.length > 0);
    if (q.length) fillSelect($("wpQuality"), q, typeof state.level === "number" ? state.level : -1);
    show($("wpCompact"), subs.length > 0 || q.length > 0);
  }

  // ---------- Следующая серия ----------

  function onEnded() {
    const n = nextItem(S.pl, S.item);
    if (!n) return;
    let left = NEXT_COUNTDOWN_S;
    const label = () => {
      const what = S.kind === "collection" ? (n.title || "следующий фильм") : (n.season !== S.item.season ? n.season + " сезон, " : "") + n.episode + " серия";
      $("wpNextText").textContent = "Дальше: " + what + " через " + left + " с";
    };
    cancelNext();
    S.next = { item: n, timer: setInterval(() => {
      left -= 1;
      if (left <= 0) { cancelNext(); select(n, { push: true, autoplay: true }); return; }
      label();
    }, 1000) };
    label();
    show($("wpNext"), true);
  }
  function cancelNext() {
    if (S.next) clearInterval(S.next.timer);
    S.next = null;
    if (hasDom && $("wpNext")) show($("wpNext"), false);
  }

  function setStatus(text, err) {
    const s = $("wpStatus");
    s.textContent = text || "";
    s.classList.toggle("wp-err", Boolean(err));
  }

  function copyLink() {
    const url = location.href;
    const done = () => setStatus("Ссылка скопирована", false);
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(url).then(done, () => window.prompt("Ссылка на фильм:", url));
    else window.prompt("Ссылка на фильм:", url);
  }

  if (hasDom && document.getElementById("wp")) {
    const start = () => boot().catch(e => showMissing(e && e.message));
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start);
    else start();
  }

  return {
    sitePaths, parseQuery, kindOf, flatItems, pickItem, nextItem, itemQuery, shortName, headingText, documentTitle,
    watchBadge, trackLabel, qualityOptions, subtitleOptions, overlayFor, playerHref, qualityOf, versionLabel,
    formatBytes, runtimeText, dateText, factRows, aboutTitle
  };
});

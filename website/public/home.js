// Боковая панель с коллекциями и баннер «Продолжить просмотр» (как в приложении для ПК).
// Работает поверх основной страницы: берёт её данные (cachedLibrary, libraryCategory, setTab, renderLibrary) и ничего не ломает, если их нет.
(function () {
  "use strict";
  if (typeof renderLibrary !== "function" || typeof setTab !== "function") return;

  var tr = (typeof window !== "undefined" && window.tr) ? window.tr : function(k, p, def) { return def || k; };

  var CATS = ["movie", "series", "anime", "cartoon", "other"];

  // ---- Боковая панель ---------------------------------------------------------------------------
  function libraryVisible() { var t = $("libraryTab"); return !!t && !t.classList.contains("hidden"); }

  function sideNavSync() {
    var inLib = libraryVisible();
    var lib = cachedLibrary || [];
    var counts = { all: lib.length, movie: 0, series: 0, anime: 0, cartoon: 0, other: 0 }, fav = 0, mine = 0;
    lib.forEach(function (m) { var c = getItemCategory(m); if (counts[c] !== undefined) counts[c]++; if (m.favorite) fav++; if (m.mine) mine++; });
    var favMode = typeof libraryStatusFilter !== "undefined" && libraryStatusFilter === "favorites";
    document.querySelectorAll(".side-cat").forEach(function (b) {
      var cat = b.dataset.cat;
      if (!cat) return;
      var n = b.querySelector(".side-count");
      if (n) n.textContent = lib.length ? counts[cat] : "";
      b.classList.toggle("active", inLib && libraryCategory === cat);
    });
    var setN = function (id, v) { var n = $(id); if (n) n.textContent = lib.length ? v : ""; };
    setN("sideTotalCount", lib.length); setN("sideFavCount", fav); setN("sideMineCount", mine);
    $("showLibraryTab").classList.toggle("secondary", !(inLib && libraryCategory === "all" && !favMode));
    $("showMineTab").classList.toggle("secondary", !(inLib && libraryCategory === "mine"));
    if ($("showFavTab")) $("showFavTab").classList.toggle("secondary", !(inLib && favMode));
    var svc = $("sideAppsTab");
    if (svc) svc.classList.toggle("active", !$("appsTab").classList.contains("hidden"));
    var ses = $("sideSessionsTab");
    if (ses) ses.classList.toggle("active", !$("sessionsTab").classList.contains("hidden"));
  }

  function setCategory(cat, switchTab) {
    libraryCategory = cat;
    try { sessionStorage.setItem("suslik.libraryCategory", cat); } catch (e) {}
    var wrap = $("libraryCategoryChips");
    if (wrap) wrap.querySelectorAll(".cat-chip").forEach(function (c) { c.classList.toggle("active", (c.dataset.cat || "all") === cat); });
    if (switchTab !== false && !libraryVisible()) setTab("library");
    renderLibrary(false);
  }

  document.querySelectorAll(".side-cat").forEach(function (b) {
    b.addEventListener("click", function () { if (b.dataset.cat) setCategory(b.dataset.cat); }); // «Приложения» и «Сессии» открывают свои вкладки сами
  });
  // «Главная» всегда сбрасывает категорию на «Все»
  $("showLibraryTab").addEventListener("click", function () {
    if (typeof setLibraryStatus === "function" && libraryStatusFilter === "favorites") setLibraryStatus("all");
    if (libraryCategory !== "all") setCategory("all");
  }, true);
  $("showMineTab").addEventListener("click", function () { setCategory("mine"); });
  var sideApps = $("sideAppsTab");
  if (sideApps) sideApps.addEventListener("click", function () { setTab("apps"); });

  // ---- Баннер «Продолжить просмотр» ---------------------------------------------------------------
  var hero = { entries: [], sig: "", idx: 0, timer: null, hover: false, fetchedAt: 0, fetching: false, info: new Map() };
  var LIMIT = 3, ROTATE_MS = 9000, MAX_AGE = 60 * 864e5;
  var pad2 = function (n) { return String(n).padStart(2, "0"); };

  function plural(n, one, few, many) {
    var t = n % 100, u = n % 10;
    return n + " " + (t >= 11 && t <= 14 ? many : u === 1 ? one : u >= 2 && u <= 4 ? few : many);
  }

  // Прогресс приходит с аккаунта Discord (/api/sync): приложения и ТВ кладут туда, где остановились; при входе по паролю его нет.
  async function fetchWatch() {
    if (hero.fetching || Date.now() - hero.fetchedAt < 60000) return;
    hero.fetching = true;
    try {
      var d = await api("/api/sync?since=0");
      hero.fetchedAt = Date.now();
      hero.rawSync = d.watch || [];
      var best = new Map();
      hero.rawSync.forEach(function (w) {
        var id = String(w.key).split("|")[0];
        var old = best.get(id);
        if (!old || w.at > old.at) best.set(id, w);
      });
      hero.raw = [...best.values()];
    } catch (e) { hero.raw = []; hero.rawSync = []; hero.fetchedAt = Date.now(); }
    hero.fetching = false;
    // доля просмотра по раздаче — для полосок на постерах и вкладок «Продолжить» / «Непросмотренные»
    var frac = new Map();
    (hero.raw || []).forEach(function (w) {
      var id = String(w.key).split("|")[0];
      if (w.len > 0 && w.pos > 0) frac.set(id, Math.min(1, w.pos / w.len));
    });
    var sig = [...frac].map(function (x) { return x[0] + ":" + Math.round(x[1] * 100); }).join(",");
    hero.frac = frac;
    if (sig !== hero.fracSig) { hero.fracSig = sig; if (libraryVisible()) renderLibrary(false); }
  }
  window.smWatchOf = function (id) { return (hero.frac && hero.frac.get(String(id))) || 0; };

  // Сборник фильмов — общие правила страницы (isCollection, collectionTypeLabel, COLLECTION_WORDS в index.html), не своя копия
  var TRACK_PREFIX = /^\s*(?:\[[^\]]*\]\s*|\([^\)]*\)\s*|(?:фильм|серия|часть|part|movie|vol|cd|диск)?\s*\d{1,3}[\s._-]*)+/i;
  var FILM_COUNT_NOISE = /(?<![\p{L}\p{N}])\d+\s*(?:фильм\p{L}*|сери\p{L}*|част\p{L}*)(?![\p{L}\p{N}])/giu;
  var MAX_FILM_QUERIES = 6; // каждый промах — ещё несколько запросов в TMDB на сервере

  function fileParts(fileOrPath) {
    var path = typeof fileOrPath === "string" ? fileOrPath : (fileOrPath && (fileOrPath.path || fileOrPath.name)) || "";
    var raw = path.replace(/\\/g, "/").split("/").pop() || "";
    var withoutExt = raw.replace(/\.[a-z0-9]{2,5}$/i, "");
    var film = escapeTitle(withoutExt.replace(TRACK_PREFIX, "").trim() || withoutExt).split("/").map(function (s) { return s.trim(); }).filter(Boolean)[0] || "";
    var ym = withoutExt.match(/\b(19\d{2}|20\d{2})\b/) || path.match(/\b(19\d{2}|20\d{2})\b/);
    return { raw: raw, film: film, year: ym ? ym[1] : null };
  }

  // Названия сборника («Гарри Поттер», «Harry Potter») без «Трилогия», «8 фильмов» и части после «:»; от принятого названия ИИ
  function collectionBases(magnet) {
    return displayTitle(magnet).replace(COLLECTION_WORDS, " ").split("/").filter(function (part) { return part.trim(); }).map(function (part) {
      return escapeTitle(part).split(":")[0].replace(FILM_COUNT_NOISE, "").replace(/\s+/g, " ").trim();
    }).filter(Boolean);
  }

  function collectionBase(magnet) { return collectionBases(magnet)[0] || ""; }

  // Постер одного фильма сборника (collectionFilePosterQueries в Model.kt): фильм с годом, фильм, франшиза: фильм, франшиза
  function collectionFilePosterQueries(magnet, fileOrPath) {
    var f = fileParts(fileOrPath), base = collectionBase(magnet), out = [];
    var add = function (q) { if (q && out.indexOf(q) < 0) out.push(q); };
    var withYear = function (q) { return f.year ? q + " (" + f.year + ")" : q; };
    if (f.film) { add(withYear(f.film)); add(f.film); }
    if (base.length >= 2 && f.film && f.film.toLowerCase().indexOf(base.toLowerCase()) < 0) { add(withYear(base + ": " + f.film)); add(base + ": " + f.film); }
    collectionBases(magnet).forEach(function (b) { if (b.length >= 2) add(b); });
    add(escapeTitle(magnet.name || ""));
    return out.slice(0, MAX_FILM_QUERIES);
  }

  function collectionFileTitle(magnet, fileOrPath) {
    var f = fileParts(fileOrPath), base = collectionBase(magnet);
    if (f.film && base && f.film.toLowerCase().indexOf(base.toLowerCase()) < 0) return base + ": " + f.film;
    return f.film || escapeTitle(f.raw);
  }

  function nextEpisode(pl, filePath) {
    if (!pl || !Array.isArray(pl.seasons)) return null;
    var all = [];
    pl.seasons.forEach(function (s) {
      (s.items || []).forEach(function (it) { all.push(it); });
    });
    var i = all.findIndex(function (it) { return it.file && it.file.path === filePath; });
    return (i >= 0 && i < all.length - 1) ? all[i + 1] : null;
  }

  function buildEntries() {
    var rawList = hero.raw || [];
    var watchMap = new Map();
    (hero.rawSync || []).forEach(function (w) { watchMap.set(String(w.key), w); });

    var candidates = [];
    rawList.forEach(function (w) {
      if (Date.now() - w.at > MAX_AGE) return;
      var id = String(w.key).split("|")[0], path = String(w.key).slice(id.length + 1);
      var magnet = (cachedLibrary || []).find(function (m) { return String(m.id) === id; });
      if (!magnet || Number(magnet.statusCode) !== 4) return;
      var pl = buildPlaylist(magnet), isColl = isCollection(pl, magnet);
      var baseTitle = typeof displayTitle === "function" ? displayTitle(magnet) : escapeTitle(magnet.name);

      var fraction = w.len > 0 ? w.pos / w.len : 0;
      var isWatched = fraction >= 0.9;
      var targetPath = path;
      var isNext = false;
      var entryPos = w.pos, entryLen = w.len, entryFrac = fraction;

      if (isWatched) {
        if (!isColl && pl.type !== "series") {
          // Завершённый фильм дальше не показываем
          return;
        }
        var next = nextEpisode(pl, path);
        if (!next || !next.file) return;
        var nextKey = id + "|" + next.file.path;
        var nextW = watchMap.get(nextKey);
        if (nextW && nextW.len > 0 && nextW.pos / nextW.len >= 0.9) return;
        targetPath = next.file.path;
        isNext = true;
        entryPos = nextW ? nextW.pos : 0;
        entryLen = nextW ? nextW.len : 0;
        entryFrac = entryLen > 0 ? entryPos / entryLen : 0;
      }

      var sub = tr("common.movie", null, "Фильм"), epCount = null, filmTitle = null, queries = null;
      if (isColl) {
        var films = (pl.seasons && pl.seasons[0] && pl.seasons[0].items) || [];
        var idx = -1;
        films.forEach(function (item, i) { if (item.file.path === targetPath) idx = i; });
        var cleanName = collectionFileTitle(magnet, targetPath);
        filmTitle = cleanName;
        var baseSub = (films.length > 1 && idx >= 0) ? tr("library.film_p", { p: idx + 1 }, "Фильм " + (idx + 1)) + " · " + cleanName : cleanName;
        sub = isNext ? (tr("library.dalshe", null, "Дальше: ") + baseSub) : baseSub;
        queries = collectionFilePosterQueries(magnet, targetPath);
      } else if (pl.type === "series") {
        var found = null;
        pl.seasons.forEach(function (s) { s.items.forEach(function (e) { if (e.file.path === targetPath) found = { s: s, e: e }; }); });
        if (!found) return;
        var epText = found.s.season === 0
          ? tr("hero.special_episode", { episode: found.e.episode }, "Спецвыпуск " + found.e.episode)
          : tr("hero.season_episode", { season: found.s.season, episode: found.e.episode }, "Сезон " + found.s.season + ", серия " + found.e.episode);
        sub = isNext ? (tr("library.dalshe", null, "Дальше: ") + epText) : epText;
        // сведения сезона, который смотрят (в общей плитке это может быть не первый): «Сезон 2 • 10 серий», год и описание сезона
        epCount = { season: found.s.season, eps: found.s.items.length };
        queries = typeof getPosterQueries === "function" ? getPosterQueries(magnet) : [baseTitle, escapeTitle(magnet.name)];
      } else if (!pl.file) return;
      else {
        queries = typeof getPosterQueries === "function" ? getPosterQueries(magnet) : [baseTitle, escapeTitle(magnet.name)];
      }

      // Группировка по сериалу / фильму (как на ПК и Android: groupVersions), чтобы разные релизы или сезоны не дублировали карточки
      var pPath = typeof posterPathOf === "function" ? posterPathOf(magnet) : null;
      var kind = (pl.type === "series" || isColl) ? "s" : "m";
      var tKey = typeof titleKey === "function" ? titleKey(baseTitle) : baseTitle.toLowerCase().trim();
      var groupKey = pPath ? (kind + "|poster|" + pPath) : (kind + "|" + tKey);

      candidates.push({
        magnet: magnet, path: targetPath, pos: entryPos, len: entryLen, at: w.at, fraction: entryFrac,
        sub: sub, epCount: epCount, season: epCount ? epCount.season : 0, isCollection: isColl, filmTitle: filmTitle,
        filmCount: isColl ? ((pl.seasons && pl.seasons[0] && pl.seasons[0].items && pl.seasons[0].items.length) || 0) : 0,
        queries: queries, baseTitle: baseTitle, groupKey: groupKey
      });
    });

    // Дедупликация: для одного сериала/фильма оставляем только последнюю просмотренную запись
    var byGroup = new Map();
    candidates.forEach(function (item) {
      var old = byGroup.get(item.groupKey);
      if (!old || item.at > old.at) byGroup.set(item.groupKey, item);
    });

    var out = [...byGroup.values()];
    out.sort(function (a, b) { return b.at - a.at; });
    return out.slice(0, LIMIT);
  }

  function getInfo(queries, season) {
    if (!Array.isArray(queries)) queries = [queries];
    var sfx = season > 0 ? "|s" + season : "";
    for (var i = 0; i < queries.length; i++) {
      var q = queries[i], key = q.toLowerCase() + sfx;
      if (hero.info.has(key)) return hero.info.get(key);
    }
    var q0 = queries[0] || "", key0 = q0.toLowerCase() + sfx;
    var p = (async function () {
      for (var j = 0; j < queries.length; j++) {
        try {
          var res = await api("/api/poster?hero=1&q=" + encodeURIComponent(queries[j]) + (season > 0 ? "&season=" + season : ""));
          if (res && (res.backdrop || res.overview || res.poster)) return res;
        } catch (e) {}
      }
      return null;
    })();
    hero.info.set(key0, p);
    return p;
  }

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined) n.textContent = text;
    return n;
  }

  function timeLeft(e) {
    if (!(e.len > 0 && e.pos > 0)) return "";
    var min = Math.max(1, Math.floor((e.len - e.pos) / 60000));
    var timeStr = (min >= 60 ? Math.floor(min / 60) + " ч " + (min % 60) + " мин" : min + " мин");
    return tr("hero.time_left", { time: timeStr }, timeStr + " осталось");
  }

  function playEntry(e) {
    var magnet = e.magnet, pl = buildPlaylist(magnet), file = null, baseTitle = e.baseTitle || (typeof displayTitle === "function" ? displayTitle(magnet) : escapeTitle(magnet.name)), label = baseTitle, req = null;
    if (e.isCollection) {
      var films = (pl.seasons && pl.seasons[0] && pl.seasons[0].items) || [];
      var fItem = films.find(function (x) { return x.file.path === e.path; });
      if (fItem) {
        file = fItem.file;
        label = e.filmTitle || escapeTitle(file.name || file.path.split("/").pop());
      }
    } else if (pl.type === "series") {
      pl.seasons.forEach(function (s) {
        var i = s.items.findIndex(function (x) { return x.file.path === e.path; });
        if (i < 0 || file) return;
        file = s.items[i].file;
        label += " · " + (s.season === 0 ? tr("hero.special_short", null, "Спец.") : "S" + pad2(s.season)) + "E" + pad2(s.items[i].episode);
        var items = s.items.slice(i).map(function (x) { return { index: magnet.files.indexOf(x.file), link: x.file.sourceLink }; })
          .filter(function (x) { return x.index >= 0 && x.link; });
        req = { magnetId: magnet.id, items: items };
        saveLastEpisode(magnet.id, file.path);
      });
    } else file = pl.file;
    if (file) playFile(file, label, req, magnet);
  }

  function renderHero() {
    var box = $("continueHero");
    if (!box) return;
    var e = hero.entries[hero.idx] || hero.entries[0];
    if (!e) { box.replaceChildren(); return; }
    var pct = function (x) { return Math.round(Math.min(1, Math.max(0, x.fraction)) * 100); };
    var baseTitle = e.baseTitle || (typeof displayTitle === "function" ? displayTitle(e.magnet) : escapeTitle(e.magnet.name));
    var displayTitleText = e.isCollection ? (e.filmTitle || baseTitle) : baseTitle;

    // Левая часть: что смотрели, сведения, описание, прогресс и кнопки
    var main = el("div", "hb-main"), top = el("div");
    var head = el("div", "hb-head");
    head.append(el("span", "hb-eyebrow", tr("hero.eyebrow", null, "Продолжить просмотр")), el("span", "hb-left", timeLeft(e)));
    var title = el("h1", "hb-title", displayTitleText);
    var meta = el("div", "hb-meta");
    var specs = getItemSpecs(e.magnet);
    var yearSpan = el("span", "hidden"), ratingSpan = el("span", "hb-rating hidden");
    var parts = [yearSpan];
    if (e.isCollection) parts.push(el("span", "", collectionTypeLabel(e.filmCount, e.magnet.name)));
    else if (specs.type) parts.push(el("span", "", specs.type));
    parts.push(ratingSpan);
    if (e.sub && e.sub !== specs.type && e.sub !== tr("common.movie", null, "Фильм")) parts.push(el("span", "hb-sub", e.sub));
    // точки-разделители только между показанными частями (год и рейтинг приходят позже)
    function relayMeta() {
      meta.replaceChildren();
      parts.filter(function (p) { return !p.classList.contains("hidden"); }).forEach(function (p, i) { if (i) meta.append(el("span", "hb-dot", "·")); meta.append(p); });
    }
    var overview = el("p", "hb-overview");
    top.append(head, title, meta, overview);

    var foot = el("div", "hb-foot"), prog = el("div", "hb-progress");
    var progHead = el("div", "hb-progress-head");
    progHead.append(el("span", "", tr("hero.watched", null, "Просмотрено")), el("span", "hb-pct", pct(e) + "%"));
    var bar = el("div", "hb-bar"), fill = el("i"); fill.style.width = pct(e) + "%"; bar.append(fill);
    prog.append(progHead, bar);
    var btns = el("div", "hb-actions");
    var play = el("button", "hb-play sm-btn sm-btn-primary"); play.type = "button";
    play.innerHTML = '<svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>';
    play.append(el("span", "", tr("hero.resume", null, "Продолжить")));
    play.addEventListener("click", function () { playEntry(e); });
    var about = el("button", "hb-about sm-btn", tr("hero.about", null, "О фильме")); about.type = "button";
    about.addEventListener("click", function () { if (typeof openLibraryModal === "function") openLibraryModal(e.magnet); });
    btns.append(play, about);
    foot.append(prog, btns);
    main.append(top, foot);

    // Правая часть: «Недавно смотрели» — переключатель между начатыми фильмами
    var side = el("div", "hb-side"), sideHead = el("div", "hb-side-head");
    sideHead.append(el("span", "hb-eyebrow", tr("hero.recent", null, "Недавно смотрели")),
      el("span", "hb-side-count", plural(hero.entries.length, "фильм", "фильма", "фильмов")));
    var thumbs = el("div", "hb-thumbs");
    hero.entries.forEach(function (x, i) {
      var thumbTitle = x.isCollection ? (x.filmTitle || x.baseTitle) : x.baseTitle;
      var b = el("button", "hb-thumb" + (x === e ? " on" : "")); b.type = "button"; b.title = thumbTitle;
      var art = el("span", "hb-thumb-art");
      var img = el("img"); img.alt = ""; img.decoding = "async";
      (async function () {
        for (var q of x.queries) {
          var u = await getPoster(q);
          if (u) { img.src = apiUrl(u); img.onload = function () { img.classList.add("loaded"); }; break; }
        }
      })();
      var p = el("span", "hb-thumb-bar"), pf = el("i"); pf.style.width = pct(x) + "%"; p.append(pf);
      art.append(el("span", "hb-thumb-fallback", thumbTitle), img, p);
      b.append(art, el("span", "hb-thumb-title", thumbTitle), el("span", "hb-thumb-pct", pct(x) + "%"));
      b.addEventListener("click", function () { hero.idx = i; renderHero(); armTimer(); });
      thumbs.append(b);
    });
    var sideFoot = el("div", "hb-side-foot");
    sideFoot.append(el("span", "", tr("hero.pick", null, "Выберите фильм для быстрого возврата")), el("span", "hb-saved", tr("hero.saved", null, "Прогресс сохранён")));
    side.append(sideHead, thumbs, sideFoot);
    box.replaceChildren(main, side);

    // описание, год и рейтинг приходят с сервера (TMDB)
    var token = e;
    getInfo(e.queries, e.season).then(function (info) {
      if (!info || (hero.entries[hero.idx] || hero.entries[0]) !== token) return;
      if (!box.contains(main)) return;
      if (info.title && e.isCollection) title.textContent = info.title;
      if (info.overview) overview.textContent = info.overview;
      if (info.year) { yearSpan.textContent = info.year; yearSpan.classList.remove("hidden"); }
      if (info.rating) { ratingSpan.textContent = "★ " + Number(info.rating).toFixed(1); ratingSpan.classList.remove("hidden"); }
      relayMeta();
    });
    relayMeta();
  }

  function armTimer() {
    clearInterval(hero.timer);
    hero.timer = null;
    if (hero.entries.length < 2) return;
    hero.timer = setInterval(function () {
      if (hero.hover || document.hidden || !heroShown()) return;
      hero.idx = (hero.idx + 1) % hero.entries.length;
      renderHero();
    }, ROTATE_MS);
  }

  function heroShown() { var b = $("continueHero"); return !!b && !b.classList.contains("hidden"); }

  function heroSync() {
    var box = $("continueHero");
    if (!box) return;
    var query = $("librarySearch") ? $("librarySearch").value.trim() : "";
    var entries = hero.raw ? buildEntries() : [];
    var show = libraryVisible() && libraryCategory === "all" && !query && entries.length > 0;
    box.classList.toggle("hidden", !show);
    var sig = entries.map(function (x) { return x.magnet.id + "|" + x.path; }).join(",");
    if (show && (sig !== hero.sig || !box.firstChild)) {
      hero.entries = entries; hero.sig = sig; hero.idx = 0;
      renderHero(); armTimer();
    }
  }

  async function heroRefresh() {
    if (!libraryVisible()) return;
    await fetchWatch();
    heroSync();
  }

  (function () {
    var box = $("continueHero");
    if (!box) return;
    box.addEventListener("mouseenter", function () { hero.hover = true; });
    box.addEventListener("mouseleave", function () { hero.hover = false; });
  })();

  // ---- Метки Dolby Vision / HDR / DTS: сервер разбирает файлы в фоне и помнит ответ, пока раздача есть ---------
  var tagsAt = 0, tagsBusy = false;
  async function tagsRefresh() {
    if (tagsBusy || Date.now() - tagsAt < 60000) return;
    tagsBusy = true;
    try {
      for (var i = 0; i < 40; i++) {
        var j = await api("/api/tags"), changed = false;
        Object.keys(j.tags || {}).forEach(function (id) {
          var v = j.tags[id];
          if ((serverTagMap[id] || []).join() !== v.join() || !serverTagMap[id]) { serverTagMap[id] = v; changed = true; }
        });
        if (changed) renderLibrary(false);
        if (!(j.pending > 0)) break;
        await new Promise(function (r) { setTimeout(r, 30000); });
        // ушли с медиатеки — не спрашиваем; вернутся — setTab запустит снова
        if (!libraryVisible()) { tagsBusy = false; return; }
      }
    } catch (e) { /* сервер без /api/tags: остаются метки по названию */ }
    tagsAt = Date.now();
    tagsBusy = false;
  }

  // ---- Подключение к существующим функциям -------------------------------------------------------
  var baseRender = renderLibrary;
  renderLibrary = function () {
    var r = baseRender.apply(this, arguments);
    try { sideNavSync(); heroSync(); } catch (e) { console.warn("home.js", e); }
    return r;
  };
  var baseSetTab = setTab;
  setTab = function () {
    var r = baseSetTab.apply(this, arguments);
    try { sideNavSync(); heroSync(); heroRefresh(); tagsRefresh(); } catch (e) { console.warn("home.js", e); }
    return r;
  };
  try {
    var savedCat = sessionStorage.getItem("suslik.libraryCategory");
    if (savedCat && savedCat !== libraryCategory) {
      setCategory(savedCat, false);
    }
  } catch (e) {}
  sideNavSync();
  heroRefresh();
})();

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
    var counts = { movie: 0, series: 0, anime: 0, cartoon: 0, other: 0 };
    (cachedLibrary || []).forEach(function (m) { var c = getItemCategory(m); if (counts[c] !== undefined) counts[c]++; });
    document.querySelectorAll(".side-cat").forEach(function (b) {
      var cat = b.dataset.cat;
      var n = b.querySelector(".side-count");
      if (n) n.textContent = counts[cat];
      b.classList.toggle("active", inLib && libraryCategory === cat);
    });
    $("showLibraryTab").classList.toggle("secondary", !(inLib && libraryCategory === "all"));
    $("showMineTab").classList.toggle("secondary", !(inLib && libraryCategory === "mine"));
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
    if (libraryCategory !== "all") setCategory("all");
  }, true);
  $("showMineTab").addEventListener("click", function () { setCategory("mine"); });
  var sideApps = $("sideAppsTab");
  if (sideApps) sideApps.addEventListener("click", function () { setTab("apps"); });

  // ---- Баннер «Продолжить просмотр» ---------------------------------------------------------------
  var hero = { entries: [], sig: "", idx: 0, timer: null, hover: false, fetchedAt: 0, fetching: false, info: new Map() };
  var LIMIT = 4, ROTATE_MS = 9000, MAX_AGE = 60 * 864e5;
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
      var best = new Map();
      (d.watch || []).forEach(function (w) {
        var id = String(w.key).split("|")[0];
        var old = best.get(id);
        if (!old || w.at > old.at) best.set(id, w);
      });
      hero.raw = [...best.values()];
    } catch (e) { hero.raw = []; hero.fetchedAt = Date.now(); }
    hero.fetching = false;
  }

  var COLLECTION_REGEX = /(?:трилоги[яи]|дилоги[яи]|тетралоги[яи]|квадрологи[яи]|пенталоги[яи]|гексалоги[яи]|франшиз[аеы]|коллекци[яи]|антологи[яи]|саг[аеы]|киносаг[аеы]|сборник|боксет|boxset|box-set|trilogy|duology|dilogy|tetralogy|quadrilogy|anthology|collection|franchise|\b\d+\s*[-–—]\s*(?:в|in)\s*1\b|\bвсе\s+части\b|\bвсе\s+фильмы\b)/i;

  function isCollectionPl(pl, m) {
    if (m && m.aiTitle && m.aiTitle.status === "accepted" && m.aiTitle.kind === "collection") return true;
    if (!pl || pl.type !== "series") return false;
    if (pl.seasons && pl.seasons[0] && pl.seasons[0].items && pl.seasons[0].items[0] && pl.seasons[0].items[0].title != null) return true;
    var name = (m && m.name) || "";
    return COLLECTION_REGEX.test(name);
  }

  function collectionTypeLabel(filmCount, name) {
    name = name || "";
    if (/\b(трилоги[яи]|trilogy)\b/i.test(name) || filmCount === 3) return tr("library.kinotrilogiya", null, "Кинотрилогия");
    if (/\b(дилоги[яи]|duology|dilogy)\b/i.test(name) || filmCount === 2) return tr("library.dilogiya", null, "Дилогия");
    if (/\b(квадрологи[яи]|тетралоги[яи]|quadrilogy|tetralogy)\b/i.test(name) || filmCount === 4) return tr("library.kinotetralogiya", null, "Кинотетралогия");
    if (/\b(сборник)\b/i.test(name)) return tr("library.sbornik", null, "Сборник");
    if (/\b(антологи[яи]|anthology)\b/i.test(name)) return tr("library.antologiya", null, "Антология");
    if (/\b(коллекци[яи]|collection|boxset)\b/i.test(name)) return tr("library.kollektsiya", null, "Коллекция");
    return tr("library.kinofranshiza", null, "Кинофраншиза");
  }

  var TRACK_PREFIX = /^\s*(?:\[[^\]]*\]\s*|\([^\)]*\)\s*|(?:фильм|серия|часть|part|movie|vol|cd|диск)?\s*\d{1,3}[\s._-]*)+/i;

  function collectionFilePosterQueries(magnet, fileOrPath) {
    var path = typeof fileOrPath === "string" ? fileOrPath : (fileOrPath && (fileOrPath.path || fileOrPath.name)) || "";
    var raw = path.replace(/\\/g, "/").split("/").pop() || "";
    var withoutExt = raw.replace(/\.[a-z0-9]{2,5}$/i, "");
    var stripped = withoutExt.replace(TRACK_PREFIX, "").trim();
    var ym = withoutExt.match(/\b(19\d{2}|20\d{2})\b/) || path.match(/\b(19\d{2}|20\d{2})\b/);
    var year = ym ? ym[1] : null;
    var cleaned = escapeTitle(stripped || withoutExt);
    var rawBase = (magnet.name || "").replace(COLLECTION_REGEX, " ");
    var cleanBases = rawBase.split("/").map(function (s) { return escapeTitle(s); }).filter(function (s) { return s.length >= 2; });
    var out = [];
    var add = function (q) { if (q && out.indexOf(q) < 0) out.push(q); };

    var parts = cleaned.split("/").map(function (s) { return s.trim(); }).filter(Boolean);
    var candidates = parts.length ? parts : [cleaned];
    candidates.forEach(function (c) {
      if (year) add(c + " (" + year + ")");
      add(c);
    });

    cleanBases.forEach(function (cb) {
      candidates.forEach(function (c) {
        if (c.toLowerCase().indexOf(cb.toLowerCase()) < 0) {
          if (year) add(cb + ": " + c + " (" + year + ")");
          add(cb + ": " + c);
          if (year) add(cb + " " + c + " (" + year + ")");
          add(cb + " " + c);
        }
      });
    });

    cleanBases.forEach(function (cb) {
      if (year) add(cb + " (" + year + ")");
      add(cb);
    });
    add(escapeTitle(magnet.name || ""));
    return out;
  }

  function collectionFileTitle(magnet, fileOrPath) {
    var path = typeof fileOrPath === "string" ? fileOrPath : (fileOrPath && (fileOrPath.path || fileOrPath.name)) || "";
    var raw = path.replace(/\\/g, "/").split("/").pop() || "";
    var withoutExt = raw.replace(/\.[a-z0-9]{2,5}$/i, "");
    var stripped = withoutExt.replace(TRACK_PREFIX, "").trim();
    var cleaned = escapeTitle(stripped || withoutExt);
    var rawBase = (magnet.name || "").replace(COLLECTION_REGEX, " ");
    var cleanBase = escapeTitle(rawBase.split("/")[0] || "");
    if (cleaned && cleanBase && cleaned.toLowerCase().indexOf(cleanBase.toLowerCase()) < 0) {
      return cleanBase + ": " + cleaned;
    }
    return cleaned || escapeTitle(raw);
  }

  function buildEntries() {
    var out = [];
    (hero.raw || []).forEach(function (w) {
      if (Date.now() - w.at > MAX_AGE) return;
      var fraction = w.len > 0 ? w.pos / w.len : 0;
      if (fraction > 0.95) return;
      var id = String(w.key).split("|")[0], path = String(w.key).slice(id.length + 1);
      var magnet = (cachedLibrary || []).find(function (m) { return String(m.id) === id; });
      if (!magnet || Number(magnet.statusCode) !== 4) return;
      var pl = buildPlaylist(magnet), isColl = isCollectionPl(pl, magnet);
      var sub = tr("common.movie", null, "Фильм"), epCount = null, filmTitle = null, queries = null;
      if (isColl) {
        var films = (pl.seasons && pl.seasons[0] && pl.seasons[0].items) || [];
        var idx = -1;
        films.forEach(function (item, i) { if (item.file.path === path) idx = i; });
        var curItem = idx >= 0 ? films[idx] : null;
        var cleanName = collectionFileTitle(magnet, path);
        filmTitle = cleanName;
        sub = (films.length > 1 && idx >= 0) ? tr("library.film_p", { p: idx + 1 }, "Фильм " + (idx + 1)) + " · " + cleanName : cleanName;
        queries = collectionFilePosterQueries(magnet, path);
      } else if (pl.type === "series") {
        var found = null;
        pl.seasons.forEach(function (s) { s.items.forEach(function (e) { if (e.file.path === path) found = { s: s, e: e }; }); });
        if (!found) return;
        sub = found.s.season === 0 ? tr("hero.special_episode", { episode: found.e.episode }, "Спецвыпуск " + found.e.episode) : tr("hero.season_episode", { season: found.s.season, episode: found.e.episode }, "Сезон " + found.s.season + ", серия " + found.e.episode);
        epCount = { seasons: pl.seasons.length, eps: pl.seasons.reduce(function (a, s) { return a + s.items.length; }, 0) };
        queries = [escapeTitle(magnet.name)];
      } else if (!pl.file) return;
      else {
        queries = [escapeTitle(magnet.name)];
      }
      out.push({
        magnet: magnet, path: path, pos: w.pos, len: w.len, at: w.at, fraction: fraction,
        sub: sub, epCount: epCount, isCollection: isColl, filmTitle: filmTitle,
        filmCount: isColl ? ((pl.seasons && pl.seasons[0] && pl.seasons[0].items && pl.seasons[0].items.length) || 0) : 0,
        queries: queries
      });
    });
    out.sort(function (a, b) { return b.at - a.at; });
    return out.slice(0, LIMIT);
  }

  function getInfo(queries) {
    if (!Array.isArray(queries)) queries = [queries];
    for (var i = 0; i < queries.length; i++) {
      var q = queries[i], key = q.toLowerCase();
      if (hero.info.has(key)) return hero.info.get(key);
    }
    var q0 = queries[0] || "", key0 = q0.toLowerCase();
    var p = (async function () {
      for (var j = 0; j < queries.length; j++) {
        try {
          var res = await api("/api/poster?hero=1&q=" + encodeURIComponent(queries[j]));
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
    var magnet = e.magnet, pl = buildPlaylist(magnet), file = null, label = escapeTitle(magnet.name), req = null;
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
    if (file) playФайл(file, label, req, magnet);
  }

  function renderHero() {
    var box = $("continueHero");
    if (!box) return;
    var e = hero.entries[hero.idx] || hero.entries[0];
    if (!e) { box.replaceChildren(); return; }
    var bg = el("div", "hb-bg"), shade = el("div", "hb-shade"), text = el("div", "hb-text"), thumbs = el("div", "hb-thumbs");
    var eyebrow = el("div", "hb-eyebrow", tr("hero.eyebrow", null, "ПРОДОЛЖИТЬ ПРОСМОТР"));
    var displayTitle = (e.isCollection && (e.filmTitle || escapeTitle(e.magnet.name))) || escapeTitle(e.magnet.name);
    var titleBox = el("div", "hb-title"), title = el("h2", "", displayTitle);
    titleBox.append(title);
    var chips = el("div", "hb-chips"), overview = el("p", "hb-overview");
    var specs = getItemSpecs(e.magnet);
    var typeChip = e.isCollection ? el("span", "hb-chip", collectionTypeLabel(e.filmCount, e.magnet.name)) : (specs.type ? el("span", "hb-chip", specs.type) : null);
    var yearChip = el("span", "hb-chip hidden"), rating = el("span", "hb-rating hidden");
    chips.append(yearChip);
    if (typeChip) chips.append(typeChip);
    if (e.isCollection) {
      if (e.filmCount > 0) chips.append(el("span", "hb-chip", plural(e.filmCount, "фильм", "фильма", "фильмов")));
    } else if (e.epCount) {
      chips.append(el("span", "hb-chip", plural(e.epCount.seasons, "сезон", "сезона", "сезонов") + " • " + plural(e.epCount.eps, "серия", "серии", "серий")));
    }
    chips.append(rating);

    var meta = el("div", "hb-meta"), left = el("span", "", e.sub), right = el("span", "hb-left", timeLeft(e));
    meta.append(left, right);
    var bar = el("div", "hb-bar"), fill = el("i"); fill.style.width = Math.round(Math.min(1, Math.max(0, e.fraction)) * 100) + "%"; bar.append(fill);
    var play = el("button", "hb-play"); play.type = "button";
    play.append(el("span", "", "▶"), el("span", "", tr("hero.resume", null, "Продолжить")));
    play.addEventListener("click", function () { playEntry(e); });
    text.append(eyebrow, titleBox, chips, overview, el("div", "hb-spacer"), meta, bar, play);

    hero.entries.forEach(function (x, i) {
      var thumbTitle = x.isCollection ? (x.filmTitle || escapeTitle(x.magnet.name)) : escapeTitle(x.magnet.name);
      var b = el("button", "hb-thumb" + (x === e ? " on" : "")); b.type = "button"; b.title = thumbTitle;
      var img = el("img"); img.alt = ""; img.decoding = "async";
      (async function () {
        for (var q of x.queries) {
          var u = await getPoster(q);
          if (u) { img.src = u; break; }
        }
      })();
      var p = el("i", "hb-thumb-bar"); p.style.width = Math.round(Math.min(1, x.fraction) * 100) + "%";
      b.append(img, p);
      b.addEventListener("click", function () { hero.idx = i; renderHero(); armTimer(); });
      thumbs.append(b);
    });
    box.replaceChildren(bg, shade, text, thumbs);

    // широкий кадр, логотип, описание, год и рейтинг приходят с сервера (TMDB); пока их нет — баннер просто тёмный
    var token = e;
    getInfo(e.queries).then(function (info) {
      if (!info || (hero.entries[hero.idx] || hero.entries[0]) !== token) return;
      if (!box.contains(bg)) return;
      if (info.backdrop && /^\/api\/poster\/img\//.test(info.backdrop)) {
        var im = new Image();
        im.onload = function () { bg.style.backgroundImage = "url(" + info.backdrop + ")"; bg.classList.add("ready"); };
        im.src = info.backdrop;
      }
      if (info.logo && /^\/api\/poster\/img\//.test(info.logo)) {
        var logo = el("img", "hb-logo"); logo.alt = displayTitle; logo.src = info.logo;
        logo.onload = function () { title.replaceWith(logo); };
      } else if (info.title && e.isCollection) {
        title.textContent = info.title;
      }
      if (info.overview) overview.textContent = info.overview;
      if (info.year) { yearChip.textContent = info.year; yearChip.classList.remove("hidden"); }
      if (info.rating) { rating.textContent = "★ " + Number(info.rating).toFixed(1); rating.classList.remove("hidden"); }
    });
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
    var query = $("libraryПоиск") ? $("libraryПоиск").value.trim() : "";
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

// Кнопки сортировки и «Только готовые» под баннером: ведут за собой прежние скрытые select и флажок, на которых держится отрисовка.
(function () {
  "use strict";
  var sel = document.getElementById("librarySort"), seg = document.getElementById("libSortSeg");
  var ready = document.getElementById("libraryOnlyReady"), readyBtn = document.getElementById("libReadyBtn");
  if (!sel || !seg || !ready || !readyBtn) return;
  function fire(n) { n.dispatchEvent(new Event("change", { bubbles: true })); }
  seg.addEventListener("click", function (e) {
    var b = e.target.closest(".lib-pill"); if (!b) return;
    sel.value = b.dataset.sort; fire(sel);
    seg.querySelectorAll(".lib-pill").forEach(function (x) { x.classList.toggle("active", x === b); });
  });
  readyBtn.addEventListener("click", function () {
    ready.checked = !ready.checked; fire(ready);
    readyBtn.classList.toggle("active", ready.checked);
    readyBtn.setAttribute("aria-pressed", String(ready.checked));
  });
})();

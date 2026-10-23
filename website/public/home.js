// Боковая панель с коллекциями и баннер «Продолжить просмотр» (как в приложении для ПК).
// Работает поверх основной страницы: берёт её данные (cachedLibrary, libraryCategory, setTab, renderLibrary) и ничего не ломает, если их нет.
(function () {
  "use strict";
  if (typeof renderLibrary !== "function" || typeof setTab !== "function") return;

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
    var svc = $("sideAppsTab");
    if (svc) svc.classList.toggle("active", !$("appsTab").classList.contains("hidden"));
  }

  function setCategory(cat) {
    libraryCategory = cat;
    var wrap = $("libraryCategoryChips");
    if (wrap) wrap.querySelectorAll(".cat-chip").forEach(function (c) { c.classList.toggle("active", (c.dataset.cat || "all") === cat); });
    if (!libraryVisible()) setTab("library");
    renderLibrary(false);
  }

  document.querySelectorAll(".side-cat").forEach(function (b) {
    b.addEventListener("click", function () { setCategory(b.dataset.cat); });
  });
  // «Главная» на странице библиотеки сбрасывает категорию на «Все»
  $("showLibraryTab").addEventListener("click", function () {
    if (libraryVisible() && libraryCategory !== "all") setCategory("all");
  }, true);
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

  function buildEntries() {
    var out = [];
    (hero.raw || []).forEach(function (w) {
      if (Date.now() - w.at > MAX_AGE) return;
      var fraction = w.len > 0 ? w.pos / w.len : 0;
      if (fraction > 0.95) return;
      var id = String(w.key).split("|")[0], path = String(w.key).slice(id.length + 1);
      var magnet = (cachedLibrary || []).find(function (m) { return String(m.id) === id; });
      if (!magnet || Number(magnet.statusCode) !== 4) return;
      var pl = buildPlaylist(magnet), sub = "Фильм", epCount = null;
      if (pl.type === "series") {
        var found = null;
        pl.seasons.forEach(function (s) { s.items.forEach(function (e) { if (e.file.path === path) found = { s: s, e: e }; }); });
        if (!found) return;
        sub = found.s.season === 0 ? "Спецвыпуск " + found.e.episode : "Сезон " + found.s.season + ", серия " + found.e.episode;
        epCount = { seasons: pl.seasons.length, eps: pl.seasons.reduce(function (a, s) { return a + s.items.length; }, 0) };
      } else if (!pl.file) return;
      out.push({ magnet: magnet, path: path, pos: w.pos, len: w.len, at: w.at, fraction: fraction, sub: sub, epCount: epCount });
    });
    out.sort(function (a, b) { return b.at - a.at; });
    return out.slice(0, LIMIT);
  }

  function getInfo(magnet) {
    var q = escapeTitle(magnet.name), key = q.toLowerCase();
    if (!hero.info.has(key)) {
      hero.info.set(key, api("/api/poster?q=" + encodeURIComponent(q)).catch(function () { return null; }));
    }
    return hero.info.get(key);
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
    return (min >= 60 ? Math.floor(min / 60) + " ч " + (min % 60) + " мин" : min + " мин") + " осталось";
  }

  function playEntry(e) {
    var magnet = e.magnet, pl = buildPlaylist(magnet), file = null, label = escapeTitle(magnet.name), req = null;
    if (pl.type === "series") {
      pl.seasons.forEach(function (s) {
        var i = s.items.findIndex(function (x) { return x.file.path === e.path; });
        if (i < 0 || file) return;
        file = s.items[i].file;
        label += " · " + (s.season === 0 ? "Спец." : "S" + pad2(s.season)) + "E" + pad2(s.items[i].episode);
        var items = s.items.slice(i).map(function (x) { return { index: magnet.files.indexOf(x.file), link: x.file.sourceLink }; })
          .filter(function (x) { return x.index >= 0 && x.link; });
        req = { magnetId: magnet.id, items: items };
        saveLastEpisode(magnet.id, file.path);
      });
    } else file = pl.file;
    if (file) playФайл(file, label, req);
  }

  function renderHero() {
    var box = $("continueHero");
    if (!box) return;
    var e = hero.entries[hero.idx] || hero.entries[0];
    if (!e) { box.replaceChildren(); return; }
    var bg = el("div", "hb-bg"), shade = el("div", "hb-shade"), text = el("div", "hb-text"), thumbs = el("div", "hb-thumbs");
    var eyebrow = el("div", "hb-eyebrow", "ПРОДОЛЖИТЬ ПРОСМОТР");
    var titleBox = el("div", "hb-title"), title = el("h2", "", escapeTitle(e.magnet.name));
    titleBox.append(title);
    var chips = el("div", "hb-chips"), overview = el("p", "hb-overview");
    var specs = getItemSpecs(e.magnet);
    var typeChip = specs.type ? el("span", "hb-chip", specs.type) : null;
    var yearChip = el("span", "hb-chip hidden"), rating = el("span", "hb-rating hidden");
    chips.append(yearChip);
    if (typeChip) chips.append(typeChip);
    if (e.epCount) chips.append(el("span", "hb-chip", plural(e.epCount.seasons, "сезон", "сезона", "сезонов") + " • " + plural(e.epCount.eps, "серия", "серии", "серий")));
    chips.append(rating);

    var meta = el("div", "hb-meta"), left = el("span", "", e.sub), right = el("span", "hb-left", timeLeft(e));
    meta.append(left, right);
    var bar = el("div", "hb-bar"), fill = el("i"); fill.style.width = Math.round(Math.min(1, Math.max(0, e.fraction)) * 100) + "%"; bar.append(fill);
    var play = el("button", "hb-play"); play.type = "button";
    play.append(el("span", "", "▶"), el("span", "", "Продолжить"));
    play.addEventListener("click", function () { playEntry(e); });
    text.append(eyebrow, titleBox, chips, overview, el("div", "hb-spacer"), meta, bar, play);

    hero.entries.forEach(function (x, i) {
      var b = el("button", "hb-thumb" + (x === e ? " on" : "")); b.type = "button"; b.title = escapeTitle(x.magnet.name);
      var img = el("img"); img.alt = ""; img.decoding = "async";
      getPoster(escapeTitle(x.magnet.name)).then(function (u) { if (u) img.src = u; });
      var p = el("i", "hb-thumb-bar"); p.style.width = Math.round(Math.min(1, x.fraction) * 100) + "%";
      b.append(img, p);
      b.addEventListener("click", function () { hero.idx = i; renderHero(); armTimer(); });
      thumbs.append(b);
    });
    box.replaceChildren(bg, shade, text, thumbs);

    // широкий кадр, логотип, описание, год и рейтинг приходят с сервера (TMDB); пока их нет — баннер просто тёмный
    var token = e;
    getInfo(e.magnet).then(function (info) {
      if (!info || hero.entries[hero.idx] !== token && hero.entries[0] !== token) return;
      if (!box.contains(bg)) return;
      if (info.backdrop && /^\/api\/poster\/img\//.test(info.backdrop)) {
        var im = new Image();
        im.onload = function () { bg.style.backgroundImage = "url(" + info.backdrop + ")"; bg.classList.add("ready"); };
        im.src = info.backdrop;
      }
      if (info.logo && /^\/api\/poster\/img\//.test(info.logo)) {
        var logo = el("img", "hb-logo"); logo.alt = escapeTitle(e.magnet.name); logo.src = info.logo;
        logo.onload = function () { title.replaceWith(logo); };
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
  sideNavSync();
  heroRefresh();
})();

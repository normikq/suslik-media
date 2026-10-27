/*
 * Медиатека: разбор /api/library, фильм или сериал по сезонам, субтитры и внешние озвучки рядом с видео.
 * Перенос shared/.../core/Model.kt один в один (те же правила, что у сайта и приложений); тесты — test/model.test.js.
 * Только ES2017 (Tizen 4.0, Chromium 56): без ?., ??, spread в объектах, lookbehind и \p{…}.
 */
(function (g) {
  'use strict';
  var SM = g.SM = g.SM || {};

  /** Название и сезон, предложенные ИИ сервера (services/aititle.js; AiTitle в Model.kt). В группировку — только "accepted". */
  function parseAiTitle(a) {
    if (!a || typeof a !== 'object' || ['suggested', 'accepted', 'rejected', 'none'].indexOf(a.status) < 0) return null;
    return {
      base: str(a.base).trim(), season: typeof a.season === 'number' && isFinite(a.season) ? Math.floor(a.season) : null,
      model: str(a.model), at: num(a.at), status: a.status,
      kind: ['movie', 'series', 'collection'].indexOf(a.kind) >= 0 ? a.kind : null, stale: a.stale === true,
      original: str(a.original).trim() || null,
      year: typeof a.year === 'number' && a.year >= 1880 && a.year <= 2100 ? Math.floor(a.year) : null
    };
  }

  /** Название для группировки: принятое предложение ИИ, иначе по регуляркам (groupTitle в Model.kt). */
  function groupTitle(it) {
    var a = it.m.aiTitle;
    return a && a.status === 'accepted' && a.base ? a.base : it.baseTitle;
  }

  function parseLibrary(json) {
    var arr = json && json.magnets;
    if (!Array.isArray(arr)) return [];
    var out = [];
    arr.forEach(function (m) {
      if (!m || typeof m !== 'object') return;
      var files = [];
      (Array.isArray(m.files) ? m.files : []).forEach(function (f, j) {
        if (!f || typeof f !== 'object') return;
        var link = typeof f.sourceLink === 'string' && f.sourceLink.indexOf('https://') === 0 ? f.sourceLink : null;
        files.push({ index: j, name: str(f.name), path: str(f.path), isFolder: f.type === 'folder', size: num(f.size), link: link });
      });
      var id = num(m.id);
      out.push(magnet({
        id: id, name: str(m.name).trim() ? str(m.name) : 'Magnet ' + id, status: str(m.status),
        statusCode: num(m.statusCode), size: num(m.size), downloaded: num(m.downloaded), files: files,
        canRemove: m.canRemove !== false, favorite: m.favorite === true, mine: m.mine === true,
        aiTitle: parseAiTitle(m.aiTitle), canAiTitle: m.canAiTitle === true
      }));
    });
    return out;
  }

  function str(v) { return typeof v === 'string' ? v : v == null ? '' : String(v); }
  function num(v) { var n = Number(v); return isFinite(n) ? n : 0; }

  /** Добавляет к раздаче вычисляемые поля Magnet.kt. */
  function magnet(m) {
    m.ready = m.statusCode === 4;
    m.downloading = m.statusCode < 4;
    m.failed = m.statusCode >= 5;
    m.progress = m.size > 0 ? Math.min(1, Math.max(0, m.downloaded / m.size)) : 0;
    return m;
  }

  // ---- те же правила, что у сайта (buildPlaylist / parseEpisode / escapeTitle) ----

  var VIDEO_EXT = /\.(mkv|mp4|avi|mov|webm|m4v|m2ts|ts|iso)$/i;
  var SAMPLE = /(^|[\/._ -])sample([\/._ -]|$)/i;
  var NAT_TOKEN = /\d+|\D+/g;
  var DIR_SEASON = /(?:^|\/)(?:season|сезон)[ ._-]?(\d{1,2})(?:\/|$)/i;
  var DIR_S = /(?:^|\/)S(\d{1,2})(?:\/|$)/i;
  var ANY_SEASON = /(?:season|сезон)[ ._-]?(\d{1,2})/i;
  var SXXEYY = /S(\d{1,2})[ ._-]?E(\d{1,3})/i;
  var NXNN = /(?:^|[^\d])(\d{1,2})x(\d{2,3})(?!\d)/i;
  var EP_WORD = /(?:^|[^a-z0-9а-я])(?:e|ep|episode|серия)[ ._-]?(\d{1,3})(?!\d)/i;
  var LEAD_NUM = /^\s*(\d{1,3})(?=[ ._-])/;
  var DOTS = /[._]+/g;
  var BRACKETS = /[\[\](){}]/g;
  var SPACES = /\s+/g;

  function norm(p) { return p.replace(/\\/g, '/'); }
  function basename(p) { var s = norm(p); return s.substring(s.lastIndexOf('/') + 1); }
  function dirname(p) { var s = norm(p); var i = s.lastIndexOf('/'); return i < 0 ? '' : s.substring(0, i); }
  function stripExt(name) { var i = name.lastIndexOf('.'); return i < 0 ? name : name.substring(0, i); }
  function isVideo(f) { return !f.isFolder && VIDEO_EXT.test(f.path); }
  function isLetterOrDigit(c) { return /\d/.test(c) || c.toLowerCase() !== c.toUpperCase(); }

  /** Естественный порядок: «Episode 2» раньше «Episode 10». */
  function natural(a, b) {
    var ra = a.toLowerCase().match(NAT_TOKEN) || [];
    var rb = b.toLowerCase().match(NAT_TOKEN) || [];
    for (var i = 0; i < Math.min(ra.length, rb.length); i++) {
      var x = ra[i], y = rb[i], c;
      if (/\d/.test(x[0]) && /\d/.test(y[0])) {
        var nx = x.replace(/^0+/, ''), ny = y.replace(/^0+/, '');
        c = nx.length !== ny.length ? nx.length - ny.length : cmp(nx, ny);
      } else c = cmp(x, y);
      if (c !== 0) return c;
    }
    return ra.length - rb.length;
  }
  function cmp(a, b) { return a < b ? -1 : a > b ? 1 : 0; }
  function byNameNatural(a, b) { return natural(basename(a.path), basename(b.path)); }

  function seasonOf(path) {
    var p = norm(path);
    var m = DIR_SEASON.exec(p) || DIR_S.exec(p);
    return m ? parseInt(m[1], 10) : null;
  }

  function parseEpisode(path) {
    var p = norm(path), name = basename(p), m;
    var fs = ANY_SEASON.exec(p) || DIR_S.exec(p);
    var folderSeason = fs ? parseInt(fs[1], 10) : null;
    if ((m = SXXEYY.exec(name) || SXXEYY.exec(p))) return [parseInt(m[1], 10), parseInt(m[2], 10)];
    if ((m = NXNN.exec(name))) return [parseInt(m[1], 10), parseInt(m[2], 10)];
    if ((m = EP_WORD.exec(name))) return [folderSeason != null ? folderSeason : 1, parseInt(m[1], 10)];
    if (folderSeason != null && (m = LEAD_NUM.exec(name))) return [folderSeason, parseInt(m[1], 10)];
    return null;
  }

  function largest(list) {
    var best = null;
    list.forEach(function (f) { if (!best || f.size > best.size) best = f; });
    return best;
  }

  function mainVideo(m) {
    var vids = m.files.filter(isVideo);
    var real = vids.filter(function (f) { return !/\.iso$/i.test(f.path); });
    return largest(real.length ? real : vids);
  }

  /** { kind: 'movie', file } | { kind: 'series', seasons: [{ season, items: [{ season, episode, file, title }] }] } | { kind: 'none' } */
  /** Что играть: по файлам, затем тип и сезон, принятые от ИИ (buildPlayable / applyAiTitle в Model.kt). */
  function buildPlayable(m) { return applyAiTitle(playableByFiles(m), m); }

  function isCollection(p) {
    return p.kind === 'series' && !!p.seasons[0] && !!p.seasons[0].items[0] && p.seasons[0].items[0].title != null;
  }

  /**
   * Тип и сезон, принятые владельцем от ИИ (services/aititle.js), поверх разбора по файлам: «сериал» — видео по
   * порядку имён серий одного сезона, «сборник» — отдельные фильмы, «фильм» — главный файл. Сезон — только если сезон
   * в раздаче один (applyAiTitle в Model.kt).
   */
  function applyAiTitle(p, m) {
    var ai = m.aiTitle;
    if (!ai || ai.status !== 'accepted' || !ai.base) return p;
    var vids = m.files.filter(function (f) { return isVideo(f) && !SAMPLE.test(f.path); });
    var main = vids.filter(function (f) { return !EXTRAS.test(f.path); });
    if (!main.length) main = vids;
    var sorted = function () { return main.slice().sort(byNameNatural); };
    var out = p;
    if (ai.kind === 'movie' && p.kind === 'series') {
      var mv = mainVideo(m);
      if (mv) out = { kind: 'movie', file: mv };
    } else if (ai.kind === 'series' && (p.kind === 'movie' || isCollection(p)) && main.length >= 2) {
      var sn = ai.season > 0 ? ai.season : 1;
      out = { kind: 'series', seasons: [{ season: sn, items: sorted().map(function (f, i) {
        return { season: sn, episode: i + 1, file: f, title: null };
      }) }] };
    } else if (ai.kind === 'collection' && (p.kind === 'movie' || (p.kind === 'series' && !isCollection(p))) && main.length >= 2) {
      out = { kind: 'series', seasons: [{ season: 1, items: sorted().map(function (f, i) {
        return { season: 1, episode: i + 1, file: f, title: cleanTitle(basename(f.path)) || 'Фильм ' + (i + 1) };
      }) }] };
    }
    var s = ai.season;
    if (s > 0 && out.kind === 'series' && !isCollection(out) && out.seasons.length === 1 && out.seasons[0].season !== s) {
      out = { kind: 'series', seasons: [{ season: s, items: out.seasons[0].items.map(function (e) {
        return { season: s, episode: e.episode, file: e.file, title: e.title };
      }) }] };
    }
    return out;
  }

  function playableByFiles(m) {
    var vids = m.files.filter(function (f) { return isVideo(f) && !SAMPLE.test(f.path); });
    // Сначала структура папок: многие раздачи называют серии без SxxEyy.
    var bySeason = {}, order = [];
    vids.forEach(function (f) {
      var s = seasonOf(f.path);
      if (s == null) return;
      if (!bySeason[s]) { bySeason[s] = []; order.push(s); }
      bySeason[s].push(f);
    });
    if (order.length >= 2 || (order.length === 1 && vids.length > 1)) {
      order.sort(function (a, b) { return a - b; });
      return { kind: 'series', seasons: order.map(function (s) {
        return { season: s, items: bySeason[s].slice().sort(byNameNatural).map(function (f, i) {
          return { season: s, episode: i + 1, file: f, title: null };
        }) };
      }) };
    }
    var eps = [];
    vids.forEach(function (f) {
      var se = parseEpisode(f.path);
      if (se) eps.push({ season: se[0], episode: se[1], file: f, title: null });
    });
    if (eps.length >= 2) {
      var groups = {}, keys = [];
      eps.forEach(function (e) {
        if (!groups[e.season]) { groups[e.season] = []; keys.push(e.season); }
        groups[e.season].push(e);
      });
      keys.sort(function (a, b) { return a - b; });
      return { kind: 'series', seasons: keys.map(function (s) {
        return { season: s, items: groups[s].slice().sort(function (a, b) {
          return a.episode - b.episode || cmp(a.file.path, b.file.path);
        }) };
      }) };
    }
    var numbered = numberedEpisodesOf(m, vids);
    if (numbered) return numbered;
    var col = collectionOf(vids);
    if (col) return col;
    var main = mainVideo(m);
    return main ? { kind: 'movie', file: main } : { kind: 'none' };
  }

  var EXTRAS = /(?:^|\/)(?:extras?|bonus|featurettes?|behind[ ._-]?the[ ._-]?scenes|trailers?|доп[^\/]*|бонус[^\/]*|допы)(?:\/|$)/i;
  var BLURAY_DIR = /(?:^|\/)(?:BDMV|STREAM|VIDEO_TS)(?:\/|$)/i;
  var MB = 1024 * 1024;

  /** Несколько полнометражных файлов (каждый не меньше 1/4 самого большого и 300 МБ) вне папок Blu-ray/DVD. */
  function collectionOf(vids) {
    if (vids.length < 2 || vids.some(function (f) { return BLURAY_DIR.test(f.path); })) return null;
    var main = vids.filter(function (f) { return !EXTRAS.test(f.path); });
    var big = largest(main);
    if (!big) return null;
    var films = main.filter(function (f) { return f.size >= 300 * MB && f.size >= Math.floor(big.size / 4); });
    if (films.length < 2) return null;
    var items = films.slice().sort(byNameNatural).map(function (f, i) {
      return { season: 1, episode: i + 1, file: f, title: cleanTitle(basename(f.path)) || 'Фильм ' + (i + 1) };
    });
    return { kind: 'series', seasons: [{ season: 1, items: items }] };
  }

  // Без lookbehind (Chromium 56): «не цифра перед» — через (^|\D) и сдвиг индекса
  var NUM_NOISE = /(?:2160|1080|720|480|576)[pi]|[xh][ .]?26[45]|\d+(?:[.,]\d+)?\s?(?:fps|kbps|mbps|bit|gb|mb)|\b(?:19|20)\d{2}\b|\b[1-9]\.[01]\b/gi;
  var NUM = /(^|\D)(\d{1,3})(?!\d)/g;
  var SERIES_HINT = /(^|[^a-zа-яё0-9])(?:сезон[a-zа-яё]*|season[a-zа-яё]*|серии|серия|series|episodes?|эпизод[a-zа-яё]*|s\d{1,2}|ova|ona)(?![a-zа-яё0-9])/i;

  /** Номера в имени: [{ value, start, end }] (число отдельно, не часть большего). */
  function numbersIn(s) {
    var out = [], m;
    NUM.lastIndex = 0;
    while ((m = NUM.exec(s))) {
      var start = m.index + m[1].length;
      out.push({ value: parseInt(m[2], 10), start: start, end: start + m[2].length });
      NUM.lastIndex = start + m[2].length;
    }
    return out;
  }

  /**
   * Файлы, что отличаются одним номером («Show - 01», «Show - 02»), — серии, если так сказано в имени раздачи (сезон,
   * серии) или это похоже на сезон: пять и больше файлов похожего размера, пронумерованных подряд. Без этого трилогия
   * («Film 1», «Film 2») остаётся сборником.
   */
  function numberedEpisodesOf(m, vids) {
    if (vids.length < 2 || vids.some(function (f) { return BLURAY_DIR.test(f.path); })) return null;
    var main = vids.filter(function (f) { return !EXTRAS.test(f.path); });
    if (main.length < 2) return null;
    var parsed = main.map(function (f) {
      var base = stripExt(basename(f.path)).replace(DOTS, ' ').replace(NUM_NOISE, ' ');
      return { base: base, nums: numbersIn(base) };
    });
    var slots = Math.min.apply(null, parsed.map(function (x) { return x.nums.length; }));
    for (var slot = 0; slot < slots; slot++) {
      var nums = parsed.map(function (x) { return x.nums[slot].value; });
      if (new Set(nums).size !== nums.length) continue;
      var templates = {};
      parsed.forEach(function (x) { var n = x.nums[slot]; templates[x.base.substring(0, n.start) + '#' + x.base.substring(n.end)] = 1; });
      if (Object.keys(templates).length !== 1) continue;
      var sorted = nums.slice().sort(function (a, b) { return a - b; });
      var consecutive = sorted.every(function (v, i) { return i === 0 || v - sorted[i - 1] === 1; });
      var sizes = main.map(function (f) { return f.size; });
      var similar = Math.max.apply(null, sizes) <= Math.min.apply(null, sizes) * 2;
      if (!SERIES_HINT.test(m.name) && !(main.length >= 5 && consecutive && similar)) continue;
      var eps = main.map(function (f, i) { return { season: 1, episode: nums[i], file: f, title: null }; })
        .sort(function (a, b) { return a.episode - b.episode; });
      return { kind: 'series', seasons: [{ season: 1, items: eps }] };
    }
    return null;
  }

  var EXT = /\.[a-z0-9]{2,5}$/i;
  var TAGS = /\b(2160p|1080p|720p|480p|4k|8k|web[- ]?dl|web[- ]?rip|bluray|blu[- ]?ray|bdrip|bdremux|remux|hdr10?\+?|dv|dovi|hevc|h\.?265|x265|h\.?264|x264|aac|dts|ddp?5?\.?1|proper|repack|extended|limited|internal|amzn|nf|dsnp|hmax|yts|rarbg)\b[\s\S]*$/i;
  var YEAR_TAIL = /\b(19|20)\d{2}\b[\s\S]*$/;

  /** Имя раздачи → простое название для поиска постера (как escapeTitle сайта). */
  function cleanTitle(value) {
    var v = (typeof value === 'string' ? value : String(value == null ? '' : value)).trim() || 'Untitled';
    var s = v.replace(EXT, '').replace(DOTS, ' ');
    s = s.replace(TAGS, '');
    s = s.replace(YEAR_TAIL, ' ').replace(BRACKETS, ' ').replace(SPACES, ' ').trim();
    return s || v.replace(DOTS, ' ').trim();
  }

  // «Сезон»/«season» и т. п. без \p{L} и lookbehind: слово ограничено началом/не-буквой слева и не-буквой справа
  var SEASON_NOISE = /(^|[^a-zа-яё0-9])(?:\d{1,2}(?:\s*[-–]\s*\d{1,2})?\s*(?:сезон[a-zа-яё]*|season[a-zа-яё]*)|(?:сезон[a-zа-яё]*|season[a-zа-яё]*|серии|серия|series|episodes?)\s*\d{1,3}(?:\s*[-–]\s*\d{1,3})?|s\d{1,2}(?:\s*[-–]?\s*[se]\d{1,3})?|complete|полный|полностью|(?:сезон[a-zа-яё]*|season[a-zа-яё]*|серии|episodes))(?![a-zа-яё0-9])/gi;
  var NON_WORD = /[^a-zа-яё0-9]+/g;

  /** Ключ «того же названия» без пометок сезона: «Show S01», «Show Season 2», «Show [1-3 сезон]» — один сериал (titleKey в Model.kt). */
  function titleKey(baseTitle) {
    var plain = String(baseTitle).toLowerCase().replace(/ё/g, 'е');
    var key = plain.replace(SEASON_NOISE, '$1 ').replace(NON_WORD, ' ').trim();
    return key || plain.replace(NON_WORD, ' ').trim();
  }

  var YEAR_IN_NAME = /\b(19\d{2}|20\d{2})\b/;

  /**
   * Один фильм или сериал, добавленный несколько раз (другие релизы, озвучки, сезоны): одна плитка на всех
   * (groupVersions в Model.kt). Только готовые; фильмы с фильмами, сериалы и сборники — с сериалами; по названию без
   * пометок сезона (фильм — ещё и по году). Первый по порядку остаётся на экране; versions — id первого → все версии.
   */
  function groupVersions(list, posterOf) {
    var byKey = {}, groups = [], shown = [];
    list.forEach(function (it) {
      var base = groupTitle(it);
      if (it.playable.kind === 'none' || !it.m.ready || !base.trim()) { shown.push(it); return; }
      var kind = it.playable.kind === 'movie' ? 'm' : 's';
      // год принятого ответа ИИ важнее года в имени раздачи (groupVersions в Model.kt)
      var aiy = it.m.aiTitle && it.m.aiTitle.status === 'accepted' && it.m.aiTitle.base ? it.m.aiTitle.year : null;
      var y = aiy ? [null, String(aiy)] : YEAR_IN_NAME.exec(it.m.name);
      var keys = [];
      var poster = posterOf ? posterOf(it) : null;
      if (poster) keys.push(kind + '|poster|' + poster);
      keys.push(kind === 'm' ? 'm|' + titleKey(base) + '|' + (y ? y[1] : '') : 's|' + titleKey(base));
      var group = null;
      for (var i = 0; i < keys.length && !group; i++) group = byKey[keys[i]] || null;
      if (!group) { group = [it]; groups.push(group); shown.push(it); keys.forEach(function (k) { byKey[k] = group; }); }
      else { group.push(it); keys.forEach(function (k) { if (!byKey[k]) byKey[k] = group; }); }
    });
    var versions = {};
    groups.forEach(function (g) { if (g.length > 1) versions[g[0].m.id] = g; });
    return { shown: shown, versions: versions };
  }

  /** «Сезоны 1–3 · 24 серии» для строки выбора версии (seriesLine в Model.kt). */
  function seriesLine(p) {
    var nums = p.seasons.map(function (s) { return s.season; }).filter(function (n) { return n > 0; });
    var eps = p.seasons.reduce(function (a, s) { return a + s.items.length; }, 0);
    var first = p.seasons[0] && p.seasons[0].items[0];
    var films = !!(first && first.title);
    var mod10 = eps % 10, mod100 = eps % 100;
    var word = films ? 'фильм.' : mod10 >= 2 && mod10 <= 4 && !(mod100 >= 12 && mod100 <= 14) ? 'серии' : mod10 === 1 && mod100 !== 11 ? 'серия' : 'серий';
    var range = films || !nums.length ? '' : nums.length === 1 ? 'Сезон ' + nums[0] + ' · ' : 'Сезоны ' + Math.min.apply(null, nums) + '–' + Math.max.apply(null, nums) + ' · ';
    return range + eps + ' ' + word;
  }

  function formatBytes(value) {
    if (!(value > 0)) return '—';
    var units = ['B', 'KB', 'MB', 'GB', 'TB'], v = value, i = 0;
    while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
    return i === 0 ? value + ' B' : v.toFixed(1) + ' ' + units[i];
  }

  function statusRu(m) {
    if (m.ready) return 'Готово';
    if (m.failed) return m.status.trim() ? m.status : 'Ошибка';
    if (m.status.toLowerCase() === 'downloading') return 'Загрузка';
    if (!m.status.trim()) return 'Обработка';
    return m.status;
  }

  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  function episodeLabel(base, s, e) {
    if (e.title) return e.title;
    return base + ' · ' + (s.season === 0 ? 'Спец.' : 'S' + pad2(s.season)) + 'E' + pad2(e.episode);
  }

  /** Всё, что нужно плитке: считается один раз на загрузку медиатеки. */
  function prepareItems(list) {
    return list.map(function (m) {
      // принятое название ИИ (services/aititle.js) — и в плитке, и в поиске постера (titleOf в Model.kt)
      var ai = m.aiTitle, base = ai && ai.status === 'accepted' && ai.base ? ai.base : cleanTitle(m.name), main = mainVideo(m);
      // первым — оригинал с годом от ИИ («Dune (2021)»: сервер берёт результат TMDB того же года; posterQueriesOf в Model.kt)
      var queries = [];
      if (ai && ai.status === 'accepted' && ai.base && ai.original) queries.push(ai.year ? ai.original + ' (' + ai.year + ')' : ai.original);
      if (queries.indexOf(base) < 0) queries.push(base);
      if (main) {
        var q = cleanTitle(main.name.trim() ? main.name : basename(main.path));
        if (queries.indexOf(q) < 0) queries.push(q);
      }
      return {
        m: m, playable: buildPlayable(m), baseTitle: base, posterQueries: queries,
        searchText: (m.name + '\n' + m.files.map(function (f) { return f.path; }).join('\n') +
          (ai && ai.status === 'accepted' && ai.base ? '\n' + ai.base + '\n' + (ai.original || '') : '')).toLowerCase()
      };
    });
  }

  /** [name] — [base] плюс хвост: «show.s01e01.rus.srt» для «show.s01e01», но не «show.s01e10.srt». */
  function sameBase(name, base) {
    return name.indexOf(base) === 0 && (name.length === base.length || !isLetterOrDigit(name[base.length]));
  }

  function videoCount(m) { return m.files.filter(function (f) { return isVideo(f) && !SAMPLE.test(f.path); }).length; }

  var SUB_EXT = /\.(srt|ass|ssa|vtt|sub)$/i;

  /** Субтитры видео: сначала по имени, иначе из той же папки или её «Subs»; при одном видео — любые. */
  function subtitlesFor(m, video) {
    var subs = m.files.filter(function (f) { return !f.isFolder && f.link && SUB_EXT.test(f.path); });
    if (!subs.length) return [];
    var vPath = norm(video.path), vDir = dirname(vPath), vBase = stripExt(basename(vPath)).toLowerCase();
    var byName = subs.filter(function (f) { return sameBase(basename(f.path).toLowerCase(), vBase); });
    if (byName.length) return byName.slice(0, 8);
    if (videoCount(m) <= 1) return subs.slice(0, 8);
    return subs.filter(function (f) {
      var d = dirname(f.path);
      return d === vDir || (d.indexOf(vDir + '/') === 0 && basename(d).toLowerCase().indexOf('sub') === 0);
    }).slice(0, 8);
  }

  var AUDIO_EXT = /\.(mka|ac3|eac3|dts|aac|flac|mp3|m4a|opus|thd|wav)$/i;

  /** Внешние озвучки («RUS Sound/Show.S01E02.mka»): по имени; при одном видео — любой аудиофайл. */
  function externalAudioFor(m, video) {
    var audio = m.files.filter(function (f) { return !f.isFolder && f.link && AUDIO_EXT.test(f.path); });
    if (!audio.length) return [];
    var vBase = stripExt(basename(video.path)).toLowerCase();
    var byName = audio.filter(function (f) { return sameBase(basename(f.path).toLowerCase(), vBase); });
    if (byName.length) return byName.slice(0, 8);
    return videoCount(m) <= 1 ? audio.slice(0, 8) : [];
  }

  /** Название озвучки из раздачи: хвост имени после имени видео («rus LostFilm»), иначе папка («LostFilm»), иначе файл. */
  function dubLabel(video, audio) {
    var vBase = stripExt(basename(video.path)), name = stripExt(basename(audio.path));
    var rest = name.toLowerCase().indexOf(vBase.toLowerCase()) === 0 ? name.substring(vBase.length) : name;
    rest = rest.replace(/[._]+/g, ' ').replace(/^[\s-]+|[\s-]+$/g, '');
    return rest || basename(dirname(audio.path)) || basename(audio.path);
  }

  /** Озвучка пользователя в меню — как на ПК (CustomTrack.menuName): «HDRezka [rus]», без названия — имя файла. */
  function customName(t) {
    var lang = t.lang && t.lang !== 'und' ? t.lang : '', title = str(t.title).trim();
    if (lang && title.slice(-(lang.length + 3)) === ' [' + lang + ']') title = title.slice(0, -(lang.length + 3)).trim();
    return (title || basename(str(t.audioPath))) + (lang ? ' [' + lang + ']' : '');
  }

  /**
   * Озвучки отдельным файлом для видео: из раздачи ([dubs] — externalAudioFor) и пользователей ([custom] — дорожки
   * /api/custom-audio этого видео). { key, kind: 'file' | 'custom', label, link | id }; key — для выбора в tv-info.
   */
  function externalChoices(video, dubs, custom) {
    var out = (dubs || []).map(function (f) { return { key: 'f:' + f.link, kind: 'file', link: f.link, label: dubLabel(video, f) }; });
    (custom || []).forEach(function (t) {
      if (t.videoPath === video.path && /\.mka$/i.test(str(t.audioPath))) out.push({ key: 'c:' + t.id, kind: 'custom', id: t.id, label: customName(t) });
    });
    return out;
  }

  /** Ответ tv-info: внешняя озвучка, что соответствует выбору [choice] (по ссылке файла или id дорожки). */
  function externalIn(info, choice) {
    var list = info && Array.isArray(info.external) ? info.external : [];
    for (var i = 0; i < list.length; i++) {
      var x = list[i];
      if (choice.kind === 'file' ? x.kind === 'file' && x.link === choice.link : x.kind === 'custom' && x.id === choice.id) return x;
    }
    return null;
  }

  /**
   * Раздача только с озвучками (.mka) — внешняя озвучка к фильму («Добавить» → «Озвучка» на ПК): она играет внутри
   * фильма, отдельной плиткой в медиатеке не показывается (как Library.isVoiceOverOnly на ПК).
   */
  function isVoiceOverOnly(m) {
    var files = m.files.filter(function (f) { return !f.isFolder; });
    return files.length > 0 && files.every(function (f) { return /\.mka$/i.test(f.path); });
  }

  function watchKey(magnetId, file) { return magnetId + '|' + file.path; }

  function watchInfo(pos, len, at) {
    return {
      pos: pos, len: len, at: at,
      fraction: len > 0 ? Math.min(1, Math.max(0, pos / len)) : 0,
      watched: len > 0 && pos >= len - 15000
    };
  }

  SM.model = {
    parseLibrary: parseLibrary, magnet: magnet, mainVideo: mainVideo, buildPlayable: buildPlayable,
    cleanTitle: cleanTitle, titleKey: titleKey, groupTitle: groupTitle, groupVersions: groupVersions, seriesLine: seriesLine, formatBytes: formatBytes, statusRu: statusRu, episodeLabel: episodeLabel,
    prepareItems: prepareItems, subtitlesFor: subtitlesFor, externalAudioFor: externalAudioFor,
    isVoiceOverOnly: isVoiceOverOnly, dubLabel: dubLabel, customName: customName, externalChoices: externalChoices, externalIn: externalIn,
    watchKey: watchKey, watchInfo: watchInfo, basename: basename
  };
  if (typeof module === 'object' && module.exports) module.exports = SM.model;
})(typeof window !== 'undefined' ? window : global);

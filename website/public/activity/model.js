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
  // Один сезон в имени раздачи («Show.S02.1080p», «Сезон: 2», «2-й сезон»); диапазон («S01-S03», «1-3 сезон») — ничего
  var NAME_SEASON_RANGE = /(?:season|сезон)[a-zа-яё]*[ ._:]*\d{1,2}[ ._]*[-–][ ._]*\d|\d[ ._]*[-–][ ._]*\d{1,2}[ ._]*(?:-?[a-zа-яё]{1,2}[ ._]*)?(?:season|сезон)|(?:^|[^a-zа-яё0-9])s\d{1,2}[ ._]*[-–][ ._]*s?\d/i;
  var NAME_SEASON = /(?:^|[^a-zа-яё0-9])s(\d{1,2})(?=e\d|[^a-zа-яё0-9]|$)|(?:season|сезон)[ ._:]*(\d{1,2})(?!\d)|(?:^|\D)(\d{1,2})[ ._]*(?:-?(?:й|ый|ий|st|nd|rd|th))?[ ._]*(?:season|сезон)(?![a-zа-яё0-9])/gi;
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

  /** Сезон из имени раздачи, если он там один (nameSeason в Model.kt): серии без сезона в файлах берут его, а не 1. */
  function nameSeason(name) {
    name = str(name);
    if (NAME_SEASON_RANGE.test(name)) return null;
    var nums = {}, m;
    NAME_SEASON.lastIndex = 0;
    while ((m = NAME_SEASON.exec(name))) nums[parseInt(m[1] || m[2] || m[3], 10)] = 1;
    var keys = Object.keys(nums);
    var n = keys.length === 1 ? parseInt(keys[0], 10) : 0;
    return n > 0 ? n : null;
  }

  function parseEpisode(path, defaultSeason) {
    var p = norm(path), name = basename(p), m;
    var fs = ANY_SEASON.exec(p) || DIR_S.exec(p);
    var folderSeason = fs ? parseInt(fs[1], 10) : null;
    if ((m = SXXEYY.exec(name) || SXXEYY.exec(p))) return [parseInt(m[1], 10), parseInt(m[2], 10)];
    if ((m = NXNN.exec(name))) return [parseInt(m[1], 10), parseInt(m[2], 10)];
    if ((m = EP_WORD.exec(name))) return [folderSeason != null ? folderSeason : (defaultSeason || 1), parseInt(m[1], 10)];
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

  // Сборник фильмов (isCollection / collectionKind в Model.kt). Без \p{L} и lookbehind (Chromium 56): слово ограничено
  // началом или не-буквой слева и не-буквой справа. Сильные слова называют набор фильмов; слабые («сага», «коллекция»,
  // «антология») бывают и у сериалов («Сага о Винланде»), поэтому считаются, только если файлы — фильмы разных лет.
  var WL = 'a-zа-яё0-9';
  var COLLECTION_STRONG = new RegExp('(^|[^' + WL + '])(?:трилоги[яи]|дилоги[яи]|тетралоги[яи]|квадр[ио]логи[яи]|пенталоги[яи]|гексалоги[яи]|франшиз[аеы]|киносаг[аеиы]|trilogy|duology|dilogy|tetralogy|quadrilogy|franchise|\\d{1,2}\\s*[-–—]?\\s*(?:в|in)\\s*1|все\\s+части|все\\s+фильмы|\\d{1,2}\\s+фильм(?:а|ов))(?![' + WL + '])', 'i');
  var COLLECTION_WEAK = new RegExp('(^|[^' + WL + '])(?:коллекци[яи]|сборник|антологи[яи]|саг[аеиы]|boxset|box[- ]set|collection|anthology|saga)(?![' + WL + '])', 'i');
  function word(re) { return new RegExp('(^|[^' + WL + '])(?:' + re + ')(?![' + WL + '])', 'i'); }
  var KINDS = [
    [word('трилоги[яи]|trilogy'), 'Кинотрилогия'], [word('дилоги[яи]|duology|dilogy'), 'Дилогия'],
    [word('квадр[ио]логи[яи]|тетралоги[яи]|quadrilogy|tetralogy'), 'Кинотетралогия']
  ];
  var KINDS_LATE = [
    [word('сборник'), 'Сборник'], [word('антологи[яи]|anthology'), 'Антология'], [word('коллекци[яи]|collection|boxset|box[- ]set'), 'Коллекция']
  ];

  /** У каждого файла в имени год, и годы разные: фильмы франшизы, а не серии. */
  function filmsByYear(files) {
    var years = files.map(function (f) { var y = YEAR_IN_NAME.exec(basename(f.path)); return y ? y[1] : null; });
    return years.every(Boolean) && new Set(years).size >= 2;
  }

  /** Имя раздачи говорит, что это набор фильмов, и не говорит про сезон. */
  function namedCollection(m, files) {
    return !SERIES_HINT.test(m.name) && (COLLECTION_STRONG.test(m.name) || (COLLECTION_WEAK.test(m.name) && filmsByYear(files)));
  }

  /** Принятый тип от ИИ решает сам; иначе фильмы с названиями или сильное слово в имени без пометок сезона. */
  function isCollection(p, m) {
    var ai = m && m.aiTitle;
    if (ai && ai.status === 'accepted' && ai.base && ai.kind) return ai.kind === 'collection';
    if (p.kind !== 'series') return false;
    if (p.seasons[0] && p.seasons[0].items[0] && p.seasons[0].items[0].title != null) return true;
    return !!m && COLLECTION_STRONG.test(m.name) && !SERIES_HINT.test(m.name);
  }

  /** «Кинотрилогия», «Коллекция»…: сначала слова имени, потом число фильмов (collectionKind в Model.kt). */
  function collectionLabel(filmCount, name) {
    var i;
    for (i = 0; i < KINDS.length; i++) if (KINDS[i][0].test(name)) return KINDS[i][1];
    if (filmCount === 2) return 'Дилогия';
    if (filmCount === 3) return 'Кинотрилогия';
    if (filmCount === 4) return 'Кинотетралогия';
    for (i = 0; i < KINDS_LATE.length; i++) if (KINDS_LATE[i][0].test(name)) return KINDS_LATE[i][1];
    return 'Кинофраншиза';
  }

  function pluralRu(n, one, few, many) {
    var m10 = n % 10, m100 = n % 100;
    return m10 === 1 && m100 !== 11 ? one : m10 >= 2 && m10 <= 4 && !(m100 >= 12 && m100 <= 14) ? few : many;
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
    // Файлы без своей пометки сезона берут сезон из имени раздачи
    var named = nameSeason(m.name) || 1;
    var eps = [];
    vids.forEach(function (f) {
      var se = parseEpisode(f.path, named);
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
    var numbered = numberedEpisodesOf(m, vids, named);
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
  function numberedEpisodesOf(m, vids, season) {
    if (vids.length < 2 || vids.some(function (f) { return BLURAY_DIR.test(f.path); })) return null;
    var main = vids.filter(function (f) { return !EXTRAS.test(f.path); });
    if (main.length < 2) return null;
    // «Пила: Трилогия», «Коллекция 1996-2018» с фильмами по годам — фильмы, а не серии (их берёт collectionOf)
    if (namedCollection(m, main)) return null;
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
      var eps = main.map(function (f, i) { return { season: season, episode: nums[i], file: f, title: null }; })
        .sort(function (a, b) { return a.episode - b.episode; });
      return { kind: 'series', seasons: [{ season: season, items: eps }] };
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
  var SEASON_RANGE_REGEX = /(?:season|сезон)[a-zа-яё]*[\s._-]*(\d{1,2})[\s._-]*[-–][\s._-]*(\d{1,2})|(\d{1,2})[\s._-]*[-–][\s._-]*(\d{1,2})[\s._-]*(?:season|сезон)[a-zа-яё]*/i;

  var YEAR_BRACKETED = /[\(\[\{](19\d{2}|20\d{2})[\)\]\}]/;
  var YEARS = /\b(19\d{2}|20\d{2})\b/g;
  var LATEST_YEAR = new Date().getFullYear() + 2;

  /** Год выпуска (1900…следующий год + 1): сначала «(2001)», потом отдельный год (extractYear в Model.kt). */
  function extractYear(name) {
    if (!name) return null;
    var b = YEAR_BRACKETED.exec(name);
    if (b && +b[1] <= LATEST_YEAR) return +b[1];
    var all = name.match(YEARS) || [];
    for (var i = 0; i < all.length; i++) if (+all[i] <= LATEST_YEAR) return +all[i];
    return null;
  }

  function itemYear(item) {
    if (!item) return null;
    if (item.m && item.m.aiTitle && item.m.aiTitle.status === 'accepted' && item.m.aiTitle.year) {
      return item.m.aiTitle.year;
    }
    var y = extractYear(item.m ? item.m.name : '');
    if (y) return y;
    y = extractYear(item.baseTitle || '');
    if (y) return y;
    if (item.m && item.m.files) {
      for (var i = 0; i < item.m.files.length; i++) {
        y = extractYear(item.m.files[i].path || '');
        if (y) return y;
      }
    }
    return null;
  }

  function parseRoman(s) {
    switch ((s || '').toUpperCase()) {
      case 'I': return 1;
      case 'II': return 2;
      case 'III': return 3;
      case 'IV': return 4;
      case 'V': return 5;
      case 'VI': return 6;
      case 'VII': return 7;
      case 'VIII': return 8;
      case 'IX': return 9;
      case 'X': return 10;
      default: return null;
    }
  }

  var PART_EXPLICIT = new RegExp('(^|[^' + WL + '])(?:часть|ч\\.|фильм|film|part|pt\\.?|глава|chapter)\\s*(\\d{1,2}|[ivx]{1,4})(?![' + WL + '])', 'i');
  var PART_ORDINAL = new RegExp('(^|[^' + WL + '])(\\d{1,2})\\s*[-–]?\\s*(?:я|ая|ья|ей|й|ый|ой)?\\s*(?:часть|фильм)(?![' + WL + '])', 'i');
  function escapeRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

  /**
   * Номер части: «Part 2», «Часть 3», «2-я часть» или число / римская цифра сразу после названия («Rocky IV», «Тачки 2»).
   * Римская цифра в другом месте имени — не часть: «X-Men» — не десятая (itemPart в Model.kt).
   */
  function itemPart(item) {
    if (!item || !item.m) return null;
    var text = item.m.name || '';
    var m = PART_EXPLICIT.exec(text);
    if (m) {
      var n = parseInt(m[2], 10);
      if (!isNaN(n)) return n;
      var r = parseRoman(m[2]);
      if (r) return r;
    }
    var ord = PART_ORDINAL.exec(text);
    if (ord) return parseInt(ord[2], 10);
    if (item.baseTitle) {
      var after = new RegExp('(^|[^' + WL + '])' + escapeRe(item.baseTitle) + '\\s+(\\d{1,2}|[IVX]{1,4})(?![' + WL + '])', 'i').exec(text);
      if (after) {
        var a = parseInt(after[2], 10);
        return isNaN(a) ? parseRoman(after[2]) : a;
      }
    }
    return null;
  }

  function qualityScore(name) {
    var lower = (name || '').toLowerCase();
    if (/2160p|4k|3840/.test(lower)) return 4;
    if (/1080p|1080i|1920/.test(lower)) return 3;
    if (/720p|1280/.test(lower)) return 2;
    if (/480p|576p|dvd/.test(lower)) return 1;
    return 0;
  }

  function itemSeasons(item) {
    if (!item) return null;
    if (item.m && item.m.aiTitle && item.m.aiTitle.status === 'accepted' && typeof item.m.aiTitle.season === 'number' && item.m.aiTitle.season >= 0) {
      return { min: item.m.aiTitle.season, max: item.m.aiTitle.season };
    }
    if (item.playable && item.playable.kind === 'series') {
      var seasons = item.playable.seasons || [];
      if (seasons.length > 0 && seasons[0].items && seasons[0].items[0] && seasons[0].items[0].title) return null; // film collection
      var nums = seasons.map(function (s) { return s.season; }).filter(function (n) { return n >= 0; });
      if (nums.length) {
        return { min: Math.min.apply(null, nums), max: Math.max.apply(null, nums) };
      }
    }
    var name = item.m ? item.m.name || '' : '';
    var range = SEASON_RANGE_REGEX.exec(name);
    if (range) {
      var rmin = parseInt(range[1] || range[3], 10);
      var rmax = parseInt(range[2] || range[4], 10);
      if (!isNaN(rmin) && !isNaN(rmax)) return { min: rmin, max: rmax };
    }
    var single = /(?:season|сезон)[\s._-]?(\d{1,2})|s(\d{1,2})[\s._-]?e\d{1,3}/i.exec(name);
    if (single) {
      var s = parseInt(single[1] || single[2], 10);
      if (!isNaN(s)) return { min: s, max: s };
    }
    return null;
  }

  var LAST = Number.MAX_SAFE_INTEGER;

  /**
   * Сезоны по порядку (спецвыпуски после них, без сезона — в конце), потом часть, потом год (у кого нет — после), потом
   * лучшее качество и больший размер (sortVersions в Model.kt). Ключ считается один раз на версию и сравнивается поле за
   * полем: так порядок всегда последовательный (сравнение, что пропускает поле, если его нет у одной стороны, зацикливается).
   */
  function sortVersions(list) {
    if (!list || list.length <= 1) return list ? list.slice() : [];
    var keys = list.map(function (it) {
      var s = itemSeasons(it), p = itemPart(it), y = itemYear(it);
      return [
        s ? (s.min === 0 ? LAST - 1 : s.min) : LAST, s ? s.max - s.min : 0, p != null ? p : LAST, y != null ? y : LAST,
        -qualityScore(it.m ? it.m.name : ''), -(it.m ? it.m.size : 0), it
      ];
    });
    keys.sort(function (a, b) {
      for (var i = 0; i < 6; i++) if (a[i] !== b[i]) return a[i] < b[i] ? -1 : 1;
      return ((a[6].m && a[6].m.name) || '').localeCompare((b[6].m && b[6].m.name) || '') || ((a[6].m && a[6].m.id) || 0) - ((b[6].m && b[6].m.id) || 0);
    });
    return keys.map(function (k) { return k[6]; });
  }

  /** Первая строка версии в «Какую версию включить?» без качества: «Кинотрилогия • 3 фильма», «Сезоны 1–3 · 24 серии», «2021 год». */
  function versionLine(v) {
    var p = v.playable;
    if (p && p.kind === 'series') {
      if (isCollection(p, v.m)) {
        var n = p.seasons.reduce(function (a, s) { return a + s.items.length; }, 0);
        return collectionLabel(n, v.m.name) + ' • ' + n + ' ' + pluralRu(n, 'фильм', 'фильма', 'фильмов');
      }
      return seriesLine(p);
    }
    var y = itemYear(v);
    return y ? y + ' год' : '';
  }

  /**
   * Один фильм или сериал, добавленный несколько раз (другие релизы, озвучки, сезоны): одна плитка на всех
   * (groupVersions в Model.kt). Только готовые; фильмы с фильмами, сериалы и сборники — с сериалами; по названию без
   * пометок сезона (фильм — ещё и по году). Первый по порядку остаётся на экране; versions — id первого → все версии (умно отсортированы).
   */
  function groupVersions(list, posterOf) {
    var byKey = {}, groups = [], shown = [];
    list.forEach(function (it) {
      var base = it.baseTitle; // принятое название ИИ уже в baseTitle (prepareItems)
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
    groups.forEach(function (g) { if (g.length > 1) versions[g[0].m.id] = sortVersions(g); });
    return { shown: shown, versions: versions };
  }

  /** «Сезоны 1–3 · 24 серии» для строки выбора версии (seriesLine в Model.kt). */
  function seriesLine(p) {
    var nums = p.seasons.map(function (s) { return s.season; }).filter(function (n) { return n > 0; });
    var eps = p.seasons.reduce(function (a, s) { return a + s.items.length; }, 0);
    var first = p.seasons[0] && p.seasons[0].items[0];
    var films = !!(first && first.title);
    var word = films ? pluralRu(eps, 'фильм', 'фильма', 'фильмов') : pluralRu(eps, 'серия', 'серии', 'серий');
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

  /**
   * Подпись плитки: принятое название ИИ, иначе русское название TMDB по запросу постера ([ruTitleOf] — title_ru сервера),
   * иначе название раздачи. У сборника — своё: TMDB находит один его фильм. Как tileTitle в Model.kt.
   */
  function tileTitle(item, ruTitleOf) {
    var ai = item.m && item.m.aiTitle;
    if ((ai && ai.status === 'accepted' && ai.base) || isCollection(item.playable, item.m)) return item.baseTitle;
    var qs = item.posterQueries || [];
    for (var i = 0; i < qs.length; i++) { var t = ruTitleOf(qs[i]); if (t) return t; }
    return item.baseTitle;
  }

  function watchInfo(pos, len, at) {
    return {
      pos: pos, len: len, at: at,
      fraction: len > 0 ? Math.min(1, Math.max(0, pos / len)) : 0,
      watched: len > 0 && pos >= len - 15000
    };
  }

  SM.model = {
    parseLibrary: parseLibrary, magnet: magnet, mainVideo: mainVideo, buildPlayable: buildPlayable,
    cleanTitle: cleanTitle, titleKey: titleKey, groupVersions: groupVersions, seriesLine: seriesLine, versionLine: versionLine,
    sortVersions: sortVersions, itemYear: itemYear, itemPart: itemPart, isCollection: isCollection, collectionLabel: collectionLabel,
    qualityScore: qualityScore, itemSeasons: itemSeasons, nameSeason: nameSeason,
    formatBytes: formatBytes, statusRu: statusRu, episodeLabel: episodeLabel,
    prepareItems: prepareItems, subtitlesFor: subtitlesFor, externalAudioFor: externalAudioFor,
    isVoiceOverOnly: isVoiceOverOnly, dubLabel: dubLabel, customName: customName, externalChoices: externalChoices, externalIn: externalIn,
    watchKey: watchKey, watchInfo: watchInfo, basename: basename, tileTitle: tileTitle
  };
  if (typeof module === 'object' && module.exports) module.exports = SM.model;
})(typeof window !== 'undefined' ? window : global);

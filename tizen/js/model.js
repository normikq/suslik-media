/*
 * Медиатека: разбор /api/library, фильм или сериал по сезонам, субтитры и внешние озвучки рядом с видео.
 * Перенос shared/.../core/Model.kt один в один (те же правила, что у сайта и приложений); тесты — test/model.test.js.
 * Только ES2017 (Tizen 4.0, Chromium 56): без ?., ??, spread в объектах, lookbehind и \p{…}.
 */
(function (g) {
  'use strict';
  var SM = g.SM = g.SM || {};

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
        canRemove: m.canRemove !== false, favorite: m.favorite === true, mine: m.mine === true
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
  function buildPlayable(m) {
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
      var base = cleanTitle(m.name), main = mainVideo(m);
      var queries = [base];
      if (main) {
        var q = cleanTitle(main.name.trim() ? main.name : basename(main.path));
        if (queries.indexOf(q) < 0) queries.push(q);
      }
      return {
        m: m, playable: buildPlayable(m), baseTitle: base, posterQueries: queries,
        searchText: (m.name + '\n' + m.files.map(function (f) { return f.path; }).join('\n')).toLowerCase()
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
    cleanTitle: cleanTitle, formatBytes: formatBytes, statusRu: statusRu, episodeLabel: episodeLabel,
    prepareItems: prepareItems, subtitlesFor: subtitlesFor, externalAudioFor: externalAudioFor,
    watchKey: watchKey, watchInfo: watchInfo, basename: basename
  };
  if (typeof module === 'object' && module.exports) module.exports = SM.model;
})(typeof window !== 'undefined' ? window : global);

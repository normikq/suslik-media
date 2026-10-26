/*
 * Поиск раздач: разбор названия релиза, фильтры, сортировка, оценка битрейта — как на ПК.
 * Перенос shared/.../core/search (ReleaseParser, SearchFilter, SearchHit) один в один; тесты — test/searchcore.test.js.
 * Копия этого файла лежит в website/public/searchcore.js: правьте оба.
 * Только ES2017 (Tizen 4.0, Chromium 56): без lookbehind и \p{…}; «граница слова» для кириллицы — явными классами.
 */
(function (g) {
  'use strict';
  var SM = g.SM = g.SM || {};

  // \b из Java (UNICODE_CHARACTER_CLASS) для кириллицы: слово — буквы, цифры и «_»
  var WC = 'a-zа-яё0-9_';
  var L = '(?:^|[^' + WC + '])';           // начало слова (\b слева)
  var R = '(?![' + WC + '])';              // конец слова (\b справа)
  var L_ = '(?:^|[^a-zа-яё0-9])';          // (?:\b|_) слева: «_» тоже граница
  var R_ = '(?![a-zа-яё0-9])';
  function re(src, flags) { return new RegExp(src, flags || 'i'); }
  function b(inner) { return re(L_ + '(?:' + inner + ')' + R_); }   // (?:\b|_)(?:inner)(?:\b|_)
  function w(inner) { return re(L + '(?:' + inner + ')' + R); }     // \b(?:inner)\b

  var RES_4K = b('2160p|4k|uhd|3840x2160|4к');
  var RES_1080P = b('1080[pi]|fhd|full[- ]?hd|1920x1080');
  var RES_720P = b('720p|1280x720');
  var RES_SD = b('480[pi]|576[pi]|sd');

  var CODEC_HEVC = b('hevc|h\\.?265|x265');
  var CODEC_AVC = b('avc|h\\.?264|x264');
  var CODEC_AV1 = b('av1');
  var CODEC_OTHER = b('xvid|divx|mpeg-?2');

  var RIP_REMUX = b('bd[- ]?remux|remux');
  var RIP_CAM = re('(?:' + L_ + '(?:cam[- ]?rip|cam|hdcam|telesync|hd-?ts|экранка)' + R_ + ')|\\[ts\\]|\\|\\s*ts' + R + '|звук\\s*с\\s*ts|' + L + '(?:dvdscr|scr)' + R);
  var RIP_BDRIP = b('bd[- ]?rip|brrip|bdrip-hevc');
  var RIP_BLURAY = b('bluray|blu[- ]?ray|bdmv');
  var RIP_WEBDL = b('web-?dl(?:rip)?|webdl(?:rip)?');
  var RIP_WEBRIP = b('web-?rip|webrip');
  var RIP_HDTV = b('hdtv(?:rip)?|tvrip|satrip');
  var RIP_DVDRIP = b('dvd[- ]?rip|dvd[59]');

  var HDR_DV = b('dolby\\s*vision|dovi|dv');
  var HDR_10PLUS = b('hdr10\\+|hdr10plus');
  var HDR_10 = b('hdr10?');

  var VOICE_DUB = re('(?:' + L_ + '(?:дублированный|дубляж|лицензия|dub)' + R + ')|(?:[|\\[(\\s](?:d|дб)' + R + ')|\\[дубляж\\]');
  var VOICE_MVO = re(L_ + '(?:многоголосый|профессиональный\\s*\\(многоголосый\\)|пм|mvo)' + R);
  var VOICE_DVO = re(L_ + '(?:двухголосый|профессиональный\\s*\\(двухголосый\\)|пд|dvo)' + R);
  var VOICE_AVO = re(L_ + '(?:авторский|одноголосый|avo)' + R);
  var VOICE_ORIG = b('субтитры|оригинал|original|eng|jap');

  var EP_OF = re(L + '\\d{1,3}\\s*(?:из|of)\\s*(\\d{1,3})' + R);
  var EP_RANGE = re('\\[\\d{1,2}(?:-\\d{1,2})?x\\d{1,2}-(\\d{1,3})\\]');
  var EP_WORD_RANGE = re(L + '(?:сери[йи]|эпизод[оыв]?)\\s*\\d{1,3}-(\\d{1,3})' + R);
  var EP_SX_EX = re('S\\d{1,2}E\\d{1,3}-E?(\\d{1,3})');
  var SERIES_HINT = w('сезон|сериал|season|s\\d{1,2}|сери[яи]|эпизод');

  var COLLECTION_WORDS = re('(?:' + L + '(?:коллекци[яи]|collection|boxset|box-set|антологи[яи]|anthology|все\\s+части|все\\s+фильмы|все\\s+выпуски|полная\\s+коллекция|полное\\s+собрание|франшиза|киносага|сага|franchise)' + R + ')|\\[коллекция\\]|\\(коллекция\\)|\\[антология\\]|\\(антология\\)');
  var COLLECTION_COUNT = re(L + '(\\d{1,2})\\s*(?:фильм[а-ов]?|movies?|parts?|частей|части|фильма)' + R + '|' + L + '(?:части|фильмы|parts|movies)\\s*1\\s*[-–—]\\s*(\\d{1,2})' + R);
  var TRILOGY = w('трилоги[яи]|trilogy');
  var DUOLOGY = w('дилоги[яи]|duology|dilogy');
  var QUADRILOGY = w('квадрологи[яи]|тетралоги[яи]|quadrilogy|tetralogy');
  var PENTALOGY = w('пенталоги[яи]');
  var HEXALOGY = w('гексалоги[яи]');

  var YEAR_RANGE = re(L + '(19\\d{2}|20\\d{2})\\s*[-–—]\\s*(19\\d{2}|20\\d{2})' + R);
  var YEAR_REGEX = re(L + '(19\\d{2}|20\\d{2})' + R);

  var TECH_SPLIT = re('(?:\\s*[|]\\s*|\\s+(?:2160p|1080[pi]|720p|480p|4k|bdremux|remux|bluray|blu-ray|bdrip|web-?dl(?:rip)?|web-?rip|hdtv(?:rip)?|dvdrip|camrip|telesync|x26[45]|hevc|avc|h\\.?26[45]|aac|dts|ddp?5?\\.?1)' + R + ')');
  var TRAILING_PUNCTUATION = /[\s|\/\\-]+$/;

  var GAME_STRONG = re('(?:' + L_ + '(?:fpkg|xci|nsp|fitgirl|xatab|dodi|decepticon|steam-?rip|gog|cusa\\d+|ppsa\\d+)' + R_ + '|' +
    L + '(?:rus\\s*text|eng\\s*text|rus\\s*sound|eng\\s*sound)' + R + '|' +
    L + '(?:goty|game\\s*of\\s*the\\s*year)' + R + '|' +
    '(?:таблетка|кряк|crack)\\s*[:(]?\\s*(?:вшита|вшит|присутствует|не требуется))');
  var GAME_WEAK = new RegExp('(?:' + L_ + '(?:repack|рипак|репак|pkg|pc)' + R_ + '|' +
    L + '(?:digital\\s*deluxe|deluxe\\s*edition|gold\\s*edition|ultimate\\s*edition)' + R + '|' +
    L + 'v\\d+\\.\\d+(?:\\.\\d+)?' + R + ')', 'gi');
  var TRAILER_OR_TEASER = re(L + '(?:тизер(?:[- ]?трейлер)?(?:ы|а|ов|у|е|ом|ам|ами)?|трейлер(?:ы|а|ов|у|е|ом|ам|ами)?|teasers?|trailers?)' + R + '(?!\\s*(?:парк|park))');

  function isTrailerOrTeaser(title) { return TRAILER_OR_TEASER.test(title); }

  var VIDEO_FORUM_WHITELIST = ['музыкальное видео', 'видеоклип', 'клип', 'концерт', 'экранизац', 'по играм', 'по книг'];
  var NON_VIDEO_FORUM = ['игр', 'game', 'ps3', 'ps4', 'ps5', 'playstation', 'xbox', 'nintendo', 'switch', 'программ', 'софт', 'soft', 'windows',
    'macos', 'linux', 'драйвер', 'книги и журналы', 'аудиокниг', 'литератур', 'электронные книги', 'комикс', 'музыка (lossless)', 'дискографи',
    'lossless', 'flac', 'саундтрек', 'музыка mp3', 'обои', 'картинк', 'фотографии', '3d модел'];

  function has(list, f) { return list.some(function (k) { return f.indexOf(k) >= 0; }); }

  /** Игры, софт, музыка, книги, трейлеры — не кино. */
  function isNonVideo(title, forumCategory) {
    var cat = forumCategory || '';
    if (cat.trim()) {
      var f = cat.toLowerCase();
      if (!has(VIDEO_FORUM_WHITELIST, f)) {
        if (has(NON_VIDEO_FORUM, f)) return true;
        if ((f.indexOf('музык') >= 0 || f.indexOf('music') >= 0) && f.indexOf('видео') < 0 && f.indexOf('клип') < 0 && f.indexOf('концерт') < 0) return true;
        if ((f.indexOf('книг') >= 0 || f.indexOf('book') >= 0) && f.indexOf('экранизац') < 0) return true;
      }
    }
    if (GAME_STRONG.test(title)) return true;
    if (isTrailerOrTeaser(title)) return true;
    var m = title.match(GAME_WEAK);
    return !!m && m.length >= 2;
  }

  var GB = 1024 * 1024 * 1024;

  function toInt(m, i) { var v = m && m[i] ? parseInt(m[i], 10) : NaN; return isNaN(v) ? null : v; }

  /** { cleanTitle, year, resolution: '4K'|'1080p'|'720p'|'SD'|'', codec: 'HEVC'|'AVC'|'AV1'|'OTHER'|'', ripType, hdr, voice, isCam, ... } */
  function parse(rawTitle, sizeBytes, kind) {
    var s = String(rawTitle || '').trim();
    sizeBytes = Number(sizeBytes) || 0;
    kind = kind || 'movie';
    var isSeries = kind === 'series' || SERIES_HINT.test(s);

    var resolution = RES_4K.test(s) ? '4K' : RES_1080P.test(s) ? '1080p' : RES_720P.test(s) ? '720p' : RES_SD.test(s) ? 'SD' : '';
    var codec = CODEC_HEVC.test(s) ? 'HEVC' : CODEC_AV1.test(s) ? 'AV1' : CODEC_AVC.test(s) ? 'AVC' : CODEC_OTHER.test(s) ? 'OTHER' : '';
    var isCam = RIP_CAM.test(s);
    var ripType = isCam ? 'CAM' : RIP_REMUX.test(s) ? 'REMUX' : RIP_BDRIP.test(s) ? 'BDRIP' : RIP_BLURAY.test(s) ? 'BLURAY' :
      RIP_WEBDL.test(s) ? 'WEBDL' : RIP_WEBRIP.test(s) ? 'WEBRIP' : RIP_HDTV.test(s) ? 'HDTV' : RIP_DVDRIP.test(s) ? 'DVDRIP' : '';
    var hdr = HDR_DV.test(s) ? 'DV' : HDR_10PLUS.test(s) ? 'HDR10_PLUS' : HDR_10.test(s) ? 'HDR10' : '';
    var voice = VOICE_DUB.test(s) ? 'DUB' : VOICE_MVO.test(s) ? 'MVO' : VOICE_DVO.test(s) ? 'DVO' : VOICE_AVO.test(s) ? 'AVO' : VOICE_ORIG.test(s) ? 'ORIGINAL' : '';

    var yr = YEAR_RANGE.exec(s);
    var yearStart = yr ? parseInt(yr[1], 10) : null, yearEnd = yr ? parseInt(yr[2], 10) : null;
    var yearSpan = yearStart != null && yearEnd != null ? yearEnd - yearStart : 0;
    var y1 = YEAR_REGEX.exec(s);
    var year = yearStart != null ? yearStart : y1 ? parseInt(y1[1], 10) : null;

    var specificCount = TRILOGY.test(s) ? 3 : DUOLOGY.test(s) ? 2 : QUADRILOGY.test(s) ? 4 : PENTALOGY.test(s) ? 5 : HEXALOGY.test(s) ? 6 : null;
    var cc = COLLECTION_COUNT.exec(s);
    var explicitCount = cc ? (toInt(cc, 1) != null ? toInt(cc, 1) : toInt(cc, 2)) : null;
    var isCollection = !isSeries && (COLLECTION_WORDS.test(s) || specificCount != null || (explicitCount != null && explicitCount > 1) || yearSpan >= 3);
    var collectionCount = null;
    if (isCollection) {
      if (specificCount != null) collectionCount = specificCount;
      else if (explicitCount != null) collectionCount = explicitCount;
      else if (yearSpan >= 3) collectionCount = Math.min(10, Math.max(2, Math.floor(yearSpan / 3)));
      else if (sizeBytes > 15 * GB) collectionCount = Math.min(8, Math.max(2, Math.floor(sizeBytes / (12 * GB))));
    }

    var episodesCount = null;
    if (isSeries) {
      var e;
      episodesCount = (e = toInt(EP_OF.exec(s), 1)) != null ? e : (e = toInt(EP_RANGE.exec(s), 1)) != null ? e :
        (e = toInt(EP_WORD_RANGE.exec(s), 1)) != null ? e : toInt(EP_SX_EX.exec(s), 1);
    }

    var bitrate = bitrateMbps(sizeBytes, isSeries, episodesCount, isCollection, collectionCount);
    var tier = bitrate <= 4.0 ? 'LIGHT' : bitrate <= 12.0 ? 'MEDIUM' : 'HEAVY';
    var info = {
      cleanTitle: cleanReleaseTitle(s), year: year, resolution: resolution, codec: codec, ripType: ripType, hdr: hdr, voice: voice,
      isCam: isCam, isCollection: isCollection, collectionCount: collectionCount, episodesCount: episodesCount,
      estimatedBitrateMbps: bitrate, bitrateTier: tier
    };
    info.isMobileFriendly = !isCam && resolution !== '4K' && ripType !== 'REMUX' && ripType !== 'BLURAY' &&
      ((bitrate >= 0.05 && bitrate <= 4.5) || (bitrate === 0 && resolution === '720p'));
    return info;
  }

  function bitrateMbps(sizeBytes, isSeries, episodesCount, isCollection, collectionCount) {
    if (sizeBytes <= 0) return 0;
    var seconds;
    if (isSeries) seconds = (episodesCount != null ? episodesCount : sizeBytes > 5 * GB ? 10 : 1) * 2700;
    else if (isCollection) seconds = (collectionCount != null ? collectionCount : 4) * 6300;
    else seconds = 6300;
    var mbps = (sizeBytes * 8) / (seconds * 1000000);
    return Math.round(mbps * 10) / 10;
  }

  function cleanReleaseTitle(raw) {
    var s = String(raw || '').trim();
    if (!s) return '';
    var spaces = (s.match(/ /g) || []).length, dots = (s.match(/\./g) || []).length;
    if (dots > 3 && dots > spaces * 2) s = s.replace(/\./g, ' ');
    var m = TECH_SPLIT.exec(s);
    var clean = m && m.index > 3 ? s.substring(0, m.index).trim() : s;
    var result = clean.replace(TRAILING_PUNCTUATION, '').trim();
    if (/\($/.test(result) && result.indexOf(')') < 0) result = result.slice(0, -1).trim();
    if (/\[$/.test(result) && result.indexOf(']') < 0) result = result.slice(0, -1).trim();
    return result || String(raw).trim();
  }

  // ---- фильтры и сортировка (SearchFilter.kt) ----

  var PRESETS = [
    { id: 'ALL', title: 'Все' }, { id: 'MOBILE', title: '📱 Мобильная сеть' },
    { id: 'BALANCED', title: 'Оптимальные (1080p)' }, { id: 'MAX_QUALITY', title: 'Макс. качество' }
  ];
  var SORTS = [{ id: 'DEFAULT', title: 'Обычная' }, { id: 'SIZE_ASC', title: 'Сначала лёгкие' }, { id: 'SEEDS_DESC', title: 'По сидам' }];

  var MEDIA_STOP_WORDS = re(L + '(?:фильм[ыа-я]*|кино(?:фильм[ыа-я]*)?|сериал[ыа-я]*|мульт(?:фильм[ыа-я]*|ик[иа-я]*)?|аниме|смотреть|скачать|онлайн|в\\s*хорошем\\s*качестве)' + R, 'gi');

  /** Убирает из запроса «фильм», «сериал», «скачать»… (SearchQueryCleaner). */
  function cleanQuery(raw) {
    var s = String(raw || '').trim();
    var stripped = s.replace(MEDIA_STOP_WORDS, function (m) { return /^[^a-zа-яё0-9_]/i.test(m) ? m.charAt(0) + ' ' : ' '; }).replace(/\s+/g, ' ').trim();
    return stripped.length >= 2 ? stripped : s;
  }

  /** Раздача из ответа /api/search → hit с разобранным релизом. */
  function toHit(r) {
    if (!r || !r.id) return null;
    var src = Array.isArray(r.sources) && r.sources.length > 1 ? r.sources.join(' + ') : String(r.provider || '');
    var d = typeof r.date === 'number' && r.date > 0 ? new Date(r.date).toLocaleDateString('ru-RU') : typeof r.date === 'string' ? r.date.substring(0, 10) : '';
    var kind = r.kind ? String(r.kind) : 'movie', title = String(r.title || ''), size = Number(r.size) || 0;
    return {
      id: String(r.id), title: title, size: size, seeders: Number(r.seeders) || 0, leechers: Number(r.leechers) || 0,
      ru: r.ru === true, kind: kind, source: src, date: d, release: parse(title, size, kind),
      magnet: typeof r.magnet === 'string' && r.magnet.indexOf('magnet:?') === 0 ? r.magnet : null
    };
  }

  function matches(hit, c) {
    if (isTrailerOrTeaser(hit.title) || isTrailerOrTeaser(hit.release.cleanTitle)) return false;
    var kind = c.kind || 'video';
    if (!(kind === 'all' || (kind === 'video' ? hit.kind !== 'other' : hit.kind === kind))) return false;
    if (c.onlySeeded && hit.seeders <= 0) return false;
    if (c.onlyRu && !hit.ru) return false;
    var preset = c.preset || 'ALL', rel = hit.release;
    if ((preset === 'MOBILE' || preset === 'BALANCED') && rel.isCam) return false;
    if (preset === 'MOBILE') {
      if (!rel.isMobileFriendly) return false;
      var multi = hit.kind === 'series' || rel.episodesCount != null || rel.isCollection;
      if (!multi && hit.size > 4 * GB && rel.estimatedBitrateMbps > 4.5) return false;
    } else if (preset === 'BALANCED') {
      if (rel.resolution === '4K' || hit.size > 14 * GB) return false;
    } else if (preset === 'MAX_QUALITY') {
      if (!(rel.resolution === '4K' || rel.ripType === 'REMUX' || rel.ripType === 'BLURAY' || rel.hdr !== '')) return false;
    }
    if (c.resolution && rel.resolution !== c.resolution) return false;
    if (c.refine && c.refine.trim()) {
      var words = c.refine.toLowerCase().split(' ').filter(function (x) { return x.trim(); });
      var t1 = hit.title.toLowerCase(), t2 = rel.cleanTitle.toLowerCase();
      if (!words.every(function (x) { return t1.indexOf(x) >= 0 || t2.indexOf(x) >= 0; })) return false;
    }
    return true;
  }

  var WORD_SPLIT = /[^a-zA-Z0-9а-яА-ЯёЁ]+/;

  function relevance(hit, query) {
    if (!query || !query.trim()) return hit.seeders;
    var nq = query.trim().toLowerCase().replace(/-/g, ' ');
    var qWords = nq.split(WORD_SPLIT).filter(function (x) { return x.length >= 2; });
    if (!qWords.length) return hit.seeders;
    var nt = hit.title.toLowerCase().replace(/-/g, ' '), nc = hit.release.cleanTitle.toLowerCase().replace(/-/g, ' ');
    var score = 0;
    if (nc.indexOf(nq) === 0) score += 2000;
    else if (nt.indexOf(nq) === 0) score += 1600;
    else if (nc.indexOf(nq) >= 0) score += 1000;
    else if (nt.indexOf(nq) >= 0) score += 700;
    var inClean = qWords.filter(function (x) { return nc.indexOf(x) >= 0; }).length;
    var inTitle = qWords.filter(function (x) { return nt.indexOf(x) >= 0; }).length;
    var maxMatched = Math.max(inClean, inTitle);
    if (maxMatched === qWords.length) score += 500; else score += maxMatched * 80;
    var last = -1, inOrder = true;
    for (var i = 0; i < qWords.length; i++) {
      var idx = nc.indexOf(qWords[i]);
      if (idx === -1 || idx < last) { inOrder = false; break; }
      last = idx;
    }
    if (inOrder && maxMatched === qWords.length) score += 350;
    if (nc && maxMatched === qWords.length) {
      var diff = Math.abs(nc.length - nq.length);
      if (diff < 15) score += 200; else if (diff < 30) score += 100;
    }
    var ym = YEAR_REGEX.exec(nq);
    if (ym) {
      var ty = parseInt(ym[1], 10);
      if (hit.release.year === ty || nc.indexOf(ym[1]) >= 0 || nt.indexOf(ym[1]) >= 0) score += 1500;
      else if (hit.release.year != null && hit.release.year !== ty) score -= 1500;
    }
    if (hit.seeders > 0) score += Math.min(hit.seeders, 50) * 2; else score -= 250;
    if (hit.release.resolution === '4K' || hit.release.resolution === '1080p') score += 40;
    else if (hit.release.resolution === '720p') score += 20;
    if (hit.release.isCam) score -= 300;
    return score;
  }

  /** Фильтр + сортировка. [c]: { query, kind, preset, resolution, onlySeeded, onlyRu, refine, sort }. Не меняет [hits]. */
  function filter(hits, c) {
    var list = hits.filter(function (h) { return !isNonVideo(h.title) && matches(h, c); });
    var order = new Map(list.map(function (h, i) { return [h, i]; })); // sort в Chromium 56 не устойчивый
    var sort = c.sort || 'DEFAULT';
    var cmp;
    if (sort === 'SIZE_ASC') cmp = function (a, b) { return (a.size <= 0 ? Infinity : a.size) - (b.size <= 0 ? Infinity : b.size) || b.seeders - a.seeders; };
    else if (sort === 'SIZE_DESC') cmp = function (a, b) { return b.size - a.size; };
    else if (sort === 'SEEDS_DESC') cmp = function (a, b) { return b.seeders - a.seeders; };
    else if (sort === 'BITRATE_ASC') cmp = function (a, b) {
      return (a.release.estimatedBitrateMbps <= 0 ? Infinity : a.release.estimatedBitrateMbps) - (b.release.estimatedBitrateMbps <= 0 ? Infinity : b.release.estimatedBitrateMbps) || b.seeders - a.seeders;
    };
    else {
      var scores = new Map(list.map(function (h) { return [h, c.query && c.query.trim() ? relevance(h, c.query) : h.seeders]; }));
      cmp = function (a, b) { return (scores.get(b) - scores.get(a)) || b.seeders - a.seeders; };
    }
    return list.sort(function (a, b) { return cmp(a, b) || order.get(a) - order.get(b); });
  }

  var RIP_LABEL = { REMUX: 'BDRemux', BLURAY: 'Blu-ray', BDRIP: 'BDRip', WEBDL: 'WEB-DL', WEBRIP: 'WEBRip', HDTV: 'HDTV', DVDRIP: 'DVDRip', CAM: 'CAM / Экранка' };
  var HDR_LABEL = { DV: 'Dolby Vision', HDR10_PLUS: 'HDR10+', HDR10: 'HDR' };
  var VOICE_LABEL = { DUB: 'Дубляж', MVO: 'MVO', DVO: 'DVO', AVO: 'AVO', ORIGINAL: 'Оригинал/Sub' };

  /** Метки релиза для карточки: [{ text, tone }] (tone — класс цвета: res4k, res, codec, hdr, cam, remux, rip, coll, voice, light, medium, heavy). */
  function badges(r) {
    var out = [];
    if (r.resolution) out.push({ text: r.resolution, tone: r.resolution === '4K' ? 'res4k' : 'res' });
    if (r.codec === 'HEVC' || r.codec === 'AV1' || r.codec === 'AVC') out.push({ text: r.codec, tone: 'codec' });
    if (r.hdr) out.push({ text: HDR_LABEL[r.hdr], tone: 'hdr' });
    if (r.ripType) out.push({ text: RIP_LABEL[r.ripType], tone: r.ripType === 'CAM' ? 'cam' : r.ripType === 'REMUX' || r.ripType === 'BLURAY' ? 'remux' : 'rip' });
    if (r.isCollection) out.push({ text: r.collectionCount != null ? 'Коллекция (' + r.collectionCount + ')' : 'Коллекция', tone: 'coll' });
    if (r.voice) out.push({ text: VOICE_LABEL[r.voice], tone: 'voice' });
    if (r.estimatedBitrateMbps > 0) {
      var t = r.estimatedBitrateMbps < 1 ? '< 1 Мбит/с' : '~' + r.estimatedBitrateMbps.toFixed(1) + ' Мбит/с';
      out.push({ text: r.isCollection ? t + ' / фильм' : t, tone: r.bitrateTier.toLowerCase() });
    }
    return out;
  }

  SM.searchcore = {
    parse: parse, cleanReleaseTitle: cleanReleaseTitle, isNonVideo: isNonVideo, isTrailerOrTeaser: isTrailerOrTeaser, cleanQuery: cleanQuery,
    toHit: toHit, filter: filter, relevance: relevance, badges: badges, PRESETS: PRESETS, SORTS: SORTS
  };
  if (typeof module === 'object' && module.exports) module.exports = SM.searchcore;
})(typeof window !== 'undefined' ? window : global);

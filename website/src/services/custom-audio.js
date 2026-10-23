"use strict";

const fs = require("fs");
const crypto = require("crypto");
const config = require("../config");
const { makeJsonWriter } = require("../utils/json-writer");

// ---- Внешние озвучки, привязанные к фильму ---------------------------------------------------------------------
// Озвучка — аудиофайл из другой раздачи AllDebrid, привязанный к видеофайлу фильма. Здесь только связки;
// сами файлы клиенты берут через зеркало по свежей подписанной ссылке.
// audio-tracks.json: { "targetMagnetId|targetVideoPath": [ { id, audioMagnetId, audioPath, title, lang, offsetMs, addedBy, createdAt } ] }
const AUDIO_EXT = new Set(["mka"]);
const VIDEO_EXT = new Set(["mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm", "wmv", "mpg", "mpeg", "flv", "vob", "m2v"]);
const EXEC_EXT = new Set(["exe", "bat", "cmd", "sh", "com", "msi", "scr", "ps1", "vbs", "js", "jar", "apk", "dll", "lnk"]);
const ARCHIVE_EXT = new Set(["zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz"]);
const IMAGE_EXT = new Set(["iso", "img", "bin", "mdf", "nrg"]);
// Описание, обложка, контрольные суммы — обычные спутники раздачи: не играются и не мешают (не повод отклонять озвучку)
const IGNORED_EXT = new Set(["txt", "nfo", "md", "jpg", "jpeg", "png", "webp", "gif", "bmp", "sfv", "md5", "sha1", "sha256", "url", "log", "cue"]);
const MAX_TOTAL = 5000;
// На один видеофайл: у сериала лимит не делится на все серии раздачи
const MAX_PER_VIDEO = 50;
const MAX_OFFSET_MS = 60_000;
const TRACK_ID_RE = /^trk_[a-f0-9]{8}$/;
// Путь видео в ключе — не длиннее 600 символов (MAX_VIDEO_PATH)
const MAX_VIDEO_PATH = 600;
const KEY_RE = /^(\d{1,20})\|(.{1,600})$/s;
// Минус в теге и смещении: ASCII, типографский (U+2212) или короткое тире (U+2013)
const toAsciiMinus = s => String(s).replace(/[−–]/g, "-");

const extOf = p => (/\.([a-z0-9]{1,6})$/i.exec(p) || [])[1]?.toLowerCase() || "";
const baseName = p => String(p).split("/").pop();
const stem = p => baseName(p).replace(/\.[a-z0-9]{1,6}$/i, "");

// ---- Чекер форматов (раздел 4) ----------------------------------------------------------------------------------
// В раздаче с озвучкой разрешены только аудиофайлы из белого списка. Всё остальное — видео, исполняемые файлы,
// архивы, образы и прочее — нарушение; описание и обложки (IGNORED_EXT) пропускаются.
// Возвращает { ok, forbidden: [{ path, ext, kind }], ignored: [path] }.
function checkFormats(paths) {
  const forbidden = [];
  const ignored = [];
  for (const path of paths) {
    const ext = extOf(path);
    if (AUDIO_EXT.has(ext)) continue;
    if (IGNORED_EXT.has(ext)) { ignored.push(path); continue; }
    const kind = VIDEO_EXT.has(ext) ? "video" : EXEC_EXT.has(ext) ? "executable" : ARCHIVE_EXT.has(ext) ? "archive" : IMAGE_EXT.has(ext) ? "image" : "other";
    forbidden.push({ path, ext, kind });
  }
  return { ok: forbidden.length === 0, forbidden, ignored };
}

// ---- Разбор имени озвучки (раздел 3) ----------------------------------------------------------------------------
const TECH = new RegExp(
  "(?<![a-z0-9])(?:\\d{3,4}[pi]|[hx]\\.?26[45]|hevc|avc|web-?dl|web-?rip|blu-?ray|bdrip|brrip|hdtv|dvdrip|remux|e-?ac-?3|ac-?3|ddp(?:\\s*[2578]\\.[01])?|dd(?:\\+|(?:\\s*[2578]\\.[01]))?|atmos|dts(?:-?hd)?(?:-?ma)?|true-?hd|aac|flac|mp3|opus|\\d{2,4}\\s?kbps|[2578]\\.[01]|[2578]ch|stereo|hdr10?|(?:8|10|12)[ ._-]?bit|\\d{2,3}[ ._-]?fps)(?![a-z0-9])",
  "gi"
);

function cleanText(s) {
  return String(s || "")
    .replace(TECH, " ")
    .replace(/[._]+/g, " ")
    .replace(/\[\s*\]|\(\s*\)/g, " ")
    .replace(/\s+/g, " ")
    .replace(/^[\s\-–—)\]]+|[\s\-–—(\[]+$/g, "")
    .trim();
}

const SERIES_RE = /(?<![a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,3})(?![0-9])/i;
const YEAR_RE = /(?<![a-z0-9])((?:19|20)\d{2})(?![a-z0-9])/g;
const SEASON_TOKEN_RE = /[ ._(\-]+(?:season|сезон|[sс][0oо]?)[\s._\-]*(\d{1,2})\)?$/i;
// Папка сезона целиком: «Season 01», «Сезон 1», «S01»
const SEASON_DIR_RE = /^(?:(?:season|сезон)[ ._-]*|[sс][0oо]?)(\d{1,2})$/i;
// Номер серии без сезона: E01, «Серия 1», 01.Озвучка, 01 - Pilot
const EP_ONLY_RE = /(?:^|[ ._\-\[(])(?:ep?|серия)[ ._-]*(\d{1,3})(?![0-9])|^(\d{1,3})(?=$|[ ._\-\[(])/i;
const LANG_TAG_RE = /\[([a-z]{2,3})\]/i;
// Коды языков ISO 639-1/639-2 для тега [xx]/[xxx]: [HQ], [RAW], [Dub] — не языки
const LANGS = new Set(`ru rus en eng uk ukr be bel kk kaz de deu ger fr fra fre es spa it ita ja jpn ko kor zh zho chi
  pt por pl pol tr tur ar ara he heb hi hin cs ces cze nl nld dut sv swe no nor fi fin da dan hu hun ro ron rum bg bul
  sr srp hr hrv sk slk slo sl slv lt lit lv lav et est ka kat geo hy hye arm az aze uz uzb tt tat th tha vi vie id ind
  ms msa may el ell gre fa fas per und mul`.split(/\s+/));
const cleanLang = v => {
  const s = typeof v === "string" ? v.trim().toLowerCase() : "";
  return LANGS.has(s) ? s : null;
};
// Первый тег языка из списка LANGS или null
const langTag = text => {
  for (const m of String(text || "").matchAll(new RegExp(LANG_TAG_RE.source, "gi"))) {
    const l = cleanLang(m[1]);
    if (l) return l;
  }
  return null;
};
const lastYear = s => [...String(s).matchAll(/(?<![a-z0-9])((?:19|20)\d{2})(?![a-z0-9])/g)].reverse().find(m => m.index > 0);

/**
 * Имя файла озвучки -> { title, year, season, episode, voice, delayMs, layout }.
 * title === null — фильм определить нельзя. delayMs === null — тега [delay=…ms] нет.
 * layout: "file" (всё в имени) или "dir" (название берётся из папки).
 */
function parseAudioName(path) {
  const parts = String(path).split("/");
  const file = parts.pop();
  const dirs = parts;
  let name = file.replace(/\.[a-z0-9]{1,6}$/i, "");
  let delayMs = null;
  name = name.replace(/\[\s*delay\s*=\s*([+\-−–]?\d{1,7})\s*ms\s*\]/gi, (_, n) => {
    delayMs = Number(toAsciiMinus(n));
    return "";
  });
  const out = { title: null, year: null, season: null, episode: null, voice: "", delayMs, lang: langTag(name), layout: "file" };
  // Тег языка в названии фильма (Severance.[rus].S01E01) мешает сопоставлению; в озвучке он остаётся («HDRezka [rus]»)
  const titleText = s => cleanText(String(s).replace(new RegExp(LANG_TAG_RE.source, "gi"), " "));

  let se = SERIES_RE.exec(name);
  if (!se) {
    const nxm = /(?<![0-9])(\d{1,2})x(\d{2,3})(?![0-9])/i.exec(name);
    if (nxm) se = { 0: nxm[0], 1: nxm[1], 2: nxm[2], index: nxm.index };
  }
  if (!se && dirs.length) {
    const epOnly = EP_ONLY_RE.exec(name);
    if (epOnly) {
      const sDir = [...dirs].reverse().map(d => SEASON_DIR_RE.exec(d.trim()) || SEASON_TOKEN_RE.exec(d)).find(Boolean);
      if (sDir) se = { 0: epOnly[0], 1: sDir[1], 2: epOnly[1] || epOnly[2], index: epOnly.index };
    }
  }

  if (se) {
    out.season = Number(se[1]);
    out.episode = Number(se[2]);
    out.voice = cleanText(name.slice(se.index + se[0].length));
    let title = titleText(name.slice(0, se.index));
    if (!title) {
      // Season 01/S01E01.Озвучка: название — в папке выше сезона
      const dir = [...dirs].reverse().find(d => !SEASON_DIR_RE.test(d.trim()));
      if (dir) { title = titleText(dir); out.layout = "dir"; }
    }
    const sm = SEASON_TOKEN_RE.exec(title || "");
    if (sm) {
      if (out.season === null) out.season = Number(sm[1]);
      title = cleanText(title.slice(0, sm.index));
    }
    const y = lastYear(title || "");
    if (y) { out.year = Number(y[1]); title = cleanText(title.slice(0, y.index)); }
    out.title = title || null;
    return out;
  }

  const y = lastYear(name);
  if (y) {
    out.year = Number(y[1]);
    out.title = titleText(name.slice(0, y.index)) || null;
    out.voice = cleanText(name.slice(y.index + y[0].length));
    return out;
  }
  // Название Фильма (Год)/Озвучка, Inception.2010/Озвучка, Inception 2010 1080p/Озвучка
  const last = dirs.length ? dirs[dirs.length - 1].trim() : "";
  const dy = lastYear(last);
  const dirTitle = dy ? titleText(last.slice(0, dy.index)) : "";
  if (dirTitle) {
    out.title = dirTitle;
    out.year = Number(dy[1]);
    out.voice = cleanText(name);
    out.layout = "dir";
  }
  return out;
}

// ---- Сопоставление с раздачей фильма (раздел 5) -----------------------------------------------------------------
const words = s => String(s).toLowerCase().replace(/ё/g, "е").replace(/[^\p{L}\p{N}]+/gu, " ").trim();
// Короче этого слитное сравнение («Spider-Man» и «SpiderMan») не делаем: «Up» нашлось бы в «backup»
const LOOSE_MIN = 6;

// Подходит ли название из имени озвучки к раздаче: по названию раздачи, именам файлов и папок.
// Название ищется целыми словами; длинное — ещё и без учёта пробелов.
function titleMatches(parsed, movie) {
  const wt = words(parsed.title || "");
  const nt = wt.replace(/ /g, "");
  if (nt.length < 2) return false;
  const text = [movie.name, ...(movie.files || []).map(f => f.path)].filter(Boolean).join(" ");
  const wtext = words(text);
  const found = ` ${wtext} `.includes(` ${wt} `) || (nt.length >= LOOSE_MIN && wtext.replace(/ /g, "").includes(nt));
  if (!found) return false;
  if (parsed.year) {
    // Разрешение 1920x1080 — не год
    const years = [...text.replace(/\d{3,4}\s*[x×]\s*\d{3,4}/gi, " ").matchAll(/(?<![0-9])((?:19|20)\d{2})(?![0-9])/g)].map(m => Number(m[1]));
    if (years.length && !years.includes(parsed.year)) return false;
  }
  return true;
}

const epLabel = (s, e) => `S${String(s).padStart(2, "0")}E${String(e).padStart(2, "0")}`;

// Серия -> номер сезона/серии в имени видеофайла (S01E02, 1x02); сезон может быть неизвестен (null)
function videoEpisode(path) {
  const name = stem(path);
  let m = SERIES_RE.exec(name) || /(?<![0-9])(\d{1,2})x(\d{2,3})(?![0-9])/i.exec(name);
  if (m) return { season: Number(m[1]), episode: Number(m[2]) };
  // Без годов и технических меток: H.264, 10.bit, 60.fps — не номера серий
  const bare = name.replace(YEAR_RE, " ").replace(TECH, " ");
  // «Show 2 - 01», «Show.2.-.01», «Show 2x01», «2 - 01»: сезон 2, серия 1
  m = /(?<![0-9])([1-9]\d?)\s*[-_]\s*(\d{2,3})(?![0-9])/.exec(bare);
  if (m) return { season: Number(m[1]), episode: Number(m[2]) };
  // E01, «Серия 1», 01.1080p, 01 - Pilot, Show.01.1080p
  m = EP_ONLY_RE.exec(name) || /[ ._-](\d{2,3})(?=$|[ ._-]|v\d)/.exec(bare);
  if (m) {
    const sMatch = /(?<![a-z0-9])(?:season|сезон)[ ._-]*(\d{1,2})(?![0-9])|(?<![a-z0-9])[sс][0oо]?(\d{1,2})(?![0-9])/i.exec(path);
    const season = sMatch ? Number(sMatch[1] || sMatch[2]) : null;
    return { season, episode: Number(m[1] || m[2]) };
  }
  return null;
}

/**
 * Строгое сопоставление озвучек (audioFiles — файлы с type "file") с раздачей фильма movie { name, files }.
 * Ошибка в любом файле — добавление запрещено. Возвращает { ok, pairs, errors: [{ code, message, path? }] }.
 * Коды: UNRESOLVED (не определили фильм/серию), TITLE_MISMATCH (озвучка к другому фильму),
 *       EPISODE_MISMATCH (серий нет в раздаче фильма), NO_VIDEO (в раздаче фильма нет видео),
 *       BAD_OFFSET (смещение в теге превышает допустимый лимит).
 */
function matchToMovie(audioFiles, movie, { explicit = false } = {}) {
  const errors = [];
  const pairs = [];
  const videos = (movie.files || []).filter(f => f && f.type === "file" && VIDEO_EXT.has(extOf(f.path)));
  if (!videos.length) {
    return { ok: false, pairs, errors: [{ code: "NO_VIDEO", message: "В раздаче с фильмом нет видеофайлов." }] };
  }
  const eps = videos.map(v => ({ v, e: videoEpisode(v.path) }));
  const maxEpisode = eps.reduce((n, x) => Math.max(n, x.e?.episode || 0), 0);
  const missing = [];

  // Односезонная ли раздача: в видеофайлах только один сезон (или сезон не указан)
  const videoSeasons = new Set(eps.map(x => x.e?.season).filter(s => s !== null && s !== undefined));
  // И в аудиофайлах только один сезон (или сезон не указан)
  const audioParsed = audioFiles.map(a => parseAudioName(a.path));
  const audioSeasons = new Set(audioParsed.map(p => p.season).filter(s => s !== null && s !== undefined));
  const singleSeason = videoSeasons.size <= 1 && audioSeasons.size <= 1;

  // Один видеофайл для фильма: единственный, либо заметно самый большой (>= 1.5x следующего).
  // Размеры неизвестны (0/null) — выбрать нельзя.
  const bySize = [...videos].sort((a, b) => (b.size || 0) - (a.size || 0));
  const top = bySize[0].size || 0;
  const mainVideo = videos.length === 1 ? videos[0] : top > 0 && top >= (bySize[1].size || 0) * 1.5 ? bySize[0] : null;

  for (const a of audioFiles) {
    const p = parseAudioName(a.path);
    const unresolved = why => errors.push({ code: "UNRESOLVED", path: a.path, message: `Не удалось однозначно определить, к какому ${why} относится файл «${baseName(a.path)}». Назовите его по стандарту: Название.Год.Озвучка или Название.SxxEyy.Озвучка.` });
    if (!p.title) { unresolved(p.episode ? "сериалу" : "фильму"); continue; }
    if (p.delayMs !== null && Math.abs(p.delayMs) > MAX_OFFSET_MS) {
      errors.push({ code: "BAD_OFFSET", path: a.path, message: `Смещение ${p.delayMs} мс превышает допустимый предел ±${MAX_OFFSET_MS} мс.` });
      continue;
    }
    if (!titleMatches(p, movie)) {
      errors.push({ code: "TITLE_MISMATCH", path: a.path, title: p.title, message: `Фильм ${p.title} не найден в библиотеке. Сначала добавьте раздачу с фильмом.` });
      continue;
    }
    let target = null;
    if (p.episode !== null) {
      const hit =
        eps.find(x => x.e && x.e.season === p.season && x.e.episode === p.episode) ||
        // Серии без сезона в имени считаются первым сезоном: S02E01 к ним не подходит
        ((p.season === null || p.season === 1) && eps.find(x => x.e && x.e.season === null && x.e.episode === p.episode)) ||
        // Если раздача выбрана явно пользователем (movieMagnetId) или для выбора среди вариантов:
        // для односезонного сериала сопоставляем серии по номеру серии
        (explicit && singleSeason && eps.find(x => x.e && x.e.episode === p.episode));
      if (!hit) { missing.push(epLabel(p.season, p.episode)); continue; }
      target = hit.v;
    } else {
      target = mainVideo;
      if (!target) { unresolved("видеофайлу (в раздаче фильма их несколько)"); continue; }
    }
    pairs.push({
      videoPath: target.path,
      audioPath: a.path,
      voice: p.voice,
      season: p.season,
      episode: p.episode,
      offsetMs: p.delayMs,
      lang: p.lang,
      by: p.layout
    });
  }
  if (missing.length) {
    errors.push({
      code: "EPISODE_MISMATCH",
      episodes: missing,
      message: `В раздаче озвучки есть серии (${missing.slice(0, 10).join(", ")}${missing.length > 10 ? "…" : ""}), которых нет в раздаче фильма${maxEpisode ? ` (последняя серия — ${maxEpisode})` : ""}.`
    });
  }
  return { ok: errors.length === 0, pairs: errors.length ? [] : pairs, errors };
}

// ---- Разбор .torrent (bencode) без загрузки в AllDebrid: нужен только список файлов -----------------------------
function bdecode(buf) {
  let i = 0;
  const fail = () => { throw new Error("Некорректный .torrent файл."); };
  const parse = depth => {
    if (depth > 20 || i >= buf.length) fail();
    const c = buf[i];
    if (c === 0x69) {
      const end = buf.indexOf(0x65, i);
      if (end < 0) fail();
      const n = Number(buf.toString("latin1", i + 1, end));
      if (!Number.isFinite(n)) fail();
      i = end + 1;
      return n;
    }
    if (c === 0x6c || c === 0x64) {
      i++;
      const isDict = c === 0x64;
      // Без прототипа: ключи __proto__/constructor из торрента — обычные данные
      const out = isDict ? Object.create(null) : [];
      while (buf[i] !== 0x65) {
        if (i >= buf.length) fail();
        if (isDict) {
          const k = parse(depth + 1);
          if (!Buffer.isBuffer(k)) fail();
          out[k.toString("utf8")] = parse(depth + 1);
        } else out.push(parse(depth + 1));
      }
      i++;
      return out;
    }
    if (c >= 0x30 && c <= 0x39) {
      const colon = buf.indexOf(0x3a, i);
      if (colon < 0) fail();
      const len = Number(buf.toString("latin1", i, colon));
      if (!Number.isSafeInteger(len) || len < 0 || colon + 1 + len > buf.length) fail();
      const s = buf.subarray(colon + 1, colon + 1 + len);
      i = colon + 1 + len;
      return s;
    }
    return fail();
  };
  return parse(0);
}

// Плоский список файлов торрента как у flattenMagnetFiles ({ path, type: "file", size }); sourceLink нет.
// Возвращает { name, files }
function torrentFiles(bytes) {
  const root = bdecode(bytes);
  const info = root && root.info;
  // name.utf-8 — имя в UTF-8, когда name записан в другой кодировке (как path.utf-8 у файлов)
  const nameBuf = info && typeof info === "object" ? (Buffer.isBuffer(info["name.utf-8"]) ? info["name.utf-8"] : info.name) : null;
  if (!Buffer.isBuffer(nameBuf)) throw new Error("В торренте нет списка файлов.");
  const top = nameBuf.toString("utf8");
  if (Array.isArray(info.files)) {
    const files = info.files
      .map(f => {
        const parts = Array.isArray(f["path.utf-8"]) ? f["path.utf-8"] : f.path;
        if (!Array.isArray(parts) || !parts.length) return null;
        return { path: [top, ...parts.map(p => p.toString("utf8"))].join("/"), type: "file", size: Number(f.length) || null, sourceLink: null };
      })
      .filter(Boolean);
    return { name: top, files };
  }
  return { name: top, files: [{ path: top, type: "file", size: Number(info.length) || null, sourceLink: null }] };
}

// Раздача — готовый фильм библиотеки (удалять её нельзя). Библиотека не загрузилась (magnets не массив) — неизвестно:
// считаем фильмом, чтобы сбой AllDebrid не удалил чужой или свой фильм.
function mayBeLibraryFilm(magnets, magnetId) {
  return !Array.isArray(magnets) || magnets.some(m => m.id === magnetId && m.statusCode === 4);
}

// ---- Хранилище ---------------------------------------------------------------------------------------------------
const store = new Map(); // "magnetId|videoPath" -> [запись]
const keyById = new Map(); // id дорожки -> ключ store
let total = 0;

function load() {
  try {
    for (const [key, list] of Object.entries(JSON.parse(fs.readFileSync(config.AUDIO_TRACKS_FILE, "utf8")) || {})) {
      if (!KEY_RE.test(key) || !Array.isArray(list)) continue;
      const ok = [];
      for (const t of list) {
        if (!t || !TRACK_ID_RE.test(t.id) || keyById.has(t.id) || !Number.isSafeInteger(t.audioMagnetId) || typeof t.audioPath !== "string") continue;
        keyById.set(t.id, key);
        ok.push(t);
      }
      if (ok.length) { store.set(key, ok); total += ok.length; }
    }
  } catch {}
}
load();

const persist = makeJsonWriter(config.AUDIO_TRACKS_FILE, () => Object.fromEntries(store));

const keyOf = (movieMagnetId, videoPath) => `${movieMagnetId}|${videoPath}`;

function view(key, t) {
  const i = key.indexOf("|");
  return { ...t, movieMagnetId: Number(key.slice(0, i)), videoPath: key.slice(i + 1) };
}

function forMovie(movieMagnetId) {
  const prefix = `${movieMagnetId}|`;
  const out = [];
  for (const [key, list] of store) if (key.startsWith(prefix)) for (const t of list) out.push(view(key, t));
  return out.sort((a, b) => a.createdAt - b.createdAt);
}

// Раздачи с озвучками, привязанными к фильму (для продления хранения вместе с фильмом). Зовётся при каждом открытии
// фильма: индекс «фильм -> раздачи озвучек» строится заново только после изменения хранилища.
let linkedIndex = null;
const changed = () => { linkedIndex = null; };
function linkedAudioMagnets(movieMagnetId) {
  if (!linkedIndex) {
    linkedIndex = new Map();
    for (const [key, list] of store) {
      const movie = Number(key.slice(0, key.indexOf("|")));
      let set = linkedIndex.get(movie);
      if (!set) linkedIndex.set(movie, (set = new Set()));
      for (const t of list) set.add(t.audioMagnetId);
    }
  }
  return [...(linkedIndex.get(Number(movieMagnetId)) || [])];
}

function cleanTitle(v, fallback) {
  const s = typeof v === "string" ? v.replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, 80) : "";
  return s || fallback;
}

// Только число или строка с числом: Number([500]) === 500 и Number(" ") === 0 — не смещения
function parseOffset(v) {
  if (typeof v === "string" ? !/^\s*[+\-−–]?\d+(?:\.\d+)?\s*$/.test(v) : typeof v !== "number") return null;
  const n = Number(typeof v === "string" ? toAsciiMinus(v) : v);
  return Number.isFinite(n) && Math.abs(n) <= MAX_OFFSET_MS ? Math.round(n) : null;
}

// Язык по названию: «HDRezka [rus]» -> rus; кириллица — rus; иначе und
function guessLang(text) {
  return langTag(text) || (/[а-яё]/i.test(text || "") ? "rus" : "und");
}

// Сохранить связки pairs ({ videoPath, audioPath, voice, offsetMs }); override: { title, lang, offsetMs } — общие значения.
// Повтор той же связки не плодит записей. Бросает ошибку со status при превышении лимитов.
function addTracks({ movieMagnetId, audioMagnetId, pairs, author, override = {} }) {
  const created = [];
  const existing = [];
  const now = Date.now();
  const toAdd = [];
  let runningTotal = total;
  const perVideo = new Map(); // ключ -> сколько дорожек у видео с учётом добавляемых
  const newIds = new Set();
  const fail = (status, code, message) => {
    const e = new Error(message);
    e.status = status;
    e.code = code;
    throw e;
  };

  for (const p of pairs) {
    if (typeof p.videoPath !== "string" || !p.videoPath || p.videoPath.length > MAX_VIDEO_PATH) {
      fail(422, "BAD_PATH", `Путь к видеофайлу длиннее ${MAX_VIDEO_PATH} символов: к такому файлу озвучку не привязать.`);
    }
    const key = keyOf(movieMagnetId, p.videoPath);
    const list = store.get(key) || [];
    const dup = list.find(t => t.audioMagnetId === audioMagnetId && t.audioPath === p.audioPath);
    if (dup) { existing.push(view(key, dup)); continue; }
    const videoCount = perVideo.get(key) ?? list.length;
    if (runningTotal >= MAX_TOTAL) fail(409, "LIMIT", "На сервере привязано слишком много озвучек.");
    if (videoCount >= MAX_PER_VIDEO) fail(409, "LIMIT", `К видеофайлу «${baseName(p.videoPath)}» уже привязано ${MAX_PER_VIDEO} озвучек.`);
    runningTotal++;
    perVideo.set(key, videoCount + 1);
    let id;
    do id = `trk_${crypto.randomBytes(4).toString("hex")}`; while (keyById.has(id) || newIds.has(id));
    newIds.add(id);
    const base = cleanTitle(p.voice, stem(p.audioPath));
    const title = cleanTitle(override.title, base);
    const t = {
      id,
      audioMagnetId,
      audioPath: p.audioPath,
      title,
      lang: cleanLang(override.lang) || cleanLang(p.lang) || guessLang(title),
      offsetMs: override.offsetMs ?? p.offsetMs ?? 0,
      addedBy: author,
      createdAt: now
    };
    toAdd.push({ key, track: t });
  }

  for (const { key, track } of toAdd) {
    const list = store.get(key) || [];
    list.push(track);
    store.set(key, list);
    keyById.set(track.id, key);
    total++;
    created.push(view(key, track));
  }
  if (created.length) { changed(); persist(); }
  return { created, existing };
}

// Дорожка по id: { key, list, t } или null
function locate(id) {
  const key = typeof id === "string" ? keyById.get(id) : undefined;
  const list = key !== undefined ? store.get(key) : null;
  const t = list ? list.find(x => x.id === id) : null;
  return t ? { key, list, t } : null;
}

function getTrack(id) {
  const hit = locate(id);
  return hit ? view(hit.key, hit.t) : null;
}

function setOffset(id, offsetMs) {
  const hit = locate(id);
  if (!hit) return null;
  hit.t.offsetMs = offsetMs;
  persist();
  return hit.t;
}

function removeTrack(id) {
  const hit = locate(id);
  if (!hit) return null;
  hit.list.splice(hit.list.indexOf(hit.t), 1);
  keyById.delete(id);
  total = Math.max(0, total - 1);
  if (hit.list.length === 0) store.delete(hit.key);
  changed();
  persist();
  return view(hit.key, hit.t);
}

function hasAudioMagnetTracks(audioMagnetId) {
  const targetId = Number(audioMagnetId);
  for (const list of store.values()) {
    if (list.some(t => t.audioMagnetId === targetId)) return true;
  }
  return false;
}

// Раздачу удалили: пропадают связки фильма (ключи "<id>|…") и дорожки, чьим источником она была
function dropMagnet(magnetId) {
  const prefix = `${magnetId}|`;
  let n = 0;
  for (const [key, list] of [...store]) {
    if (key.startsWith(prefix)) {
      n += list.length;
      for (const t of list) keyById.delete(t.id);
      store.delete(key);
      continue;
    }
    const keep = list.filter(t => t.audioMagnetId !== Number(magnetId));
    if (keep.length !== list.length) {
      n += list.length - keep.length;
      for (const t of list) if (t.audioMagnetId === Number(magnetId)) keyById.delete(t.id);
      if (keep.length) store.set(key, keep);
      else store.delete(key);
    }
  }
  if (n) { total = Math.max(0, total - n); changed(); persist(); }
  return n;
}

/**
 * Дорожки фильма со ссылками AllDebrid на файлы озвучки: { items: [{ track, link }], failed }. [videoPath] — только
 * для этого видео. Списки файлов раздач с озвучками — из кеша, остальные одним пакетным запросом; дорожка, у которой
 * раздачу убрали, пропускается. failed — не получили ни одного нужного списка (AllDebrid не ответил).
 */
async function trackLinks(movieMagnetId, videoPath) {
  const { fetchMagnetTrees, treeCache } = require("./alldebrid");
  const { safeHttpsUrl } = require("../utils/helpers");
  const { isAllDebridFileLink } = require("./mirror");
  const tracks = forMovie(movieMagnetId).filter(t => videoPath == null || t.videoPath === videoPath);
  const ids = [...new Set(tracks.map(t => t.audioMagnetId))];
  const lists = new Map();
  for (const id of ids) if (treeCache.has(id)) lists.set(id, treeCache.get(id));
  const rest = ids.filter(id => !lists.has(id));
  let upstreamFailed = false;
  if (rest.length) {
    try {
      for (const [id, list] of await fetchMagnetTrees(rest)) lists.set(id, list);
    } catch {
      upstreamFailed = true;
    }
  }
  const items = [];
  for (const t of tracks) {
    const f = (lists.get(t.audioMagnetId) || []).find(x => x.type === "file" && x.path === t.audioPath);
    const parsed = f ? safeHttpsUrl(f.sourceLink || "") : null;
    if (isAllDebridFileLink(parsed)) items.push({ track: t, link: parsed.toString() });
  }
  return { items, failed: ids.length > 0 && !lists.size && upstreamFailed };
}

function _reset() {
  changed();
  store.clear();
  keyById.clear();
  total = 0;
}

module.exports = {
  AUDIO_EXT,
  MAX_OFFSET_MS,
  MAX_PER_VIDEO,
  MAX_VIDEO_PATH,
  TRACK_ID_RE,
  checkFormats,
  parseAudioName,
  titleMatches,
  matchToMovie,
  bdecode,
  torrentFiles,
  forMovie,
  linkedAudioMagnets,
  parseOffset,
  addTracks,
  getTrack,
  setOffset,
  removeTrack,
  hasAudioMagnetTracks,
  dropMagnet,
  trackLinks,
  mayBeLibraryFilm,
  baseName,
  _reset
};

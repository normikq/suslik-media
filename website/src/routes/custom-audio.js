"use strict";

const express = require("express");
const config = require("../config");
const { apiLimiter } = require("../middleware/rate-limit");
const { requireLogin, deviceId } = require("../middleware/auth");
const { cleanFileName } = require("../utils/helpers");
const { getMagnetFileList } = require("../services/alldebrid");
const { makeMirrorToken } = require("../services/mirror");
const { isLibraryAdmin, isOwner } = require("../services/downloads");
const { deleteMagnet, markOpened } = require("../services/cleanup");
const { getLibrary, libraryChanged } = require("../services/library");
const { getUser, SHARED_USER } = require("../services/users");
const ca = require("../services/custom-audio");
const { checkDurations } = require("../services/audio-duration");

const router = express.Router();

// Только число или строка из цифр: Number([5]) === 5 — не идентификатор
const magnetIdOf = v => {
  if (typeof v === "string" ? !/^\d{1,20}$/.test(v) : typeof v !== "number") return null;
  const n = Number(v);
  return Number.isSafeInteger(n) && n > 0 ? n : null;
};

// Автор дорожки: пользователь Discord — его id; вход по общему паролю — устройство (как у «своих» раздач).
// Запоминается на запрос: без cookie sdev deviceId каждый раз выдаёт новый id, и «mine» в ответе attach было бы false.
function authorOf(req, res) {
  if (!req.customAudioAuthor) {
    req.customAudioAuthor = req.user && req.user.id !== SHARED_USER.id ? req.user.id : `dev_${deviceId(req, res)}`;
  }
  return req.customAudioAuthor;
}

const fail = (res, status, code, message, extra = {}) => res.status(status).json({ error: message, code, ...extra });

// Список файлов раздачи; null — ответ уже отправлен
async function filesOf(res, id, what) {
  let list = null;
  try {
    list = await getMagnetFileList(id);
  } catch {
    fail(res, 502, "UPSTREAM", "Не удалось получить список файлов. Попробуйте ещё раз.");
    return null;
  }
  if (!list || !list.some(f => f.type === "file")) {
    fail(res, 409, "NOT_READY", `Раздача ${what} ещё загружается или в ней нет файлов. Повторите проверку, когда список файлов будет готов.`);
    return null;
  }
  return list;
}

async function libraryMagnets() {
  try {
    return (await getLibrary()).data.magnets || [];
  } catch {
    return null;
  }
}

// Раздел 4: в раздаче с озвучкой только аудио. Раздачу, которую пользователь сам добавил (magnet), сразу удаляем
// из AllDebrid; чужую, админскую и ничейную — не трогаем. `keep: true` в теле оставляет раздачу.
async function rejectForbidden(req, res, audioMagnetId, forbidden, magnets) {
  let removed = false;
  const isLibraryFilm = ca.mayBeLibraryFilm(magnets, audioMagnetId);
  const adminCaller = isLibraryAdmin(req);
  if (audioMagnetId && !isLibraryFilm && !adminCaller && isOwner(audioMagnetId, req) && req.body?.keep !== true) {
    try {
      await deleteMagnet(audioMagnetId);
      libraryChanged();
      removed = true;
    } catch {}
  }
  const first = forbidden[0];
  const more = forbidden.length > 1 ? ` и ещё ${forbidden.length - 1}` : "";
  const allowed = [...ca.AUDIO_EXT].map(e => "." + e).join(", ");
  fail(res, 422, "FORBIDDEN_FILES", `В раздаче есть недопустимые файлы (${first.path}${more}): разрешены только аудио — ${allowed}.${removed ? " Раздача удалена из медиатеки." : ""}`, {
    forbidden: forbidden.slice(0, 50),
    removed
  });
}

// Длительность озвучки и видео по содержимому (ffprobe). Ссылки озвучки — из её списка файлов; у видео из снимка
// библиотеки ссылок может не быть — тогда один запрос списка файлов фильма.
async function durationsOf(pairs, audioFiles, movie, offsetMs) {
  let movieFiles = null;
  const linkOf = async (kind, path) => {
    if (kind === "audio") return audioFiles.find(f => f.path === path)?.sourceLink || null;
    const known = movie.files.find(f => f.path === path)?.sourceLink;
    if (known) return known;
    movieFiles ||= getMagnetFileList(movie.id).catch(() => null);
    return ((await movieFiles) || []).find(f => f.type === "file" && f.path === path)?.sourceLink || null;
  };
  const list = offsetMs === undefined ? pairs : pairs.map(p => ({ ...p, offsetMs }));
  return checkDurations(list, linkOf);
}

// Общая часть check и attach. Возвращает { movieMagnetId, audioMagnetId, fromTorrent, pairs, durations, warnings }
// или null (ответ отправлен). Фильм — movieMagnetId, а без него ищется в библиотеке по названию из имён файлов.
// Озвучка — audioMagnetId или .torrent. offsetMs — смещение из запроса (вместо тегов), учитывается при сверке длительности.
async function prepare(req, res, { allowTorrent, offsetMs }) {
  const isTorrent = Buffer.isBuffer(req.body);
  const src = isTorrent ? req.query : req.body || {};
  // null, "" или нет поля — фильм ищется по названию
  const movieGiven = src.movieMagnetId !== undefined && src.movieMagnetId !== null && src.movieMagnetId !== "";
  const movieMagnetId = movieGiven ? magnetIdOf(src.movieMagnetId) : null;
  if (movieGiven && !movieMagnetId) {
    fail(res, 400, "BAD_REQUEST", "Некорректный movieMagnetId.");
    return null;
  }

  let audio;
  let audioMagnetId = null;
  if (isTorrent) {
    if (!allowTorrent) {
      fail(res, 400, "BAD_REQUEST", "Торрент-файл сначала загрузите через /api/torrents и передайте audioMagnetId.");
      return null;
    }
    if (!req.body.length) {
      fail(res, 400, "BAD_REQUEST", "Торрент-файл пустой.");
      return null;
    }
    try {
      audio = ca.torrentFiles(req.body);
    } catch (err) {
      fail(res, 400, "BAD_TORRENT", err.message || "Не удалось разобрать .torrent.");
      return null;
    }
  } else {
    audioMagnetId = magnetIdOf(src.audioMagnetId);
    if (!audioMagnetId) {
      fail(res, 400, "BAD_REQUEST", "Укажите audioMagnetId (раздача с озвучкой) или пришлите .torrent файлом.");
      return null;
    }
    const files = await filesOf(res, audioMagnetId, "с озвучкой");
    if (!files) return null;
    audio = { name: "", files };
  }

  if (movieMagnetId && audioMagnetId && movieMagnetId === audioMagnetId) {
    fail(res, 400, "BAD_REQUEST", "Раздача с фильмом и раздача с озвучкой должны различаться.");
    return null;
  }

  const magnets = await libraryMagnets();
  const audioFiles = audio.files.filter(f => f.type === "file");
  const fmt = ca.checkFormats(audioFiles.map(f => f.path));
  if (!fmt.ok) {
    await rejectForbidden(req, res, audioMagnetId, fmt.forbidden, magnets);
    return null;
  }

  // Без библиотеки нельзя проверить ни наличие фильма, ни что раздача готова
  if (!magnets) {
    fail(res, 502, "UPSTREAM", "Не удалось загрузить библиотеку. Попробуйте ещё раз.");
    return null;
  }

  let movie = null;
  let match = null;
  if (movieMagnetId) {
    const hit = magnets.find(m => m.id === movieMagnetId);
    if (!hit) {
      fail(res, 404, "NOT_IN_LIBRARY", "Фильм не найден в библиотеке. Сначала добавьте раздачу с фильмом.");
      return null;
    }
    if (hit.statusCode !== 4) {
      fail(res, 409, "NOT_READY", "Раздача с фильмом ещё загружается или в ней нет файлов. Повторите проверку, когда список файлов будет готов.");
      return null;
    }
    const files = await filesOf(res, movieMagnetId, "с фильмом");
    if (!files) return null;
    movie = { id: movieMagnetId, name: hit.name || "", files };
    match = ca.matchToMovie(audioFiles, movie, { explicit: true });
  } else {
    const first = audioFiles.map(f => ({ f, p: ca.parseAudioName(f.path) })).find(x => x.p.title);
    if (!first) {
      const m = ca.matchToMovie(audioFiles, { name: "", files: [{ type: "file", path: "x.mkv" }] });
      fail(res, 422, "UNRESOLVED", m.errors[0]?.message || "Не удалось определить фильм по именам файлов.", { errors: m.errors });
      return null;
    }
    const candidates = magnets.filter(m => m.statusCode === 4 && m.id !== audioMagnetId && m.files?.length && ca.titleMatches(first.p, m));
    if (!candidates.length) {
      fail(res, 404, "NOT_IN_LIBRARY", `Фильм ${first.p.title} не найден в библиотеке. Сначала добавьте раздачу с фильмом.`, {
        errors: [{ code: "NOT_IN_LIBRARY", title: first.p.title }]
      });
      return null;
    }
    const results = candidates.map(m => ({ m, r: ca.matchToMovie(audioFiles, m) }));
    const good = results.filter(x => x.r.ok);
    const allMatches = candidates.map(m => ({ m, r: ca.matchToMovie(audioFiles, m, { explicit: true }) }));
    const allGood = allMatches.filter(x => x.r.ok);
    if (allGood.length > 1) {
      fail(res, 409, "AMBIGUOUS", "Подходит несколько раздач с этим фильмом. Выберите нужную.", {
        candidates: allGood.map(x => ({ id: x.m.id, name: x.m.name }))
      });
      return null;
    }
    const chosen = good[0] || allGood[0] || results[0];
    movie = { id: chosen.m.id, name: chosen.m.name, files: chosen.m.files };
    match = chosen.r;
  }

  if (!match.ok) {
    fail(res, 422, match.errors[0].code, match.errors[0].message, { errors: match.errors, movieMagnetId: movie.id });
    return null;
  }

  // У .torrent ссылок на файлы нет: длительность сверится при привязке. Админ может пропустить сверку (ignoreDuration).
  const warnings = [];
  let durations = [];
  if (isTorrent) {
    warnings.push({ code: "DURATION_UNCHECKED", message: "Длительность сверится при привязке, когда раздача появится в медиатеке." });
  } else if (src.ignoreDuration === true && isLibraryAdmin(req)) {
    warnings.push({ code: "DURATION_SKIPPED", message: "Сверка длительности пропущена по запросу админа." });
  } else {
    const d = await durationsOf(match.pairs, audioFiles, movie, offsetMs);
    if (d.errors.length) {
      fail(res, 422, d.errors[0].code, d.errors[0].message, { errors: d.errors, movieMagnetId: movie.id });
      return null;
    }
    warnings.push(...d.warnings);
    durations = d.checked;
  }
  return { movieMagnetId: movie.id, audioMagnetId, fromTorrent: isTorrent, pairs: match.pairs, durations, warnings };
}

const torrentBody = express.raw({ type: "application/x-bittorrent", limit: "1mb" });

// Предварительная проверка: формат файлов (раздел 4), сопоставление с библиотекой (раздел 5). Ничего не сохраняет.
// JSON { movieMagnetId?, audioMagnetId } либо тело .torrent (Content-Type: application/x-bittorrent, ?movieMagnetId=…)
router.post("/api/custom-audio/check", requireLogin, apiLimiter, torrentBody, async (req, res) => {
  const s = await prepare(req, res, { allowTorrent: true });
  if (!s) return;
  res.set("Cache-Control", "no-store");
  res.json({
    ok: true,
    movieMagnetId: s.movieMagnetId,
    audioMagnetId: s.audioMagnetId,
    fromTorrent: s.fromTorrent,
    attachable: !s.fromTorrent,
    pairs: s.pairs,
    durations: s.durations,
    warnings: s.warnings
  });
});

// Подтверждение: { movieMagnetId?, audioMagnetId, title?, lang?, offsetMs? }. Проверки те же, что в check;
// привязываются все найденные пары. title/lang/offsetMs, если заданы, заменяют вычисленные из имён файлов.
router.post("/api/custom-audio/attach", requireLogin, apiLimiter, async (req, res) => {
  // Тело .torrent здесь не читаем: отказ по заголовку, без буфера в памяти
  if (req.is("application/x-bittorrent")) return fail(res, 400, "BAD_REQUEST", "Торрент-файл сначала загрузите через /api/torrents и передайте audioMagnetId.");
  const override = {};
  if (req.body?.offsetMs !== undefined) {
    override.offsetMs = ca.parseOffset(req.body.offsetMs);
    if (override.offsetMs === null) return fail(res, 400, "BAD_OFFSET", `offsetMs — целое число миллисекунд, не больше ±${ca.MAX_OFFSET_MS}.`);
  }
  if (typeof req.body?.title === "string") override.title = req.body.title;
  if (typeof req.body?.lang === "string") override.lang = req.body.lang;
  const s = await prepare(req, res, { allowTorrent: false, offsetMs: override.offsetMs });
  if (!s) return;
  try {
    const { created, existing } = ca.addTracks({
      movieMagnetId: s.movieMagnetId,
      audioMagnetId: s.audioMagnetId,
      pairs: s.pairs,
      author: authorOf(req, res),
      override
    });
    res.set("Cache-Control", "no-store");
    res.status(created.length ? 201 : 200).json({
      ok: true,
      movieMagnetId: s.movieMagnetId,
      created: created.map(t => publicTrack(t, req)),
      existing: existing.map(t => publicTrack(t, req)),
      warnings: s.warnings
    });
  } catch (err) {
    fail(res, err.status || 500, err.code || "INTERNAL", err.status ? err.message : "Не удалось сохранить привязку.");
  }
});

function canEdit(req, track) {
  return isLibraryAdmin(req) || track.addedBy === authorOf(req, null);
}

function publicTrack(t, req, url) {
  const author = /^u_\d+$/.test(t.addedBy) ? getUser(t.addedBy) : null;
  const out = {
    id: t.id,
    movieMagnetId: t.movieMagnetId,
    videoPath: t.videoPath,
    audioMagnetId: t.audioMagnetId,
    audioPath: t.audioPath,
    title: t.title,
    lang: t.lang,
    offsetMs: t.offsetMs,
    addedBy: t.addedBy,
    addedByName: author ? author.name : null,
    createdAt: t.createdAt,
    mine: canEdit(req, t)
  };
  if (url !== undefined) out.url = url;
  return out;
}

// Внешние дорожки фильма. Подпись зеркала — на MIRROR_TTL, как у /api/file/mirror-url; клиент запрашивает список заново.
// Открытие фильма продлевает хранение и раздач с озвучками (cleanup.markOpened).
router.get("/api/custom-audio/:magnetId", requireLogin, apiLimiter, async (req, res) => {
  const movieMagnetId = magnetIdOf(req.params.magnetId);
  if (!movieMagnetId) return fail(res, 400, "BAD_REQUEST", "Некорректный идентификатор magnet-ссылки.");
  markOpened(movieMagnetId);
  // Сбой одной раздачи убирает только её дорожки; 502 — лишь если не получили ни одного списка
  const { items, failed } = await ca.trackLinks(movieMagnetId);
  if (failed) return fail(res, 502, "UPSTREAM", "Не удалось получить список файлов озвучки. Попробуйте ещё раз.");
  const expires = String(Math.floor(Date.now() / 1000) + config.MIRROR_TTL);
  const out = items.map(({ track: t, link }) => {
    const name = cleanFileName(ca.baseName(t.audioPath)) || "audio";
    return publicTrack(
      t,
      req,
      `https://${req.get("host")}/api/file/proxy/${encodeURIComponent(name)}?link=${encodeURIComponent(link)}&exp=${expires}&sig=${makeMirrorToken(link, expires)}`
    );
  });
  res.set("Cache-Control", "no-store");
  res.json({ magnetId: movieMagnetId, tracks: out });
});

// Смещение задержки, мс (+ звук отстаёт, − спешит): автору дорожки или админу. { offsetMs }
router.put("/api/custom-audio/:trackId/offset", requireLogin, apiLimiter, (req, res) => {
  const track = ca.getTrack(req.params.trackId);
  if (!track) return fail(res, 404, "NOT_FOUND", "Дорожка не найдена.");
  if (!canEdit(req, track)) return fail(res, 403, "FORBIDDEN", "Менять смещение может автор дорожки или админ.");
  const offsetMs = ca.parseOffset(req.body?.offsetMs);
  if (offsetMs === null) return fail(res, 400, "BAD_OFFSET", `offsetMs — целое число миллисекунд, не больше ±${ca.MAX_OFFSET_MS}.`);
  const t = ca.setOffset(track.id, offsetMs);
  res.set("Cache-Control", "no-store");
  res.json({ ok: true, id: t.id, offsetMs: t.offsetMs });
});

// Удаление дорожки: автору дорожки или админу Discord.
// Если у раздачи с озвучкой не осталось дорожек и добавил её этот же пользователь, сервер удаляет и её из AllDebrid.
router.delete("/api/custom-audio/:trackId", requireLogin, apiLimiter, async (req, res) => {
  const track = ca.getTrack(req.params.trackId);
  if (!track) return fail(res, 404, "NOT_FOUND", "Дорожка не найдена.");
  if (!canEdit(req, track)) return fail(res, 403, "FORBIDDEN", "Удалять дорожку может автор дорожки или админ.");

  ca.removeTrack(track.id);
  let removedAudioMagnet = false;
  const audioMagnetId = track.audioMagnetId;

  if (audioMagnetId && !ca.hasAudioMagnetTracks(audioMagnetId)) {
    const magnets = await libraryMagnets();
    const isLibraryFilm = ca.mayBeLibraryFilm(magnets, audioMagnetId);
    if (!isLibraryFilm && isOwner(audioMagnetId, req)) {
      try {
        await deleteMagnet(audioMagnetId);
        libraryChanged();
        removedAudioMagnet = true;
      } catch (err) {
        console.warn("[custom-audio] не удалось удалить пустую раздачу с озвучкой:", err.message);
      }
    }
  }

  res.set("Cache-Control", "no-store");
  res.json({ ok: true, id: track.id, removedAudioMagnet });
});

module.exports = router;

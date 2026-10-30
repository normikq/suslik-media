"use strict";

// Поток для телевизора (приложение Tizen): см. services/tvstream.js. Остальные клиенты сюда не ходят.

const express = require("express");
const { spawn } = require("child_process");
const config = require("../config");
const { mirrorUrlLimiter, mirrorLimiter } = require("../middleware/rate-limit");
const { requireLogin } = require("../middleware/auth");
const { safeHttpsUrl, cleanFileName, clientIp, publicBase } = require("../utils/helpers");
const { isAllDebridFileLink, resolveDirect, mirrorLog, MIRROR_CSP, UnlockError } = require("../services/mirror");
const { markLinkOpened } = require("../services/cleanup");
const tv = require("../services/tvstream");
const ca = require("../services/custom-audio");

const router = express.Router();

function adLink(v) {
  const parsed = safeHttpsUrl(typeof v === "string" ? v.trim() : "");
  return isAllDebridFileLink(parsed) ? parsed.toString() : null;
}

function linkOf(req) {
  return adLink(req.query.link);
}

// Озвучек отдельным файлом из раздачи (&ext=) и озвучек пользователей в одном ответе — не больше
const MAX_EXT_FILES = 8;
const MAX_CUSTOM = 20;

/**
 * Внешние озвучки видео для tv-info: файлы из той же раздачи (&ext=<ссылка>, повторяется) и озвучки пользователей
 * (&magnetId=&path= — раздача и путь видео, /api/custom-audio). Ошибка AllDebrid — без озвучек пользователей.
 */
async function externalsOf(req) {
  const raw = req.query.ext;
  // asked — ссылка, как её прислал ТВ: по ней он узнаёт свой файл (safeHttpsUrl может переписать адрес)
  const out = [];
  const seen = new Set();
  for (const asked of Array.isArray(raw) ? raw : raw != null ? [raw] : []) {
    const link = adLink(asked);
    if (!link || seen.has(link) || out.length >= MAX_EXT_FILES) continue;
    seen.add(link);
    out.push({ kind: "file", link, asked: String(asked), offsetMs: 0 });
  }
  const magnetId = /^\d{1,20}$/.test(String(req.query.magnetId || "")) ? Number(req.query.magnetId) : null;
  const path = typeof req.query.path === "string" ? req.query.path : "";
  if (magnetId && path) {
    try {
      const { items, failed } = await ca.trackLinks(magnetId, path);
      for (const { track: t, link } of items.slice(0, MAX_CUSTOM)) {
        out.push({ kind: "custom", id: t.id, title: t.title, lang: t.lang, offsetMs: t.offsetMs || 0, link });
      }
      // Для разбора «no dub on server» на ТВ: у раздачи озвучки есть, а к этому видео не нашлось ни одной
      const all = ca.forMovie(magnetId);
      if (!items.length && all.length) {
        console.warn(`[tv] озвучки пользователей: у раздачи ${magnetId} их ${all.length}, для «${path}» — ни одной` +
          (failed ? " (AllDebrid не отдал список файлов озвучки)" : `; пути видео в привязках: ${[...new Set(all.map(t => t.videoPath))].slice(0, 3).join(" | ")}`));
      }
    } catch (err) {
      console.warn(`[tv] озвучки пользователей не получены: ${err.message}`);
    }
  }
  return out;
}

/**
 * Что в файле и нужен ли серверный поток: { stream, durationMs, video, audio: [{ index, codec, channels, language,
 * title, ok, url }], subs, audioDefault, external: [{ kind: "file" | "custom", link?, id?, title?, lang?, offsetMs, url }] }.
 * url — подписанный адрес потока с этой дорожкой (живёт, как зеркало, 12 ч). external — внешние озвучки (externalsOf):
 * сервер склеивает видео фильма с файлом озвучки; у "file" link — та, что пришла в &ext= (клиент узнаёт свой файл).
 */
router.get("/api/file/tv-info", requireLogin, mirrorUrlLimiter, async (req, res) => {
  res.setHeader("Cache-Control", "no-store");
  if (!config.TV_STREAM) return res.status(404).json({ error: "Поток для телевизора выключен на сервере." });
  if (!tv.isTvClient(req)) return res.status(403).json({ error: "Только для приложения на телевизоре." });
  const link = linkOf(req);
  if (!link) return res.status(400).json({ error: "Некорректная ссылка на медиафайл." });
  markLinkOpened(link);
  let info, external;
  try {
    const entry = await resolveDirect(link, false);
    [info, external] = await Promise.all([tv.probeFile(link, entry.url), externalsOf(req)]);
  } catch (err) {
    return res.status(502).json({ error: err instanceof UnlockError ? err.message : "Сервер не смог прочитать файл." });
  }
  const raw = cleanFileName(typeof req.query.name === "string" ? req.query.name.split(/[\\/]/).pop() : "") || "video";
  const name = encodeURIComponent(raw.replace(/\.[a-z0-9]{2,5}$/i, "") + "." + config.TV_STREAM_FORMAT);
  const expires = String(Math.floor(Date.now() / 1000) + config.MIRROR_TTL);
  const def = tv.defaultAudio(info);
  const base = `${publicBase(req)}/api/file/tv/${name}?link=${encodeURIComponent(link)}`;
  res.json({
    stream: tv.needsTvStream(info),
    videoUnsupported: tv.videoUnsupported(info) ? info.video.codec : null,
    durationMs: info.durationMs,
    container: info.container,
    video: info.video,
    audio: info.audio.map(a => ({
      index: a.index, codec: a.codec, profile: a.profile, channels: a.channels, language: a.language, title: a.title, ok: a.ok,
      url: `${base}&a=${a.index}&exp=${expires}&sig=${tv.makeTvToken(link, a.index, expires)}`
    })),
    subs: info.subs,
    audioDefault: def ? def.index : null,
    external: external.map(({ link: extLink, asked, ...x }) => ({
      ...x,
      ...(x.kind === "file" ? { link: asked } : {}),
      url: `${base}&x=${encodeURIComponent(extLink)}&xo=${x.offsetMs}&exp=${expires}&sig=${tv.makeTvToken(link, tv.externalKey(extLink, x.offsetMs), expires)}`
    }))
  });
});

/**
 * Сведения о медиафайле для веб-плеера: все аудиодорожки (включая внешние из раздачи и от пользователей)
 * и субтитры. Для дорожек без нативной поддержки в браузере (AC3, DTS) выдаётся адрес потока с транскодированием в AAC.
 */
router.get("/api/file/media-info", requireLogin, mirrorUrlLimiter, async (req, res) => {
  res.setHeader("Cache-Control", "no-store");
  if (!config.TV_STREAM) return res.status(404).json({ error: "Поток выключен на сервере." });
  const link = linkOf(req);
  if (!link) return res.status(400).json({ error: "Некорректная ссылка на медиафайл." });
  markLinkOpened(link);
  let info, external;
  try {
    const entry = await resolveDirect(link, false);
    [info, external] = await Promise.all([tv.probeFile(link, entry.url), externalsOf(req)]);
  } catch (err) {
    return res.status(502).json({ error: err instanceof UnlockError ? err.message : "Сервер не смог прочитать файл." });
  }
  const raw = cleanFileName(typeof req.query.name === "string" ? req.query.name.split(/[\\/]/).pop() : "") || "video";
  const name = encodeURIComponent(raw.replace(/\.[a-z0-9]{2,5}$/i, "") + ".mp4");
  const expires = String(Math.floor(Date.now() / 1000) + config.MIRROR_TTL);
  const base = `${publicBase(req)}/api/file/tv/${name}?link=${encodeURIComponent(link)}&ac=aac`;
  const subBase = `${publicBase(req)}/api/file/sub?link=${encodeURIComponent(link)}`;

  const audioList = info.audio.map(a => ({
    index: a.index,
    codec: a.codec,
    channels: a.channels,
    language: a.language,
    title: a.title,
    isDefault: a.isDefault,
    webOk: tv.WEB_AUDIO_OK.has(a.codec),
    url: `${base}&a=${a.index}&exp=${expires}&sig=${tv.makeTvToken(link, a.index, expires, "aac")}`
  }));

  const extList = external.map(({ link: extLink, asked, ...x }) => ({
    ...x,
    ...(x.kind === "file" ? { link: asked } : {}),
    url: `${base}&x=${encodeURIComponent(extLink)}&xo=${x.offsetMs}&exp=${expires}&sig=${tv.makeTvToken(link, tv.externalKey(extLink, x.offsetMs), expires, "aac")}`
  }));

  const subsList = info.subs.map(s => ({
    index: s.index,
    codec: s.codec,
    language: s.language,
    title: s.title,
    url: `${subBase}&s=${s.index}&exp=${expires}&sig=${tv.makeTvToken(link, "sub:" + s.index, expires, "aac")}`
  }));

  const def = tv.defaultAudio(info);
  const defOk = def && tv.WEB_AUDIO_OK.has(def.codec);

  res.json({
    durationMs: info.durationMs,
    container: info.container,
    video: info.video,
    audio: audioList,
    external: extList,
    subs: subsList,
    needsTranscode: !defOk || /mpegts/.test(info.container),
    audioDefault: def ? def.index : (audioList[0] ? audioList[0].index : 0)
  });
});

/**
 * Извлечение текстовых субтитров из медиафайла в формате WebVTT для веб-плеера:
 * ?link&s=<stream_index>&exp&sig
 */
router.get("/api/file/sub", mirrorLimiter, async (req, res) => {
  if (!config.TV_STREAM) return res.status(404).send("Поток выключен на сервере.");
  const link = linkOf(req);
  const s = Number(req.query.s);
  const exp = req.query.exp;
  const sig = req.query.sig;
  if (!link || !Number.isSafeInteger(s) || s < 0) return res.status(400).send("Некорректный запрос субтитров.");
  if (!tv.isValidTvToken(link, "sub:" + s, String(exp || ""), String(sig || ""), "aac")) {
    return res.status(401).send("Ссылка на субтитры устарела или недействительна.");
  }
  res.setHeader("Content-Type", "text/vtt; charset=utf-8");
  res.setHeader("Cache-Control", "private, max-age=3600");
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Content-Security-Policy", MIRROR_CSP);
  if (req.method === "HEAD") return res.status(200).end();

  let entry;
  try {
    entry = await resolveDirect(link, false);
  } catch (err) {
    return res.status(502).send(err instanceof UnlockError ? err.message : "Сервер не смог прочитать файл.");
  }

  let child;
  try {
    child = tv.startSubFfmpeg(tv.subArgs(entry.url, s));
  } catch (e) {
    return res.status(503).send("На сервере нет ffmpeg.");
  }
  if (!child) return res.status(503).send("На сервере нет ffmpeg.");

  res.on("close", () => {
    if (child.exitCode == null && !child.killed) child.kill("SIGKILL");
  });

  child.stdout.pipe(res);
  child.on("error", () => {
    if (!res.headersSent) res.status(503).send("Ошибка запуска ffmpeg для субтитров.");
  });
});

/**
 * Сам поток: ?link&a&exp&sig (подпись), &t=<сек> — с какого места, &s=<метка плеера>. Внешняя озвучка вместо &a:
 * &x=<ссылка на файл озвучки>&xo=<сдвиг, мс> — ffmpeg берёт видео из фильма, звук из этого файла.
 * &ac=aac — транскодирование в AAC для веб-плеера (если звук не поддерживается браузером).
 */
router.get("/api/file/tv/:name", mirrorLimiter, async (req, res) => {
  if (!config.TV_STREAM) return res.status(404).send("Поток для телевизора выключен на сервере.");
  const link = linkOf(req);
  const extLink = req.query.x != null ? adLink(req.query.x) : null;
  const xo = Number(req.query.xo || 0);
  const a = extLink ? null : Number(req.query.a);
  const targetAudio = req.query.ac === "aac" ? "aac" : "ac3";
  // Что подписано: номер дорожки фильма или файл озвучки со сдвигом
  const signed = extLink
    ? (Number.isSafeInteger(xo) && Math.abs(xo) <= ca.MAX_OFFSET_MS ? tv.externalKey(extLink, xo) : null)
    : (Number.isSafeInteger(a) && a >= 0 ? a : null);
  if (!link || signed == null || (req.query.x != null && !extLink) || !tv.isValidTvToken(link, signed, String(req.query.exp || ""), String(req.query.sig || ""), targetAudio)) {
    return res.status(401).send("Ссылка устарела или недействительна.");
  }
  // Поток идёт только с начала (с места &t=): перематывать его байтами нельзя
  const range = String(req.headers.range || "");
  const m = /^bytes=(\d+)-/.exec(range);
  if (m && Number(m[1]) > 0) {
    console.warn(`[tv] 416: плеер просит ${range} (поток идёт только с начала)`);
    res.setHeader("Content-Range", "bytes */*");
    return res.status(416).end();
  }
  const format = /\.mp4$/i.test(req.params.name) ? "mp4" : /\.mkv$/i.test(req.params.name) ? "mkv" : "ts";
  res.setHeader("Content-Type", format === "mp4" ? "video/mp4" : format === "mkv" ? "video/x-matroska" : "video/mp2t");
  res.setHeader("Cache-Control", "private, no-store");
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Content-Security-Policy", MIRROR_CSP);
  res.setHeader("Accept-Ranges", "none");
  res.setHeader("X-Accel-Buffering", "no"); // nginx: не копить поток на диске, ffmpeg идёт в темпе телевизора
  if (req.method === "HEAD") return res.status(200).end();

  let info, entry, ext = null;
  try {
    entry = await resolveDirect(link, false);
    info = await tv.probeFile(link, entry.url);
    if (extLink) {
      const extEntry = await resolveDirect(extLink, false);
      const extAudio = tv.defaultAudio(await tv.probeFile(extLink, extEntry.url));
      if (!extAudio) return res.status(400).send("В файле озвучки нет звука.");
      ext = { url: extEntry.url, audio: extAudio, offsetMs: xo };
    }
  } catch (err) {
    return res.status(502).send(err instanceof UnlockError ? err.message : "Сервер не смог прочитать файл.");
  }
  const audio = ext ? ext.audio : info.audio.find(x => x.index === a);
  if (!info.video || !audio) return res.status(400).send("В файле нет такой дорожки.");
  // AV1 у ТВ 2018–2019 нет: поток был бы со звуком и чёрным экраном (журнал 21.10)
  if (tv.videoUnsupported(info)) {
    console.warn(`[tv] 415: видео ${info.video.codec} телевизор не декодирует`);
    return res.status(415).send("Видео этого файла телевизор не поддерживает.");
  }
  const t = Math.max(0, Math.min(Number(req.query.t) || 0, info.durationMs ? info.durationMs / 1000 : 1e7));
  // s не подписан: привязываем к адресу клиента, чтобы чужая метка не гасила ffmpeg другого зрителя
  const viewer = /^[a-z0-9]{1,32}$/i.test(String(req.query.s || "")) ? `${clientIp(req)}|${req.query.s}` : null;

  let closed = false;
  res.on("close", () => { closed = true; });
  const child = tv.startFfmpeg(tv.ffmpegArgs(entry.url, Math.round(t * 10) / 10, info, audio, format, ext, targetAudio), viewer);
  if (!child) {
    console.warn(`[tv] 503: уже ${tv.activeCount()} ffmpeg`);
    return res.status(503).send("Сервер занят: телевизоров смотрит слишком много.");
  }
  const started = Date.now();
  let sent = 0;
  child.stdout.on("data", c => { sent += c.length; });
  res.on("close", () => console.log(`[tv] плеер закрыл поток: ${Math.round((Date.now() - started) / 1000)} с, отдано ${Math.round(sent / 1024)} КБ`));
  const v = info.video;
  console.log(`[tv] видео ${v.codec} ${v.profile} ${v.width}x${v.height} ${v.pixFmt} ${v.transfer || "sdr"}${v.dv ? " DolbyVision P" + v.dv : ""}, контейнер ${info.container}`);
  const which = ext ? `озвучка отдельным файлом, сдвиг ${xo} мс` : `a=${a}`;
  console.log(`[tv] старт ${format} ${which} (${audio.codec}${audio.ok ? ", копия" : " → ac3"}) t=${t} range=${range || "-"} ua="${req.get("user-agent") || "-"}"`);
  mirrorLog(req, `tv stream: ${ext ? `ext offset=${xo}` : `a=${a}`} (${audio.codec}${audio.ok ? ", copy" : " -> ac3"}) t=${t}`);

  const kill = () => { if (child.exitCode == null && !child.killed) child.kill("SIGKILL"); };
  res.on("close", kill);
  if (closed) return kill();

  // Заголовки — только когда ffmpeg дал первые байты: если файл не открылся, телевизор получит ошибку, а не пустой поток
  const firstTimer = setTimeout(kill, 45000);
  child.stdout.once("data", chunk => {
    clearTimeout(firstTimer);
    child.stdout.pause();
    if (closed) return kill();
    res.status(200);
    res.flushHeaders();
    res.write(chunk);
    child.stdout.pipe(res);
  });
  child.on("error", err => {
    clearTimeout(firstTimer);
    console.warn(`[tv] ffmpeg не запустился: ${err.message} (FFMPEG_PATH=${config.FFMPEG_PATH})`);
    if (!res.headersSent) res.status(503).send("На сервере нет ffmpeg.");
  });
  child.on("close", code => {
    clearTimeout(firstTimer);
    // Ошибки ffmpeg — всегда в журнал (а не только с MIRROR_DEBUG): иначе «сервер не смог подготовить файл» без причины
    if (code && !closed) console.warn(`[tv] ffmpeg вышел с кодом ${code}: ${String(child.stderrText || "").trim().slice(0, 600)}`);
    if (!res.headersSent) res.status(502).send("Сервер не смог подготовить этот файл.");
  });
});

module.exports = router;

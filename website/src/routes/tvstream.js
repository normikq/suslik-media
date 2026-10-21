"use strict";

// Поток для телевизора (приложение Tizen): см. services/tvstream.js. Остальные клиенты сюда не ходят.

const express = require("express");
const config = require("../config");
const { mirrorUrlLimiter, mirrorLimiter } = require("../middleware/rate-limit");
const { requireLogin } = require("../middleware/auth");
const { safeHttpsUrl, cleanFileName } = require("../utils/helpers");
const { isAllDebridFileLink, resolveDirect, mirrorLog, MIRROR_CSP, UnlockError } = require("../services/mirror");
const { markLinkOpened } = require("../services/cleanup");
const tv = require("../services/tvstream");

const router = express.Router();

function linkOf(req) {
  const parsed = safeHttpsUrl(typeof req.query.link === "string" ? req.query.link.trim() : "");
  return isAllDebridFileLink(parsed) ? parsed.toString() : null;
}

/**
 * Что в файле и нужен ли серверный поток: { stream, durationMs, video, audio: [{ index, codec, channels, language,
 * title, ok, url }], subs, audioDefault }. url — подписанный адрес потока с этой дорожкой (живёт, как зеркало, 12 ч).
 */
router.get("/api/file/tv-info", requireLogin, mirrorUrlLimiter, async (req, res) => {
  res.setHeader("Cache-Control", "no-store");
  if (!config.TV_STREAM) return res.status(404).json({ error: "Поток для телевизора выключен на сервере." });
  if (!tv.isTvClient(req)) return res.status(403).json({ error: "Только для приложения на телевизоре." });
  const link = linkOf(req);
  if (!link) return res.status(400).json({ error: "Некорректная ссылка на файл AllDebrid." });
  markLinkOpened(link);
  let info;
  try {
    const entry = await resolveDirect(link, false);
    info = await tv.probeFile(link, entry.url);
  } catch (err) {
    return res.status(502).json({ error: err instanceof UnlockError ? err.message : "Сервер не смог прочитать файл." });
  }
  const raw = cleanFileName(typeof req.query.name === "string" ? req.query.name.split(/[\\/]/).pop() : "") || "video";
  const name = encodeURIComponent(raw.replace(/\.[a-z0-9]{2,5}$/i, "") + ".mkv");
  const expires = String(Math.floor(Date.now() / 1000) + config.MIRROR_TTL);
  const host = req.get("host");
  const def = tv.defaultAudio(info);
  res.json({
    stream: tv.needsTvStream(info),
    durationMs: info.durationMs,
    container: info.container,
    video: info.video,
    audio: info.audio.map(a => ({
      index: a.index, codec: a.codec, profile: a.profile, channels: a.channels, language: a.language, title: a.title, ok: a.ok,
      url: `https://${host}/api/file/tv/${name}?link=${encodeURIComponent(link)}&a=${a.index}&exp=${expires}&sig=${tv.makeTvToken(link, a.index, expires)}`
    })),
    subs: info.subs,
    audioDefault: def ? def.index : null
  });
});

/** Сам поток: ?link&a&exp&sig (подпись), &t=<сек> — с какого места, &s=<метка плеера>. */
router.get("/api/file/tv/:name", mirrorLimiter, async (req, res) => {
  if (!config.TV_STREAM) return res.status(404).send("Поток для телевизора выключен на сервере.");
  const link = linkOf(req);
  const a = Number(req.query.a);
  if (!link || !Number.isSafeInteger(a) || a < 0 || !tv.isValidTvToken(link, a, String(req.query.exp || ""), String(req.query.sig || ""))) {
    return res.status(401).send("Ссылка устарела или недействительна.");
  }
  // Поток идёт только с начала (с места &t=): перематывать его байтами нельзя
  const range = String(req.headers.range || "");
  const m = /^bytes=(\d+)-/.exec(range);
  if (m && Number(m[1]) > 0) {
    res.setHeader("Content-Range", "bytes */*");
    return res.status(416).end();
  }
  res.setHeader("Content-Type", "video/x-matroska");
  res.setHeader("Cache-Control", "private, no-store");
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Content-Security-Policy", MIRROR_CSP);
  res.setHeader("Accept-Ranges", "none");
  res.setHeader("X-Accel-Buffering", "no"); // nginx: не копить поток на диске, ffmpeg идёт в темпе телевизора
  if (req.method === "HEAD") return res.status(200).end();

  let info, entry;
  try {
    entry = await resolveDirect(link, false);
    info = await tv.probeFile(link, entry.url);
  } catch (err) {
    return res.status(502).send(err instanceof UnlockError ? err.message : "Сервер не смог прочитать файл.");
  }
  const audio = info.audio.find(x => x.index === a);
  if (!info.video || !audio) return res.status(400).send("В файле нет такой дорожки.");
  const t = Math.max(0, Math.min(Number(req.query.t) || 0, info.durationMs ? info.durationMs / 1000 : 1e7));
  const viewer = /^[a-z0-9]{1,32}$/i.test(String(req.query.s || "")) ? String(req.query.s) : null;

  let closed = false;
  res.on("close", () => { closed = true; });
  const child = tv.startFfmpeg(tv.ffmpegArgs(entry.url, Math.round(t * 10) / 10, info, audio), viewer);
  if (!child) return res.status(503).send("Сервер занят: телевизоров смотрит слишком много.");
  mirrorLog(req, `tv stream: a=${a} (${audio.codec}${audio.ok ? ", copy" : " -> ac3"}) t=${t}`);

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
  child.on("error", () => {
    clearTimeout(firstTimer);
    if (!res.headersSent) res.status(503).send("На сервере нет ffmpeg.");
  });
  child.on("close", code => {
    clearTimeout(firstTimer);
    if (code) mirrorLog(req, `tv stream: ffmpeg exit ${code} ${String(child.stderrText || "").trim().slice(0, 300)}`);
    if (!res.headersSent) res.status(502).send("Сервер не смог подготовить этот файл.");
  });
});

module.exports = router;

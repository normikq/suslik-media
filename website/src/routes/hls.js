"use strict";

const crypto = require("crypto");
const express = require("express");
const config = require("../config");
const { hlsLimiter, hlsInternalLimiter } = require("../middleware/rate-limit");
const { requireLogin } = require("../middleware/auth");
const { safeHttpsUrl, cleanFileName, publicBase } = require("../utils/helpers");
const { getMagnetFileList } = require("../services/alldebrid");
const { makeMirrorToken, isAllDebridFileLink } = require("../services/mirror");
const { isLibraryAdmin } = require("../services/downloads");
const { findFile } = require("../services/sidecars");
const { cachedMagnetHash } = require("../services/library");
const hls = require("../services/hls");
const store = require("../services/hls-store");
const hlsAudio = require("../services/hls-audio");

// ---- HLS: маршруты (docs/HLS.md §10, задача T4; устройство — docs/HLS-SERVER.md) ----
// /internal/hls/* — кодировщик (Bearer HLS_WORKER_TOKEN) и CDN (Bearer HLS_CDN_TOKEN), свой парсер JSON до 256 КБ;
// этот роутер ставится в app.js до общего express.json (20 КБ). /api/hls/* — master.m3u8 и админка (T6).

const internal = express.Router();
const router = express.Router();

/** Bearer-токен из .env: не задан → 503, нет или чужой → 401. Сравнение по sha256 через timingSafeEqual. */
function bearer(name) {
  return (req, res, next) => {
    const want = config[name];
    if (!want) return res.status(503).json({ error: "HLS не настроен.", code: "not_configured" });
    const m = /^Bearer\s+(\S{1,512})$/.exec(String(req.get("Authorization") || ""));
    const digest = s => crypto.createHash("sha256").update(String(s)).digest();
    if (!m || !crypto.timingSafeEqual(digest(m[1]), digest(want))) {
      return res.status(401).json({ error: "Нет доступа.", code: "unauthorized" });
    }
    next();
  };
}

// HlsError → её HTTP-код и code; остальное — 500 без подробностей
function sendError(res, err) {
  if (err instanceof hls.HlsError) return res.status(err.status || 400).json({ error: err.message, code: err.code });
  console.error("[hls] ошибка запроса:", err && err.message);
  return res.status(500).json({ error: "Непредвиденная ошибка сервера.", code: "internal" });
}

const wrap = fn => async (req, res) => {
  try {
    await fn(req, res);
  } catch (err) {
    sendError(res, err);
  }
};

const body = req => (req.body && typeof req.body === "object" ? req.body : {});

/** Подписанная ссылка зеркала на файл раздачи (как variant-url: дерево AllDebrid → sourceLink → /api/file/proxy). */
async function mirrorUrlFor(adId, filePath, base) {
  const list = await getMagnetFileList(Number(adId));
  const f = list && list.length ? findFile(list, filePath) : null;
  if (!f || !f.sourceLink) return { gone: true };
  const parsed = safeHttpsUrl(f.sourceLink);
  if (!isAllDebridFileLink(parsed)) return { gone: true };
  const link = parsed.toString();
  const expires = String(Math.floor(Date.now() / 1000) + config.MIRROR_TTL);
  const name = cleanFileName(f.path.split(/[\\/]/).pop() || f.name || "video");
  return {
    url: `${base}/api/file/proxy${name ? "/" + encodeURIComponent(name) : ""}?link=${encodeURIComponent(link)}&exp=${expires}&sig=${makeMirrorToken(link, expires)}`,
    size: Number(f.size) || 0
  };
}

internal.use("/internal/hls", hlsInternalLimiter);

// Тело разбирается только после проверки токена
const json256 = express.json({ limit: "256kb" });
const worker = [bearer("HLS_WORKER_TOKEN"), json256];
const cdn = [bearer("HLS_CDN_TOKEN"), json256];

/**
 * Задание «звук» (§18) для воркера — ровно поля фикстуры next-job-audio.json, у каждой дорожки mirror_url на файл
 * озвучки (sidecar — в раздаче фильма, custom — в раздаче озвучки). Файла озвучки больше нет — дорожка пропускается;
 * не осталось ни одной → { gone }. AllDebrid не ответил — исключение (failed с повтором).
 */
async function audioJobBody(job, base) {
  const tracks = [];
  for (const t of job.tracks || []) {
    if (!t.src || t.src.ad_id == null || !t.src.path) continue;
    const r = await mirrorUrlFor(t.src.ad_id, t.src.path, base);
    if (r.gone) continue;
    tracks.push({ key: t.key, dir: t.dir, mirror_url: r.url, offset_ms: t.offset_ms, name: t.name, language: t.language });
  }
  if (!tracks.length) return { gone: true };
  return { body: { job_id: job.job_id, type: "audio", hls_id: job.hls_id, profile: job.profile, duration_ms: job.duration_ms, tracks } };
}

// Задание в аренду + mirror_url. Файла больше нет в раздаче → задание failed (без повтора), AllDebrid не ответил →
// failed с повтором; берётся следующее (не больше 3 за запрос). Пусто → 204. Задание «звук» (§18) — свой формат.
internal.get("/internal/hls/next", ...worker, wrap(async (req, res) => {
  const base = publicBase(req);
  for (let i = 0; i < 3; i++) {
    const job = hls.next();
    if (!job) break;
    if (job.type === "audio") {
      let r;
      try {
        r = await audioJobBody(job, base);
      } catch (err) {
        hls.failed({ job_id: job.job_id, stage: "mirror", error: `AllDebrid: ${err.message || "ошибка"}`, retryable: true });
        continue;
      }
      if (r.gone) {
        hls.failed({ job_id: job.job_id, stage: "mirror", error: "Файлов озвучки нет в раздаче.", retryable: false });
        continue;
      }
      res.setHeader("Cache-Control", "no-store");
      return res.json(r.body);
    }
    let r;
    try {
      r = await mirrorUrlFor(job.ad_id, job.path, base);
    } catch (err) {
      hls.failed({ job_id: job.job_id, stage: "mirror", error: `AllDebrid: ${err.message || "ошибка"}`, retryable: true });
      continue;
    }
    if (r.gone) {
      hls.failed({ job_id: job.job_id, stage: "mirror", error: "Файла нет в раздаче.", retryable: false });
      continue;
    }
    res.setHeader("Cache-Control", "no-store");
    return res.json({ ...job, mirror_url: r.url });
  }
  res.status(204).end();
}));

internal.post("/internal/hls/probe", ...worker, wrap(async (req, res) => {
  const b = body(req);
  res.json(hls.handleProbe(String(b.job_id || ""), b));
}));

// Любая стадия (probe, fingerprint, wait, encode, verify, upload, done) продлевает аренду
internal.post("/internal/hls/progress", ...worker, wrap(async (req, res) => {
  const job = hls.progress(body(req));
  if (!job) return res.status(404).json({ error: "Задание не найдено.", code: "unknown_job" });
  res.json({ ok: true, leased_until: job.leased_until });
}));

internal.post("/internal/hls/done", ...worker, wrap(async (req, res) => {
  res.json(hls.done(body(req)));
}));

// §18: итог задания «звук» — дорожки xN в manifest.audio профиля
internal.post("/internal/hls/audio-done", ...worker, wrap(async (req, res) => {
  res.json(hlsAudio.audioDone(body(req)));
}));

internal.post("/internal/hls/failed", ...worker, wrap(async (req, res) => {
  const job = hls.failed(body(req));
  if (!job) return res.status(404).json({ error: "Задание не найдено.", code: "unknown_job" });
  const live = store.getJob(job.job_id);
  res.json({ ok: true, state: live ? live.state : null, attempts: live ? live.attempts : null });
}));

internal.get("/internal/hls/pending-deletes", ...cdn, wrap(async (req, res) => {
  res.setHeader("Cache-Control", "no-store");
  res.json(hls.pendingDeletes());
}));

// Неизвестные hls_id/профили (уже убранные, чужие) пропускаются: ответ всё равно 200. С path ("xN", §18) — удалена
// только папка внешней озвучки, профиль остаётся
internal.post("/internal/hls/evicted", ...cdn, wrap(async (req, res) => {
  const b = req.body;
  const list = Array.isArray(b) ? b : Array.isArray(b?.items) ? b.items : null;
  if (!list) return res.status(400).json({ error: "Ожидается массив [{hls_id, profile}].", code: "bad_request" });
  res.json({ ok: true, removed: hls.evicted(list) });
}));

// ---- §17 холодный архив (тоже CDN) ----

const listOf = req => {
  const b = req.body;
  return Array.isArray(b) ? b : Array.isArray(b?.items) ? b.items : null;
};

// Копия версии проверена в архиве: [{hls_id, profile, bytes, at}]
internal.post("/internal/hls/archived", ...cdn, wrap(async (req, res) => {
  const list = listOf(req);
  if (!list) return res.status(400).json({ error: "Ожидается массив [{hls_id, profile, bytes, at}].", code: "bad_request" });
  res.json({ ok: true, archived: hls.archived(list) });
}));

// Что вернуть из архива на CDN (запросы «Смотреть» и админки); здесь же считаются тайм-ауты
internal.get("/internal/hls/pending-restores", ...cdn, wrap(async (req, res) => {
  res.setHeader("Cache-Control", "no-store");
  res.json(hls.pendingRestores());
}));

// Итог возврата: [{hls_id, profile, ok, bytes?, error?}]
internal.post("/internal/hls/restored", ...cdn, wrap(async (req, res) => {
  const list = listOf(req);
  if (!list) return res.status(400).json({ error: "Ожидается массив [{hls_id, profile, ok}].", code: "bad_request" });
  res.json({ ok: true, ...hls.restored(list) });
}));

internal.post("/internal/hls/cdn-stats", ...cdn, wrap(async (req, res) => {
  hls.cdnStats(body(req));
  res.json({ ok: true });
}));

internal.all(/^\/internal\/hls(?:\/|$)/, (req, res) => res.status(404).json({ error: "Не найдено.", code: "not_found" }));

// ---- master.m3u8 (без cookie: подпись в адресе, работает в Tizen, mpv, ExoPlayer) ----

router.get("/api/hls/:id/master.m3u8", hlsLimiter, (req, res) => {
  res.setHeader("Cache-Control", "no-store");
  if (!config.HLS_ENABLED) return res.status(404).type("text/plain").send("Не найдено.");
  const id = String(req.params.id || "");
  const exp = String(req.query.exp || "");
  const v = hls.verifyMaster(id, exp, String(req.query.sig || ""));
  if (v === "expired") return res.status(410).type("text/plain").send("Ссылка устарела.");
  if (v !== "ok") return res.status(403).type("text/plain").send("Ссылка недействительна.");
  let text;
  try {
    text = hls.masterPlaylist(id, Number(exp));
  } catch (err) {
    if (err instanceof hls.HlsError) return res.status(err.status || 404).type("text/plain").send(err.message);
    throw err;
  }
  hls.touch(id);
  res.setHeader("Content-Type", "application/vnd.apple.mpegurl");
  res.send(text);
});

// ---- variant-url: блок hls (вызывается из routes/mirror.js при выдаче, мимо 15-минутного кэша) ----

/**
 * Блок hls для ответа variant-url или null (HLS выключен, пользователь не в бете). «Смотреть» при status none ставит
 * файл в очередь с приоритетом 0, а следующие две серии сезона — с приоритетом 1.
 */
async function variantHls(req, { adId, path, size }) {
  if (!hls.enabledFor(req.user && req.user.id)) return null;
  let st = hls.statusFor(adId, path);
  if (st.status === "none" && hls.videoFiles([{ path }]).length) {
    try {
      hls.enqueue({ ad_id: String(adId), path, size: Number(size) || 0, hash: cachedMagnetHash(adId), priority: 0 });
      const list = await getMagnetFileList(Number(adId)).catch(() => null);
      for (const f of hls.nextEpisodes(list || [], path, 2)) {
        if (hls.statusFor(adId, f.path).state === "failed") continue; // не повторять сломанное на каждое «Смотреть»
        hls.enqueue({ ad_id: String(adId), path: f.path, size: Number(f.size) || 0, hash: cachedMagnetHash(adId), priority: 1 });
      }
    } catch (err) {
      console.warn("[hls] не удалось поставить в очередь:", err.message);
    }
    st = hls.statusFor(adId, path);
  }
  // «Смотреть» на версии в архиве (в т.ч. найденной только что через enqueue по hls_id) сразу просит CDN вернуть её
  // (§17); повтор — отметка «ждёт пользователь»
  if ((st.status === "cold" || st.status === "restoring") && st.hls_id) {
    try {
      hls.requestRestore(st.hls_id, st.profile, Date.now(), {
        user: true,
        file: { ad_id: String(adId), path, size: Number(size) || 0, hash: cachedMagnetHash(adId) }
      });
    } catch (err) {
      console.warn("[hls] не удалось запросить возврат из архива:", err.message);
    }
    st = hls.statusFor(adId, path);
  }
  const block = { status: st.status };
  // §18: внешних озвучек этого видео ещё нет в HLS — задание «звук» с приоритетом 1 (в фоне, не чаще раза в 5 мин)
  if (st.status === "ready" && st.hls_id) hlsAudio.onWatch(st.hls_id);
  if (st.status === "ready") {
    const exp = Math.floor(Date.now() / 1000) + hls.MASTER_TTL_S;
    block.url = `${publicBase(req)}/api/hls/${st.hls_id}/master.m3u8?exp=${exp}&sig=${hls.signMaster(st.hls_id, exp)}`;
    block.profile = st.profile;
    block.shift_ms = st.shift_ms || 0;
  } else if (st.status === "encoding") {
    if (st.profile) block.profile = st.profile;
    block.eta = st.eta ?? null;
    block.progress = st.progress ?? 0;
  } else if (st.status === "restoring") {
    block.eta = st.eta ?? null;
    if (st.profile) block.profile = st.profile;
  } else if (st.status === "cold") {
    block.status = "none"; // запрос возврата не удался — для клиента как «нет»
  }
  const d = hls.directFor(adId, path);
  block.direct_ok = d.direct_ok;
  block.direct = d.direct;
  return block;
}

// ---- Админка (T6): только isLibraryAdmin ----

function adminOnly(req, res, next) {
  if (!isLibraryAdmin(req)) return res.status(403).json({ error: "Недостаточно прав администратора.", code: "forbidden" });
  next();
}

const admin = [requireLogin, hlsLimiter, adminOnly];

router.get("/api/hls/admin/state", ...admin, wrap(async (req, res) => {
  res.setHeader("Cache-Control", "no-store");
  res.json(hls.adminState());
}));

router.post("/api/hls/admin/retry", ...admin, wrap(async (req, res) => {
  res.json({ ok: true, job: hls.retryJob(body(req).job_id) });
}));

router.post("/api/hls/admin/reencode", ...admin, wrap(async (req, res) => {
  const b = body(req);
  const adId = Number(b.ad_id);
  const filePath = typeof b.path === "string" ? b.path.trim() : "";
  if (!Number.isSafeInteger(adId) || adId <= 0 || !filePath) return res.status(400).json({ error: "Нужны ad_id и path.", code: "bad_request" });
  const list = await getMagnetFileList(adId);
  const f = list && list.length ? findFile(list, filePath) : null;
  if (!f) return res.status(404).json({ error: "Файл не найден в раздаче.", code: "unknown_file" });
  if (!hls.videoFiles([{ path: f.path }]).length) return res.status(400).json({ error: "Это не видеофайл.", code: "bad_request" });
  const r = hls.reencode({ ad_id: String(adId), path: f.path, size: Number(f.size) || 0, hash: cachedMagnetHash(adId), replaces: b.hls_id });
  res.json({ ok: true, ...r });
}));

router.post("/api/hls/admin/delete", ...admin, wrap(async (req, res) => {
  const b = body(req);
  res.json({ ok: true, ...hls.requestDelete(String(b.hls_id || ""), b.profile || null) });
}));

// Достать версию из архива вручную: {hls_id, profile?}. Не cold → 409 not_cold, уже запрошено → ok (already: true)
router.post("/api/hls/admin/restore", ...admin, wrap(async (req, res) => {
  const b = body(req);
  res.json(hls.requestRestore(String(b.hls_id || ""), b.profile || null));
}));

module.exports = { router, internal, variantHls, bearer };

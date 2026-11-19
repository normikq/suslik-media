'use strict';

// Одно задание: ffprobe → отпечаток → probe → alias | wait | encode → проверка → manifest → CDN → done.
// Любая ошибка → POST failed {stage, error, retryable}. Пока идёт работа, раз в progressSec — progress (аренда).

const fs = require('node:fs');
const path = require('node:path');
const { ffprobeJson, normalizeProbe, mainVideo, copyCandidate, keyframeIntervalMax, firstFrameColor } = require('./probe');
const { fingerprintUrl } = require('./fingerprint');
const { buildPlan, buildFfmpegArgs, planDirs } = require('./plan');
const { finalizeOutput } = require('./manifest');
const { upload } = require('./upload');
const { run, lastLine } = require('./proc');

class JobError extends Error {
  constructor(stage, msg, retryable) { super(msg); this.stage = stage; this.retryable = retryable; }
}

const sleep = (ms, signal) => new Promise((resolve) => {
  if (signal?.aborted) return resolve();
  const t = setTimeout(resolve, ms);
  signal?.addEventListener('abort', () => { clearTimeout(t); resolve(); }, { once: true });
});

/**
 * @param ctx {cfg, api, log, signal, ffmpegVersion}
 * @returns {Promise<{result:'done'|'alias'|'failed'|'aborted', ...}>}
 */
async function processJob(job, ctx) {
  const { cfg, api, log, signal } = ctx;
  const state = { stage: 'probe', progress: 0, eta_s: null, fps: null };
  const jobDir = path.join(cfg.workDir, String(job.job_id).replace(/[^\w.-]/g, '_'));
  let timer = null;

  const sendProgress = async () => {
    const body = { job_id: job.job_id, stage: state.stage, progress: Math.round(state.progress * 1000) / 1000, eta_s: state.eta_s };
    if (state.fps !== null) body.fps = state.fps;
    try { await api.progress(body); } catch (e) { log.warn(`[progress] ${e.message}`); }
  };

  try {
    if (!job.job_id || !job.mirror_url || !Number.isSafeInteger(job.size)) {
      throw new JobError('probe', 'задание без job_id/mirror_url/size', false);
    }
    log.info(`[job] ${job.job_id} ${job.path} (${(job.size / 1024 ** 3).toFixed(2)} ГБ, приоритет ${job.priority})`);
    if (job.size > cfg.maxSourceBytes) {
      throw new JobError('probe', `исходник ${(job.size / 1024 ** 3).toFixed(1)} ГБ больше SM_MAX_SOURCE_GB=${cfg.maxSourceBytes / 1024 ** 3}`, false);
    }
    timer = setInterval(sendProgress, cfg.progressSec * 1000);
    timer.unref?.();
    await sendProgress();

    // 1. ffprobe по зеркалу
    let raw;
    try { raw = await ffprobeJson(cfg, job.mirror_url, { signal }); } catch (e) { throw new JobError('probe', e.message, e.retryable !== false); }
    const probe = normalizeProbe(raw);
    const v = mainVideo(probe);
    if (!v) throw new JobError('probe', 'в файле нет видеодорожки', false);
    if (!probe.duration_ms) throw new JobError('probe', 'ffprobe не дал длительность', false);
    if (!v.color_transfer || v.color_transfer === 'unknown') {
      // в заголовке MKV цвета нет — смотрим первый кадр (PQ/HLG/DV в VUI и SEI)
      const fc = await firstFrameColor(cfg, job.mirror_url, v.index, { signal });
      if (fc) { v.color_transfer = fc.color_transfer === 'unknown' ? '' : fc.color_transfer; v.dovi = v.dovi || fc.dovi; }
    }
    if (copyCandidate(probe)) {
      try {
        v.keyframe_interval_max_s = await keyframeIntervalMax(cfg, job.mirror_url, v.index, cfg.keyframeProbeSec, { signal });
      } catch (e) { log.warn(`[probe] ключевые кадры: ${e.message}`); }
    }
    log.info(`[probe] ${probe.format.format_name} ${v.codec_name} ${v.width}x${v.height} ${v.pix_fmt} ${v.color_transfer || '-'} ` +
      `${Math.round((probe.format.bit_rate || 0) / 1000)} кбит/с, ${(probe.duration_ms / 60000).toFixed(1)} мин, kf_max=${v.keyframe_interval_max_s}`);

    // 2. отпечаток (Range-запросы к зеркалу)
    state.stage = 'fingerprint';
    let fingerprint;
    try { fingerprint = await fingerprintUrl(job.mirror_url, job.size, { signal }); } catch (e) { throw new JobError('fingerprint', e.message, true); }
    log.info(`[fingerprint] ${fingerprint}`);

    // 3. probe → решение сервера
    let decision;
    for (;;) {
      state.stage = 'probe';
      try {
        decision = await api.probe({ job_id: job.job_id, fingerprint, duration_ms: probe.duration_ms, format: probe.format, streams: probe.streams });
      } catch (e) { throw new JobError('probe', `POST probe: ${e.message}`, true); }
      if (!decision || typeof decision.action !== 'string') throw new JobError('probe', 'пустой ответ probe', true);
      if (decision.action !== 'wait') break;
      const waitS = Math.max(1, Number(decision.retry_after_s) || 60);
      log.info(`[probe] wait ${waitS} с (ассет кодирует другое задание)`);
      state.stage = 'wait';
      await sleep(waitS * 1000, signal);
      if (signal?.aborted) throw new JobError('wait', 'остановка воркера', true);
    }
    if (decision.action === 'alias') {
      // сервер сам записал алиас (§10): кодировать нечего, done не нужен
      log.info(`[probe] alias → ${decision.hls_id} shift_ms=${decision.shift_ms ?? 0}`);
      return { result: 'alias', hls_id: decision.hls_id, shift_ms: decision.shift_ms ?? 0 };
    }
    if (decision.action !== 'encode') throw new JobError('probe', `неизвестное действие ${decision.action}`, true);
    const hlsId = decision.hls_id;
    const profile = decision.profile;
    if (!/^[0-9a-f]{16}$/.test(hlsId || '')) throw new JobError('probe', `плохой hls_id ${hlsId}`, true);

    // 4. кодирование
    state.stage = 'encode';
    state.progress = 0;
    let plan;
    try { plan = buildPlan(probe, profile, raw); } catch (e) { throw new JobError('encode', e.message, e.retryable !== false); }
    const outDir = path.join(jobDir, profile);
    fs.rmSync(jobDir, { recursive: true, force: true });
    for (const d of planDirs(plan)) fs.mkdirSync(path.join(outDir, d), { recursive: true });
    const args = buildFfmpegArgs(cfg, plan, job.mirror_url, outDir);
    log.info(`[encode] ${profile} ${cfg.encoder}: видео ${plan.video.map((t) => `${t.dir} ${t.width}x${t.height}`).join(', ')}; ` +
      `звук ${plan.audio.length}; субтитры ${plan.subs.length}; пропущено ${plan.skipped.length}; тонмаппинг ${plan.tonemap}`);
    log.info(`[encode] ffmpeg ${args.map((a) => (/\s/.test(a) ? JSON.stringify(a) : a)).join(' ')}`);
    const durS = probe.duration_ms / 1000;
    const t0 = Date.now();
    const kv = {};
    const r = await run(cfg.ffmpeg, args, {
      signal,
      tailBytes: 16384,
      onLine(line) {
        const i = line.indexOf('=');
        if (i < 0) return;
        kv[line.slice(0, i)] = line.slice(i + 1);
        if (line.startsWith('progress=')) {
          const us = Number(kv.out_time_us ?? kv.out_time_ms);
          if (Number.isFinite(us) && us > 0) {
            state.progress = Math.min(0.999, us / 1e6 / durS);
            const speed = parseFloat(kv.speed);
            state.eta_s = speed > 0 ? Math.round((durS - us / 1e6) / speed) : null;
          }
          const fps = parseFloat(kv.fps);
          state.fps = Number.isFinite(fps) ? Math.round(fps) : null;
        }
      },
    });
    if (signal?.aborted) throw new JobError('encode', 'остановка воркера', true);
    if (r.code !== 0) throw new JobError('encode', `ffmpeg exit ${r.code}: ${lastLine(r.stderr)}`, true);
    log.info(`[encode] готово за ${Math.round((Date.now() - t0) / 1000)} с`);
    state.progress = 1;
    state.eta_s = 0;
    state.fps = null;

    // 5. проверка плейлистов, субтитры, manifest.json
    state.stage = 'verify';
    let out;
    try {
      out = finalizeOutput({ outDir, plan, job, hlsId, fingerprint, durationMs: probe.duration_ms, encoder: cfg.encoder, ffmpegVersion: ctx.ffmpegVersion });
    } catch (e) { throw new JobError('verify', e.message, false); }
    log.info(`[verify] ${out.bytes} байт, manifest ${out.sha256}`);

    // 6. CDN
    state.stage = 'upload';
    state.progress = 0;
    try { await upload(cfg, outDir, hlsId, profile, { signal, log }); } catch (e) { throw new JobError('upload', e.message, true); }

    // 7. done
    try {
      await api.done({ job_id: job.job_id, hls_id: hlsId, profile, manifest_sha256: out.sha256, bytes: out.bytes });
    } catch (e) { throw new JobError('done', `POST done: ${e.message}`, true); }
    log.info(`[done] ${job.job_id} → ${hlsId}/${profile}`);
    return { result: 'done', hls_id: hlsId, profile, bytes: out.bytes, manifest: out.manifest, outDir };
  } catch (e) {
    const je = e instanceof JobError ? e : new JobError(state.stage, e.message || String(e), true);
    log.error(`[failed] ${job.job_id} ${je.stage}: ${je.message} (retryable=${je.retryable})`);
    try {
      await api.failed({ job_id: job.job_id, stage: je.stage, error: je.message.slice(0, 500), retryable: je.retryable });
    } catch (e2) { log.error(`[failed] не удалось сообщить серверу: ${e2.message}`); }
    return { result: signal?.aborted ? 'aborted' : 'failed', stage: je.stage, error: je.message, retryable: je.retryable };
  } finally {
    if (timer) clearInterval(timer);
    if (!ctx.keepWork) fs.rmSync(jobDir, { recursive: true, force: true });
  }
}

module.exports = { processJob, JobError, sleep };

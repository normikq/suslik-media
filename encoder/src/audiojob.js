'use strict';

// Задание «звук» (docs/HLS.md §18): внешние озвучки (.mka) → xN/ того же профиля.
// На каждую дорожку — своя команда ffmpeg по mirror_url: сдвиг как на ТВ (CUSTOM-AUDIO-API.md §8),
// длительность ровно duration_ms видео, AAC-LC стерео 128 кбит, HLS fMP4 single_file по 6 с.
// Затем проверка плейлистов → CDN (finalize-audio, только папки xN/) → POST audio-done.

const fs = require('node:fs');
const path = require('node:path');
const { inputNetArgs } = require('./probe');
const { SEG } = require('./plan');
const { readPlaylist, dirBytes } = require('./manifest');
const { totalDuration } = require('./playlist');
const { uploadAudio } = require('./upload');
const { run, lastLine } = require('./proc');

const MAX_TRACKS = 8;
const AUDIO_BITRATE = 128000;
const AAC_LC = 'mp4a.40.2';
const DURATION_TOLERANCE_MS = 1000;

const sec = (ms) => (ms / 1000).toFixed(3);

/** Проверка задания; ошибка — строка (задание битое, повтор не поможет). */
function checkAudioJob(job) {
  if (!job.job_id) return 'задание без job_id';
  if (!/^[0-9a-f]{16}$/.test(job.hls_id || '')) return `плохой hls_id ${job.hls_id}`;
  if (!/^[\w.-]+$/.test(job.profile || '')) return `плохой profile ${job.profile}`;
  if (!Number.isSafeInteger(job.duration_ms) || job.duration_ms <= 0) return `плохой duration_ms ${job.duration_ms}`;
  if (!Array.isArray(job.tracks) || !job.tracks.length) return 'задание без tracks';
  if (job.tracks.length > MAX_TRACKS) return `дорожек ${job.tracks.length} > ${MAX_TRACKS}`;
  const dirs = new Set();
  for (const t of job.tracks) {
    if (!t || !t.key) return 'дорожка без key';
    if (!/^x[0-7]$/.test(t.dir || '')) return `${t.key}: плохой dir ${t.dir}`;
    if (dirs.has(t.dir)) return `${t.key}: dir ${t.dir} повторяется`;
    dirs.add(t.dir);
    if (typeof t.mirror_url !== 'string' || !t.mirror_url) return `${t.key}: нет mirror_url`;
    if (t.offset_ms !== undefined && t.offset_ms !== null && !Number.isSafeInteger(t.offset_ms)) return `${t.key}: плохой offset_ms ${t.offset_ms}`;
  }
  return null;
}

/**
 * Аргументы ffmpeg для одной внешней дорожки.
 * Место в дорожке = место в видео − offset: offset > 0 — тишина в начале (adelay), offset < 0 — первые |offset| мс
 * дорожки пропускаются (-ss до -i, точный поиск с декодированием). apad + -t — ровно duration_ms.
 * @param dir папка дорожки (…/xN)
 */
function buildAudioArgs(cfg, track, durationMs, dir) {
  const offset = Number(track.offset_ms) || 0;
  const args = ['-hide_banner', '-nostdin', '-y', '-loglevel', 'error', '-progress', 'pipe:1', '-nostats'];
  if (offset < 0) args.push('-ss', sec(-offset));
  args.push(...inputNetArgs(track.mirror_url, cfg), '-i', track.mirror_url);
  const filters = [];
  if (offset > 0) filters.push(`adelay=${offset}:all=1`);
  filters.push('apad');
  args.push(
    '-map', '0:a:0', '-vn', '-sn', '-dn',
    '-af', filters.join(','), '-t', sec(durationMs),
    '-c:a', 'aac', '-profile:a', 'aac_low', '-b:a', String(AUDIO_BITRATE), '-ac', '2', '-ar', '48000',
    '-map_metadata', '-1', '-map_chapters', '-1', '-max_muxing_queue_size', '4096',
    '-f', 'hls', '-hls_time', String(SEG), '-hls_playlist_type', 'vod', '-hls_list_size', '0',
    '-hls_segment_type', 'fmp4', '-hls_flags', 'independent_segments+single_file',
    '-hls_segment_filename', path.join(dir, 'audio.m4s'), path.join(dir, 'index.m3u8'),
  );
  return args;
}

/** Ошибка ffmpeg → {message, retryable}: битый файл, нет файла, нет звука — без повтора; сеть, подпись, 5xx — с повтором. */
function classifyFfmpegError(stderr, code) {
  const line = lastLine(stderr) || `ffmpeg exit ${code}`;
  const text = String(stderr || '');
  if (/404 Not Found|410 Gone/i.test(text)) return { message: `файл озвучки не найден (${line})`, retryable: false };
  if (/matches no streams/i.test(text)) return { message: 'в файле озвучки нет звуковой дорожки', retryable: false };
  if (/Invalid data found|EBML header parsing failed|could not find codec|Error while decoding|does not contain any stream/i.test(text)) {
    return { message: `файл озвучки испорчен (${line})`, retryable: false };
  }
  return { message: `ffmpeg exit ${code}: ${line}`, retryable: true };
}

/** Плейлист дорожки: EXT-X-MAP, BYTERANGE без дыр до конца audio.m4s, сумма EXTINF ≈ duration_ms. */
function verifyAudioTrack(outDir, dir, durationMs) {
  const { pl } = readPlaylist(outDir, dir, 'audio.m4s');
  const total = totalDuration(pl) * 1000;
  if (Math.abs(total - durationMs) > DURATION_TOLERANCE_MS) {
    throw new Error(`${dir}: длительность ${Math.round(total)} мс, ожидалось ${durationMs} ± ${DURATION_TOLERANCE_MS}`);
  }
  return { bytes: dirBytes(path.join(outDir, dir)), durationMs: Math.round(total) };
}

/**
 * @param job  {job_id, type:"audio", hls_id, profile, duration_ms, tracks:[{key, dir, mirror_url, offset_ms, name, language}]}
 * @param ctx  {cfg, api, log, signal, keepWork}
 * @param JobError класс ошибки из job.js (stage, retryable)
 */
async function processAudioJob(job, ctx, JobError) {
  const { cfg, api, log, signal } = ctx;
  const state = { stage: 'encode', progress: 0, eta_s: null };
  const jobDir = path.join(cfg.workDir, String(job.job_id || 'job').replace(/[^\w.-]/g, '_'));
  let timer = null;

  const sendProgress = async () => {
    const body = { job_id: job.job_id, stage: state.stage, progress: Math.round(state.progress * 1000) / 1000, eta_s: state.eta_s };
    try { await api.progress(body); } catch (e) { log.warn(`[progress] ${e.message}`); }
  };

  try {
    const bad = checkAudioJob(job);
    if (bad) throw new JobError('encode', bad, false);
    const { hls_id: hlsId, profile, duration_ms: durationMs, tracks } = job;
    log.info(`[job] ${job.job_id} звук → ${hlsId}/${profile}: ${tracks.map((t) => `${t.dir} ${t.key} (${t.offset_ms || 0} мс)`).join(', ')}`);
    timer = setInterval(sendProgress, cfg.progressSec * 1000);
    timer.unref?.();
    await sendProgress();

    // 1. кодирование, дорожка за дорожкой
    const outDir = path.join(jobDir, profile);
    fs.rmSync(jobDir, { recursive: true, force: true });
    const durS = durationMs / 1000;
    const n = tracks.length;
    for (let i = 0; i < n; i++) {
      const t = tracks[i];
      const dir = path.join(outDir, t.dir);
      fs.mkdirSync(dir, { recursive: true });
      const args = buildAudioArgs(cfg, t, durationMs, dir);
      log.info(`[encode] ${t.dir} ${t.key} offset ${t.offset_ms || 0} мс`);
      log.info(`[encode] ffmpeg ${args.map((a) => (/\s/.test(a) ? JSON.stringify(a) : a)).join(' ')}`);
      const t0 = Date.now();
      const kv = {};
      const r = await run(cfg.ffmpeg, args, {
        signal,
        tailBytes: 16384,
        onLine(line) {
          const k = line.indexOf('=');
          if (k < 0) return;
          kv[line.slice(0, k)] = line.slice(k + 1);
          if (!line.startsWith('progress=')) return;
          const us = Number(kv.out_time_us ?? kv.out_time_ms);
          if (!Number.isFinite(us) || us <= 0) return;
          const done = Math.min(1, us / 1e6 / durS);
          state.progress = Math.min(0.999, (i + done) / n);
          const speed = parseFloat(kv.speed);
          state.eta_s = speed > 0 ? Math.round(((1 - done) * durS + (n - i - 1) * durS) / speed) : null;
        },
      });
      if (signal?.aborted) throw new JobError('encode', 'остановка воркера', true);
      if (r.code !== 0) {
        const c = classifyFfmpegError(r.stderr, r.code);
        throw new JobError('encode', `${t.dir} (${t.key}): ${c.message}`, c.retryable);
      }
      log.info(`[encode] ${t.dir} готово за ${Math.round((Date.now() - t0) / 1000)} с`);
    }
    state.progress = 1;
    state.eta_s = 0;

    // 2. проверка плейлистов
    state.stage = 'verify';
    const result = [];
    try {
      for (const t of tracks) {
        const v = verifyAudioTrack(outDir, t.dir, durationMs);
        result.push({ key: t.key, dir: t.dir, codec: AAC_LC, channels: 2, bytes: v.bytes });
        log.info(`[verify] ${t.dir} ${v.durationMs} мс, ${v.bytes} байт`);
      }
    } catch (e) { throw new JobError('verify', e.message, false); }

    // 3. CDN: только папки xN/
    state.stage = 'upload';
    state.progress = 0;
    state.eta_s = null;
    try { await uploadAudio(cfg, outDir, hlsId, profile, tracks.map((t) => t.dir), { signal, log }); } catch (e) { throw new JobError('upload', e.message, true); }

    // 4. audio-done
    try {
      await api.audioDone({ job_id: job.job_id, hls_id: hlsId, profile, tracks: result });
    } catch (e) { throw new JobError('done', `POST audio-done: ${e.message}`, true); }
    log.info(`[done] ${job.job_id} → ${hlsId}/${profile} ${result.map((t) => t.dir).join(' ')}`);
    return { result: 'done', type: 'audio', hls_id: hlsId, profile, tracks: result, outDir };
  } catch (e) {
    const je = e instanceof JobError ? e : new JobError(state.stage, e.message || String(e), true);
    log.error(`[failed] ${job.job_id} ${je.stage}: ${je.message} (retryable=${je.retryable})`);
    try {
      await api.failed({ job_id: job.job_id, stage: je.stage, error: je.message.slice(0, 500), retryable: je.retryable });
    } catch (e2) { log.error(`[failed] не удалось сообщить серверу: ${e2.message}`); }
    return { result: signal?.aborted ? 'aborted' : 'failed', type: 'audio', stage: je.stage, error: je.message, retryable: je.retryable };
  } finally {
    if (timer) clearInterval(timer);
    if (!ctx.keepWork) fs.rmSync(jobDir, { recursive: true, force: true });
  }
}

module.exports = { processAudioJob, buildAudioArgs, checkAudioJob, classifyFfmpegError, verifyAudioTrack, AUDIO_BITRATE, MAX_TRACKS };

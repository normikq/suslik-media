'use strict';

// План кодирования и аргументы ffmpeg для профилей v1 и v1-copy (docs/HLS.md §4, §5).
// Одна команда ffmpeg: исходник читается по HTTP один раз, на выходе — отдельный HLS-плейлист
// на каждую дорожку (1080p/, 720p/, a0…a3/) и subs.vtt на каждые субтитры (s0…s5/).

const path = require('node:path');
const { TEXT_SUBS, inputNetArgs, parseRate, isHdr } = require('./probe');
const { lang2, trackName } = require('./langs');
const { libplaceboFilter, vulkanArgs, doviNeedsRpu, tonemapPathFor } = require('./tonemap');

const SEG = 6;            // длина сегмента и шаг ключевых кадров, с
const MAX_AUDIO = 4;
const MAX_SUBS = 6;
const AUDIO_BITRATE = 160000;
const TIERS = [
  { dir: '1080p', maxW: 1920, maxH: 1080, maxrate: 7e6 },
  { dir: '720p', maxW: 1280, maxH: 720, maxrate: 4e6 },
];

const even = (x) => Math.max(2, 2 * Math.round(x / 2));

// Что NVDEC декодирует наверняка (кадры сразу в памяти GPU). Остальное (H.264 10 бит, 4:2:2, MPEG-4 ASP…) — масштаб на CPU
const NVDEC_OK = {
  h264: ['yuv420p', 'yuvj420p'],
  hevc: ['yuv420p', 'yuvj420p', 'yuv420p10le'],
  av1: ['yuv420p', 'yuv420p10le'],
  vp9: ['yuv420p', 'yuv420p10le'],
  mpeg2video: ['yuv420p'],
};

/**
 * Можно ли весь видеотракт держать на видеокарте (plan.gpu): nvenc + hwdec + gpuScale, без тонмаппинга (у него свои пути)
 * и исходник из NVDEC_OK. Черезстрочное — bwdif_cuda.
 */
function gpuPathOk(cfg, plan, tm) {
  if (cfg.encoder !== 'nvenc' || !cfg.hwdec || !cfg.gpuScale || tm) return false;
  if (plan.profile === 'v1-copy' && plan.video.every((t) => t.copy)) return false;
  return (NVDEC_OK[plan.srcCodec] || []).includes(plan.srcPixFmt);
}

function skipReason(codec) {
  if (codec === 'hdmv_pgs_subtitle') return 'pgs';
  if (codec === 'dvd_subtitle') return 'vobsub';
  if (codec === 'dvb_subtitle' || codec === 'dvb_teletext') return 'dvb';
  return codec || 'unknown';
}

/** Размеры кадра с учётом SAR (анаморф) — в квадратных пикселях. */
function displaySize(v, raw) {
  let w = v.width;
  const h = v.height;
  const m = /^(\d+):(\d+)$/.exec(raw?.sample_aspect_ratio || '');
  if (m && Number(m[1]) > 0 && Number(m[2]) > 0 && m[1] !== m[2]) w = w * Number(m[1]) / Number(m[2]);
  return { w, h };
}

/** Ступени v1: вписать в 1920×1080 и 1280×720 без увеличения; маленький исходник — только 720p. */
function videoTiers(disp) {
  const out = [];
  for (const t of TIERS) {
    const f = Math.min(1, t.maxW / disp.w, t.maxH / disp.h);
    out.push({ dir: t.dir, width: even(disp.w * f), height: even(disp.h * f), maxrate: t.maxrate });
  }
  if (disp.w <= 1280 && disp.h <= 720) return [out[1]];
  return out;
}

/**
 * Дорожки v1-copy (§5): верхняя — копия исходника (папка по высоте: > 720 → 1080p/, иначе 720p/),
 * при высоте > 720 — ещё 720p, перекодированная с ключевыми кадрами ровно там, где у исходника (sourceKeys).
 */
function copyTiers(v, disp) {
  const big = v.height > 720;
  const out = [{ dir: big ? '1080p' : '720p', width: v.width, height: v.height, copy: true, codec_name: v.codec_name }];
  if (big) {
    const t = TIERS[1];
    const f = Math.min(1, t.maxW / disp.w, t.maxH / disp.h);
    out.push({ dir: t.dir, width: even(disp.w * f), height: even(disp.h * f), maxrate: t.maxrate, sourceKeys: true });
  }
  return out;
}

/** Dolby Vision: профиль и совместимость базы из DOVI configuration record (ffprobe), null — не DV. */
function doviInfo(v, rawV) {
  if (!v.dovi) return null;
  const rec = (rawV?.side_data_list || []).find((d) => /dovi configuration/i.test(d.side_data_type || ''));
  const profile = Number.isFinite(rec?.dv_profile) ? rec.dv_profile : null;
  const compat = Number.isFinite(rec?.dv_bl_signal_compatibility_id) ? rec.dv_bl_signal_compatibility_id : null;
  return { profile, compat, needsRpu: doviNeedsRpu({ profile, compat }, v.color_transfer) };
}

/**
 * @param probe   normalizeProbe(...) (+ raw: ответ ffprobe как есть)
 * @param profile 'v1' | 'v1-copy'
 */
function buildPlan(probe, profile, raw = {}) {
  const rawById = new Map((raw.streams || []).map((s) => [s.index, s]));
  const v = probe.streams.find((s) => s.codec_type === 'video');
  if (!v) throw Object.assign(new Error('в файле нет видеодорожки'), { retryable: false });
  const fps = parseRate(v.avg_frame_rate) || parseRate(rawById.get(v.index)?.r_frame_rate) || 24;
  const skipped = [];

  let video;
  let tonemap = false;
  let dovi = null;
  if (profile === 'v1-copy') {
    video = copyTiers(v, displaySize(v, rawById.get(v.index)));
  } else if (profile === 'v1') {
    tonemap = isHdr(v);
    dovi = doviInfo(v, rawById.get(v.index));
    video = videoTiers(displaySize(v, rawById.get(v.index)));
  } else {
    throw Object.assign(new Error(`неизвестный профиль ${profile}`), { retryable: false });
  }

  const audioSrc = probe.streams.filter((s) => s.codec_type === 'audio');
  const audio = audioSrc.slice(0, MAX_AUDIO).map((s, i) => ({
    dir: `a${i}`, src_index: s.index, language: lang2(s.language), name: trackName(s.language, s.title, 'audio', i), default: false,
  }));
  for (const s of audioSrc.slice(MAX_AUDIO)) skipped.push({ src_index: s.index, reason: 'limit' });
  if (audio.length) {
    const defIdx = audioSrc.slice(0, MAX_AUDIO).findIndex((s) => s.default);
    audio[defIdx >= 0 ? defIdx : 0].default = true;
  }

  const subs = [];
  for (const s of probe.streams.filter((x) => x.codec_type === 'subtitle')) {
    if (!TEXT_SUBS.has(s.codec_name)) { skipped.push({ src_index: s.index, reason: skipReason(s.codec_name) }); continue; }
    if (subs.length >= MAX_SUBS) { skipped.push({ src_index: s.index, reason: 'limit' }); continue; }
    subs.push({ dir: `s${subs.length}`, src_index: s.index, language: lang2(s.language), name: trackName(s.language, s.title, 'subtitle', subs.length), forced: !!s.forced });
  }

  return { profile, srcVideoIndex: v.index, srcCodec: v.codec_name || '', srcPixFmt: v.pix_fmt || '', fps, interlaced: /^(tt|bb|tb|bt)$/.test(rawById.get(v.index)?.field_order || ''), tonemap, dovi, video, audio, subs, skipped };
}

function hlsOut(dir, file) {
  return [
    '-f', 'hls', '-hls_time', String(SEG), '-hls_playlist_type', 'vod', '-hls_list_size', '0',
    '-hls_segment_type', 'fmp4', '-hls_flags', 'independent_segments+single_file',
    '-hls_segment_filename', path.join(dir, file), path.join(dir, 'index.m3u8'),
  ];
}

const commonOut = ['-map_metadata', '-1', '-map_chapters', '-1', '-max_muxing_queue_size', '4096'];

function videoEncoderArgs(cfg, tier, fps, tonemap) {
  // сами по себе ключевые кадры не появятся — только принудительные: каждые 6 с (v1)
  // или ровно на ключевых кадрах исходника (720p в v1-copy, интервал у исходника до 11 с — §5)
  const gop = tier.sourceKeys ? Math.max(1000, Math.ceil(fps * 30)) : Math.max(1000, Math.ceil(fps * SEG * 2));
  const kf = ['-force_key_frames', tier.sourceKeys ? 'source' : `expr:gte(t,n_forced*${SEG})`, '-forced-idr', '1'];
  const rate = ['-maxrate', String(tier.maxrate), '-bufsize', String(tier.maxrate * 2)];
  // после тонмаппинга помечаем bt709 явно; иначе цвет берётся из кадров исходника
  const color = tonemap ? ['-color_primaries', 'bt709', '-color_trc', 'bt709', '-colorspace', 'bt709'] : [];
  if (cfg.encoder === 'nvenc') {
    return ['-c:v', 'hevc_nvenc', '-preset', cfg.nvencPreset, '-tune', 'hq', '-profile:v', 'main', '-rc', 'vbr',
      '-cq', String(cfg.cq), '-b:v', '0', ...rate, '-spatial-aq', '1', '-no-scenecut', '1', '-g', String(gop), ...kf,
      '-tag:v', 'hvc1', ...color];
  }
  if (cfg.encoder === 'x265') {
    return ['-c:v', 'libx265', '-preset', cfg.swPreset, '-profile:v', 'main', '-crf', String(cfg.cq), ...rate, ...kf,
      '-x265-params', `keyint=${gop}:min-keyint=1:scenecut=0:open-gop=0:log-level=error`, '-tag:v', 'hvc1', ...color];
  }
  // x264 — только для проверки без видеокарты (avc1)
  return ['-c:v', 'libx264', '-preset', cfg.swPreset, '-profile:v', 'high', '-crf', String(cfg.cq), ...rate, ...kf,
    '-g', String(gop), '-keyint_min', '1', '-sc_threshold', '0', '-x264-params', 'scenecut=0:open-gop=0', '-tag:v', 'avc1', ...color];
}

/**
 * Полный список аргументов ffmpeg. outDir — папка профиля (в ней 1080p/, a0/, …).
 * HDR → SDR (plan.tonemapPath, иначе tonemapPathFor):
 *   libplacebo — масштаб до верхней ступени + тонмаппинг + bt709 на видеокарте (Vulkan) за один проход;
 *   cpu        — сначала уменьшить до верхней ступени (10 бит), потом zscale+tonemap уже на 1080p.
 * Остальные ступени — scale от результата верхней.
 * Без тонмаппинга и с исходником, который декодирует NVDEC (gpuPathOk), — всё на видеокарте: scale_cuda (и bwdif_cuda),
 * кадры не уходят в память. plan.gpu === false (повтор после сбоя) — по-старому, на CPU.
 */
function buildFfmpegArgs(cfg, plan, src, outDir) {
  const args = ['-hide_banner', '-nostdin', '-y', '-loglevel', 'error', '-progress', 'pipe:1', '-nostats'];
  const tm = plan.profile === 'v1' ? (plan.tonemapPath !== undefined ? plan.tonemapPath : tonemapPathFor(cfg, plan)) : null;
  // v1-copy: декодировать нужно только для 720p (копия идёт пакетами)
  const decodes = plan.profile === 'v1' || plan.video.some((t) => !t.copy);
  const gpu = plan.gpu !== false && gpuPathOk(cfg, plan, tm);
  if (decodes && cfg.encoder === 'nvenc' && cfg.hwdec) args.push('-hwaccel', 'cuda');
  if (gpu) args.push('-hwaccel_output_format', 'cuda');
  if (tm === 'libplacebo') args.push(...vulkanArgs(cfg.tonemapPath.device.index));
  args.push(...inputNetArgs(src, cfg), '-i', src);

  if (gpu) {
    const deint = plan.interlaced ? 'bwdif_cuda=mode=send_frame:deint=interlaced,' : '';
    const enc = plan.video.filter((t) => !t.copy);
    let graph;
    if (plan.profile === 'v1') {
      // верхняя ступень + 8 бит за один scale_cuda (тот же размер — без пересчёта), остальные — от неё
      const [top, ...rest] = enc;
      graph = `[0:${plan.srcVideoIndex}]${deint}scale_cuda=w=${top.width}:h=${top.height}:format=yuv420p`;
      graph += rest.length ? `,split=${enc.length}${enc.map((_, i) => `[g${i}]`).join('')};[g0]setsar=1[v0]` : ',setsar=1[v0]';
      rest.forEach((t, k) => { graph += `;[g${k + 1}]scale_cuda=w=${t.width}:h=${t.height},setsar=1[v${k + 1}]`; });
    } else {
      graph = enc.map((t) => `[0:${plan.srcVideoIndex}]${deint}scale_cuda=w=${t.width}:h=${t.height}:format=yuv420p,setsar=1[c${t.dir}]`).join(';');
    }
    args.push('-filter_complex', graph);
    let i = 0;
    for (const t of plan.video) {
      const dir = path.join(outDir, t.dir);
      if (t.copy) {
        args.push('-map', `0:${plan.srcVideoIndex}`, '-c:v', 'copy', '-tag:v', t.codec_name === 'hevc' ? 'hvc1' : 'avc1', ...commonOut, ...hlsOut(dir, 'video.m4s'));
        continue;
      }
      const label = plan.profile === 'v1' ? `[v${i++}]` : `[c${t.dir}]`;
      args.push('-map', label, ...videoEncoderArgs(cfg, t, plan.fps, false), ...commonOut, ...hlsOut(dir, 'video.m4s'));
    }
  } else if (plan.profile === 'v1') {
    const top = plan.video[0];
    const pre = [];
    if (plan.interlaced) pre.push('bwdif=mode=send_frame:deint=interlaced');
    if (tm === 'libplacebo') {
      // HDR (PQ/HLG, DV с RPU) → SDR bt709, уже в размере верхней ступени, yuv420p
      pre.push(libplaceboFilter(top.width, top.height));
    } else if (tm === 'cpu') {
      // HDR (PQ/HLG, DV с базой HDR10) → SDR bt709; уменьшаем до тонмаппинга, чтобы float32 считался не на 4K
      pre.push(`scale=${top.width}:${top.height}`, 'format=yuv420p10le',
        'zscale=t=linear:npl=100', 'format=gbrpf32le', 'zscale=p=bt709', 'tonemap=tonemap=hable:desat=0',
        'zscale=t=bt709:m=bt709:r=tv');
    }
    pre.push('format=yuv420p');
    const n = plan.video.length;
    let graph = `[0:${plan.srcVideoIndex}]${pre.join(',')}`;
    graph += n > 1 ? `,split=${n}${plan.video.map((_, i) => `[s${i}]`).join('')};` : '[s0];';
    graph += plan.video.map((t, i) => `[s${i}]scale=${t.width}:${t.height},setsar=1[v${i}]`).join(';');
    args.push('-filter_complex', graph);
    plan.video.forEach((t, i) => {
      const dir = path.join(outDir, t.dir);
      args.push('-map', `[v${i}]`, ...videoEncoderArgs(cfg, t, plan.fps, plan.tonemap), ...commonOut, ...hlsOut(dir, 'video.m4s'));
    });
  } else {
    // v1-copy: копия + (если есть) 720p из того же входа за один проход
    const enc = plan.video.filter((t) => !t.copy);
    if (enc.length) {
      const pre = plan.interlaced ? ['bwdif=mode=send_frame:deint=interlaced'] : [];
      args.push('-filter_complex', enc.map((t) =>
        `[0:${plan.srcVideoIndex}]${[...pre, 'format=yuv420p', `scale=${t.width}:${t.height}`, 'setsar=1'].join(',')}[c${t.dir}]`).join(';'));
    }
    for (const t of plan.video) {
      const dir = path.join(outDir, t.dir);
      if (t.copy) {
        const tag = t.codec_name === 'hevc' ? 'hvc1' : 'avc1';
        args.push('-map', `0:${plan.srcVideoIndex}`, '-c:v', 'copy', '-tag:v', tag, ...commonOut, ...hlsOut(dir, 'video.m4s'));
        continue;
      }
      args.push('-map', `[c${t.dir}]`, ...videoEncoderArgs(cfg, t, plan.fps, false), ...commonOut, ...hlsOut(dir, 'video.m4s'));
    }
  }

  for (const a of plan.audio) {
    args.push('-map', `0:${a.src_index}`, '-c:a', 'aac', '-profile:a', 'aac_low', '-b:a', String(AUDIO_BITRATE), '-ac', '2', '-ar', '48000',
      ...commonOut, ...hlsOut(path.join(outDir, a.dir), 'audio.m4s'));
  }
  for (const s of plan.subs) {
    args.push('-map', `0:${s.src_index}`, '-c:s', 'webvtt', ...commonOut, '-f', 'webvtt', path.join(outDir, s.dir, 'subs.vtt'));
  }
  return args;
}

/** Все папки, которые ffmpeg ожидает готовыми. */
function planDirs(plan) {
  return [...plan.video, ...plan.audio, ...plan.subs].map((x) => x.dir);
}

module.exports = { buildPlan, buildFfmpegArgs, planDirs, gpuPathOk, videoTiers, SEG, AUDIO_BITRATE, MAX_AUDIO, MAX_SUBS };

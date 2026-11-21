'use strict';

// ffprobe исходника по ссылке зеркала и приведение к виду probe-request.json (docs/HLS.md §10).

const { run, lastLine } = require('./proc');

const TEXT_SUBS = new Set(['subrip', 'srt', 'ass', 'ssa', 'webvtt', 'mov_text', 'text']);
const HDR_TRC = new Set(['smpte2084', 'arib-std-b67']);

function isUrl(src) { return /^https?:\/\//i.test(src); }

/** Опции чтения по HTTP: переподключение и таймаут, чтобы не висеть вечно. */
function inputNetArgs(src, cfg) {
  if (!isUrl(src)) return [];
  return [
    '-reconnect', '1', '-reconnect_streamed', '1', '-reconnect_on_network_error', '1',
    '-reconnect_on_http_error', '5xx', '-reconnect_delay_max', String(cfg.reconnectDelayMax ?? 30),
    '-rw_timeout', String(60 * 1e6),
  ];
}

function numOrNull(v) {
  if (v === undefined || v === null || v === '' || v === 'N/A') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function parseRate(r) {
  const m = /^(\d+)\/(\d+)$/.exec(String(r || ''));
  if (!m || Number(m[2]) === 0) return 0;
  return Number(m[1]) / Number(m[2]);
}

function hasDovi(s) {
  if (s.codec_tag_string === 'dvhe' || s.codec_tag_string === 'dvh1') return true;
  return (s.side_data_list || []).some((d) => /dovi|dolby vision/i.test(d.side_data_type || ''));
}

class ProbeError extends Error {
  constructor(msg, retryable) { super(msg); this.retryable = retryable; }
}

async function ffprobeJson(cfg, src, { signal } = {}) {
  const args = ['-v', 'error', ...inputNetArgs(src, cfg), '-show_format', '-show_streams', '-of', 'json', src];
  const r = await run(cfg.ffprobe, args, { signal, timeoutMs: 5 * 60 * 1000 });
  if (r.code !== 0) {
    const msg = lastLine(r.stderr) || `ffprobe exit ${r.code}`;
    // испорченный файл — повтор не поможет; сеть, 403 по истёкшей подписи и т. п. — поможет
    throw new ProbeError(`ffprobe: ${msg}`, !/Invalid data found|could not find codec|EBML header parsing failed/i.test(msg));
  }
  try { return JSON.parse(r.stdout); } catch { throw new ProbeError('ffprobe: не JSON', true); }
}

/** Raw ffprobe → {duration_ms, format, streams} для POST probe (+ raw для плана кодирования). */
function normalizeProbe(raw) {
  const fmt = raw.format || {};
  const streams = [];
  for (const s of raw.streams || []) {
    const tags = s.tags || {};
    const disp = s.disposition || {};
    const base = { index: s.index, codec_type: s.codec_type, codec_name: s.codec_name || '' };
    if (s.codec_type === 'video') {
      if (disp.attached_pic) continue; // обложка, не видео
      streams.push({
        ...base,
        profile: s.profile || '',
        pix_fmt: s.pix_fmt || '',
        width: s.width || 0,
        height: s.height || 0,
        color_transfer: s.color_transfer || '',
        avg_frame_rate: s.avg_frame_rate || s.r_frame_rate || '',
        bit_rate: numOrNull(s.bit_rate),
        keyframe_interval_max_s: null,
        dovi: hasDovi(s),
      });
    } else if (s.codec_type === 'audio') {
      streams.push({
        ...base,
        channels: s.channels || 0,
        language: tags.language || '',
        title: tags.title || '',
        default: !!disp.default,
      });
    } else if (s.codec_type === 'subtitle') {
      streams.push({
        ...base,
        language: tags.language || '',
        title: tags.title || '',
        forced: !!disp.forced,
      });
    }
  }
  const dur = numOrNull(fmt.duration);
  return {
    duration_ms: dur === null ? 0 : Math.round(dur * 1000),
    format: { format_name: fmt.format_name || '', bit_rate: numOrNull(fmt.bit_rate) },
    streams,
  };
}

function mainVideo(probe) {
  return probe.streams.find((s) => s.codec_type === 'video') || null;
}

/** Условия v1-copy из §5 без замера ключевых кадров — чтобы не качать 10 минут зря. */
function copyCandidate(probe) {
  const v = mainVideo(probe);
  if (!v) return false;
  // v1-copy — только из HEVC (окончательная версия, в архив); H.264 сразу в v1 (§5)
  if (v.codec_name !== 'hevc') return false;
  if (/10|12/.test(v.pix_fmt)) return false;
  if (HDR_TRC.has(v.color_transfer) || v.dovi) return false;
  if (v.height > 1080 || v.width > 1920) return false;
  const br = probe.format.bit_rate;
  if (br === null || br > 8e6) return false;
  return true;
}

function isHdr(v) { return !!v && (HDR_TRC.has(v.color_transfer) || !!v.dovi); }

/**
 * Цвет по первому кадру — когда в заголовке контейнера нет color_transfer (бывает у MKV),
 * а HDR-признаки есть только в VUI/SEI потока. Читает один пакет.
 */
async function firstFrameColor(cfg, src, streamIndex, { signal } = {}) {
  const args = ['-v', 'error', ...inputNetArgs(src, cfg), '-select_streams', String(streamIndex), '-read_intervals', '%+#1',
    '-show_entries', 'frame=color_transfer,color_primaries:frame_side_data=side_data_type', '-of', 'json', src];
  const r = await run(cfg.ffprobe, args, { signal, timeoutMs: 2 * 60 * 1000 });
  if (r.code !== 0) return null;
  try {
    const f = (JSON.parse(r.stdout).frames || [])[0];
    if (!f) return null;
    const dovi = (f.side_data_list || []).some((d) => /dovi|dolby vision/i.test(d.side_data_type || ''));
    return { color_transfer: f.color_transfer || '', dovi };
  } catch { return null; }
}

/**
 * Наибольший интервал между ключевыми кадрами за первые windowSec секунд (по пакетам, без декодирования).
 * Один ключевой кадр в окне → возвращаем длину окна (заведомо > 10 с).
 */
async function keyframeIntervalMax(cfg, src, streamIndex, windowSec, { signal } = {}) {
  const args = ['-v', 'error', ...inputNetArgs(src, cfg), '-select_streams', String(streamIndex),
    '-show_entries', 'packet=pts_time,dts_time,flags', '-read_intervals', `%+${windowSec}`, '-of', 'csv=p=0', src];
  let prev = null;
  let first = null;
  let max = 0;
  let last = null;
  const r = await run(cfg.ffprobe, args, {
    signal,
    timeoutMs: 30 * 60 * 1000,
    onLine(line) {
      const [pts, dts, flags] = line.split(',');
      const t = numOrNull(pts) ?? numOrNull(dts);
      if (t === null) return;
      last = t;
      if (!flags || !flags.includes('K')) return;
      if (first === null) first = t;
      if (prev !== null && t - prev > max) max = t - prev;
      prev = t;
    },
  });
  if (r.code !== 0) throw new ProbeError(`ffprobe keyframes: ${lastLine(r.stderr) || r.code}`, true);
  if (prev === null) return null;
  if (max === 0) max = Math.max(windowSec, (last ?? 0) - first);
  return Math.round(max * 1000) / 1000;
}

module.exports = {
  ffprobeJson, normalizeProbe, mainVideo, copyCandidate, isHdr, keyframeIntervalMax, firstFrameColor, inputNetArgs,
  parseRate, TEXT_SUBS, ProbeError, isUrl,
};

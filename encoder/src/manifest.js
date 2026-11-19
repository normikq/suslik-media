'use strict';

// Проверка результата ffmpeg и manifest.json (как docs/hls-fixtures/manifest.json).

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { parseMediaPlaylist, validateByteRangePlaylist, bitrates, subsPlaylist } = require('./playlist');
const { codecFromFile } = require('./mp4');

const AAC_LC = 'mp4a.40.2';

function readPlaylist(outDir, dir, file) {
  const plPath = path.join(outDir, dir, 'index.m3u8');
  const mediaPath = path.join(outDir, dir, file);
  if (!fs.existsSync(plPath)) throw new Error(`${dir}: нет index.m3u8`);
  if (!fs.existsSync(mediaPath)) throw new Error(`${dir}: нет ${file}`);
  const pl = parseMediaPlaylist(fs.readFileSync(plPath, 'utf8'));
  const errs = validateByteRangePlaylist(pl, { fileSize: fs.statSync(mediaPath).size, file });
  if (errs.length) throw new Error(`${dir}/index.m3u8: ${errs.join('; ')}`);
  return { pl, mediaPath };
}

/** Проверяет плейлисты, пишет плейлисты субтитров и manifest.json. Возвращает {manifest, sha256, bytes}. */
function finalizeOutput({ outDir, plan, job, hlsId, fingerprint, durationMs, encoder, ffmpegVersion, now = Date.now() }) {
  let audioPeak = 0;
  let audioAvg = 0;
  for (const a of plan.audio) {
    const { pl } = readPlaylist(outDir, a.dir, 'audio.m4s');
    const br = bitrates(pl);
    audioPeak = Math.max(audioPeak, br.peak);
    audioAvg = Math.max(audioAvg, br.avg);
  }

  const video = plan.video.map((t) => {
    const { pl, mediaPath } = readPlaylist(outDir, t.dir, 'video.m4s');
    const codec = codecFromFile(mediaPath, pl.map.length);
    if (!codec) throw new Error(`${t.dir}: не найден hvcC/avcC в init-сегменте`);
    const br = bitrates(pl);
    return {
      dir: t.dir,
      codec,
      width: t.width,
      height: t.height,
      // BANDWIDTH в мастере — видео плюс самая «тяжёлая» звуковая дорожка
      bandwidth: br.peak + audioPeak,
      avg_bandwidth: br.avg + audioAvg,
      fps: Math.round(plan.fps * 1000) / 1000,
    };
  });

  for (const s of plan.subs) {
    const vtt = path.join(outDir, s.dir, 'subs.vtt');
    if (!fs.existsSync(vtt)) throw new Error(`${s.dir}: нет subs.vtt`);
    fs.writeFileSync(path.join(outDir, s.dir, 'index.m3u8'), subsPlaylist(durationMs));
  }

  const manifest = {
    hls_id: hlsId,
    profile: plan.profile,
    created_at: Math.floor(now / 1000),
    source: { ad_id: String(job.ad_id ?? ''), path: job.path, size: job.size, fingerprint },
    duration_ms: durationMs,
    video,
    audio: plan.audio.map((a) => ({ dir: a.dir, src_index: a.src_index, codec: AAC_LC, channels: 2, language: a.language, name: a.name, default: a.default })),
    subtitles: plan.subs.map((s) => ({ dir: s.dir, src_index: s.src_index, language: s.language, name: s.name, forced: s.forced })),
    skipped: plan.skipped,
    tonemapped: plan.tonemap,
    encoder: plan.profile === 'v1-copy' ? 'copy' : encoder,
    ffmpeg: ffmpegVersion,
  };
  const text = JSON.stringify(manifest, null, 2) + '\n';
  fs.writeFileSync(path.join(outDir, 'manifest.json'), text);
  return { manifest, sha256: crypto.createHash('sha256').update(text).digest('hex'), bytes: dirBytes(outDir) };
}

function dirBytes(dir) {
  let n = 0;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    n += e.isDirectory() ? dirBytes(p) : fs.statSync(p).size;
  }
  return n;
}

module.exports = { finalizeOutput, dirBytes, readPlaylist };

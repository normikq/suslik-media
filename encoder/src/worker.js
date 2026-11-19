'use strict';

// Главный цикл: GET next → processJob → чистка; один фильм за раз; ошибки цикла не роняют процесс.

const fs = require('node:fs');
const path = require('node:path');
const { run } = require('./proc');
const { processJob, sleep } = require('./job');

const ENCODER_NAME = { nvenc: 'hevc_nvenc', x265: 'libx265', x264: 'libx264' };

/** Версия ffmpeg из `ffmpeg -version` («9.0.2-full_build…» → «9.0.2»; «N-12345-g…» — сборка из git). */
function parseFfmpegVersion(text) {
  const m = /ffmpeg version (\S+)/.exec(text || '');
  if (!m) return null;
  const full = m[1];
  const num = /^n?(\d+)\.(\d+)(?:\.(\d+))?/.exec(full);
  return { full, short: num ? num[0].replace(/^n/, '') : full, major: num ? Number(num[1]) : null, minor: num ? Number(num[2]) : null };
}

function versionOk(v) {
  if (!v || v.major === null) return true; // сборка из git (N-…) — считаем свежей
  return v.major > 6 || (v.major === 6 && v.minor >= 1);
}

/** Проверки при старте: ffmpeg ≥ 6.1, нужный кодировщик и фильтры есть. */
async function checkTools(cfg, log) {
  const ver = await run(cfg.ffmpeg, ['-hide_banner', '-version']).catch((e) => ({ code: -1, stdout: '', stderr: e.message }));
  if (ver.code !== 0) throw new Error(`ffmpeg не запускается (${cfg.ffmpeg}): ${ver.stderr}`);
  const v = parseFfmpegVersion(ver.stdout);
  if (!v) throw new Error('не удалось разобрать версию ffmpeg');
  if (!versionOk(v)) throw new Error(`нужен ffmpeg ≥ 6.1, найден ${v.full}`);
  const enc = await run(cfg.ffmpeg, ['-hide_banner', '-encoders']);
  if (!enc.stdout.includes(ENCODER_NAME[cfg.encoder])) throw new Error(`в ffmpeg нет кодировщика ${ENCODER_NAME[cfg.encoder]}`);
  const flt = await run(cfg.ffmpeg, ['-hide_banner', '-filters']);
  if (!/\bzscale\b/.test(flt.stdout) || !/\btonemap\b/.test(flt.stdout)) log.warn('[start] в ffmpeg нет zscale/tonemap — HDR-исходники кодировать не получится');
  const pr = await run(cfg.ffprobe, ['-hide_banner', '-version']).catch(() => ({ code: -1 }));
  if (pr.code !== 0) throw new Error(`ffprobe не запускается (${cfg.ffprobe})`);
  log.info(`[start] ffmpeg ${v.full}, кодировщик ${ENCODER_NAME[cfg.encoder]}`);
  return v;
}

/** Один воркер на папку: pid-файл; чужой живой pid — выходим. */
function acquireLock(workDir) {
  fs.mkdirSync(workDir, { recursive: true });
  const lock = path.join(workDir, '.lock');
  let pid = 0;
  try { pid = Number(fs.readFileSync(lock, 'utf8')); } catch (e) { if (e.code !== 'ENOENT') throw e; }
  const alive = (p) => { try { process.kill(p, 0); return true; } catch (e) { return e.code === 'EPERM'; } };
  if (pid && pid !== process.pid && alive(pid)) throw new Error(`воркер уже запущен (pid ${pid}, ${lock})`);
  fs.writeFileSync(lock, String(process.pid));
  return () => { try { if (Number(fs.readFileSync(lock, 'utf8')) === process.pid) fs.rmSync(lock); } catch { /* ignore */ } };
}

/** Чистка временных файлов (всё, кроме .lock). */
function cleanWork(workDir) {
  if (!fs.existsSync(workDir)) return;
  for (const e of fs.readdirSync(workDir)) {
    if (e === '.lock') continue;
    fs.rmSync(path.join(workDir, e), { recursive: true, force: true });
  }
}

async function runLoop({ cfg, api, log, signal, ffmpegVersion, keepWork = false, onResult }) {
  cleanWork(cfg.workDir);
  for (;;) {
    if (signal?.aborted) break;
    let job = null;
    try {
      job = await api.next();
    } catch (e) {
      log.warn(`[next] ${e.message}`);
      await sleep(cfg.errorSec * 1000, signal);
      continue;
    }
    if (!job) {
      if (cfg.once) break;
      await sleep(cfg.idleSec * 1000, signal);
      continue;
    }
    let res;
    try {
      res = await processJob(job, { cfg, api, log, signal, ffmpegVersion, keepWork });
    } catch (e) {
      // processJob сам ловит ошибки; сюда — только совсем неожиданное
      log.error('[loop]', e);
      res = { result: 'failed', error: e.message };
      await sleep(cfg.errorSec * 1000, signal);
    } finally {
      if (!keepWork) { try { cleanWork(cfg.workDir); } catch (e) { log.warn(`[clean] ${e.message}`); } }
    }
    onResult?.(res, job);
    if (cfg.once) break;
  }
}

module.exports = { runLoop, checkTools, acquireLock, cleanWork, parseFfmpegVersion, versionOk };

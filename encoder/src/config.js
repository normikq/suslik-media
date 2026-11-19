'use strict';

// Настройки воркера — только из переменных окружения (docs/HLS.md §13).
// Домены и секреты в репозиторий не пишем: значения по умолчанию — плейсхолдеры.

const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');

function num(v, def) {
  if (v === undefined || v === '') return def;
  const n = Number(v);
  return Number.isFinite(n) ? n : def;
}

function bool(v, def) {
  if (v === undefined || v === '') return def;
  return /^(1|true|yes|on)$/i.test(String(v));
}

function loadConfig(env = process.env) {
  const encoder = (env.SM_ENCODER || 'nvenc').toLowerCase();
  if (!['nvenc', 'x265', 'x264'].includes(encoder)) {
    throw new Error(`SM_ENCODER: ожидается nvenc|x265|x264, получено «${env.SM_ENCODER}»`);
  }
  const siteUrl = (env.SM_SITE_URL || '').replace(/\/+$/, '');
  const cfg = {
    siteUrl,                                   // https://site.example.org (с путём, если сервер не в корне)
    token: env.HLS_WORKER_TOKEN || '',
    encoder,
    maxSourceBytes: num(env.SM_MAX_SOURCE_GB, 40) * 1024 ** 3,
    ffmpeg: env.SM_FFMPEG || 'ffmpeg',
    ffprobe: env.SM_FFPROBE || 'ffprobe',
    // аппаратное декодирование исходника (только с nvenc): -hwaccel cuda, кадры возвращаются в память для фильтров
    hwdec: bool(env.SM_HWDEC, encoder === 'nvenc'),
    workDir: path.resolve(env.SM_WORK_DIR || path.join(ROOT, 'work')),
    logDir: path.resolve(env.SM_LOG_DIR || path.join(ROOT, 'logs')),
    logMaxBytes: num(env.SM_LOG_MAX_MB, 10) * 1024 * 1024,
    logKeep: num(env.SM_LOG_KEEP, 5),
    idleSec: num(env.SM_IDLE_S, 30),           // пауза после 204 от /next
    errorSec: num(env.SM_ERROR_S, 60),         // пауза после сбоя связи с сервером
    progressSec: num(env.SM_PROGRESS_S, 10),   // progress продлевает аренду
    keyframeProbeSec: num(env.SM_KEYFRAME_PROBE_S, 600), // окно замера keyframe_interval_max_s
    reconnectDelayMax: num(env.SM_RECONNECT_DELAY_MAX, 30),
    // качество: -cq для nvenc, -crf для x265/x264
    cq: num(env.SM_CQ, encoder === 'nvenc' ? 24 : 23),
    nvencPreset: env.SM_NVENC_PRESET || 'p5',
    swPreset: env.SM_SW_PRESET || 'medium',    // x265/x264; для тестов — ultrafast
    // выгрузка на CDN
    upload: {
      mode: (env.SM_UPLOAD_MODE || 'rsync').toLowerCase(), // rsync | local
      ssh: env.SM_CDN_SSH || '',                 // hls-upload@cdn.example.org
      sshKey: env.SM_CDN_SSH_KEY || '',          // путь к закрытому ключу; пусто — ssh сам ищет
      sshPort: env.SM_CDN_SSH_PORT || '',
      sshBin: env.SM_SSH || 'ssh',
      rsyncBin: env.SM_RSYNC || 'rsync',
      tmpDir: env.SM_CDN_TMP_DIR || '.tmp',      // относительно корня, доступного hls-upload (rrsync)
      // команда, которую ssh выполняет на CDN после rsync; подстановки {tmp} {name} {hls_id} {profile}
      finalizeCmd: env.SM_CDN_FINALIZE_CMD || 'finalize {name} {hls_id} {profile}',
      localDir: env.SM_UPLOAD_LOCAL_DIR ? path.resolve(env.SM_UPLOAD_LOCAL_DIR) : '', // mode=local: «CDN» в папке
    },
    once: bool(env.SM_ONCE, false),              // одно задание и выход (для проверки)
  };
  return cfg;
}

function checkConfig(cfg) {
  const errs = [];
  if (!cfg.siteUrl) errs.push('SM_SITE_URL не задан');
  if (!cfg.token) errs.push('HLS_WORKER_TOKEN не задан');
  if (cfg.upload.mode === 'rsync' && !cfg.upload.ssh) errs.push('SM_CDN_SSH не задан (или SM_UPLOAD_MODE=local)');
  if (cfg.upload.mode === 'local' && !cfg.upload.localDir) errs.push('SM_UPLOAD_LOCAL_DIR не задан');
  if (!['rsync', 'local'].includes(cfg.upload.mode)) errs.push(`SM_UPLOAD_MODE: rsync|local, получено «${cfg.upload.mode}»`);
  return errs;
}

module.exports = { loadConfig, checkConfig, ROOT };

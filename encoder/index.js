#!/usr/bin/env node
'use strict';

// Точка входа воркера-кодировщика HLS. Перезапуск после падения — снаружи (NSSM / systemd), см. README.md.

const { loadConfig, checkConfig } = require('./src/config');
const { createLogger } = require('./src/log');
const { createApi } = require('./src/api');
const { runLoop, checkTools, acquireLock } = require('./src/worker');

async function main() {
  const cfg = loadConfig();
  const log = createLogger({ dir: cfg.logDir, maxBytes: cfg.logMaxBytes, keep: cfg.logKeep });
  const errs = checkConfig(cfg);
  if (errs.length) {
    log.error(`[start] настройки: ${errs.join('; ')}`);
    process.exit(2);
  }
  const release = acquireLock(cfg.workDir);
  const ac = new AbortController();
  const stop = (sig) => {
    if (ac.signal.aborted) return;
    log.info(`[stop] ${sig}: останавливаю текущее задание`);
    ac.abort();
  };
  process.on('SIGINT', () => stop('SIGINT'));
  process.on('SIGTERM', () => stop('SIGTERM'));
  process.on('SIGBREAK', () => stop('SIGBREAK')); // Windows: NSSM шлёт Ctrl+Break
  process.on('exit', release);
  process.on('unhandledRejection', (e) => log.error('[unhandled]', e));

  try {
    const ver = await checkTools(cfg, log);
    log.info(`[start] сервер ${cfg.siteUrl.replace(/^(https?:\/\/[^/]+).*/, '$1')}, upload=${cfg.upload.mode}, лимит ${cfg.maxSourceBytes / 1024 ** 3} ГБ`);
    await runLoop({ cfg, api: createApi(cfg), log, signal: ac.signal, ffmpegVersion: ver.short });
  } catch (e) {
    log.error('[fatal]', e);
    process.exitCode = 1;
  } finally {
    release();
  }
  log.info('[stop] выход');
}

main();

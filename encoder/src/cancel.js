'use strict';

// Отмена задания на сайте (docs/HLS.md §19): свой AbortController на задание.
// Он срабатывает и от остановки воркера (SIGTERM — signal цикла), и от ответа сайта «задание снято»
// (JobCancelled на progress/probe/done/audio-done) — тогда ffmpeg/rsync/ssh задания убиваются сразу.
// Различить причины: ctl.cancelled (снято на сайте) против signal цикла (остановка воркера).

const { JobCancelled } = require('./api');

function createJobControl(workerSignal) {
  const ac = new AbortController();
  const onStop = () => ac.abort();
  if (workerSignal?.aborted) ac.abort();
  else workerSignal?.addEventListener('abort', onStop, { once: true });
  const ctl = {
    signal: ac.signal,
    /** JobCancelled, если сайт снял задание */
    cancelled: null,
    /** Если e — «задание снято»: запомнить, остановить всё задание, true. */
    take(e) {
      if (!(e instanceof JobCancelled)) return false;
      if (!ctl.cancelled) ctl.cancelled = e;
      ac.abort();
      return true;
    },
    /** Бросить JobCancelled, если задание уже снято (между этапами). */
    check() { if (ctl.cancelled) throw ctl.cancelled; },
    dispose() { workerSignal?.removeEventListener('abort', onStop); },
  };
  return ctl;
}

/** Задание снято на сайте (§19): журнал и результат без failed. */
function cancelledResult(job, stage, err, log, extra = {}) {
  log.info(`[job] ${job.job_id} отменено на сайте (этап ${stage}, ${err?.code || 'cancelled'})`);
  return { result: 'cancelled', ...extra, job_id: job.job_id, stage, code: err?.code || 'cancelled' };
}

/** POST failed; если сайт уже снял задание (410/404) — это не ошибка. */
async function reportFailed(api, log, body) {
  try { await api.failed(body); } catch (e) {
    if (e instanceof JobCancelled) log.info(`[failed] ${body.job_id}: задание уже снято на сайте (${e.code})`);
    else log.error(`[failed] не удалось сообщить серверу: ${e.message}`);
  }
}

module.exports = { createJobControl, cancelledResult, reportFailed };

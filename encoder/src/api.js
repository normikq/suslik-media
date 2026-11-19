'use strict';

// Клиент /internal/hls/* основного сервера (docs/HLS.md §10, Bearer HLS_WORKER_TOKEN).

class ApiError extends Error {
  constructor(msg, status) { super(msg); this.status = status; }
}

/**
 * Задание снято на сайте (§19): 410 {code:"cancelled"} или 404 {code:"unknown_job"} на запрос по заданию.
 * Это не сбой связи: воркер бросает задание без failed и сразу берёт следующее.
 */
class JobCancelled extends ApiError {
  constructor(msg, status, code) { super(msg, status); this.code = code; }
}

/** Код отмены из ответа: 410 cancelled / 404 unknown_job, иначе null (404 без кода — просто ошибка, напр. nginx). */
function cancelCode(status, text) {
  if (status !== 410 && status !== 404) return null;
  let code;
  try { code = JSON.parse(text)?.code; } catch { return null; }
  if (status === 410 && code === 'cancelled') return code;
  if (status === 404 && code === 'unknown_job') return code;
  return null;
}

function createApi({ siteUrl, token, timeoutMs = 30000 }) {
  async function call(method, path, body, { job = false } = {}) {
    const res = await fetch(`${siteUrl}/internal/hls/${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (res.status === 204) return null;
    const text = await res.text();
    if (!res.ok) {
      const msg = `${method} ${path}: HTTP ${res.status} ${text.slice(0, 200)}`;
      const code = job ? cancelCode(res.status, text) : null;
      if (code) throw new JobCancelled(msg, res.status, code);
      throw new ApiError(msg, res.status);
    }
    if (!text) return null;
    try { return JSON.parse(text); } catch { throw new ApiError(`${method} ${path}: ответ не JSON`, res.status); }
  }
  const jobCall = (path) => (b) => call('POST', path, b, { job: true });
  return {
    next: () => call('GET', 'next'),
    probe: jobCall('probe'),
    progress: jobCall('progress'),
    done: jobCall('done'),
    audioDone: jobCall('audio-done'),
    failed: jobCall('failed'),
  };
}

module.exports = { createApi, ApiError, JobCancelled, cancelCode };

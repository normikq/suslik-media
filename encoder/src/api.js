'use strict';

// Клиент /internal/hls/* основного сервера (docs/HLS.md §10, Bearer HLS_WORKER_TOKEN).

class ApiError extends Error {
  constructor(msg, status) { super(msg); this.status = status; }
}

function createApi({ siteUrl, token, timeoutMs = 30000 }) {
  async function call(method, path, body) {
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
    if (!res.ok) throw new ApiError(`${method} ${path}: HTTP ${res.status} ${text.slice(0, 200)}`, res.status);
    if (!text) return null;
    try { return JSON.parse(text); } catch { throw new ApiError(`${method} ${path}: ответ не JSON`, res.status); }
  }
  return {
    next: () => call('GET', 'next'),
    probe: (b) => call('POST', 'probe', b),
    progress: (b) => call('POST', 'progress', b),
    done: (b) => call('POST', 'done', b),
    failed: (b) => call('POST', 'failed', b),
  };
}

module.exports = { createApi, ApiError };

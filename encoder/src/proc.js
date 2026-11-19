'use strict';

// Запуск внешних программ (ffmpeg, ffprobe, rsync, ssh) без оболочки.

const { spawn } = require('node:child_process');

/**
 * @returns {Promise<{code:number, stdout:string, stderr:string}>}
 * stdout копится целиком, если не задан onLine (тогда строки отдаются по одной).
 * stderr — только хвост (tailBytes), чтобы не раздувать память на длинном кодировании.
 */
function run(cmd, args, { cwd, env, signal, onLine, tailBytes = 8192, timeoutMs } = {}) {
  return new Promise((resolve, reject) => {
    let child;
    try {
      child = spawn(cmd, args, { cwd, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (e) {
      reject(e);
      return;
    }
    let stdout = '';
    let stderr = '';
    let buf = '';
    let timer = null;
    const kill = () => { try { child.kill('SIGKILL'); } catch { /* ignore */ } };
    if (signal) {
      if (signal.aborted) kill();
      else signal.addEventListener('abort', kill, { once: true });
    }
    if (timeoutMs) timer = setTimeout(kill, timeoutMs);
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (d) => {
      if (!onLine) { stdout += d; return; }
      buf += d;
      let i;
      // конец строки — \n или \r: rsync --info=progress2 перерисовывает строку прогресса возвратом каретки
      while ((i = buf.search(/[\r\n]/)) >= 0) {
        const line = buf.slice(0, i);
        buf = buf.slice(i + 1);
        if (!line) continue;
        try { onLine(line); } catch { /* ignore */ }
      }
    });
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (d) => {
      stderr += d;
      if (stderr.length > tailBytes * 2) stderr = stderr.slice(-tailBytes);
    });
    child.on('error', (e) => {
      if (timer) clearTimeout(timer);
      signal?.removeEventListener('abort', kill);
      reject(e);
    });
    child.on('close', (code, sig) => {
      if (timer) clearTimeout(timer);
      signal?.removeEventListener('abort', kill);
      if (onLine && buf) { try { onLine(buf); } catch { /* ignore */ } }
      resolve({ code: code === null ? (sig ? 128 : 1) : code, stdout, stderr: stderr.slice(-tailBytes) });
    });
  });
}

/** Последняя содержательная строка stderr — для поля error в failed. */
function lastLine(stderr) {
  const lines = String(stderr || '').split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
  return lines.length ? lines[lines.length - 1].slice(0, 300) : '';
}

module.exports = { run, lastLine };

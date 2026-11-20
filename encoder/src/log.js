'use strict';

// Журнал в файл с ротацией по размеру: encoder.log → encoder.log.1 … .N.
// Пишет синхронно (строк мало, зато ничего не теряется при падении).

const fs = require('node:fs');
const path = require('node:path');

function createLogger({ dir, maxBytes = 10 * 1024 * 1024, keep = 5, name = 'encoder.log', console: toConsole = true } = {}) {
  let file = null;
  let size = 0;
  if (dir) {
    fs.mkdirSync(dir, { recursive: true });
    file = path.join(dir, name);
    try { size = fs.statSync(file).size; } catch { size = 0; }
  }

  function rotate() {
    for (let i = keep - 1; i >= 1; i--) {
      const from = `${file}.${i}`;
      if (fs.existsSync(from)) {
        try { fs.renameSync(from, `${file}.${i + 1}`); } catch { /* занят — пропускаем */ }
      }
    }
    try { fs.renameSync(file, `${file}.1`); } catch { /* нет файла */ }
    try { fs.rmSync(`${file}.${keep + 1}`, { force: true }); } catch { /* ignore */ }
    size = 0;
  }

  function write(level, args) {
    const msg = args.map((a) => (a instanceof Error ? (a.stack || a.message) : typeof a === 'string' ? a : JSON.stringify(a))).join(' ');
    const line = `${new Date().toISOString()} ${level} ${msg}\n`;
    if (toConsole) (level === 'ERR' ? process.stderr : process.stdout).write(line);
    if (!file) return;
    try {
      if (size + line.length > maxBytes) rotate();
      fs.appendFileSync(file, line);
      size += Buffer.byteLength(line);
    } catch { /* журнал не должен ронять воркер */ }
  }

  return {
    info: (...a) => write('INF', a),
    warn: (...a) => write('WRN', a),
    error: (...a) => write('ERR', a),
    file,
  };
}

const silent = { info() {}, warn() {}, error() {}, file: null };

module.exports = { createLogger, silent };

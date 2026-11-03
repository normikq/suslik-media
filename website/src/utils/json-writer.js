"use strict";

const fs = require("fs");
const path = require("path");

const pendingWriters = [];
for (const sig of ["SIGINT", "SIGTERM"]) {
  process.on(sig, () => {
    for (const w of pendingWriters) w.flush();
    process.exit(0);
  });
}

// Windows: замену файла ненадолго не даёт антивирус, индексатор или чужое чтение (EPERM / EBUSY / EACCES).
// Повторяем несколько раз с растущей паузой, потом сдаёмся (запись повторится при следующем изменении).
const RETRY_CODES = new Set(["EPERM", "EBUSY", "EACCES"]);
const RETRY_MS = [20, 50, 100, 200];

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function sleepSync(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

async function renameRetry(from, to) {
  for (let i = 0; ; i++) {
    try {
      return await fs.promises.rename(from, to);
    } catch (err) {
      if (i >= RETRY_MS.length || !RETRY_CODES.has(err.code)) throw err;
      await sleep(RETRY_MS[i]);
    }
  }
}

function renameRetrySync(from, to) {
  for (let i = 0; ; i++) {
    try {
      return fs.renameSync(from, to);
    } catch (err) {
      if (i >= RETRY_MS.length || !RETRY_CODES.has(err.code)) throw err;
      sleepSync(RETRY_MS[i]);
    }
  }
}

let tmpSeq = 0;

// Debounced atomic JSON writer shared by the small file-backed caches.
// Запись на диск асинхронная (не держит поток Node на время записи большого кеша); одновременно идёт одна запись
// на файл, изменения за время записи сохраняются следующей. При остановке (SIGINT/SIGTERM) несохранённое
// дописывается синхронно.
function makeJsonWriter(file, collect, delayMs = 2000) {
  let timer = null;
  let dueAt = 0;
  let writing = false; // идёт асинхронная запись
  let again = false; // за время записи были новые изменения

  const fail = err => console.error(`Не удалось сохранить ${path.basename(file)}:`, err.message);
  // У каждой записи свой временный файл: синхронная запись при остановке не столкнётся с асинхронной
  const tmpName = () => `${file}.${process.pid}.${++tmpSeq}.tmp`;

  const write = () => {
    timer = null;
    if (writing) {
      again = true;
      return;
    }
    let data;
    try {
      data = JSON.stringify(collect());
    } catch (err) {
      return fail(err);
    }
    writing = true;
    const tmp = tmpName();
    (async () => {
      try {
        await fs.promises.writeFile(tmp, data, { mode: 0o600 });
        await renameRetry(tmp, file);
      } catch (err) {
        fail(err);
        fs.promises.unlink(tmp).catch(() => {});
      }
    })().finally(() => {
      writing = false;
      if (again) {
        again = false;
        write();
      }
    });
  };

  const writeSync = () => {
    const tmp = tmpName();
    try {
      fs.writeFileSync(tmp, JSON.stringify(collect()), { mode: 0o600 });
      renameRetrySync(tmp, file);
    } catch (err) {
      fail(err);
      try { fs.unlinkSync(tmp); } catch {}
    }
  };

  const schedule = (ms = delayMs) => {
    const due = Date.now() + ms;
    // Уже ждёт запись, но позже, чем просят сейчас (touch на 30 с, потом новая сессия на 2 с): ближе — приоритетнее
    if (timer && due >= dueAt) return;
    if (timer) clearTimeout(timer);
    dueAt = due;
    timer = setTimeout(write, ms);
    timer.unref?.();
  };
  // Синхронно, сейчас: при остановке процесса асинхронная запись не успеет
  schedule.flush = () => {
    if (!timer && !writing && !again) return;
    if (timer) clearTimeout(timer);
    timer = null;
    again = false;
    writeSync();
  };
  pendingWriters.push(schedule);
  return schedule;
}

module.exports = {
  makeJsonWriter,
  pendingWriters
};

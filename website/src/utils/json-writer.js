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

// Debounced atomic JSON writer shared by the small file-backed caches.
function makeJsonWriter(file, collect, delayMs = 2000) {
  let timer = null;
  let dueAt = 0;
  const write = () => {
    timer = null;
    try {
      const tmp = file + ".tmp";
      fs.writeFileSync(tmp, JSON.stringify(collect()), { mode: 0o600 });
      fs.renameSync(tmp, file);
    } catch (err) {
      console.error(`Не удалось сохранить ${path.basename(file)}:`, err.message);
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
  schedule.flush = () => {
    if (timer) {
      clearTimeout(timer);
      write();
    }
  };
  pendingWriters.push(schedule);
  return schedule;
}

module.exports = {
  makeJsonWriter,
  pendingWriters
};

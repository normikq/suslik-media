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
    if (!timer) {
      timer = setTimeout(write, ms);
      timer.unref?.();
    }
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

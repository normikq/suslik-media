"use strict";

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const config = require("../config");

const UPDATE_KINDS = { android: /\.apk$/i, desktop: /\.(msi|exe)$/i };
const isUpdatePlatform = p => Object.prototype.hasOwnProperty.call(UPDATE_KINDS, p);

// A file copied in by hand (scp) is still growing for a while: it is not offered (and older versions are not removed)
// until it has not changed for a minute. Files that came through /api/app/upload or promote are complete at once.
const UPDATE_SETTLE_MS = 60_000;
const completeUpdates = new Set();

// SHA-256 of an update file, by path + size + mtime (the PC app checks the MSI with it before running msiexec).
const updateHashes = new Map();

function updateSha256(full, size, mtimeMs) {
  const key = `${size}:${mtimeMs}`;
  const known = updateHashes.get(full);
  if (known && known.key === key) return known.hash;
  const hash = new Promise(resolve => {
    const h = crypto.createHash("sha256");
    fs.createReadStream(full)
      .on("data", c => h.update(c))
      .on("end", () => resolve(h.digest("hex")))
      .on("error", () => resolve(""));
  });
  updateHashes.set(full, { key, hash });
  if (updateHashes.size > 20) updateHashes.delete(updateHashes.keys().next().value);
  return hash;
}

// «2.0.0» или «2.0.0.1» (четвёртое число — промежуточные беты между релизами).
function versionOf(name) {
  const m = /(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?/.exec(name);
  return m ? m.slice(1).filter(x => x !== undefined).map(Number) : null;
}

function cmpVersion(a, b) {
  for (let i = 0; i < 4; i++) {
    if ((a[i] || 0) !== (b[i] || 0)) return (a[i] || 0) - (b[i] || 0);
  }
  return 0;
}

const vparts = v => String(v).split(".").map(n => parseInt(n, 10) || 0);

function latestUpdate(platform, dir = config.UPDATES_DIR) {
  if (!isUpdatePlatform(platform)) return null;
  const re = UPDATE_KINDS[platform];
  let best = null;
  const now = Date.now();
  let names;
  try {
    names = fs.readdirSync(dir);
  } catch {
    return null;
  }
  for (const name of names) {
    if (!re.test(name)) continue;
    const v = versionOf(name);
    if (!v) continue;
    const full = path.join(dir, name);
    let st;
    try {
      st = fs.statSync(full);
    } catch {
      continue;
    }
    if (!st.isFile() || st.size === 0) continue;
    if (now - st.mtimeMs < UPDATE_SETTLE_MS && !completeUpdates.has(full)) continue; // still being copied
    // при одинаковой версии msi предпочтительнее exe
    if (!best || cmpVersion(v, best.v) > 0 || (cmpVersion(v, best.v) === 0 && /\.msi$/i.test(name))) {
      best = { v, name, full, size: st.size, at: st.mtimeMs };
    }
  }
  if (!best) return null;
  pruneOldUpdates(platform, best, dir);
  let notes = "";
  try {
    notes = fs.readFileSync(best.full.replace(/\.[^.]+$/, ".txt"), "utf8").slice(0, 4000).trim();
  } catch {}
  return { ...best, version: best.v.join("."), notes };
}

// Остаётся только самая новая версия каждой платформы: старые .apk / .msi / .exe (и их .txt) удаляются —
// сразу после загрузки новой и при первом же запросе после того, как файл подложили вручную (scp).
function pruneOldUpdates(platform, best, dir = config.UPDATES_DIR) {
  const re = UPDATE_KINDS[platform];
  let names;
  try {
    names = fs.readdirSync(dir);
  } catch {
    return;
  }
  for (const name of names) {
    if (!re.test(name) || name === best.name) continue;
    const v = versionOf(name);
    // та же версия в другом формате (exe рядом с msi) тоже лишняя
    if (v && cmpVersion(v, best.v) > 0) continue;
    const full = path.join(dir, name);
    fs.rm(full, { force: true }, err => {
      if (!err) console.log(`[updates] удалена старая версия: ${name}`);
    });
    const notes = full.replace(/\.[^.]+$/, ".txt");
    if (notes !== best.full.replace(/\.[^.]+$/, ".txt")) {
      fs.rm(notes, { force: true }, () => {});
    }
  }
}

function clearBetas(platform) {
  const re = UPDATE_KINDS[platform];
  let names;
  try {
    names = fs.readdirSync(config.BETA_DIR);
  } catch {
    return;
  }
  for (const name of names) {
    if (!re.test(name)) continue;
    const full = path.join(config.BETA_DIR, name);
    fs.rm(full, { force: true }, err => {
      if (!err) console.log(`[updates] бета ${name} убрана: вышел релиз`);
    });
    fs.rm(full.replace(/\.[^.]+$/, ".txt"), { force: true }, () => {});
  }
}

// ---- Changelog ---------------------------------------------------------------------------------
const changelogCache = {};

function cleanReleases(list) {
  return (Array.isArray(list) ? list : [])
    .filter(r => r && typeof r.version === "string" && /^\d+(\.\d+){0,3}$/.test(r.version) && Array.isArray(r.items))
    .map(r => ({
      version: r.version,
      title: String(r.title || "").slice(0, 120),
      items: r.items.map(x => String(x).slice(0, 400)).filter(Boolean).slice(0, 12)
    }))
    .filter(r => r.items.length)
    .filter((r, i, all) => all.findIndex(x => x.version === r.version) === i) // одна версия — одна точка
    .sort((a, b) => cmpVersion(vparts(b.version), vparts(a.version)))
    .slice(0, 50);
}

function readChangelog(platform) {
  const file = path.join(config.CHANGELOG_DIR, `${platform}.json`);
  try {
    const st = fs.statSync(file);
    const c = changelogCache[platform];
    if (c && c.mtime === st.mtimeMs) return c.list;
    const list = cleanReleases(JSON.parse(fs.readFileSync(file, "utf8")));
    changelogCache[platform] = { mtime: st.mtimeMs, list };
    return list;
  } catch (err) {
    if (err.code !== "ENOENT") console.warn(`[changelog] ${file}: ${err.message}`);
    return [];
  }
}

function saveChangelog(platform, list) {
  const file = path.join(config.CHANGELOG_DIR, `${platform}.json`);
  const tmp = file + ".tmp";
  fs.writeFileSync(tmp, JSON.stringify(list, null, 2));
  fs.renameSync(tmp, file);
}

// ---- Reports -----------------------------------------------------------------------------------
function saveCrashReport({ platform, version, kind, known, device, ua }, text) {
  const name = `${Date.now()}-${platform}-${version}-${kind}-${crypto.randomBytes(3).toString("hex")}.txt`;
  fs.writeFileSync(
    path.join(config.REPORTS_DIR, name),
    `platform=${platform} version=${version} kind=${kind} device=${String(device).slice(0, 8)}${known ? "" : " anon"} ` +
      `ua="${String(ua || "").slice(0, 120)}"\n\n${text}`
  );
  const all = fs.readdirSync(config.REPORTS_DIR).filter(n => n.endsWith(".txt")).sort();
  for (const old of all.slice(0, Math.max(0, all.length - 300))) {
    fs.rm(path.join(config.REPORTS_DIR, old), { force: true }, () => {});
  }
}

function listCrashReports(limit = 10, tail = 6000) {
  let names = [];
  try {
    names = fs.readdirSync(config.REPORTS_DIR).filter(n => n.endsWith(".txt")).sort().reverse();
  } catch {}
  return {
    total: names.length,
    reports: names.slice(0, limit).map(n => {
      let text = "";
      try {
        text = fs.readFileSync(path.join(config.REPORTS_DIR, n), "utf8");
      } catch {}
      return {
        name: n,
        at: Number(n.split("-")[0]) || 0,
        head: text.split("\n")[0],
        text: text.length > tail ? "…" + text.slice(-tail) : text
      };
    })
  };
}

module.exports = {
  UPDATE_KINDS,
  isUpdatePlatform,
  completeUpdates,
  updateSha256,
  versionOf,
  cmpVersion,
  vparts,
  latestUpdate,
  pruneOldUpdates,
  clearBetas,
  cleanReleases,
  readChangelog,
  saveChangelog,
  saveCrashReport,
  listCrashReports
};

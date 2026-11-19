'use strict';

// Выгрузка профиля на CDN: rsync в <tmpDir>/<hls_id>.<profile>.<rand>/, затем ssh-команда finalize,
// которая на CDN атомарно переносит папку в /srv/hls/<hls_id>/<profile>/ (сторона CDN — T1, infra/cdn/).
// Режим local — то же самое в локальную папку (проверка без CDN и без rsync).

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { run, lastLine } = require('./proc');

function sshOpts(u) {
  const o = ['-o', 'BatchMode=yes', '-o', 'ConnectTimeout=20', '-o', 'ServerAliveInterval=30'];
  if (u.sshKey) o.push('-i', u.sshKey);
  if (u.sshPort) o.push('-p', String(u.sshPort));
  return o;
}

const q = (s) => (/[\s"]/.test(s) ? `"${s.replace(/"/g, '\\"')}"` : s);

function fill(tpl, vars) {
  return tpl.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? vars[k] : m));
}

function tmpName(hlsId, profile) {
  return `${hlsId}.${profile}.${crypto.randomBytes(4).toString('hex')}`;
}

async function uploadRsync(cfg, srcDir, hlsId, profile, { signal, log }) {
  const u = cfg.upload;
  const name = tmpName(hlsId, profile);
  const tmp = `${u.tmpDir.replace(/\/+$/, '')}/${name}`;
  const e = [u.sshBin, ...sshOpts(u)].map(q).join(' ');
  // cwd = папка профиля, источник «./»: без букв дисков (cwRsync/MSYS иначе принимают «C:» за хост)
  const args = ['-a', '--partial', '--chmod=D755,F644', '-e', e, './', `${u.ssh}:${tmp}/`];
  log?.info(`[upload] rsync → ${u.ssh}:${tmp}/`);
  const r = await run(u.rsyncBin, args, { cwd: srcDir, signal });
  if (r.code !== 0) throw new Error(`rsync exit ${r.code}: ${lastLine(r.stderr)}`);
  const cmd = fill(u.finalizeCmd, { tmp, name, hls_id: hlsId, profile });
  log?.info(`[upload] ssh ${u.ssh} ${cmd}`);
  const f = await run(u.sshBin, [...sshOpts(u), u.ssh, cmd], { signal, timeoutMs: 10 * 60 * 1000 });
  if (f.code !== 0) throw new Error(`finalize exit ${f.code}: ${lastLine(f.stderr)}`);
  return { tmp };
}

/** Эмуляция CDN в папке: <localDir>/.tmp/<name>/ → <localDir>/<hls_id>/<profile>/. */
async function uploadLocal(cfg, srcDir, hlsId, profile, { log }) {
  const root = cfg.upload.localDir;
  const name = tmpName(hlsId, profile);
  const tmp = path.join(root, cfg.upload.tmpDir, name);
  fs.mkdirSync(path.dirname(tmp), { recursive: true });
  fs.cpSync(srcDir, tmp, { recursive: true });
  const target = path.join(root, hlsId, profile);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  if (fs.existsSync(target)) {
    const old = `${tmp}.old`;
    fs.renameSync(target, old);
    fs.renameSync(tmp, target);
    fs.rmSync(old, { recursive: true, force: true });
  } else {
    fs.renameSync(tmp, target);
  }
  log?.info(`[upload] local → ${target}`);
  return { tmp: name, target };
}

function upload(cfg, srcDir, hlsId, profile, opts = {}) {
  if (cfg.upload.mode === 'local') return uploadLocal(cfg, srcDir, hlsId, profile, opts);
  return uploadRsync(cfg, srcDir, hlsId, profile, opts);
}

module.exports = { upload, fill, tmpName };

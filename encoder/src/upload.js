'use strict';

// Выгрузка профиля на CDN: rsync в <tmpDir>/<hls_id>.<profile>.<rand>/, затем ssh-команда finalize,
// которая на CDN атомарно переносит папку в /srv/hls/<hls_id>/<profile>/ (сторона CDN — T1, infra/cdn/).
// Задание «звук» (§18) — то же для папок xN/: <hls_id>.<profile>.a<rand>/, затем finalize-audio.
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

/** Временная папка задания «звук»: <hls_id>.<profile>.a<rand> (§18). */
function audioTmpName(hlsId, profile) {
  return `${hlsId}.${profile}.a${crypto.randomBytes(4).toString('hex')}`;
}

/**
 * Путь назначения rsync. Он считается от корня rrsync у hls-upload (на CDN это уже /srv/hls/.tmp/),
 * поэтому по умолчанию — просто имя папки; «.tmp/<имя>» дал бы /srv/hls/.tmp/.tmp/<имя> и rsync exit 11.
 */
function rsyncTarget(tmpDir, name) {
  const base = String(tmpDir || '').replace(/^(\.\/)+|\/+$/g, '');
  return base && base !== '.' ? `${base}/${name}` : name;
}

/** rsync содержимого srcDir в <name>/ и ssh-команда cmdTpl; ответ CDN — «ok <hls_id>/<profile> <байт>». */
async function rsyncFinalize(cfg, srcDir, name, cmdTpl, hlsId, profile, { signal, log }) {
  const u = cfg.upload;
  const tmp = rsyncTarget(u.tmpDir, name);
  const e = [u.sshBin, ...sshOpts(u)].map(q).join(' ');
  // cwd = папка профиля, источник «./»: без букв дисков (cwRsync/MSYS иначе принимают «C:» за хост)
  const args = ['-a', '--partial', '--chmod=D755,F644', '-e', e, './', `${u.ssh}:${tmp}/`];
  log?.info(`[upload] rsync → ${u.ssh}:${tmp}/`);
  const r = await run(u.rsyncBin, args, { cwd: srcDir, signal });
  if (r.code !== 0) throw new Error(`rsync exit ${r.code}: ${lastLine(r.stderr)}`);
  const cmd = fill(cmdTpl, { tmp, name, hls_id: hlsId, profile });
  log?.info(`[upload] ssh ${u.ssh} ${cmd}`);
  const f = await run(u.sshBin, [...sshOpts(u), u.ssh, cmd], { signal, timeoutMs: 10 * 60 * 1000 });
  if (f.code !== 0) throw new Error(`finalize exit ${f.code}: ${lastLine(f.stderr)}`);
  // CDN (infra/cdn/bin/hls-finalize) отвечает «ok <hls_id>/<profile> <байт>»
  const answer = lastLine(f.stdout);
  if (!checkAnswer(answer, hlsId, profile)) throw new Error(`finalize: неожиданный ответ «${answer}»`);
  return { tmp };
}

/** «ok <hls_id>/<profile>» целым словом (не префикс другого профиля), дальше — байты. */
function checkAnswer(answer, hlsId, profile) {
  const head = `ok ${hlsId}/${profile}`;
  return answer === head || answer.startsWith(`${head} `);
}

function uploadRsync(cfg, srcDir, hlsId, profile, opts) {
  return rsyncFinalize(cfg, srcDir, tmpName(hlsId, profile), cfg.upload.finalizeCmd, hlsId, profile, opts);
}

/**
 * Задание «звук» (§18): в <hls_id>.<profile>.a<rand>/ — только папки xN/, затем finalize-audio <имя>.
 * srcDir — папка профиля в work, в ней ничего, кроме dirs, быть не должно.
 */
function uploadAudioRsync(cfg, srcDir, hlsId, profile, opts) {
  return rsyncFinalize(cfg, srcDir, audioTmpName(hlsId, profile), cfg.upload.finalizeAudioCmd, hlsId, profile, opts);
}

/** Эмуляция CDN в папке: <localDir>/.tmp/<name>/ → <localDir>/<hls_id>/<profile>/. */
async function uploadLocal(cfg, srcDir, hlsId, profile, { log }) {
  const root = cfg.upload.localDir;
  const name = tmpName(hlsId, profile);
  const tmp = path.join(root, '.tmp', name);
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

/** Эмуляция finalize-audio: каждая <localDir>/.tmp/<name>/xN/ → <localDir>/<hls_id>/<profile>/xN/ с заменой. */
async function uploadAudioLocal(cfg, srcDir, hlsId, profile, dirs, { log }) {
  const root = cfg.upload.localDir;
  const name = audioTmpName(hlsId, profile);
  const tmp = path.join(root, '.tmp', name);
  fs.mkdirSync(tmp, { recursive: true });
  for (const d of dirs) fs.cpSync(path.join(srcDir, d), path.join(tmp, d), { recursive: true });
  const profDir = path.join(root, hlsId, profile);
  fs.mkdirSync(profDir, { recursive: true });
  for (const d of dirs) {
    const target = path.join(profDir, d);
    if (fs.existsSync(target)) {
      const old = path.join(tmp, `${d}.old`);
      fs.renameSync(target, old);
      fs.renameSync(path.join(tmp, d), target);
      fs.rmSync(old, { recursive: true, force: true });
    } else {
      fs.renameSync(path.join(tmp, d), target);
    }
  }
  fs.rmSync(tmp, { recursive: true, force: true });
  log?.info(`[upload] local → ${profDir} ${dirs.join(' ')}`);
  return { tmp: name, target: profDir };
}

function upload(cfg, srcDir, hlsId, profile, opts = {}) {
  if (cfg.upload.mode === 'local') return uploadLocal(cfg, srcDir, hlsId, profile, opts);
  return uploadRsync(cfg, srcDir, hlsId, profile, opts);
}

/** Выгрузка папок xN/ задания «звук»; dirs — список xN, кроме них в srcDir ничего нет. */
function uploadAudio(cfg, srcDir, hlsId, profile, dirs, opts = {}) {
  const extra = fs.readdirSync(srcDir).filter((e) => !dirs.includes(e));
  if (extra.length) throw new Error(`в папке выгрузки лишнее: ${extra.join(', ')}`);
  if (cfg.upload.mode === 'local') return uploadAudioLocal(cfg, srcDir, hlsId, profile, dirs, opts);
  return uploadAudioRsync(cfg, srcDir, hlsId, profile, opts);
}

module.exports = { upload, uploadAudio, fill, tmpName, audioTmpName, rsyncTarget, checkAnswer };

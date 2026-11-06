"use strict";

// Файлы «рядом с видео» в раздаче: внешние озвучки и субтитры, которые относятся к выбранному файлу.
// Правило (то же, что на клиентах): сначала файлы с тем же именем без расширения; если таких нет —
// при единственном видео в раздаче берём все, иначе только лежащие в папке видео или в её подпапке
// с нужным префиксом ("audio…" / "sub…").

const AUDIO_EXT = [".mka", ".ac3", ".dts", ".dtshd", ".eac3", ".flac", ".wav", ".aac", ".m4a", ".mp3", ".opus"];
const SUB_EXT = [".srt", ".vtt", ".ass", ".ssa", ".sub"];
const VIDEO_RE = /\.(mkv|mp4|avi|mov|webm|m4v|m2ts|ts|iso)$/i;
const SAMPLE_RE = /(^|[\/._ -])sample([\/._ -]|$)/i;
const MAX_SIDECARS = 8;

const norm = p => (typeof p === "string" ? p.replace(/\\/g, "/").replace(/^\/+|\/+$/g, "") : "");
const baseName = p => { const s = norm(p); const i = s.lastIndexOf("/"); return i < 0 ? s : s.slice(i + 1); };
const dirName = p => { const s = norm(p); const i = s.lastIndexOf("/"); return i < 0 ? "" : s.slice(0, i); };
const stripExt = n => { const i = n.lastIndexOf("."); return i < 0 ? n : n.slice(0, i); };
const isLetterOrDigit = c => /\d/.test(c) || c.toLowerCase() !== c.toUpperCase();
const sameBase = (name, base) => name.startsWith(base) && (name.length === base.length || !isLetterOrDigit(name[base.length]));

const isFile = it => Boolean(it && it.type === "file" && typeof it.path === "string");

/** Файл варианта в списке раздачи: точный путь, затем без учёта регистра, затем «хвост» пути или имя файла. */
function findFile(list, filePath) {
  const target = norm(filePath);
  if (!target) return null;
  const lower = target.toLowerCase();
  const files = list.filter(isFile);
  const exact = files.find(it => norm(it.path).toLowerCase() === lower);
  if (exact) return exact;
  const tail = files.find(it => norm(it.path).toLowerCase().endsWith("/" + lower));
  if (tail) return tail;
  if (!/[\\/]/.test(filePath)) {
    return files.find(it => typeof it.name === "string" && it.name.toLowerCase() === filePath.toLowerCase()) || null;
  }
  return null;
}

/** Файлы заданного вида (по расширениям), относящиеся к видео videoPath. */
function pickSidecars(list, videoPath, exts, folderPrefix) {
  const all = list.filter(it => isFile(it) && it.sourceLink && exts.some(ext => it.path.toLowerCase().endsWith(ext)));
  if (!all.length) return [];
  const vPath = norm(videoPath);
  const vDir = dirName(vPath);
  const vBase = stripExt(baseName(vPath)).toLowerCase();
  const byName = all.filter(s => sameBase(stripExt(baseName(s.path)).toLowerCase(), vBase));
  if (byName.length) return byName.slice(0, MAX_SIDECARS);
  const videos = list.filter(it => isFile(it) && VIDEO_RE.test(it.path) && !SAMPLE_RE.test(it.path)).length;
  if (videos <= 1) return all.slice(0, MAX_SIDECARS);
  const prefix = vDir ? vDir + "/" : "";
  return all.filter(s => {
    const d = dirName(s.path);
    return d === vDir || (d.startsWith(prefix) && baseName(d).toLowerCase().startsWith(folderPrefix));
  }).slice(0, MAX_SIDECARS);
}

const pickAudio = (list, videoPath) => pickSidecars(list, videoPath, AUDIO_EXT, "audio");
const pickSubtitles = (list, videoPath) => pickSidecars(list, videoPath, SUB_EXT, "sub");

module.exports = { findFile, pickAudio, pickSubtitles, norm };

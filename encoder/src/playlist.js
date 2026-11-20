'use strict';

// Разбор и проверка медиаплейлистов HLS (fMP4, single_file, EXT-X-BYTERANGE), тривиальный плейлист субтитров.

function parseAttrs(s) {
  const out = {};
  const re = /([A-Z0-9-]+)=("[^"]*"|[^,]*)/g;
  let m;
  while ((m = re.exec(s))) out[m[1]] = m[2].startsWith('"') ? m[2].slice(1, -1) : m[2];
  return out;
}

function parseByteRange(s) {
  const m = /^(\d+)(?:@(\d+))?$/.exec(String(s).trim());
  if (!m) return null;
  return { length: Number(m[1]), offset: m[2] === undefined ? null : Number(m[2]) };
}

/** Медиаплейлист → {version, targetDuration, playlistType, independent, endList, map, segments[]}. */
function parseMediaPlaylist(text) {
  const lines = String(text).split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  if (lines[0] !== '#EXTM3U') throw new Error('нет #EXTM3U');
  const pl = { version: null, targetDuration: null, mediaSequence: 0, playlistType: null, independent: false, endList: false, map: null, segments: [] };
  let pending = null;
  let lastEnd = null; // для BYTERANGE без @offset
  for (const line of lines.slice(1)) {
    if (line.startsWith('#EXT-X-VERSION:')) pl.version = Number(line.slice(15));
    else if (line.startsWith('#EXT-X-TARGETDURATION:')) pl.targetDuration = Number(line.slice(22));
    else if (line.startsWith('#EXT-X-MEDIA-SEQUENCE:')) pl.mediaSequence = Number(line.slice(22));
    else if (line.startsWith('#EXT-X-PLAYLIST-TYPE:')) pl.playlistType = line.slice(21);
    else if (line === '#EXT-X-INDEPENDENT-SEGMENTS') pl.independent = true;
    else if (line === '#EXT-X-ENDLIST') pl.endList = true;
    else if (line.startsWith('#EXT-X-MAP:')) {
      const a = parseAttrs(line.slice(11));
      const br = a.BYTERANGE ? parseByteRange(a.BYTERANGE) : null;
      pl.map = { uri: a.URI, length: br ? br.length : null, offset: br ? (br.offset ?? 0) : null };
    } else if (line.startsWith('#EXTINF:')) {
      pending = pending || {};
      pending.duration = parseFloat(line.slice(8));
    } else if (line.startsWith('#EXT-X-BYTERANGE:')) {
      pending = pending || {};
      const br = parseByteRange(line.slice(17));
      if (!br) throw new Error(`плохой EXT-X-BYTERANGE: ${line}`);
      pending.length = br.length;
      pending.offset = br.offset ?? lastEnd;
    } else if (!line.startsWith('#')) {
      if (!pending || pending.duration === undefined) throw new Error(`сегмент без EXTINF: ${line}`);
      const seg = { duration: pending.duration, uri: line, length: pending.length ?? null, offset: pending.offset ?? null };
      if (seg.length !== null) lastEnd = seg.offset + seg.length;
      pl.segments.push(seg);
      pending = null;
    }
  }
  return pl;
}

/**
 * Проверка плейлиста fMP4 single_file: EXT-X-MAP с диапазоном, у каждого сегмента EXT-X-BYTERANGE,
 * смещения идут без дыр от конца init, все ссылки на один файл, последний байт = размер файла.
 * @returns {string[]} ошибки (пусто — всё хорошо)
 */
function validateByteRangePlaylist(pl, { fileSize = null, file = null } = {}) {
  const errs = [];
  if (!pl.map) errs.push('нет EXT-X-MAP');
  else if (pl.map.length === null) errs.push('EXT-X-MAP без BYTERANGE');
  if (!pl.endList) errs.push('нет EXT-X-ENDLIST');
  if (pl.playlistType !== 'VOD') errs.push('не VOD');
  if (!pl.segments.length) errs.push('нет сегментов');
  const uri = file || pl.map?.uri;
  let pos = pl.map && pl.map.length !== null ? pl.map.offset + pl.map.length : null;
  if (pl.map && pl.map.offset !== 0) errs.push(`EXT-X-MAP начинается не с 0: ${pl.map.offset}`);
  pl.segments.forEach((s, i) => {
    if (s.length === null) { errs.push(`сегмент ${i}: нет EXT-X-BYTERANGE`); return; }
    if (uri && s.uri !== uri) errs.push(`сегмент ${i}: файл ${s.uri}, ожидался ${uri}`);
    if (pos !== null && s.offset !== pos) errs.push(`сегмент ${i}: смещение ${s.offset}, ожидалось ${pos}`);
    if (!(s.duration > 0)) errs.push(`сегмент ${i}: длительность ${s.duration}`);
    if (pl.targetDuration !== null && Math.round(s.duration) > pl.targetDuration) errs.push(`сегмент ${i}: ${s.duration} > TARGETDURATION ${pl.targetDuration}`);
    pos = s.offset + s.length;
  });
  if (fileSize !== null && pos !== null && pos !== fileSize) errs.push(`конец последнего сегмента ${pos} ≠ размер файла ${fileSize}`);
  return errs;
}

function totalDuration(pl) {
  return pl.segments.reduce((a, s) => a + s.duration, 0);
}

/** Пиковый и средний битрейт по сегментам, бит/с. */
function bitrates(pl) {
  let peak = 0;
  let bytes = 0;
  let dur = 0;
  for (const s of pl.segments) {
    if (s.length === null || !(s.duration > 0)) continue;
    bytes += s.length;
    dur += s.duration;
    peak = Math.max(peak, (s.length * 8) / s.duration);
  }
  return { peak: Math.round(peak), avg: dur > 0 ? Math.round((bytes * 8) / dur) : 0 };
}

/**
 * Совпадают ли сегменты двух видеодорожек (ABR): одинаковое число, длительности EXTINF и начала сегментов
 * в пределах tol, с. Последний сегмент — с допуском tailTol: у копии ffmpeg считает его без длительности
 * последних кадров (на 1–3 кадра короче), на переключение качества это не влияет.
 * @returns {string|null} описание первого расхождения или null
 */
function segmentMismatch(a, b, tol, tailTol = 0.5) {
  if (a.segments.length !== b.segments.length) return `сегментов ${a.segments.length} и ${b.segments.length}`;
  let ta = 0;
  let tb = 0;
  const n = a.segments.length;
  for (let i = 0; i < n; i++) {
    const da = a.segments[i].duration;
    const db = b.segments[i].duration;
    if (Math.abs(ta - tb) > tol) return `сегмент ${i} начинается на ${ta.toFixed(3)} с и ${tb.toFixed(3)} с`;
    if (Math.abs(da - db) > (i === n - 1 ? tailTol : tol)) return `сегмент ${i}: ${da} с и ${db} с`;
    ta += da;
    tb += db;
  }
  return null;
}

/** Тривиальный медиаплейлист субтитров: один сегмент на всю длительность (как subs-s0.m3u8). */
function subsPlaylist(durationMs, file = 'subs.vtt') {
  const sec = Math.max(0.001, durationMs / 1000);
  return [
    '#EXTM3U',
    '#EXT-X-VERSION:7',
    `#EXT-X-TARGETDURATION:${Math.ceil(sec)}`,
    '#EXT-X-MEDIA-SEQUENCE:0',
    '#EXT-X-PLAYLIST-TYPE:VOD',
    `#EXTINF:${sec.toFixed(3)},`,
    file,
    '#EXT-X-ENDLIST',
    '',
  ].join('\n');
}

module.exports = { parseMediaPlaylist, validateByteRangePlaylist, totalDuration, bitrates, segmentMismatch, subsPlaylist, parseAttrs };

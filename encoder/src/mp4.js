'use strict';

// Строка CODECS (RFC 6381) из init-сегмента fMP4: hvcC → hvc1.1.6.L120.90, avcC → avc1.640028.

const fs = require('node:fs');

/** Ищет бокс по четырёхбуквенному типу в дереве moov → … → stsd → hvc1/avc1 → hvcC/avcC. */
function findBox(buf, type) {
  const t = Buffer.from(type, 'latin1');
  let i = buf.indexOf(t, 4);
  while (i >= 4) {
    const size = buf.readUInt32BE(i - 4);
    if (size >= 8 && i - 4 + size <= buf.length) return buf.subarray(i + 4, i - 4 + size);
    i = buf.indexOf(t, i + 1);
  }
  return null;
}

function reverseBits32(x) {
  let r = 0;
  for (let i = 0; i < 32; i++) { r = (r << 1) | (x & 1); x >>>= 1; }
  return r >>> 0;
}

function hevcCodec(hvcC, sampleType = 'hvc1') {
  const b1 = hvcC[1];
  const space = b1 >> 6;
  const tier = (b1 >> 5) & 1;
  const idc = b1 & 0x1f;
  const compat = reverseBits32(hvcC.readUInt32BE(2));
  const cons = [...hvcC.subarray(6, 12)];
  while (cons.length && cons[cons.length - 1] === 0) cons.pop();
  const level = hvcC[12];
  const parts = [sampleType, `${['', 'A', 'B', 'C'][space]}${idc}`, compat.toString(16).toUpperCase(), `${tier ? 'H' : 'L'}${level}`];
  for (const c of cons) parts.push(c.toString(16).toUpperCase().padStart(2, '0'));
  return parts.join('.');
}

function avcCodec(avcC, sampleType = 'avc1') {
  const h = (n) => n.toString(16).padStart(2, '0');
  return `${sampleType}.${h(avcC[1])}${h(avcC[2])}${h(avcC[3])}`;
}

/** CODECS для видеодорожки из начала файла (init-сегмент — первые mapLength байт). */
function codecFromInit(buf) {
  const hvcC = findBox(buf, 'hvcC');
  if (hvcC) return hevcCodec(hvcC, buf.includes(Buffer.from('hev1')) ? 'hev1' : 'hvc1');
  const avcC = findBox(buf, 'avcC');
  if (avcC) return avcCodec(avcC, buf.includes(Buffer.from('avc3')) ? 'avc3' : 'avc1');
  return null;
}

function codecFromFile(file, initLength) {
  const fd = fs.openSync(file, 'r');
  try {
    const buf = Buffer.alloc(initLength);
    fs.readSync(fd, buf, 0, initLength, 0);
    return codecFromInit(buf);
  } finally {
    fs.closeSync(fd);
  }
}

module.exports = { codecFromInit, codecFromFile, hevcCodec, avcCodec, reverseBits32 };

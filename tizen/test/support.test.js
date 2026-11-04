'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const S = require('../js/support.js');

test('checkFile: по имени файла', () => {
  assert.equal(S.checkFile('Film/Film.2020.1080p.mkv').level, 'ok');
  assert.equal(S.checkFile('Film/Disc.iso').level, 'no');
  assert.equal(S.checkFile('Film.2023.2160p.AV1.mkv').level, 'no');
  assert.equal(S.checkFile('Old.Film.DVDRip.XviD.avi').level, 'warn');
  assert.equal(S.checkFile('Film.2010.1080p.BluRay.DTS-HD.MA.5.1.mkv').level, 'server');
  assert.equal(S.checkFile('Film.2010.2160p.Remux.TrueHD.Atmos.mkv').level, 'server');
  assert.equal(S.checkFile('Film_DTS_rus.mkv').level, 'server');
  // «dts» внутри слова — не кодек
  assert.equal(S.checkFile('Shorts.2009.mkv').level, 'ok');
  assert.equal(S.checkFile('Film.WEB-DL.DDP5.1.Atmos.mkv').level, 'ok');
});

test('audioPlan: дорожки DTS/TrueHD не играются', () => {
  const info = [
    { index: 0, type: 'VIDEO', extra_info: '{"fourCC":"H264"}' },
    { index: 1, type: 'AUDIO', extra_info: '{"fourCC":"DTS","language":"rus","channels":"6"}' },
    { index: 2, type: 'AUDIO', extra_info: '{"fourCC":"AC3","language":"eng","channels":"6"}' },
    { index: 3, type: 'AUDIO', extra_info: 'not json' },
    { index: 4, type: 'TEXT', extra_info: '{}' }
  ];
  const plan = S.audioPlan(info, 1);
  assert.equal(plan.tracks.length, 3);
  assert.deepEqual(plan.playable.map(t => t.index), [2, 3]);
  assert.equal(plan.current.ok, false);
  assert.equal(plan.current.language, 'rus');
  assert.equal(S.audioPlan([{ index: 1, type: 'AUDIO', extra_info: '{"fourCC":"TRUEHD"}' }], 1).playable.length, 0);
});

test('errorKind', () => {
  assert.equal(S.errorKind('PLAYER_ERROR_NOT_SUPPORTED_FILE'), 'unsupported');
  assert.equal(S.errorKind('PLAYER_ERROR_CONNECTION_FAILED'), 'network');
  assert.equal(S.errorKind('PLAYER_ERROR_GENEREIC'), 'other');
});

test('checkFile: Blu-ray (.m2ts) — через сервер', () => {
  assert.equal(S.checkFile('Film.2010.BluRay/BDMV/STREAM/00001.m2ts').level, 'server');
  assert.equal(S.checkFile('Film.m2ts').level, 'server');
  assert.match(S.slowHint('BDMV/STREAM/00001.m2ts'), /Blu-ray/);
  assert.match(S.slowHint('Film.2160p.Remux.mkv'), /тяжёлый/);
});

test('checkVariantCompatibility и pickBestCompatibleVariant для Tizen TV (DTS guard)', () => {
  const v1 = { key: '1|dts.mkv', audio_tech: 'DTS-HD MA 5.1', hardware_flags: ['dts'], score: 395 };
  const v2 = { key: '2|ac3.mkv', audio_tech: 'AC3 5.1', hardware_flags: [], score: 335 };

  const c1 = S.checkVariantCompatibility(v1);
  assert.equal(c1.ok, false);
  assert.equal(c1.warn, true);
  assert.match(c1.text, /Нет звука на вашем ТВ/);

  const c2 = S.checkVariantCompatibility(v2);
  assert.equal(c2.ok, true);
  assert.equal(c2.warn, false);

  const best = S.pickBestCompatibleVariant([v1, v2]);
  assert.equal(best.key, '2|ac3.mkv');
});

// Те же случаи, что shared/src/test/.../ModelTest.kt: правила медиатеки на ТВ не должны расходиться с приложениями.
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const M = require('../js/model.js');

const MB = 1024 * 1024;
const file = (index, path, sizeMb = 1000, link = `https://host/f/${index}`) =>
  ({ index, name: path.substring(path.lastIndexOf('/') + 1), path, isFolder: false, size: sizeMb * MB, link });
const mag = (files, extra = {}) => M.magnet(Object.assign({ id: 1, name: 'X', status: 'Готово', statusCode: 4, size: 0, downloaded: 0, files, canRemove: true }, extra));

test('parseLibrary: пустой и неверный ответ', () => {
  assert.deepEqual(M.parseLibrary({}), []);
  assert.deepEqual(M.parseLibrary({ magnets: [] }), []);
  assert.deepEqual(M.parseLibrary({ magnets: 'not-an-array' }), []);
});

test('parseLibrary: раздачи и файлы', () => {
  const list = M.parseLibrary({ magnets: [
    { id: 101, name: 'Interstellar.2014.1080p', status: 'Downloading', statusCode: 2, size: 10000000, downloaded: 5000000,
      canRemove: false, favorite: true, mine: true, files: [
        { name: 'Interstellar.mkv', path: 'Interstellar/Interstellar.mkv', type: 'file', size: 9500000, sourceLink: 'https://alldebrid.com/f/xyz' },
        { name: 'Subs', path: 'Interstellar/Subs', type: 'folder', size: 0, sourceLink: '' },
        { name: 'insecure.srt', path: 'Interstellar/Subs/insecure.srt', type: 'file', size: 5000, sourceLink: 'http://insecure.test/file.srt' }
      ] },
    { id: 102, name: '', status: '', statusCode: 4 }
  ] });
  assert.equal(list.length, 2);
  const [m1, m2] = list;
  assert.equal(m1.id, 101);
  assert.equal(m1.canRemove, false);
  assert.equal(m1.mine, true);
  assert.equal(m1.downloading, true);
  assert.equal(m1.progress, 0.5);
  assert.equal(m1.files[0].link, 'https://alldebrid.com/f/xyz');
  assert.equal(m1.files[1].isFolder, true);
  assert.equal(m1.files[1].link, null);
  assert.equal(m1.files[2].link, null);
  assert.equal(m2.name, 'Magnet 102');
  assert.equal(m2.ready, true);
  assert.equal(m2.canRemove, true);
  assert.equal(m2.favorite, false);
});

test('cleanTitle', () => {
  assert.equal(M.cleanTitle('Inception.2010.1080p.BluRay.x264-SPARKS.mkv'), 'Inception');
  assert.equal(M.cleanTitle('Breaking.Bad.S01.1080p.NF.WEB-DL.DDP5.1.H.264-NTb'), 'Breaking Bad S01');
  assert.equal(M.cleanTitle('Dune.Part.Two.2024.2160p.UHD.Remux.HEVC.DV.HDR10+.mkv'), 'Dune Part Two');
  assert.equal(M.cleanTitle('The Matrix (1999) [BDRip 720p]'), 'The Matrix');
  assert.equal(M.cleanTitle('Movie_Title_2021_Proper_Repack'), 'Movie Title');
  assert.equal(M.cleanTitle('Severance.S01E01.480p.WEBRip.x264.mp4'), 'Severance S01E01');
  assert.equal(M.cleanTitle(''), 'Untitled');
  assert.ok(M.cleanTitle('1080p').length > 0);
});

test('formatBytes и statusRu', () => {
  assert.equal(M.formatBytes(0), '—');
  assert.equal(M.formatBytes(512), '512 B');
  assert.equal(M.formatBytes(1536), '1.5 KB');
  assert.equal(M.formatBytes(10 * MB), '10.0 MB');
  const s = (code, status) => M.statusRu(mag([], { statusCode: code, status }));
  assert.equal(s(4, 'Uploading'), 'Готово');
  assert.equal(s(2, 'downloading'), 'Загрузка');
  assert.equal(s(1, '   '), 'Обработка');
  assert.equal(s(5, ''), 'Ошибка');
  assert.equal(s(5, 'Bad Torrent'), 'Bad Torrent');
  assert.equal(s(1, 'Queued'), 'Queued');
});

test('mainVideo: самый большой, .iso — только если больше нечего', () => {
  assert.equal(M.mainVideo(mag([file(0, 'Film.sample.mkv', 50), file(1, 'Film.iso', 8000), file(2, 'Film.mkv', 4000)])).name, 'Film.mkv');
  assert.equal(M.mainVideo(mag([file(0, 'Disc.iso', 4000)])).name, 'Disc.iso');
  assert.equal(M.mainVideo(mag([file(0, 'readme.txt', 1)])), null);
});

test('buildPlayable: фильм, сезоны по папкам и по именам, без sample', () => {
  const movie = M.buildPlayable(mag([file(0, 'Avatar.sample.mkv', 30), file(1, 'Avatar.mkv', 8000)]));
  assert.equal(movie.kind, 'movie');
  assert.equal(movie.file.name, 'Avatar.mkv');

  const bb = M.buildPlayable(mag([
    file(0, 'Breaking Bad/Season 1/ep01.mkv'), file(1, 'Breaking Bad/Season 1/ep02.mkv'),
    file(2, 'Breaking Bad/Season 1/ep10.mkv'), file(3, 'Breaking Bad/Season 2/ep01.mkv')]));
  assert.equal(bb.kind, 'series');
  assert.equal(bb.seasons.length, 2);
  assert.deepEqual(bb.seasons[0].items.map(e => e.file.name), ['ep01.mkv', 'ep02.mkv', 'ep10.mkv']);
  assert.deepEqual(bb.seasons[0].items.map(e => e.episode), [1, 2, 3]);

  const ru = M.buildPlayable(mag([file(0, 'Чернобыль/Сезон 1/01.mkv'), file(1, 'Чернобыль/Сезон 1/02.mkv')]));
  assert.equal(ru.seasons[0].season, 1);
  assert.equal(ru.seasons[0].items.length, 2);

  const sh = M.buildPlayable(mag([file(0, 'Sherlock.S01E01.mkv'), file(1, 'Sherlock.S01E02.mkv'), file(2, 'Sherlock.1x03.mkv')]));
  assert.deepEqual(sh.seasons[0].items.map(e => e.episode), [1, 2, 3]);

  const smp = M.buildPlayable(mag([file(0, 'sample/sample.mkv', 50), file(1, 'movie.sample.avi', 50), file(2, 'movie.mkv', 2000)]));
  assert.equal(smp.file.name, 'movie.mkv');

  assert.equal(M.buildPlayable(mag([file(0, 'song1.flac', 30)])).kind, 'none');
});

test('buildPlayable: коллекция фильмов и Blu-ray', () => {
  const lotr = M.buildPlayable(mag([
    file(0, '1.The.Fellowship.of.the.Ring.2001.1080p.mkv', 8000), file(1, '2.The.Two.Towers.2002.1080p.mkv', 8200),
    file(2, '3.The.Return.of.the.King.2003.1080p.mkv', 9000), file(3, 'Extras/featurette.mkv', 500)]));
  assert.deepEqual(lotr.seasons[0].items.map(e => e.title), ['1 The Fellowship of the Ring', '2 The Two Towers', '3 The Return of the King']);

  const bd = M.buildPlayable(mag([file(0, 'BDMV/STREAM/00001.m2ts', 20000), file(1, 'BDMV/STREAM/00002.m2ts', 15000)]));
  assert.equal(bd.kind, 'movie');
  assert.equal(bd.file.name, '00001.m2ts');
});

test('episodeLabel', () => {
  const f = file(0, 'ep.mkv');
  assert.equal(M.episodeLabel('Breaking Bad', { season: 1 }, { episode: 2, file: f }), 'Breaking Bad · S01E02');
  assert.equal(M.episodeLabel('Doctor Who', { season: 0 }, { episode: 1, file: f }), 'Doctor Who · Спец.E01');
  assert.equal(M.episodeLabel('Alien Collection', { season: 1 }, { episode: 1, file: f, title: 'Alien 1' }), 'Alien 1');
});

test('субтитры и озвучки рядом с видео', () => {
  const vid1 = file(0, 'Series/S01/Episode.01.1080p.mkv');
  const vid2 = file(1, 'Series/S01/Episode.02.1080p.mkv');
  const sub1Rus = file(2, 'Series/S01/Episode.01.1080p.rus.srt');
  const sub1Eng = file(3, 'Series/S01/Episode.01.1080p.eng.ass');
  const sub2Rus = file(4, 'Series/S01/Subs/Episode.02.1080p.srt');
  const m = mag([vid1, vid2, sub1Rus, sub1Eng, sub2Rus, file(5, 'Series/S01/other.srt')]);
  assert.deepEqual(M.subtitlesFor(m, vid1), [sub1Rus, sub1Eng]);
  assert.deepEqual(M.subtitlesFor(m, vid2), [sub2Rus]);

  const single = file(0, 'Movie/Movie.mkv');
  assert.equal(M.subtitlesFor(mag([single, file(1, 'Movie/Subs/rus.srt'), file(2, 'Movie/eng.vtt')]), single).length, 2);

  const e1 = file(0, 'Show/Show.S01E1.mkv'), e10 = file(1, 'Show/Show.S01E10.mkv');
  const s1 = file(2, 'Show/Show.S01E1.rus.srt'), s10 = file(3, 'Show/Show.S01E10.rus.srt');
  const a1 = file(4, 'Show/Show.S01E1_rus.mka'), a10 = file(5, 'Show/Show.S01E10.mka');
  const show = mag([e1, e10, s1, s10, a1, a10]);
  assert.deepEqual(M.subtitlesFor(show, e1), [s1]);
  assert.deepEqual(M.subtitlesFor(show, e10), [s10]);
  assert.deepEqual(M.externalAudioFor(show, e1), [a1]);
  assert.deepEqual(M.externalAudioFor(show, e10), [a10]);

  const film = file(0, 'Film/Film.mkv'), dub = file(1, 'Film/Dub.ac3');
  assert.deepEqual(M.externalAudioFor(mag([film, dub]), film), [dub]);
});

test('watchKey, watchInfo, prepareItems', () => {
  assert.equal(M.watchKey(42, file(5, 'folder/movie.mkv')), '42|folder/movie.mkv');
  assert.equal(M.watchInfo(50000, 100000, 1).watched, false);
  assert.equal(M.watchInfo(85000, 100000, 1).watched, true);
  assert.equal(M.watchInfo(0, 0, 0).fraction, 0);
  const [item] = M.prepareItems([mag([file(0, 'Inception.mkv', 4000)], { id: 10, name: 'Inception.2010.1080p' })]);
  assert.equal(item.baseTitle, 'Inception');
  assert.equal(item.playable.kind, 'movie');
  assert.ok(item.posterQueries.includes('Inception'));
  assert.ok(item.searchText.includes('inception.mkv'));
});

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

test('озвучки отдельным файлом: названия, озвучки пользователей, выбор в ответе tv-info', () => {
  const e1 = file(0, 'Show/Show.S01E01.mkv');
  assert.equal(M.dubLabel(e1, file(1, 'Show/Show.S01E01.rus.LostFilm.mka')), 'rus LostFilm');
  assert.equal(M.dubLabel(e1, file(2, 'Show/RUS Sound/HDRezka/Show.S01E01.mka')), 'HDRezka', 'имя как у видео — папка');
  assert.equal(M.dubLabel(file(0, 'Film/Film.mkv'), file(1, 'Film/Dub.ac3')), 'Dub');

  assert.equal(M.customName({ title: 'HDRezka [rus]', lang: 'rus' }), 'HDRezka [rus]');
  assert.equal(M.customName({ title: 'HDRezka', lang: 'rus' }), 'HDRezka [rus]');
  assert.equal(M.customName({ title: '', lang: 'und', audioPath: 'Dub/S01E01.mka' }), 'S01E01.mka');

  const side = file(3, 'Show/Show.S01E01.LostFilm.mka');
  const custom = [
    { id: 'trk_00000001', videoPath: 'Show/Show.S01E01.mkv', audioPath: 'S01E01.RHS.mka', title: 'RHS', lang: 'rus' },
    { id: 'trk_00000002', videoPath: 'Show/Show.S01E02.mkv', audioPath: 'S01E02.RHS.mka', title: 'RHS', lang: 'rus' },
    { id: 'trk_00000003', videoPath: 'Show/Show.S01E01.mkv', audioPath: 'S01E01.RHS.aac', title: 'Old', lang: null }
  ];
  const choices = M.externalChoices(e1, [side], custom);
  assert.deepEqual(choices.map(c => [c.kind, c.label]), [['file', 'LostFilm'], ['custom', 'RHS [rus]']], 'чужая серия и не .mka — мимо');

  const info = { external: [
    { kind: 'file', link: 'https://host/f/3', offsetMs: 0, url: 'u1' },
    { kind: 'custom', id: 'trk_00000001', offsetMs: -4200, url: 'u2' }
  ] };
  assert.equal(M.externalIn(info, choices[0]).url, 'u1');
  assert.equal(M.externalIn(info, choices[1]).url, 'u2');
  assert.equal(M.externalIn({}, choices[1]), null, 'старый сервер без external');

  assert.equal(M.isVoiceOverOnly(mag([file(0, 'Dub/S01E01.mka'), file(1, 'Dub/S01E02.mka')])), true, 'раздача-озвучка — не плитка');
  assert.equal(M.isVoiceOverOnly(mag([e1, side])), false);
  assert.equal(M.isVoiceOverOnly(mag([])), false);
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

test('cleanTitle: не строка — не падает', () => {
  assert.equal(M.cleanTitle(undefined), 'Untitled');
  assert.equal(M.cleanTitle(null), 'Untitled');
});

// ---- слияние версий (SeriesGroupingTest.kt, groupVersions) ----

test('titleKey: пометки сезона не делят название', () => {
  const a = M.titleKey(M.cleanTitle('Breaking.Bad.S01.1080p.BluRay'));
  assert.equal(a, 'breaking bad');
  assert.equal(M.titleKey(M.cleanTitle('Breaking Bad Season 2 1080p')), a);
  assert.equal(M.titleKey(M.cleanTitle('Breaking Bad [1-3 сезон] 1080p')), a);
  assert.equal(M.titleKey(M.cleanTitle('Breaking Bad Complete 720p')), a);
  assert.notEqual(M.titleKey('Cyberpunk Edgerunners'), M.titleKey('Cyberpunk 2077'));
});

test('buildPlayable: пронумерованные серии — сериал, а не сборник', () => {
  const files = Array.from({ length: 10 }, (_, i) => file(i, `Show/Show - ${String(i + 1).padStart(2, '0')} [1080p].mkv`, 800));
  const p = M.buildPlayable(mag(files, { name: 'Show [WEB-DL 1080p]' }));
  assert.equal(p.kind, 'series');
  assert.equal(p.seasons[0].items.length, 10);
  assert.equal(p.seasons[0].items[0].episode, 1);
  assert.equal(p.seasons[0].items[0].title, null);
});

test('buildPlayable: трилогия остаётся сборником', () => {
  const p = M.buildPlayable(mag([file(0, 'Film 1 (1999).mkv', 4000), file(1, 'Film 2 (2003).mkv', 4200), file(2, 'Film 3 (2003).mkv', 4100)], { name: 'Trilogy' }));
  assert.equal(p.kind, 'series');
  assert.equal(p.seasons[0].items[0].title, 'Film 1');
});

test('groupVersions: сезоны и раздачи сериала — одна плитка; фильмы — по названию и году', () => {
  const ep = (n) => file(n, `S/Show.S01E0${n + 1}.mkv`, 500);
  const items = M.prepareItems([
    mag([ep(0), ep(1)], { id: 1, name: 'Show.S01.1080p' }),
    mag([ep(0), ep(1)], { id: 2, name: 'Show Season 2 720p' }),
    mag([file(0, 'Film.mkv', 4000)], { id: 3, name: 'Film.2020.1080p' }),
    mag([file(0, 'Film.mkv', 2000)], { id: 4, name: 'Film.2020.720p' }),
    mag([file(0, 'Film.mkv', 2000)], { id: 5, name: 'Film.2021.720p' })
  ]);
  const g = M.groupVersions(items);
  assert.deepEqual(g.shown.map((i) => i.m.id), [1, 3, 5]);
  assert.deepEqual(g.versions[1].map((i) => i.m.id), [1, 2]);
  assert.deepEqual(g.versions[3].map((i) => i.m.id), [3, 4]);
  assert.equal(g.versions[5], undefined);
});

// ---- название от ИИ (services/aititle.js; groupTitle в Model.kt) ----
test('parseLibrary: aiTitle и canAiTitle; неизвестный статус отбрасывается', () => {
  const list = M.parseLibrary({ magnets: [
    { id: 1, name: 'A', statusCode: 4, files: [], canAiTitle: true, aiTitle: { base: ' Тед Лассо ', season: 2, source: 'ai', model: 'gemini-3.5-flash-lite', at: 5, status: 'suggested' } },
    { id: 2, name: 'B', statusCode: 4, files: [], aiTitle: { base: 'X', season: null, status: 'weird' } },
    { id: 3, name: 'C', statusCode: 4, files: [] }
  ] });
  assert.deepEqual(list[0].aiTitle, { base: 'Тед Лассо', season: 2, model: 'gemini-3.5-flash-lite', at: 5, status: 'suggested', kind: null, stale: false, original: null, year: null });
  assert.equal(list[0].canAiTitle, true);
  assert.equal(list[1].aiTitle, null);
  assert.equal(list[2].aiTitle, null);
  assert.equal(list[2].canAiTitle, false);
});

test('groupVersions: принятое название ИИ объединяет, предложенное — нет', () => {
  const ai = (status) => ({ base: 'Тачки 2', season: null, model: 'm', at: 1, status });
  const build = (status) => M.prepareItems([
    mag([file(0, 'Cars.2.mkv', 4000)], { id: 1, name: 'Тачки 2 (2011) BDRip' }),
    mag([file(0, 'Cars2.mkv', 2000)], { id: 2, name: 'Cars.Two.2011.720p', aiTitle: ai(status) })
  ]);
  assert.equal(M.groupTitle(build('accepted')[1]), 'Тачки 2');
  assert.deepEqual(M.groupVersions(build('accepted')).versions[1].map((i) => i.m.id), [1, 2]);
  assert.deepEqual(M.groupVersions(build('suggested')).shown.map((i) => i.m.id), [1, 2]);
  assert.deepEqual(M.groupVersions(build('rejected')).shown.map((i) => i.m.id), [1, 2]);
});

test('prepareItems: принятое название ИИ — в названии плитки и первым запросом постера', () => {
  const ai = (status) => ({ base: 'Как приручить лису', season: 1, model: 'm', at: 1, status });
  const files = [file(0, 'Kak.priruchit.lisu.S01E01.mkv', 800), file(1, 'Kak.priruchit.lisu.S01E02.mkv', 800)];
  const acc = M.prepareItems([mag(files, { name: 'Kak.priruchit.lisu.S01.2025.WEB-DL.1080p', aiTitle: ai('accepted') })])[0];
  assert.equal(acc.baseTitle, 'Как приручить лису');
  assert.equal(acc.posterQueries[0], 'Как приручить лису');
  const sug = M.prepareItems([mag(files, { name: 'Kak.priruchit.lisu.S01.2025.WEB-DL.1080p', aiTitle: ai('suggested') })])[0];
  assert.notEqual(sug.baseTitle, 'Как приручить лису');
});

// ---- тип раздачи и сезон от ИИ (applyAiTitle в Model.kt) ----
test('buildPlayable: принятые тип и сезон от ИИ', () => {
  const ai = (kind, season, status = 'accepted') => ({ base: 'Show', season, kind, model: 'm', at: 1, status });
  const films = [file(0, 'Show/Show A.mkv', 1500), file(1, 'Show/Show B.mkv', 1500), file(2, 'Show/Show C.mkv', 1500)];
  // по файлам — сборник (нет номеров серий)
  assert.ok(M.buildPlayable(mag(films, { name: 'Show' })).seasons[0].items[0].title);
  const series = M.buildPlayable(mag(films, { name: 'Show', aiTitle: ai('series', 3) }));
  assert.equal(series.kind, 'series');
  assert.equal(series.seasons[0].season, 3);
  assert.deepEqual(series.seasons[0].items.map((e) => [e.episode, e.title, e.file.path]), [[1, null, 'Show/Show A.mkv'], [2, null, 'Show/Show B.mkv'], [3, null, 'Show/Show C.mkv']]);
  // предложенное, не принятое — без изменений
  assert.ok(M.buildPlayable(mag(films, { name: 'Show', aiTitle: ai('series', 3, 'suggested') })).seasons[0].items[0].title);
  // сериал по SxxEyy: сезон 1 → 2; сборник; фильм
  const eps = [file(0, 'S/Show.S01E01.mkv', 500), file(1, 'S/Show.S01E02.mkv', 500)];
  const relabeled = M.buildPlayable(mag(eps, { name: 'Show', aiTitle: ai(null, 2) }));
  assert.deepEqual([relabeled.seasons[0].season, relabeled.seasons[0].items[1].season, relabeled.seasons[0].items[1].episode], [2, 2, 2]);
  assert.ok(M.buildPlayable(mag(eps, { name: 'Show', aiTitle: ai('collection', null) })).seasons[0].items[0].title);
  const movie = M.buildPlayable(mag([file(0, 'Film.mkv', 4000), file(1, 'Film part2.mkv', 3900)], { name: 'Film', aiTitle: ai('movie', null) }));
  assert.equal(movie.kind, 'movie');
  assert.equal(movie.file.path, 'Film.mkv');
  // два сезона по папкам — номер сезона от ИИ не трогает их
  const two = [file(0, 'Season 1/a.mkv', 500), file(1, 'Season 2/b.mkv', 500)];
  assert.deepEqual(M.buildPlayable(mag(two, { name: 'Show', aiTitle: ai('series', 5) })).seasons.map((s) => s.season), [1, 2]);
});

test('prepareItems: постер — сначала оригинал с годом от ИИ', () => {
  const ai = { base: 'Дюна', season: null, kind: 'movie', original: 'Dune', year: 2021, model: 'm', at: 1, status: 'accepted' };
  const it = M.prepareItems([mag([file(0, 'Dune.2021.mkv', 4000)], { name: 'Дюна (2021) BDRip', aiTitle: ai })])[0];
  assert.deepEqual(it.posterQueries.slice(0, 2), ['Dune (2021)', 'Дюна']);
  const sug = M.prepareItems([mag([file(0, 'Dune.2021.mkv', 4000)], { name: 'Дюна (2021) BDRip', aiTitle: Object.assign({}, ai, { status: 'suggested' }) })])[0];
  assert.ok(sug.posterQueries.indexOf('Dune (2021)') < 0);
  const parsed = M.parseLibrary({ magnets: [{ id: 1, name: 'A', statusCode: 4, files: [], aiTitle: { base: 'Дюна', original: ' Dune ', year: 2021, status: 'accepted' } }] })[0];
  assert.equal(parsed.aiTitle.original, 'Dune');
  assert.equal(parsed.aiTitle.year, 2021);
});

// Те же случаи, что shared/src/test/.../search/ReleaseParserTest.kt и SearchFilterTest.kt: поиск на ТВ и сайте не должен расходиться с ПК.
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const S = require('../js/searchcore.js');

const GB = 1024 * 1024 * 1024;
const inRange = (v, a, b) => v >= a && v <= b;

test('4K Dolby Vision Remux', () => {
  const i = S.parse('Дюна: Часть вторая / Dune: Part Two (2024) BDRemux 2160p | HDR, Dolby Vision | DDP 5.1, TrueHD 7.1.Atmos | D', 58 * GB, 'movie');
  assert.equal(i.cleanTitle, 'Дюна: Часть вторая / Dune: Part Two (2024)');
  assert.equal(i.year, 2024);
  assert.equal(i.resolution, '4K');
  assert.equal(i.ripType, 'REMUX');
  assert.equal(i.hdr, 'DV');
  assert.equal(i.voice, 'DUB');
  assert.equal(i.isCam, false);
  assert.equal(i.bitrateTier, 'HEAVY');
  assert.ok(i.estimatedBitrateMbps > 50);
  assert.equal(i.isMobileFriendly, false);
  const t = S.badges(i).map((b) => b.text);
  for (const x of ['4K', 'BDRemux', 'Dolby Vision', 'Дубляж']) assert.ok(t.includes(x), x);
});

test('1080p BDRip с дубляжом', () => {
  const i = S.parse('Интерстеллар / Interstellar (2014) BDRip 1080p | D, P, A | Лицензия', 8 * GB, 'movie');
  assert.equal(i.cleanTitle, 'Интерстеллар / Interstellar (2014)');
  assert.equal(i.resolution, '1080p');
  assert.equal(i.ripType, 'BDRIP');
  assert.equal(i.voice, 'DUB');
  assert.equal(i.bitrateTier, 'MEDIUM');
  assert.ok(inRange(i.estimatedBitrateMbps, 9, 12));
});

test('720p WEB-DL, мобильная сеть', () => {
  const i = S.parse('Оппенгеймер / Oppenheimer (2023) WEB-DLRip 720p | D', 2 * GB + 200 * 1024 * 1024, 'movie');
  assert.equal(i.resolution, '720p');
  assert.equal(i.ripType, 'WEBDL');
  assert.equal(i.bitrateTier, 'LIGHT');
  assert.ok(inRange(i.estimatedBitrateMbps, 2.5, 3.2));
  assert.equal(i.isMobileFriendly, true);
});

test('HEVC BDRip', () => {
  const i = S.parse('Мальчик и птица / The Boy and the Heron (2023) BDRip-HEVC 1080p | MVO', 3 * GB, 'movie');
  assert.equal(i.resolution, '1080p');
  assert.equal(i.codec, 'HEVC');
  assert.equal(i.ripType, 'BDRIP');
  assert.equal(i.voice, 'MVO');
  assert.equal(i.isMobileFriendly, true);
});

test('CAMRip и Telesync', () => {
  for (const t of ['Гладиатор 2 / Gladiator II (2024) CAMRip | Звук с TS', 'Форсаж 10 / Fast X (2023) Telesync [TS]']) {
    const i = S.parse(t, 1.5e9, 'movie');
    assert.equal(i.isCam, true);
    assert.equal(i.ripType, 'CAM');
    assert.equal(i.isMobileFriendly, false);
  }
});

test('сериал: число серий и битрейт', () => {
  const a = S.parse('Одни из нас / The Last of Us (Сезон 1, серии 1-9 из 9) (2023) WEB-DL 1080p | Red Head Sound', 18 * GB, 'series');
  assert.equal(a.episodesCount, 9);
  assert.equal(a.ripType, 'WEBDL');
  assert.ok(inRange(a.estimatedBitrateMbps, 5.5, 6.5));
  const b = S.parse('Во все тяжкие / Breaking Bad [01-05x01-62] (2008-2013) BDRip 720p | LostFilm', 50 * GB, 'series');
  assert.equal(b.episodesCount, 62);
  assert.equal(b.bitrateTier, 'LIGHT');
  assert.equal(b.isMobileFriendly, true);
  const c = S.parse('Игра престолов / Game of Thrones [01-08x01-73 из 73] (2011-2019) BDRip 1080p | LostFilm', 120 * GB, 'series');
  assert.equal(c.episodesCount, 73);
});

test('английское название с точками', () => {
  const i = S.parse('Dune.Part.Two.2024.2160p.UHD.BluRay.x265.TrueHD.7.1.Atmos.DV.HDR10-SWTYBLZ', 40 * GB, 'movie');
  assert.equal(i.cleanTitle, 'Dune Part Two 2024');
  assert.equal(i.year, 2024);
  assert.equal(i.resolution, '4K');
  assert.equal(i.codec, 'HEVC');
  assert.equal(i.ripType, 'BLURAY');
  assert.equal(i.hdr, 'DV');
});

test('коллекции', () => {
  const r = S.parse('Обитель зла / Resident Evil (2002-2023) BDRip [H.265/1080p] [10-bit] [Коллекция]', Math.floor(83.3 * GB), 'movie');
  assert.equal(r.isCollection, true);
  assert.equal(r.collectionCount, 7);
  assert.equal(r.codec, 'HEVC');
  assert.ok(inRange(r.estimatedBitrateMbps, 14, 18));
  assert.ok(S.badges(r).map((b) => b.text).includes('Коллекция (7)'));
  const t = S.parse('Властелин колец: Трилогия / The Lord of the Rings: Trilogy (2001-2003) BDRip 1080p', 36 * GB, 'movie');
  assert.equal(t.collectionCount, 3);
  assert.equal(S.parse('Дюна: Дилогия (2021-2024) BDRip 1080p').collectionCount, 2);
  assert.equal(S.parse('Джон Уик: Квадрология (2014-2023) BDRip 1080p').collectionCount, 4);
});

test('не видео: игры, софт, музыка, книги', () => {
  assert.ok(S.isNonVideo("Marvel's Spider-Man 2: Digital Deluxe Edition [RUS text / RUS sound / EUR / v01.005.001 / Лицензия / FPKG]"));
  assert.ok(S.isNonVideo('Cyberpunk 2077 [v 2.13] (2020) PC | RePack от FitGirl'));
  assert.ok(S.isNonVideo('The Witcher 3: Wild Hunt - Complete Edition (2015) PC | GOG'));
  for (const c of ['PS4', 'Игры для ПК', 'Программы и Дизайн', 'Аудиокниги', 'Музыка (lossless)']) assert.ok(S.isNonVideo('Any title', c), c);
  assert.ok(!S.isNonVideo('Человек-паук 2 / Spider-Man 2 (2004) BDRip 1080p | DUB', 'Зарубежное кино (HD Video)'));
  assert.ok(!S.isNonVideo('Spider-Man: No Way Home (2021) 2160p UHD Blu-Ray Remux | HDR | Dolby Vision', 'Кино (4K UHD)'));
  assert.ok(!S.isNonVideo('Игра престолов / Game of Thrones [S01-S08] (2011-2019) BDRip 1080p', 'Зарубежные сериалы'));
  assert.ok(!S.isNonVideo('Linkin Park - Live in Texas (2003) 1080p', 'Музыкальное видео'));
  assert.ok(!S.isNonVideo('Преступление и наказание (2007) 1080p', 'Экранизации книг'));
  assert.ok(S.isNonVideo('Стивен Кинг - Сияние FB2, EPUB', 'Книги и журналы'));
  assert.ok(S.isNonVideo('Some Game (2020) PC', 'Игры для PC: фильм-квесты'));
  assert.ok(!S.isNonVideo('Silent Hill (2006) 1080p', 'Фильмы по играм'));
});

test('одна игровая метка не прячет фильм', () => {
  assert.ok(!S.isNonVideo('Dune.Part.Two.2024.REPACK.1080p.WEB-DL.x264'));
  assert.ok(!S.isNonVideo('Бэтмен против Супермена: На заре справедливости [Ultimate Edition] (2016) BDRip 1080p'));
  assert.ok(!S.isNonVideo('Хоббит: Нежданное путешествие (2012) Gold Edition BDRemux 1080p'));
  assert.ok(S.isNonVideo('Elden Ring (2022) PC | RePack'));
});

test('трейлеры и тизеры', () => {
  assert.ok(!S.isTrailerOrTeaser('Парни из Трейлерного парка / Trailer Park Boys (2001) WEBRip 720p'));
  assert.ok(!S.isTrailerOrTeaser('Трейлерный парк (2019) WEB-DL 1080p'));
  for (const t of ['Человек-паук: Новый день [2026] тизер-трейлер', 'Мстители: Секретные войны (2026) Тизер [1080p]', 'GTA 6 Official Trailer 1080p', 'Бэтмен 2 (2026) Русский трейлер']) {
    assert.ok(S.isTrailerOrTeaser(t), t);
    assert.ok(S.isNonVideo(t), t);
  }
  assert.ok(!S.isTrailerOrTeaser('Новый Человек-паук / The Amazing Spider-Man (2012) BDRip 1080p'));
});

test('границы слов для кириллицы', () => {
  const s1 = S.parse('Дом Дракона (Сезон 1) (2022) 1080p', 10 * GB, 'movie');
  assert.equal(s1.resolution, '1080p');
  assert.ok(inRange(s1.estimatedBitrateMbps, 3, 4));
  assert.equal(S.parse('Слово пацана (серии 1-8) (2023) WEBRip 720p', 4 * GB, 'series').episodesCount, 8);
  assert.equal(S.parse('Чернобыль (1 из 5) (2019) BDRip 1080p', 3 * GB, 'series').episodesCount, 5);
  assert.equal(S.parse('Начало / Inception (2010) BDRip 1080p | Дубляж').voice, 'DUB');
  assert.equal(S.parse('Матрица / The Matrix (1999) BDRip 1080p | многоголосый').voice, 'MVO');
  assert.equal(S.parse('Бойцовский клуб / Fight Club (1999) BDRip 1080p | двухголосый').voice, 'DVO');
  const h = S.parse('Хоббит: Трилогия (2012-2014) BDRemux 1080p');
  assert.equal(h.isCollection, true);
  assert.equal(h.collectionCount, 3);
});

test('запрос: стоп-слова убираются', () => {
  assert.equal(S.cleanQuery('скачать фильм Дюна онлайн'), 'Дюна');
  assert.equal(S.cleanQuery('сериал'), 'сериал');
  assert.equal(S.cleanQuery('Интерстеллар'), 'Интерстеллар');
});

const hit = (id, title, size, seeders, kind) => S.toHit({ id, title, size, seeders, leechers: 0, kind: kind || 'movie', provider: 'T', ru: true });

test('фильтры: пресеты, качество, сиды, уточнение', () => {
  const hits = [
    hit('1', 'Дюна / Dune (2021) BDRemux 2160p | HDR', 60 * GB, 10),
    hit('2', 'Дюна / Dune (2021) WEB-DL 1080p | D', 6 * GB, 5),
    hit('3', 'Дюна / Dune (2021) CAMRip', 2 * GB, 50),
    hit('4', 'Дюна / Dune (2021) WEB-DL 720p | D', 2 * GB, 0)
  ];
  const ids = (c) => S.filter(hits, c).map((h) => h.id);
  assert.deepEqual(ids({ query: 'Дюна', preset: 'MAX_QUALITY' }), ['1']);
  assert.deepEqual(ids({ query: 'Дюна', preset: 'BALANCED' }).sort(), ['2', '4']);
  assert.deepEqual(ids({ query: 'Дюна', preset: 'MOBILE' }), ['4', '2'].filter((x) => ids({ preset: 'MOBILE' }).includes(x)));
  assert.deepEqual(ids({ resolution: '720p' }), ['4']);
  assert.ok(!ids({ onlySeeded: true }).includes('4'));
  assert.deepEqual(ids({ refine: 'remux' }), ['1']);
  assert.deepEqual(ids({ sort: 'SIZE_ASC' }), ['3', '4', '2', '1'].filter((x, _i) => true).sort((a, b) => hits.find((h) => h.id === a).size - hits.find((h) => h.id === b).size || hits.find((h) => h.id === b).seeders - hits.find((h) => h.id === a).seeders));
  assert.deepEqual(ids({ sort: 'SEEDS_DESC' }), ['3', '1', '2', '4']);
});

test('нерелевантное и не видео отсеивается', () => {
  const hits = [hit('1', 'Cyberpunk 2077 [v 2.13] (2020) PC | RePack от FitGirl', 60 * GB, 100), hit('2', 'Дюна (2021) 1080p', 5 * GB, 3), hit('3', 'Дюна тизер-трейлер', 1e8, 9)];
  assert.deepEqual(S.filter(hits, { query: 'Дюна' }).map((h) => h.id), ['2']);
});

test('релевантность: год в запросе решает', () => {
  const a = hit('a', 'Дюна / Dune (1984) BDRip 1080p', 5 * GB, 5), b = hit('b', 'Дюна / Dune (2021) BDRip 1080p', 5 * GB, 5);
  assert.ok(S.relevance(b, 'Дюна 2021') > S.relevance(a, 'Дюна 2021'));
  assert.deepEqual(S.filter([a, b], { query: 'Дюна 2021' }).map((h) => h.id), ['b', 'a']);
});

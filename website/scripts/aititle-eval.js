"use strict";

// Прогон «Определить через ИИ» на настоящей модели: набор раздач с ожидаемым ответом, вызов тем же путём, что сервер
// (services/aititle.js: suggestMany — промпт, схема, проверка ответа). Ключ — GEMINI_API_KEY (или GEMINI_API).
// Запуск: node scripts/aititle-eval.js [фильтр по имени случая]. Ничего не пишет в данные сервера (временная папка).
// Печатает сырой ответ модели и итог после проверки; в конце — сколько совпало по типу, сезону и названию.

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-aititle-eval-"));
process.env.GEMINI_API_KEY = process.env.GEMINI_API_KEY || process.env.GEMINI_API || "";
process.env.AI_TITLE_COOLDOWN_MIN = "0";
process.env.AI_TITLE_DAILY_MAX = "100000";
if (!process.env.GEMINI_API_KEY) {
  console.error("Нужен ключ: GEMINI_API_KEY или GEMINI_API.");
  process.exit(1);
}
console.log = (...a) => { if (!String(a[0]).startsWith("[aititle]")) process.stdout.write(a.join(" ") + "\n"); };
console.warn = () => {};

const ai = require("../src/services/aititle");

const GB = 1024 ** 3;
const MB = 1024 ** 2;
let nextId = 1;
const f = (p, size = 1.5 * GB) => ({ path: p, type: "file", size });
/** Раздача: name, files (пути или [путь, размер]), want: { kind, season, base: RegExp } */
const R = (name, files, want) => ({ id: nextId++, name, files: files.map(x => Array.isArray(x) ? f(x[0], x[1]) : f(x)), want });
const eps = (fmt, n, from = 1) => Array.from({ length: n }, (_, i) => fmt(String(i + from).padStart(2, "0"), i + from));

// Случаи: одна раздача или несколько (версии одной плитки — одним вызовом, как в приложениях)
const CASES = [
  ["Коллекция по годам (Гарри Поттер)", [R("Гарри Поттер: Коллекция / Harry Potter Collection (2001-2011) BDRip 1080p", [
    "HP/Harry.Potter.and.the.Philosophers.Stone.2001.1080p.BluRay.mkv", "HP/Harry.Potter.and.the.Chamber.of.Secrets.2002.1080p.BluRay.mkv",
    "HP/Harry.Potter.and.the.Prisoner.of.Azkaban.2004.1080p.BluRay.mkv", "HP/Harry.Potter.and.the.Goblet.of.Fire.2005.1080p.BluRay.mkv",
    "HP/Harry.Potter.and.the.Order.of.the.Phoenix.2007.1080p.BluRay.mkv", "HP/Harry.Potter.and.the.Half-Blood.Prince.2009.1080p.BluRay.mkv",
    "HP/Harry.Potter.and.the.Deathly.Hallows.Part.1.2010.1080p.BluRay.mkv", "HP/Harry.Potter.and.the.Deathly.Hallows.Part.2.2011.1080p.BluRay.mkv"
  ], { kind: "collection", season: null, base: /гарри поттер|harry potter/i })]],
  ["Коллекция: в файлах только годы", [R("Миссия невыполнима (1996-2018) 6 фильмов", [
    "MI/1996.mkv", "MI/2000.mkv", "MI/2006.mkv", "MI/2011.mkv", "MI/2015.mkv", "MI/2018.mkv"
  ], { kind: "collection", season: null, base: /миссия невыполнима|mission/i })]],
  ["Коллекция с номерами частей", [R("Пила / Saw 1-10 (2004-2023) BDRip", eps((e, i) => `Saw/Пила ${i} (${2003 + i}).mkv`, 10), { kind: "collection", season: null, base: /пила|saw/i })]],
  ["Трилогия без лет", [R("Властелин колец: Трилогия [Extended Edition] 2160p", [
    "LOTR/01. The Fellowship of the Ring.mkv", "LOTR/02. The Two Towers.mkv", "LOTR/03. The Return of the King.mkv"
  ], { kind: "collection", season: null, base: /властелин колец|lord of the rings/i })]],

  ["Сериал по годам: папки = годы выхода сезонов", [R("Фарго / Fargo (2014-2023) WEB-DL 1080p", [
    ...eps(e => `Fargo/2014/Fargo.2014.E${e}.mkv`, 3), ...eps(e => `Fargo/2015/Fargo.2015.E${e}.mkv`, 3), ...eps(e => `Fargo/2017/Fargo.2017.E${e}.mkv`, 3)
  ], { kind: "series", season: null, base: /фарго|fargo/i })]],
  ["Сериал: в названии только год, сезон 1", [R("Чернобыль / Chernobyl (2019) WEB-DL 1080p", eps(e => `Chernobyl/Chernobyl.E${e}.1080p.mkv`, 5), { kind: "series", season: 1, base: /чернобыль|chernobyl/i })]],
  ["Сериал: год в названии раздачи, сезон 3 по SxxEyy", [R("Мандалорец (2023) 1080p", eps(e => `Mandalorian.S03E${e}.2023.1080p.mkv`, 8), { kind: "series", season: 3, base: /мандалорец|mandalorian/i })]],
  ["Сериал: год сезона вместо номера", [R("Доктор Кто (2024) / Doctor Who 2024 WEB-DL", eps(e => `Doctor.Who.2024.E${e}.mkv`, 8), { kind: "series", season: [1, null], base: /доктор кто|doctor who/i })]],
  ["Название = год (1923)", [R("1923 S02 2160p WEB-DL", eps(e => `1923.S02E${e}.2160p.mkv`, 8), { kind: "series", season: 2, base: /^1923$/ })]],
  ["Фильм, название = год (2012)", [R("2012 (2009) BDRip 1080p", ["2012.2009.BDRip.1080p.mkv"], { kind: "movie", season: null, base: /^2012$/ })]],
  ["Фильм с годом", [R("Дюна / Dune (2021) UHD BDRemux 2160p", [["Dune.2021.UHD.BDRemux.2160p.mkv", 60 * GB], ["Extras/Trailer.mkv", 200 * MB]], { kind: "movie", season: null, base: /дюна|dune/i })]],
  ["Фильм-сиквел с цифрой", [R("Тачки 2 / Cars 2 (2011) BDRip 1080p", ["Cars.2.2011.BDRip.1080p.mkv"], { kind: "movie", season: null, base: /тачки 2|cars 2/i })]],
  ["Фильм из двух частей-файлов", [R("Аватар / Avatar (2009) Extended DVDRip", [["Avatar.2009.Extended.CD1.avi", 700 * MB], ["Avatar.2009.Extended.CD2.avi", 700 * MB]], { kind: "movie", season: null, base: /аватар|avatar/i })]],

  ["Сериал «Название 2 - 01» (сезон цифрой)", [R("Cyberpunk Edgerunners 2 [2160p WEB-DL DV HDR10]", eps(e => `Cyberpunk Edgerunners 2 [2160p WEB-DL DV HDR10]/Cyberpunk Edgerunners 2 - ${e} [2160p WEB-DL DV HDR10].mkv`, 10), { kind: "series", season: 2, base: /киберпанк|cyberpunk/i })]],
  ["Сериал без пометок сезона (первый)", [R("Cyberpunk - Edgerunners [2022] [WEB-DL] [Dolby Vision] [1080p] [RUS + JAP] [Multi-14]", eps(e => `Cyberpunk - Edgerunners [2022]/Cyberpunk - Edgerunners - ${e} (WEB-DL 1920x1080 HEVC DV AAC Rus + Jap).mkv`, 10), { kind: "series", season: 1, base: /киберпанк|cyberpunk/i })]],
  ["Аниме: «Final Season Part 2»", [R("Attack on Titan The Final Season Part 2 [1080p]", eps(e => `[SubsPlease] Shingeki no Kyojin (The Final Season) - ${Number(e) + 75} (1080p).mkv`, 12), { kind: "series", season: 4, base: /атака титанов|attack on titan|shingeki/i })]],
  ["Аниме, сезон словом", [R("Sousou no Frieren 2nd Season [1080p]", eps(e => `[SubsPlease] Sousou no Frieren S2 - ${e} (1080p).mkv`, 10), { kind: "series", season: 2, base: /фрирен|frieren|провожающая в последний путь/i })]],
  ["Транслит с точками", [R("Kak.priruchit.lisu.S01.2025.WEB-DL.1080p.ExKinoRay", eps(e => `Kak.priruchit.lisu.S01E${e}.2025.WEB-DL.1080p.mkv`, 8), { kind: "series", season: 1, base: /как приручить лису/i })]],
  ["Русская раздача «N сезон 1-24 серии»", [R("Ходячие мертвецы / The Walking Dead [11 сезон: 1-24 серии из 24] (2021) WEB-DLRip", eps(e => `TWD.S11/The.Walking.Dead.S11E${e}.mkv`, 24), { kind: "series", season: 11, base: /ходячие мертвецы|walking dead/i })]],
  ["Все сезоны в одной раздаче", [R("Во все тяжкие / Breaking Bad S01-S05 Complete 1080p", [
    ...eps(e => `BB/Season 1/Breaking.Bad.S01E${e}.mkv`, 3), ...eps(e => `BB/Season 2/Breaking.Bad.S02E${e}.mkv`, 3), ...eps(e => `BB/Season 5/Breaking.Bad.S05E${e}.mkv`, 3)
  ], { kind: "series", season: null, base: /во все тяжкие|breaking bad/i })]],
  ["Русский сериал «1-10 серии из 10» с годом", [R("Мастер и Маргарита (2005) DVDRip [1-10 серии из 10]", eps(e => `Master.i.Margarita.2005.${e}.avi`, 10), { kind: "series", season: 1, base: /мастер и маргарита/i })]],
  ["Шоу по датам выпусков", [R("Вечерний Ургант (2021) [выпуски за март]", ["VU/Vecherniy.Urgant.2021.03.01.mp4", "VU/Vecherniy.Urgant.2021.03.02.mp4", "VU/Vecherniy.Urgant.2021.03.03.mp4", "VU/Vecherniy.Urgant.2021.03.04.mp4"], { kind: "series", season: null, base: /ургант/i })]],
  ["Ремейк: год различает, сезон 1", [R("Сёгун / Shogun (2024) S01 2160p", eps(e => `Shogun.2024.S01E${e}.2160p.mkv`, 10), { kind: "series", season: 1, base: /сёгун|сегун|shogun/i })]],

  ["Фильм: год в самом названии", [R("Бегущий по лезвию 2049 / Blade Runner 2049 (2017) UHD 2160p", ["Blade.Runner.2049.2017.2160p.mkv"], { kind: "movie", season: null, base: /2049/ })]],
  ["Фильм «Часть вторая»", [R("Дюна: Часть вторая / Dune: Part Two (2024) WEB-DL 2160p", ["Dune.Part.Two.2024.2160p.mkv"], { kind: "movie", season: null, base: /дюна|dune/i })]],
  ["Коллекция «1-10» с годами", [R("Форсаж / Fast & Furious 1-10 (2001-2023) BDRip 1080p", eps((e, i) => `FF/${e}. Fast.and.Furious.${2000 + i * 2}.mkv`, 10), { kind: "collection", season: null, base: /форсаж|fast/i })]],
  ["Коллекция римскими эпизодами", [R("Звёздные войны: Эпизоды I-IX / Star Wars (1977-2019) 2160p", ["SW/Star.Wars.Episode.I.1999.mkv", "SW/Star.Wars.Episode.II.2002.mkv", "SW/Star.Wars.Episode.III.2005.mkv", "SW/Star.Wars.Episode.IV.1977.mkv", "SW/Star.Wars.Episode.V.1980.mkv"], { kind: "collection", season: null, base: /звёздные войны|звездные войны|star wars/i })]],
  ["Аниме: римский сезон и число в названии", [R("Mob Psycho 100 III [BDRip 1080p]", eps(e => `[Erai-raws] Mob Psycho 100 III - ${e} [1080p].mkv`, 12), { kind: "series", season: 3, base: /моб|mob psycho 100/i })]],
  ["Сезоны диапазоном в названии", [R("Твин Пикс / Twin Peaks (1990-1991) Season 1-2 DVDRip", [...eps(e => `TP/S1/Twin.Peaks.1x${e}.avi`, 3), ...eps(e => `TP/S2/Twin.Peaks.2x${e}.avi`, 3)], { kind: "series", season: null, base: /твин пикс|twin peaks/i })]],
  ["Все сезоны без папок", [R("Друзья / Friends (1994-2004) Complete Series BDRip", [...eps(e => `Friends.S01E${e}.mkv`, 3), ...eps(e => `Friends.S10E${e}.mkv`, 3)], { kind: "series", season: null, base: /друзья|friends/i })]],
  ["Русский сериал «1-8 серии»", [R("Слово пацана. Кровь на асфальте (2023) WEB-DL 1080p [1-8 серии из 8]", eps(e => `Slovo.patsana.2023.E${e}.WEB-DL.1080p.mkv`, 8), { kind: "series", season: 1, base: /слово пацана/i })]],
  ["Сериал: у файлов только год", [R("Медведь / The Bear (2022) WEB-DL 1080p", eps(e => `The.Bear.2022.${e}.mkv`, 8), { kind: "series", season: 1, base: /медведь|the bear/i })]],
  ["Сериал: сезон и год в названии раздачи", [R("Разделение / Severance S02 (2025) 2160p", eps(e => `Severance.S02E${e}.2160p.mkv`, 10), { kind: "series", season: 2, base: /разделение|severance/i })]],

  ["Плитка: сезоны с разным неймингом (одним вызовом)", [
    R("Тед Лассо / Ted Lasso [S01] (2020) WEB-DL 1080p", eps(e => `Ted.Lasso.S01E${e}.mkv`, 10), { kind: "series", season: 1, base: /тед лассо|ted lasso/i }),
    R("Ted Lasso Season 2 (2021) 2160p", eps(e => `Ted Lasso Season 2/Ted Lasso - ${e}.mkv`, 12), { kind: "series", season: 2, base: /тед лассо|ted lasso/i }),
    R("Тед Лассо 3 сезон 1-12 серии (2023)", eps(e => `Тед Лассо (2023)/Тед Лассо ${e}.mkv`, 12), { kind: "series", season: 3, base: /тед лассо|ted lasso/i }),
    // 2023 — год третьего сезона: модель знает это сама, годится и 3, и «не знаю»
    R("Ted.Lasso.2023.1080p.WEB-DL", eps(e => `Ted.Lasso.2023.E${e}.mkv`, 12), { kind: "series", season: [3, null], base: /тед лассо|ted lasso/i })
  ]],
  ["Плитка: фильм и сборник одного названия", [
    R("Матрица / The Matrix (1999) BDRip 1080p", ["The.Matrix.1999.1080p.mkv"], { kind: "movie", season: null, base: /матрица|matrix/i }),
    R("Матрица: Трилогия (1999-2003) BDRip 1080p", ["The.Matrix.1999.mkv", "The.Matrix.Reloaded.2003.mkv", "The.Matrix.Revolutions.2003.mkv"], { kind: "collection", season: null, base: /матрица|matrix/i })
  ]]
];

// want.season может быть списком допустимых ответов
// Оригинальное название и год (для постера в TMDB) — по началу имени раздачи. year списком — допустимые ответы.
const ORIGINAL = [
  ["Гарри Поттер: Коллекция", /harry potter/i, 2001],
  ["Миссия невыполнима", /mission.*impossible/i, 1996],
  ["Фарго", /^fargo$/i, 2014],
  ["Чернобыль", /^chernobyl$/i, 2019],
  ["Мандалорец", /mandalorian/i, 2019],
  ["Доктор Кто", /doctor who/i, [1963, 2005, 2023, 2024]],
  ["1923 S02", /^1923$/, 2022],
  ["2012 (2009)", /^2012$/, 2009],
  ["Дюна / Dune (2021)", /^dune$/i, 2021],
  ["Тачки 2", /^cars 2$/i, 2011],
  ["Cyberpunk Edgerunners 2", /cyberpunk.*edgerunners/i, [2022, 2025, 2026]],
  ["Cyberpunk - Edgerunners", /cyberpunk.*edgerunners/i, 2022],
  ["Attack on Titan", /attack on titan/i, 2013],
  ["Sousou no Frieren", /frieren/i, 2023],
  ["Ходячие мертвецы", /walking dead/i, 2010],
  ["Во все тяжкие", /breaking bad/i, 2008],
  ["Мастер и Маргарита (2005)", /мастер и маргарита/i, 2005],
  ["Сёгун / Shogun (2024)", /sh[oō]gun/i, 2024],
  ["Тед Лассо", /ted lasso/i, 2020],
  ["Ted Lasso", /ted lasso/i, 2020],
  ["Ted.Lasso", /ted lasso/i, 2020],
  ["Матрица / The Matrix", /^the matrix$/i, 1999],
  ["Матрица: Трилогия", /matrix/i, 1999],
  ["Бегущий по лезвию 2049", /blade runner 2049/i, 2017],
  ["Дюна: Часть вторая", /dune.*part two/i, 2024],
  ["Mob Psycho 100", /mob psycho 100/i, 2016],
  ["Слово пацана", /слово пацана/i, 2023],
  ["Медведь / The Bear", /^the bear$/i, 2022],
  ["Разделение / Severance", /^severance$/i, 2022],
  ["Друзья / Friends", /^friends$/i, 1994]
];

function same(got, want) {
  if (Array.isArray(want)) return want.some(w => same(got, w));
  return got === want || (got == null && want == null);
}

(async () => {
  const filter = (process.argv[2] || "").toLowerCase();
  const raw = new Map(); // id раздачи -> сырой ответ модели
  const realFetch = globalThis.fetch;
  let batch = [];
  globalThis.fetch = async (url, init) => {
    const res = await realFetch(url, init);
    const body = await res.clone().json().catch(() => null);
    const text = body?.candidates?.[0]?.content?.parts?.map(p => p.text || "").join("") || "";
    let items = [];
    try { items = JSON.parse(text).items || []; } catch {}
    for (const it of items) if (batch[it.n - 1]) raw.set(batch[it.n - 1].id, it);
    const u = body?.usageMetadata;
    if (u) process.stdout.write(`   токены: вход ${u.promptTokenCount}, выход ${u.candidatesTokenCount}\n`);
    return res;
  };
  let total = 0, okKind = 0, okSeason = 0, okBase = 0, okAll = 0, origTotal = 0, okOrig = 0, okYear = 0;
  const fails = [];
  for (const [title, releases] of CASES) {
    if (filter && !title.toLowerCase().includes(filter)) continue;
    console.log(`\n■ ${title}`);
    batch = releases;
    let out;
    for (let attempt = 1; ; attempt++) {
      try { out = await ai.suggestMany(releases, "u_1", Date.now(), { noCooldown: true, force: true }); break; }
      catch (err) {
        // 429 — лимит запросов в минуту у ключа: ждём и повторяем
        if (err.code === "busy" && attempt < 6) { await new Promise(r => setTimeout(r, 10000 * attempt)); continue; }
        console.log(`   ОШИБКА: ${err.code} ${err.status || ""} ${err.message}`);
        out = { results: [] };
        break;
      }
    }
    for (const r of releases) {
      const got = out.results.find(x => x.id === r.id)?.aiTitle || {};
      const m = raw.get(r.id) || {};
      // слова сборника в названии мешают слить его с фильмом той же франшизы в одну плитку
      const k = same(got.kind, r.want.kind), s = same(got.season, r.want.season);
      const b = r.want.base.test(got.base || "") && !/трилоги|коллекци|сборник|collection|trilogy/i.test(got.base || "");
      total++; okKind += k; okSeason += s; okBase += b; okAll += k && s && b;
      const mark = k && s && b ? "✓" : "✗";
      console.log(`   ${mark} ${r.name}`);
      console.log(`       модель: ${JSON.stringify({ base: m.base, season: m.season, kind: m.kind, original: m.original, year: m.year })}`);
      const og = ORIGINAL.find(([prefix]) => r.name.startsWith(prefix));
      if (og) {
        origTotal++;
        const o = og[1].test(got.original || ""), y = same(got.year, og[2]);
        okOrig += o; okYear += y;
        if (!o || !y) {
          console.log(`       ✗ постер: «${got.original} (${got.year})», ждём ${og[1]} (${og[2]})`);
          fails.push(`${title}: ${r.name} → постер «${got.original} (${got.year})», ждём ${og[1]} (${og[2]})`);
        }
      }
      console.log(`       итог:   «${got.base}» ${got.kind}, сезон ${got.season}   ждём: ${r.want.kind}, сезон ${r.want.season}${b ? "" : ", название " + r.want.base}`);
      if (!(k && s && b)) fails.push(`${title}: ${r.name} → ${got.kind}/${got.season}/«${got.base}» (модель ${m.kind}/${m.season}), ждём ${r.want.kind}/${r.want.season}`);
    }
  }
  console.log(`\nИтого раздач: ${total}. Всё верно: ${okAll}. Тип: ${okKind}, сезон: ${okSeason}, название: ${okBase}.`);
  console.log(`Для постера (${origTotal} раздач): оригинальное название ${okOrig}, год ${okYear}.`);
  if (fails.length) console.log("Ошибки:\n  " + fails.join("\n  "));
})();

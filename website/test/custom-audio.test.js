"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-audio-test-"));

const test = require("node:test");
const assert = require("node:assert/strict");

const ca = require("../src/services/custom-audio");

const f = (p, size) => ({ path: p, type: "file", size, sourceLink: "https://alldebrid.com/f/x" });

test("имена: примеры из стандарта", () => {
  const p = n => {
    const r = ca.parseAudioName(n);
    return [r.title, r.year, r.season, r.episode, r.voice, r.delayMs];
  };
  assert.deepEqual(p("Inception.2010.RedHeadSound[delay=-4200ms].mka"), ["Inception", 2010, null, null, "RedHeadSound", -4200]);
  assert.deepEqual(p("Dune.Part.Two.2024.FlarrowFilms.ac3"), ["Dune Part Two", 2024, null, null, "FlarrowFilms", null]);
  assert.deepEqual(p("Оппенгеймер (2023)/Дубляж RHS[delay=-3500ms].mka"), ["Оппенгеймер", 2023, null, null, "Дубляж RHS", -3500]);
  assert.deepEqual(p("Severance.S01E01.HDRezka.mka"), ["Severance", null, 1, 1, "HDRezka", null]);
  assert.deepEqual(p("The.Boys.S04E08.LostFilm[delay=-1500ms].mka"), ["The Boys", null, 4, 8, "LostFilm", -1500]);
  assert.deepEqual(p("Severance/Season 01/S01E02.HDRezka[delay=+300ms].mka"), ["Severance", null, 1, 2, "HDRezka", 300]);
});

test("имена: технические теги вычищаются, неопознанное — без названия", () => {
  const r = ca.parseAudioName("Movie.Name.2019.1080p.WEB-DL.DDP5.1.Studio.eac3");
  assert.equal(r.title, "Movie Name");
  assert.equal(r.year, 2019);
  assert.equal(ca.parseAudioName("track.mka").title, null);
});

test("чекер: только белый список аудио (.mka)", () => {
  assert.equal(ca.checkFormats(["a/1.mka", "b/2.MKA"]).ok, true);
  const rAudio = ca.checkFormats(["a.ac3", "b.flac", "c.wav", "d.mp3"]);
  assert.equal(rAudio.ok, false);
  assert.equal(rAudio.forbidden.length, 4);
  const r = ca.checkFormats(["a.mka", "movie.mkv", "x.exe", "pack.rar", "disc.iso", "readme.txt"]);
  assert.deepEqual(r.forbidden.map(x => x.kind), ["video", "executable", "archive", "image", "other"]);
});

test("сопоставление: фильм, один видеофайл", () => {
  const movie = { name: "Inception.2010.1080p", files: [f("Inception.2010.1080p/Inception.2010.mkv", 9e9), f("Inception.2010.1080p/sample.nfo")] };
  const m = ca.matchToMovie([f("Inception.2010.RHS[delay=-4200ms].mka")], movie);
  assert.equal(m.ok, true);
  assert.equal(m.pairs[0].videoPath, "Inception.2010.1080p/Inception.2010.mkv");
  assert.equal(m.pairs[0].offsetMs, -4200);
});

test("сопоставление: фильм не из библиотеки — ошибка", () => {
  const movie = { name: "Dune 2021", files: [f("Dune.2021.mkv", 1)] };
  const m = ca.matchToMovie([f("Inception.2010.RHS.mka")], movie);
  assert.equal(m.ok, false);
  assert.equal(m.errors[0].code, "TITLE_MISMATCH");
  assert.match(m.errors[0].message, /Фильм Inception не найден в библиотеке\. Сначала добавьте раздачу с фильмом\./);
});

test("сопоставление: год не совпал — ошибка", () => {
  const movie = { name: "Dune 2021", files: [f("Dune.2021.mkv", 1)] };
  assert.equal(ca.matchToMovie([f("Dune.1984.RHS.mka")], movie).errors[0].code, "TITLE_MISMATCH");
});

test("сопоставление: серии, которых нет в раздаче фильма", () => {
  const movie = { name: "Severance", files: [1, 2, 3].map(n => f(`Severance/Season 01/Severance.S01E0${n}.mkv`, 1e9)) };
  const ok = ca.matchToMovie([1, 2].map(n => f(`Severance.S01E0${n}.HDRezka.mka`)), movie);
  assert.equal(ok.ok, true);
  assert.deepEqual(ok.pairs.map(p => p.episode), [1, 2]);
  const bad = ca.matchToMovie([f("Severance.S01E01.HDRezka.mka"), f("Severance.S01E10.HDRezka.mka")], movie);
  assert.equal(bad.ok, false);
  assert.equal(bad.errors[0].code, "EPISODE_MISMATCH");
  assert.deepEqual(bad.errors[0].episodes, ["S01E10"]);
  assert.equal(bad.pairs.length, 0);
});

test("сопоставление: файл без определимой цели блокирует всю раздачу", () => {
  const movie = { name: "Inception 2010", files: [f("Inception.2010.mkv", 1)] };
  const m = ca.matchToMovie([f("Inception.2010.RHS.mka"), f("track2.mka")], movie);
  assert.equal(m.ok, false);
  assert.equal(m.errors[0].code, "UNRESOLVED");
});

test("сопоставление: несколько видео у фильма — берётся заметно самое большое", () => {
  const movie = { name: "Inception 2010", files: [f("Inception.2010.mkv", 9e9), f("extras/making.mkv", 1e8)] };
  assert.equal(ca.matchToMovie([f("Inception.2010.RHS.mka")], movie).pairs[0].videoPath, "Inception.2010.mkv");
  const same = { name: "Inception 2010", files: [f("a.mkv", 5e9), f("b.mkv", 5e9)] };
  assert.equal(ca.matchToMovie([f("Inception.2010.RHS.mka")], same).errors[0].code, "UNRESOLVED");
});

test("список файлов из .torrent", () => {
  const b = s => Buffer.from(s, "utf8");
  const str = s => `${b(s).length}:${s}`;
  const file = (name, len) => `d6:lengthi${len}e4:pathl${str(name)}ee`;
  const torrent = b(`d4:infod5:filesl${file("1.mka", 10)}${file("2.mka", 20)}e4:name${str("Дубляж")}ee`);
  assert.deepEqual(ca.torrentFiles(torrent).files.map(x => x.path), ["Дубляж/1.mka", "Дубляж/2.mka"]);
  assert.throws(() => ca.torrentFiles(b("not a torrent")));
});

test("хранилище: ключ magnetId|videoPath, повтор не дублирует, смещение, очистка", () => {
  ca._reset();
  const pair = { videoPath: "m.mkv", audioPath: "Dub/a.mka", voice: "HDRezka [rus]", offsetMs: -4200 };
  const r1 = ca.addTracks({ movieMagnetId: 7, audioMagnetId: 8, pairs: [pair], author: "u_1" });
  assert.equal(r1.created.length, 1);
  const t = r1.created[0];
  assert.match(t.id, /^trk_[a-f0-9]{8}$/);
  assert.deepEqual([t.title, t.lang, t.offsetMs, t.addedBy], ["HDRezka [rus]", "rus", -4200, "u_1"]);
  assert.equal(ca.addTracks({ movieMagnetId: 7, audioMagnetId: 8, pairs: [pair], author: "u_2" }).existing.length, 1);
  assert.deepEqual(ca.linkedAudioMagnets(7), [8]);
  assert.equal(ca.setOffset(t.id, 120).offsetMs, 120);
  assert.equal(ca.getTrack("zzz"), null);
  assert.equal(ca.parseOffset(60001), null);
  assert.equal(ca.dropMagnet(7), 1); // удалили фильм
  assert.equal(ca.forMovie(7).length, 0);
  ca.addTracks({ movieMagnetId: 9, audioMagnetId: 8, pairs: [pair], author: "u_1" });
  assert.equal(ca.dropMagnet(8), 1); // удалили раздачу с озвучкой
  assert.equal(ca.forMovie(9).length, 0);

  // removeTrack & hasAudioMagnetTracks
  assert.equal(ca.hasAudioMagnetTracks(88), false);
  const rNew = ca.addTracks({ movieMagnetId: 10, audioMagnetId: 88, pairs: [pair], author: "u_1" });
  const newTrack = rNew.created[0];
  assert.equal(ca.hasAudioMagnetTracks(88), true);
  assert.equal(ca.removeTrack("invalid"), null);
  assert.equal(ca.removeTrack("trk_00000000"), null);
  const removed = ca.removeTrack(newTrack.id);
  assert.equal(removed.id, newTrack.id);
  assert.equal(ca.getTrack(newTrack.id), null);
  assert.equal(ca.hasAudioMagnetTracks(88), false);
});


test("имена: фильмы и сериалы со сложными путями и годами", () => {
  // Фильм с годом в названии
  const r2001 = ca.parseAudioName("2001.A.Space.Odyssey.1968.Dub.mka");
  assert.equal(r2001.title, "2001 A Space Odyssey");
  assert.equal(r2001.year, 1968);
  assert.equal(r2001.voice, "Dub");

  // Папка S01 в сериале
  const s01 = ca.parseAudioName("Severance/S01/S01E01.HDRezka.mka");
  assert.equal(s01.title, "Severance");
  assert.equal(s01.season, 1);
  assert.equal(s01.episode, 1);

  // Серии в подпапках сезона Season 02/01.mkv
  const movie = {
    name: "The Boys",
    files: [
      f("The Boys/Season 01/01.mkv", 1e9),
      f("The Boys/Season 02/01.mkv", 1e9)
    ]
  };
  const m = ca.matchToMovie([f("The.Boys.S02E01.LostFilm.mka", 1e8)], movie);
  assert.equal(m.ok, true);
  assert.equal(m.pairs[0].videoPath, "The Boys/Season 02/01.mkv");

  // Ровно 1.5x размер
  const movie15 = {
    name: "Movie 2024",
    files: [
      f("Movie.2024.mkv", 1500),
      f("Extra.mkv", 1000)
    ]
  };
  const m15 = ca.matchToMovie([f("Movie.2024.RHS.mka", 100)], movie15);
  assert.equal(m15.ok, true);
  assert.equal(m15.pairs[0].videoPath, "Movie.2024.mkv");
});

test("маршруты custom-audio: проверка без авторизации", async t => {
  const { createApp } = require("../src/app");
  const server = createApp().listen(0, "127.0.0.1");
  t.after(() => server.close());
  await new Promise(resolve => server.once("listening", resolve));
  const base = `http://127.0.0.1:${server.address().port}`;

  // Без авторизации — 401
  const r1 = await fetch(`${base}/api/custom-audio/check`, { method: "POST" });
  assert.equal(r1.status, 401);

  const r2 = await fetch(`${base}/api/custom-audio/123`);
  assert.equal(r2.status, 401);

  const r3 = await fetch(`${base}/api/custom-audio/trk_12345678/offset`, { method: "PUT" });
  assert.equal(r3.status, 401);

  const r4 = await fetch(`${base}/api/custom-audio/trk_12345678`, { method: "DELETE" });
  assert.equal(r4.status, 401);
});

test("маршруты custom-audio: удаление дорожки (DELETE /api/custom-audio/:trackId)", async t => {
  const users = require("../src/services/users");
  const userA = users.findOrCreateUser({ id: "901", username: "authorA" }, "allow");
  const pA = users.createDevicePairing("Desktop A");
  users.authorizeDevicePairing(pA.user_code, userA.id);
  const tokenA = users.pollDevicePairing(pA.code).device_token;

  const userB = users.findOrCreateUser({ id: "902", username: "authorB" }, "allow");
  const pB = users.createDevicePairing("Desktop B");
  users.authorizeDevicePairing(pB.user_code, userB.id);
  const tokenB = users.pollDevicePairing(pB.code).device_token;

  const admin = users.findOrCreateUser({ id: "903", username: "adminUser" }, "admin");
  const pAdmin = users.createDevicePairing("Admin");
  users.authorizeDevicePairing(pAdmin.user_code, admin.id);
  const tokenAdmin = users.pollDevicePairing(pAdmin.code).device_token;

  const { createApp } = require("../src/app");
  const server = createApp().listen(0, "127.0.0.1");
  t.after(() => server.close());
  await new Promise(resolve => server.once("listening", resolve));
  const base = `http://127.0.0.1:${server.address().port}`;

  // 404 если дорожка не существует
  const r404 = await fetch(`${base}/api/custom-audio/trk_00000000`, {
    method: "DELETE",
    headers: { "X-Device-Token": tokenA }
  });
  assert.equal(r404.status, 404);

  // Создаем дорожку от имени userA
  const pair = { videoPath: "movie.mkv", audioPath: "audio.mka", voice: "RHS", offsetMs: 0 };
  const created = ca.addTracks({ movieMagnetId: 100, audioMagnetId: 200, pairs: [pair], author: userA.id });
  const trackA = created.created[0];
  assert.ok(trackA && trackA.id);

  // Пользователь B пытается удалить чужую дорожку -> 403
  const r403 = await fetch(`${base}/api/custom-audio/${trackA.id}`, {
    method: "DELETE",
    headers: { "X-Device-Token": tokenB }
  });
  assert.equal(r403.status, 403);
  assert.ok(ca.getTrack(trackA.id));

  // Автор дорожки (userA) удаляет -> 200
  const rDelA = await fetch(`${base}/api/custom-audio/${trackA.id}`, {
    method: "DELETE",
    headers: { "X-Device-Token": tokenA }
  });
  assert.equal(rDelA.status, 200);
  const jsonA = await rDelA.json();
  assert.equal(jsonA.ok, true);
  assert.equal(jsonA.id, trackA.id);
  assert.equal(ca.getTrack(trackA.id), null);

  // Админ может удалить чужую дорожку
  const created2 = ca.addTracks({ movieMagnetId: 100, audioMagnetId: 201, pairs: [pair], author: userA.id });
  const trackA2 = created2.created[0];
  const rDelAdmin = await fetch(`${base}/api/custom-audio/${trackA2.id}`, {
    method: "DELETE",
    headers: { "X-Device-Token": tokenAdmin }
  });
  assert.equal(rDelAdmin.status, 200);
  const jsonAdmin = await rDelAdmin.json();
  assert.equal(jsonAdmin.ok, true);
  assert.equal(jsonAdmin.id, trackA2.id);
  assert.equal(ca.getTrack(trackA2.id), null);
});

test("ревью: папки сезонов S01, серии 01.Озвучка, год в папке без скобок, тег [rus] в названии", () => {
  const p = n => {
    const r = ca.parseAudioName(n);
    return [r.title, r.year, r.season, r.episode, r.voice];
  };
  assert.deepEqual(p("Severance/S01/01.mka"), ["Severance", null, 1, 1, ""]);
  assert.deepEqual(p("Severance/S02/E03.HDRezka.mka"), ["Severance", null, 2, 3, "HDRezka"]);
  assert.deepEqual(p("Severance/Season 01/01.HDRezka.mka"), ["Severance", null, 1, 1, "HDRezka"]);
  assert.deepEqual(p("Inception.2010/Voice.mka"), ["Inception", 2010, null, null, "Voice"]);
  assert.deepEqual(p("Inception 2010 1080p/RHS.mka"), ["Inception", 2010, null, null, "RHS"]);
  const r = ca.parseAudioName("Severance.[rus].S01E01.HDRezka.mka");
  assert.deepEqual([r.title, r.lang, r.voice], ["Severance", "rus", "HDRezka"]);
  const m = ca.matchToMovie([f("Severance.[rus].S01E01.HDRezka.mka")], { name: "Severance", files: [f("Severance.S01E01.mkv", 1)] });
  assert.equal(m.ok, true);
  assert.equal(m.pairs[0].lang, "rus");
});

test("ревью: сезон 2 не цепляется к сериям без сезона, видео 01.1080p — серия", () => {
  const show = { name: "Show", files: [f("Show/01.1080p.mkv", 1), f("Show/02 - Pilot.mkv", 1)] };
  assert.equal(ca.matchToMovie([f("Show.S01E02.Voice.mka")], show).ok, true);
  const s2 = ca.matchToMovie([f("Show.S02E01.Voice.mka")], show);
  assert.equal(s2.ok, false);
  assert.equal(s2.errors[0].code, "EPISODE_MISMATCH");
});

test("ревью: несколько видео без размеров — фильм не угадывается", () => {
  const r = ca.matchToMovie([f("Movie.2020.Voice.mka")], { name: "Movie 2020", files: [f("Movie.Part1.mkv", null), f("Movie.Part2.mkv", 0)] });
  assert.equal(r.errors[0].code, "UNRESOLVED");
});

test("ревью: короткое название ищется целым словом", () => {
  const up = ca.parseAudioName("Up.2009.Voice.mka");
  assert.equal(ca.titleMatches(up, { name: "Backup.Tools.2009", files: [] }), false);
  assert.equal(ca.titleMatches(up, { name: "Up.2009.1080p", files: [] }), true);
  assert.equal(ca.titleMatches(ca.parseAudioName("Spider-Man.2002.V.mka"), { name: "SpiderMan.2002", files: [] }), true);
});

test("ревью: bdecode без прототипа, строгие смещения", () => {
  const t = Buffer.from("d9:__proto__d4:infoi1ee4:infod4:name3:abc6:lengthi5eee");
  const r = ca.torrentFiles(t);
  assert.equal(r.name, "abc");
  assert.equal(Object.getPrototypeOf(ca.bdecode(t)), null);
  for (const v of [[], [500], " ", "", null, true, {}, "1e3"]) assert.equal(ca.parseOffset(v), null, JSON.stringify(v));
  assert.equal(ca.parseOffset("-4200"), -4200);
  assert.equal(ca.parseOffset(300), 300);
});

test("ревью: индекс дорожек по id после добавления, смещения и удаления", () => {
  ca._reset();
  const { created } = ca.addTracks({
    movieMagnetId: 77,
    audioMagnetId: 78,
    pairs: [1, 2, 3].map(i => ({ videoPath: `S01E0${i}.mkv`, audioPath: `a/S01E0${i}.mka`, voice: "V", offsetMs: null })),
    author: "u_1"
  });
  assert.equal(new Set(created.map(t => t.id)).size, 3);
  const id = created[1].id;
  assert.equal(ca.getTrack(id).videoPath, "S01E02.mkv");
  assert.equal(ca.setOffset(id, 250).offsetMs, 250);
  assert.equal(ca.removeTrack(id).id, id);
  assert.equal(ca.getTrack(id), null);
  ca.dropMagnet(78);
  assert.equal(ca.getTrack(created[0].id), null);
  assert.equal(ca.forMovie(77).length, 0);
});

test("сопоставление: сериал 2 - 01 и явный выбор односезонной раздачи (explicit)", () => {
  const m4 = {
    name: "Cyberpunk Edgerunners 2",
    files: [f("Cyberpunk Edgerunners 2 - 01.mkv", 1e9), f("Cyberpunk Edgerunners 2 - 02.mkv", 1e9)]
  };
  const audios = [f("Cyberpunk.Edgerunners.S02E01.mka"), f("Cyberpunk.Edgerunners.S02E02.mka")];
  // 2 - 01 распознаётся как S02E01 автоматически
  assert.equal(ca.matchToMovie(audios, m4).ok, true);

  // Односезонная раздача без сезонов (- 01) или S01: с explicit: true сопоставляется
  const m3 = {
    name: "Cyberpunk Edgerunners",
    files: [f("Cyberpunk - Edgerunners - 01.mkv", 1e9), f("Cyberpunk - Edgerunners - 02.mkv", 1e9)]
  };
  assert.equal(ca.matchToMovie(audios, m3, { explicit: true }).ok, true);
  assert.equal(ca.matchToMovie(audios, m3, { explicit: false }).ok, false);
});

test("аудит: [delay=…] и offsetMs с типографским минусом и тире", () => {
  assert.equal(ca.parseAudioName("Inception.2010.RHS[delay=−4200ms].mka").delayMs, -4200);
  assert.equal(ca.parseAudioName("Inception.2010.RHS[delay=–300ms].mka").delayMs, -300);
  assert.equal(ca.parseAudioName("Inception.2010.RHS[delay=−4200ms].mka").voice, "RHS");
  assert.equal(ca.parseOffset("−1500"), -1500);
  assert.equal(ca.parseOffset("–20"), -20);
  assert.equal(ca.parseOffset("−"), null);
});

test("аудит: разрешение 1920x1080 в раздаче — не год", () => {
  const movie = { name: "Movie.Name.1920x1080", files: [f("Movie.Name.1920x1080/Movie.Name.1920x1080.mkv", 9e9)] };
  const m = ca.matchToMovie([f("Movie.Name.2023.Dub.mka")], movie);
  assert.equal(m.ok, true);
  // настоящий другой год по-прежнему отклоняется
  const other = { name: "Movie.Name.2019.1920x1080", files: [f("Movie.Name.2019.mkv", 9e9)] };
  assert.equal(ca.matchToMovie([f("Movie.Name.2023.Dub.mka")], other).errors[0].code, "TITLE_MISMATCH");
});

test("аудит: технические метки в имени серии — не номер серии", () => {
  const movie = {
    name: "Show",
    files: [f("Show/Show.01.H.264.mkv", 1e9), f("Show/Show.02.10.bit.mkv", 1e9), f("Show/Show.1080p.60.fps.03.mkv", 1e9)]
  };
  const m = ca.matchToMovie([1, 2, 3].map(n => f(`Show.S01E0${n}.Dub.mka`)), movie);
  assert.equal(m.ok, true);
  assert.deepEqual(m.pairs.map(p => p.videoPath), ["Show/Show.01.H.264.mkv", "Show/Show.02.10.bit.mkv", "Show/Show.1080p.60.fps.03.mkv"]);
});

test("аудит: два года в имени видео — оба не мешают номеру серии", () => {
  const movie = { name: "1917 2019", files: [f("Show.1917.2019.05.mkv", 1e9)] };
  const m = ca.matchToMovie([f("Show.1917.2019.S01E05.Dub.mka")], movie, { explicit: true });
  assert.equal(m.ok, true);
});

test("аудит: тег языка — только код ISO 639", () => {
  assert.equal(ca.parseAudioName("Inception.2010.RHS [HQ].mka").lang, null);
  assert.equal(ca.parseAudioName("Inception.2010.RHS [HQ] [eng].mka").lang, "eng");
  assert.equal(ca.parseAudioName("Inception.2010.RHS [RUS].mka").lang, "rus");
  ca._reset();
  const add = (voice, lang) =>
    ca.addTracks({ movieMagnetId: 31, audioMagnetId: 32, pairs: [{ videoPath: "m.mkv", audioPath: `${voice}.mka`, voice }], author: "u_1", override: lang ? { lang } : {} }).created[0];
  assert.equal(add("Director's Cut [Dub]").lang, "und");
  assert.equal(add("Студия [RAW]").lang, "rus");
  assert.equal(add("Other", "hq").lang, "und");
  assert.equal(add("Other2", "EN").lang, "en");
});

test("аудит: имя раздачи из name.utf-8 в .torrent", () => {
  const b = s => Buffer.from(s, "utf8");
  const str = s => `${b(s).length}:${s}`;
  const torrent = Buffer.concat([
    b(`d4:infod6:lengthi10e4:name${str("x")}10:name.utf-8${str("Дубляж.mka")}ee`)
  ]);
  assert.equal(ca.torrentFiles(torrent).name, "Дубляж.mka");
  assert.equal(ca.torrentFiles(torrent).files[0].path, "Дубляж.mka");
});

test("аудит: длинный путь видео — отказ без записи, лимит на видеофайл, а не на раздачу", () => {
  ca._reset();
  const long = "a/".repeat(320) + "film.mkv";
  assert.throws(
    () => ca.addTracks({ movieMagnetId: 41, audioMagnetId: 42, pairs: [{ videoPath: long, audioPath: "x.mka", voice: "X" }], author: "u_1" }),
    e => e.status === 422 && e.code === "BAD_PATH"
  );
  assert.equal(ca.forMovie(41).length, 0);

  // сериал: по одной озвучке на 150 серий — больше 100 на раздачу, но не больше лимита на файл
  const pairs = Array.from({ length: 150 }, (_, i) => ({ videoPath: `S/E${i}.mkv`, audioPath: `D/E${i}.mka`, voice: "Dub" }));
  assert.equal(ca.addTracks({ movieMagnetId: 43, audioMagnetId: 44, pairs, author: "u_1" }).created.length, 150);

  // на один файл — не больше MAX_PER_VIDEO
  const one = Array.from({ length: ca.MAX_PER_VIDEO + 1 }, (_, i) => ({ videoPath: "m.mkv", audioPath: `D/${i}.mka`, voice: "Dub" }));
  assert.throws(() => ca.addTracks({ movieMagnetId: 45, audioMagnetId: 46, pairs: one, author: "u_1" }), e => e.status === 409 && e.code === "LIMIT");
  assert.equal(ca.forMovie(45).length, 0); // привязка целиком или никак
  assert.equal(ca.addTracks({ movieMagnetId: 45, audioMagnetId: 46, pairs: one.slice(1), author: "u_1" }).created.length, ca.MAX_PER_VIDEO);
});

test("аудит: без снимка библиотеки раздачу не удаляем", () => {
  assert.equal(ca.mayBeLibraryFilm(null, 5), true);
  assert.equal(ca.mayBeLibraryFilm(undefined, 5), true);
  assert.equal(ca.mayBeLibraryFilm([{ id: 5, statusCode: 4 }], 5), true);
  assert.equal(ca.mayBeLibraryFilm([{ id: 5, statusCode: 1 }], 5), false);
  assert.equal(ca.mayBeLibraryFilm([], 5), false);
});

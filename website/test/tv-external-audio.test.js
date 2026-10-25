"use strict";

// Озвучка отдельным файлом на телевизоре: сервер склеивает видео фильма с файлом озвучки (ffmpeg, второй вход)

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-tv-ext-test-"));

const test = require("node:test");
const assert = require("node:assert/strict");
const { spawnSync } = require("child_process");
const tv = require("../src/services/tvstream");
const ca = require("../src/services/custom-audio");
const { treeCache } = require("../src/services/alldebrid");

const MKV = tv.parseProbe({
  format: { format_name: "matroska,webm", duration: "7200" },
  streams: [
    { index: 0, codec_type: "video", codec_name: "h264" },
    { index: 1, codec_type: "audio", codec_name: "dts", channels: 6 },
    { index: 2, codec_type: "subtitle", codec_name: "subrip" }
  ]
});
const DUB = tv.parseProbe({
  format: { format_name: "matroska,webm", duration: "7210" },
  streams: [{ index: 0, codec_type: "audio", codec_name: "ac3", channels: 6, tags: { language: "rus" } }]
});
const ext = (offsetMs, audio = DUB.audio[0]) => ({ url: "https://host/dub.mka", audio, offsetMs });

test("ffmpeg: внешняя озвучка — второй вход, место в дорожке = место в видео − сдвиг", () => {
  const s = tv.ffmpegArgs("https://host/f.mkv", 600, MKV, null, "ts", ext(-5000)).join(" ");
  assert.match(s, /-ss 600 -i https:\/\/host\/f\.mkv .*-ss 605 -i https:\/\/host\/dub\.mka/, "−5 с: пропускаем интро дорожки");
  assert.match(s, /-map 0:0 -map 1:0/);
  assert.match(s, /-c:a ac3 /, "озвучку с места перекодируем: копия с -ss сдвигала видео позже звука");
  assert.match(s, /-t 6600 /, "поток кончается вместе с видео");
  assert.equal((s.match(/-reconnect 1/g) || []).length, 2, "переподключение у обоих входов");

  const late = tv.ffmpegArgs("https://host/f.mkv", 0, MKV, null, "ts", ext(2000)).join(" ");
  assert.match(late, /-itsoffset 2 -i https:\/\/host\/dub\.mka/, "+2 с с начала: звук с 00:02");
  assert.match(late, /-c:a copy/, "без -ss у озвучки AC3 копируется");
  assert.doesNotMatch(late, /-ss /);
  const after = tv.ffmpegArgs("https://host/f.mkv", 600, MKV, null, "ts", ext(2000)).join(" ");
  assert.match(after, /-ss 598 -i https:\/\/host\/dub\.mka/);
  const zero = tv.ffmpegArgs("https://host/f.mkv", 0, MKV, null, "ts", ext(0)).join(" ");
  assert.doesNotMatch(zero, /-itsoffset|-ss /);

  const flac = tv.ffmpegArgs("https://host/f.mkv", 0, MKV, null, "mkv", ext(0, { index: 0, codec: "flac", channels: 2, ok: false })).join(" ");
  assert.match(flac, /-c:a ac3 -b:a 192k -ac 2/, "то, что ТВ не играет, — в AC3");
  assert.match(flac, /-map 0:2 .*-c:s copy/, "субтитры фильма остаются (MKV)");
});

test("подпись: файл озвучки и сдвиг входят в подпись", () => {
  const exp = String(Math.floor(Date.now() / 1000) + 3600);
  const film = "https://alldebrid.com/f/film";
  const sig = tv.makeTvToken(film, tv.externalKey("https://alldebrid.com/f/dub", -4200), exp);
  assert.ok(tv.isValidTvToken(film, tv.externalKey("https://alldebrid.com/f/dub", -4200), exp, sig));
  assert.ok(!tv.isValidTvToken(film, tv.externalKey("https://alldebrid.com/f/dub", 0), exp, sig), "другой сдвиг");
  assert.ok(!tv.isValidTvToken(film, tv.externalKey("https://alldebrid.com/f/other", -4200), exp, sig), "другой файл");
  assert.ok(!tv.isValidTvToken(film, 0, exp, sig), "не дорожка фильма");
});

test("озвучки пользователей: ссылки на файлы из списка раздачи, только для своего видео", async () => {
  ca._reset();
  const pairs = [
    { videoPath: "Show/S01E01.mkv", audioPath: "Dub/S01E01.HDRezka.mka", voice: "HDRezka", offsetMs: -4200 },
    { videoPath: "Show/S01E02.mkv", audioPath: "Dub/S01E02.HDRezka.mka", voice: "HDRezka", offsetMs: null },
    { videoPath: "Show/S01E01.mkv", audioPath: "Dub/S01E01.gone.mka", voice: "Gone", offsetMs: null }
  ];
  ca.addTracks({ movieMagnetId: 500, audioMagnetId: 600, pairs, author: "u_1" });
  treeCache.set(600, [
    { type: "file", path: "Dub/S01E01.HDRezka.mka", sourceLink: "https://alldebrid.com/f/e1" },
    { type: "file", path: "Dub/S01E02.HDRezka.mka", sourceLink: "https://alldebrid.com/f/e2" }
  ]);
  try {
    const { items, failed } = await ca.trackLinks(500, "Show/S01E01.mkv");
    assert.equal(failed, false);
    assert.deepEqual(items.map(i => [i.link, i.track.offsetMs]), [["https://alldebrid.com/f/e1", -4200]], "файла нет в раздаче — дорожки нет");
    assert.equal((await ca.trackLinks(500)).items.length, 2);
  } finally {
    treeCache.delete(600);
    ca._reset();
  }
});

// ---- С настоящим ffmpeg ----

const hasFfmpeg = spawnSync("ffmpeg", ["-version"]).status === 0 && spawnSync("ffprobe", ["-version"]).status === 0;
const probe = file => JSON.parse(spawnSync("ffprobe", ["-v", "error", "-show_format", "-show_streams", "-of", "json", file]).stdout);

test("маршрут: tv-info отдаёт озвучки, поток склеивает видео с файлом озвучки", { skip: !hasFfmpeg && "нет ffmpeg" }, async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tv-ext-"));
  const files = {
    "https://alldebrid.com/f/film": path.join(dir, "film.mkv"),
    "https://alldebrid.com/f/side": path.join(dir, "side.mka"),
    "https://alldebrid.com/f/custom": path.join(dir, "custom.mka")
  };
  const gen = (args, out) => spawnSync("ffmpeg", ["-v", "error", "-y", ...args, out]).status === 0;
  const ok =
    gen(["-f", "lavfi", "-i", "testsrc=size=320x180:rate=24", "-f", "lavfi", "-i", "sine=f=440:sample_rate=48000", "-t", "10",
      "-c:v", "libx264", "-preset", "ultrafast", "-g", "24", "-c:a", "aac"], files["https://alldebrid.com/f/film"]) &&
    gen(["-f", "lavfi", "-i", "sine=f=1000:sample_rate=48000", "-t", "14", "-c:a", "flac"], files["https://alldebrid.com/f/side"]) &&
    gen(["-f", "lavfi", "-i", "sine=f=700:sample_rate=48000", "-t", "10", "-ac", "2", "-c:a", "ac3"], files["https://alldebrid.com/f/custom"]);
  if (!ok) { fs.rmSync(dir, { recursive: true, force: true }); return; } // сборка ffmpeg без libx264

  const mirror = require("../src/services/mirror");
  const realResolve = mirror.resolveDirect;
  mirror.resolveDirect = async link => {
    if (!files[link]) throw new mirror.UnlockError("нет файла");
    return { url: files[link], name: path.basename(files[link]) };
  };
  for (const k of Object.keys(require.cache)) if (k.includes(path.join("src", "routes", "tvstream"))) delete require.cache[k];
  const users = require("../src/services/users");
  const u = users.findOrCreateUser({ id: "911", username: "tv" }, "allow");
  const pairing = users.createDevicePairing("Samsung TV");
  users.authorizeDevicePairing(pairing.user_code, u.id);
  const token = users.pollDevicePairing(pairing.code).device_token;
  ca._reset();
  ca.addTracks({ movieMagnetId: 700, audioMagnetId: 800, pairs: [{ videoPath: "Film/film.mkv", audioPath: "custom.mka", voice: "RHS", offsetMs: 2000 }], author: u.id });
  treeCache.set(800, [{ type: "file", path: "custom.mka", sourceLink: "https://alldebrid.com/f/custom" }]);

  const { createApp } = require("../src/app");
  const server = createApp().listen(0, "127.0.0.1");
  await new Promise(r => server.once("listening", r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const headers = { "X-Device-Token": token, "User-Agent": "Mozilla/5.0 (SMART-TV; LINUX; Tizen 4.0)" };
  try {
    const q = `link=${encodeURIComponent("https://alldebrid.com/f/film")}&name=film.mkv&ext=${encodeURIComponent("https://alldebrid.com/f/side")}` +
      `&ext=${encodeURIComponent("https://example.com/f/evil")}&magnetId=700&path=${encodeURIComponent("Film/film.mkv")}`;
    const r = await fetch(`${base}/api/file/tv-info?${q}`, { headers });
    assert.equal(r.status, 200);
    const info = await r.json();
    assert.equal(info.stream, false, "AAC фильм ТВ играет сам");
    assert.deepEqual(info.external.map(x => [x.kind, x.link || x.title, x.offsetMs]), [["file", "https://alldebrid.com/f/side", 0], ["custom", "RHS", 2000]],
      "чужие ссылки (не AllDebrid) отброшены");
    const local = u => u.replace(/^https:\/\/[^/]+/, base);

    // Сдвиг подписан: подменённый — 401
    const custom = local(info.external[1].url);
    assert.equal((await fetch(custom.replace("xo=2000", "xo=0"))).status, 401);

    // Озвучка из раздачи (FLAC → AC3), с 3-й секунды: звук на всю длину видео, не дольше
    const side = await fetch(local(info.external[0].url).replace(/film\.ts/, "film.mkv") + "&t=3&s=a1");
    assert.equal(side.status, 200);
    const sideOut = path.join(dir, "side-out.mkv");
    fs.writeFileSync(sideOut, Buffer.from(await side.arrayBuffer()));
    const sp = probe(sideOut);
    assert.deepEqual(sp.streams.map(s => s.codec_name), ["h264", "ac3"]);
    const dur = Number(sp.format.duration);
    assert.ok(dur > 6 && dur < 8, `длительность ${dur}: видео 10 с с 3-й, а не озвучка 14 с`);

    // Озвучка пользователя +2 с: звук начинается на 2 с позже видео
    const cr = await fetch(custom.replace(/film\.ts/, "film.mkv") + "&s=a2");
    assert.equal(cr.status, 200);
    const customOut = path.join(dir, "custom-out.mkv");
    fs.writeFileSync(customOut, Buffer.from(await cr.arrayBuffer()));
    const cp = probe(customOut);
    assert.deepEqual(cp.streams.map(s => s.codec_name), ["h264", "ac3"], "AC3 копией");
    const [v, a] = cp.streams.map(s => Number(s.start_time));
    assert.ok(Math.abs(a - v - 2) < 0.2, `звук позже видео на ${a - v} с`);
  } finally {
    server.close();
    mirror.resolveDirect = realResolve;
    treeCache.delete(800);
    ca._reset();
    for (let i = 0; i < 20 && tv.activeCount(); i++) await new Promise(r => setTimeout(r, 50));
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("ffmpeg: озвучка AC3 с места — видео и звук начинаются вместе, интро вырезано", { skip: !hasFfmpeg && "нет ffmpeg" }, () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tv-ext-sync-"));
  try {
    const video = path.join(dir, "v.mkv"), dub = path.join(dir, "d.mka");
    const gen = (...a) => spawnSync("ffmpeg", ["-v", "error", "-y", ...a]).status === 0;
    // Озвучка: 5 с «интро» (тон), потом тишина
    if (!gen("-f", "lavfi", "-i", "testsrc=size=320x180:rate=24", "-t", "20", "-c:v", "libx264", "-preset", "ultrafast", "-g", "48", video) ||
      !gen("-f", "lavfi", "-i", "sine=f=300:sample_rate=48000:duration=5", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-filter_complex",
        "[0]aformat=channel_layouts=stereo[a];[1]atrim=duration=20[b];[a][b]concat=n=2:v=0:a=1", "-c:a", "ac3", dub)) return;
    const vinfo = tv.parseProbe(probe(video)), dinfo = tv.parseProbe(probe(dub));
    for (const t of [0, 6]) {
      const out = path.join(dir, `o${t}.mkv`);
      const args = tv.ffmpegArgs(video, t, vinfo, null, "mkv", { url: dub, audio: tv.defaultAudio(dinfo), offsetMs: -5000 });
      assert.equal(spawnSync("ffmpeg", ["-y", ...args.slice(0, -1), out]).status, 0);
      const [v, a] = probe(out).streams.map(s => Number(s.start_time));
      assert.ok(Math.abs(v - a) < 0.1, `t=${t}: видео ${v}, звук ${a}`);
      const vol = spawnSync("ffmpeg", ["-hide_banner", "-i", out, "-t", "2", "-map", "0:a", "-af", "volumedetect", "-f", "null", "-"], { encoding: "utf8" }).stderr;
      assert.ok(Number((/max_volume: (\S+)/.exec(vol) || [])[1]) < -60, `t=${t}: в начале слышно интро`);
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

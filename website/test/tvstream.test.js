"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawnSync, spawn } = require("child_process");
const tv = require("../src/services/tvstream");

const BD = {
  format: { format_name: "mpegts", duration: "5400.5" },
  streams: [
    { index: 0, codec_type: "video", codec_name: "h264", width: 1920, height: 1080 },
    { index: 1, codec_type: "audio", codec_name: "dts", profile: "DTS-HD MA", channels: 8, tags: { language: "eng" }, disposition: { default: 1 } },
    { index: 2, codec_type: "audio", codec_name: "ac3", channels: 6, tags: { language: "rus" } },
    { index: 3, codec_type: "subtitle", codec_name: "hdmv_pgs_subtitle" }
  ]
};
const MKV_DTS = {
  format: { format_name: "matroska,webm", duration: "7200" },
  streams: [
    { index: 0, codec_type: "video", codec_name: "hevc", width: 3840, height: 2160 },
    { index: 1, codec_type: "audio", codec_name: "truehd", channels: 8, tags: { language: "eng" } },
    { index: 2, codec_type: "audio", codec_name: "dts", channels: 6, tags: { language: "rus" } },
    { index: 3, codec_type: "subtitle", codec_name: "subrip", tags: { language: "rus" } },
    { index: 4, codec_type: "video", codec_name: "mjpeg", disposition: { attached_pic: 1 } }
  ]
};
const MKV_OK = {
  format: { format_name: "matroska,webm", duration: "7200" },
  streams: [
    { index: 0, codec_type: "video", codec_name: "h264" },
    { index: 1, codec_type: "audio", codec_name: "dts", channels: 6 },
    { index: 2, codec_type: "audio", codec_name: "ac3", channels: 6 }
  ]
};

test("tvstream: разбор ffprobe и выбор дорожки", () => {
  const bd = tv.parseProbe(BD);
  assert.equal(bd.durationMs, 5400500);
  assert.equal(bd.video.index, 0);
  assert.deepEqual(bd.audio.map(a => [a.codec, a.ok]), [["dts", false], ["ac3", true]]);
  assert.equal(bd.subs.length, 0, "PGS не копируем");
  assert.equal(tv.needsTvStream(bd), true, "MPEG-TS — всегда через сервер (по зеркалу .m2ts шёл 1 кадр/с)");
  assert.equal(tv.defaultAudio(bd).index, 2, "дорожка, которую ТВ играет, и русская");

  const mkv = tv.parseProbe(MKV_DTS);
  assert.equal(mkv.video.index, 0, "обложка (attached_pic) — не видео");
  assert.equal(tv.needsTvStream(mkv), true, "нет звука, который ТВ играет");
  assert.equal(tv.defaultAudio(mkv).index, 2, "русская");
  assert.deepEqual(mkv.subs.map(s => s.index), [3]);

  assert.equal(tv.needsTvStream(tv.parseProbe(MKV_OK)), false, "MKV с AC3 телевизор переключит сам");
  const av1 = tv.parseProbe({ ...MKV_DTS, streams: MKV_DTS.streams.map(s => (s.index === 0 ? { ...s, codec_name: "av1" } : s)) });
  assert.equal(tv.videoUnsupported(av1), true);
  assert.equal(tv.needsTvStream(av1), false, "AV1 сервер не спасёт");
});

test("tvstream: аргументы ffmpeg", () => {
  const mkv = tv.parseProbe(MKV_DTS);
  const a = tv.ffmpegArgs("https://host/f.mkv", 600, mkv, mkv.audio[0], "mkv");
  const s = a.join(" ");
  assert.match(s, /-ss 600 -i https:\/\/host\/f\.mkv/, "-ss до -i: быстрый прыжок");
  assert.match(s, /-map 0:0 -map 0:1 -map 0:3/);
  assert.match(s, /-c:v copy/);
  assert.match(s, /-c:a ac3 -b:a 640k -ac 6/, "7.1 → 5.1 AC3");
  assert.match(s, /-c:s copy/);
  assert.match(s, /-reconnect 1/);
  assert.equal(a[a.length - 1], "pipe:1");
  const t = tv.ffmpegArgs("https://host/f.mkv", 0, mkv, mkv.audio[0]).join(" ");
  assert.match(t, /-f mpegts/, "по умолчанию MPEG-TS");
  assert.doesNotMatch(t, /-c:s|0:3/, "субтитров MPEG-TS не несёт");
  const bd = tv.parseProbe(BD);
  const b = tv.ffmpegArgs("https://host/f.m2ts", 0, bd, bd.audio[1]).join(" ");
  assert.match(b, /-c:a copy/, "AC3 копируется");
  assert.doesNotMatch(b, /-ss /);
});

test("tvstream: подпись адреса и клиент ТВ", () => {
  const exp = String(Math.floor(Date.now() / 1000) + 3600);
  const sig = tv.makeTvToken("https://alldebrid.com/f/x", 2, exp);
  assert.ok(tv.isValidTvToken("https://alldebrid.com/f/x", 2, exp, sig));
  assert.ok(!tv.isValidTvToken("https://alldebrid.com/f/x", 1, exp, sig), "другая дорожка — другая подпись");
  assert.ok(!tv.isValidTvToken("https://alldebrid.com/f/y", 2, exp, sig));
  const req = (ua, name) => ({ get: h => (h === "user-agent" ? ua : ""), device: name ? { name } : null });
  assert.ok(tv.isTvClient(req("Mozilla/5.0 (SMART-TV; LINUX; Tizen 4.0) AppleWebKit/537.36", null)));
  assert.ok(tv.isTvClient(req("okhttp", "Suslik Media Samsung TV")));
  assert.ok(!tv.isTvClient(req("okhttp/4.12", "Pixel 8")));
});

// С настоящим ffmpeg (если он есть): DTS → AC3 и Blu-ray LPCM, видео не перекодируется
const hasFfmpeg = spawnSync("ffmpeg", ["-version"]).status === 0 && spawnSync("ffprobe", ["-version"]).status === 0;

function run(args) {
  return new Promise((resolve, reject) => {
    const c = spawn("ffmpeg", args, { stdio: ["ignore", "pipe", "pipe"] });
    const out = [];
    let err = "";
    c.stdout.on("data", d => out.push(d));
    c.stderr.on("data", d => { err += d; });
    c.on("close", code => (code === 0 ? resolve(Buffer.concat(out)) : reject(new Error(err))));
  });
}

test("tvstream: ffmpeg на настоящих файлах", { skip: !hasFfmpeg && "нет ffmpeg" }, async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tvstream-"));
  try {
    const src = path.join(dir, "bd.m2ts");
    const gen = spawnSync("ffmpeg", ["-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=320x180:rate=24", "-f", "lavfi", "-i", "sine=f=440:sample_rate=48000",
      "-t", "12", "-c:v", "libx264", "-preset", "ultrafast", "-g", "24", "-c:a", "pcm_bluray", "-mpegts_m2ts_mode", "1", "-f", "mpegts", src]);
    if (gen.status !== 0) return; // сборка ffmpeg без libx264/pcm_bluray
    const info = await tv.probeFile("test:" + src, src);
    assert.equal(tv.needsTvStream(info), true);
    const audio = tv.defaultAudio(info);
    assert.equal(audio.codec, "pcm_bluray");
    const out = path.join(dir, "out.mkv");
    fs.writeFileSync(out, await run(tv.ffmpegArgs(src, 5, info, audio, "mkv")));
    const outTs = path.join(dir, "out.ts");
    fs.writeFileSync(outTs, await run(tv.ffmpegArgs(src, 5, info, audio)));
    const ts = JSON.parse(spawnSync("ffprobe", ["-v", "error", "-show_format", "-show_streams", "-of", "json", outTs]).stdout);
    assert.equal(ts.format.format_name, "mpegts");
    assert.deepEqual(ts.streams.map(s => s.codec_name), ["h264", "ac3"]);
    const res = JSON.parse(spawnSync("ffprobe", ["-v", "error", "-show_format", "-show_streams", "-of", "json", out]).stdout);
    const codecs = res.streams.map(s => s.codec_name);
    assert.deepEqual(codecs, ["h264", "ac3"]);
    // Длительности в заголовке потока из трубы нет (её ТВ берёт из tv-info): считаем кадры — 12 с с 5-й секунды ≈ 7 с
    const frames = Number(JSON.parse(spawnSync("ffprobe", ["-v", "error", "-count_frames", "-select_streams", "v:0",
      "-show_entries", "stream=nb_read_frames", "-of", "json", out]).stdout).streams[0].nb_read_frames);
    assert.ok(frames > 5 * 24 && frames < 8.5 * 24, `кадров: ${frames}`);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("tvstream: маршрут /api/file/tv отдаёт MKV и не перематывается байтами", { skip: !hasFfmpeg && "нет ffmpeg" }, async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tvroute-"));
  const mirror = require("../src/services/mirror");
  const realResolve = mirror.resolveDirect;
  const src = path.join(dir, "film.mkv");
  const gen = spawnSync("ffmpeg", ["-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=320x180:rate=24", "-f", "lavfi", "-i", "sine=f=440:sample_rate=48000",
    "-t", "8", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "dca", "-strict", "-2", src]);
  if (gen.status !== 0) { fs.rmSync(dir, { recursive: true, force: true }); return; }
  // Прямая ссылка AllDebrid подменяется локальным файлом: маршрут берёт resolveDirect из модуля при загрузке
  mirror.resolveDirect = async () => ({ url: src, name: "film.mkv" });
  for (const k of Object.keys(require.cache)) if (k.includes(path.join("src", "routes", "tvstream"))) delete require.cache[k];
  const { createApp } = require("../src/app");
  const server = createApp().listen(0);
  try {
    const port = server.address().port;
    const link = "https://alldebrid.com/f/testfile";
    const exp = String(Math.floor(Date.now() / 1000) + 3600);
    const url = `http://127.0.0.1:${port}/api/file/tv/film.mkv?link=${encodeURIComponent(link)}&exp=${exp}&a=1&sig=${tv.makeTvToken(link, 1, exp)}`;
    const bad = await fetch(url.replace("a=1", "a=0"));
    assert.equal(bad.status, 401, "подпись другой дорожки");
    const ranged = await fetch(url, { headers: { Range: "bytes=1000-" } });
    assert.equal(ranged.status, 416);
    const rts = await fetch(url.replace("/film.mkv", "/film.ts") + "&t=2&s=abd");
    assert.equal(rts.status, 200);
    assert.equal(rts.headers.get("content-type"), "video/mp2t");
    assert.ok((await rts.arrayBuffer()).byteLength > 1000);
    const r = await fetch(url + "&t=2&s=abc");
    assert.equal(r.status, 200);
    assert.equal(r.headers.get("content-type"), "video/x-matroska");
    const body = Buffer.from(await r.arrayBuffer());
    const out = path.join(dir, "out.mkv");
    fs.writeFileSync(out, body);
    const res = JSON.parse(spawnSync("ffprobe", ["-v", "error", "-show_streams", "-of", "json", out]).stdout);
    assert.deepEqual(res.streams.map(s => s.codec_name), ["h264", "ac3"]);
    for (let i = 0; i < 20 && tv.activeCount(); i++) await new Promise(r => setTimeout(r, 50)); // close приходит после конца потока
    assert.equal(tv.activeCount(), 0, "ffmpeg завершился");
  } finally {
    server.close();
    mirror.resolveDirect = realResolve;
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

"use strict";

const fs = require("fs");
const path = require("path");
const test = require("node:test");
const assert = require("node:assert/strict");

const hlsPlayer = require("../public/hls-player.js");

const fixturesDir = path.join(__dirname, "..", "..", "docs", "hls-fixtures");
const loadJson = name => JSON.parse(fs.readFileSync(path.join(fixturesDir, name), "utf8"));

const fxNone = loadJson("variant-url-none.json");
const fxEncoding = loadJson("variant-url-encoding.json");
const fxReady = loadJson("variant-url-ready.json");
const fxMp4Direct = loadJson("variant-url-mp4-direct.json");
const masterM3u8 = fs.readFileSync(path.join(fixturesDir, "master-v1.m3u8"), "utf8");

const canPlayChrome = m => /video\/(mp4|webm)/.test(m) && !/dtsc|ac-3|ec-3|mlpa|hvc1/.test(m);
const CHROME_HEVC = {
  hevcMse: true,
  avcMse: true,
  nativeHls: false,
  nativeHevc: false,
  nativeAvc: false,
  safari: false,
  canPlay: canPlayChrome
};
const FIREFOX = {
  hevcMse: false,
  avcMse: true,
  nativeHls: false,
  nativeHevc: false,
  nativeAvc: false,
  safari: false,
  canPlay: canPlayChrome
};
const SAFARI = {
  hevcMse: true,
  avcMse: true,
  nativeHls: true,
  nativeHevc: true,
  nativeAvc: true,
  safari: true,
  canPlay: m => /video\/mp4/.test(m) && !/dtsc|mlpa/.test(m)
};
const NOTHING = {
  canPlay: () => false
};

test("pickSource: ответ без блока hls возвращает legacy", () => {
  const noHls = structuredClone(fxReady);
  delete noHls.hls;
  for (const caps of [CHROME_HEVC, FIREFOX, SAFARI, NOTHING]) {
    assert.equal(hlsPlayer.pickSource(noHls, caps).kind, "legacy");
  }
  assert.equal(hlsPlayer.pickSource(null, CHROME_HEVC).kind, "legacy");
});

test("pickSource: ready для Chrome HEVC выбирает hlsjs", () => {
  const res = hlsPlayer.pickSource(fxReady, CHROME_HEVC);
  assert.equal(res.kind, "hls");
  assert.equal(res.engine, "hlsjs");
  assert.equal(res.url, fxReady.hls.url);
  assert.equal(res.shiftMs, 0);
  assert.equal(res.profile, "v1");
  assert.equal(res.fallback, null);
});

test("pickSource: ready для Safari выбирает native HLS", () => {
  const res = hlsPlayer.pickSource(fxReady, SAFARI);
  assert.equal(res.kind, "hls");
  assert.equal(res.engine, "native");
});

test("pickSource: ready для Firefox без direct_ok — unsupported, с direct_ok — direct, а Chrome получает fallback", () => {
  assert.equal(hlsPlayer.pickSource(fxReady, FIREFOX).kind, "unsupported");

  const withDirect = structuredClone(fxReady);
  withDirect.hls.direct_ok = true;
  withDirect.hls.direct = { ext: "mp4", vcodec: "h264", acodec: "aac" };

  const ffRes = hlsPlayer.pickSource(withDirect, FIREFOX);
  assert.equal(ffRes.kind, "direct");
  assert.equal(ffRes.url, withDirect.url);
  assert.equal(ffRes.mime, 'video/mp4; codecs="avc1.640028, mp4a.40.2"');

  const chromeRes = hlsPlayer.pickSource(withDirect, CHROME_HEVC);
  assert.equal(chromeRes.kind, "hls");
  assert.equal(chromeRes.fallback?.kind, "direct");
});

test("pickSource: shift_ms передаётся в shiftMs или по умолчанию 0", () => {
  const shifted = structuredClone(fxReady);
  shifted.hls.shift_ms = 2500;
  assert.equal(hlsPlayer.pickSource(shifted, CHROME_HEVC).shiftMs, 2500);

  const noShift = structuredClone(fxReady);
  delete noShift.hls.shift_ms;
  assert.equal(hlsPlayer.pickSource(noShift, CHROME_HEVC).shiftMs, 0);
});

test("pickSource: профиль v1-copy с h264 работает на Firefox через hlsjs, с hevc — unsupported", () => {
  const copyH264 = structuredClone(fxReady);
  copyH264.hls.profile = "v1-copy";
  copyH264.hls.direct = { ext: "mp4", vcodec: "h264", acodec: "aac" };
  const res = hlsPlayer.pickSource(copyH264, FIREFOX);
  assert.equal(res.kind, "hls");
  assert.equal(res.engine, "hlsjs");

  const copyHevc = structuredClone(copyH264);
  copyHevc.hls.direct.vcodec = "hevc";
  assert.equal(hlsPlayer.pickSource(copyHevc, FIREFOX).kind, "unsupported");
});

test("pickSource и messageFor: статус encoding", () => {
  const res = hlsPlayer.pickSource(fxEncoding, CHROME_HEVC);
  assert.equal(res.kind, "wait");
  assert.equal(res.etaMin, 14);
  assert.equal(res.progress, 0.37);
  assert.equal(hlsPlayer.messageFor(res), "Готовим для браузера, примерно 14 мин");

  const noEta = structuredClone(fxEncoding);
  delete noEta.hls.eta;
  const resNoEta = hlsPlayer.pickSource(noEta, CHROME_HEVC);
  assert.equal(resNoEta.etaMin, null);
  assert.equal(hlsPlayer.messageFor(resNoEta), "Готовим для браузера");
});

test("pickSource и messageFor: статус none", () => {
  for (const caps of [CHROME_HEVC, FIREFOX, SAFARI, NOTHING]) {
    const res = hlsPlayer.pickSource(fxNone, caps);
    assert.equal(res.kind, "soon");
    assert.equal(hlsPlayer.messageFor(res), "Скоро будет готово для браузера");
  }
});

test("pickSource: mp4-direct", () => {
  const chromeRes = hlsPlayer.pickSource(fxMp4Direct, CHROME_HEVC);
  assert.equal(chromeRes.kind, "direct");
  assert.equal(chromeRes.upgrade, true);
  assert.equal(chromeRes.url, fxMp4Direct.url);

  assert.equal(hlsPlayer.pickSource(fxMp4Direct, NOTHING).kind, "soon");
  assert.equal(hlsPlayer.pickSource(fxMp4Direct, FIREFOX).kind, "direct");
});

test("pickSource: статус ready без url не возвращает hls", () => {
  const readyNoUrl = structuredClone(fxNone);
  readyNoUrl.hls.status = "ready";
  assert.equal(hlsPlayer.pickSource(readyNoUrl, CHROME_HEVC).kind, "soon");
});

test("directMime: формирование MIME-типа для контейнеров и кодеков", () => {
  assert.equal(hlsPlayer.directMime({ ext: "mp4", vcodec: "h264", acodec: "aac" }), 'video/mp4; codecs="avc1.640028, mp4a.40.2"');
  assert.equal(hlsPlayer.directMime({ ext: "m4v", vcodec: "h264", acodec: "" }), 'video/mp4; codecs="avc1.640028"');
  assert.equal(hlsPlayer.directMime({ ext: ".MP4", vcodec: "H264", acodec: "dts" }), 'video/mp4; codecs="avc1.640028, dtsc"');
  assert.equal(hlsPlayer.directMime({ ext: "mp4", vcodec: "h264", acodec: "ac3" }), 'video/mp4; codecs="avc1.640028, ac-3"');
  assert.equal(hlsPlayer.directMime({ ext: "mp4", vcodec: "h264", acodec: "eac3" }), 'video/mp4; codecs="avc1.640028, ec-3"');
  assert.equal(hlsPlayer.directMime({ ext: "mp4", vcodec: "h264", acodec: "truehd" }), 'video/mp4; codecs="avc1.640028, mlpa"');
  assert.equal(hlsPlayer.directMime({ ext: "webm", vcodec: "vp9", acodec: "aac" }), 'video/webm; codecs="vp09.00.40.08"');
  assert.equal(hlsPlayer.directMime({ ext: "webm", vcodec: "av1", acodec: "opus" }), 'video/webm; codecs="av01.0.08M.08, opus"');
  assert.equal(hlsPlayer.directMime({ ext: "mp4", vcodec: "", acodec: "" }), "video/mp4");
  assert.equal(hlsPlayer.directMime({ ext: "mkv", vcodec: "h264", acodec: "aac" }), null);
  assert.equal(hlsPlayer.directMime(undefined), null);
  assert.equal(hlsPlayer.directMime({ ext: "mp4", vcodec: "hevc", acodec: "aac" }), 'video/mp4; codecs="hvc1.1.6.L120.90, mp4a.40.2"');
});

test("toHlsMs и toFileMs: преобразование времени со сдвигом", () => {
  assert.equal(hlsPlayer.toHlsMs(60000, 2500), 62500);
  assert.equal(hlsPlayer.toFileMs(62500, 2500), 60000);
  assert.equal(hlsPlayer.toHlsMs(1000, -3000), 0);
  assert.equal(hlsPlayer.toFileMs(1000, 3000), 0);
  assert.equal(hlsPlayer.toHlsMs(5000, undefined), 5000);
  assert.equal(hlsPlayer.toHlsMs("abc", 0), 0);

  for (const [pos, shift] of [[0, 2500], [15000, 2500], [123456, -1000], [90000, 0]]) {
    assert.equal(hlsPlayer.toFileMs(hlsPlayer.toHlsMs(pos, shift), shift), pos);
  }
});

test("etaMinutes: округление минут ожидания", () => {
  assert.equal(hlsPlayer.etaMinutes(840), 14);
  assert.equal(hlsPlayer.etaMinutes(30), 1);
  assert.equal(hlsPlayer.etaMinutes(0), null);
  assert.equal(hlsPlayer.etaMinutes(undefined), null);
});

test("parseMasterMedia: разбор master.m3u8 (LF и CRLF)", () => {
  const check = parsed => {
    assert.equal(parsed.audio.length, 2);
    assert.equal(parsed.audio[0].name, "Русский (Дубляж)");
    assert.equal(parsed.audio[0].lang, "ru");
    assert.equal(parsed.audio[0].default, true);
    assert.equal(parsed.audio[1].default, false);

    assert.equal(parsed.subtitles.length, 2);
    assert.equal(parsed.subtitles[0].name, "Русские (полные)");
    assert.equal(parsed.subtitles[1].name, "English");
    assert.equal(parsed.subtitles[0].forced, false);
    assert.equal(parsed.subtitles[1].forced, false);

    assert.equal(parsed.variants.length, 2);
    assert.equal(parsed.variants[0].bandwidth, 5400000);
    assert.equal(parsed.variants[0].codecs, "hvc1.1.6.L120.90,mp4a.40.2");
    assert.equal(parsed.variants[0].resolution, "1920x800");
    assert.equal(parsed.variants[0].uri.endsWith("/1080p/index.m3u8"), true);
    assert.equal(parsed.variants[1].uri.endsWith("/720p/index.m3u8"), true);
  };

  check(hlsPlayer.parseMasterMedia(masterM3u8));
  check(hlsPlayer.parseMasterMedia(masterM3u8.replace(/\r?\n/g, "\r\n")));
});

test("pickTrack: выбор аудиодорожки и субтитров", () => {
  const { audio, subtitles } = hlsPlayer.parseMasterMedia(masterM3u8);
  assert.equal(hlsPlayer.pickTrack(audio, null, 0), 0);
  assert.equal(hlsPlayer.pickTrack(audio, { name: "English" }, 0), 1);
  assert.equal(hlsPlayer.pickTrack(audio, { lang: "en" }, 0), 1);
  assert.equal(hlsPlayer.pickTrack(audio, { name: "nope", lang: "de" }, 0), 0);

  assert.equal(hlsPlayer.pickTrack(subtitles, null, -1), -1);
  assert.equal(hlsPlayer.pickTrack(subtitles, { lang: "en" }, -1), 1);
  assert.equal(hlsPlayer.pickTrack(subtitles, { off: true }, -1), -1);
  assert.equal(hlsPlayer.pickTrack([], null, 0), -1);
});

test("hlsErrorAction: реакция на ошибки hls.js", () => {
  assert.equal(hlsPlayer.hlsErrorAction({ fatal: false, type: "networkError" }, { net: 0 }), "ignore");
  assert.equal(hlsPlayer.hlsErrorAction(null, { net: 0 }), "ignore");
  assert.equal(hlsPlayer.hlsErrorAction({ fatal: true, type: "networkError" }, { net: 0 }), "retry");
  assert.equal(hlsPlayer.hlsErrorAction({ fatal: true, type: "networkError" }, { net: 3, refreshed: false }), "refresh");
  assert.equal(hlsPlayer.hlsErrorAction({ fatal: true, type: "networkError" }, { net: 3, refreshed: true }), "fallback");
  assert.equal(hlsPlayer.hlsErrorAction({ fatal: true, type: "mediaError" }, { media: 0 }), "recover");
  assert.equal(hlsPlayer.hlsErrorAction({ fatal: true, type: "mediaError" }, { media: 1 }), "swap");
  assert.equal(hlsPlayer.hlsErrorAction({ fatal: true, type: "mediaError" }, { media: 2 }), "fallback");
  assert.equal(hlsPlayer.hlsErrorAction({ fatal: true, type: "otherError" }, {}), "fallback");
});

test("resumeMs: вычисление позиции возобновления", () => {
  assert.equal(hlsPlayer.resumeMs(null), 0);
  assert.equal(hlsPlayer.resumeMs({ pos: 3000, len: 100000 }), 0);
  assert.equal(hlsPlayer.resumeMs({ pos: 60000, len: 7200000 }), 60000);
  assert.equal(hlsPlayer.resumeMs({ pos: 7190000, len: 7200000 }), 0);
  assert.equal(hlsPlayer.resumeMs({ pos: 60000, len: 0 }), 60000);
});

test("экспорт модуля: attach и detectCaps определены, globalThis.SuslikHls не задан", () => {
  assert.equal(typeof hlsPlayer.attach, "function");
  assert.equal(typeof hlsPlayer.detectCaps, "function");
  assert.equal(globalThis.SuslikHls, undefined);
});

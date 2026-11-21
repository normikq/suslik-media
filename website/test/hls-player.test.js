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
const fxRestoring = loadJson("variant-url-restoring.json");
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

test("pickSource: кодек из hls.vcodec (manifest) важнее меток раздачи", () => {
  // раздачу удалили и добавили снова: меток media-tags ещё нет, direct.vcodec пуст
  const readded = structuredClone(fxReady);
  readded.hls.profile = "v1-copy";
  readded.hls.direct = { ext: "mkv", vcodec: "", acodec: "" };
  assert.equal(hlsPlayer.pickSource(readded, FIREFOX).kind, "unsupported");
  readded.hls.vcodec = "h264";
  const res = hlsPlayer.pickSource(readded, FIREFOX);
  assert.equal(res.kind, "hls");
  assert.equal(res.engine, "hlsjs");

  const hevc = structuredClone(readded);
  hevc.hls.vcodec = "hevc";
  hevc.hls.direct.vcodec = "h264";
  assert.equal(hlsPlayer.pickSource(hevc, FIREFOX).kind, "unsupported");
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

test("pickSource и messageFor: статус restoring (возврат из архива, §17)", () => {
  for (const caps of [CHROME_HEVC, FIREFOX, SAFARI, NOTHING]) {
    const res = hlsPlayer.pickSource(fxRestoring, caps);
    assert.equal(res.kind, "wait");
    assert.equal(res.restoring, true);
    assert.equal(res.etaMin, 3);
    assert.equal(hlsPlayer.messageFor(res), "Достаём из архива, примерно 3 мин");
  }

  const noEta = structuredClone(fxRestoring);
  noEta.hls.eta = null;
  assert.equal(hlsPlayer.messageFor(hlsPlayer.pickSource(noEta, CHROME_HEVC)), "Достаём из архива");

  // direct_ok: играем зеркало и ждём HLS, как при encoding
  const direct = structuredClone(fxRestoring);
  direct.hls.direct_ok = true;
  direct.hls.direct = { ext: "mp4", vcodec: "h264", acodec: "aac" };
  const res = hlsPlayer.pickSource(direct, CHROME_HEVC);
  assert.equal(res.kind, "direct");
  assert.equal(res.upgrade, true);
  assert.equal(res.url, direct.url);
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

test("levelLabel: определение ярлыка качества по разрешению", () => {
  assert.equal(hlsPlayer.levelLabel({ width: 1920, height: 800 }), "1080p");
  assert.equal(hlsPlayer.levelLabel({ height: 1040 }), "1080p");
  assert.equal(hlsPlayer.levelLabel({ width: 1920, height: 1080 }), "1080p");

  assert.equal(hlsPlayer.levelLabel({ width: 1280, height: 534 }), "720p");
  assert.equal(hlsPlayer.levelLabel({ height: 694 }), "720p");
  assert.equal(hlsPlayer.levelLabel({ width: 1280, height: 720 }), "720p");

  assert.equal(hlsPlayer.levelLabel({ height: 2160 }), "4K");
  assert.equal(hlsPlayer.levelLabel({ width: 3840, height: 1600 }), "4K");
  assert.equal(hlsPlayer.levelLabel({ height: 1440 }), "1440p");
  assert.equal(hlsPlayer.levelLabel({ height: 576 }), "480p");

  assert.equal(hlsPlayer.levelLabel({}), "");
  assert.equal(hlsPlayer.levelLabel(null), "");
});

test("levelList: сортировка уровней от лучшего к худшему и уточнение битрейта при дублях", () => {
  const levels = [
    { width: 1280, height: 534, bitrate: 3300000 },
    { width: 1920, height: 800, bitrate: 5400000 }
  ];
  assert.deepEqual(hlsPlayer.levelList(levels), [
    { index: 1, height: 800, label: "1080p" },
    { index: 0, height: 534, label: "720p" }
  ]);

  const dupLevels = [
    { width: 1920, height: 1080, bitrate: 4000000 },
    { width: 1920, height: 1080, bitrate: 8000000 }
  ];
  assert.deepEqual(hlsPlayer.levelList(dupLevels), [
    { index: 1, height: 1080, label: "1080p · 8 Мбит" },
    { index: 0, height: 1080, label: "1080p · 4 Мбит" }
  ]);

  assert.deepEqual(hlsPlayer.levelList(null), []);
  assert.deepEqual(hlsPlayer.levelList(undefined), []);
  assert.deepEqual(hlsPlayer.levelList({}), []);
});

test("showQualityMenu: меню качества показывается только для hlsjs и более чем одного уровня", () => {
  const twoLevels = [
    { width: 1920, height: 800, bitrate: 5400000 },
    { width: 1280, height: 534, bitrate: 3300000 }
  ];
  assert.equal(hlsPlayer.showQualityMenu(twoLevels, "hlsjs"), true);
  assert.equal(hlsPlayer.showQualityMenu([twoLevels[0]], "hlsjs"), false);
  assert.equal(hlsPlayer.showQualityMenu(twoLevels, "native"), false);
  assert.equal(hlsPlayer.showQualityMenu(null, "hlsjs"), false);
});

test("readQuality и saveQuality: сохранение и чтение выбранного качества", () => {
  assert.equal(hlsPlayer.QUALITY_KEY, "suslik.hls.quality");

  const makeStorage = () => ({
    data: {},
    getItem(k) {
      return k in this.data ? this.data[k] : null;
    },
    setItem(k, v) {
      this.data[k] = String(v);
    }
  });

  const st = makeStorage();
  assert.equal(hlsPlayer.readQuality(st), null);
  assert.equal(hlsPlayer.saveQuality(st, "720p"), true);
  assert.equal(hlsPlayer.readQuality(st), "720p");

  assert.equal(hlsPlayer.saveQuality(st, -1), true);
  assert.equal(hlsPlayer.readQuality(st), "auto");

  assert.equal(hlsPlayer.saveQuality(st, null), true);
  assert.equal(hlsPlayer.readQuality(st), "auto");

  assert.equal(hlsPlayer.readQuality(null), null);
  assert.equal(hlsPlayer.saveQuality(null, "720p"), false);

  const brokenStorage = {
    getItem() {
      throw new Error("read fail");
    },
    setItem() {
      throw new Error("write fail");
    }
  };
  assert.equal(hlsPlayer.readQuality(brokenStorage), null);
  assert.equal(hlsPlayer.saveQuality(brokenStorage, "720p"), false);
});

test("startLevelFor: выбор стартового уровня по сохранённому ярлыку", () => {
  const levels = [
    { width: 1920, height: 800, bitrate: 5400000 },
    { width: 1280, height: 534, bitrate: 3300000 }
  ];
  assert.equal(hlsPlayer.startLevelFor(levels, "720p"), 1);
  assert.equal(hlsPlayer.startLevelFor(levels, "1080p"), 0);
  assert.equal(hlsPlayer.startLevelFor(levels, "auto"), -1);
  assert.equal(hlsPlayer.startLevelFor(levels, null), -1);
  assert.equal(hlsPlayer.startLevelFor(levels, "4K"), -1);

  const dupLevels = [
    { width: 1920, height: 1080, bitrate: 4000000 },
    { width: 1920, height: 1080, bitrate: 8000000 }
  ];
  assert.equal(hlsPlayer.startLevelFor(dupLevels, "1080p"), 1);
});

test("autoLabel: подпись режима Авто с текущим уровнем", () => {
  const levels = [
    { width: 1920, height: 800, bitrate: 5400000 },
    { width: 1280, height: 534, bitrate: 3300000 }
  ];
  assert.equal(hlsPlayer.autoLabel(levels, 0), "Авто (сейчас 1080p)");
  assert.equal(hlsPlayer.autoLabel(levels, 1), "Авто (сейчас 720p)");
  assert.equal(hlsPlayer.autoLabel(levels, -1), "Авто");
  assert.equal(hlsPlayer.autoLabel(null, 0), "Авто");
});

test("hlsConfig: параметры конфигурации hls.js", () => {
  const cfg125 = hlsPlayer.hlsConfig(125);
  assert.equal(cfg125.startPosition, 125);
  assert.equal(cfg125.backBufferLength, 90);
  assert.equal(cfg125.maxBufferLength, 30);
  assert.equal(cfg125.maxMaxBufferLength, 120);
  assert.equal(cfg125.abrEwmaDefaultEstimate, 5000000);
  assert.equal(cfg125.autoStartLoad, false);
  assert.equal(cfg125.startLevel, -1);
  assert.equal(cfg125.enableWorker, true);
  assert.equal("capLevelToPlayerSize" in cfg125, false);

  const cfg0 = hlsPlayer.hlsConfig(0);
  assert.equal(cfg0.startPosition, -1);
});

test("secToMs: перевод секунд в миллисекунды", () => {
  assert.equal(hlsPlayer.secToMs(12.5), 12500);
  assert.equal(hlsPlayer.secToMs(undefined), 0);
  assert.equal(hlsPlayer.secToMs(-3), 0);
  assert.equal(hlsPlayer.secToMs("abc"), 0);
});

test("playerState: вычисление состояния плеера", () => {
  assert.equal(hlsPlayer.playerState(null), null);

  assert.deepEqual(hlsPlayer.playerState({ error: "Ошибка X" }), {
    kind: "error",
    message: "Ошибка X"
  });

  const waitState = hlsPlayer.playerState({
    source: { kind: "wait", etaMin: 14, progress: 0.3 }
  });
  assert.equal(waitState.kind, "preparing");
  assert.equal(waitState.etaMin, 14);
  assert.equal(waitState.message, "Готовим для браузера, примерно 14 мин");

  const restoringState = hlsPlayer.playerState({
    source: { kind: "wait", restoring: true, etaMin: 3 }
  });
  assert.equal(restoringState.message, "Достаём из архива, примерно 3 мин");

  const soonState = hlsPlayer.playerState({ source: { kind: "soon" } });
  assert.equal(soonState.kind, "preparing");
  assert.equal(soonState.etaMin, null);

  const unsupportedState = hlsPlayer.playerState({
    source: { kind: "unsupported" }
  });
  assert.equal(unsupportedState.kind, "unsupported");
  assert.equal(unsupportedState.message, "Этот браузер не показывает такое видео");

  const legacyState = hlsPlayer.playerState({ source: { kind: "legacy" } });
  assert.equal(legacyState.kind, "unsupported");
  assert.equal(typeof legacyState.message, "string");
  assert.ok(legacyState.message.length > 0);

  const L = [
    { width: 1920, height: 800, bitrate: 5400000 },
    { width: 1280, height: 534, bitrate: 3300000 }
  ];
  const tracks = {
    audio: [
      { name: "Русский (Дубляж)", lang: "ru" },
      { name: "", lang: "en" }
    ],
    subs: [{ name: "English", lang: "en" }],
    audioNow: 0,
    subNow: -1
  };

  assert.deepEqual(
    hlsPlayer.playerState({
      source: { kind: "hls" },
      engine: "hlsjs",
      levels: L,
      level: -1,
      levelNow: 1,
      tracks
    }),
    {
      kind: "hls",
      levels: [
        { index: 0, height: 800, label: "1080p" },
        { index: 1, height: 534, label: "720p" }
      ],
      level: -1,
      levelNow: 1,
      qualityLabel: "Авто (сейчас 720p)",
      audio: [
        { index: 0, name: "Русский (Дубляж)", lang: "ru" },
        { index: 1, name: "EN", lang: "en" }
      ],
      audioIndex: 0,
      subtitles: [{ index: 0, name: "English", lang: "en" }],
      subtitleIndex: -1
    }
  );

  const manualState = hlsPlayer.playerState({
    source: { kind: "hls" },
    engine: "hlsjs",
    levels: L,
    level: 0,
    levelNow: 1,
    tracks,
    note: "Переключились на прямой файл."
  });
  assert.equal(manualState.level, 0);
  assert.equal(manualState.qualityLabel, "1080p");
  assert.equal(manualState.message, "Переключились на прямой файл.");

  const nativeState = hlsPlayer.playerState({
    source: { kind: "hls" },
    engine: "native",
    levels: L,
    level: 0,
    levelNow: 0,
    tracks
  });
  assert.deepEqual(nativeState.levels, []);
  assert.equal(nativeState.level, -1);
  assert.equal(nativeState.qualityLabel, "");

  const singleLevelState = hlsPlayer.playerState({
    source: { kind: "hls" },
    engine: "hlsjs",
    levels: [L[0]],
    level: 0,
    levelNow: 0,
    tracks
  });
  assert.deepEqual(singleLevelState.levels, []);
  assert.equal(singleLevelState.level, -1);
  assert.equal(singleLevelState.qualityLabel, "");

  const mirrorState = hlsPlayer.playerState({
    source: { kind: "direct" },
    tracks: null,
    note: "Переключились на прямой файл."
  });
  assert.equal(mirrorState.kind, "mirror");
  assert.deepEqual(mirrorState.levels, []);
  assert.deepEqual(mirrorState.audio, []);
  assert.equal(mirrorState.audioIndex, -1);
  assert.deepEqual(mirrorState.subtitles, []);
  assert.equal(mirrorState.subtitleIndex, -1);
  assert.equal(mirrorState.message, "Переключились на прямой файл.");
});

test("экспорт модуля: mount и attach определены, mount в Node без DOM выбрасывает ошибку", () => {
  assert.equal(typeof hlsPlayer.mount, "function");
  assert.equal(typeof hlsPlayer.attach, "function");
  assert.throws(() => hlsPlayer.mount({}, {}));
});

test("интеграция с master-v1.m3u8: levelList и showQualityMenu", () => {
  const { variants } = hlsPlayer.parseMasterMedia(masterM3u8);
  const levels = variants.map(v => {
    const [width, height] = v.resolution.split("x").map(Number);
    return { width, height, bitrate: v.bandwidth };
  });
  assert.deepEqual(
    hlsPlayer.levelList(levels).map(l => l.label),
    ["1080p", "720p"]
  );
  assert.equal(hlsPlayer.showQualityMenu(levels, "hlsjs"), true);
});

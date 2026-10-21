/*
 * Что телевизор Samsung (Tizen 4.0, модели 2018 года) воспроизводит, а что нет. Программного декодера, как FFmpeg на
 * Android, здесь нет: работает только то, что умеет сам телевизор. Сервер не перекодирует (решение 21.10), поэтому
 * приложение предупреждает: до запуска — по имени файла, после открытия — по настоящим дорожкам AVPlay.
 */
(function (g) {
  'use strict';
  var SM = g.SM = g.SM || {};

  // Звук, которого у Samsung 2018+ нет: DTS убран, TrueHD (MLP) не было никогда.
  var BAD_AUDIO = /\b(dts(?:-?hd)?|truehd|true-hd|mlp)\b/i;
  var BAD_AUDIO_CODEC = /dts|dca|truehd|mlp/i;
  var AV1 = /\bav1\b/i;
  var BLURAY_DIR = /(^|\/)BDMV\/STREAM\//i;

  /**
   * Проверка до запуска, по пути файла. { level: 'ok' | 'warn' | 'no', text } — text короткий, для значка и подсказки.
   * 'no' — телевизор файл не откроет, 'warn' — может не открыться или не будет звука.
   */
  function checkFile(path) {
    var name = path.replace(/\\/g, '/');
    name = name.substring(name.lastIndexOf('/') + 1);
    if (/\.iso$/i.test(name)) return { level: 'no', text: 'Образ диска (.iso) телевизор не открывает' };
    if (AV1.test(name)) return { level: 'no', text: 'Видео AV1 этот телевизор не поддерживает' };
    if (/\.avi$/i.test(name)) return { level: 'warn', text: 'Старый формат .avi: может не воспроизвестись' };
    // Blu-ray и DTS/TrueHD: сам ТВ такое не играет (Blu-ray — 1 кадр/с, 21.10), их готовит сервер (serverFirst) — level 'server'
    if (/\.m2ts$/i.test(name) || BLURAY_DIR.test(path.replace(/\\/g, '/'))) return { level: 'server', text: 'Blu-ray (.m2ts) — через сервер: перемотка чуть дольше' };
    if (BAD_AUDIO.test(name.replace(/[._]/g, ' '))) return { level: 'server', text: 'Звук DTS/TrueHD — через сервер, в AC3' };
    return { level: 'ok', text: '' };
  }

  /**
   * Сразу спросить сервер (/api/file/tv-info): .m2ts/.ts (MPEG-TS телевизор по сети показывает рывками) и звук
   * DTS/TrueHD в имени. Сервер отдаёт такой файл через ffmpeg: видео как есть, звук — в AC3.
   */
  function serverFirst(path) {
    var p = path.replace(/\\/g, '/'), name = p.substring(p.lastIndexOf('/') + 1);
    return /\.(m2ts|mts|ts)$/i.test(name) || BLURAY_DIR.test(p) || BAD_AUDIO.test(name.replace(/[._]/g, ' '));
  }

  /** Озвучка отдельным файлом (.mka рядом с видео): AVPlay не умеет подключать второй источник звука. */
  var EXTERNAL_AUDIO_TEXT = 'Озвучка отдельным файлом — на телевизоре недоступна';

  /**
   * Звуковая дорожка AVPlay (`getTotalTrackInfo()`, type 'AUDIO'): extra_info — строка JSON с fourCC, language,
   * channels. Возвращает { index, codec, language, channels, ok }.
   */
  function audioTrack(t) {
    var info = {};
    try { info = typeof t.extra_info === 'string' ? JSON.parse(t.extra_info) : (t.extra_info || {}); } catch (e) { info = {}; }
    var codec = String(info.fourCC || info.codec || info.codec_name || '').trim();
    return {
      index: t.index, codec: codec, language: String(info.language || ''), channels: Number(info.channels) || 0,
      ok: !BAD_AUDIO_CODEC.test(codec)
    };
  }

  /**
   * Решение по звуку после открытия: { tracks, playable, current } — playable: дорожки, которые ТВ играет.
   * Если выбранная по умолчанию не играется, а другая есть, плеер переключается и показывает подсказку.
   */
  function audioPlan(trackInfo, currentIndex) {
    var tracks = (trackInfo || []).filter(function (t) { return t.type === 'AUDIO'; }).map(audioTrack);
    var playable = tracks.filter(function (t) { return t.ok; });
    var cur = null;
    tracks.forEach(function (t) { if (t.index === currentIndex) cur = t; });
    return { tracks: tracks, playable: playable, current: cur };
  }

  /** Ошибка AVPlay (onerror) → 'unsupported' | 'network' | 'other'. */
  function errorKind(code) {
    var s = String(code || '');
    if (/NOT_SUPPORTED|UNSUPPORTED|INVALID_URI|NO_SUCH_FILE/i.test(s)) return 'unsupported';
    if (/CONNECTION|NETWORK|TIMEOUT|DISCONNECT/i.test(s)) return 'network';
    return 'other';
  }

  /** Подсказка к «видео идёт рывками»: что обычно этому виной. */
  function slowHint(path, mbps) {
    if (/\.m2ts$/i.test(path) || BLURAY_DIR.test(path.replace(/\\/g, '/'))) return 'Так бывает с Blu-ray (.m2ts): лучше выбрать раздачу в .mkv.';
    if (mbps >= 35) return 'Такой поток трудно получить по Wi-Fi: лучше выбрать раздачу полегче (до 20–30 Мбит/с) или подключить ТВ кабелем.';
    return 'Возможно, файл слишком тяжёлый для телевизора (Remux, 4K): лучше выбрать раздачу полегче.';
  }

  var MESSAGES = {
    unsupported: 'Этот файл телевизор не поддерживает',
    noAudio: 'Звук этого файла телевизор не воспроизводит',
    network: 'Нет связи с сервером',
    other: 'Не удалось воспроизвести файл'
  };

  SM.support = {
    checkFile: checkFile, serverFirst: serverFirst, slowHint: slowHint, audioTrack: audioTrack, audioPlan: audioPlan, errorKind: errorKind,
    EXTERNAL_AUDIO_TEXT: EXTERNAL_AUDIO_TEXT, MESSAGES: MESSAGES
  };
  if (typeof module === 'object' && module.exports) module.exports = SM.support;
})(typeof window !== 'undefined' ? window : global);

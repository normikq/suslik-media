"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const config = require("../src/config");
const { pageCsp } = require("../src/services/precompressed");

test("webplayer: html contains artplayer script and modal markup", () => {
  const html = fs.readFileSync(path.join(config.PUBLIC_DIR, "index.html"), "utf8");

  // Artplayer library included
  assert.ok(html.includes("artplayer.js"), "index.html must include artplayer.js");

  // Web player modal elements
  assert.ok(html.includes('id="webPlayerModal"'), "index.html must contain #webPlayerModal");
  assert.ok(html.includes('id="webPlayerTitle"'), "index.html must contain #webPlayerTitle");
  assert.ok(html.includes('id="webPlayerClose"'), "index.html must contain #webPlayerClose");
  assert.ok(html.includes('id="artplayerContainer"'), "index.html must contain #artplayerContainer");
  assert.ok(html.includes('id="webPlayerAudio"'), "index.html must contain #webPlayerAudio select");
  assert.ok(html.includes('id="webPlayerSub"'), "index.html must contain #webPlayerSub select");
  assert.ok(html.includes("web-player-controls"), "index.html must contain .web-player-controls");

  // Browser watch button in PlaySheet
  assert.ok(html.includes("ps-watch-browser"), "index.html must contain ps-watch-browser button in PlaySheet");
  assert.ok(html.includes("Смотреть в браузере"), "PlaySheet must include button label 'Смотреть в браузере'");

  // Player functions
  assert.ok(html.includes("function openWebPlayer("), "index.html must define openWebPlayer");
});

test("webplayer: redesign.css contains web player styles", () => {
  const css = fs.readFileSync(path.join(config.PUBLIC_DIR, "redesign.css"), "utf8");

  assert.ok(css.includes(".web-player-modal"), "redesign.css must include .web-player-modal");
  assert.ok(css.includes(".web-player-header"), "redesign.css must include .web-player-header");
  assert.ok(css.includes(".web-player-title"), "redesign.css must include .web-player-title");
  assert.ok(css.includes(".web-player-controls"), "redesign.css must include .web-player-controls");
  assert.ok(css.includes(".web-player-select"), "redesign.css must include .web-player-select");
  assert.ok(css.includes(".web-player-body"), "redesign.css must include .web-player-body");
  assert.ok(css.includes(".ps-watch-browser"), "redesign.css must include .ps-watch-browser");
});

test("webplayer: tvstream supports AAC web audio transcoding and subtitles", () => {
  const tv = require("../src/services/tvstream");

  // WEB_AUDIO_OK codecs
  assert.ok(tv.WEB_AUDIO_OK.has("aac"));
  assert.ok(tv.WEB_AUDIO_OK.has("mp3"));
  assert.ok(tv.WEB_AUDIO_OK.has("opus"));
  assert.ok(!tv.WEB_AUDIO_OK.has("ac3"), "AC3 is not natively supported in browsers");
  assert.ok(!tv.WEB_AUDIO_OK.has("dts"), "DTS is not natively supported in browsers");

  // Subtitle arguments
  const sArgs = tv.subArgs("https://host/film.mkv", 3);
  const sStr = sArgs.join(" ");
  assert.match(sStr, /-map 0:3/, "SubArgs must map requested subtitle stream index");
  assert.match(sStr, /-c:s webvtt/, "SubArgs must encode subtitle to webvtt");
  assert.match(sStr, /-f webvtt pipe:1/, "SubArgs must output webvtt to pipe:1");

  // Subtitle token verification
  const exp = String(Math.floor(Date.now() / 1000) + 3600);
  const subSig = tv.makeTvToken("https://host/film.mkv", "sub:3", exp, "aac");
  assert.ok(tv.isValidTvToken("https://host/film.mkv", "sub:3", exp, subSig, "aac"), "Valid subtitle token passes");
  assert.ok(!tv.isValidTvToken("https://host/film.mkv", "sub:2", exp, subSig, "aac"), "Different sub index rejected");

  // AAC audio transcoding in ffmpegArgs
  const info = {
    video: { index: 0, codec: "hevc" },
    audio: [{ index: 1, codec: "ac3", channels: 6, ok: true }],
    subs: []
  };
  const args = tv.ffmpegArgs("https://host/film.mkv", 0, info, info.audio[0], "mp4", null, "aac");
  const str = args.join(" ");
  assert.match(str, /-c:a aac/, "ffmpegArgs must transcode AC3 to AAC when targetAudio is aac");
  assert.match(str, /-tag:v hvc1/, "mp4 format with hevc must add -tag:v hvc1 for browser compatibility");
});

test("webplayer: CSP allows script and media execution", () => {
  const html = fs.readFileSync(path.join(config.PUBLIC_DIR, "index.html"), "utf8");
  const csp = pageCsp(html);

  assert.ok(csp.includes("default-src 'self'"), "CSP must include default-src 'self'");
  assert.ok(csp.includes("media-src 'self' blob:"), "CSP must include media-src 'self' blob:");
  assert.ok(csp.includes("https://cdn.jsdelivr.net"), "CSP must allow CDN if used");
});

test("webplayer: local vendor artplayer bundle exists", () => {
  const vendorPath = path.join(config.PUBLIC_DIR, "vendor", "artplayer.js");
  assert.ok(fs.existsSync(vendorPath), "vendor/artplayer.js must exist");
  const stat = fs.statSync(vendorPath);
  assert.ok(stat.size > 50000, "vendor/artplayer.js must be non-empty valid bundle");
});

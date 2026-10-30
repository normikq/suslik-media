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
  assert.ok(css.includes(".web-player-body"), "redesign.css must include .web-player-body");
  assert.ok(css.includes(".ps-watch-browser"), "redesign.css must include .ps-watch-browser");
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

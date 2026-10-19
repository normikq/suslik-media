"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { safeHttpsUrl, cleanFileName, contentDisposition, readCookie } = require("../src/utils/helpers");
const { versionOf, cmpVersion, cleanReleases } = require("../src/services/updates");
const { makeMirrorToken, isValidMirrorToken, makePlaylistToken, isValidPlaylistToken } = require("../src/services/mirror");
const { createApp } = require("../src/app");

test("helpers: safeHttpsUrl parses https only", () => {
  assert.equal(safeHttpsUrl("http://example.com"), null);
  assert.equal(safeHttpsUrl("not-a-url"), null);
  assert.equal(safeHttpsUrl("javascript:alert(1)"), null);
  assert.ok(safeHttpsUrl("https://example.com/file.mp4") instanceof URL);
});

test("helpers: cleanFileName and contentDisposition", () => {
  assert.equal(cleanFileName("bad/name*?.mp4"), "bad_name__.mp4");
  const disp = contentDisposition("movie 1 (2024).mkv");
  assert.ok(disp.startsWith("inline; filename="));
  assert.ok(disp.includes("movie 1 (2024).mkv"));
});

test("helpers: readCookie extracts cookie by name", () => {
  const req = { headers: { cookie: "other=123; sdev=abcdef0123456789abcdef0123456789; test=1" } };
  assert.equal(readCookie(req, "sdev"), "abcdef0123456789abcdef0123456789");
  assert.equal(readCookie(req, "missing"), "");
});

test("updates: versionOf and cmpVersion", () => {
  assert.deepEqual(versionOf("suslik-media-2.4.1.apk"), [2, 4, 1]);
  assert.deepEqual(versionOf("suslik-media-2.0.0.4.apk"), [2, 0, 0, 4]);
  assert.equal(versionOf("invalid.apk"), null);

  assert.ok(cmpVersion([2, 4, 1], [2, 4, 0]) > 0);
  assert.ok(cmpVersion([2, 0, 0, 1], [2, 0, 0]) > 0);
  assert.ok(cmpVersion([2, 0, 0, 1], [2, 0, 0, 2]) < 0);
  assert.equal(cmpVersion([2, 4, 1], [2, 4, 1]), 0);
});

test("updates: cleanReleases sanitizes and sorts changelog items", () => {
  const input = [
    { version: "1.0.0", title: "v1", items: ["First"] },
    { version: "2.0.0", title: "v2", items: ["Second"] },
    { version: "invalid", items: ["bad"] }
  ];
  const cleaned = cleanReleases(input);
  assert.equal(cleaned.length, 2);
  assert.equal(cleaned[0].version, "2.0.0");
  assert.equal(cleaned[1].version, "1.0.0");
});

test("mirror: makeMirrorToken and isValidMirrorToken", () => {
  const link = "https://alldebrid.com/f/xyz";
  const now = Math.floor(Date.now() / 1000);
  const expires = String(now + 3600);
  const token = makeMirrorToken(link, expires);

  assert.ok(isValidMirrorToken(link, expires, token));
  assert.equal(isValidMirrorToken(link, expires, "invalidtokendddddddddddddddddddddddddddddddddddddddddddddddddddddd"), false);
  assert.equal(isValidMirrorToken(link, String(now - 100), token), false);
});

test("mirror: makePlaylistToken and isValidPlaylistToken", () => {
  const id = 12345;
  const idxStr = "0.1.2";
  const now = Math.floor(Date.now() / 1000);
  const expires = String(now + 3600);
  const token = makePlaylistToken(id, idxStr, expires);

  assert.ok(isValidPlaylistToken(id, idxStr, expires, token));
  assert.equal(isValidPlaylistToken(id, idxStr, expires, "invalidtokendddddddddddddddddddddddddddddddddddddddddddddddddddddd"), false);
});

test("app: createApp initializes express app without throwing", () => {
  const app = createApp();
  assert.equal(typeof app.listen, "function");
});

test("precompressed: pageCsp includes normalized LF hashes regardless of CRLF input", () => {
  const { pageCsp } = require("../src/services/precompressed");
  const htmlCrlf = "<html><script>\r\nconsole.log(1);\r\n</script></html>";
  const htmlLf = "<html><script>\nconsole.log(1);\n</script></html>";

  const cspFromCrlf = pageCsp(htmlCrlf);
  const cspFromLf = pageCsp(htmlLf);

  // Both must contain the normalized LF hash
  assert.ok(cspFromLf.includes("script-src 'self' 'sha256-"));
  assert.ok(cspFromCrlf.includes("script-src 'self' 'sha256-"));

  // The LF hash must be present in the CSP generated from CRLF
  const lfHashMatch = cspFromLf.match(/'sha256-[^']+'/);
  assert.ok(lfHashMatch, "LF hash must be present");
  assert.ok(cspFromCrlf.includes(lfHashMatch[0]), "CRLF-generated CSP must contain LF hash");
});


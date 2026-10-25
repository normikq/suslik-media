"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

process.env.DATA_DIR = process.env.DATA_DIR || require("os").tmpdir() + "/suslik-tags-test";
require("fs").mkdirSync(process.env.DATA_DIR, { recursive: true });
const { tagsFromStreams, pickMainFile } = require("../src/services/mediatags");

test("Dolby Vision, HDR и лучшая дорожка DTS", () => {
  const tags = tagsFromStreams([
    { codec_type: "video", color_transfer: "smpte2084", side_data_list: [{ side_data_type: "DOVI configuration record" }] },
    { codec_type: "audio", codec_name: "ac3" },
    { codec_type: "audio", codec_name: "dts", profile: "DTS" },
    { codec_type: "audio", codec_name: "dts", profile: "DTS-HD MA" }
  ]);
  assert.deepEqual(tags, ["Dolby Vision", "HDR", "DTS-HD MA"]);
});

test("DTS:X важнее DTS-HD MA; обычное SDR-видео без меток", () => {
  assert.deepEqual(tagsFromStreams([{ codec_type: "audio", codec_name: "dts", profile: "DTS-HD MA + DTS:X" }]), ["DTS:X"]);
  assert.deepEqual(tagsFromStreams([{ codec_type: "video", color_transfer: "bt709" }, { codec_type: "audio", codec_name: "aac" }]), []);
  assert.deepEqual(tagsFromStreams(null), []);
});

test("для разбора берётся самый большой видеофайл со ссылкой", () => {
  const f = pickMainFile([
    { path: "a/sample.mkv", type: "file", sourceLink: "x", size: 9 },
    { path: "a/movie.mkv", type: "file", sourceLink: "y", size: 100 },
    { path: "a/disc.iso", type: "file", sourceLink: "z", size: 900 },
    { path: "a/readme.txt", type: "file", sourceLink: "w", size: 1000 }
  ]);
  assert.equal(f.path, "a/movie.mkv");
});

"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

process.env.DATA_DIR = process.env.DATA_DIR || require("os").tmpdir() + "/suslik-tags-test";
require("fs").mkdirSync(process.env.DATA_DIR, { recursive: true });
const {
  tagsFromStreams,
  pickMainFile,
  calculateScore,
  getHardwareFlags,
  buildCompositeLabel,
  buildMediaVariant
} = require("../src/services/mediatags");

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

test("Smart Quality Score формула", () => {
  // 1080p (300) + DV (+40) + DTS-HD MA (+30) + Remux (+25) = 395
  assert.equal(calculateScore({ resolution: "1080p", videoTech: "DV", audioTech: "DTS-HD MA", sourceType: "Remux" }), 395);

  // 1080p (300) + 10-bit (+10) + AC3 (+10) + BDRip (+15) = 335
  assert.equal(calculateScore({ resolution: "1080p", videoTech: "10-bit", audioTech: "AC3 5.1", sourceType: "BDRip" }), 335);

  // 1080p (300) + 10-bit (+10) + DTS-HD MA (+30) + Remux (+25) = 365
  assert.equal(calculateScore({ resolution: "1080p", videoTech: "10-bit", audioTech: "DTS-HD MA", sourceType: "Remux" }), 365);

  // 4K (400) + DV (+40) + TrueHD Atmos (+40) + Remux (+25) = 505
  assert.equal(calculateScore({ resolution: "4K", videoTech: "DV", audioTech: "TrueHD Atmos", sourceType: "Remux" }), 505);
});

test("buildMediaVariant и метки оборудования", () => {
  const v = buildMediaVariant({
    adId: 104921,
    filePath: "Dune.2021.1080p.BDRemux.DTS-HD.MA.mkv",
    fileName: "Dune.2021.1080p.BDRemux.DTS-HD.MA.10-bit.mkv",
    sizeBytes: 25983728640,
    durationSec: 9324,
    tags: ["DTS-HD MA"]
  });

  assert.equal(v.key, "104921|Dune.2021.1080p.BDRemux.DTS-HD.MA.mkv");
  assert.equal(v.ad_id, 104921);
  assert.equal(v.resolution, "1080p");
  assert.equal(v.source_type, "Remux");
  assert.equal(v.video_tech, "10-bit");
  assert.equal(v.audio_tech, "DTS-HD MA");
  assert.equal(v.score, 365);
  assert.ok(v.hardware_flags.includes("dts"));
  assert.ok(v.hardware_flags.includes("heavy"));
  assert.match(v.label, /^1080p · 10-bit · BDRemux · DTS-HD MA \(24\.2 ГБ\)$/);
});

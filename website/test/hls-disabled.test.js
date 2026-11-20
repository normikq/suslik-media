"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-hls-off-test-"));
delete process.env.HLS_ENABLED;
process.env.AUTO_CLEAN = "false";

const test = require("node:test");
const assert = require("node:assert/strict");

const axios = require("axios");
let magnets = [];
axios.post = async () => ({ status: 200, data: { status: "success", data: { magnets } } });

const { treeCache } = require("../src/services/alldebrid");
const { buildLibrary, libraryChanged } = require("../src/services/library");
const hls = require("../src/services/hls");
const store = require("../src/services/hls-store");

test("HLS выключен: медиатека как раньше, очередь пуста", async () => {
  magnets = [{ id: 1, filename: "M", status: "Downloading", statusCode: 1, size: 1 }];
  await buildLibrary();
  treeCache.set(1, [{ path: "M/M.mkv", type: "file", size: 100, sourceLink: "x" }]);
  magnets = [{ id: 1, filename: "M", status: "Ready", statusCode: 4, size: 1 }];
  libraryChanged();
  const lib = await buildLibrary();
  assert.equal(lib.magnets[0].files.length, 1);
  assert.equal(store.allJobs().size, 0);
  assert.equal(hls.onLibrary(lib.magnets), 0);
  assert.equal(hls.enabledFor("123"), false);
  assert.equal(fs.existsSync(path.join(process.env.DATA_DIR, "hls-queue.json")), false);
});

"use strict";

// Runtime files (owners.json and the like) go to a temporary folder, not into the repository.
const os = require("os");
const fs = require("fs");
const path = require("path");
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-test-"));

const test = require("node:test");
const assert = require("node:assert/strict");
const axios = require("axios");
const config = require("../src/config");
const { recordOwner, removeChecker, canRemove } = require("../src/services/downloads");
const { searchPoster } = require("../src/services/posters");
const { createApp } = require("../src/app");

const DEV_A = "a".repeat(32);
const DEV_B = "b".repeat(32);
const reqOf = dev => ({ headers: { cookie: `sdev=${dev}` } });

test("downloads: removeChecker matches canRemove and reads the device once", () => {
  recordOwner(101, DEV_A, "1.1.1.1");
  const asA = removeChecker(reqOf(DEV_A));
  const asB = removeChecker(reqOf(DEV_B));
  assert.equal(asA(101), true);
  assert.equal(asB(101), false);
  assert.equal(asB(999), true); // no owner recorded: anyone may remove
  assert.equal(canRemove(101, reqOf(DEV_B)), false);
});

test("posters: a network failure is not remembered as «no poster»", async t => {
  const realGet = axios.get;
  const realKey = config.TMDB_API_KEY;
  t.after(() => {
    axios.get = realGet;
    config.TMDB_API_KEY = realKey;
  });
  config.TMDB_API_KEY = "test";
  let calls = 0;
  let online = false;
  axios.get = async () => {
    calls++;
    if (!online && calls === 1) throw new Error("ETIMEDOUT");
    return { data: { results: online ? [{ media_type: "movie", poster_path: "/abc.jpg", title: "Film" }] : [] } };
  };
  await assert.rejects(searchPoster("Some Film 2024"), err => err.status === 502);
  online = true;
  const body = await searchPoster("Some Film 2024");
  assert.equal(body.poster, "/api/poster/img/w500/abc.jpg");
});

test("app: malformed JSON body gets a clear 400", async t => {
  const server = createApp().listen(0, "127.0.0.1");
  t.after(() => server.close());
  await new Promise(resolve => server.once("listening", resolve));
  const res = await fetch(`http://127.0.0.1:${server.address().port}/api/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: "{bad json"
  });
  assert.equal(res.status, 400);
  assert.match((await res.json()).error, /не является JSON/);
});

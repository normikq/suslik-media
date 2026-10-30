"use strict";

// Сервер под путём (https://домен/путь): nginx сообщает путь заголовком X-Forwarded-Prefix (docs/MIGRATION.md)
const os = require("os");
const fs = require("fs");
const path = require("path");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-prefix-test-"));
process.env.TRUST_PROXY = "1";
process.env.SITE_PREFIX = "/media";
process.env.DISCORD_CLIENT_ID = "123";
process.env.DISCORD_CLIENT_SECRET = "test-secret";
delete process.env.DISCORD_REDIRECT_URI;

const http = require("http");
const test = require("node:test");
const assert = require("node:assert/strict");
const { basePath, publicBase } = require("../src/utils/helpers");

const fakeReq = (headers, trust = 1) => ({
  app: { get: k => (k === "trust proxy" ? trust : undefined) },
  get: h => headers[h.toLowerCase()]
});

test("basePath: путь из заголовка только за доверенным прокси и только простой", () => {
  assert.equal(basePath(fakeReq({ "x-forwarded-prefix": "/suslik" })), "/suslik");
  assert.equal(basePath(fakeReq({ "x-forwarded-prefix": "/a/b-c_1/" })), "/a/b-c_1");
  assert.equal(basePath(fakeReq({ "x-forwarded-prefix": "/suslik" }, false)), ""); // без TRUST_PROXY не верим
  assert.equal(basePath(fakeReq({})), "");
  for (const bad of ["suslik", "//evil.example", "/../x", "/a b", "/x?y", "/x#y", "https://evil", "/%2e%2e", "/."]) {
    assert.equal(basePath(fakeReq({ "x-forwarded-prefix": bad })), "", bad);
  }
  assert.equal(publicBase(fakeReq({ host: "new.test", "x-forwarded-prefix": "/suslik" })), "https://new.test/suslik");
  assert.equal(publicBase(fakeReq({ host: "old.test" })), "https://old.test");
});

test("Discord и код устройства: адреса с путём сервера, без пути — как раньше", async () => {
  const { createApp } = require("../src/app");
  const server = createApp().listen(0, "127.0.0.1");
  await new Promise(r => server.once("listening", r));
  const port = server.address().port;
  // fetch не даёт задать Host (его ставит nginx), поэтому http.request
  const send = (method, url, headers, body) => new Promise((resolve, reject) => {
    const req = http.request({ host: "127.0.0.1", port, method, path: url, headers }, res => {
      let text = "";
      res.setEncoding("utf8");
      res.on("data", c => { text += c; });
      res.on("end", () => resolve({ status: res.statusCode, headers: res.headers, text }));
    });
    req.on("error", reject);
    req.end(body);
  });
  try {
    const start = async headers => {
      const r = await send("GET", "/api/auth/discord/start", { "X-Forwarded-Proto": "https", ...headers });
      assert.equal(r.status, 302);
      return new URL(r.headers.location).searchParams.get("redirect_uri");
    };
    assert.equal(await start({ Host: "new.test", "X-Forwarded-Prefix": "/suslik" }), "https://new.test/suslik/api/auth/discord/callback");
    assert.equal(await start({ Host: "old.test" }), "https://old.test/api/auth/discord/callback");

    const device = async headers => {
      const body = JSON.stringify({ name: "Тест" });
      const r = await send("POST", "/api/auth/device",
        { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body), "X-Forwarded-Proto": "https", ...headers }, body);
      assert.equal(r.status, 200);
      return JSON.parse(r.text).verification_url;
    };
    assert.match(await device({ Host: "new.test", "X-Forwarded-Prefix": "/suslik" }), /^https:\/\/new\.test\/suslik\/media\/link\?code=/);
    assert.match(await device({ Host: "old.test" }), /^https:\/\/old\.test\/media\/link\?code=/);
  } finally {
    server.close();
  }
});

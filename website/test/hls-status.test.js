"use strict";

const os = require("os");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "suslik-hls-admin-test-"));
process.env.SESSION_SECRET = "test-session-secret-do-not-use";
process.env.HLS_ENABLED = "true";
process.env.HLS_CDN_URL = "https://cdn.example.org/some/path";
process.env.HLS_SIGN_SECRET = "test-secret-do-not-use";
process.env.HLS_WORKER_TOKEN = "worker-token-test";
process.env.HLS_CDN_TOKEN = "cdn-token-test";
process.env.AUTO_CLEAN = "false";
delete process.env.HLS_BETA_USERS;

const test = require("node:test");
const assert = require("node:assert/strict");

const config = require("../src/config");
const hls = require("../src/services/hls");
const store = require("../src/services/hls-store");
const ui = require("../public/hls-admin.js");

// Очищаем хранилище перед каждым тестом
test.beforeEach(() => {
  store._reset();
});

test("eta_s сохраняется только на стадии encode", () => {
  hls.enqueue({
    ad_id: "900001",
    path: "A.mkv",
    size: 1000,
    priority: 0,
    hls_id: "aaaaaaaaaaaaaaa1",
    profile: "v1"
  });
  const job = hls.next();
  assert.ok(job);

  // На стадии probe eta_s сбрасывается в null
  hls.progress({ job_id: job.job_id, stage: "probe", progress: 0, eta_s: 30 });
  assert.equal(store.getJob(job.job_id).eta_s, null);

  // На стадии encode eta_s сохраняется
  hls.progress({ job_id: job.job_id, stage: "encode", progress: 0.1, eta_s: 1020 });
  assert.equal(store.getJob(job.job_id).eta_s, 1020);

  // На стадии upload eta_s снова сбрасывается в null
  hls.progress({ job_id: job.job_id, stage: "upload", progress: 0.9, eta_s: 5 });
  assert.equal(store.getJob(job.job_id).eta_s, null);
});

test("статус версии в adminState: queued, encoding и удаление профиля при failed", () => {
  const hlsId = "aaaaaaaaaaaaaaa2";
  store.putAsset(hlsId, {
    profiles: {
      v1: { status: "encoding", created_at: 1 }
    }
  });

  hls.enqueue({
    ad_id: "900002",
    path: "B.mkv",
    size: 1000,
    priority: 2,
    hls_id: hlsId,
    profile: "v1"
  });

  // Пока задание в очереди, статус версии — queued
  const stateQueued = hls.adminState();
  const assetQueued = stateQueued.assets.find(a => a.hls_id === hlsId);
  assert.ok(assetQueued);
  assert.equal(assetQueued.profiles.v1.status, "queued");

  // После взятия задания воркером статус меняется на encoding
  const job = hls.next();
  assert.ok(job);
  const stateEncoding = hls.adminState();
  const assetEncoding = stateEncoding.assets.find(a => a.hls_id === hlsId);
  assert.ok(assetEncoding);
  assert.equal(assetEncoding.profiles.v1.status, "encoding");
  assert.notEqual(assetQueued.profiles.v1.status, assetEncoding.profiles.v1.status);

  // При окончательной ошибке (retryable: false) профиль encoding удаляется из ассета
  hls.failed({ job_id: job.job_id, stage: "encode", error: "x", retryable: false });
  const stateFailed = hls.adminState();
  const assetFailed = stateFailed.assets.find(a => a.hls_id === hlsId);
  assert.ok(assetFailed);
  assert.equal(assetFailed.profiles.v1, undefined);
});

test("hls-admin.js: тексты статусов queued и failed", () => {
  assert.equal(ui.profileStatusText("queued"), "в очереди");
  assert.equal(ui.profileStatusText("failed"), "ошибка");
});

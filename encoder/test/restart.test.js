'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { runLoop, cleanWork, RESTART_MARK } = require('../src/worker');

const log = { info() {}, warn() {}, error() {} };

function makeTmp() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'sm-restart-'));
}

test('runLoop: выходит с "restart" и удаляет метку .restart до вызова api.next', async () => {
  const tmp = makeTmp();
  const cfg = { workDir: tmp, once: false, idleSec: 0.01, errorSec: 0.01 };
  try {
    fs.writeFileSync(path.join(tmp, RESTART_MARK), '');
    let calls = 0;
    const api = {
      next: async () => {
        calls++;
        return null;
      },
    };
    const r = await runLoop({ cfg, api, log });
    assert.equal(r, 'restart');
    assert.equal(calls, 0);
    assert.equal(fs.existsSync(path.join(tmp, RESTART_MARK)), false);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('runLoop: метка .restart появляется во время простоя и цикл завершается', async () => {
  const tmp = makeTmp();
  const cfg = { workDir: tmp, once: false, idleSec: 0.01, errorSec: 0.01 };
  try {
    let calls = 0;
    const api = {
      next: async () => {
        calls++;
        if (calls === 1) {
          fs.writeFileSync(path.join(tmp, RESTART_MARK), '');
          return null;
        }
        throw new Error('не должен вызываться');
      },
    };
    const r = await runLoop({ cfg, api, log });
    assert.equal(r, 'restart');
    assert.equal(calls, 1);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('cleanWork: удаляет временные файлы, но сохраняет .lock и .restart', () => {
  const tmp = makeTmp();
  try {
    fs.mkdirSync(path.join(tmp, 'job_1'));
    fs.writeFileSync(path.join(tmp, 'job_1', 'part.ts'), 'data');
    fs.writeFileSync(path.join(tmp, 'x'), 'temp');
    fs.writeFileSync(path.join(tmp, '.lock'), '12345');
    fs.writeFileSync(path.join(tmp, RESTART_MARK), '');

    cleanWork(tmp);

    assert.equal(fs.existsSync(path.join(tmp, 'job_1')), false);
    assert.equal(fs.existsSync(path.join(tmp, 'x')), false);
    assert.equal(fs.existsSync(path.join(tmp, '.lock')), true);
    assert.equal(fs.existsSync(path.join(tmp, RESTART_MARK)), true);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

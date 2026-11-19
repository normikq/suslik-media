'use strict';

// Прогресс выгрузки на CDN: rsync --info=progress2 → { progress, eta_s } для админки.
const test = require('node:test');
const assert = require('node:assert/strict');
const { parseRsyncProgress } = require('../src/upload');
const { run } = require('../src/proc');

test('parseRsyncProgress: строки rsync --info=progress2', () => {
  assert.deepEqual(parseRsyncProgress('  1,234,567,890  45%   11.20MB/s    0:01:23  (xfr#3, to-chk=2/9)'),
    { progress: 0.45, eta_s: 83, speed: '11.20MB/s' });
  assert.deepEqual(parseRsyncProgress('              0   0%    0.00kB/s    0:00:00 (xfr#0, ir-chk=1000/1001)'),
    { progress: 0, eta_s: 0, speed: '0.00kB/s' });
  assert.deepEqual(parseRsyncProgress('  8,980,206,963 100%   10.85MB/s    0:13:09 (xfr#14, to-chk=0/31)'),
    { progress: 1, eta_s: 0, speed: '10.85MB/s' }); // в последней строке время затраченное — оставшееся 0
  assert.deepEqual(parseRsyncProgress('     52,428,800   2%   49.96MB/s    1:05:07'),
    { progress: 0.02, eta_s: 3907, speed: '49.96MB/s' });
  assert.equal(parseRsyncProgress('sending incremental file list'), null);
  assert.equal(parseRsyncProgress(''), null);
  assert.equal(parseRsyncProgress(undefined), null);
});

test('run(onLine): строки, разделённые CR (перерисовка прогресса), отдаются по одной', async () => {
  const lines = [];
  // CR/LF собираются в дочернем процессе: обратные слэши в аргументах на Windows искажаются при передаче
  const script = "const R=String.fromCharCode(13),N=String.fromCharCode(10);process.stdout.write('  10  10%  1.00MB/s  0:00:09'+R+'  50  50%  1.00MB/s  0:00:05'+R+'  100 100%  1.00MB/s  0:00:10'+N+'ok'+R+N)";
  const r = await run(process.execPath, ['-e', script], { onLine: (l) => lines.push(l) });
  assert.equal(r.code, 0);
  assert.deepEqual(lines, ['  10  10%  1.00MB/s  0:00:09', '  50  50%  1.00MB/s  0:00:05', '  100 100%  1.00MB/s  0:00:10', 'ok']);
});

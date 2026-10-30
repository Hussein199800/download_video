import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fmtDur, fmtBytes, progressLine } from '../../src/ui.js';

test('تنسيق المدة والحجم', () => {
  assert.equal(fmtDur(245), '4:05');
  assert.equal(fmtDur(3725), '1:02:05');
  assert.equal(fmtDur(null), '');
  assert.equal(fmtBytes(1572864), '1.5 م.ب');
  assert.equal(fmtBytes(0), '');
  assert.equal(fmtBytes(512), '512 ب');
});

test('سطر التقدم: كم حُمّل وكم بقي والسرعة والوقت', () => {
  assert.equal(progressLine({ downloaded: 24538316, total: 61865984, speed: 2202009, eta: 17 }),
    'حُمّل 23.4 م.ب من 59.0 م.ب · بقي 35.6 م.ب · 2.1 م.ب/ث · 0:17');
  assert.equal(progressLine({ downloaded: 1048576, total: 0, speed: 0, eta: -1 }), 'حُمّل 1.0 م.ب');
  assert.equal(progressLine({ downloaded: 0, total: 0, speed: 0, eta: -1 }), 'يبدأ…');
});

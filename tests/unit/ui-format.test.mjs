import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fmtDur, fmtBytes } from '../../src/ui.js';

test('تنسيق المدة والحجم', () => {
  assert.equal(fmtDur(245), '4:05');
  assert.equal(fmtDur(3725), '1:02:05');
  assert.equal(fmtDur(null), '');
  assert.equal(fmtBytes(1572864), '1.5 م.ب');
  assert.equal(fmtBytes(0), '');
  assert.equal(fmtBytes(512), '512 ب');
});

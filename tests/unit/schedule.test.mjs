import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nextAt, scheduleLabel, retryLabel } from '../../src/schedule.js';

const at = (y, mo, d, h, mi) => new Date(y, mo - 1, d, h, mi).getTime();

test('وقت الجدولة: اليوم إن لم يمضِ، وإلا غداً', () => {
  const now = at(2026, 10, 30, 1, 30);
  assert.equal(nextAt('02:00', now), at(2026, 10, 30, 2, 0));
  assert.equal(nextAt('01:00', now), at(2026, 10, 31, 1, 0));
  assert.equal(nextAt('01:30', now), at(2026, 10, 31, 1, 30));
});

test('عنوان المهمة المجدولة', () => {
  const now = at(2026, 10, 30, 12, 0);
  assert.equal(scheduleLabel({ scheduledAt: at(2026, 10, 31, 2, 0), wifiOnly: true }, now), 'مجدول: غداً 02:00 · واي فاي فقط');
  assert.equal(scheduleLabel({ scheduledAt: at(2026, 10, 30, 14, 5), wifiOnly: false }, now), 'مجدول: اليوم 14:05');
  assert.equal(scheduleLabel({ scheduledAt: 0, wifiOnly: true }, now), 'واي فاي فقط');
  assert.equal(scheduleLabel({ scheduledAt: -1, wifiOnly: false }, now), '');
});

test('عنوان إعادة المحاولة', () => {
  assert.equal(retryLabel({ attempts: 2, retryAt: 105000 }, 0, 5), 'المحاولة 2 من 5 بعد 1:45');
  assert.equal(retryLabel({ attempts: 1, retryAt: 0 }, 1000, 3), 'المحاولة 1 من 3 الآن');
});

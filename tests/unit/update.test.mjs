import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fetchLatest, isNewer, shouldCheck, DOWNLOAD_PREFIX } from '../../src/update.js';

const rel = (assets, tag = 'v1.2.0') => async () => ({ ok: true, status: 200, json: async () => ({ tag_name: tag, body: 'جديد', published_at: '2026-10-29', assets }) });
const asset = (name, url = DOWNLOAD_PREFIX + 'v1.2.0/' + name) => ({ name, browser_download_url: url, size: 10 });

test('يختار ملف المعالج المناسب', async () => {
  const f = rel([asset('sayl-1.2.0-armeabi-v7a.apk'), asset('sayl-1.2.0-arm64-v8a.apk'), asset('sayl-debug-arm64-v8a.apk')]);
  assert.equal((await fetchLatest(f, 'arm64-v8a')).url, DOWNLOAD_PREFIX + 'v1.2.0/sayl-1.2.0-arm64-v8a.apk');
  assert.equal((await fetchLatest(f, 'armeabi-v7a')).url, DOWNLOAD_PREFIX + 'v1.2.0/sayl-1.2.0-armeabi-v7a.apk');
  assert.equal((await fetchLatest(f, 'arm64-v8a')).version, '1.2.0');
});

test('يرفض النسخ التجريبية والروابط الغريبة', async () => {
  assert.equal(await fetchLatest(rel([asset('sayl-debug-arm64-v8a.apk')]), 'arm64-v8a'), null);
  assert.equal(await fetchLatest(rel([asset('sayl-1.2.0-arm64-v8a.apk', 'https://evil.com/sayl-1.2.0-arm64-v8a.apk')]), 'arm64-v8a'), null);
  assert.equal(await fetchLatest(async () => ({ ok: false, status: 404 }), 'arm64-v8a'), null);
});

test('مقارنة الإصدارات', () => {
  assert.equal(isNewer('1.2.0', '1.10.0'), false);
  assert.equal(isNewer('1.10.0', '1.2.0'), true);
  assert.equal(isNewer('1.0.0', '1.0.0'), false);
});

test('موعد الفحص التلقائي', () => {
  const h = 3600e3;
  assert.equal(shouldCheck(0, 100 * h, 12), true);
  assert.equal(shouldCheck(88 * h, 100 * h, 12), true);
  assert.equal(shouldCheck(95 * h, 100 * h, 12), false);
});

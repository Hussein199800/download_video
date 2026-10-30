import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

const mem = new Map();
globalThis.localStorage = { getItem: (k) => (mem.has(k) ? mem.get(k) : null), setItem: (k, v) => mem.set(k, String(v)), removeItem: (k) => mem.delete(k) };
const { getSettings, setSettings, DEFAULTS, getMeta, setMeta } = await import('../../src/store.js');

beforeEach(() => mem.clear());

test('القيم الافتراضية عند التخزين الفارغ أو التالف', () => {
  assert.deepEqual(getSettings(), DEFAULTS);
  mem.set('sayl.settings', '{not json');
  assert.deepEqual(getSettings(), DEFAULTS);
  assert.deepEqual(DEFAULTS, { theme: 'auto', fontScale: 1, video: 'best', audio: 'mp3', kbps: 192, parallel: 1, aria2: false, clipboard: true,
    connections: 4, retries: 5, window: { on: false, from: '01:00', to: '07:00' } });
});

test('الحفظ مع حصر القيم في حدودها', () => {
  setSettings({ parallel: 9, fontScale: 3 });
  assert.equal(getSettings().parallel, 3);
  assert.equal(getSettings().fontScale, 1.3);
  setSettings({ parallel: 0, fontScale: 0.1, theme: 'ugly', kbps: 999, audio: 'flac' });
  const s = getSettings();
  assert.equal(s.parallel, 1);
  assert.equal(s.fontScale, 0.85);
  assert.equal(s.theme, 'auto');
  assert.equal(s.kbps, 192);
  assert.equal(s.audio, 'mp3');
  setSettings({ theme: 'dark', video: 720 });
  assert.equal(getSettings().theme, 'dark');
  assert.equal(getSettings().video, 720);
});

test('الاتصالات والمحاولات ونافذة التنزيل ضمن القيم المسموحة', () => {
  setSettings({ connections: 16, retries: 7, window: { on: true, from: '25:00', to: '06:30' } });
  const s = getSettings();
  assert.equal(s.connections, 4);
  assert.equal(s.retries, 5);
  assert.deepEqual(s.window, { on: true, from: '01:00', to: '06:30' });
  setSettings({ connections: 8, retries: 0 });
  assert.equal(getSettings().connections, 8);
  assert.equal(getSettings().retries, 0);
});

test('بيانات داخلية (أوقات الفحص) منفصلة عن الإعدادات', () => {
  setMeta({ appCheckAt: 5 });
  assert.equal(getMeta().appCheckAt, 5);
  assert.equal(getSettings().appCheckAt, undefined);
});

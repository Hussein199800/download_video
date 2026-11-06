import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { V_OK, A_OK, videoMode, audioCopy, canConcatCopy, resName } from '../../src/mediarules.js';
import { layoutFor } from '../../src/layout.js';
import { sortItems, filterItems } from '../../src/views/library.js';

// جدولا النسخ في الواجهة يطابقان MediaArgs.kt حرفياً، حتى لا تعد الواجهة بنسخ سريع لا يحدث
test('قواعد النسخ في الواجهة مطابقة لـ Kotlin', () => {
  const kt = readFileSync(new URL('../../android/app/src/main/java/com/sayl/downloader/MediaArgs.kt', import.meta.url), 'utf8');
  const table = (name) => {
    const body = kt.slice(kt.indexOf(`private val ${name} = mapOf(`)).split('\n    )')[0];
    return Object.fromEntries([...body.matchAll(/"(\w+)" to (null|setOf\(([^)]*)\))/g)].map((m) => [m[1], m[2] === 'null' ? null : m[3].match(/"([^"]+)"/g).map((x) => x.slice(1, -1))]));
  };
  assert.deepEqual(table('V_OK'), V_OK);
  assert.deepEqual(table('A_OK'), A_OK);
});

test('النسخ أو إعادة الترميز', () => {
  const p = (v, a) => ({ video: v && { codec: v }, audio: a && { codec: a } });
  assert.equal(videoMode('mkv', p('vp9', 'opus')), 'copy');
  assert.equal(videoMode('mp4', p('h264', 'vorbis')), 'audio');
  assert.equal(videoMode('webm', p('h264', 'aac')), 'encode');
  assert.ok(audioCopy('m4a', 'aac'));
  assert.ok(!audioCopy('m4a', 'aac', { kbps: 128 }));
  const a = { video: { codec: 'h264', width: 1280, height: 720 }, audio: { codec: 'aac', sampleRate: 48000, channels: 2 } };
  assert.ok(canConcatCopy([a, a]));
  assert.ok(!canConcatCopy([a, { ...a, video: { ...a.video, width: 640 } }]));
  assert.equal(resName(1920, 1080), '1080p');
  assert.equal(resName(1080, 1920), '1080p');
  assert.equal(resName(3840, 2160), '4K');
});

test('التخطيط: جوال، ولوحي عمودي مكبّر، وعمودان بالعرض', () => {
  assert.deepEqual(layoutFor(390, 844), { two: false, k: 1 });
  assert.deepEqual(layoutFor(320, 640), { two: false, k: 1 });
  assert.deepEqual(layoutFor(844, 390), { two: true, k: 1 });
  const tp = layoutFor(800, 1280);
  assert.equal(tp.two, false);
  // اللوحي العمودي = تصميم الجوال بعرض منطقي 560
  assert.equal(Math.round(800 / tp.k), 560);
  const tl = layoutFor(1280, 800);
  assert.equal(tl.two, true);
  assert.ok(tl.k > 1 && tl.k <= 1.5);
});

test('فرز المكتبة وتصفيتها', () => {
  const items = [
    { name: 'ب', size: 3, date: 1, duration: 10, kind: 'video', folder: '' },
    { name: 'أ', size: 1, date: 3, duration: 30, kind: 'audio', folder: '' },
    { name: 'محاضرة', size: 2, date: 2, duration: 0, kind: 'video', folder: 'دروس' },
  ];
  assert.deepEqual(sortItems(items, 'size', 'desc').map((x) => x.size), [3, 2, 1]);
  assert.deepEqual(sortItems(items, 'date', 'asc').map((x) => x.date), [1, 2, 3]);
  assert.deepEqual(sortItems(items, 'name', 'asc').map((x) => x.name), ['أ', 'ب', 'محاضرة']);
  assert.equal(filterItems(items, { kind: 'video', min: 0, q: '' }).length, 2);
  assert.equal(filterItems(items, { kind: 'all', min: 2, q: '' }).length, 2);
  assert.equal(filterItems(items, { kind: 'all', min: 0, q: 'محاضره' }).length, 1);
  assert.equal(filterItems(items, { kind: 'all', min: 0, q: 'دروس' }).length, 1);
});

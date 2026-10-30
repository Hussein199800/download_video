import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { buildArgs, parseInfo, OUTPUT_TEMPLATE } from '../../src/formats.js';

const fixture = (n) => JSON.parse(readFileSync(new URL(`../fixtures/${n}.json`, import.meta.url)));
const S = { aria2: false, connections: 4 };
const pair = (args, flag) => args[args.indexOf(flag) + 1];
const FORBIDDEN = ['-o', '--output', '--exec', '-P', '--paths'];

test('فيديو بدقة محددة', () => {
  const a = buildArgs({ type: 'video', height: 720 }, S);
  assert.equal(pair(a, '-f'), 'bv*[height<=720]+ba/b[height<=720]/b');
  assert.equal(pair(a, '--merge-output-format'), 'mp4');
  assert.equal(pair(a, '--retries'), '10');
  assert.equal(pair(a, '--fragment-retries'), '10');
  assert.ok(a.includes('--windows-filenames'));
  assert.ok(a.includes('--no-playlist'));
  assert.equal(pair(buildArgs({ type: 'video', height: 'best' }, S), '-f'), 'bv*+ba/b');
});

test('صوت MP3 وM4A', () => {
  const mp3 = buildArgs({ type: 'audio', format: 'mp3', kbps: 192 }, S);
  assert.ok(mp3.includes('-x'));
  assert.equal(pair(mp3, '--audio-format'), 'mp3');
  assert.equal(pair(mp3, '--audio-quality'), '192K');
  const m4a = buildArgs({ type: 'audio', format: 'm4a' }, S);
  assert.equal(pair(m4a, '-f'), 'ba[ext=m4a]/ba');
  assert.equal(pair(m4a, '--audio-format'), 'm4a');
  assert.ok(!m4a.includes('--audio-quality'));
});

test('الصورة المصغرة تُدمج في MP3 فقط، والفيديو يفضّل H.264', () => {
  assert.ok(buildArgs({ type: 'audio', format: 'mp3', kbps: 192 }, S).includes('--embed-thumbnail'));
  assert.ok(!buildArgs({ type: 'audio', format: 'm4a' }, S).includes('--embed-thumbnail'));
  assert.equal(pair(buildArgs({ type: 'video', height: 720 }, S), '-S'), 'res,vcodec:h264,acodec:m4a');
});

test('المنزّل الأصلي افتراضياً مع أجزاء متوازية وتقسيم يتجنب خنق يوتيوب', () => {
  const a = buildArgs({ type: 'video', height: 'best' }, S);
  assert.equal(pair(a, '-N'), '4');
  assert.equal(pair(a, '--http-chunk-size'), '10M');
  assert.ok(!a.includes('--downloader'));
  assert.equal(pair(buildArgs({ type: 'video', height: 'best' }, { connections: 8 }), '-N'), '8');
});

test('aria2c اختياري بعدد الاتصالات المختار', () => {
  const a = buildArgs({ type: 'video', height: 'best' }, { aria2: true, connections: 8 });
  assert.equal(pair(a, '--downloader'), 'libaria2c.so');
  assert.equal(pair(a, '--downloader-args'), 'aria2c:-x8 -s8');
  assert.ok(!a.includes('--http-chunk-size'));
});

test('لا تحتوي الأوامر على مسار الإخراج أو تنفيذ أوامر', () => {
  for (const c of [{ type: 'video', height: 1080 }, { type: 'audio', format: 'mp3', kbps: 320 }, { type: 'audio', format: 'm4a' }]) {
    const a = buildArgs(c, { aria2: true, connections: 4 });
    for (const f of FORBIDDEN) assert.ok(!a.includes(f), `${f} in ${a}`);
  }
});

test('قالب الاسم يقصّ العنوان الطويل', () => {
  assert.equal(OUTPUT_TEMPLATE, '%(title).150B [%(id)s].%(ext)s');
});

test('تحليل مقطع واحد', () => {
  const i = parseInfo(fixture('single'));
  assert.equal(i.kind, 'single');
  assert.equal(i.title, 'عنوان المقطع');
  assert.equal(i.uploader, 'القناة');
  assert.equal(i.duration, 212);
  assert.equal(i.url, 'https://www.youtube.com/watch?v=dQw4w9WgXcQ');
  assert.deepEqual(i.heights, [1080, 720, 360]);
});

test('تحليل قائمة تشغيل مع مقاطع غير متاحة', () => {
  const p = parseInfo(fixture('playlist'));
  assert.equal(p.kind, 'playlist');
  assert.equal(p.title, 'قائمتي');
  assert.equal(p.count, 3);
  assert.deepEqual(p.entries.map((e) => e.available), [true, false, false]);
  assert.equal(p.entries[0].url, 'https://www.youtube.com/watch?v=a1');
});

test('قائمة مدخلاتها روابط بلا عنوان url تُبنى من المعرّف', () => {
  const p = parseInfo({ _type: 'playlist', id: 'P', title: 'T', webpage_url: 'https://www.youtube.com/playlist?list=P', entries: [{ id: 'z9', title: 'x', ie_key: 'Youtube' }] });
  assert.equal(p.entries[0].url, 'https://www.youtube.com/watch?v=z9');
});

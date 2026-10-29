import { test } from 'node:test';
import assert from 'node:assert/strict';
import { extractUrl, siteOf } from '../../src/links.js';

test('يستخرج الرابط من نص المشاركة', () => {
  assert.equal(extractUrl('شاهد هذا https://vt.tiktok.com/ZS123/ 🔥'), 'https://vt.tiktok.com/ZS123/');
  assert.equal(extractUrl('لا رابط'), null);
  assert.equal(extractUrl('(https://youtu.be/abc).'), 'https://youtu.be/abc');
  assert.equal(extractUrl('https://www.youtube.com/watch?v=x&list=PL1،'), 'https://www.youtube.com/watch?v=x&list=PL1');
  assert.equal(extractUrl(''), null);
  assert.equal(extractUrl(null), null);
});

test('يتعرف على الموقع', () => {
  assert.equal(siteOf('https://m.youtube.com/watch?v=x').id, 'youtube');
  assert.equal(siteOf('https://youtu.be/x').id, 'youtube');
  assert.equal(siteOf('https://fb.watch/x').id, 'facebook');
  assert.equal(siteOf('https://web.facebook.com/x').id, 'facebook');
  assert.equal(siteOf('https://vm.tiktok.com/x').id, 'tiktok');
  assert.equal(siteOf('https://instagr.am/p/x').id, 'instagram');
  assert.equal(siteOf('https://www.instagram.com/reel/x').id, 'instagram');
  assert.equal(siteOf('https://x.com/a/status/1').id, 'x');
  assert.equal(siteOf('https://vimeo.com/1').id, 'other');
  assert.equal(siteOf('ليس رابطاً').id, 'other');
  assert.equal(siteOf('https://youtu.be/x').name, 'يوتيوب');
});

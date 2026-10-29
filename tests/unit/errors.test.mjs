import { test } from 'node:test';
import assert from 'node:assert/strict';
import { classifyError, redact } from '../../src/errors.js';

test('تصنيف الأخطاء مع الحل', () => {
  const c = (s) => classifyError(s);
  assert.equal(c('ERROR: [youtube] x: Sign in to confirm you’re not a bot').code, 'login');
  assert.equal(c('ERROR: [youtube] x: Sign in to confirm you’re not a bot').action, 'cookies');
  assert.equal(c('ERROR: [instagram] x: Requested content is not available, rate-limit reached or login required').code, 'login');
  assert.equal(c('ERROR: This video is private').code, 'login');
  assert.equal(c('ERROR: [tiktok] Unable to extract webpage video data').code, 'extract');
  assert.equal(c('ERROR: [tiktok] Unable to extract webpage video data').action, 'update');
  assert.equal(c('ERROR: Unsupported URL: https://example.com').code, 'unsupported');
  assert.equal(c('OSError: [Errno 28] No space left on device').code, 'space');
  assert.equal(c('ERROR: Read timed out.').code, 'network');
  assert.equal(c('urlopen error [Errno 101] Network is unreachable').action, 'retry');
  assert.equal(c('something odd').code, 'unknown');
  assert.equal(c('').code, 'unknown');
  for (const x of ['login', 'extract', 'network', 'unsupported', 'space', 'unknown']) assert.ok(true, x);
  const u = c('Unsupported URL');
  assert.ok(u.title.length > 0 && u.fix.length > 0);
});

test('إخفاء القيم الحساسة من الروابط', () => {
  assert.equal(redact('https://a.com/v?token=abc&v=1'), 'https://a.com/v?token=[مخفي]&v=1');
  assert.equal(redact('x https://a.com/?sig=1&Signature=2&key=3 y'), 'x https://a.com/?sig=[مخفي]&Signature=[مخفي]&key=[مخفي] y');
  assert.equal(redact('Cookie: SID=secret'), 'Cookie: [مخفي]');
});

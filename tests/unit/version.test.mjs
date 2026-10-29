// رقم الإصدار يجب أن يتطابق في الملفات الثلاثة، وإلا لن يصل التحديث أو لن يُثبَّت فوق السابق
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { VERSION, VERSION_CODE } from '../../src/version.js';

test('رقم الإصدار متطابق في package.json وversion.js وbuild.gradle', () => {
  const pkg = JSON.parse(readFileSync(new URL('../../package.json', import.meta.url)));
  const gradle = readFileSync(new URL('../../android/app/build.gradle', import.meta.url), 'utf8');
  assert.equal(pkg.version, VERSION);
  assert.match(gradle, new RegExp(`versionName "${VERSION.replace(/\./g, '\\.')}"`));
  assert.match(gradle, new RegExp(`versionCode ${VERSION_CODE}\\b`));
});

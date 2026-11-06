import { test } from 'node:test';
import assert from 'node:assert/strict';
import { count, W, toLatinDigits, parseNum, parseTime, timeField, normalize, matches } from '../../src/text.js';

test('صيغة المعدود بالعربية', () => {
  const hour = ['ساعة', 'ساعتان', 'ساعات', 'ساعة'];
  assert.equal(count(1, hour), 'ساعة');
  assert.equal(count(2, hour), 'ساعتان');
  assert.equal(count(3, hour), '3 ساعات');
  assert.equal(count(10, hour), '10 ساعات');
  assert.equal(count(11, hour), '11 ساعة');
  assert.equal(count(100, hour), '100 ساعة');
  assert.equal(count(103, hour), '103 ساعات');
  assert.equal(count(0, W.file), '0 ملفات');
  assert.equal(count(25, W.file), '25 ملفاً');
});

test('الأرقام العربية والفارسية والفاصلة العشرية', () => {
  assert.equal(toLatinDigits('٠١٢٣٤٥٦٧٨٩'), '0123456789');
  assert.equal(toLatinDigits('۰۱۲۳۴۵۶۷۸۹'), '0123456789');
  assert.equal(parseNum('٤٫٥'), 4.5);
  assert.equal(parseNum('4,5'), 4.5);
  assert.equal(parseNum('4.'), 4);
  assert.ok(Number.isNaN(parseNum('4.4.4')));
  assert.ok(Number.isNaN(parseNum('abc')));
});

test('قراءة الوقت المكتوب', () => {
  assert.equal(parseTime('90'), 90);
  assert.equal(parseTime('1:30'), 90);
  assert.equal(parseTime('١:٣٠'), 90);
  assert.equal(parseTime('1:02:03'), 3723);
  assert.equal(parseTime('٢:٠٥٫٥'), 125.5);
  assert.ok(Number.isNaN(parseTime('1:75')));
  assert.ok(Number.isNaN(parseTime('')));
  assert.ok(Number.isNaN(parseTime('1:')));
  assert.equal(timeField(125.5), '2:05.5');
  assert.equal(timeField(3723), '1:02:03');
  assert.equal(parseTime(timeField(3723.4)), 3723.4);
});

test('البحث يوحّد الهمزات والتاء المربوطة والألف المقصورة والتشكيل', () => {
  assert.equal(normalize('أُغنيةٌ إلى مَسْرَحِيّة'), 'اغنيه الي مسرحيه');
  assert.ok(matches('محاضرة الفيزياء', 'محاضره'));
  assert.ok(matches('إسلام', 'اسلام'));
  assert.ok(matches('مستشفى', 'مستشفي'));
  assert.ok(matches('مقطع ٣', 'مقطع 3'));
  assert.ok(matches('شيء', ''));
  assert.ok(!matches('رحلة', 'جبل'));
});

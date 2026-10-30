// اختبارات الواجهة في Chromium مع المحرك الوهمي (src/native.js خارج أندرويد).
// التشغيل: npm run test:e2e   (الصور تُحفظ في tests/e2e/out)
const http = require('http');
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

let playwright;
try { playwright = require('playwright'); } catch { playwright = require(path.join(execSync('npm root -g').toString().trim(), 'playwright')); }

const ROOT = path.join(__dirname, '../../www');
const OUT = path.join(__dirname, 'out');
fs.mkdirSync(OUT, { recursive: true });

const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.woff2': 'font/woff2', '.woff': 'font/woff', '.svg': 'image/svg+xml' };
const server = http.createServer((req, res) => {
  const p = path.join(ROOT, decodeURIComponent(new URL(req.url, 'http://x').pathname));
  const file = p.endsWith(path.sep) || p.endsWith('/') ? path.join(p, 'index.html') : p;
  if (!file.startsWith(ROOT) || !fs.existsSync(file)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});

const VER = require('../../package.json').version;
const ABOUT = 'تطوير: حسين محمد علي الطويل. اللهم اغفر له ولوالديه، واجعل هذا العمل صدقة جارية في ميزان حسناتهم.';

let failures = 0;
const results = [];
function check(name, cond, info = '') {
  results.push(`${cond ? '✓' : '✗'} ${name}${!cond && info ? ' — ' + info : ''}`);
  if (!cond) failures++;
}

async function newPage(browser, base, { scheme = 'dark', storage = {}, viewport = { width: 390, height: 844 } } = {}) {
  const ctx = await browser.newContext({ viewport, deviceScaleFactor: 1, colorScheme: scheme });
  await ctx.route('https://api.github.com/**', (r) => r.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify({ tag_name: 'v' + VER, body: '', assets: [] }) }));
  await ctx.addInitScript((s) => { for (const [k, v] of Object.entries(s)) localStorage.setItem(k, v); }, storage);
  const page = await ctx.newPage();
  page.errors = [];
  page.on('pageerror', (e) => page.errors.push(e.message));
  page.on('console', (m) => m.type() === 'error' && page.errors.push(m.text()));
  await page.goto(base);
  await page.waitForSelector('.tabbar');
  return page;
}

// انتظار انتهاء حركة دخول الصفحة قبل التصوير
const shot = async (page, opts) => { await page.waitForTimeout(400); await page.screenshot(opts); };
const text = (page) => page.evaluate(() => document.body.innerText);

async function main() {
  await new Promise((r) => server.listen(0, r));
  const base = `http://127.0.0.1:${server.address().port}/`;
  const browser = await playwright.chromium.launch();
  try {
    // ---------- الرئيسية: رابط واحد ----------
    let page = await newPage(browser, base);
    await shot(page, { path: path.join(OUT, 'home-dark.png') });
    const wide = async () => page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
    check('لا تمرير أفقي في الرئيسية', !(await wide()));
    check('لا يظهر null في الرئيسية', !/\bnull\b/.test(await text(page)));
    check('الرئيسية تعرض البطاقات الثلاث', /فيديو/.test(await text(page)) && /صوت/.test(await text(page)) && /قائمة تشغيل/.test(await text(page)));
    await page.fill('#url', 'شاهد هذا https://youtu.be/demo1 🔥');
    await page.click('#go');
    await page.waitForSelector('.preview');
    check('لا تمرير أفقي مع المعاينة', !(await wide()));
    check('المعاينة تعرض العنوان', /مقطع تجريبي/.test(await text(page)));
    check('خيارات الدقة من المقطع', await page.locator('.preview [data-v="1080"]').count() === 1 && await page.locator('.preview [data-v="2160"]').count() === 0);
    await shot(page, { path: path.join(OUT, 'preview.png') });
    await page.click('#dl');
    await page.waitForTimeout(200);
    await page.click('.tabbar [data-tab="jobs"]');
    check('المهمة في التنزيلات', /مقطع تجريبي/.test(await text(page)));
    await page.waitForTimeout(1200);
    await page.click('.tabbar [data-tab="history"]');
    check('بعد الاكتمال تظهر في السجل', /مقطع تجريبي/.test(await text(page)));
    await shot(page, { path: path.join(OUT, 'history.png') });

    // تكرار الرابط نفسه أثناء التنزيل لا يُضاف مرتين
    await page.click('.tabbar [data-tab="home"]');
    for (let i = 0; i < 2; i++) {
      await page.fill('#url', 'https://youtu.be/dup');
      await page.click('#go');
      await page.waitForSelector('#dl');
      await page.click('#dl');
      await page.waitForTimeout(50);
    }
    check('الرابط المكرر يُرفض بإشعار', /موجود في الطابور/.test(await page.locator('.toast').innerText()));
    // تنزيل بطيء لرؤية الأحجام والسرعة
    await page.click('.tabbar [data-tab="home"]');
    await page.fill('#url', 'https://youtu.be/slow1');
    await page.click('#go');
    await page.waitForSelector('#dl');
    await page.click('#dl');
    await page.click('.tabbar [data-tab="jobs"]');
    await page.waitForTimeout(500);
    const jt0 = await text(page);
    check('التنزيل الجاري يعرض كم حُمّل ومن كم وكم بقي', /حُمّل [\d.]+ م.ب من 50.0 م.ب · بقي [\d.]+ م.ب/.test(jt0), jt0.slice(0, 300));
    check('رأس التنزيلات يعرض السرعة الكلية', /السرعة الآن/.test(jt0) && /1.5 م.ب\/ث/.test(jt0));
    await shot(page, { path: path.join(OUT, 'jobs-progress.png') });
    check('لا أخطاء في الصفحة (رابط واحد)', page.errors.length === 0, page.errors.join(' | '));
    await page.context().close();

    // ---------- مدير التنزيلات: إيقاف واستكمال وترتيب ----------
    page = await newPage(browser, base);
    const add = async (u) => { await page.click('.tabbar [data-tab="home"]'); await page.fill('#url', u); await page.click('#go'); await page.waitForSelector('#dl'); await page.click('#dl'); await page.waitForTimeout(80); };
    await add('https://youtu.be/slow-pause');
    for (const n of [1, 2, 3]) await add(`https://youtu.be/hold${n}`);
    await page.click('.tabbar [data-tab="jobs"]');
    await page.waitForTimeout(400);
    const slowCard = page.locator('.job', { hasText: 'slow-pause' }).first();
    const titles = async () => page.locator('.job .job-t small').allInnerTexts();
    await page.locator('.job').first().locator('[data-act="إيقاف"]').click();
    await page.waitForTimeout(300);
    check('الإيقاف المؤقت يظهر «موقوف»', /موقوف/.test(await page.locator('.job .tag', { hasText: 'موقوف' }).first().innerText().catch(() => '')));
    const before = (await titles()).filter((t) => /hold/.test(t));
    await page.locator('.job', { hasText: 'hold3' }).locator('[data-act="إلى رأس الطابور"]').click();
    await page.waitForTimeout(300);
    const after = (await titles()).filter((t) => /hold/.test(t));
    check('«إلى رأس الطابور» يقدّم المهمة', before[2]?.includes('hold3') && after[0]?.includes('hold3'), `${before} → ${after}`);
    await page.locator('.job', { hasText: 'hold1' }).locator('[data-act="للأسفل"]').click();
    await page.waitForTimeout(300);
    const after2 = (await titles()).filter((t) => /hold/.test(t));
    check('«للأسفل» يؤخر المهمة', after2.join('|').indexOf('hold1') > after2.join('|').indexOf('hold2'), String(after2));
    await page.locator('.job', { hasText: 'موقوف' }).first().locator('[data-act="استكمال"]').click();
    await page.waitForTimeout(400);
    check('الاستكمال يعيد التنزيل', !(await text(page)).includes('موقوف'));
    await page.locator('.job .job-t').first().click();
    check('صفحة التفاصيل تعرض السيرفر والحجم', /سيرفر 1/.test(await page.locator('.sheet').innerText()) && /الحجم/.test(await page.locator('.sheet').innerText()));
    await shot(page, { path: path.join(OUT, 'details.png') });
    await page.keyboard.press('Escape');
    // عدة روابط
    await page.click('.tabbar [data-tab="home"]');
    await page.click('#open-batch');
    await page.fill('#batch-text', 'https://youtu.be/m1\nنص https://youtu.be/m2 هنا\nhttps://youtu.be/m1\n');
    check('عداد الروابط يحذف المكرر', /أضف 2 إلى الطابور/.test(await page.locator('#batch-add').innerText()));
    await shot(page, { path: path.join(OUT, 'batch.png') });
    await page.click('#batch-add');
    await page.waitForTimeout(300);
    const bt = await text(page);
    check('الروابط المتعددة في الطابور', bt.includes('youtu.be/m1') && bt.includes('youtu.be/m2'));

    // الجدولة
    await page.click('.tabbar [data-tab="home"]');
    await page.fill('#url', 'https://youtu.be/sched1');
    await page.click('#go');
    await page.waitForSelector('#dl');
    await page.locator('.when .seg [data-v="true"]').click();
    await page.locator('.when .sw').check();
    await shot(page, { path: path.join(OUT, 'schedule.png'), fullPage: true });
    await page.click('#dl');
    await page.click('.tabbar [data-tab="jobs"]');
    await page.waitForTimeout(300);
    const sc = page.locator('.job', { hasText: 'sched1' });
    check('المجدول يظهر بوقته وشرط الواي فاي', /مجدول: (اليوم|غداً) 02:00 · واي فاي فقط/.test(await sc.innerText()), await sc.innerText());
    await sc.locator('[data-act="ابدأ الآن"]').click();
    await page.waitForTimeout(900);
    check('«ابدأ الآن» يبدأ المجدول', !/مجدول/.test(await text(page)));
    check('لا أخطاء في الصفحة (المدير)', page.errors.length === 0, page.errors.join(' | '));
    await page.context().close();

    // ---------- قائمة تشغيل ----------
    page = await newPage(browser, base, { scheme: 'light' });
    await page.fill('#url', 'https://www.youtube.com/playlist?list=PL1');
    await page.click('#go');
    await page.waitForSelector('.pl-list');
    check('القائمة تعرض 3 مقاطع', await page.locator('.pl-item').count() === 3);
    check('المقطع الخاص معطّل', await page.locator('.pl-item.off').count() === 1);
    check('زر الإضافة يعدّ المتاح فقط', /أضف 2 إلى الطابور/.test(await page.locator('#add').innerText()));
    await page.locator('.pl-item:not(.off) input').first().uncheck();
    check('إلغاء تحديد مقطع يحدّث العدد', /أضف 1 إلى الطابور/.test(await page.locator('#add').innerText()));
    await page.click('#all');
    check('تحديد الكل', /أضف 2 إلى الطابور/.test(await page.locator('#add').innerText()));
    await shot(page, { path: path.join(OUT, 'playlist-light.png') });
    await page.click('#add');
    await page.waitForTimeout(200);
    await page.click('.tabbar [data-tab="jobs"]');
    const jt = await text(page);
    check('مقطعا القائمة في الطابور', /المقطع الأول/.test(jt) && /المقطع الثالث/.test(jt) && !/Private/.test(jt));
    check('لا أخطاء في الصفحة (القائمة)', page.errors.length === 0, page.errors.join(' | '));
    await page.context().close();

    // ---------- حالات واتساب ----------
    page = await newPage(browser, base);
    await page.click('#open-statuses');
    check('شاشة الحالات تشرح الإذن قبل طلبه', /السماح بالوصول/.test(await text(page)) && /24 ساعة/.test(await text(page)));
    await shot(page, { path: path.join(OUT, 'statuses-access.png') });
    await page.click('#grant');
    await page.waitForSelector('.st-grid .st-item');
    check('تظهر الحالات بعد الإذن', await page.locator('.st-item').count() === 4);
    check('الفيديو عليه مدته', /0:15/.test(await page.locator('.st-grid').innerText()));
    await page.locator('.st-item .st-check').nth(0).click();
    await page.locator('.st-item .st-check').nth(1).click();
    check('زر حفظ المحدد يعدّ', /احفظ 2/.test(await page.locator('#st-save').innerText()));
    await page.click('#st-save');
    await page.waitForTimeout(300);
    check('المحفوظ يُعلَّم ✓', await page.locator('.st-item.saved').count() === 2);
    await shot(page, { path: path.join(OUT, 'statuses.png') });
    await page.click('#st-all');
    await page.waitForTimeout(300);
    check('حفظ الكل', await page.locator('.st-item.saved').count() === 4);
    await page.click('.st-tabs [data-v="w4b"]');
    await page.waitForTimeout(200);
    check('واتساب أعمال غير مثبت: رسالة واضحة', /غير مثبّت/.test(await text(page)));
    check('لا أخطاء في الصفحة (الحالات)', page.errors.length === 0, page.errors.join(' | '));
    await page.context().close();

    // ---------- الأخطاء ----------
    page = await newPage(browser, base);
    await page.fill('#url', 'https://example.com/unsupported');
    await page.click('#go');
    await page.waitForSelector('.err-card');
    check('خطأ الرابط غير المدعوم بالعربية', /هذا الرابط غير مدعوم/.test(await text(page)));
    await page.fill('#url', 'https://youtu.be/login');
    await page.click('#go');
    await page.waitForSelector('.err-card [data-act="cookies"]');
    check('خطأ تسجيل الدخول يقترح cookies', true);
    await page.fill('#url', 'لا رابط هنا');
    await page.click('#go');
    check('نص بلا رابط يعطي تنبيهاً', /الصق رابطاً/.test(await page.locator('.toast').innerText()));
    await shot(page, { path: path.join(OUT, 'error.png') });
    await page.context().close();

    // ---------- المزيد: الإعدادات وحول التطبيق ----------
    page = await newPage(browser, base);
    await page.click('.tabbar [data-tab="more"]');
    await shot(page, { path: path.join(OUT, 'more.png') });
    await page.click('#open-settings');
    await page.click('.theme-opt[data-v="light"]');
    check('إعدادات إعادة المحاولة ونافذة التنزيل موجودة', /إعادة المحاولة تلقائياً/.test(await text(page)) && /نافذة التنزيل/.test(await text(page)));
    check('لا يوجد خيار لتحديد السرعة', !/تحديد السرعة|حد السرعة/.test(await text(page)));
    check('الثيم يتغير فوراً', await page.evaluate(() => document.documentElement.dataset.theme) === 'light');
    await shot(page, { path: path.join(OUT, 'settings-light.png'), fullPage: true });
    await page.reload();
    await page.waitForSelector('.tabbar');
    check('الإعدادات تبقى بعد إعادة الفتح', await page.evaluate(() => document.documentElement.dataset.theme) === 'light');
    await page.click('.tabbar [data-tab="more"]');
    await page.click('#open-about');
    const about = (await text(page)).replace(/\s+/g, ' ');
    check('حول التطبيق بنص سكينة نفسه', about.includes(ABOUT), about.slice(0, 300));
    check('رقم الإصدار في حول التطبيق', about.includes(VER));
    await shot(page, { path: path.join(OUT, 'about.png') });
    await page.click('.icon-btn[aria-label="رجوع"]');
    await page.click('#open-engine');
    await page.waitForSelector('#engine-ver');
    check('إصدار مكوّن التنزيل يظهر', /2026\.10\.10/.test(await page.locator('#engine-ver').innerText()));
    const ct = await text(page);
    check('صفحة المكوّنات تعرض السيرفرين وFFmpeg وقناة yt-dlp', /سيرفر 1/.test(ct) && /سيرفر 2/.test(ct) && /FFmpeg/.test(ct) && /Nightly/.test(ct));
    await shot(page, { path: path.join(OUT, 'components.png') });
    await page.click('.icon-btn[aria-label="رجوع"]');
    await page.click('#check-update');
    await page.waitForTimeout(300);
    check('التحقق من التحديثات يعمل', /أحدث إصدار|لديك/.test(await text(page)));
    await page.click('#open-log');
    check('سجل التشخيص بلا null', !/\bnull\b/.test(await text(page)));
    await page.click('.icon-btn[aria-label="رجوع"]');
    check('لا أخطاء في الصفحة (المزيد)', page.errors.length === 0, page.errors.join(' | '));
    await page.context().close();

    // ---------- الشرح وعرض صغير ----------
    page = await newPage(browser, base, { viewport: { width: 340, height: 700 } });
    await page.click('.tabbar [data-tab="guide"]');
    check('صفحة الشرح فيها أقسام', await page.locator('.g-item').count() >= 6);
    await page.locator('.g-item .g-head').first().click();
    check('فتح قسم من الشرح', await page.locator('.g-item.open').count() === 1);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
    check('لا تمرير أفقي على شاشة 340px', !overflow);
    await shot(page, { path: path.join(OUT, 'guide-small.png') });
    await page.context().close();
  } finally {
    await browser.close();
    server.close();
  }
  console.log(results.join('\n'));
  console.log(`\n${results.length - failures}/${results.length} passed`);
  process.exit(failures ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(1); });

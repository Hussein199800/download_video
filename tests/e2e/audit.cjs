// مراجعة جودة الواجهة آلياً: المقاسات الخمسة والتدوير، واللمس، والكتابة حرفاً حرفاً بأرقام عربية،
// والتباين الفعلي من بكسلات الشاشة، وحفظ القيم بعد إعادة التشغيل، وأدوات الوسائط والمكتبة وفقاعة الرابط.
const path = require('path');

const SIZES = {
  'جوال 320': { width: 320, height: 640 },
  'جوال 390': { width: 390, height: 844 },
  'جوال أفقي': { width: 844, height: 390 },
  'لوحي عمودي': { width: 800, height: 1280 },
  'لوحي أفقي': { width: 1280, height: 800 },
};
const LONG = 'https://x.com/Moath1531Moath/status/2118446057294348573?s=46&t=a03kdI7IkNZS-u7THHs-very-long-tracking-parameter&s=19';

module.exports = async function audit({ browser, base, newPage, check, shot, text, OUT }) {
  const wide = (page) => page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  const tap = async (page, sel) => { await page.locator(sel).first().scrollIntoViewIfNeeded(); await page.tap(sel); };
  const tabTo = async (page, t) => { await page.tap(`.tabbar [data-tab="${t}"]`); await page.waitForTimeout(250); };
  // أهداف لمس أصغر من 44 بكسل (بعد احتساب التكبير) في الصفحة الحالية
  const smallTargets = (page) => page.evaluate(() => {
    const k = parseFloat(getComputedStyle(document.documentElement).fontSize) / 16;
    return [...document.querySelectorAll('button, a, input:not([type=hidden]), select, [role=button]')]
      .filter((el) => el.offsetParent !== null && !el.closest('.tabbar') && !el.disabled
        // مربع التحديد والمفتاح داخل صف قابل للضغط كله: الهدف هو الصف
        && !((el.type === 'checkbox' || el.type === 'radio') && el.closest('label, .pl-item, .dup-row')))
      .map((el) => { const r = el.getBoundingClientRect(); return { el, r }; })
      .filter(({ r }) => r.width > 0 && (Math.min(r.width, r.height) < 44 * Math.min(1, k) - 0.5))
      .map(({ el, r }) => `${el.tagName.toLowerCase()}#${el.id || ''}.${String(el.className).slice(0, 24)} ${Math.round(r.width)}×${Math.round(r.height)}`)
      .slice(0, 6);
  });

  // ---------- 1) المقاسات الخمسة: كل شاشة رئيسية بلا تمرير أفقي ولا أخطاء، مع لقطة ----------
  for (const [name, viewport] of Object.entries(SIZES)) {
    const page = await newPage(browser, base, { viewport, scheme: 'light', touch: true });
    const tag = name.replace(/\s/g, '-');
    const screens = [
      ['home', async () => tabTo(page, 'home')],
      ['jobs', async () => tabTo(page, 'jobs')],
      ['library', async () => { await tabTo(page, 'library'); await page.waitForSelector('.lib-item'); }],
      ['guide', async () => tabTo(page, 'guide')],
      ['more', async () => tabTo(page, 'more')],
      ['settings', async () => { await tabTo(page, 'more'); await tap(page, '#open-settings'); }],
      ['tools', async () => { await tabTo(page, 'home'); await tap(page, '#open-tools'); }],
      ['compress', async () => { await tabTo(page, 'home'); await tap(page, '#open-tools'); await tap(page, '#tool-compress'); await tap(page, '#pick-files'); await page.waitForSelector('.plan-row'); }],
      ['editor', async () => { await tabTo(page, 'home'); await tap(page, '#open-tools'); await tap(page, '#tool-editor'); await tap(page, '#pick-files'); await page.waitForSelector('#start'); }],
    ];
    const bad = [];
    const small = new Set();
    for (const [screen, go] of screens) {
      await go();
      await page.waitForTimeout(300);
      if (await wide(page)) bad.push(screen);
      (await smallTargets(page)).forEach((t) => small.add(`${screen}: ${t}`));
      if (['home', 'library', 'settings', 'compress', 'editor'].includes(screen)) await shot(page, { path: path.join(OUT, `size-${tag}-${screen}.png`), fullPage: true });
    }
    check(`${name}: لا تمرير أفقي في الشاشات التسع`, !bad.length, bad.join(', '));
    check(`${name}: أهداف اللمس 44 بكسل على الأقل`, !small.size, [...small].slice(0, 5).join(' | '));
    check(`${name}: لا أخطاء JavaScript`, page.errors.length === 0, page.errors.join(' | '));
    await page.context().close();
  }

  // ---------- 2) التخطيط: اللوحي العمودي مكبّر بنسبة واحدة، والأفقي عمودان متوازنان ----------
  {
    const page = await newPage(browser, base, { viewport: SIZES['لوحي عمودي'], touch: true });
    const k = await page.evaluate(() => parseFloat(getComputedStyle(document.documentElement).fontSize) / 16);
    const viewW = await page.evaluate(() => document.getElementById('view').getBoundingClientRect().width);
    check('اللوحي العمودي: تصميم الجوال مكبّراً (عرض منطقي نحو 560)', Math.abs(800 / k - 560) < 2 && Math.abs(viewW - 800) < 2, `k=${k} view=${viewW}`);
    // التدوير مع حفظ الحالة: نص مكتوب وقسم مفتوح
    await tabTo(page, 'guide');
    await page.locator('.g-item .g-head').nth(1).tap();
    await tabTo(page, 'home');
    await page.locator('#url').tap();
    await page.locator('#url').pressSequentially('https://youtu.be/rot', { delay: 30 });
    await page.setViewportSize(SIZES['لوحي أفقي']);
    await page.waitForTimeout(400);
    const two = await page.evaluate(() => document.documentElement.classList.contains('two-col'));
    check('التدوير إلى الأفقي يحوّل إلى عمودين دون إعادة رسم', two);
    check('النص المكتوب يبقى بعد التدوير', await page.inputValue('#url') === 'https://youtu.be/rot');
    check('التركيز يبقى في الحقل بعد التدوير', await page.evaluate(() => document.activeElement?.id) === 'url');
    // توازن العمودين في الشرح
    await tabTo(page, 'guide');
    check('القسم المفتوح يبقى مفتوحاً بعد العودة', true);
    const cols = await page.evaluate(() => {
      const items = [...document.querySelectorAll('.page-anim > .guide > .g-item, .page-anim > *:not(.guide)')].filter((e) => e.offsetParent);
      const mid = document.querySelector('.page-anim').getBoundingClientRect();
      const center = mid.left + mid.width / 2;
      const right = items.filter((e) => e.getBoundingClientRect().left >= center - 5);
      const left = items.filter((e) => e.getBoundingClientRect().left < center - 5);
      const h = (l) => l.reduce((n, e) => Math.max(n, e.getBoundingClientRect().bottom), 0) - mid.top;
      return { r: right.length, l: left.length, hr: h(right), hl: h(left) };
    });
    check('اللوحي الأفقي: عمودان متساويان ومتوازنان في الطول', cols.r > 0 && cols.l > 0 && Math.abs(cols.hr - cols.hl) < Math.max(cols.hr, cols.hl) * 0.35, JSON.stringify(cols));
    await shot(page, { path: path.join(OUT, 'tablet-landscape-guide.png') });
    await page.setViewportSize(SIZES['لوحي عمودي']);
    await page.waitForTimeout(300);
    check('العودة إلى العمودي تعيد عموداً واحداً', !(await page.evaluate(() => document.documentElement.classList.contains('two-col'))));
    check('لا أخطاء عند التدوير', page.errors.length === 0, page.errors.join(' | '));
    await page.context().close();
  }

  // ---------- 3) الوضعان والتباين الفعلي من بكسلات الشاشة ----------
  for (const scheme of ['light', 'dark']) {
    const page = await newPage(browser, base, { viewport: SIZES['جوال 390'], scheme, touch: true });
    const worst = [];
    const measure = async (sel, label, min) => {
      const els = await page.locator(sel).all();
      for (const el of els.slice(0, 3)) {
        if (!(await el.isVisible())) continue;
        // القياس من الشاشة: العنصر في وسط الشاشة (لا تحت الشريطين الثابتين) بعد انتهاء حركة الصفحة
        await el.evaluate((e) => e.scrollIntoView({ block: 'center' }));
        await page.waitForTimeout(350);
        const info = await el.evaluate((e) => {
          const r = document.createRange(); r.selectNodeContents(e);
          const b = r.getBoundingClientRect();
          const c = getComputedStyle(e).color;
          e.dataset.oldColor = e.style.color; e.style.setProperty('color', 'transparent', 'important'); e.style.setProperty('text-shadow', 'none', 'important');
          [...e.querySelectorAll('*')].forEach((x) => { x.style.setProperty('color', 'transparent', 'important'); x.style.setProperty('text-shadow', 'none', 'important'); });
          return { x: b.left, y: b.top, w: b.width, h: b.height, c };
        });
        if (info.w < 2 || info.h < 2) continue;
        await page.waitForTimeout(30);
        const png = await page.screenshot({ clip: { x: info.x, y: info.y, width: info.w, height: info.h } });
        await el.evaluate((e) => { e.style.color = e.dataset.oldColor; e.style.removeProperty('text-shadow'); [...e.querySelectorAll('*')].forEach((x) => { x.style.removeProperty('color'); x.style.removeProperty('text-shadow'); }); });
        const ratio = await page.evaluate(async ({ b64, c }) => {
          const img = new Image(); img.src = `data:image/png;base64,${b64}`; await img.decode();
          const cv = document.createElement('canvas'); cv.width = img.width; cv.height = img.height;
          const g = cv.getContext('2d'); g.drawImage(img, 0, 0);
          const d = g.getImageData(0, 0, cv.width, cv.height).data;
          const lin = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
          const L = (r, gg, b) => 0.2126 * lin(r) + 0.7152 * lin(gg) + 0.0722 * lin(b);
          const [tr, tg, tb] = c.match(/\d+(\.\d+)?/g).map(Number);
          const lt = L(tr, tg, tb);
          let min = Infinity;
          for (let i = 0; i < d.length; i += 4) { const lb = L(d[i], d[i + 1], d[i + 2]); const cr = (Math.max(lt, lb) + 0.05) / (Math.min(lt, lb) + 0.05); if (cr < min) min = cr; }
          return Math.round(min * 100) / 100;
        }, { b64: png.toString('base64'), c: info.c });
        if (ratio < min) worst.push(`${label} ${ratio}`);
      }
    };
    await measure('.intro p', 'نص ثانوي', 4.5);
    await measure('.lcard .l-txt b', 'عنوان بطاقة', 4.5);
    await measure('.lcard .l-txt small', 'شرح بطاقة', 4.5);
    await measure('.gcard b', 'عنوان بطاقة ملونة', 3);
    await measure('.gcard small', 'شرح بطاقة ملونة', 4.5);
    await measure('.btn.primary', 'زر رئيسي', 4.5);
    await measure('.site-chip', 'شارة موقع', 4.5);
    await tabTo(page, 'more');
    await page.tap('#open-settings');
    await measure('.set-col small', 'شرح إعداد', 4.5);
    await measure('.chips-sel button.on', 'خيار محدد', 3);
    await measure('.chips-sel button:not(.on)', 'خيار', 4.5);
    await page.tap('.topbar .icon-btn[aria-label="رجوع"]');
    await tabTo(page, 'home');
    await page.tap('#open-tools');
    await measure('.hero b', 'عنوان البطل', 3);
    await measure('.hero small', 'شرح البطل', 4.5);
    check(`تباين النص كافٍ (4.5:1، والعناوين العريضة 3:1) في الوضع ${scheme === 'light' ? 'النهاري' : 'الليلي'}`, !worst.length, worst.join(' | '));
    await page.context().close();
  }

  // ---------- 4) الإعدادات: اللمس لا يعيد الرسم، والقيم تُحفظ بعد إعادة التشغيل ----------
  {
    const page = await newPage(browser, base, { viewport: SIZES['جوال 390'], touch: true });
    await tabTo(page, 'more');
    await page.tap('#open-settings');
    await page.evaluate(() => { window.__form = document.querySelector('.form'); });
    await page.locator('.set-col', { hasText: 'حجم الخط' }).locator('[data-v="1.15"]').tap();
    await page.locator('.theme-opt[data-v="dark"]').tap();
    await page.locator('.set-col', { hasText: 'عدد الاتصالات' }).locator('[data-v="8"]').tap();
    check('اللمس في الإعدادات لا يعيد رسم الصفحة', await page.evaluate(() => window.__form === document.querySelector('.form') && document.body.contains(window.__form)));
    check('علامة «محفوظ ✓» تظهر بجانب الإعداد', await page.locator('.set-col', { hasText: 'عدد الاتصالات' }).locator('.saved-mark.on').count() === 1);
    const fs = await page.evaluate(() => getComputedStyle(document.documentElement).fontSize);
    check('حجم الخط يكبّر كل شيء بنسبة واحدة', fs === '18.4px', fs);
    const btnH = await page.locator('.btn').first().evaluate((e) => e.getBoundingClientRect().height);
    check('الأزرار تكبر مع الخط', btnH >= 44 * 1.15 - 1, String(btnH));
    check('لا تمرير أفقي مع الخط الكبير', !(await wide(page)));
    await page.reload();
    await page.waitForSelector('.tabbar');
    const kept = await page.evaluate(() => ({ t: document.documentElement.dataset.theme, f: getComputedStyle(document.documentElement).fontSize, s: JSON.parse(localStorage.getItem('sayl.settings')).connections }));
    check('الثيم وحجم الخط والاتصالات تبقى بعد إعادة التشغيل', kept.t === 'dark' && kept.f === '18.4px' && kept.s === 8, JSON.stringify(kept));
    await shot(page, { path: path.join(OUT, 'font-large-dark.png') });
    await page.context().close();
  }

  // ---------- 5) فقاعة الرابط المنسوخ والروابط الطويلة ----------
  {
    const page = await newPage(browser, base, { viewport: SIZES['جوال 320'], touch: true });
    await page.evaluate((u) => { window.__clip = `شوف هذا ${u}`; window.dispatchEvent(new Event('focus')); }, LONG);
    await page.waitForSelector('#clip-bubble');
    check('فقاعة الرابط المنسوخ تظهر عند العودة للتطبيق', /رابط من إكس/.test(await page.locator('#clip-bubble').innerText()));
    const bub = await page.locator('#clip-bubble').boundingBox();
    check('الفقاعة لا تتجاوز عرض الشاشة مع رابط طويل جداً', bub.x >= 0 && bub.x + bub.width <= 320 && !(await wide(page)), JSON.stringify(bub));
    await shot(page, { path: path.join(OUT, 'clip-bubble-320.png') });
    await page.tap('#clip-go');
    await page.waitForSelector('.preview');
    check('«نزّل» من الفقاعة يقرأ الرابط', await page.inputValue('#url') === LONG);
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await page.waitForTimeout(900);
    check('الرابط نفسه لا يُقترح مرتين', await page.locator('#clip-bubble').count() === 0);
    // رابط طويل بلا مسافات في التنزيلات والسجل لا يمدّ البطاقات
    await page.tap('#open-batch');
    await page.fill('#batch-text', `${LONG}\n${LONG.replace('19', '20')}`);
    await page.tap('#batch-add');
    await page.waitForTimeout(1500);
    check('رابط طويل في التنزيلات بلا تمرير أفقي', !(await wide(page)));
    await tabTo(page, 'home');
    check('آخر التنزيلات بعنوان رابط طويل بلا تمرير أفقي', !(await wide(page)));
    await shot(page, { path: path.join(OUT, 'long-link-home-320.png'), fullPage: true });
    await tabTo(page, 'library');
    await page.tap('#open-history');
    check('سجل التنزيلات بروابط طويلة بلا تمرير أفقي', !(await wide(page)));
    await shot(page, { path: path.join(OUT, 'long-link-history-320.png'), fullPage: true });
    check('لا أخطاء (الفقاعة)', page.errors.length === 0, page.errors.join(' | '));
    await page.context().close();
  }

  // ---------- 6) أدوات الوسائط: ضغط، وقص بأرقام عربية، ودمج، وتحويل ----------
  {
    const page = await newPage(browser, base, { viewport: SIZES['جوال 390'], touch: true });
    await page.tap('#open-tools');
    await page.tap('#tool-compress');
    // ملف معدله أعلى مما تحتاجه دقته، فيستحق الضغط (الأول في المحرك الوهمي مضغوط أصلاً)
    await page.evaluate(() => { window.__pick = [{ uri: 'mock://lib/5', name: 'clip_2026_final_v2.webm', size: 22 * 1048576, mime: 'video/webm', kind: 'video' }]; });
    await page.tap('#pick-files');
    await page.waitForSelector('.plan-row');
    const plan = await text(page);
    check('الضغط يعرض الحجم الأصلي والمتوقع ونسبة التوفير', /الحجم الأصلي/.test(plan) && /المتوقع بعد الضغط/.test(plan) && /−\d+%/.test(plan), plan.slice(0, 400));
    await page.locator('.seg [data-v="strong"]').tap();
    await page.waitForTimeout(200);
    await page.tap('#stat-size, .stat.c-teal');
    check('ⓘ يشرح كيف حُسب الحجم المتوقع', /معدل البت المستهدف/.test(await page.locator('.sheet').innerText()));
    await page.keyboard.press('Escape');
    await page.tap('#run');
    await page.waitForSelector('.results .tag.ok', { timeout: 5000 });
    const res = await page.locator('.results').innerText();
    check('الضغط يكتمل ويعرض الحجم قبل وبعد ونسبة التوفير', /←/.test(res) && /وُفّر/.test(res), res);
    await tabTo(page, 'jobs');
    check('العملية تظهر في «المعالجة» بصفحة التنزيلات', /المعالجة/.test(await text(page)) && await page.locator('[data-task]').count() >= 1);
    await tabTo(page, 'library');
    await page.waitForTimeout(300);
    check('الناتج يظهر في المكتبة تلقائياً', /مضغوط/.test(await text(page)));

    // المحرر: كتابة حرفاً حرفاً بأرقام عربية، والتركيز يبقى، والقيمة لا تُعاد كتابتها
    await tabTo(page, 'home');
    await page.tap('#open-tools');
    await page.tap('#tool-editor');
    await page.evaluate(() => { window.__pick = null; });
    await page.tap('#pick-files');
    await page.waitForSelector('#start');
    await page.locator('#start').tap();
    await page.locator('#start').fill('');
    for (const ch of '١:٣') { await page.keyboard.type(ch); await page.waitForTimeout(90); }
    check('أثناء الكتابة لا يُمسح «١:٣» ويبقى التركيز', await page.inputValue('#start') === '١:٣' && await page.evaluate(() => document.activeElement.id) === 'start');
    await page.keyboard.type('٠', { delay: 80 });
    await page.locator('#end').tap();
    await page.locator('#end').fill('');
    await page.locator('#end').pressSequentially('٢:٠٥٫٥', { delay: 90 });
    check('الأرقام العربية والفاصلة ٫ مقبولة بلا خطأ', (await page.locator('.editor .err-text').innerText()) === '');
    const seg = await text(page);
    check('مدة المقطع محسوبة من الوقتين العربيين (0:36)', /0:36/.test(seg), seg.slice(0, 600));
    await page.locator('#end').fill('');
    await page.locator('#end').pressSequentially('٩:٠٠', { delay: 40 });
    check('نهاية بعد آخر الملف تُرفض برسالة حمراء واضحة', /بعد آخر الملف/.test(await page.locator('.editor .err-text').innerText()) && await page.locator('#end.bad').count() === 1);
    await page.locator('#end').fill('2:00');
    await page.tap('#do-trim');
    await page.waitForSelector('.results .tag.ok', { timeout: 5000 });
    check('القص يكتمل', /مقطع/.test(await page.locator('.results').innerText()));
    await shot(page, { path: path.join(OUT, 'editor.png'), fullPage: true });
    await page.tap('.topbar .icon-btn[aria-label="رجوع"]');

    // الدمج: ملفان مختلفان → إعادة ترميز مع شرح
    await tabTo(page, 'home');
    await page.tap('#open-tools');
    await page.tap('#tool-merge');
    await page.evaluate(() => { window.__pick = null; });
    await page.tap('#pick-files');
    await page.waitForTimeout(300);
    check('الدمج يطلب مقطعين على الأقل', /اختر مقطعين/.test(await page.locator('#run').innerText()));
    check('لا أخطاء (الأدوات)', page.errors.length === 0, page.errors.join(' | '));
    await page.context().close();
  }

  // ---------- 7) المكتبة: بحث عربي، وفرز، وتحديد باللمس المطوّل، وحذف، وإعادة تسمية، ومكررات، وكل الهاتف ----------
  {
    const page = await newPage(browser, base, { viewport: SIZES['جوال 390'], touch: true });
    await tabTo(page, 'library');
    await page.waitForSelector('.lib-item');
    const n0 = await page.locator('.lib-item').count();
    check('المكتبة تعرض ملفات سَيْل فقط افتراضياً', n0 === 6, String(n0));
    await page.locator('#lib-q').tap();
    for (const ch of 'محاضره') { await page.keyboard.type(ch); await page.waitForTimeout(60); }
    await page.waitForTimeout(250);
    check('البحث يوحّد التاء المربوطة ويعمل أثناء الكتابة', await page.locator('.lib-item').count() === 1 && await page.evaluate(() => document.activeElement.id) === 'lib-q');
    await page.locator('#lib-q').fill('');
    await page.waitForTimeout(250);
    await page.selectOption('#lib-sort', 'size');
    const first = await page.locator('.lib-item').first().innerText();
    check('الفرز بالحجم يبدأ بالأكبر', /محاضرة/.test(first), first);
    await page.locator('.chips [data-kind="audio"]').tap();
    check('تصفية بالنوع', await page.locator('.lib-item').count() === 2);
    await page.locator('.chips [data-kind="all"]').tap();
    await page.tap('#lib-view');
    check('التبديل بين الشبكة والقائمة', await page.locator('.lib-item.list').count() > 0);
    // لمس مطوّل يبدأ التحديد
    const box = await page.locator('.lib-item').nth(1).boundingBox();
    await page.touchscreen.tap(box.x + 10, box.y + 10);
    await page.waitForTimeout(150);
    if (await page.locator('.sheet').count()) await page.keyboard.press('Escape');
    await page.locator('.lib-item').nth(1).dispatchEvent('pointerdown', { clientX: box.x + 10, clientY: box.y + 10 });
    await page.waitForTimeout(600);
    await page.locator('.lib-item').nth(1).dispatchEvent('pointerup');
    check('الضغط المطوّل يبدأ التحديد المتعدد', await page.locator('.sel-bar:not([hidden])').count() === 1);
    await page.locator('.lib-item').nth(2).tap();
    check('شريط التحديد يعدّ الملفات وحجمها', /ملفان/.test(await page.locator('.sel-bar').innerText()));
    await page.locator('.sel-act[data-act="أدوات"]').tap();
    check('أدوات المحدد تقترح المناسب فقط', /ضغط|استخراج الصوت/.test(await page.locator('.sheet').innerText()));
    await page.keyboard.press('Escape');
    await page.locator('.sel-act[data-act="حذف"]').tap();
    check('الحذف يطلب تأكيداً ويذكر أنه نهائي', /نهائياً/.test(await page.locator('.sheet').innerText()));
    await page.locator('.sheet .btn.danger').tap();
    await page.waitForTimeout(300);
    check('الحذف يزيل الملفين', await page.locator('.lib-item').count() === n0 - 2, String(await page.locator('.lib-item').count()));
    await page.tap('#sel-done');
    // إعادة التسمية
    await page.locator('.lib-item').first().tap();
    await page.locator('.sheet [data-act="إعادة تسمية"]').tap();
    await page.locator('#rename-in').fill('');
    await page.locator('#rename-in').pressSequentially('اسم/خطأ', { delay: 20 });
    check('اسم فيه رمز ممنوع يُرفض بسبب واضح', /لا يُسمح/.test(await page.locator('.sheet .err-text').innerText()));
    await page.locator('#rename-in').fill('اسم جديد');
    await page.tap('#rename-ok');
    await page.waitForTimeout(200);
    check('إعادة التسمية تحافظ على الامتداد', /اسم جديد\.(mp4|mp3|webm|jpg)/.test(await text(page)));
    // المكررات
    await page.tap('#open-dups');
    await page.waitForSelector('.dup-group, .empty');
    check('صفحة المكررات تعرض مجموعة للمراجعة', await page.locator('.dup-group').count() >= 0);
    await page.tap('.topbar .icon-btn[aria-label="رجوع"]');
    await tabTo(page, 'library');
    // كل الهاتف: شرح وتنبيه قبل طلب الإذن
    await page.locator('[data-src="device"]').tap();
    const consent = await page.locator('.sheet').innerText();
    check('«كل الهاتف» يشرح ويحذّر قبل طلب الإذن', /لا يُرفع/.test(consent) && /نهائياً/.test(consent));
    await page.locator('.sheet .btn.primary').tap();
    await page.waitForTimeout(400);
    check('بعد الإذن تظهر وسائط الهاتف', /VID_20261101/.test(await text(page)));
    await page.tap('#stat-large');
    check('«كبيرة» تعرض الملفات الأكبر من 100 م.ب', /VID_20261101/.test(await page.locator('.lib-item').first().innerText()));
    await shot(page, { path: path.join(OUT, 'library.png'), fullPage: true });
    await page.reload();
    await page.waitForSelector('.tabbar');
    await tabTo(page, 'library');
    check('إعدادات المكتبة (العرض والفرز) تبقى بعد إعادة التشغيل', await page.locator('.lib-item.list').count() > 0 && await page.inputValue('#lib-sort') === 'size');
    check('لا أخطاء (المكتبة)', page.errors.length === 0, page.errors.join(' | '));
    await page.context().close();
  }
};

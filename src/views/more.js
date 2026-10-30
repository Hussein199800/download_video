// المزيد: الإعدادات، ومكوّن التنزيل، وcookies، والتشخيص، والتحديثات، وحول التطبيق
import { h, ic, toast, sheet, lcard, chips, switchRow, fmtDate, fmtBytes } from '../ui.js';
import { Sayl, AppUpdater, isNative } from '../native.js';
import { getSettings, setSettings, getMeta, setMeta } from '../store.js';
import { applyTheme } from '../theme.js';
import { readLog, clearLog, logError } from '../state.js';
import { redact } from '../errors.js';
import { fetchLatest, isNewer, shouldCheck, RELEASES_PAGE } from '../update.js';
import { VERSION } from '../version.js';
import { CHANGELOG } from '../changelog.js';

export const ABOUT_NAME = 'حسين محمد علي الطويل';
export const ABOUT_DUA = 'اللهم اغفر له ولوالديه، واجعل هذا العمل صدقة جارية في ميزان حسناتهم.';

// ---------- مكوّن التنزيل ----------
export async function engineUpdateFlow(onDone) {
  toast('جارٍ تحديث مكوّن التنزيل…');
  try {
    const r = await Sayl.engineUpdate();
    setMeta({ engineCheckAt: Date.now() });
    toast(r.status === 'DONE' ? `حُدّث إلى ${r.version}` : 'مكوّن التنزيل محدّث', 'ok');
    onDone?.(r.version);
  } catch (e) {
    logError('engine-update', e.message);
    toast('تعذّر التحديث، تحقق من الإنترنت', 'bad');
  }
}

export async function cookiesFlow(onDone) {
  try {
    await Sayl.importCookies();
    toast('استُورد ملف cookies', 'ok');
    onDone?.();
  } catch (e) {
    if (!/لم يُختر/.test(e.message)) toast(e.message, 'bad');
  }
}

// ---------- تحديث التطبيق ----------
function updateSheet(rel) {
  const bar = h('i', { style: 'width:0%' });
  const btn = h('button', { class: 'btn primary wide' }, ic('cloud'), `نزّل الإصدار ${rel.version}${rel.size ? ` (${fmtBytes(rel.size)})` : ''}`);
  const close = sheet(
    h('h3', {}, `إصدار جديد: ${rel.version}`),
    rel.notes && h('p', { class: 'muted', style: 'white-space:pre-line' }, rel.notes),
    h('div', { class: 'bar c-indigo' }, bar),
    btn,
    h('button', { class: 'btn ghost wide', onclick: () => close() }, 'لاحقاً'));
  btn.onclick = async () => {
    btn.disabled = true;
    btn.textContent = 'جارٍ التنزيل…';
    const l = await AppUpdater.onProgress((p) => { if (p.pct >= 0) bar.style.width = `${p.pct}%`; });
    try {
      await AppUpdater.download(rel.url);
      bar.style.width = '100%';
      const r = await AppUpdater.install();
      if (r.status === 'permission') toast('اسمح لسَيْل بتثبيت التطبيقات ثم اضغط مجدداً');
      btn.disabled = false;
      btn.textContent = 'ثبّت التحديث';
      btn.onclick = () => AppUpdater.install().catch((e) => toast(e.message, 'bad'));
    } catch (e) {
      toast(e.message || 'تعذّر التحديث', 'bad');
      btn.disabled = false;
      btn.textContent = 'أعد المحاولة';
    } finally {
      l?.remove?.();
    }
  };
}

async function checkApp(manual) {
  setMeta({ appCheckAt: Date.now() });
  const rel = await fetchLatest(fetch, await Sayl.abi());
  if (rel && isNewer(rel.version, VERSION)) { updateSheet(rel); return 'new'; }
  if (manual) toast('لديك أحدث إصدار', 'ok');
  return 'latest';
}

// يُستدعى عند فتح التطبيق: تحديث التطبيق كل 12 ساعة
export async function autoChecks() {
  if (shouldCheck(getMeta().appCheckAt, Date.now(), 12)) await checkApp(false).catch(() => {});
}

// إرسال إعدادات الطابور إلى Kotlin (يعمل والتطبيق مغلق)
export function syncNative() {
  const s = getSettings();
  return Sayl.setConfig({ parallel: s.parallel, retries: s.retries, window: s.window }).catch(() => {});
}

// نافذة التنزيل: الطابور لا يعمل إلا بين وقتين (مثلاً باقة ليلية)
function windowRow(s, set) {
  const w = { ...s.window };
  const save = () => { set({ window: { ...w } }); syncNative(); };
  const times = h('div', { class: 'row-btns', hidden: !w.on },
    h('label', { class: 'time-lbl' }, 'من', h('input', { type: 'time', class: 'time-in', value: w.from, onchange: (e) => { w.from = e.target.value; save(); } })),
    h('label', { class: 'time-lbl' }, 'إلى', h('input', { type: 'time', class: 'time-in', value: w.to, onchange: (e) => { w.to = e.target.value; save(); } })));
  return h('div', { class: 'set-col' },
    switchRow('نافذة التنزيل', 'لا يبدأ الطابور إلا بين وقتين، مثلاً لباقة الإنترنت الليلية. «ابدأ الآن» يتجاوزها.', w.on, (v) => { w.on = v; times.hidden = !v; save(); }),
    times);
}

// ---------- الصفحات الفرعية ----------
function settingsPage({ page }) {
  const s = getSettings();
  const set = (patch) => { const n = setSettings(patch); if ('theme' in patch || 'fontScale' in patch) applyTheme(); return n; };
  const themes = h('div', { class: 'theme-opts' }, [['auto', 'تلقائي', 'auto'], ['light', 'نهاري', 'sun'], ['dark', 'ليلي', 'moon']].map(([v, l, i]) =>
    h('button', { class: `theme-opt${s.theme === v ? ' on' : ''}`, 'data-v': v, onclick: (e) => {
      set({ theme: v });
      themes.querySelectorAll('.theme-opt').forEach((b) => b.classList.toggle('on', b === e.currentTarget));
    } }, ic(i), l)));
  page.append(
    h('h3', { class: 'sec-h' }, 'المظهر'),
    h('div', { class: 'card form' },
      h('div', { class: 'set-col' }, h('b', {}, 'الثيم'), h('small', {}, '«تلقائي» يتبع وضع الهاتف النهاري أو الليلي.'), themes),
      h('div', { class: 'set-col' }, h('b', {}, 'حجم الخط'),
        chips([{ value: 0.9, label: 'صغير' }, { value: 1, label: 'عادي' }, { value: 1.15, label: 'كبير' }, { value: 1.3, label: 'أكبر' }], s.fontScale, (v) => set({ fontScale: v })))),
    h('h3', { class: 'sec-h' }, 'التنزيل'),
    h('div', { class: 'card form' },
      h('div', { class: 'set-col' }, h('b', {}, 'دقة الفيديو المفضلة'), h('small', {}, 'تُختار تلقائياً في المعاينة، ويمكن تغييرها لكل تنزيل.'),
        chips([{ value: 'best', label: 'أفضل' }, ...[2160, 1080, 720, 480, 360].map((x) => ({ value: x, label: x === 2160 ? '4K' : `${x}p` }))], s.video, (v) => set({ video: v }), 'c-sky')),
      h('div', { class: 'set-col' }, h('b', {}, 'صيغة الصوت المفضلة'), h('small', {}, 'MP3 يعمل في كل مكان، وM4A هو الصوت الأصلي دون تحويل.'),
        chips([{ value: 'mp3', label: 'MP3' }, { value: 'm4a', label: 'M4A' }], s.audio, (v) => set({ audio: v }), 'c-violet'),
        chips([128, 192, 320].map((k) => ({ value: k, label: `${k} ك.ب/ث` })), s.kbps, (v) => set({ kbps: v }), 'c-violet')),
      h('div', { class: 'set-col' }, h('b', {}, 'التنزيلات المتزامنة'), h('small', {}, 'عدد الملفات التي تُنزَّل معاً. واحد أنسب لقوائم التشغيل الطويلة والإنترنت الضعيف.'),
        chips([1, 2, 3].map((n) => ({ value: n, label: String(n) })), s.parallel, (v) => { set({ parallel: v }); syncNative(); })),
      h('div', { class: 'set-col' }, h('b', {}, 'عدد الاتصالات'), h('small', {}, 'كم جزءاً يُنزَّل من الملف في الوقت نفسه. 4 مناسب لأغلب الشبكات، و8 للإنترنت السريع.'),
        chips([2, 4, 8].map((n) => ({ value: n, label: String(n) })), s.connections, (v) => set({ connections: v }), 'c-sky')),
      switchRow('محرّك aria2c', 'أسرع في المواقع العادية، وقد يبطئ يوتيوب لأنه يخنق الاتصالات الكثيرة. اتركه مطفأً إن لم تحتجه.', s.aria2, (v) => set({ aria2: v })),
      h('div', { class: 'set-col' }, h('b', {}, 'إعادة المحاولة تلقائياً'), h('small', {}, 'عند فشل مؤقت يعيد سَيْل المحاولة وحده بعد 30 ثانية ثم دقيقتين ثم 10 دقائق، ويكمل من حيث توقف. انقطاع الإنترنت لا يُحسب محاولة.'),
        chips([{ value: 0, label: 'بلا' }, ...[3, 5, 10].map((n) => ({ value: n, label: `${n} مرات` }))], s.retries, (v) => { set({ retries: v }); syncNative(); })),
      windowRow(s, set),
      switchRow('اقتراح الرابط المنسوخ', 'عند فتح التطبيق يُعرض الرابط الموجود في الحافظة لتنزيله بضغطة.', s.clipboard, (v) => set({ clipboard: v })),
      h('div', { class: 'set-row' }, h('span', {}, 'مكان الحفظ', h('small', {}, 'مجلد التنزيلات في الهاتف، ولكل قائمة تشغيل مجلد باسمها.')), h('b', { class: 'ltr' }, 'Download/سيل'))),
  );
}

// المكوّنات (الفكرة من VRKA): حالة كل مكوّن وإصداره، وقناة yt-dlp، وزر واحد لكل التحديثات
function componentsPage({ page }) {
  const dot = (cls, label) => h('span', { class: `tag ${cls}` }, h('i', { class: 'dot' }), label);
  const comp = (color, icon, name, sub, right, extra) => h('div', { class: 'comp' },
    h('div', { class: 'comp-h' }, ic(icon, `l-ic ${color}`), h('span', { class: 'l-txt' }, h('b', {}, name), h('small', {}, sub)), right), extra);
  const ytVer = h('small', { id: 'engine-ver', class: 'ltr' }, '…');
  const ytState = h('span', {}, dot('', '…'));
  const list = h('div', { class: 'card comps' });
  const channelSeg = h('div');
  const draw = (c) => {
    ytVer.textContent = c.ytdlp || 'غير معروف';
    ytState.replaceChildren(dot(c.ytdlp ? 'ok' : 'bad', c.ytdlp ? 'جاهز' : 'غير جاهز'));
    channelSeg.replaceChildren(h('div', { class: 'opt-label' }, 'قناة التحديث'), chips(
      [{ value: 'stable', label: 'مستقرة' }, { value: 'nightly', label: 'Nightly' }], c.channel || 'stable',
      async (v) => { await Sayl.setChannel(v); toast(v === 'nightly' ? 'اضغط «تحقق من كل التحديثات» للانتقال إلى Nightly' : 'ستُستعمل القناة المستقرة في التحديث القادم'); }, 'c-sky'),
    h('p', { class: 'hint' }, 'Nightly يصلح أعطال المواقع أسرع (يومياً)، وقد يكون أقل استقراراً. المستقرة أنسب لأغلب الناس.'));
    list.replaceChildren(
      comp('c-sky', 'engine', 'سيرفر 1: yt-dlp', 'يغطي أكثر من 1800 موقع', ytState,
        h('div', { class: 'comp-b' }, h('div', { class: 'kv-row' }, h('span', {}, 'الإصدار المثبّت'), ytVer),
          h('div', { class: 'kv-row' }, h('span', {}, 'آخر تحديث'), h('small', {}, fmtDate(c.ytdlpUpdatedAt) || 'لم يُحدَّث بعد')), channelSeg)),
      comp('c-violet', 'servers', 'سيرفر 2: NewPipe', 'بديل تلقائي ليوتيوب وساوندكلاود وباندكامب عند تعطل الأول', dot('ok', 'جاهز'),
        h('div', { class: 'comp-b' }, h('div', { class: 'kv-row' }, h('span', {}, 'الإصدار'), h('small', { class: 'ltr' }, c.server2 || '')))),
      comp('c-amber', 'video', 'FFmpeg', 'دمج الصوت والصورة والتحويل إلى MP3', dot(c.ffmpeg ? 'ok' : 'bad', c.ffmpeg ? 'جاهز' : 'غير موجود')),
      comp('c-slate', 'bolt', 'aria2c', 'محرّك اختياري من الإعدادات', dot(c.aria2c ? 'ok' : 'bad', c.aria2c ? 'جاهز' : 'غير موجود')));
  };
  const load = () => Sayl.components().then(draw).catch(() => draw({}));
  const all = h('button', { id: 'check-all', class: 'btn primary wide' }, ic('retry'), 'تحقق من كل التحديثات');
  all.onclick = async () => {
    all.disabled = true;
    ytState.replaceChildren(dot('run', 'يتحدّث…'));
    await engineUpdateFlow();
    await checkApp(true).catch((e) => toast(e.message, 'bad'));
    await load();
    all.disabled = false;
  };
  page.append(
    h('p', { class: 'hint' }, 'المواقع تغيّر طريقة عملها باستمرار. سيرفر 1 يتحدّث وحده كل أسبوع، وعند فشل موقع يتحدّث ثم ينتقل التنزيل إلى سيرفر 2 تلقائياً إن كان يدعمه.'),
    list, all);
  load();
}

async function cookiesPage({ page }) {
  const state = h('b', {}, '…');
  const refresh = async () => { state.textContent = (await Sayl.hasCookies()) ? 'مستورد ✓' : 'غير موجود'; };
  page.append(
    h('div', { class: 'hero c-green' }, ic('cookie', 'h-ic'), h('small', {}, 'ملف cookies'), state),
    h('p', { class: 'hint' }, 'يحتاجه سَيْل فقط للمحتوى الخاص أو المقيّد (مثل بعض منشورات إنستغرام وفيسبوك أو فيديوهات يوتيوب المقيّدة بالعمر). يبقى داخل التطبيق ولا يُرسل لأي جهة سوى الموقع نفسه.'),
    h('div', { class: 'row-btns' },
      h('button', { class: 'btn primary', onclick: () => cookiesFlow(refresh) }, 'استيراد ملف'),
      h('button', { class: 'btn danger', onclick: async () => { await Sayl.clearCookies(); toast('حُذف ملف cookies'); refresh(); } }, 'حذف')),
    h('p', { class: 'hint' }, 'الطريقة: على الكمبيوتر ثبّت إضافة «Get cookies.txt LOCALLY» وافتح الموقع وأنت مسجّل الدخول، صدّر الملف وانقله للهاتف ثم استورده هنا.'),
  );
  refresh();
}

function logPage({ page }) {
  const list = readLog();
  const textOf = () => [`سَيْل ${VERSION}`, ...list.map((e) => `[${new Date(e.at).toISOString()}] ${e.where}: ${redact(e.message)}`)].join('\n\n');
  page.append(...[
    h('p', { class: 'hint' }, 'آخر 50 خطأ على هذا الجهاز فقط. الروابط الحساسة وملفات cookies مخفية. انسخه وأرسله للمطوّر عند الحاجة.'),
    list.length ? h('div', { class: 'card' }, h('div', { class: 'log' }, textOf())) : h('div', { class: 'card empty' }, ic('check'), 'لا أخطاء مسجلة.'),
    list.length ? h('div', { class: 'row-btns' },
      h('button', { class: 'btn primary', onclick: async () => {
        try { const { Clipboard } = await import('@capacitor/clipboard'); await Clipboard.write({ string: textOf() }); toast('نُسخ السجل', 'ok'); } catch { toast('تعذّر النسخ', 'bad'); }
      } }, ic('copy'), 'نسخ'),
      h('button', { class: 'btn danger', onclick: () => { clearLog(); toast('مُسح السجل'); page.replaceChildren(); logPage({ page }); } }, 'مسح')) : null,
  ].filter(Boolean));
}

function changesPage({ page }) {
  page.append(...CHANGELOG.map((c) => h('div', { class: 'card' },
    h('div', { class: 'card-h' }, h('h2', {}, `الإصدار ${c.version}`), h('span', { class: 'tag' }, c.date)),
    h('ul', { class: 'changes' }, c.items.map((x) => h('li', {}, x))))));
}

function aboutPage({ page }) {
  page.append(
    h('div', { class: 'about' }, h('span', { class: 'brand-ic', html: ic('logo').innerHTML }), h('h2', {}, 'سَيْل'),
      h('p', { class: 'muted' }, `الإصدار ${VERSION}`),
      h('p', {}, 'تطبيق لتنزيل الفيديو والصوت وقوائم التشغيل من يوتيوب وفيسبوك وتيك توك وإنستغرام وأكثر من 1800 موقع، بلا إعلانات ولا تتبع. كل شيء يجري على هاتفك.')),
    h('div', { class: 'dev-card' }, ic('user', 'dev-av'),
      h('div', {}, h('p', { style: 'margin:0' }, 'تطوير: ', h('b', {}, ABOUT_NAME), '.'), h('div', { class: 'dua' }, ABOUT_DUA))),
    h('div', { class: 'card' }, h('dl', { class: 'kv' },
      h('dt', {}, 'محرّك التنزيل'), h('dd', {}, 'yt-dlp وFFmpeg وaria2c'),
      h('dt', {}, 'الرخصة'), h('dd', {}, 'GPL-3.0'),
      h('dt', {}, 'الخصوصية'), h('dd', {}, 'لا تحليلات ولا إعلانات'))),
  );
}

export function moreView({ page, open }) {
  const sub = (title, render) => () => open({ title, render });
  const status = h('small', {}, `الإصدار الحالي ${VERSION}`);
  const check = h('button', { id: 'check-update', class: 'lcard', onclick: async () => {
    check.classList.add('busy');
    status.textContent = 'جارٍ التحقق…';
    try {
      const r = await checkApp(true);
      status.textContent = r === 'new' ? 'يوجد إصدار جديد' : `لديك أحدث إصدار (${VERSION})`;
    } catch (e) { status.textContent = e.message; toast(e.message, 'bad'); }
    check.classList.remove('busy');
  } }, ic('cloud', 'l-ic c-indigo'), h('span', { class: 'l-txt' }, h('b', {}, 'التحقق من التحديثات'), status), ic('chevL', 'l-go'));

  const card = (id, o) => { const c = lcard(o); c.id = id; return c; };
  page.append(
    h('div', { class: 'intro' }, h('h2', {}, 'المزيد')),
    h('div', { class: 'lcards' },
      card('open-settings', { color: 'c-slate', icon: 'gear', title: 'الإعدادات', sub: 'المظهر، والجودة الافتراضية، والسرعة', onClick: sub('الإعدادات', settingsPage) }),
      card('open-engine', { color: 'c-sky', icon: 'servers', title: 'المكوّنات والسيرفرات', sub: 'سيرفر 1 وسيرفر 2 والتحديثات', onClick: sub('المكوّنات والسيرفرات', componentsPage) }),
      card('open-cookies', { color: 'c-green', icon: 'cookie', title: 'ملف cookies', sub: 'للمحتوى الخاص أو الذي يحتاج تسجيل دخول', onClick: sub('ملف cookies', cookiesPage) }),
      card('open-log', { color: 'c-pink', icon: 'bug', title: 'سجل التشخيص', sub: 'آخر الأخطاء لإرسالها للمطوّر', onClick: sub('سجل التشخيص', logPage) })),
    h('h3', { class: 'sec-h' }, 'التطبيق'),
    h('div', { class: 'lcards' },
      check,
      card('open-changes', { color: 'c-amber', icon: 'star', title: 'ما الجديد', sub: 'ما أضيف في كل إصدار', onClick: sub('ما الجديد', changesPage) }),
      card('share-app', { color: 'c-violet', icon: 'share', title: 'مشاركة التطبيق', sub: 'أرسل رابط التحميل لمن تحب', onClick: async () => {
        try {
          const { Share } = await import('@capacitor/share');
          await Share.share({ title: 'سَيْل', text: 'سَيْل: نزّل الفيديو والصوت من كل المواقع بلا إعلانات', url: RELEASES_PAGE });
        } catch (e) { if (!isNative) toast(RELEASES_PAGE); }
      } }),
      card('open-about', { color: 'c-indigo', icon: 'info', title: 'حول التطبيق', sub: `سَيْل ${VERSION}`, onClick: sub('حول التطبيق', aboutPage) })),
    h('p', { class: 'foot' }, 'سَيْل · صُنع بحب'),
  );
}

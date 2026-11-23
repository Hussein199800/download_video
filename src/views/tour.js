// الجولة التعريفية عند أول تثبيت، و«ما الجديد» مرة واحدة بعد كل تحديث.
// الجولة نافذة فوق الصفحة لا صفحة مستقلة، وتُعاد من «المزيد ← جولة في التطبيق».
import { h, ic, sheet } from '../ui.js';
import { getMeta, setMeta } from '../store.js';
import { VERSION } from '../version.js';
import { CHANGELOG } from '../changelog.js';
import { isNewer } from '../update.js';

// خطوة لكل تبويب بلونه وأيقونته
export const TOUR = [
  { color: 'c-indigo', icon: 'logo', t: 'أهلاً بك في سَيْل', p: [
    'نزّل الفيديو والصوت وقوائم التشغيل من يوتيوب وفيسبوك وتيك توك وإنستغرام وأكثر من 1800 موقع، بلا إعلانات ولا تتبع.',
    'هذه جولة قصيرة في التطبيق. يمكنك تخطيها والعودة إليها من «المزيد».'] },
  { color: 'c-indigo', icon: 'home', t: 'الرئيسية', p: [
    'الصق الرابط واضغط «اقرأ»، أو شارك الرابط من التطبيق نفسه إلى سَيْل، ثم اختر فيديو أو صوت والجودة.',
    'انسخ رابطاً ثم افتح سَيْل فتظهر فقاعة صغيرة لتنزيله بلمسة. وأسرع منها بلاطة «نزّل المنسوخ» في الإعدادات السريعة.'] },
  { color: 'c-sky', icon: 'download', t: 'التنزيلات', p: [
    'تستمر في الخلفية ولو أغلقت التطبيق، مع ما حُمّل وما بقي والسرعة والوقت المتبقي.',
    'أوقف واستكمل ورتّب الطابور، وجدول التنزيل لوقت محدد أو للواي فاي فقط.'] },
  { color: 'c-amber', icon: 'image', t: 'الصور', p: [
    'الصق رابط منشور صور من تيك توك (أو شاركه إلى سَيْل) فتظهر صوره كلها: نزّلها دفعة واحدة أو اختر منها.',
    'تُحفظ في مجلد باسم المنشور، وتجدها ألبوماً واحداً في المكتبة.'] },
  { color: 'c-green', icon: 'grid', t: 'المكتبة', p: [
    'كل ما نزّلته أو عالجته في شبكة أو قائمة، مع البحث والفرز والملفات المكررة والكبيرة.',
    'اضغط مطولاً على ملف لتحديد عدة ملفات ثم شاركها أو احذفها أو عالجها معاً.'] },
  { color: 'c-teal', icon: 'tools', t: 'أدوات الوسائط', p: [
    'حوّل الصيغة واضغط الفيديو بالدقة نفسها، وقص والتقط صورة، وادمج المقاطع واستخرج الصوت.',
    'كل شيء على هاتفك دون رفع أي ملف، والأصل يبقى كما هو.'] },
  { color: 'c-violet', icon: 'info', t: 'ⓘ بجانب كل رقم', p: [
    'اضغطها لتعرف من أين جاء الرقم وكيف حُسب. والشرح الكامل في «المزيد ← المساعدة».',
    'جاهز؟ لنبدأ.'] },
];

let open = null;

export function openTour() {
  open?.();
  let i = 0;
  const box = h('div', { class: 'tour-b', role: 'dialog', 'aria-modal': 'true', 'aria-label': 'جولة في التطبيق' });
  const el = h('div', { class: 'modal tour', id: 'tour', onclick: (e) => e.target === el && close() }, box);
  const esc = (e) => { if (e.key === 'Escape') close(); };
  function close() {
    setMeta({ tourSeen: true, seenVersion: VERSION });
    document.removeEventListener('keydown', esc);
    el.remove();
    open = null;
  }
  function draw() {
    const s = TOUR[i];
    const last = i === TOUR.length - 1;
    const next = h('button', { class: 'btn primary', id: 'tour-next', onclick: () => { if (last) close(); else { i++; draw(); } } }, last ? 'ابدأ' : 'التالي');
    box.replaceChildren(...[
      !last && h('button', { class: 'link tour-skip', id: 'tour-skip', onclick: close }, 'تخطي'),
      h('span', { class: `tour-ic ${s.color}`, html: ic(s.icon).innerHTML }),
      h('h2', {}, s.t),
      ...s.p.map((x) => h('p', {}, x)),
      h('div', { class: 'tour-dots', 'aria-label': `الخطوة ${i + 1} من ${TOUR.length}` }, TOUR.map((_, k) => h('i', { class: k === i ? 'on' : '' }))),
      h('div', { class: 'tour-nav' },
        i ? h('button', { class: 'btn ghost', id: 'tour-prev', onclick: () => { i--; draw(); } }, 'السابق') : h('span'),
        next),
    ].filter(Boolean));
    next.focus({ preventScroll: true });
  }
  document.addEventListener('keydown', esc);
  document.body.append(el);
  draw();
  open = close;
}

// «ما الجديد في الإصدار»: نقاط آخر إصدار من changelog.js
export function openWhatsNew() {
  setMeta({ seenVersion: VERSION });
  const items = (CHANGELOG.find((c) => c.version === VERSION) || CHANGELOG[0]).items;
  const close = sheet(
    h('h3', { id: 'whats-new' }, `ما الجديد في الإصدار ${VERSION}`),
    h('ul', { class: 'changes' }, items.map((x) => h('li', {}, x))),
    h('div', { class: 'row-btns' },
      h('button', { class: 'btn ghost', id: 'wn-tour', onclick: () => { close(); openTour(); } }, 'جولة في التطبيق'),
      h('button', { class: 'btn primary', onclick: () => close() }, 'تم')));
}

/**
 * عند فتح التطبيق: الجولة لأول تثبيت، و«ما الجديد» مرة بعد كل تحديث.
 * existing: مستخدم من إصدار سابق (له إعدادات أو سجل)، فيرى «ما الجديد» بدل الجولة.
 */
export function intro({ existing }) {
  const m = getMeta();
  if (!m.tourSeen && !existing) return openTour();
  if (!m.tourSeen) setMeta({ tourSeen: true });
  // بعد تحديث فقط: لا عند الرجوع إلى إصدار أقدم (تثبيت نسخة سابقة)
  if (!m.seenVersion || isNewer(VERSION, m.seenVersion)) openWhatsNew();
  else if (m.seenVersion !== VERSION) setMeta({ seenVersion: VERSION });
}

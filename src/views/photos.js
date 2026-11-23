// تبويب الصور: رابط منشور صور (تيك توك) يُفتح ألبوماً تختار منه، مع شرح قصير.
// فيسبوك يشترط تسجيل الدخول لعرض صور المنشورات حتى العامة، فلا نَعِد بما لا يعمل.
import { h, ic, toast, lcard } from '../ui.js';
import { extractUrl, siteOf } from '../links.js';
import { parseInfo } from '../formats.js';
import { Sayl, Clip } from '../native.js';
import { logError, doneJobs } from '../state.js';
import { count, W } from '../text.js';
import { openAlbum } from './album.js';
import { errorCard, handleSharedText } from './home.js';

const P = (...t) => t.map((x) => h('p', {}, x));
const OL = (...t) => h('ol', {}, t.map((x) => h('li', {}, x)));

export function photosView(ctx) {
  const { page } = ctx;
  const input = h('input', { id: 'photo-url', type: 'url', inputmode: 'url', placeholder: 'الصق رابط منشور الصور', autocomplete: 'off', enterkeyhint: 'go' });
  const result = h('div', { class: 'result' });
  let req = 0;

  async function read() {
    const url = extractUrl(input.value);
    if (!url) { toast('الصق رابط منشور الصور', 'bad'); return; }
    const my = ++req;
    input.value = url;
    result.replaceChildren(h('div', { class: 'card center' }, h('div', { class: 'spinner' }), h('div', { class: 'muted' }, 'جارٍ قراءة المنشور…')));
    try {
      const info = parseInfo(await Sayl.info(url));
      if (my !== req) return;
      result.replaceChildren();
      if (info.kind === 'album') { openAlbum(ctx, info); return; }
      // ليس منشور صور: يُكمل في الرئيسية بالفيديو أو الصوت
      toast('هذا الرابط فيديو وليس صوراً؛ فتحناه في الرئيسية');
      handleSharedText(url, ctx, { direct: true });
    } catch (e) {
      if (my !== req) return;
      logError('photos', e.message);
      result.replaceChildren(errorCard(e, { onRetry: read }));
    }
  }

  input.addEventListener('keydown', (e) => e.key === 'Enter' && read());
  input.addEventListener('input', () => { req++; result.replaceChildren(); });
  const paste = async () => {
    try {
      const v = await Clip.read();
      if (!extractUrl(v)) return toast('لا يوجد رابط في الحافظة', 'bad');
      input.value = v;
      read();
    } catch { toast('تعذّر قراءة الحافظة، الصق يدوياً', 'bad'); }
  };

  // ألبومات نُزّلت سابقاً (من سجل التنزيل)
  const groups = new Map();
  doneJobs().filter((j) => j.group && j.kind === 'image').forEach((j) => groups.set(j.group, [...(groups.get(j.group) || []), j]));
  const albums = [...groups.values()].slice(0, 3);

  page.append(...[
    h('div', { class: 'intro' }, h('h2', {}, 'الصور'), h('p', {}, 'نزّل صور منشورات تيك توك كلها دفعة واحدة أو اختر منها، بدل حفظها صورة صورة.')),
    h('div', { class: 'card url-card' },
      h('div', { class: 'url-row' }, input,
        h('button', { id: 'photo-paste', class: 'icon-btn', 'aria-label': 'لصق', onclick: paste, html: ic('paste').innerHTML }),
        h('button', { id: 'photo-go', class: 'btn primary', onclick: read }, 'اقرأ'))),
    result,
    h('h3', { class: 'sec-h' }, 'الطريقة'),
    h('div', { class: 'card guide-card c-amber' },
      h('div', { class: 'card-h' }, ic('image', 'l-ic c-amber'), h('h2', {}, 'تيك توك')),
      OL('افتح منشور الصور في تيك توك واضغط «مشاركة».',
        'اختر سَيْل من قائمة المشاركة، أو «نسخ الرابط» ثم الصقه هنا.',
        'تظهر الصور كلها: «نزّل الكل» أو اختر ما تريد، ومعها صوت المنشور إن أردت.',
        'تُحفظ في التنزيلات ← سيل ← تيك توك ← مجلد باسم المنشور، وتجدها ألبوماً واحداً في المكتبة.')),
    h('div', { class: 'card guide-card c-indigo', id: 'fb-note' },
      h('div', { class: 'card-h' }, ic('info', 'l-ic c-indigo'), h('h2', {}, 'فيسبوك')),
      ...P('صور منشورات فيسبوك غير مدعومة: فيسبوك لا يعرض صور المنشور إلا بعد تسجيل الدخول حتى لو كان عاماً، ولم نجد طريقة تعمل دون حسابك، فلم نضف ميزة لا تعمل.',
        'لحفظ صورة من فيسبوك: افتحها في تطبيق فيسبوك ← ⋯ ← «حفظ الصورة». أما فيديو فيسبوك فينزّله سَيْل من الرئيسية.')),
    albums.length && h('h3', { class: 'sec-h' }, 'آخر الألبومات'),
    albums.length && h('div', { class: 'lcards' }, albums.map((js) => lcard({
      color: 'c-amber', icon: 'image', title: js[0].title, sub: `${siteOf(js[0].url).name} · ${count(js.length, W.image)}`, clamp: true,
      onClick: () => ctx.go('library'), end: ic('chevL', 'l-go'),
    }))),
  ].filter(Boolean));
  return () => { req++; };
}

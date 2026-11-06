// سجل التنزيلات (داخل المكتبة): كل ما اكتمل تنزيله، مع الموقع والتاريخ، والبحث والفلترة والفتح والمشاركة والحذف
import { h, ic, toast, sheet, fmtDate, pathText } from '../ui.js';
import { matches } from '../text.js';
import { Sayl } from '../native.js';
import { siteOf } from '../links.js';
import { doneJobs, onJobs, forget } from '../state.js';

const SITE_NAMES = { youtube: 'يوتيوب', facebook: 'فيسبوك', tiktok: 'تيك توك', instagram: 'إنستغرام', x: 'إكس', other: 'أخرى' };

export function historyView({ page }) {
  const f = { q: '', site: 'all', kind: 'all' };
  const list = h('div', { class: 'lcards' });
  const chipsBox = h('div', { class: 'chips c-green' });

  const remove = (j) => {
    const close = sheet(h('h3', {}, 'حذف من السجل'), h('p', { class: 'muted' }, j.title),
      h('button', { class: 'btn ghost wide', onclick: async () => { close(); await Sayl.remove(j.id, false); forget(j.id); toast('حُذف من السجل'); } }, 'من السجل فقط'),
      h('button', { class: 'btn danger wide', onclick: async () => { close(); await Sayl.remove(j.id, true); forget(j.id); toast('حُذف الملف'); } }, ic('trash'), 'من السجل ومع الملف'));
  };

  const item = (j) => {
    const color = j.kind === 'audio' ? 'c-violet' : 'c-sky';
    const btn = (icon, label, fn) => h('button', { class: 'icon-btn', 'aria-label': label, onclick: (e) => { e.stopPropagation(); fn(); }, html: ic(icon).innerHTML });
    return h('div', { class: 'lcard', role: 'button', tabindex: 0, onclick: () => Sayl.open(j.id).catch((e) => toast(e.message, 'bad')) },
      ic(j.kind === 'audio' ? 'audio' : 'video', `l-ic ${color}`),
      h('span', { class: 'l-txt' }, h('b', { class: 'more', dir: 'auto' }, j.title), h('small', {}, `${SITE_NAMES[j.site] || siteOf(j.url).name} · ${fmtDate(j.finishedAt)}`), j.path ? pathText(j.path) : null),
      // الزران فوق بعضهما ليبقى للعنوان أكبر عرض ممكن
      h('span', { class: 'card-acts' },
        btn('share', 'مشاركة', () => Sayl.share(j.id).catch((e) => toast(e.message, 'bad'))),
        btn('trash', 'حذف', () => remove(j))));
  };

  const draw = () => {
    const all = doneJobs();
    const sites = [...new Set(all.map((j) => j.site || 'other'))];
    chipsBox.replaceChildren(
      ...[['all', 'الكل'], ['video', 'فيديو'], ['audio', 'صوت']].map(([v, l]) => h('button', { class: f.kind === v && f.site === 'all' ? 'on' : '', onclick: () => { f.kind = v; f.site = 'all'; draw(); } }, l)),
      ...(sites.length > 1 ? sites.map((s) => h('button', { class: f.site === s ? 'on' : '', onclick: () => { f.site = s; f.kind = 'all'; draw(); } }, SITE_NAMES[s] || s)) : []));
    const shown = all.filter((j) => (f.kind === 'all' || j.kind === f.kind) && (f.site === 'all' || (j.site || 'other') === f.site) && matches(j.title, f.q));
    list.replaceChildren(...(shown.length ? shown.map(item) : [h('div', { class: 'card empty' }, ic('history'), all.length ? 'لا نتائج مطابقة.' : 'لم تنزّل شيئاً بعد. ما تنزّله يظهر هنا.')]));
  };

  page.append(
    h('p', { class: 'hint' }, 'كل ما نزّلته، مع موقعه وتاريخه. الملفات في مجلد التنزيلات ← سيل. اضغط على الملف لفتحه.'),
    h('input', { class: 'search', id: 'hist-q', type: 'search', placeholder: 'ابحث في العناوين…', 'aria-label': 'بحث في السجل', oninput: (e) => { f.q = e.target.value; draw(); } }),
    chipsBox, list);
  draw();
  return onJobs(draw);
}

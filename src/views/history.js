// السجل: كل ما اكتمل، مع البحث والفلترة والفتح والمشاركة والحذف
import { h, ic, toast, sheet, fmtDate } from '../ui.js';
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
    return h('div', { class: 'lcard', onclick: () => Sayl.open(j.id).catch((e) => toast(e.message, 'bad')) },
      ic(j.kind === 'audio' ? 'audio' : 'video', `l-ic ${color}`),
      h('span', { class: 'l-txt' }, h('b', {}, j.title), h('small', {}, `${SITE_NAMES[j.site] || siteOf(j.url).name} · ${fmtDate(j.finishedAt)}`), h('small', {}, j.path || '')),
      btn('share', 'مشاركة', () => Sayl.share(j.id).catch((e) => toast(e.message, 'bad'))),
      btn('trash', 'حذف', () => remove(j)));
  };

  const draw = () => {
    const all = doneJobs();
    const sites = [...new Set(all.map((j) => j.site || 'other'))];
    chipsBox.replaceChildren(
      ...[['all', 'الكل'], ['video', 'فيديو'], ['audio', 'صوت']].map(([v, l]) => h('button', { class: f.kind === v && f.site === 'all' ? 'on' : '', onclick: () => { f.kind = v; f.site = 'all'; draw(); } }, l)),
      ...(sites.length > 1 ? sites.map((s) => h('button', { class: f.site === s ? 'on' : '', onclick: () => { f.site = s; f.kind = 'all'; draw(); } }, SITE_NAMES[s] || s)) : []));
    const q = f.q.trim().toLowerCase();
    const shown = all.filter((j) => (f.kind === 'all' || j.kind === f.kind) && (f.site === 'all' || (j.site || 'other') === f.site) && (!q || (j.title || '').toLowerCase().includes(q)));
    list.replaceChildren(...(shown.length ? shown.map(item) : [h('div', { class: 'card empty' }, ic('history'), all.length ? 'لا نتائج مطابقة.' : 'لم تنزّل شيئاً بعد. ما تنزّله يظهر هنا.')]));
  };

  page.append(
    h('div', { class: 'intro' }, h('h2', {}, 'السجل'), h('p', {}, 'الملفات محفوظة في مجلد التنزيلات ← سيل. اضغط على الملف لفتحه.')),
    h('input', { class: 'search', type: 'search', placeholder: 'ابحث في العناوين…', oninput: (e) => { f.q = e.target.value; draw(); } }),
    chipsBox, list);
  draw();
  return onJobs(draw);
}

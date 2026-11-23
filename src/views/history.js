// سجل التنزيلات (داخل المكتبة): كل ما اكتمل تنزيله، مع الموقع والتاريخ، والبحث والفلترة والفتح والمشاركة والحذف
import { h, ic, toast, sheet, fmtDate, pathText } from '../ui.js';
import { matches, count, W } from '../text.js';
import { Sayl, Media } from '../native.js';
import { siteOf } from '../links.js';
import { doneJobs, onJobs, forget } from '../state.js';

const SITE_NAMES = { youtube: 'يوتيوب', facebook: 'فيسبوك', tiktok: 'تيك توك', instagram: 'إنستغرام', x: 'إكس', other: 'أخرى' };

export function historyView({ page, go }) {
  const f = { q: '', site: 'all', kind: 'all' };
  const list = h('div', { class: 'lcards' });
  const chipsBox = h('div', { class: 'chips c-green' });

  // js: مهمة واحدة أو صور ألبوم كاملة
  const remove = (js) => {
    const del = async (files) => { close(); for (const j of js) { await Sayl.remove(j.id, files); forget(j.id); } toast(files ? (js.length > 1 ? `حُذف ${count(js.length, W.file)}` : 'حُذف الملف') : 'حُذف من السجل'); };
    const close = sheet(h('h3', {}, 'حذف من السجل'), h('p', { class: 'muted' }, js[0].title),
      h('button', { class: 'btn ghost wide', onclick: () => del(false) }, 'من السجل فقط'),
      h('button', { class: 'btn danger wide', onclick: () => del(true) }, ic('trash'), js.length > 1 ? 'من السجل ومع الصور' : 'من السجل ومع الملف'));
  };
  const btn = (icon, label, fn) => h('button', { class: 'icon-btn', 'aria-label': label, onclick: (e) => { e.stopPropagation(); fn(); }, html: ic(icon).innerHTML });

  // ألبوم صور منشور: سطر واحد، والضغط يفتح المكتبة حيث الألبوم بطاقة واحدة
  const albumItem = (js) => {
    const j = js[0];
    const dir = String(j.path || '').split('/').slice(0, -1).join('/');
    return h('div', { class: 'lcard', role: 'button', tabindex: 0, 'data-album': j.group, onclick: () => go?.('library') },
      ic('image', 'l-ic c-amber'),
      h('span', { class: 'l-txt' }, h('b', { class: 'more', dir: 'auto' }, j.title),
        h('small', {}, `${SITE_NAMES[j.site] || siteOf(j.url).name} · ${count(js.filter((x) => x.kind === 'image').length, W.image)} · ${fmtDate(j.finishedAt)}`), dir ? pathText(dir) : null),
      h('span', { class: 'card-acts' },
        btn('share', 'مشاركة', () => Media.share(js.map((x) => x.uri).filter(Boolean)).catch((e) => toast(e.message, 'bad'))),
        btn('trash', 'حذف', () => remove(js))));
  };

  const item = (j) => {
    const color = j.kind === 'audio' ? 'c-violet' : 'c-sky';
    return h('div', { class: 'lcard', role: 'button', tabindex: 0, onclick: () => Sayl.open(j.id).catch((e) => toast(e.message, 'bad')) },
      ic(j.kind === 'audio' ? 'audio' : 'video', `l-ic ${color}`),
      h('span', { class: 'l-txt' }, h('b', { class: 'more', dir: 'auto' }, j.title), h('small', {}, `${SITE_NAMES[j.site] || siteOf(j.url).name} · ${fmtDate(j.finishedAt)}`), j.path ? pathText(j.path) : null),
      // الزران فوق بعضهما ليبقى للعنوان أكبر عرض ممكن
      h('span', { class: 'card-acts' },
        btn('share', 'مشاركة', () => Sayl.share(j.id).catch((e) => toast(e.message, 'bad'))),
        btn('trash', 'حذف', () => remove([j]))));
  };

  const draw = () => {
    const all = doneJobs();
    const sites = [...new Set(all.map((j) => j.site || 'other'))];
    chipsBox.replaceChildren(
      ...[['all', 'الكل'], ['video', 'فيديو'], ['audio', 'صوت'], ['image', 'صور']].map(([v, l]) => h('button', { class: f.kind === v && f.site === 'all' ? 'on' : '', onclick: () => { f.kind = v; f.site = 'all'; draw(); } }, l)),
      ...(sites.length > 1 ? sites.map((s) => h('button', { class: f.site === s ? 'on' : '', onclick: () => { f.site = s; f.kind = 'all'; draw(); } }, SITE_NAMES[s] || s)) : []));
    const kindOf = (j) => (j.group ? 'image' : j.kind === 'music' ? 'audio' : j.kind);
    const shown = all.filter((j) => (f.kind === 'all' || kindOf(j) === f.kind) && (f.site === 'all' || (j.site || 'other') === f.site) && matches(j.title, f.q));
    // صور الألبوم الواحد (وصوته) في سطر واحد بمكان أحدثها
    const rows = [];
    const groups = new Map();
    for (const j of shown) {
      if (!j.group) { rows.push([j]); continue; }
      if (!groups.has(j.group)) { groups.set(j.group, []); rows.push(groups.get(j.group)); }
      groups.get(j.group).push(j);
    }
    list.replaceChildren(...(rows.length ? rows.map((r) => (r[0].group ? albumItem(r) : item(r[0]))) : [h('div', { class: 'card empty' }, ic('history'), all.length ? 'لا نتائج مطابقة.' : 'لم تنزّل شيئاً بعد. ما تنزّله يظهر هنا.')]));
  };

  page.append(
    h('p', { class: 'hint' }, 'كل ما نزّلته، مع موقعه وتاريخه. الملفات في مجلد التنزيلات ← سيل. اضغط على الملف لفتحه.'),
    h('input', { class: 'search', id: 'hist-q', type: 'search', placeholder: 'ابحث في العناوين…', 'aria-label': 'بحث في السجل', oninput: (e) => { f.q = e.target.value; draw(); } }),
    chipsBox, list);
  draw();
  return onJobs(draw);
}

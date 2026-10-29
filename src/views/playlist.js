// صفحة قائمة التشغيل: اختيار المقاطع وإعداد واحد للجميع
import { h, ic, fmtDur } from '../ui.js';
import { siteOf } from '../links.js';
import { choicePicker, uid } from './choice.js';
import { enqueueJobs } from './home.js';

export function openPlaylist(ctx, info, url) {
  ctx.open({ title: 'قائمة تشغيل', render: ({ page, go }) => {
    const site = siteOf(url);
    const picker = choicePicker({});
    const avail = info.entries.map((e, i) => (e.available ? i : -1)).filter((i) => i >= 0);
    const sel = new Set(avail);
    const add = h('button', { id: 'add', class: 'btn grad c-amber wide' });
    const all = h('button', { id: 'all', class: 'link' });
    const sync = () => {
      add.replaceChildren(ic('download'), `أضف ${sel.size} إلى الطابور`);
      add.disabled = !sel.size;
      all.textContent = sel.size === avail.length ? 'إلغاء الكل' : 'تحديد الكل';
      list.querySelectorAll('.pl-item input').forEach((c) => { c.checked = sel.has(Number(c.dataset.i)); });
    };
    const list = h('ul', { class: 'pl-list' }, info.entries.map((e, i) => h('li', { class: `pl-item${e.available ? '' : ' off'}` },
      h('input', { type: 'checkbox', 'data-i': String(i), disabled: !e.available, 'aria-label': e.title || 'مقطع',
        onchange: (ev) => { ev.target.checked ? sel.add(i) : sel.delete(i); sync(); } }),
      h('span', { class: 'pl-n' }, String(i + 1)),
      h('span', { class: 'pl-t' }, e.available ? e.title : 'غير متاح (خاص أو محذوف)'),
      h('span', { class: 'pl-d' }, fmtDur(e.duration)))));
    all.onclick = () => { if (sel.size === avail.length) sel.clear(); else avail.forEach((i) => sel.add(i)); sync(); };
    add.onclick = async () => {
      add.disabled = true;
      const args = picker.args(), kind = picker.kind();
      const jobs = [...sel].sort((a, b) => a - b).map((i) => info.entries[i]).map((e) => ({
        id: uid(), url: e.url, title: e.title, kind, args, thumb: '', site: site.id, folder: info.title,
      }));
      const r = await enqueueJobs(jobs);
      if (r?.added) go('jobs'); else add.disabled = false;
    };
    page.append(
      h('div', { class: 'hero c-amber' }, ic('playlist', 'h-ic'), h('b', {}, info.title),
        h('small', {}, `${info.count} مقطع · ${avail.length} متاح للتنزيل. تُحفظ في مجلد باسم القائمة داخل «سيل».`)),
      h('div', { class: 'card' }, picker.el),
      h('div', { class: 'card' }, h('div', { class: 'pl-bar' }, h('b', {}, 'المقاطع'), all), list),
      h('div', { class: 'sticky-act' }, add),
    );
    sync();
  } });
}

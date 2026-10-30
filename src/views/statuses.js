// حالات واتساب وواتساب أعمال: عرض ما شاهدته وحفظه في Download/سيل/حالات واتساب
import { h, ic, toast, fmtDur } from '../ui.js';
import { Statuses } from '../native.js';

const APPS = [{ value: 'wa', label: 'واتساب' }, { value: 'w4b', label: 'واتساب أعمال' }];

export function openStatuses(ctx) {
  ctx.open({ title: 'حالات واتساب', render: ({ page }) => {
    let app = 'wa';
    const body = h('div', { class: 'st-body' });
    const tabs = h('div', { class: 'seg st-tabs c-wa' }, APPS.map((a) => h('button', {
      class: a.value === app ? 'on' : '', 'data-v': a.value,
      onclick: (e) => { app = a.value; tabs.querySelectorAll('button').forEach((b) => b.classList.toggle('on', b === e.currentTarget)); load(); },
    }, a.label)));

    async function load() {
      body.replaceChildren(h('div', { class: 'card center' }, h('div', { class: 'spinner' })));
      const name = APPS.find((a) => a.value === app).label;
      try {
        const acc = await Statuses.access(app);
        if (!acc.installed) return body.replaceChildren(h('div', { class: 'card empty' }, ic('whatsapp'), `${name} غير مثبّت على هذا الهاتف.`));
        if (!acc.granted) return body.replaceChildren(accessCard(name));
        grid(await Statuses.list(app));
      } catch (e) {
        body.replaceChildren(h('div', { class: 'card empty' }, ic('alert'), e.message || 'تعذّر قراءة الحالات'));
      }
    }

    function accessCard(name) {
      return h('div', { class: 'card st-access' },
        ic('whatsapp', 'l-ic c-wa st-big'),
        h('h3', {}, `السماح بقراءة حالات ${name}`),
        h('ol', { class: 'changes' },
          h('li', {}, 'اضغط «السماح بالوصول» فيفتح أندرويد مجلد الحالات مباشرة.'),
          h('li', {}, 'اضغط «استخدام هذا المجلد» ثم «سماح». مرة واحدة فقط.')),
        h('button', { id: 'grant', class: 'btn grad c-wa wide', onclick: async () => {
          try { const r = await Statuses.request(app); if (r.granted) load(); else toast('لم يُمنح الإذن', 'bad'); } catch (e) { toast(e.message, 'bad'); }
        } }, 'السماح بالوصول'),
        h('p', { class: 'hint' }, 'سَيْل يقرأ مجلد الحالات فقط، ولا يصل إلى محادثاتك أو أي ملف آخر.'));
    }

    function grid(items) {
      if (!items.length) return body.replaceChildren(h('div', { class: 'card empty' }, ic('whatsapp'), 'لا توجد حالات. افتح واتساب وشاهد الحالات أولاً ثم عُد إلى هنا.'));
      const sel = new Set();
      const save = h('button', { id: 'st-save', class: 'btn grad c-wa', disabled: true });
      const sync = () => { save.disabled = !sel.size; save.replaceChildren(ic('download'), sel.size ? `احفظ ${sel.size}` : 'احفظ المحدد'); };
      const doSave = async (names) => {
        if (!names.length) return;
        try {
          await Statuses.save(app, names);
          toast(names.length > 1 ? `حُفظت ${names.length} حالات` : 'حُفظت الحالة', 'ok');
          grid(await Statuses.list(app));
        } catch (e) { toast(e.message, 'bad'); }
      };
      save.onclick = () => doSave([...sel]);
      const cells = items.map((it) => {
        const check = h('button', { class: 'st-check', 'aria-label': 'تحديد', onclick: (e) => {
          e.stopPropagation();
          sel.has(it.name) ? sel.delete(it.name) : sel.add(it.name);
          cell.classList.toggle('sel', sel.has(it.name));
          sync();
        } }, ic('check'));
        const src = Statuses.src(it.thumb);
        const cell = h('div', { class: `st-item${it.saved ? ' saved' : ''}`, onclick: () => Statuses.open(app, it.name).catch((e) => toast(e.message, 'bad')) },
          src ? h('img', { src, alt: '', loading: 'lazy' }) : ic(it.video ? 'video' : 'whatsapp', 'st-ph'),
          it.video && h('span', { class: 'st-dur' }, ic('play'), fmtDur(it.duration) || ''),
          it.saved && h('span', { class: 'st-saved' }, ic('check'), 'محفوظة'),
          check);
        return cell;
      });
      body.replaceChildren(
        h('div', { class: 'st-bar' }, h('span', { class: 'muted' }, `${items.length} حالة`),
          h('div', { class: 'st-acts' }, save, h('button', { id: 'st-all', class: 'btn ghost', onclick: () => doSave(items.filter((i) => !i.saved).map((i) => i.name)) }, 'احفظ الكل'))),
        h('div', { class: 'st-grid' }, cells));
      sync();
    }

    page.append(
      h('div', { class: 'hero c-wa' }, ic('whatsapp', 'h-ic'), h('b', {}, 'حالات واتساب'),
        h('small', {}, 'تظهر الحالة هنا بعد مشاهدتها في واتساب، وتختفي بعد 24 ساعة. تُحفظ في التنزيلات ← سيل ← حالات واتساب.')),
      tabs, body);
    load();
  } });
}

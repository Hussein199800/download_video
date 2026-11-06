// المكتبة: كل ملفات سَيْل، ومجلدات يختارها المستخدم، وكل وسائط الهاتف إن سمح بذلك.
// تصنيف وبحث وفرز وتصفية، وعرض شبكي أو قائمة، وتحديد متعدد، والمكررات والملفات الكبيرة.
import { h, ic, toast, sheet, confirmSheet, explain, infoBtn, fmtBytes, fmtDur, fmtDate } from '../ui.js';
import { Media, Statuses, Sayl } from '../native.js';
import { count, W, iso, matches } from '../text.js';
import { doneJobs, onJobs, forget } from '../state.js';
import { historyView } from './history.js';
import { fileInfoSheet, openConvert, openCompress, openEditor, openMerge, openAudio } from './tools.js';
import { resName } from '../mediarules.js';

const KEY = 'sayl.lib';
const MB = 1024 * 1024;
const LARGE = 100 * MB;
const PAGE = 60;
const KINDS = [['all', 'الكل'], ['video', 'فيديو'], ['audio', 'صوت'], ['image', 'صور']];
const SIZES = [[0, 'أي حجم'], [50 * MB, 'أكبر من 50 م.ب'], [500 * MB, 'أكبر من 500 م.ب'], [1024 * MB, 'أكبر من 1 ج.ب']];
const SORTS = [['date', 'التاريخ'], ['size', 'الحجم'], ['duration', 'المدة'], ['name', 'الاسم']];
const SOURCE_NAME = { sayl: 'سَيْل', folder: 'مجلد مختار', device: 'الهاتف' };
const KIND_ICON = { video: 'video', audio: 'audio', image: 'image' };
const KIND_COLOR = { video: 'c-sky', audio: 'c-violet', image: 'c-amber' };

const load = () => { try { return JSON.parse(localStorage.getItem(KEY) || '{}'); } catch { return {}; } };
const save = (s) => { try { localStorage.setItem(KEY, JSON.stringify(s)); } catch {} };

// الصور المصغرة والمدد المحسوبة تبقى طوال الجلسة
const thumbs = new Map();

export function sortItems(items, by, dir) {
  const k = { date: (x) => x.date, size: (x) => x.size, duration: (x) => x.duration || 0, name: (x) => x.name }[by] || ((x) => x.date);
  const coll = new Intl.Collator('ar', { numeric: true, sensitivity: 'base' });
  const out = [...items].sort((a, b) => (by === 'name' ? coll.compare(k(a), k(b)) : k(a) - k(b)));
  return dir === 'desc' ? out.reverse() : out;
}

export function filterItems(items, f) {
  return items.filter((x) => (f.kind === 'all' || x.kind === f.kind) && (!f.min || x.size >= f.min) && matches(`${x.name} ${x.folder}`, f.q));
}

function folderText(x) {
  return String(x.folder || '').replace(/^Download\b/, 'التنزيلات').split('/').filter(Boolean).map(iso).join(' ← ');
}

export function libraryView(ctx) {
  const { page, open } = ctx;
  page.classList.add('wide');
  const st = { kind: 'all', min: 0, q: '', sort: 'date', dir: 'desc', view: 'grid', sources: ['sayl'], ...load() };
  st.q = '';
  let items = [];
  let shown = [];
  let limit = PAGE;
  let selecting = false;
  const sel = new Set();
  let reqToken = 0;

  const persist = () => save({ kind: st.kind, min: st.min, sort: st.sort, dir: st.dir, view: st.view, sources: st.sources });

  const statsBox = h('div', { class: 'stats' });
  const sourcesBox = h('div', { class: 'chips c-green', role: 'group', 'aria-label': 'المصادر' });
  const kindsBox = h('div', { class: 'chips c-green', role: 'group', 'aria-label': 'النوع' });
  const search = h('input', { class: 'search', id: 'lib-q', type: 'search', placeholder: 'ابحث بالاسم…', enterkeyhint: 'search', 'aria-label': 'بحث في المكتبة' });
  const sortSel = h('select', { class: 'field sm-field', id: 'lib-sort', 'aria-label': 'الفرز' }, SORTS.map(([v, l]) => h('option', { value: v, selected: st.sort === v }, `الفرز: ${l}`)));
  const sizeSel = h('select', { class: 'field sm-field', id: 'lib-size', 'aria-label': 'تصفية بالحجم' }, SIZES.map(([v, l]) => h('option', { value: String(v), selected: st.min === v }, l)));
  const dirBtn = h('button', { class: 'icon-btn', id: 'lib-dir', type: 'button' });
  const viewBtn = h('button', { class: 'icon-btn', id: 'lib-view', type: 'button' });
  const selBtn = h('button', { class: 'icon-btn', id: 'lib-select', type: 'button', 'aria-label': 'تحديد عدة ملفات', html: ic('select').innerHTML });
  const list = h('div', { class: 'lib-list' });
  const more = h('button', { class: 'btn ghost wide', id: 'lib-more', hidden: true });
  const bar = h('div', { class: 'sel-bar', hidden: true, role: 'toolbar', 'aria-label': 'إجراءات المحدد' });

  // ---------- المصادر ----------
  async function toggleSource(src) {
    const on = st.sources.includes(src);
    if (on) {
      if (src === 'sayl' && st.sources.length === 1) return toast('اختر مصدراً واحداً على الأقل');
      st.sources = st.sources.filter((x) => x !== src);
      persist(); drawSources(); refresh();
      return;
    }
    if (src === 'folder') {
      const fs = await Media.folders();
      if (!fs.length) {
        try { await Media.addFolder(); } catch (e) { if (!/لم يُختر/.test(e.message)) toast(e.message, 'bad'); return; }
      }
    }
    if (src === 'device' && !(await deviceConsent())) return;
    st.sources = [...st.sources, src];
    persist(); drawSources(); refresh();
  }

  // شرح وتنبيه قبل طلب صلاحية كل وسائط الهاتف
  async function deviceConsent() {
    const state = await Media.deviceAccess();
    if (state !== 'denied') return true;
    const yes = await confirmSheet({
      title: 'عرض كل وسائط الهاتف؟',
      text: 'سيقرأ سَيْل قائمة الفيديوهات والصوتيات والصور في هاتفك ليعرضها هنا فقط. لا يُرفع أي ملف ولا يُرسل لأي جهة، ويمكنك سحب الإذن متى شئت من إعدادات أندرويد. تنبيه: الحذف من المكتبة يحذف الملف من الهاتف نهائياً.',
      yes: 'السماح', no: 'لاحقاً',
    });
    if (!yes) return false;
    const r = await Media.requestDeviceAccess();
    if (r.state === 'granted') return true;
    if (r.state === 'partial') { toast('مُنح وصول جزئي: تظهر الملفات التي سمحت بها فقط'); return true; }
    if (r.blocked) {
      const go = await confirmSheet({ title: 'الإذن مرفوض', text: 'رفضت الإذن سابقاً، فلن يعرض أندرويد النافذة مجدداً. افتح إعدادات التطبيق ← الأذونات ← الصور والفيديو والموسيقى.', yes: 'فتح الإعدادات', no: 'إغلاق' });
      if (go) Media.openAppSettings();
    } else toast('لم يُمنح الإذن', 'bad');
    return false;
  }

  async function manageFolders() {
    const fs = await Media.folders();
    const close = sheet(h('h3', {}, 'مجلداتي'),
      h('p', { class: 'muted' }, 'مجلدات اخترتها بنفسك تظهر ملفاتها في المكتبة. سَيْل يقرأ هذه المجلدات فقط.'),
      ...fs.map((f) => h('div', { class: 'file-row' }, ic('folder', 'l-ic sm c-green'), h('span', { class: 'l-txt' }, h('b', { class: 'ell', dir: 'auto' }, f.label)),
        h('button', { class: 'icon-btn', 'aria-label': `إزالة ${f.label}`, onclick: async () => { await Media.removeFolder(f.uri); close(); refresh(); }, html: ic('x').innerHTML }))),
      h('button', { class: 'btn primary wide', onclick: async () => { try { await Media.addFolder(); close(); if (!st.sources.includes('folder')) { st.sources.push('folder'); persist(); drawSources(); } refresh(); } catch (e) { if (!/لم يُختر/.test(e.message)) toast(e.message, 'bad'); } } }, ic('plus'), 'أضف مجلداً'),
      h('button', { class: 'btn ghost wide', onclick: () => close() }, 'إغلاق'));
  }

  function drawSources() {
    const chip = (src, label) => h('button', { class: st.sources.includes(src) ? 'on' : '', 'data-src': src, 'aria-pressed': String(st.sources.includes(src)), onclick: () => toggleSource(src) }, label);
    sourcesBox.replaceChildren(chip('sayl', 'سَيْل'), chip('folder', 'مجلداتي'), chip('device', 'كل الهاتف'),
      h('button', { class: 'chip-ic', 'aria-label': 'إدارة المجلدات', onclick: manageFolders, html: ic('folder').innerHTML }));
  }

  // ---------- الإحصاءات ----------
  function drawStats() {
    const total = shown.reduce((n, x) => n + (x.size || 0), 0);
    const large = items.filter((x) => x.size >= LARGE);
    const largeSize = large.reduce((n, x) => n + x.size, 0);
    const byKind = Object.fromEntries(KINDS.slice(1).map(([k]) => [k, items.filter((x) => x.kind === k).length]));
    statsBox.replaceChildren(
      h('button', { class: 'stat c-green', type: 'button', id: 'stat-count', onclick: () => explain('عدد الملفات', `${count(items.length, W.file)} في المصادر المختارة: ${count(byKind.video, W.file)} فيديو، و${count(byKind.audio, W.file)} صوت، و${count(byKind.image, W.file)} صور. الرقم أعلاه يحسب ما يطابق البحث والتصفية الحالية فقط.`) },
        h('small', {}, 'الملفات', ic('info')), h('b', {}, String(shown.length))),
      h('button', { class: 'stat c-sky', type: 'button', id: 'stat-size', onclick: () => explain('المساحة', `مجموع أحجام الملفات المعروضة الآن: ${fmtBytes(total) || '0'}. يتغير مع البحث والتصفية. الأحجام كما يذكرها أندرويد لكل ملف.`) },
        h('small', {}, 'المساحة', ic('info')), h('b', {}, fmtBytes(total) || '0')),
      h('button', { class: 'stat c-amber', type: 'button', id: 'stat-large', onclick: () => {
        st.min = LARGE; st.sort = 'size'; st.dir = 'desc'; sizeSel.value = String(LARGE); sortSel.value = 'size'; persist(); draw();
        toast(`${count(large.length, W.file)} أكبر من ${iso('100 م.ب')} · ${fmtBytes(largeSize) || '0'}`);
      } }, h('small', {}, 'الكبيرة', ic('filter')), h('b', {}, String(large.length))));
  }

  // ---------- العناصر ----------
  function thumbOf(x, el) {
    const cached = thumbs.get(x.uri);
    const apply = (r) => {
      if (r.duration && !x.duration) x.duration = r.duration;
      if (r.width && !x.width) { x.width = r.width; x.height = r.height; }
      const dur = el.querySelector('.lib-dur');
      if (dur && x.duration) dur.textContent = fmtDur(x.duration / 1000);
      const src = Statuses.src(r.thumb);
      if (src) {
        const img = h('img', { src, alt: '', loading: 'lazy', decoding: 'async', onerror: (e) => e.target.remove() });
        el.querySelector('.lib-th')?.replaceChildren(img);
      }
    };
    if (cached) return apply(cached);
    io.observe(el);
    el._thumb = () => Media.thumb(x.uri, x.kind, x.date).then((r) => { thumbs.set(x.uri, r); apply(r); }).catch(() => thumbs.set(x.uri, {}));
  }

  const io = typeof IntersectionObserver === 'function'
    ? new IntersectionObserver((es) => es.forEach((e) => { if (e.isIntersecting) { io.unobserve(e.target); e.target._thumb?.(); } }), { rootMargin: '300px' })
    : { observe: (el) => el._thumb?.(), unobserve() {} };

  function meta(x) {
    return [x.duration ? iso(fmtDur(x.duration / 1000)) : '', x.width ? resName(x.width, x.height) || iso(`${x.width}×${x.height}`) : '', fmtBytes(x.size)].filter(Boolean).join(' · ');
  }

  function card(x) {
    const on = sel.has(x.uri);
    const th = h('span', { class: `lib-th ${KIND_COLOR[x.kind]}` }, ic(KIND_ICON[x.kind]));
    const press = longPress(() => { if (!selecting) { selecting = true; sel.add(x.uri); drawBar(); drawList(); } });
    const el = h('button', {
      class: `lib-item ${st.view}${on ? ' sel' : ''}`, type: 'button', 'data-uri': x.uri, 'aria-pressed': selecting ? String(on) : null,
      onclick: () => { if (press.fired()) return; if (selecting) { on ? sel.delete(x.uri) : sel.add(x.uri); drawBar(); drawList(); } else details(x); },
      ...press.attrs,
    },
    th,
    x.kind !== 'image' && h('span', { class: 'lib-dur' }, x.duration ? fmtDur(x.duration / 1000) : ''),
    selecting && h('span', { class: 'lib-check', 'aria-hidden': 'true' }, ic('check')),
    h('span', { class: 'lib-txt' }, h('b', { class: st.view === 'grid' ? 'ell' : 'clamp2', dir: 'auto' }, x.name), h('small', {}, meta(x)),
      st.view === 'list' && x.folder ? h('small', { class: 'ell' }, folderText(x)) : null));
    thumbOf(x, el);
    return el;
  }

  function drawList() {
    const page1 = shown.slice(0, limit);
    list.className = `lib-list ${st.view}`;
    list.replaceChildren(...(page1.length ? page1.map(card) : [h('div', { class: 'card empty' }, ic('grid'),
      items.length ? 'لا نتائج مطابقة للبحث أو التصفية.' : st.sources.length === 1 ? 'لا ملفات بعد. ما تنزّله أو تعالجه بسَيْل يظهر هنا.' : 'لا ملفات في المصادر المختارة.')]));
    more.hidden = shown.length <= limit;
    more.textContent = `عرض المزيد (${count(shown.length - limit, W.file)})`;
  }

  function draw() {
    shown = sortItems(filterItems(items, st), st.sort, st.dir);
    kindsBox.replaceChildren(...KINDS.map(([v, l]) => h('button', { class: st.kind === v ? 'on' : '', 'data-kind': v, 'aria-pressed': String(st.kind === v), onclick: () => { st.kind = v; limit = PAGE; persist(); draw(); } }, l)));
    dirBtn.innerHTML = ic(st.dir === 'desc' ? 'down' : 'up').innerHTML;
    dirBtn.setAttribute('aria-label', st.dir === 'desc' ? 'تنازلي' : 'تصاعدي');
    viewBtn.innerHTML = ic(st.view === 'grid' ? 'list' : 'grid').innerHTML;
    viewBtn.setAttribute('aria-label', st.view === 'grid' ? 'عرض قائمة' : 'عرض شبكي');
    selBtn.classList.toggle('on', selecting);
    drawStats();
    drawList();
  }

  // ---------- التحديد المتعدد ----------
  function selected() { return items.filter((x) => sel.has(x.uri)); }

  function drawBar() {
    bar.hidden = !selecting;
    if (!selecting) return;
    const s = selected();
    const size = s.reduce((n, x) => n + x.size, 0);
    const b = (icon, label, fn, cls = '') => h('button', { class: `sel-act ${cls}`, type: 'button', 'data-act': label, disabled: !s.length, onclick: fn }, ic(icon), h('span', {}, label));
    bar.replaceChildren(
      h('div', { class: 'sel-head' }, h('b', {}, s.length ? `${count(s.length, W.file)} · ${fmtBytes(size)}` : 'اختر ملفات'),
        h('button', { class: 'link', id: 'sel-all', onclick: () => { shown.slice(0, limit).forEach((x) => sel.add(x.uri)); drawBar(); drawList(); } }, 'تحديد الظاهر'),
        h('button', { class: 'link', id: 'sel-done', onclick: () => { selecting = false; sel.clear(); drawBar(); draw(); } }, 'إلغاء')),
      h('div', { class: 'sel-acts' },
        b('share', 'مشاركة', () => Media.share(s.map((x) => x.uri)).catch((e) => toast(e.message, 'bad'))),
        b('tools', 'أدوات', () => toolsFor(s)),
        b('trash', 'حذف', () => remove(s), 'danger')));
  }

  function toolsFor(list0) {
    const vids = list0.filter((x) => x.kind === 'video');
    const av = list0.filter((x) => x.kind !== 'image');
    const one = list0.length === 1;
    const opt = (icon, color, title, sub, fn, ok) => ok && h('button', { class: 'lcard', type: 'button', onclick: () => { close(); fn(); } }, ic(icon, `l-ic ${color}`), h('span', { class: 'l-txt' }, h('b', {}, title), h('small', {}, sub)), ic('chevL', 'l-go'));
    const close = sheet(h('h3', {}, `أدوات (${count(list0.length, W.file)})`),
      h('div', { class: 'lcards' }, ...[
        opt('compress', 'c-sky', 'ضغط', `${count(vids.length, W.file)} فيديو`, () => openCompress(ctx, vids), vids.length),
        opt('convert', 'c-teal', 'تحويل الصيغة', 'MP4 وMKV وMOV وWEBM وAVI', () => openConvert(ctx, vids), vids.length),
        opt('audio', 'c-violet', 'استخراج الصوت', 'MP3 وM4A وWAV وFLAC وOPUS', () => openAudio(ctx, av), av.length),
        opt('merge', 'c-amber', 'دمج في فيديو واحد', 'بالترتيب الحالي', () => openMerge(ctx, vids), vids.length >= 2),
        opt('cut', 'c-pink', 'قص ولقطة', 'مقطع أو صورة أو صوته', () => openEditor(ctx, av), one && av.length === 1),
      ].filter(Boolean)),
      list0.some((x) => x.kind === 'image') && h('p', { class: 'hint' }, 'الصور لا تُعالج بهذه الأدوات.'),
      h('button', { class: 'btn ghost wide', onclick: () => close() }, 'إغلاق'));
  }

  async function remove(list0) {
    const size = list0.reduce((n, x) => n + x.size, 0);
    const others = list0.filter((x) => !x.owned).length;
    const yes = await confirmSheet({
      title: list0.length > 1 ? `حذف ${count(list0.length, W.file)}؟` : 'حذف الملف؟',
      text: `${list0.length === 1 ? list0[0].name : ''}\nيُحذف من الهاتف نهائياً ويحرر ${fmtBytes(size) || 'مساحة'}. لا يمكن التراجع.${others ? ' سيطلب أندرويد موافقتك على حذف ملفات لم ينشئها سَيْل.' : ''}`,
      yes: 'حذف', danger: true,
    });
    if (!yes) return;
    try {
      const r = await Media.deleteItems(list0.map((x) => x.uri));
      // ما نزّله سَيْل يُزال من سجل التنزيلات أيضاً
      const gone = new Set(list0.map((x) => x.uri));
      for (const j of doneJobs()) if (j.uri && gone.has(j.uri)) { await Sayl.remove(j.id, false).catch(() => {}); forget(j.id); }
      toast(r.declined ? 'لم تُمنح موافقة الحذف' : r.deleted ? `حُذف ${count(r.deleted, W.file)}` : 'لم يُحذف شيء', r.deleted ? 'ok' : 'bad');
      list0.forEach((x) => sel.delete(x.uri));
      refresh();
    } catch (e) { toast(e.message, 'bad'); }
  }

  function rename(x) {
    const dot = x.name.lastIndexOf('.');
    const ext = dot > 0 ? x.name.slice(dot) : '';
    const input = h('input', { class: 'field', id: 'rename-in', value: dot > 0 ? x.name.slice(0, dot) : x.name, dir: 'auto', 'aria-label': 'الاسم الجديد', enterkeyhint: 'done' });
    const err = h('p', { class: 'err-text', role: 'alert' });
    const btn = h('button', { class: 'btn primary', id: 'rename-ok' }, 'حفظ');
    const valid = () => {
      const v = input.value.trim();
      const bad = !v ? 'اكتب اسماً.' : /[\\/:*?"<>|]/.test(v) ? 'لا يُسمح بالرموز \\ / : * ? " < > |' : v.startsWith('.') ? 'لا يبدأ الاسم بنقطة.' : '';
      err.textContent = bad;
      input.classList.toggle('bad', Boolean(bad));
      btn.disabled = Boolean(bad);
      return !bad;
    };
    input.addEventListener('input', valid);
    btn.onclick = async () => {
      if (!valid()) return;
      btn.disabled = true;
      try {
        const r = await Media.rename(x.uri, input.value.trim() + ext);
        x.name = r.name; x.uri = r.uri || x.uri;
        close(); toast('غُيّر الاسم', 'ok'); draw();
      } catch (e) { err.textContent = e.message; btn.disabled = false; }
    };
    input.addEventListener('keydown', (e) => e.key === 'Enter' && btn.onclick());
    const close = sheet(h('h3', {}, 'إعادة التسمية'), h('label', { class: 'rename-row' }, input, ext && h('span', { class: 'ltr muted' }, ext)), err,
      h('div', { class: 'row-btns' }, h('button', { class: 'btn ghost', onclick: () => close() }, 'إلغاء'), btn));
    setTimeout(() => { input.focus(); input.select(); }, 50);
  }

  function details(x) {
    const job = doneJobs().find((j) => j.uri === x.uri);
    const th = thumbs.get(x.uri);
    const src = Statuses.src(th?.thumb);
    const rows = [
      ['النوع', { video: 'فيديو', audio: 'صوت', image: 'صورة' }[x.kind]],
      ['الحجم', fmtBytes(x.size) || '—'],
      x.duration && ['المدة', iso(fmtDur(x.duration / 1000))],
      x.width && ['الأبعاد', `${iso(`${x.width}×${x.height}`)}${resName(x.width, x.height) ? ` · ${resName(x.width, x.height)}` : ''}`],
      ['التاريخ', fmtDate(x.date)],
      ['المجلد', folderText(x) || '—'],
      ['المصدر', job ? `سَيْل · ${job.site || ''}` : SOURCE_NAME[x.source]],
    ].filter(Boolean);
    const act = (icon, label, fn, cls = 'ghost') => h('button', { class: `btn ${cls}`, 'data-act': label, onclick: () => { close(); fn(); } }, ic(icon), label);
    const close = sheet(
      src ? h('img', { class: 'det-thumb', src, alt: '' }) : null,
      h('h3', { dir: 'auto', class: 'clamp2' }, x.name),
      h('dl', { class: 'kv' }, rows.flatMap(([k, v]) => [h('dt', {}, k), h('dd', {}, v)])),
      h('div', { class: 'row-btns' }, act('play', 'فتح', () => Media.open(x.uri, x.mime, x.name).catch((e) => toast(e.message, 'bad')), 'primary'), act('share', 'مشاركة', () => Media.share([x.uri]).catch((e) => toast(e.message, 'bad')))),
      x.kind !== 'image' && h('div', { class: 'row-btns' }, act('info', 'التفاصيل التقنية', () => fileInfoSheet(x)), act('tools', 'أدوات', () => toolsFor([x]))),
      h('div', { class: 'row-btns' }, act('edit', 'إعادة تسمية', () => rename(x)), act('trash', 'حذف', () => remove([x]), 'danger')),
    );
  }

  // ---------- التحميل والتحديث ----------
  async function refresh() {
    const my = ++reqToken;
    try {
      const got = await Media.library(st.sources);
      // نتيجة طلب قديم وصلت بعد طلب أحدث: تُهمل
      if (my !== reqToken) return;
      items = got;
      const live = new Set(items.map((x) => x.uri));
      [...sel].forEach((u) => !live.has(u) && sel.delete(u));
      draw();
      drawBar();
    } catch (e) {
      if (my === reqToken) list.replaceChildren(h('div', { class: 'card empty' }, ic('alert'), e.message || 'تعذّرت قراءة المكتبة'));
    }
  }

  // البحث أثناء الكتابة يعيد رسم النتائج فقط، ولا يلمس الحقل نفسه
  let qTimer;
  search.addEventListener('input', () => { clearTimeout(qTimer); qTimer = setTimeout(() => { st.q = search.value; limit = PAGE; draw(); }, 120); });
  sortSel.addEventListener('change', () => { st.sort = sortSel.value; st.dir = st.sort === 'name' ? 'asc' : 'desc'; persist(); draw(); });
  sizeSel.addEventListener('change', () => { st.min = Number(sizeSel.value); limit = PAGE; persist(); draw(); });
  dirBtn.onclick = () => { st.dir = st.dir === 'desc' ? 'asc' : 'desc'; persist(); draw(); };
  viewBtn.onclick = () => { st.view = st.view === 'grid' ? 'list' : 'grid'; persist(); drawList(); draw(); };
  selBtn.onclick = () => { selecting = !selecting; if (!selecting) sel.clear(); drawBar(); draw(); };
  more.onclick = () => { limit += PAGE; drawList(); };

  page.append(
    h('div', { class: 'intro' }, h('h2', {}, 'المكتبة'), h('p', {}, 'كل ما نزّلته أو عالجته، ومجلداتك إن أضفتها. اضغط ملفاً لتفاصيله، أو اضغط مطولاً لتحديد عدة ملفات.')),
    statsBox,
    sourcesBox,
    h('div', { class: 'lib-tools' }, search, h('div', { class: 'lib-row' }, sortSel, dirBtn, sizeSel, viewBtn, selBtn)),
    kindsBox,
    h('div', { class: 'lib-links' },
      h('button', { class: 'link', id: 'open-dups', onclick: () => openDuplicates(ctx, items) }, ic('dup'), 'الملفات المكررة'),
      h('button', { class: 'link', id: 'open-history', onclick: () => open({ title: 'سجل التنزيلات', render: historyView }) }, ic('history'), 'سجل التنزيلات'),
      infoBtn('المكتبة', 'الملفات تُقرأ من مكانها على الهاتف ولا تُنسخ. «سَيْل» = مجلد التنزيلات ← سيل. «مجلداتي» = مجلدات تختارها أنت. «كل الهاتف» يحتاج إذنك ويعرض كل الوسائط. الحذف من هنا يحذف الملف من الهاتف.')),
    list, more, bar);
  drawSources();
  draw();
  refresh();
  const stopLib = Media.onLibraryChanged?.(() => refresh());
  let doneCount = doneJobs().length;
  const stopJobs = onJobs(() => { const n = doneJobs().length; if (n !== doneCount) { doneCount = n; refresh(); } });
  return () => { stopJobs(); stopLib?.then?.((l) => l?.remove?.()); io.disconnect?.(); };
}

// الضغط المطوّل باللمس يبدأ التحديد المتعدد، دون أن يُعدّ ضغطة عادية
function longPress(fn) {
  let t = 0;
  let fired = false;
  let x0 = 0, y0 = 0;
  const cancel = () => clearTimeout(t);
  return {
    fired: () => { const f = fired; fired = false; return f; },
    attrs: {
      onpointerdown: (e) => { fired = false; x0 = e.clientX; y0 = e.clientY; cancel(); t = setTimeout(() => { fired = true; navigator.vibrate?.(15); fn(); }, 480); },
      onpointermove: (e) => { if (Math.abs(e.clientX - x0) + Math.abs(e.clientY - y0) > 12) cancel(); },
      onpointerup: cancel,
      onpointercancel: cancel,
      oncontextmenu: (e) => e.preventDefault(),
    },
  };
}

// ---------- المكررات ----------
function openDuplicates(ctx, items) {
  ctx.open({ title: 'الملفات المكررة', render: ({ page }) => {
    const body = h('div', { class: 'card center' }, h('div', { class: 'spinner' }), h('p', { class: 'muted', id: 'dup-progress' }, 'جارٍ المقارنة…'));
    page.append(h('p', { class: 'hint' }, 'يقارن سَيْل الحجم أولاً، ثم بصمة من بداية الملف ووسطه ونهايته. راجع كل مجموعة واختر ما تحذفه؛ الأقدم محدد للإبقاء افتراضياً.'), body);
    const stop = Media.onDupProgress?.((p) => { const el = document.getElementById('dup-progress'); if (el) el.textContent = `جارٍ المقارنة… ${iso(`${p.done}/${p.total}`)}`; });
    Media.duplicates(items).then((groups) => {
      const byUri = new Map(items.map((x) => [x.uri, x]));
      const gs = groups.map((g) => g.map((u) => byUri.get(u)).filter(Boolean).sort((a, b) => a.date - b.date)).filter((g) => g.length > 1);
      if (!gs.length) return body.replaceWith(h('div', { class: 'card empty' }, ic('check'), 'لا ملفات مكررة في المصادر المختارة.'));
      const del = new Set(gs.flatMap((g) => g.slice(1).map((x) => x.uri)));
      const waste = gs.reduce((n, g) => n + g.slice(1).reduce((m, x) => m + x.size, 0), 0);
      const btn = h('button', { class: 'btn danger wide', id: 'dup-delete' });
      const sync = () => {
        const sz = [...del].reduce((n, u) => n + (byUri.get(u)?.size || 0), 0);
        btn.disabled = !del.size;
        btn.replaceChildren(ic('trash'), del.size ? `احذف ${count(del.size, W.file)} (${fmtBytes(sz)})` : 'اختر ما تحذفه');
      };
      const groupsEl = gs.map((g, i) => h('div', { class: 'card dup-group' },
        h('div', { class: 'card-h' }, h('h2', {}, `المجموعة ${i + 1}`), h('span', { class: 'tag' }, `${count(g.length, W.file)} · ${fmtBytes(g[0].size)}`)),
        ...g.map((x, k) => h('label', { class: 'dup-row' },
          h('input', { type: 'checkbox', checked: del.has(x.uri), 'aria-label': `حذف ${x.name}`, onchange: (e) => {
            e.target.checked ? del.add(x.uri) : del.delete(x.uri);
            // لا يُحذف كل نسخ المجموعة دون تنبيه
            if (g.every((y) => del.has(y.uri))) toast('حددت كل النسخ؛ لن تبقى أي نسخة', 'bad');
            sync();
          } }),
          h('span', { class: 'l-txt' }, h('b', { class: 'ell', dir: 'auto' }, x.name), h('small', {}, `${fmtDate(x.date)}${k === 0 ? ' · الأقدم' : ''}`), h('small', { class: 'ell' }, folderText(x)))))));
      btn.onclick = async () => {
        const yes = await confirmSheet({ title: `حذف ${count(del.size, W.file)}؟`, text: 'تُحذف النسخ المحددة من الهاتف نهائياً. لا يمكن التراجع.', yes: 'حذف', danger: true });
        if (!yes) return;
        try {
          const r = await Media.deleteItems([...del]);
          toast(r.deleted ? `حُذف ${count(r.deleted, W.file)}` : 'لم يُحذف شيء', r.deleted ? 'ok' : 'bad');
          ctx.back();
        } catch (e) { toast(e.message, 'bad'); }
      };
      body.replaceWith(h('div', { class: 'stats two' },
        h('div', { class: 'stat c-amber' }, h('small', {}, 'مجموعات مكررة'), h('b', {}, String(gs.length))),
        h('div', { class: 'stat c-pink' }, h('small', {}, 'مساحة يمكن تحريرها'), h('b', {}, fmtBytes(waste)))), ...groupsEl, h('div', { class: 'sticky-act' }, btn));
      sync();
    }).catch((e) => body.replaceWith(h('div', { class: 'card empty' }, ic('alert'), e.message || 'تعذّرت المقارنة')));
    return () => stop?.then?.((l) => l?.remove?.());
  } });
}

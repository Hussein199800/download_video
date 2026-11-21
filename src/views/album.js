// ألبوم صور منشور (تيك توك): معاينات تُحمّل عند ظهورها، و«تنزيل الكل» أو صور بعينها،
// والمحفوظ سابقاً معلَّم (ويمكن إعادة تنزيله إن حُذف ملفه)، وكل صورة مهمة في الطابور ضمن مجموعة المنشور.
import { h, ic, toast } from '../ui.js';
import { Sayl } from '../native.js';
import { count, W, iso } from '../text.js';
import { albumFolder } from '../formats.js';
import { doneJobs } from '../state.js';
import { enqueueJobs } from './home.js';
import { whenPicker } from './when.js';
import { uid } from './choice.js';

const pad = (n) => String(n).padStart(3, '0');
export const albumGroup = (info) => `${info.site}:${info.id}`;

// أرقام الصور المحفوظة فعلاً (المهمة مكتملة والملف ما زال موجوداً)
async function savedIndexes(info) {
  const g = albumGroup(info);
  const jobs = doneJobs().filter((j) => j.group === g && j.kind === 'image' && j.uri);
  if (!jobs.length) return new Set();
  const exists = await Sayl.exists(jobs.map((j) => j.uri)).catch(() => jobs.map(() => true));
  const idx = (j) => Number(String(j.url).split('#')[1]);
  return new Set(jobs.filter((_, i) => exists[i]).map(idx));
}

export function openAlbum(ctx, info) {
  ctx.open({ title: 'ألبوم صور', render: ({ page, go }) => {
    const sel = new Set(info.entries.map((e) => e.index));
    let saved = new Set();
    let withAudio = false;
    const when = whenPicker();
    const add = h('button', { id: 'album-add', class: 'btn grad c-amber wide' });
    const all = h('button', { id: 'album-all', class: 'link' });
    const status = h('small', { class: 'muted', id: 'album-status' });
    const grid = h('div', { class: 'album-grid' });

    const sync = () => {
      const n = sel.size + (withAudio ? 1 : 0);
      add.disabled = !n;
      add.replaceChildren(ic('download'), sel.size === info.count - saved.size && !saved.size ? `نزّل الكل (${count(info.count, W.image)})` : n ? `نزّل ${count(sel.size, W.image)}${withAudio ? ' والصوت' : ''}` : 'اختر صوراً');
      all.textContent = sel.size === info.count ? 'إلغاء الكل' : 'تحديد الكل';
      status.textContent = saved.size ? `محفوظ منها ${count(saved.size, W.image)}؛ غير المحفوظ محدد` : '';
      grid.querySelectorAll('.album-item').forEach((el) => {
        const i = Number(el.dataset.i);
        el.classList.toggle('sel', sel.has(i));
        el.classList.toggle('saved', saved.has(i));
        el.setAttribute('aria-pressed', String(sel.has(i)));
      });
    };

    // المعاينات تُحمّل حين تقترب من الشاشة فقط
    const io = typeof IntersectionObserver === 'function'
      ? new IntersectionObserver((es) => es.forEach((e) => { if (e.isIntersecting) { io.unobserve(e.target); e.target.src = e.target.dataset.src; } }), { rootMargin: '400px' })
      : { observe: (img) => { img.src = img.dataset.src; }, disconnect() {} };

    grid.append(...info.entries.map((e) => {
      const img = h('img', { alt: `الصورة ${e.index}`, 'data-src': e.urls[0], referrerpolicy: 'no-referrer', decoding: 'async', onerror: (ev) => ev.target.remove() });
      io.observe(img);
      const ratio = e.width && e.height ? `${e.width} / ${e.height}` : '3 / 4';
      return h('button', { class: 'album-item', type: 'button', 'data-i': String(e.index), style: `aspect-ratio:${ratio}`,
        onclick: () => { sel.has(e.index) ? sel.delete(e.index) : sel.add(e.index); sync(); } },
      h('span', { class: 'album-ph' }, ic('image')), img,
      h('span', { class: 'album-n' }, String(e.index)),
      h('span', { class: 'album-check', 'aria-hidden': 'true' }, ic('check')),
      h('span', { class: 'album-saved' }, ic('check'), 'محفوظة'));
    }));

    all.onclick = () => { if (sel.size === info.count) sel.clear(); else info.entries.forEach((e) => sel.add(e.index)); sync(); };

    add.onclick = async () => {
      const folder = albumFolder(info.title, info.id);
      const group = albumGroup(info);
      const base = { title: info.title, args: [], thumb: '', site: info.site, folder, group, author: info.uploader, ...when.get() };
      const jobs = info.entries.filter((e) => sel.has(e.index)).map((e) => ({
        ...base, id: uid(), url: `${info.url}#${pad(e.index)}`, kind: 'image', choice: { index: e.index, urls: e.urls, page: info.url },
      }));
      if (withAudio && info.audio) jobs.push({ ...base, id: uid(), url: `${info.url}#audio`, kind: 'music', choice: { index: 0, urls: [info.audio], page: info.url } });
      if (!jobs.length) return;
      add.disabled = true;
      const r = await enqueueJobs(jobs);
      if (r?.added) go('jobs'); else add.disabled = false;
    };

    page.append(...[
      h('div', { class: 'hero c-amber' }, ic('image', 'h-ic'),
        h('b', { class: 'more', style: '--lines:3', dir: 'auto' }, info.title),
        h('small', {}, [info.uploader && iso(`@${info.uploader}`), count(info.count, W.image)].filter(Boolean).join(' · ')),
        h('small', { class: 'more', style: '--lines:1' }, `تُحفظ في ${['سيل', 'تيك توك', albumFolder(info.title, info.id)].map(iso).join(' ← ')}`)),
      h('div', { class: 'card' }, h('div', { class: 'pl-bar' }, h('b', {}, 'الصور'), all), status, grid),
      info.audio && h('label', { class: 'set-row card' }, h('span', {}, 'ومعها صوت المنشور', h('small', {}, 'الموسيقى المرافقة للصور، في الألبوم نفسه.')),
        h('input', { type: 'checkbox', class: 'sw', id: 'album-audio', onchange: (e) => { withAudio = e.target.checked; sync(); } })),
      h('div', { class: 'card' }, when.el),
      h('div', { class: 'sticky-act' }, add),
    ].filter(Boolean));
    sync();
    // المحفوظ سابقاً يُعلَّم ويُستثنى من التحديد
    savedIndexes(info).then((s) => { saved = s; s.forEach((i) => sel.delete(i)); sync(); });
    return () => io.disconnect();
  } });
}

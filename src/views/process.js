// بطاقة عملية معالجة: التقدم والمرحلة، والحجم قبل وبعد، والإلغاء وإعادة المحاولة وفتح الناتج
import { h, ic, toast, fmtBytes, pct, infoBtn } from '../ui.js';
import { Media } from '../native.js';
import { iso } from '../text.js';
import { forgetTask, refreshTasks } from '../tasks.js';

export const OPS = {
  convert: { name: 'تحويل الصيغة', icon: 'convert' },
  audio: { name: 'استخراج الصوت', icon: 'audio' },
  compress: { name: 'ضغط الفيديو', icon: 'compress' },
  trim: { name: 'قص', icon: 'cut' },
  merge: { name: 'دمج', icon: 'merge' },
  frame: { name: 'لقطة', icon: 'image' },
};

const STATE = { queued: ['في الانتظار', ''], running: ['يعمل', 'run'], done: ['اكتمل', 'ok'], failed: ['تعذّر', 'bad'], canceled: ['أُلغي', 'warn'] };
const ENGINE = { copy: 'دون إعادة ترميز', hw: 'بالعتاد', cpu: 'بالمعالج' };

// نسبة التوفير بين الحجم الأصلي والناتج
export const saving = (src, out) => (src > 0 && out > 0 ? Math.round((1 - out / src) * 100) : null);

export function taskCard(t) {
  const [label, cls] = STATE[t.state] || [t.state, ''];
  const op = OPS[t.op] || { name: t.op, icon: 'tools' };
  const act = (icon, text, fn, extra = 'ghost') => h('button', { class: `btn sm ${extra}`, 'data-act': text,
    onclick: async () => { try { await fn(); } catch (e) { toast(e.message, 'bad'); } } }, ic(icon), text);
  const out = t.outputs?.[0];
  const sub = [op.name, ENGINE[t.engine]].filter(Boolean).join(' · ');
  const save = saving(t.srcSize, t.outSize);
  const sizes = t.state === 'done' && t.outSize > 0
    ? h('div', { class: 'job-meta' },
      h('span', {}, `${fmtBytes(t.srcSize)} ← ${fmtBytes(t.outSize)}`),
      t.op === 'compress' && save != null ? h('span', { class: save > 0 ? 'ok-text' : '' }, save > 0 ? `وُفّر ${iso(`${save}%`)}` : 'لم يصغر') : null)
    : null;
  return h('div', { class: 'job c-teal', 'data-task': t.id },
    h('div', { class: 'job-h' }, ic(op.icon, 'job-ic'),
      h('div', { class: 'job-t' }, h('b', { dir: 'auto' }, t.title), h('small', {}, sub)),
      h('span', { class: `tag ${cls}` }, label)),
    t.state === 'running' && h('div', { class: `bar${t.pct > 0 ? '' : ' wait'}` }, h('i', { style: `width:${t.pct > 0 ? t.pct : 30}%` })),
    t.state === 'running' && h('div', { class: 'job-meta' }, h('span', {}, t.stage || 'يبدأ…'), t.pct > 0 ? h('span', {}, pct(t.pct)) : null),
    t.state === 'queued' && h('div', { class: 'job-meta' }, h('span', {}, 'ينتظر انتهاء العملية الجارية')),
    sizes,
    t.state === 'done' && out && h('small', { class: 'muted ell', dir: 'auto' }, out.name),
    t.state === 'failed' && h('div', { class: 'job-err' }, h('b', {}, 'سبب الفشل'), h('small', {}, t.error || 'خطأ غير معروف')),
    h('div', { class: 'job-acts' },
      (t.state === 'queued' || t.state === 'running') && act('x', 'إلغاء', () => Media.cancel(t.id), 'danger'),
      t.state === 'done' && out && act('play', 'فتح', () => Media.open(out.uri, out.mime, out.name), 'primary'),
      t.state === 'done' && t.outputs.length && act('share', 'مشاركة', () => Media.share(t.outputs.map((o) => o.uri))),
      (t.state === 'failed' || t.state === 'canceled') && act('retry', 'إعادة المحاولة', () => Media.retry(t.id), 'primary'),
      !(t.state === 'queued' || t.state === 'running') && act('trash', 'إزالة', async () => { await Media.remove(t.id); forgetTask(t.id); })),
  );
}

// قسم «المعالجة» في صفحة التنزيلات
export function processSection(list) {
  if (!list.length) return null;
  const done = list.filter((t) => t.state !== 'queued' && t.state !== 'running').length;
  return h('div', { class: 'proc' },
    h('div', { class: 'card-h sec-row' }, h('h3', { class: 'sec-h flat' }, 'المعالجة'),
      h('span', { class: 'row-gap' },
        infoBtn('المعالجة', 'عمليات التحويل والضغط والقص والدمج تعمل واحدة بعد الأخرى في الخلفية حتى لو أغلقت التطبيق، رحمةً بالبطارية وحرارة الهاتف. الملف الأصلي لا يُعدَّل أبداً، والناتج ملف جديد في مجلد الإخراج.'),
        done ? h('button', { class: 'link', id: 'clear-tasks', onclick: async () => { await Media.clear(); await refreshTasks(); } }, 'مسح المنتهية') : null)),
    h('div', { class: 'jobs' }, list.map(taskCard)));
}

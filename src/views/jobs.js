// التنزيلات: الطابور الحالي مع التقدم، والفاشل والملغى مع إعادة المحاولة
import { h, ic, toast, fmtBytes, fmtDur } from '../ui.js';
import { Sayl } from '../native.js';
import { classifyError } from '../errors.js';
import { activeJobs, onJobs, refreshJobs, forget } from '../state.js';

const STATE = { queued: ['في الانتظار', ''], running: ['يُنزَّل', 'run'], failed: ['تعذّر', 'bad'], canceled: ['أُلغي', 'warn'] };

function jobCard(j) {
  const [label, cls] = STATE[j.state] || [j.state, ''];
  const color = j.kind === 'audio' ? 'c-violet' : 'c-sky';
  const act = (icon, text, fn, extra = '') => h('button', { class: `btn sm ${extra || 'ghost'}`, onclick: async () => { try { await fn(); await refreshJobs(); } catch (e) { toast(e.message, 'bad'); } } }, ic(icon), text);
  const running = j.state === 'running' && j.pct > 0;
  const meta = j.state === 'running'
    ? [running ? `${Math.floor(j.pct)}%` : 'يبدأ…', j.speed ? `${fmtBytes(j.speed)}/ث` : '', j.eta > 0 ? `متبقٍ ${fmtDur(j.eta)}` : ''].filter(Boolean)
    : j.state === 'queued' ? ['ينتظر دوره'] : [];
  const err = j.state === 'failed' && classifyError(j.error);
  return h('div', { class: `job ${color}`, 'data-id': j.id },
    h('div', { class: 'job-h' }, ic(j.kind === 'audio' ? 'audio' : 'video', 'job-ic'),
      h('div', { class: 'job-t' }, h('b', {}, j.title || j.url), h('small', {}, j.folder ? `📁 ${j.folder}` : j.url)),
      h('span', { class: `tag ${cls}` }, label)),
    (j.state === 'running' || j.state === 'queued') && h('div', { class: `bar${running ? '' : ' wait'}` }, h('i', { style: `width:${running ? j.pct : 30}%` })),
    meta.length ? h('div', { class: 'job-meta' }, meta.map((m) => h('span', {}, m))) : null,
    err && h('div', { class: 'job-err' }, h('b', {}, err.title), h('small', {}, err.fix)),
    h('div', { class: 'job-acts' },
      (j.state === 'running' || j.state === 'queued') && act('x', 'إلغاء', () => Sayl.cancel(j.id), 'danger'),
      (j.state === 'failed' || j.state === 'canceled') && act('retry', 'إعادة المحاولة', () => Sayl.retry(j.id), 'primary'),
      (j.state === 'failed' || j.state === 'canceled') && act('trash', 'حذف', async () => { await Sayl.remove(j.id); forget(j.id); })),
  );
}

export function jobsView({ page, go }) {
  const box = h('div', { class: 'jobs' });
  const head = h('div');
  const draw = () => {
    const list = activeJobs();
    const finished = list.filter((j) => j.state === 'failed' || j.state === 'canceled').length;
    head.replaceChildren(finished ? h('div', { class: 'card-h' }, h('span', { class: 'muted' }, `${list.length} مهمة`),
      h('button', { class: 'link', onclick: async () => { await Sayl.clearFinished(); await refreshJobs(); } }, 'مسح الفاشلة والملغاة')) : '');
    box.replaceChildren(...(list.length ? list.map(jobCard) : [h('div', { class: 'card empty' }, ic('download'),
      h('div', {}, 'لا توجد تنزيلات جارية.'), h('button', { class: 'btn primary', onclick: () => go('home') }, 'ابدأ تنزيلاً'))]));
  };
  page.append(h('div', { class: 'intro' }, h('h2', {}, 'التنزيلات'), h('p', {}, 'تستمر في الخلفية حتى لو أغلقت التطبيق، ويظهر تقدمها في الإشعارات.')), head, box);
  draw();
  return onJobs(draw);
}

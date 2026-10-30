// التنزيلات: الطابور الحالي مع التقدم، والفاشل والملغى مع إعادة المحاولة
import { h, ic, toast, fmtBytes, progressLine } from '../ui.js';
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
    ? [progressLine(j), running ? `${Math.floor(j.pct)}%` : '']
    : j.state === 'queued' ? ['ينتظر دوره'] : [];
  const err = j.state === 'failed' && classifyError(j.error);
  return h('div', { class: `job ${color}`, 'data-id': j.id },
    h('div', { class: 'job-h' }, ic(j.kind === 'audio' ? 'audio' : 'video', 'job-ic'),
      h('div', { class: 'job-t' }, h('b', {}, j.title || j.url), h('small', {}, j.folder ? `📁 ${j.folder}` : j.url)),
      h('span', { class: `tag ${cls}` }, label)),
    (j.state === 'running' || j.state === 'queued') && h('div', { class: `bar${running ? '' : ' wait'}` }, h('i', { style: `width:${running ? j.pct : 30}%` })),
    meta.length ? h('div', { class: 'job-meta' }, meta.filter(Boolean).map((m) => h('span', {}, m))) : null,
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
    const running = list.filter((j) => j.state === 'running');
    const waiting = list.length - running.length - finished;
    const speed = running.reduce((n, j) => n + (j.speed || 0), 0);
    head.replaceChildren(
      list.length ? h('div', { class: 'stats' },
        h('div', { class: 'stat c-sky' }, h('small', {}, 'السرعة الآن'), h('b', {}, speed ? `${fmtBytes(speed)}/ث` : '—')),
        h('div', { class: 'stat c-green' }, h('small', {}, 'يُنزَّل'), h('b', {}, String(running.length))),
        h('div', { class: 'stat c-amber' }, h('small', {}, 'في الطابور'), h('b', {}, String(Math.max(0, waiting))))) : '',
      finished ? h('div', { class: 'card-h' }, h('span', { class: 'muted' }, `${finished} فاشلة أو ملغاة`),
        h('button', { class: 'link', onclick: async () => { await Sayl.clearFinished(); await refreshJobs(); } }, 'مسحها')) : '');
    box.replaceChildren(...(list.length ? list.map(jobCard) : [h('div', { class: 'card empty' }, ic('download'),
      h('div', {}, 'لا توجد تنزيلات جارية.'), h('button', { class: 'btn primary', onclick: () => go('home') }, 'ابدأ تنزيلاً'))]));
  };
  page.append(h('div', { class: 'intro' }, h('h2', {}, 'التنزيلات'), h('p', {}, 'تستمر في الخلفية حتى لو أغلقت التطبيق، ويظهر تقدمها في الإشعارات.')), head, box);
  draw();
  return onJobs(draw);
}

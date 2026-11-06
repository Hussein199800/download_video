// التنزيلات: الطابور الحالي مع التقدم، والفاشل والملغى مع إعادة المحاولة
import { h, ic, toast, fmtBytes, progressLine, pct, explain } from '../ui.js';
import { count, W } from '../text.js';
import { allTasks, onTasks, startTasks } from '../tasks.js';
import { processSection } from './process.js';
import { Sayl } from '../native.js';
import { classifyError } from '../errors.js';
import { activeJobs, onJobs, refreshJobs, forget } from '../state.js';
import { showDetails } from './details.js';
import { scheduleLabel, retryLabel } from '../schedule.js';
import { getSettings } from '../store.js';

const STATE = {
  queued: ['في الانتظار', ''], running: ['يُنزَّل', 'run'], paused: ['موقوف', 'warn'], waiting_network: ['بانتظار الشبكة', 'warn'],
  retrying: ['سيُعاد', 'warn'], failed: ['تعذّر', 'bad'], canceled: ['أُلغي', 'warn'],
};

function jobCard(j) {
  const [label, cls] = STATE[j.state] || [j.state, ''];
  const color = j.kind === 'audio' ? 'c-violet' : 'c-sky';
  const act = (icon, text, fn, extra = '', label) => h('button', { class: `btn sm ${extra || 'ghost'}`, 'aria-label': label || text, 'data-act': label || text,
    onclick: async () => { try { await fn(); await refreshJobs(); } catch (e) { toast(e.message, 'bad'); } } }, ic(icon), text);
  const pending = ['queued', 'waiting_network', 'retrying'].includes(j.state);
  const running = j.state === 'running' && j.pct > 0;
  const meta = j.state === 'running'
    ? [progressLine(j), running ? pct(Math.floor(j.pct)) : '']
    : j.state === 'queued' ? [scheduleLabel(j) || 'ينتظر دوره']
      : j.state === 'retrying' ? [retryLabel(j, Date.now(), getSettings().retries)]
      : j.state === 'waiting_network' ? ['ينتظر عودة الإنترنت ثم يكمل وحده']
      : j.state === 'paused' ? [j.downloaded ? `${progressLine(j)} · موقوف` : 'موقوف قبل البدء'] : [];
  const err = j.state === 'failed' && classifyError(j.error);
  return h('div', { class: `job ${color}`, 'data-id': j.id },
    h('div', { class: 'job-h' }, ic(j.kind === 'audio' ? 'audio' : 'video', 'job-ic'),
      h('button', { class: 'job-t', onclick: () => showDetails(j) }, h('b', { dir: j.title && j.title !== j.url ? 'auto' : 'ltr' }, j.title || j.url),
        h('small', { dir: j.folder ? 'auto' : 'ltr' }, [j.engine === 2 ? 'سيرفر 2' : '', j.folder ? `📁 ${j.folder}` : String(j.url).replace(/^https?:\/\/(www\.)?/, '')].filter(Boolean).join(' · '))),
      h('span', { class: `tag ${cls}` }, label)),
    (j.state === 'running' || pending) && h('div', { class: `bar${running ? '' : ' wait'}` }, h('i', { style: `width:${running ? j.pct : 30}%` })),
    j.state === 'paused' && j.pct > 0 && h('div', { class: 'bar paused' }, h('i', { style: `width:${j.pct}%` })),
    meta.length ? h('div', { class: 'job-meta' }, meta.filter(Boolean).map((m) => h('span', {}, m))) : null,
    err && h('div', { class: 'job-err' }, h('b', {}, err.title), h('small', {}, err.fix)),
    h('div', { class: 'job-acts' },
      (j.state === 'running' || pending) && act('pause', 'إيقاف', () => Sayl.pause(j.id)),
      j.state === 'paused' && act('play', 'استكمال', () => Sayl.resume(j.id), 'primary'),
      (j.scheduledAt > Date.now() || j.wifiOnly || j.state === 'retrying' || j.state === 'waiting_network') && act('play', 'ابدأ الآن', () => Sayl.startNow(j.id), 'primary'),
      pending && act('top', '', () => Sayl.toTop(j.id), '', 'إلى رأس الطابور'),
      pending && act('up', '', () => Sayl.move(j.id, -1), '', 'للأعلى'),
      pending && act('down', '', () => Sayl.move(j.id, 1), '', 'للأسفل'),
      (j.state === 'running' || pending || j.state === 'paused') && act('x', 'إلغاء', () => Sayl.cancel(j.id), 'danger'),
      (j.state === 'failed' || j.state === 'canceled') && act('retry', 'إعادة المحاولة', () => Sayl.retry(j.id), 'primary'),
      (j.state === 'failed' || j.state === 'canceled') && act('trash', 'حذف', async () => { await Sayl.remove(j.id); forget(j.id); })),
  );
}

export function jobsView({ page, go }) {
  const box = h('div', { class: 'jobs' });
  const head = h('div');
  const proc = h('div');
  const drawProc = () => proc.replaceChildren(...[processSection(allTasks())].filter(Boolean));
  const draw = () => {
    const list = activeJobs();
    const finished = list.filter((j) => j.state === 'failed' || j.state === 'canceled').length;
    const running = list.filter((j) => j.state === 'running');
    const waiting = list.filter((j) => ['queued', 'waiting_network', 'retrying'].includes(j.state)).length;
    const speed = running.reduce((n, j) => n + (j.speed || 0), 0);
    head.replaceChildren(
      list.length ? h('div', { class: 'stats' },
        h('button', { class: 'stat c-sky', type: 'button', id: 'stat-speed', onclick: () => explain('السرعة الآن', 'مجموع سرعات التنزيلات الجارية في هذه اللحظة، كما يقيسها مكوّن التنزيل كل نصف ثانية. تتغير مع الشبكة وعدد التنزيلات المتزامنة.') },
          h('small', {}, 'السرعة الآن', ic('info')), h('b', {}, speed ? `${fmtBytes(speed)}/ث` : '—')),
        h('button', { class: 'stat c-green', type: 'button', onclick: () => explain('يُنزَّل', 'عدد الملفات التي تُنزَّل الآن. الحد الأقصى من الإعدادات ← التنزيلات المتزامنة.') },
          h('small', {}, 'يُنزَّل', ic('info')), h('b', {}, String(running.length))),
        h('button', { class: 'stat c-amber', type: 'button', onclick: () => explain('في الطابور', 'الملفات التي تنتظر دورها، ومنها المجدول والمنتظر للواي فاي أو لعودة الإنترنت. تبدأ وحدها عند فراغ مكان.') },
          h('small', {}, 'في الطابور', ic('info')), h('b', {}, String(Math.max(0, waiting))))) : '',
      finished ? h('div', { class: 'card-h' }, h('span', { class: 'muted' }, count(finished, ['تنزيل واحد فاشل أو ملغى', 'تنزيلان فاشلان أو ملغيان', 'تنزيلات فاشلة أو ملغاة', 'تنزيلاً فاشلاً أو ملغى'])),
        h('button', { class: 'link', onclick: async () => { await Sayl.clearFinished(); await refreshJobs(); } }, 'مسحها')) : '');
    box.replaceChildren(...(list.length ? list.map(jobCard) : [h('div', { class: 'card empty' }, ic('download'),
      h('div', {}, 'لا توجد تنزيلات جارية.'), h('button', { class: 'btn primary', onclick: () => go('home') }, 'ابدأ تنزيلاً'))]));
  };
  page.append(h('div', { class: 'intro' }, h('h2', {}, 'التنزيلات'), h('p', {}, 'تستمر في الخلفية حتى لو أغلقت التطبيق، ويظهر تقدمها في الإشعارات.')), proc, head, box);
  draw();
  drawProc();
  startTasks().catch(() => {});
  const a = onJobs(draw), b = onTasks(drawProc);
  return () => { a(); b(); };
}

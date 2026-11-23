// التنزيلات: الطابور الحالي مع التقدم، والفاشل والملغى مع إعادة المحاولة
import { h, ic, toast, fmtBytes, progressLine, pct, explain } from '../ui.js';
import { count, W } from '../text.js';
import { allTasks, onTasks, startTasks } from '../tasks.js';
import { processSection } from './process.js';
import { Sayl } from '../native.js';
import { classifyError } from '../errors.js';
import { activeJobs, allJobs, onJobs, refreshJobs, forget } from '../state.js';
import { showDetails } from './details.js';
import { scheduleLabel, retryLabel } from '../schedule.js';
import { getSettings } from '../store.js';
import { SERVERS, serversFor, serverOf } from '../servers.js';

// سيرفرات أخرى تدعم رابط المهمة غير الذي فشل (صور تيك توك لها طريقها الخاص)
const otherServers = (j) => (serverOf(j.engine) ? serversFor(j.url).filter((n) => n !== serverOf(j.engine)) : []);

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
      h('button', { class: 'job-t', onclick: () => showDetails(j) }, h('b', { class: 'more', dir: j.title && j.title !== j.url ? 'auto' : 'ltr' }, j.title || j.url),
        h('small', { dir: j.folder ? 'auto' : 'ltr' }, [j.engine === 2 || j.engine === 4 ? SERVERS[serverOf(j.engine)].name : '', j.folder ? `📁 ${j.folder}` : String(j.url).replace(/^https?:\/\/(www\.)?/, '')].filter(Boolean).join(' · '))),
      h('span', { class: `tag ${cls}` }, label)),
    (j.state === 'running' || pending) && h('div', { class: `bar${running ? '' : ' wait'}` }, h('i', { style: `width:${running ? j.pct : 30}%` })),
    j.state === 'paused' && j.pct > 0 && h('div', { class: 'bar paused' }, h('i', { style: `width:${j.pct}%` })),
    meta.length ? h('div', { class: 'job-meta' }, meta.filter(Boolean).map((m) => h('span', {}, m))) : null,
    err && h('div', { class: 'job-err' }, h('b', {}, err.title), h('small', { class: 'more', style: '--lines:3' }, err.fix)),
    h('div', { class: 'job-acts' },
      (j.state === 'running' || pending) && act('pause', 'إيقاف', () => Sayl.pause(j.id)),
      j.state === 'paused' && act('play', 'استكمال', () => Sayl.resume(j.id), 'primary'),
      (j.scheduledAt > Date.now() || j.wifiOnly || j.state === 'retrying' || j.state === 'waiting_network') && act('play', 'ابدأ الآن', () => Sayl.startNow(j.id), 'primary'),
      pending && act('top', '', () => Sayl.toTop(j.id), '', 'إلى رأس الطابور'),
      pending && act('up', '', () => Sayl.move(j.id, -1), '', 'للأعلى'),
      pending && act('down', '', () => Sayl.move(j.id, 1), '', 'للأسفل'),
      (j.state === 'running' || pending || j.state === 'paused') && act('x', 'إلغاء', () => Sayl.cancel(j.id), 'danger'),
      (j.state === 'failed' || j.state === 'canceled') && act('retry', 'إعادة المحاولة', () => Sayl.retry(j.id), 'primary'),
      // جرّب سيرفراً آخر يدعم الرابط
      ...(j.state === 'failed' ? otherServers(j).map((n) => act('servers', `جرّب ${SERVERS[n].name}`, () => Sayl.retry(j.id, n))) : []),
      (j.state === 'failed' || j.state === 'canceled') && act('trash', 'حذف', async () => { await Sayl.remove(j.id); forget(j.id); })),
  );
}

// ألبوم في الطابور: بطاقة واحدة لكل صور المنشور بدل بطاقة لكل صورة
function albumCard(g, members) {
  const total = members.length;
  const done = members.filter((j) => j.state === 'done').length;
  const failed = members.filter((j) => j.state === 'failed' || j.state === 'canceled');
  const active = members.filter((j) => !['done', 'failed', 'canceled'].includes(j.state));
  const running = members.filter((j) => j.state === 'running');
  const pctNow = ((done + running.reduce((n, j) => n + (j.pct || 0) / 100, 0)) / total) * 100;
  const j0 = members[0];
  const act = (icon, text, fn, extra = 'ghost') => h('button', { class: `btn sm ${extra}`, 'data-act': text,
    onclick: async () => { try { await fn(); await refreshJobs(); } catch (e) { toast(e.message, 'bad'); } } }, ic(icon), text);
  const tag = active.length ? ['يُنزَّل', 'run'] : failed.length ? ['تعذّر بعضها', 'bad'] : ['اكتمل', 'ok'];
  return h('div', { class: 'job c-amber', 'data-group': g },
    h('div', { class: 'job-h' }, ic('image', 'job-ic'),
      h('div', { class: 'job-t' }, h('b', { class: 'more', dir: 'auto' }, j0.title), h('small', {}, `ألبوم${j0.author ? ` · @${j0.author}` : ''}`)),
      h('span', { class: `tag ${tag[1]}` }, tag[0])),
    active.length ? h('div', { class: 'bar' }, h('i', { style: `width:${pctNow}%` })) : null,
    h('div', { class: 'job-meta' }, h('span', {}, `حُفظت ${done} من ${count(total, W.file)}`), failed.length ? h('span', { class: 'err-text' }, `تعذّرت ${count(failed.length, W.file)}`) : null),
    h('div', { class: 'job-acts' },
      failed.length && act('retry', 'إعادة المتعثرة', () => Promise.all(failed.map((j) => Sayl.retry(j.id))), 'primary'),
      active.length && act('x', 'إلغاء الباقي', () => Promise.all(active.map((j) => Sayl.cancel(j.id))), 'danger'),
      !active.length && failed.length && act('trash', 'إزالة المتعثرة', async () => { for (const j of failed) { await Sayl.remove(j.id); forget(j.id); } })));
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
    // صور الألبوم الواحد في بطاقة واحدة مكان أول صورة منه
    const members = new Map();
    for (const j of allJobs()) if (j.group) members.set(j.group, [...(members.get(j.group) || []), j]);
    const seen = new Set();
    const cards = list.map((j) => {
      if (!j.group) return jobCard(j);
      if (seen.has(j.group)) return null;
      seen.add(j.group);
      return albumCard(j.group, members.get(j.group).sort((a, b) => String(a.url).localeCompare(String(b.url))));
    }).filter(Boolean);
    box.replaceChildren(...(cards.length ? cards : [h('div', { class: 'card empty' }, ic('download'),
      h('div', {}, 'لا توجد تنزيلات جارية.'), h('button', { class: 'btn primary', onclick: () => go('home') }, 'ابدأ تنزيلاً'))]));
  };
  page.append(h('div', { class: 'intro' }, h('h2', {}, 'التنزيلات'), h('p', {}, 'تستمر في الخلفية حتى لو أغلقت التطبيق، ويظهر تقدمها في الإشعارات.')), proc, head, box);
  draw();
  drawProc();
  startTasks().catch(() => {});
  const a = onJobs(draw), b = onTasks(drawProc);
  return () => { a(); b(); };
}

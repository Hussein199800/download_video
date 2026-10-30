// تفاصيل تنزيل واحد: المصدر، والسيرفر، والحجم، ومتوسط السرعة، والأوقات، والمسار، وسبب الفشل
import { h, sheet, fmtBytes, fmtDate } from '../ui.js';
import { classifyError } from '../errors.js';
import { siteOf } from '../links.js';

const STATE = { queued: 'في الانتظار', running: 'يُنزَّل', paused: 'موقوف مؤقتاً', waiting_network: 'بانتظار الشبكة', retrying: 'سيُعاد', done: 'اكتمل', failed: 'تعذّر', canceled: 'أُلغي' };

export function avgSpeed(j) {
  const end = j.finishedAt || Date.now();
  const secs = j.startedAt ? (end - j.startedAt) / 1000 : 0;
  return secs > 0 && j.downloaded > 0 ? j.downloaded / secs : 0;
}

export function showDetails(j) {
  const rows = [
    ['الحالة', STATE[j.state] || j.state],
    ['الموقع', siteOf(j.url).name],
    ['السيرفر', j.engine === 2 ? 'سيرفر 2 (NewPipe)' : 'سيرفر 1 (yt-dlp)'],
    ['الحجم', j.total ? fmtBytes(j.total) : j.downloaded ? fmtBytes(j.downloaded) : '—'],
    ['متوسط السرعة', avgSpeed(j) ? `${fmtBytes(avgSpeed(j))}/ث` : '—'],
    ['المحاولات', String(j.attempts || 0)],
    ['أُضيف', fmtDate(j.createdAt)],
    ['بدأ', fmtDate(j.startedAt) || '—'],
    ['انتهى', fmtDate(j.finishedAt) || '—'],
    j.path && ['المسار', j.path],
  ].filter(Boolean);
  const err = j.error && j.state !== 'done' && classifyError(j.error);
  const close = sheet(
    h('h3', {}, j.title || j.url),
    h('dl', { class: 'kv' }, rows.flatMap(([k, v]) => [h('dt', {}, k), h('dd', {}, v)])),
    err && h('div', { class: 'job-err' }, h('b', {}, err.title), h('small', {}, err.fix)),
    h('div', { class: 'log' }, j.url),
    h('button', { class: 'btn ghost wide', onclick: () => close() }, 'إغلاق'));
}

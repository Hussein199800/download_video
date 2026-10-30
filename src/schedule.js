// الجدولة في الواجهة: حساب الوقت المختار وعناوين المهام المجدولة والمؤجلة
import { fmtDur } from './ui.js';

const pad = (n) => String(n).padStart(2, '0');

// «02:00» → أقرب موعد قادم بهذا الوقت (اليوم أو غداً)
export function nextAt(hhmm, now = Date.now()) {
  const [h, m] = hhmm.split(':').map(Number);
  const d = new Date(now);
  d.setHours(h, m, 0, 0);
  if (d.getTime() <= now) d.setDate(d.getDate() + 1);
  return d.getTime();
}

function dayWord(t, now) {
  const a = new Date(now); a.setHours(0, 0, 0, 0);
  const days = Math.round((new Date(t).setHours(0, 0, 0, 0) - a.getTime()) / 864e5);
  return days === 0 ? 'اليوم' : days === 1 ? 'غداً' : new Date(t).toLocaleDateString('ar', { weekday: 'long' });
}

export function scheduleLabel(j, now = Date.now()) {
  const parts = [];
  if (j.scheduledAt > now) {
    const d = new Date(j.scheduledAt);
    parts.push(`مجدول: ${dayWord(j.scheduledAt, now)} ${pad(d.getHours())}:${pad(d.getMinutes())}`);
  }
  if (j.wifiOnly) parts.push('واي فاي فقط');
  return parts.join(' · ');
}

export function retryLabel(j, now, max) {
  const left = Math.max(0, Math.round((j.retryAt - now) / 1000));
  return `المحاولة ${j.attempts} من ${max} ${left ? `بعد ${fmtDur(left)}` : 'الآن'}`;
}

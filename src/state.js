// حالة الطابور في الواجهة: نسخة من طابور Kotlin تُحدَّث بالأحداث
import { Sayl } from './native.js';

const jobs = new Map();
const subs = new Set();
let pending = false;

function notify() {
  // تجميع التحديثات المتقاربة في رسم واحد
  if (pending) return;
  pending = true;
  requestAnimationFrame(() => { pending = false; subs.forEach((cb) => cb()); });
}

export async function refreshJobs() {
  const list = await Sayl.list();
  jobs.clear();
  list.forEach((j) => jobs.set(j.id, j));
  notify();
}

export function startSync() {
  Sayl.onProgress((j) => { jobs.set(j.id, { ...jobs.get(j.id), ...j }); notify(); });
  return refreshJobs();
}

export const allJobs = () => [...jobs.values()].sort((a, b) => b.createdAt - a.createdAt);
export const activeJobs = () => allJobs().filter((j) => j.state !== 'done').sort((a, b) => a.createdAt - b.createdAt);
export const doneJobs = () => allJobs().filter((j) => j.state === 'done').sort((a, b) => (b.finishedAt || 0) - (a.finishedAt || 0));
export const forget = (id) => { jobs.delete(id); notify(); };

export function onJobs(cb) {
  subs.add(cb);
  return () => subs.delete(cb);
}

// سجل التشخيص: آخر 50 خطأ في الجهاز فقط
const LOG = 'sayl.log';
export function logError(where, message) {
  let list = [];
  try { list = JSON.parse(localStorage.getItem(LOG) || '[]'); } catch {}
  list.unshift({ at: Date.now(), where, message: String(message).slice(0, 2000) });
  localStorage.setItem(LOG, JSON.stringify(list.slice(0, 50)));
}
export const readLog = () => { try { return JSON.parse(localStorage.getItem(LOG) || '[]'); } catch { return []; } };
export const clearLog = () => localStorage.removeItem(LOG);

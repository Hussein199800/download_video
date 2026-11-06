// طابور المعالجة في الواجهة: نسخة من TaskStore في Kotlin تُحدَّث بالأحداث
import { Media } from './native.js';

const tasks = new Map();
const subs = new Set();
let pending = false;
let started = false;

function notify() {
  if (pending) return;
  pending = true;
  requestAnimationFrame(() => { pending = false; subs.forEach((cb) => cb()); });
}

export async function refreshTasks() {
  const list = await Media.tasks();
  tasks.clear();
  list.forEach((t) => tasks.set(t.id, t));
  notify();
}

export function startTasks() {
  if (started) return Promise.resolve();
  started = true;
  Media.onTask((t) => { tasks.set(t.id, { ...tasks.get(t.id), ...t }); notify(); });
  return refreshTasks();
}

export const allTasks = () => [...tasks.values()].sort((a, b) => b.createdAt - a.createdAt);
export const activeTasks = () => allTasks().filter((t) => t.state === 'queued' || t.state === 'running');
export const forgetTask = (id) => { tasks.delete(id); notify(); };

export function onTasks(cb) {
  subs.add(cb);
  return () => subs.delete(cb);
}

let seq = 0;
export const taskId = () => `t${Date.now().toString(36)}${(seq++).toString(36)}${Math.random().toString(36).slice(2, 6)}`;

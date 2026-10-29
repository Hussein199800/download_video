import './styles.css';
import { h, ic, $ } from './ui.js';
import { getSettings } from './store.js';
import { applyTheme } from './theme.js';
import { startSync, onJobs, activeJobs } from './state.js';
import { Sayl, isNative } from './native.js';
import { homeView, handleSharedText } from './views/home.js';
import { jobsView } from './views/jobs.js';
import { historyView } from './views/history.js';
import { guideView } from './views/guide.js';
import { moreView, autoChecks } from './views/more.js';

// [المعرّف، الاسم، الأيقونة، لون التبويب]
const TABS = [
  ['home', 'الرئيسية', 'home', 'c-indigo'],
  ['jobs', 'التنزيلات', 'download', 'c-sky'],
  ['history', 'السجل', 'history', 'c-green'],
  ['guide', 'الشرح', 'book', 'c-amber'],
  ['more', 'المزيد', 'more', 'c-violet'],
];
const VIEWS = { home: homeView, jobs: jobsView, history: historyView, guide: guideView, more: moreView };

let tab = 'home';
let stack = [];   // الصفحات الفرعية فوق التبويب الحالي
let cleanup = null;

function render() {
  cleanup?.();
  cleanup = null;
  const top = stack[stack.length - 1];
  const view = $('#view');
  view.replaceChildren();
  const page = h('div', { class: 'page-anim' });
  view.append(page);
  const ctx = { go, open, back, page };
  const res = top ? top.render(ctx) : VIEWS[tab](ctx);
  cleanup = typeof res === 'function' ? res : null;
  $('.topbar').replaceChildren(...topbar(top));
  document.querySelectorAll('.tabbar button').forEach((b) => b.classList.toggle('on', b.dataset.tab === tab));
  window.scrollTo(0, 0);
}

function topbar(top) {
  if (top) return [h('button', { class: 'icon-btn', 'aria-label': 'رجوع', onclick: back, html: ic('chevR').innerHTML }), h('div', { class: 'page-title' }, top.title)];
  return [h('div', { class: 'brand' }, ic('logo', 'brand-ic'), h('div', {}, h('b', {}, 'سَيْل'), h('small', {}, 'نزّل من كل المواقع')))];
}

export function go(t) {
  tab = t;
  stack = [];
  render();
}

// صفحة فرعية: { title, render(ctx) }
export function open(sub) {
  stack.push(sub);
  render();
}

export function back() {
  if (stack.length) { stack.pop(); render(); return true; }
  if (tab !== 'home') { go('home'); return true; }
  return false;
}

function badge() {
  const n = activeJobs().filter((j) => j.state === 'queued' || j.state === 'running').length;
  const b = document.querySelector('.tabbar [data-tab="jobs"] .badge');
  b.textContent = n || '';
  b.hidden = !n;
}

async function boot() {
  applyTheme();
  matchMedia('(prefers-color-scheme: dark)').addEventListener?.('change', applyTheme);
  const app = $('#app');
  app.replaceChildren(
    h('header', { class: 'topbar' }),
    h('main', { id: 'view' }),
    h('nav', { class: 'tabbar', 'aria-label': 'التبويبات' }, TABS.map(([id, label, icon, color]) =>
      h('button', { 'data-tab': id, class: color, onclick: () => go(id) }, ic(icon), label, id === 'jobs' && h('i', { class: 'badge', hidden: true })))),
  );
  render();
  await startSync().catch(() => {});
  onJobs(badge);
  badge();
  Sayl.setConfig({ parallel: getSettings().parallel }).catch(() => {});
  // المشاركة من تطبيق آخر: عند الفتح وأثناء العمل
  Sayl.takeSharedText().then((t) => t && handleSharedText(t, { go, open })).catch(() => {});
  Sayl.onShared((t) => handleSharedText(t, { go, open }));
  if (isNative) {
    const { App } = await import('@capacitor/app');
    App.addListener('backButton', () => { if (!back()) App.exitApp(); });
    App.addListener('resume', () => { if (tab === 'home' && !stack.length) render(); });
  }
  autoChecks().catch(() => {});
}

boot();

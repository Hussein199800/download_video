import './styles.css';
import { h, ic, $ } from './ui.js';
import { getSettings, migrate } from './store.js';
import { applyTheme } from './theme.js';
import { watchLayout } from './layout.js';
import { suggestClip, hideBubble } from './clip.js';
import { startSync, onJobs, activeJobs } from './state.js';
import { Sayl, isNative } from './native.js';
import { homeView, handleSharedText } from './views/home.js';
import { jobsView } from './views/jobs.js';
import { historyView } from './views/history.js';
import { guideView } from './views/guide.js';
import { moreView, autoChecks, syncNative } from './views/more.js';

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
  migrate();
  applyTheme();
  watchLayout();
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
  syncNative();
  // المشاركة من تطبيق آخر، أو «نزّل المنسوخ» من البلاطة والاختصار: عند الفتح وأثناء العمل
  const openUrl = (t) => { hideBubble(); handleSharedText(t, { go, open }); };
  const pasteNow = () => suggestClip(openUrl, { direct: true });
  const launch = await Sayl.takeLaunch().catch(() => ({}));
  if (launch.text) openUrl(launch.text);
  else if (launch.paste) pasteNow();
  else setTimeout(() => suggestClip(openUrl), 500);
  Sayl.onShared(openUrl);
  Sayl.onPasteRequest(pasteNow);
  // العودة إلى التطبيق: اقتراح الرابط المنسوخ دون إعادة رسم الصفحة (يبقى ما كُتب كما هو)
  window.addEventListener('focus', () => setTimeout(() => suggestClip(openUrl), 250));
  if (isNative) {
    const { App } = await import('@capacitor/app');
    App.addListener('backButton', () => { if (document.querySelector('.modal')) { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); return; } if (!back()) App.exitApp(); });
    App.addListener('resume', () => setTimeout(() => suggestClip(openUrl), 400));
  }
  autoChecks().catch(() => {});
}

boot();

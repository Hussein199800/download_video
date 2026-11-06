// فقاعة الرابط المنسوخ: عند فتح التطبيق أو العودة إليه، إن كان في الحافظة رابط جديد
// تظهر فقاعة صغيرة فوق الشريط السفلي في أي صفحة، وتختفي وحدها بعد ثوانٍ.
// أندرويد 10+ لا يسمح بقراءة الحافظة إلا والتطبيق في المقدمة، لذا لا تظهر خارجه؛
// والبديل لذلك بلاطة «نزّل المنسوخ» في الإعدادات السريعة واختصار أيقونة التطبيق.
import { h, ic, toast, urlText } from './ui.js';
import { extractUrl, siteOf } from './links.js';
import { getSettings } from './store.js';
import { activeJobs } from './state.js';
import { Clip } from './native.js';

const SEEN = 'sayl.lastClip';
const LIFE = 12000;
let bubble = null;
let timer = 0;
let busy = false;

const seen = () => { try { return localStorage.getItem(SEEN) || ''; } catch { return ''; } };
const markSeen = (url) => { try { localStorage.setItem(SEEN, url); } catch {} };

export function hideBubble() {
  clearTimeout(timer);
  const b = bubble;
  bubble = null;
  if (!b) return;
  b.classList.add('out');
  setTimeout(() => b.remove(), 200);
}

function showBubble(url, onOpen) {
  hideBubble();
  const site = siteOf(url);
  const go = () => { hideBubble(); onOpen(url); };
  bubble = h('div', { class: 'clip-bubble', id: 'clip-bubble', role: 'status', 'aria-live': 'polite' },
    ic('paste', 'cb-ic'),
    h('button', { class: 'cb-txt', onclick: go },
      h('b', {}, site.id === 'other' ? 'رابط منسوخ' : `رابط من ${site.name}`), urlText(url, 'cb-url')),
    h('button', { class: 'btn primary sm', id: 'clip-go', onclick: go }, ic('download'), 'نزّل'),
    h('button', { class: 'cb-x', 'aria-label': 'إخفاء', onclick: hideBubble, html: ic('x').innerHTML }));
  document.body.append(bubble);
  timer = setTimeout(hideBubble, LIFE);
}

// الحافظة لا تُقرأ قبل أن تأخذ النافذة التركيز، فنعيد المحاولة قليلاً
async function readUrl(tries = 3) {
  for (let i = 0; i < tries; i++) {
    try {
      const url = extractUrl(await Clip.read());
      if (url) return url;
    } catch {}
    if (i < tries - 1) await new Promise((r) => setTimeout(r, 350));
  }
  return null;
}

/**
 * اقتراح الرابط المنسوخ مرة واحدة لكل رابط.
 * direct: طلب صريح من البلاطة أو الاختصار؛ يفتح الرابط فوراً بلا فقاعة، ويُعلم إن لم يوجد رابط.
 */
export async function suggestClip(onOpen, { direct = false } = {}) {
  if (busy) return;
  if (!direct && !getSettings().clipboard) return;
  busy = true;
  try {
    const url = await readUrl(direct ? 4 : 2);
    if (!url) { if (direct) toast('لا يوجد رابط في الحافظة. انسخ رابط الفيديو أولاً', 'bad'); return; }
    markSeen(url);
    if (direct) { hideBubble(); onOpen(url); return; }
    if (url === seen.last || activeJobs().some((j) => j.url === url)) return;
    seen.last = url;
    showBubble(url, onOpen);
  } finally {
    busy = false;
  }
}

// عند الإقلاع نتذكر آخر رابط اقتُرح حتى لا يتكرر الاقتراح نفسه بعد كل فتح للتطبيق
seen.last = seen();

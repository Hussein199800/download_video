// الرئيسية: حقل الرابط، والمعاينة، وبطاقات الأنواع، وآخر التنزيلات
import { h, ic, toast, gcard, lcard, fmtDur } from '../ui.js';
import { extractUrl, siteOf } from '../links.js';
import { parseInfo } from '../formats.js';
import { classifyError } from '../errors.js';
import { Sayl, isNative } from '../native.js';
import { getSettings } from '../store.js';
import { doneJobs, activeJobs, refreshJobs, logError } from '../state.js';
import { choicePicker, uid } from './choice.js';
import { whenPicker } from './when.js';
import { openPlaylist } from './playlist.js';
import { engineUpdateFlow, cookiesFlow } from './more.js';

let pendingShared = null;
let preferType = 'video';
let lastClip = null;

export function handleSharedText(text, ctx) {
  pendingShared = text;
  ctx.go('home');
}

export async function enqueueJobs(jobs) {
  try {
    const r = await Sayl.enqueue(jobs);
    await refreshJobs();
    if (!r.added) toast('موجود في الطابور', 'bad');
    else if (jobs[0]?.scheduledAt > 0) toast(jobs.length > 1 ? `جُدول ${r.added}` : 'جُدول التنزيل', 'ok');
    else toast(r.skipped ? `أُضيف ${r.added}، وتُرك ${r.skipped} موجود مسبقاً` : jobs.length > 1 ? `أُضيف ${r.added} إلى الطابور` : 'أُضيف إلى الطابور', 'ok');
    return r;
  } catch (e) {
    logError('enqueue', e.message);
    toast(e.message || 'تعذّرت الإضافة', 'bad');
    return null;
  }
}

export function errorCard(err, { onRetry } = {}) {
  const c = classifyError(err?.message || err);
  const act = {
    update: ['حدّث مكوّن التنزيل', () => engineUpdateFlow()],
    cookies: ['استيراد ملف cookies', () => cookiesFlow()],
    retry: ['أعد المحاولة', onRetry],
  }[c.action];
  return h('div', { class: 'card err-card' },
    h('div', { class: 'card-h' }, ic('alert', 'l-ic c-pink'), h('h2', {}, c.title)),
    h('p', {}, c.fix),
    act && act[1] && h('button', { class: 'btn grad c-pink', 'data-act': c.action, onclick: act[1] }, act[0]));
}

function preview(info, url) {
  const site = siteOf(url);
  const picker = choicePicker({ heights: info.heights, type: preferType });
  const when = whenPicker();
  const btn = h('button', { id: 'dl', class: 'btn primary wide' }, ic('download'), 'نزّل');
  btn.onclick = async () => {
    btn.disabled = true;
    await enqueueJobs([{ id: uid(), url: info.url || url, title: info.title, kind: picker.kind(), args: picker.args(), choice: picker.choice(),
      thumb: info.thumbnail || '', site: site.id, ...when.get() }]);
    btn.disabled = false;
  };
  return h('div', { class: 'card preview' },
    h('div', { class: 'pv-head' },
      info.thumbnail ? h('img', { class: 'thumb', src: info.thumbnail, alt: '', referrerpolicy: 'no-referrer', onerror: (e) => e.target.replaceWith(ic('video', 'thumb ph')) }) : ic('video', 'thumb ph'),
      h('div', { class: 'pv-txt' }, h('b', {}, info.title),
        h('small', {}, [site.name, info.uploader, fmtDur(info.duration)].filter(Boolean).join(' · ')))),
    picker.el, when.el, btn);
}

export function homeView(ctx) {
  const { page } = ctx;
  const input = h('input', { id: 'url', type: 'url', inputmode: 'url', placeholder: 'الصق رابط الفيديو أو قائمة التشغيل', autocomplete: 'off', enterkeyhint: 'go' });
  const result = h('div', { class: 'result' });
  const clip = h('div');

  async function analyze() {
    const url = extractUrl(input.value);
    if (!url) { toast('الصق رابطاً صحيحاً لصفحة الفيديو', 'bad'); return; }
    input.value = url;
    result.replaceChildren(h('div', { class: 'card center' }, h('div', { class: 'spinner' }), h('div', { class: 'muted' }, 'جارٍ قراءة الرابط…')));
    try {
      const info = parseInfo(await Sayl.info(url));
      if (info.kind === 'playlist') { result.replaceChildren(); openPlaylist(ctx, info, url); return; }
      result.replaceChildren(preview(info, url));
    } catch (e) {
      logError('info', e.message);
      result.replaceChildren(errorCard(e, { onRetry: analyze }));
    }
  }

  const go = h('button', { id: 'go', class: 'btn primary', onclick: analyze }, 'اقرأ');
  input.addEventListener('keydown', (e) => e.key === 'Enter' && analyze());

  async function paste() {
    try {
      const { Clipboard } = await import('@capacitor/clipboard');
      const { value } = await Clipboard.read();
      if (!extractUrl(value)) return toast('لا يوجد رابط في الحافظة', 'bad');
      input.value = value;
      analyze();
    } catch { toast('تعذّر قراءة الحافظة، الصق يدوياً', 'bad'); }
  }

  // اقتراح الرابط المنسوخ مرة واحدة لكل رابط
  async function suggestClipboard() {
    if (!isNative || !getSettings().clipboard) return;
    try {
      const { Clipboard } = await import('@capacitor/clipboard');
      const url = extractUrl((await Clipboard.read()).value);
      if (!url || url === lastClip || activeJobs().some((j) => j.url === url)) return;
      lastClip = url;
      clip.replaceChildren(lcard({ color: 'c-indigo', icon: 'paste', title: 'رابط منسوخ', sub: url,
        onClick: () => { input.value = url; clip.replaceChildren(); analyze(); } }));
    } catch {}
  }

  const pick = (type, hint) => () => {
    preferType = type;
    input.focus();
    toast(hint);
  };

  const recent = doneJobs().slice(0, 3);
  // page.append يكتب null نصاً، فنمرر العناصر الموجودة فقط
  page.append(...[
    h('div', { class: 'intro' }, h('h2', {}, 'ماذا تريد أن تنزّل؟'), h('p', {}, 'الصق رابطاً من يوتيوب أو فيسبوك أو تيك توك أو إنستغرام أو أي موقع، أو شارك الرابط من التطبيق نفسه إلى سَيْل.')),
    h('div', { class: 'card url-card' },
      h('div', { class: 'url-row' }, input, isNative && h('button', { class: 'icon-btn', 'aria-label': 'لصق', onclick: paste, html: ic('paste').innerHTML }), go),
      h('div', { class: 'sites' }, ['يوتيوب', 'فيسبوك', 'تيك توك', 'إنستغرام', 'إكس', '+1800 موقع'].map((s) => h('span', { class: 'site-chip' }, s)))),
    clip,
    result,
    h('div', { class: 'gcards' },
      gcard({ color: 'c-sky', icon: 'video', title: 'فيديو', sub: 'حتى 4K مع الصوت', onClick: pick('video', 'الصق رابط الفيديو') }),
      gcard({ color: 'c-violet', icon: 'audio', title: 'صوت', sub: 'MP3 أو M4A', onClick: pick('audio', 'الصق الرابط وسيُختار الصوت') }),
      gcard({ color: 'c-amber', icon: 'playlist', title: 'قائمة تشغيل', sub: 'نزّلها كاملة', onClick: pick('video', 'الصق رابط القائمة (فيه list=)') })),
    recent.length ? h('h3', { class: 'sec-h' }, 'آخر التنزيلات') : null,
    recent.length ? h('div', { class: 'lcards' }, recent.map((j) => lcard({
      color: j.kind === 'audio' ? 'c-violet' : 'c-sky', icon: j.kind === 'audio' ? 'audio' : 'video', title: j.title, sub: j.path || '',
      onClick: () => Sayl.open(j.id).catch((e) => toast(e.message, 'bad')), end: ic('play', 'l-go'),
    }))) : null,
  ].filter(Boolean));

  if (pendingShared) {
    input.value = pendingShared;
    pendingShared = null;
    analyze();
  } else suggestClipboard();
}

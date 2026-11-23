// الرئيسية: حقل الرابط، والمعاينة، وبطاقات الأنواع، وآخر التنزيلات
import { h, ic, toast, gcard, lcard, fmtDur, pathText, sheet } from '../ui.js';
import { extractUrl, siteOf } from '../links.js';
import { parseInfo, buildArgs } from '../formats.js';
import { getSettings } from '../store.js';
import { classifyError } from '../errors.js';
import { Sayl, Clip } from '../native.js';
import { doneJobs, refreshJobs, logError } from '../state.js';
import { count, W } from '../text.js';
import { quickLabel, quickHint, quickChoice } from '../quick.js';
import { choicePicker, uid } from './choice.js';
import { whenPicker } from './when.js';
import { extrasPicker } from './extras.js';
import { openPlaylist } from './playlist.js';
import { openAlbum } from './album.js';
import { openBatch } from './batch.js';
import { openStatuses } from './statuses.js';
import { engineUpdateFlow, cookiesFlow } from './more.js';
import { openTools } from './tools.js';

let pendingShared = null;
let pendingQuick = null;
let preferType = 'video';

// رابط من المشاركة أو الحافظة: «احفظ بطريقتي» (طرق جاهزة بلمسة) أو المعاينة المفصّلة
// direct: إلى المعاينة دون نافذة الطرق (مثلاً رابط فيديو لُصق في تبويب الصور)
export function handleSharedText(text, ctx, { direct = false } = {}) {
  const url = extractUrl(text);
  const s = getSettings();
  if (!url || direct || !s.shareAsk || !s.quick.length) { pendingShared = text; ctx.go('home'); return; }
  document.querySelector('.modal .quick-sheet')?.closest('.modal')?.remove();
  const pick = (p) => () => { close(); pendingShared = url; pendingQuick = p; ctx.go('home'); };
  const close = sheet(h('div', { class: 'quick-sheet' },
    h('h3', {}, 'احفظ بطريقتي'),
    h('small', { class: 'muted ltr ellipsis', dir: 'ltr' }, url),
    h('div', { class: 'lcards' }, s.quick.map((p, i) => {
      const c = lcard({ color: p.type === 'audio' ? 'c-violet' : 'c-sky', icon: p.type === 'audio' ? 'audio' : 'video', title: quickLabel(p), sub: quickHint(p), onClick: pick(p), end: ic('download', 'l-go') });
      c.id = `quick-${i}`;
      return c;
    })),
    h('button', { class: 'btn ghost wide', id: 'quick-custom', onclick: pick(null) }, ic('gear'), 'اختيار مفصّل'),
    h('p', { class: 'hint' }, 'غيّر هذه الطرق أو أوقف هذه النافذة من المزيد ← الإعدادات ← «احفظ بطريقتي».')));
}

export async function enqueueJobs(jobs) {
  try {
    const r = await Sayl.enqueue(jobs);
    await refreshJobs();
    if (!r.added) toast('موجود في الطابور', 'bad');
    else if (jobs[0]?.scheduledAt > 0) toast(jobs.length > 1 ? `جُدول ${count(r.added, W.file)}` : 'جُدول التنزيل', 'ok');
    else toast(r.skipped ? `أُضيف ${count(r.added, W.file)}، وتُرك ${count(r.skipped, W.file)} موجود مسبقاً` : jobs.length > 1 ? `أُضيف ${count(r.added, W.file)} إلى الطابور` : 'أُضيف إلى الطابور', 'ok');
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

// «احفظ بطريقتي»: يُضاف مباشرة بالطريقة المختارة، والمعاينة تبقى ظاهرة لمن أراد غيرها
async function saveQuick(info, url, p) {
  const choice = quickChoice(p);
  const s = getSettings();
  await enqueueJobs([{ id: uid(), url: info.url || url, title: info.title, kind: choice.type, args: buildArgs(choice, { aria2: s.aria2, connections: s.connections }), choice,
    server: info.server || undefined, thumb: info.thumbnail || '', site: siteOf(url).id, scheduledAt: 0, wifiOnly: false }]);
}

// current: الرابط الموجود في الحقل الآن؛ المعاينة لا تنزّل إلا الرابط الذي قُرئت منه
function preview(info, url, current) {
  const site = siteOf(url);
  const extras = extrasPicker(info, preferType);
  const picker = choicePicker({ heights: info.heights, type: preferType, onType: (k) => extras.setKind(k) });
  const when = whenPicker();
  const btn = h('button', { id: 'dl', class: 'btn primary wide', 'data-url': url }, ic('download'), 'نزّل');
  btn.onclick = async () => {
    if (current() !== url) { toast('تغيّر الرابط؛ اضغط «اقرأ» لقراءة الرابط الجديد', 'bad'); return; }
    const x = extras.get();
    if (x.error) { toast(x.error, 'bad'); return; }
    const choice = { ...picker.choice(), ...(x.subs && { subs: x.subs }), ...(x.after && { after: x.after }) };
    const s = getSettings();
    btn.disabled = true;
    await enqueueJobs([{ id: uid(), url: info.url || url, title: info.title, kind: picker.kind(), args: buildArgs(choice, { aria2: s.aria2, connections: s.connections }), choice, server: x.server,
      thumb: info.thumbnail || '', site: site.id, ...when.get() }]);
    btn.disabled = false;
  };
  return h('div', { class: 'card preview' },
    h('div', { class: 'pv-head' },
      info.thumbnail ? h('img', { class: 'thumb', src: info.thumbnail, alt: '', referrerpolicy: 'no-referrer', onerror: (e) => e.target.replaceWith(ic('video', 'thumb ph')) }) : ic('video', 'thumb ph'),
      h('div', { class: 'pv-txt' }, h('b', { class: 'more', style: '--lines:3', dir: 'auto' }, info.title),
        h('small', {}, [site.name, info.uploader, fmtDur(info.duration)].filter(Boolean).join(' · ')))),
    // السيرفر 1 لم يقرأ الرابط فقرأه السيرفر 3: يُنزَّل به مباشرة
    info.server === 3 && h('p', { class: 'hint', id: 'via-s3' }, 'قرأ السيرفر 3 هذا الفيديو من صفحة تيك توك نفسها، وسيُنزَّل به.'),
    picker.el, extras.el, when.el, btn);
}

export function homeView(ctx) {
  const { page } = ctx;
  const input = h('input', { id: 'url', type: 'url', inputmode: 'url', placeholder: 'الصق رابط الفيديو أو قائمة التشغيل', autocomplete: 'off', enterkeyhint: 'go' });
  const result = h('div', { class: 'result' });
  // كل قراءة لها رقم؛ لا يُعرض إلا رد آخر قراءة، والقديم الذي يصل متأخراً يُهمل
  let req = 0;
  let shownUrl = null;
  const current = () => extractUrl(input.value);

  async function analyze() {
    const url = extractUrl(input.value);
    if (!url) { toast('الصق رابطاً صحيحاً لصفحة الفيديو', 'bad'); return; }
    const my = ++req;
    // طريقة «احفظ بطريقتي» تخص الرابط الذي شوركت معه فقط
    const quick = pendingQuick && url === pendingQuick.url ? pendingQuick.p : null;
    pendingQuick = null;
    input.value = url;
    shownUrl = null;
    result.replaceChildren(h('div', { class: 'card center' }, h('div', { class: 'spinner' }), h('div', { class: 'muted' }, 'جارٍ قراءة الرابط…')));
    try {
      const info = parseInfo(await Sayl.info(url));
      if (my !== req) return;
      if (quick && info.kind !== 'single') toast('الرابط فيه أكثر من ملف؛ اختر منه ما تريد', '');
      if (info.kind === 'playlist') { result.replaceChildren(); openPlaylist(ctx, info, url); return; }
      if (info.kind === 'album') { result.replaceChildren(); openAlbum(ctx, info); return; }
      shownUrl = url;
      result.replaceChildren(preview(info, url, current));
      if (quick) await saveQuick(info, url, quick);
    } catch (e) {
      if (my !== req) return;
      logError('info', e.message);
      result.replaceChildren(errorCard(e, { onRetry: analyze }));
    }
  }

  // تغيير الرابط يبطل القراءة الجارية والمعاينة القديمة
  input.addEventListener('input', () => {
    const u = current();
    if (u === shownUrl) return;
    req++;
    if (result.firstChild) { shownUrl = null; result.replaceChildren(); }
  });

  const go = h('button', { id: 'go', class: 'btn primary', onclick: analyze }, 'اقرأ');
  input.addEventListener('keydown', (e) => e.key === 'Enter' && analyze());

  async function paste() {
    try {
      const value = await Clip.read();
      if (!extractUrl(value)) return toast('لا يوجد رابط في الحافظة', 'bad');
      input.value = value;
      analyze();
    } catch { toast('تعذّر قراءة الحافظة، الصق يدوياً', 'bad'); }
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
      h('div', { class: 'url-row' }, input, h('button', { id: 'paste', class: 'icon-btn', 'aria-label': 'لصق', onclick: paste, html: ic('paste').innerHTML }), go),
      h('button', { id: 'open-batch', class: 'link batch-link', onclick: () => openBatch(ctx) }, ic('links'), 'عدة روابط دفعة واحدة'),
      h('div', { class: 'sites' }, ['يوتيوب', 'فيسبوك', 'تيك توك', 'إنستغرام', 'إكس', '+1800 موقع'].map((s) => h('span', { class: 'site-chip' }, s)))),
    result,
    h('div', { class: 'gcards' },
      gcard({ color: 'c-sky', icon: 'video', title: 'فيديو', sub: 'حتى 4K مع الصوت', onClick: pick('video', 'الصق رابط الفيديو') }),
      gcard({ color: 'c-violet', icon: 'audio', title: 'صوت', sub: 'MP3 أو M4A', onClick: pick('audio', 'الصق الرابط وسيُختار الصوت') }),
      gcard({ color: 'c-amber', icon: 'playlist', title: 'قائمة تشغيل', sub: 'نزّلها كاملة', onClick: pick('video', 'الصق رابط القائمة (فيه list=)') })),
    h('div', { class: 'lcards' },
      (() => { const c = lcard({ color: 'c-teal', icon: 'tools', title: 'أدوات الوسائط', sub: 'تحويل وضغط وقص ودمج واستخراج الصوت، على هاتفك', onClick: () => openTools(ctx) }); c.id = 'open-tools'; return c; })(),
      (() => { const c = lcard({ color: 'c-wa', icon: 'whatsapp', title: 'حالات واتساب', sub: 'احفظ صور وفيديوهات الحالات التي شاهدتها', onClick: () => openStatuses(ctx) }); c.id = 'open-statuses'; return c; })()),
    recent.length ? h('h3', { class: 'sec-h' }, 'آخر التنزيلات') : null,
    recent.length ? h('div', { class: 'lcards' }, recent.map((j) => lcard({
      color: j.kind === 'audio' ? 'c-violet' : 'c-sky', icon: j.kind === 'audio' ? 'audio' : 'video', title: j.title, sub: j.path ? pathText(j.path) : '', clamp: true,
      onClick: () => Sayl.open(j.id).catch((e) => toast(e.message, 'bad')), end: ic('play', 'l-go'),
    }))) : null,
  ].filter(Boolean));

  if (pendingShared) {
    input.value = pendingShared;
    if (pendingQuick !== null) pendingQuick = pendingQuick && { url: extractUrl(pendingShared), p: pendingQuick };
    pendingShared = null;
    analyze();
  }
  return () => { req++; };
}

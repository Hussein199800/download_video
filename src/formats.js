// ما الذي ننزّله: بناء أوامر yt-dlp من اختيار المستخدم، وتحليل معلومات الرابط.
// مسار الإخراج يضيفه Kotlin وحده، فلا تحتوي هذه الأوامر على -o أو تنفيذ أوامر.

// العنوان يُقصّ إلى 150 بايت ويُلحق به المعرّف حتى لا يتكرر الاسم
export const OUTPUT_TEMPLATE = '%(title).150B [%(id)s].%(ext)s';
export const HEIGHTS = [2160, 1440, 1080, 720, 480, 360];

export function buildArgs(choice, settings = {}) {
  // إعادة المحاولة داخل yt-dlp لكل طلب؛ إعادة المهمة كلها يتولاها العامل
  const a = ['--no-playlist', '--windows-filenames', '--no-mtime', '--retries', '10', '--fragment-retries', '10'];
  if (choice.type === 'audio') {
    // دمج الصورة في M4A يحتاج مكتبة غير مضمّنة، فيقتصر على MP3
    if (choice.format === 'm4a') a.push('-f', 'ba[ext=m4a]/ba', '-x', '--audio-format', 'm4a', '--embed-metadata');
    else a.push('-f', 'ba/b', '-x', '--audio-format', 'mp3', '--audio-quality', `${choice.kbps || 192}K`, '--embed-thumbnail', '--embed-metadata');
  } else {
    const h = Number(choice.height);
    // عند تساوي الدقة يُفضَّل H.264 وصوت M4A لأنهما يعملان على كل الأجهزة
    a.push('-f', h > 0 ? `bv*[height<=${h}]+ba/b[height<=${h}]/b` : 'bv*+ba/b', '-S', 'res,vcodec:h264,acodec:m4a', '--merge-output-format', 'mp4', '--embed-metadata');
  }
  // الترجمة (للفيديو): ملف SRT بجانب الفيديو، أو مدمجة فيه (yt-dlp يحذف الملف بعد الدمج)
  const sub = choice.type !== 'audio' && choice.subs;
  if (sub && sub.lang) {
    a.push(sub.auto ? '--write-auto-subs' : '--write-subs', '--sub-langs', sub.lang, '--convert-subs', 'srt');
    if (sub.embed) a.push('--embed-subs');
  }
  const c = String(settings.connections || 4);
  // aria2c يفتح عدة اتصالات للملف الواحد ويوتيوب قد يخنقها؛ المنزّل الأصلي مع أجزاء متوازية
  // وقطع 10 م.ب أسرع وأثبت معه
  if (settings.aria2) a.push('--downloader', 'libaria2c.so', '--downloader-args', `aria2c:-x${c} -s${c}`);
  else a.push('-N', c, '--http-chunk-size', '10M');
  return a;
}

const UNAVAILABLE = /^\[(private|deleted) video\]$/i;

function entryUrl(e) {
  if (e.url && /^https?:/.test(e.url)) return e.url;
  if (e.webpage_url) return e.webpage_url;
  if (e.id && /youtube/i.test(e.ie_key || '')) return `https://www.youtube.com/watch?v=${e.id}`;
  return e.url || null;
}

// ألبوم صور (منشور تيك توك): العناصر بترتيب المصدر
function parseAlbum(j) {
  const entries = (j.entries || []).filter((e) => e && Array.isArray(e.urls) && e.urls.length)
    .map((e) => ({ index: Number(e.index), urls: e.urls, width: e.width || 0, height: e.height || 0 }));
  return { kind: 'album', id: String(j.id || ''), title: j.title || 'ألبوم', uploader: j.uploader || '', url: j.webpage_url || '', site: j.site || 'other', audio: j.audio || null, count: entries.length, entries };
}

// اسم مجلد الألبوم: أول سطر من الوصف مقصوراً، ثم [المعرّف] كاملاً دائماً
export function albumFolder(title, id) {
  const first = String(title || '').split('\n')[0].replace(/[\\/:*?"<>|]/g, ' ').replace(/\s+/g, ' ').trim();
  const short = [...first].slice(0, 50).join('').trim();
  return `${short || 'منشور'} [${id}]`;
}

// اسم اللغة بالعربية؛ «en-orig» لغة المقطع الأصلية في يوتيوب
const LANG = typeof Intl.DisplayNames === 'function' ? new Intl.DisplayNames(['ar'], { type: 'language' }) : null;
function langName(code) {
  const base = String(code).replace(/-orig$/, '');
  try { return LANG?.of(base) || base; } catch { return base; }
}

/**
 * الترجمات المتاحة: اليدوية كلها، ومن التلقائية العربية ولغة المقطع الأصلية والإنجليزية فقط
 * (يوتيوب يعرض مئات الترجمات الآلية المترجمة عن الأصل). العربية أولاً ثم اليدوية.
 */
export function subtitleList(j) {
  const out = [];
  const seen = new Set();
  const add = (lang, auto) => {
    const key = lang.replace(/-orig$/, '');
    if (seen.has(key) || lang === 'live_chat') return;
    seen.add(key);
    out.push({ lang, auto, label: auto ? `${langName(lang)} · تلقائية` : langName(lang) });
  };
  Object.keys(j.subtitles || {}).forEach((l) => add(l, false));
  const auto = Object.keys(j.automatic_captions || {});
  auto.filter((l) => /-orig$/.test(l)).forEach((l) => add(l, true));
  auto.filter((l) => l === 'ar' || l === 'en').forEach((l) => add(l, true));
  const rank = (s) => (s.lang.startsWith('ar') ? 0 : 1) * 2 + (s.auto ? 1 : 0);
  return out.sort((a, b) => rank(a) - rank(b));
}

export function parseInfo(j) {
  if (j._type === 'album') return parseAlbum(j);
  if (j._type === 'playlist' || Array.isArray(j.entries)) {
    const entries = (j.entries || []).filter(Boolean).map((e) => {
      const url = entryUrl(e);
      return {
        id: e.id, title: e.title || '', url, duration: e.duration || null,
        available: Boolean(e.title && url && !UNAVAILABLE.test(e.title)),
      };
    });
    return { kind: 'playlist', id: j.id, title: j.title || 'قائمة تشغيل', count: entries.length, entries };
  }
  const heights = [...new Set((j.formats || [])
    .filter((f) => f.height && f.vcodec !== 'none')
    .map((f) => f.height))].sort((x, y) => y - x);
  return {
    kind: 'single', id: j.id, title: j.title || 'بدون عنوان', uploader: j.uploader || j.channel || '',
    duration: j.duration || null, thumbnail: j.thumbnail || null, url: j.webpage_url || j.original_url || j.url, heights, subs: subtitleList(j),
  };
}

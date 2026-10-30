// استخراج الرابط من نص المشاركة، ومعرفة الموقع لعرض اسمه ولونه
const URL_RE = /https?:\/\/[^\s<>"'«»]+/i;
const URL_ALL = /https?:\/\/[^\s<>"'«»]+/gi;
// علامات ترقيم تلتصق بآخر الرابط عند لصقه داخل جملة
const TRAILING = /[).,،؛:!?؟\]}'"»]+$/u;

export function extractUrl(text) {
  const m = String(text ?? '').match(URL_RE);
  if (!m) return null;
  let url = m[0];
  while (TRAILING.test(url)) {
    // نُبقي القوس الختامي إذا كان جزءاً من الرابط نفسه
    if (url.endsWith(')') && url.split('(').length > url.split(')').length - 1) break;
    url = url.replace(/.$/u, '');
  }
  return url;
}

// كل الروابط في النص، مرة واحدة لكل رابط وبترتيب ظهورها
export function extractUrls(text) {
  const seen = new Set();
  for (const m of String(text ?? '').match(URL_ALL) || []) {
    const u = extractUrl(m);
    if (u) seen.add(u);
  }
  return [...seen];
}

const SITES = [
  { id: 'youtube', name: 'يوتيوب', hosts: ['youtube.com', 'youtu.be', 'youtube-nocookie.com'] },
  { id: 'facebook', name: 'فيسبوك', hosts: ['facebook.com', 'fb.watch', 'fb.com'] },
  { id: 'tiktok', name: 'تيك توك', hosts: ['tiktok.com'] },
  { id: 'instagram', name: 'إنستغرام', hosts: ['instagram.com', 'instagr.am'] },
  { id: 'x', name: 'إكس (تويتر)', hosts: ['x.com', 'twitter.com'] },
];
const OTHER = { id: 'other', name: 'موقع آخر' };

export function siteOf(url) {
  let host;
  try { host = new URL(url).hostname.toLowerCase(); } catch { return OTHER; }
  const s = SITES.find((s) => s.hosts.some((h) => host === h || host.endsWith('.' + h)));
  return s ? { id: s.id, name: s.name } : OTHER;
}

// السيرفرات: 1 = yt-dlp لكل المواقع، 2 = NewPipe ليوتيوب وساوندكلاود وباندكامب،
// 3 = قراءة صفحة تيك توك نفسها (TikTokVideo.kt). مطابقة لـ Server2.supports وTikTokVideo.supports.
// المحرك في المهمة: 1 و2 كما هما، 3 = صور تيك توك (ليس سيرفراً يُختار)، 4 = السيرفر 3.

const S2 = /(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com|soundcloud\.com|bandcamp\.com|media\.ccc\.de)$/;

export const SERVERS = {
  1: { name: 'سيرفر 1', desc: 'yt-dlp: يغطي أكثر من 1800 موقع' },
  2: { name: 'سيرفر 2', desc: 'NewPipe: يوتيوب وساوندكلاود' },
  3: { name: 'سيرفر 3', desc: 'تيك توك: يقرأ صفحة الفيديو نفسها' },
};

function parse(url) {
  try { return new URL(url); } catch { return null; }
}

/** السيرفرات التي تدعم الرابط، بترتيب التجربة التلقائية */
export function serversFor(url) {
  const u = parse(url);
  if (!u) return [1];
  const host = u.hostname.toLowerCase();
  const out = [1];
  if (S2.test(host) && !(host.includes('youtube') && u.pathname.startsWith('/playlist'))) out.push(2);
  if (/(^|\.)tiktok\.com$/.test(host) && !u.pathname.includes('/photo/')) out.push(3);
  return out;
}

/** رقم السيرفر في الواجهة من محرك المهمة؛ null لصور تيك توك */
export const serverOf = (engine) => ({ 1: 1, 2: 2, 4: 3 }[engine || 1] ?? null);

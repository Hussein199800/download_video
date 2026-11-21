// «احفظ بطريقتي»: طرق حفظ جاهزة تظهر عند مشاركة رابط إلى سَيْل، فيُنزَّل بلمسة دون المرور بالمعاينة.
// كل طريقة: فيديو بدقة (ومعها نسخة للواتساب اختيارياً) أو صوت بصيغة وجودة.
import { iso } from './text.js';
const HEIGHTS = ['best', 2160, 1440, 1080, 720, 480, 360];
const RES = { best: 'أفضل جودة', 2160: '4K', 1440: '1440p', 1080: '1080p', 720: '720p', 480: '480p', 360: '360p' };
export const MAX_QUICK = 5;

export const DEFAULT_QUICK = Object.freeze([
  Object.freeze({ type: 'video', height: 'best' }),
  Object.freeze({ type: 'video', height: 720, wa: true }),
  Object.freeze({ type: 'audio', format: 'mp3', kbps: 192 }),
]);

function one(p) {
  if (!p || typeof p !== 'object') return null;
  if (p.type === 'audio') {
    if (p.format === 'm4a') return { type: 'audio', format: 'm4a' };
    const kbps = [128, 192, 320].includes(Number(p.kbps)) ? Number(p.kbps) : 192;
    return { type: 'audio', format: 'mp3', kbps };
  }
  if (p.type !== 'video') return null;
  const height = HEIGHTS.includes(p.height) ? p.height : HEIGHTS.includes(Number(p.height)) ? Number(p.height) : 'best';
  return p.wa ? { type: 'video', height, wa: true } : { type: 'video', height };
}

const key = (p) => JSON.stringify(p);

/** قائمة صالحة بلا تكرار، بحد أقصى خمس طرق */
export function sanitizeQuick(list) {
  if (!Array.isArray(list)) return DEFAULT_QUICK.map((p) => ({ ...p }));
  const seen = new Set();
  return list.map(one).filter((p) => p && !seen.has(key(p)) && seen.add(key(p))).slice(0, MAX_QUICK);
}

export const sameQuick = (a, b) => key(one(a)) === key(one(b));

export function quickLabel(p) {
  if (p.type === 'audio') return p.format === 'm4a' ? 'صوت M4A' : `صوت ${iso(`MP3 ${p.kbps}`)}`;
  return `فيديو ${RES[p.height]}${p.wa ? ' + نسخة للواتساب' : ''}`;
}

export const quickHint = (p) => (p.type === 'audio'
  ? (p.format === 'm4a' ? 'الصوت الأصلي دون تحويل' : 'يعمل على كل الأجهزة')
  : p.wa ? 'ومعه نسخة مضغوطة H.264 أصغر للإرسال' : p.height === 'best' ? 'أعلى دقة يتيحها المقطع' : 'أو أقرب دقة أقل منها');

/** الاختيار الذي يُبنى منه أمر yt-dlp، والخطوات التلقائية بعد التنزيل */
export function quickChoice(p) {
  if (p.type === 'audio') return { type: 'audio', format: p.format, kbps: p.kbps || 192 };
  return { type: 'video', height: p.height, ...(p.wa && { after: [{ op: 'compress', params: { level: 'strong', codec: 'h264' } }] }) };
}

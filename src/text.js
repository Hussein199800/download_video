// أدوات النص العربي: صيغة المعدود، والأرقام، وتوحيد البحث، والعزل الاتجاهي.
// الأرقام تُعرض دائماً بالأرقام اللاتينية (ar-u-nu-latn) مهما كانت لغة الجهاز.

export const LOCALE = 'ar-u-nu-latn';

// عزل اتجاهي لرقم أو وحدة أو نسبة داخل جملة عربية حتى لا تنقلب (FSI … PDI)
export const iso = (s) => `⁨${s}⁩`;

/**
 * صيغة المعدود الصحيحة: count(3, ['ساعة', 'ساعتان', 'ساعات', 'ساعة'])
 * forms = [مفرد، مثنى، جمع (3–10)، تمييز (11 فما فوق)].
 * المفرد والمثنى يُكتبان دون رقم كما في العربية: «ساعة»، «ساعتان»، والصفر بصيغة الجمع «0 ملفات».
 */
export function count(n, [one, two, few, many]) {
  n = Math.abs(Math.trunc(Number(n) || 0));
  if (n === 0) return `0 ${few}`;
  if (n === 1) return one;
  if (n === 2) return two;
  const r = n % 100;
  if (r >= 3 && r <= 10) return `${n} ${few}`;
  return `${n} ${many}`;
}

// صيغ شائعة في التطبيق
export const W = {
  file: ['ملف واحد', 'ملفان', 'ملفات', 'ملفاً'],
  link: ['رابط واحد', 'رابطان', 'روابط', 'رابطاً'],
  clip: ['مقطع واحد', 'مقطعان', 'مقاطع', 'مقطعاً'],
  status: ['حالة واحدة', 'حالتان', 'حالات', 'حالة'],
  time: ['مرة واحدة', 'مرتان', 'مرات', 'مرة'],
  hour: ['ساعة', 'ساعتان', 'ساعات', 'ساعة'],
  minute: ['دقيقة', 'دقيقتان', 'دقائق', 'دقيقة'],
  second: ['ثانية', 'ثانيتان', 'ثوانٍ', 'ثانية'],
  item: ['عنصر واحد', 'عنصران', 'عناصر', 'عنصراً'],
  group: ['مجموعة واحدة', 'مجموعتان', 'مجموعات', 'مجموعة'],
  op: ['عملية واحدة', 'عمليتان', 'عمليات', 'عملية'],
};

// الأرقام العربية (٠-٩) والفارسية (۰-۹) إلى 0-9
export function toLatinDigits(s) {
  return String(s ?? '')
    .replace(/[٠-٩]/g, (d) => String(d.charCodeAt(0) - 0x0660))
    .replace(/[۰-۹]/g, (d) => String(d.charCodeAt(0) - 0x06F0));
}

// رقم مكتوب بأي أرقام وبالفاصلة العشرية «٫» أو «,» أو «.»؛ NaN إن لم يكن رقماً
export function parseNum(s) {
  const t = toLatinDigits(s).trim().replace(/[٫,]/g, '.').replace(/\s+/g, '');
  if (!/^-?(\d+\.?\d*|\.\d+)$/.test(t)) return NaN;
  return Number(t);
}

/**
 * وقت مكتوب: «90» ثانية، أو «1:30»، أو «1:02:03»، أو بالأرقام العربية مع «٫» للكسور.
 * يعيد الثواني أو NaN؛ الدقائق والثواني بعد النقطتين أقل من 60.
 */
export function parseTime(s) {
  const t = toLatinDigits(s).trim().replace(/[٫,]/g, '.');
  if (!t) return NaN;
  const parts = t.split(':');
  if (parts.length > 3 || parts.some((p) => !/^(\d+\.?\d*|\.\d+)$/.test(p))) return NaN;
  const nums = parts.map(Number);
  if (nums.slice(1).some((x) => x >= 60)) return NaN;
  return nums.reduce((acc, x) => acc * 60 + x, 0);
}

// ثوانٍ إلى «1:02:03.5» للحقول القابلة للتعديل (عُشر الثانية إن وُجد)
export function timeField(sec) {
  if (!Number.isFinite(sec) || sec < 0) return '';
  const all = Math.round(sec * 10);
  const tenths = all % 10;
  const s = Math.floor(all / 10);
  const hh = Math.floor(s / 3600), mm = Math.floor((s % 3600) / 60), ss = s % 60;
  const p = (n) => String(n).padStart(2, '0');
  const base = hh ? `${hh}:${p(mm)}:${p(ss)}` : `${mm}:${p(ss)}`;
  return tenths ? `${base}.${tenths}` : base;
}

// توحيد النص للبحث: الهمزات، والتاء المربوطة، والألف المقصورة، والتشكيل والتطويل، والأرقام
export function normalize(s) {
  return toLatinDigits(String(s ?? ''))
    .toLowerCase()
    .replace(/[ً-ٰٟـ]/g, '')
    .replace(/[أإآٱ]/g, 'ا')
    .replace(/ؤ/g, 'و')
    .replace(/ئ/g, 'ي')
    .replace(/ى/g, 'ي')
    .replace(/ة/g, 'ه')
    .replace(/\s+/g, ' ')
    .trim();
}

export const matches = (text, q) => !q || normalize(text).includes(normalize(q));

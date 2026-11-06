// التخطيط حسب الشاشة، ويُعاد حسابه عند التدوير وتغيير حجم النافذة دون إعادة رسم الصفحة،
// فتبقى الأقسام المفتوحة والنص المكتوب كما هي.
//  - الجوال العمودي: عمود واحد بعرض الشاشة.
//  - اللوحي العمودي: تصميم الجوال نفسه مكبّراً بنسبة واحدة (عرض منطقي نحو 560).
//  - الجوال أو اللوحي بالعرض: عمودان متساويان، كل منهما بعرض جوال تقريباً.

const PHONE_COL = 35;     // rem: عرض العمود الواحد المنطقي (560px)
const TWO_COL = 56;       // rem: عرض العمودين مع الفاصل والهوامش

export function layoutFor(w, h) {
  const landscape = w > h;
  if (landscape && w >= 720) {
    // اللوحي بالعرض يُكبَّر حتى يملأ العمودان الشاشة؛ الجوال بالعرض قصير فيبقى بلا تكبير
    const k = h >= 600 ? Math.min(1.5, Math.max(1, w / (TWO_COL * 16))) : 1;
    return { two: true, k };
  }
  if (Math.min(w, h) >= 600) return { two: false, k: Math.min(1.6, Math.max(1, w / (PHONE_COL * 16))) };
  return { two: false, k: 1 };
}

export function applyLayout() {
  const { two, k } = layoutFor(window.innerWidth, window.innerHeight);
  const root = document.documentElement;
  root.classList.toggle('two-col', two);
  root.style.setProperty('--k', String(Math.round(k * 1000) / 1000));
}

export function watchLayout() {
  let raf = 0;
  const on = () => { cancelAnimationFrame(raf); raf = requestAnimationFrame(applyLayout); };
  window.addEventListener('resize', on);
  window.addEventListener('orientationchange', on);
  applyLayout();
}

// تحويل رسائل yt-dlp إلى سبب وحل بالعربية، وإخفاء ما هو حساس في سجل التشخيص
const RULES = [
  { code: 'space', re: /errno 28|no space left/i, title: 'المساحة غير كافية', fix: 'احذف بعض الملفات من الهاتف ثم أعد المحاولة.', action: null },
  { code: 'login', re: /sign in|login required|log in|private|cookies|members only|age-restricted|confirm your age/i,
    title: 'هذا المحتوى يحتاج تسجيل دخول', fix: 'استورد ملف cookies من حسابك في «المزيد ← ملف cookies» ثم أعد المحاولة.', action: 'cookies' },
  { code: 'unsupported', re: /unsupported url/i, title: 'هذا الرابط غير مدعوم', fix: 'تأكد أن الرابط لصفحة الفيديو نفسها، لا لصفحة البحث أو الملف الشخصي.', action: null },
  { code: 'network', re: /timed out|network is unreachable|connection (reset|refused|aborted)|temporary failure in name resolution|getaddrinfo|unable to download webpage|http error 5\d\d/i,
    title: 'انقطع الاتصال', fix: 'تحقق من الإنترنت ثم أعد المحاولة.', action: 'retry' },
  { code: 'extract', re: /unable to extract|unable to download json|no video formats|signature|nsig|http error 403|requested format is not available/i,
    title: 'الموقع غيّر طريقة عمله', fix: 'حدّث مكوّن التنزيل من «المزيد» ثم أعد المحاولة.', action: 'update' },
];

export function classifyError(stderr) {
  const s = String(stderr || '');
  const r = RULES.find((r) => r.re.test(s));
  if (r) return { code: r.code, title: r.title, fix: r.fix, action: r.action };
  return { code: 'unknown', title: 'حدث خطأ غير متوقع', fix: 'أعد المحاولة، وإن تكرر انسخ سجل التشخيص من «المزيد».', action: 'retry' };
}

const SECRET_PARAMS = /([?&](?:token|sig|signature|key|auth|session|access_token)=)[^&\s#]*/gi;

export function redact(text) {
  return String(text || '')
    .replace(SECRET_PARAMS, '$1[مخفي]')
    .replace(/(cookie:\s*)[^\n]*/gi, '$1[مخفي]');
}

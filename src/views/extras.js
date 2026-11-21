// خيارات إضافية قبل التنزيل: جزء من المقطع فقط، ونسخة مضغوطة للواتساب، والترجمة.
// القص والضغط خطوتان تلقائيتان بعد اكتمال التنزيل (choice.after) تنفّذهما أدوات المعالجة،
// والناتج يُحفظ بجانب الملف في مجلد منصته.
import { h, seg } from '../ui.js';
import { iso, parseTime, timeField } from '../text.js';

const sw = (id, title, hint, onChange) => {
  const input = h('input', { type: 'checkbox', class: 'sw', id, onchange: (e) => onChange(e.target.checked) });
  return { input, row: h('label', { class: 'set-row' }, h('span', {}, title, h('small', {}, hint)), input) };
};

export function extrasPicker(info, kind = 'video') {
  const dur = Number(info.duration) || 0;
  const st = { clip: false, precise: false, keep: false, wa: false, sub: '', embed: false, kind };

  // ---------- جزء من المقطع ----------
  const startIn = h('input', { class: 'field time-field', id: 'clip-start', inputmode: 'decimal', dir: 'ltr', value: timeField(0), 'aria-label': 'البداية' });
  const endIn = h('input', { class: 'field time-field', id: 'clip-end', inputmode: 'decimal', dir: 'ltr', value: dur ? timeField(dur) : '', 'aria-label': 'النهاية', placeholder: '2:45' });
  const err = h('p', { class: 'err-text', role: 'alert', id: 'clip-err' });
  const check = () => {
    const s = parseTime(startIn.value), e = parseTime(endIn.value);
    let msg = '';
    if (Number.isNaN(s)) msg = 'وقت البداية غير مفهوم. اكتب مثلاً 1:30 أو 90.';
    else if (Number.isNaN(e)) msg = 'وقت النهاية غير مفهوم. اكتب مثلاً 2:45.';
    else if (s >= e) msg = 'النهاية يجب أن تكون بعد البداية.';
    else if (dur && e > dur + 0.5) msg = `النهاية بعد آخر المقطع (${timeField(dur)}).`;
    startIn.classList.toggle('bad', Boolean(msg) && (Number.isNaN(s) || s >= e));
    endIn.classList.toggle('bad', Boolean(msg) && !Number.isNaN(s) && (Number.isNaN(e) || s >= e || e > dur + 0.5));
    err.textContent = msg;
    return msg ? null : { start: s, end: dur ? Math.min(e, dur) : e };
  };
  startIn.addEventListener('input', check);
  endIn.addEventListener('input', check);
  const precise = sw('clip-precise', 'قص دقيق', 'يبدأ عند الثانية المحددة تماماً، لكنه أبطأ لأنه يعيد ترميز الصورة. دونه يبدأ من أقرب إطار مفتاحي (قد يسبقها بثانية أو اثنتين).', (v) => { st.precise = v; });
  const keep = sw('clip-keep', 'احتفظ بالمقطع الكامل أيضاً', 'دونه يُحذف الملف الكامل بعد قص الجزء، ويبقى الجزء وحده.', (v) => { st.keep = v; });
  const clipBox = h('div', { class: 'clip-box', hidden: true },
    h('div', { class: 'range-grid' },
      h('label', { class: 'time-lbl col' }, h('span', {}, 'من'), startIn),
      h('label', { class: 'time-lbl col' }, h('span', {}, 'إلى'), endIn)),
    err,
    h('p', { class: 'hint' }, `يُنزَّل المقطع كاملاً ثم يُقص الجزء تلقائياً ويُحفظ باسم «… (مقطع)».${dur ? ` مدة المقطع ${iso(timeField(dur))}.` : ''}`),
    precise.row, keep.row);
  const clip = sw('clip-on', 'جزء من المقطع فقط', 'حدد البداية والنهاية، مثل 1:30 إلى 2:45.', (v) => { st.clip = v; clipBox.hidden = !v; if (v) check(); });

  // ---------- نسخة للواتساب ----------
  const wa = sw('wa-copy', 'ونسخة مضغوطة للواتساب', 'نسخة إضافية أصغر بترميز H.264 تعمل على كل الهواتف وتُرسل أسرع، بالأبعاد نفسها. الأصل يبقى كما هو.', (v) => { st.wa = v; });

  // ---------- الترجمة ----------
  const subs = info.subs || [];
  const subMode = h('div', { hidden: true, id: 'subs-mode-box' },
    seg([{ value: 'srt', label: 'ملف SRT بجانبه' }, { value: 'embed', label: 'مدمجة في الفيديو' }], 'srt', (v) => { st.embed = v === 'embed'; }, 'c-sky'),
    h('p', { class: 'hint' }, 'الملف المنفصل يفتحه أي مشغّل ويمكن تعديله. المدمجة تبقى داخل الفيديو ويُظهرها المشغّل عند تفعيلها.'));
  const subSel = subs.length ? h('select', { class: 'field', id: 'subs-lang', 'aria-label': 'لغة الترجمة',
    onchange: (e) => { st.sub = e.target.value; subMode.hidden = !st.sub; } },
  h('option', { value: '' }, 'بلا ترجمة'), ...subs.map((s) => h('option', { value: s.lang }, s.label))) : null;
  const subBox = subSel && h('div', { class: 'set-col' },
    h('b', {}, 'الترجمة'), h('small', {}, '«تلقائية» يكتبها الموقع آلياً وقد تكون فيها أخطاء.'), subSel, subMode);

  const videoOnly = [wa.row, subBox].filter(Boolean);
  const el = h('details', { class: 'card extras', id: 'extras' },
    h('summary', {}, 'خيارات إضافية', h('small', {}, 'جزء من المقطع، نسخة للواتساب، الترجمة')),
    clip.row, clipBox, ...videoOnly);

  const setKind = (k) => {
    st.kind = k;
    videoOnly.forEach((x) => { x.hidden = k !== 'video'; });
  };
  setKind(kind);

  return {
    el,
    setKind,
    /** {subs, after} لإضافتها إلى choice، أو {error} إن كان الجزء غير صالح */
    get() {
      const after = [];
      if (st.clip) {
        const r = check();
        if (!r) { el.open = true; clip.input.focus(); return { error: err.textContent }; }
        after.push({ op: 'trim', params: { start: r.start, end: r.end, precise: st.precise, deleteSource: !st.keep } });
      }
      const video = st.kind === 'video';
      if (video && st.wa) after.push({ op: 'compress', params: { level: 'strong', codec: 'h264' } });
      const s = video && st.sub ? subs.find((x) => x.lang === st.sub) : null;
      return { after: after.length ? after : undefined, subs: s ? { lang: s.lang, auto: s.auto, embed: st.embed } : undefined };
    },
  };
}

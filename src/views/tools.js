// أدوات الوسائط: تحويل الصيغة، واستخراج الصوت وتحويله، والضغط، والقص واللقطة، والدمج.
// المعالجة كلها على الهاتف ولا يُرفع أي ملف. الأصل لا يُعدَّل أبداً، والناتج ملف جديد.
import { h, ic, toast, sheet, chips, seg, fmtBytes, fmtDur, gcard, explain } from '../ui.js';
import { Media, isNative } from '../native.js';
import { count, W, iso, parseTime, timeField } from '../text.js';
import { taskCard, OPS } from './process.js';
import { startTasks, onTasks, allTasks, taskId } from '../tasks.js';
import { videoMode, audioCopy, canConcatCopy, MODE_LABEL, VIDEO_TARGETS, AUDIO_TARGETS, resName } from '../mediarules.js';
import { getSettings } from '../store.js';

// ---------- آخر اختيارات المستخدم في الأدوات ----------
const PREF = 'sayl.tools';
const prefs = () => { try { return JSON.parse(localStorage.getItem(PREF) || '{}'); } catch { return {}; } };
const setPref = (patch) => { try { localStorage.setItem(PREF, JSON.stringify({ ...prefs(), ...patch })); } catch {} };

// ---------- معلومات الملف ----------
const CODEC = { h264: 'H.264', hevc: 'H.265', vp9: 'VP9', vp8: 'VP8', av1: 'AV1', mpeg4: 'MPEG-4', aac: 'AAC', mp3: 'MP3', opus: 'Opus', vorbis: 'Vorbis', flac: 'FLAC', pcm_s16le: 'PCM' };
const codecName = (c) => CODEC[c] || (c || '').toUpperCase();
const kbps = (b) => (b > 0 ? iso(`${Math.round(b / 1000)} ك.ب/ث`) : '—');

export function probeRows(p, size) {
  const v = p.video, a = p.audio;
  return [
    ['الحجم', fmtBytes(size || p.size) || '—'],
    ['المدة', p.duration ? iso(fmtDur(p.duration)) : '—'],
    v && ['الفيديو', `${codecName(v.codec)} · ${iso(`${v.width}×${v.height}`)}${resName(v.width, v.height) ? ` (${resName(v.width, v.height)})` : ''}`],
    v && ['الإطارات', v.fps ? iso(`${Math.round(v.fps * 100) / 100} إطار/ث`) : '—'],
    v && ['معدل الفيديو', kbps(v.bitrate)],
    a && ['الصوت', `${codecName(a.codec)} · ${a.channels === 1 ? 'أحادي' : a.channels === 2 ? 'ستيريو' : iso(`${a.channels} قنوات`)}`],
    a && ['معدل العينة', a.sampleRate ? iso(`${a.sampleRate} هرتز`) : '—'],
    a && ['معدل الصوت', kbps(a.bitrate)],
    !v && !a && ['المحتوى', 'لا صوت ولا صورة'],
  ].filter(Boolean);
}

export async function fileInfoSheet(ref) {
  const body = h('div', { class: 'center' }, h('div', { class: 'spinner' }));
  const close = sheet(h('h3', { dir: 'auto' }, ref.name), body, h('button', { class: 'btn ghost wide', onclick: () => close() }, 'إغلاق'));
  try {
    const p = await Media.probe(ref.uri, ref.size);
    body.replaceWith(h('dl', { class: 'kv' }, probeRows(p, ref.size).flatMap(([k, v]) => [h('dt', {}, k), h('dd', {}, v)])));
  } catch (e) {
    body.replaceWith(h('p', { class: 'err-text' }, e.message || 'تعذّرت قراءة الملف'));
  }
}

// ---------- اختيار الملفات ----------
const KIND_TYPES = { video: ['video/*'], audio: ['audio/*'], av: ['video/*', 'audio/*'] };

async function pickFiles(kind, multiple) {
  try {
    return await Media.pick({ multiple, types: KIND_TYPES[kind] });
  } catch (e) {
    if (!/لم يُختر/.test(e.message)) toast(e.message, 'bad');
    return [];
  }
}

/**
 * قائمة الملفات المختارة للأداة: إضافة وإزالة، ومعلومات كل ملف.
 * onChange تُستدعى بالقائمة الجديدة.
 */
function filesBox({ kind, multiple, files, onChange, order = false }) {
  const box = h('div', { class: 'card files-box' });
  const draw = () => {
    const rows = files.map((f, i) => h('div', { class: 'file-row', 'data-uri': f.uri },
      ic(f.kind === 'audio' ? 'audio' : 'video', `l-ic sm ${f.kind === 'audio' ? 'c-violet' : 'c-sky'}`),
      h('button', { class: 'l-txt plain', onclick: () => fileInfoSheet(f), 'aria-label': `معلومات ${f.name}` },
        h('b', { class: 'more', dir: 'auto' }, f.name), h('small', {}, [fmtBytes(f.size), f.meta].filter(Boolean).join(' · ') || '…')),
      order && i > 0 && h('button', { class: 'icon-btn', 'aria-label': 'للأعلى', onclick: () => { files.splice(i - 1, 0, files.splice(i, 1)[0]); onChange(files); draw(); }, html: ic('up').innerHTML }),
      order && i < files.length - 1 && h('button', { class: 'icon-btn', 'aria-label': 'للأسفل', onclick: () => { files.splice(i + 1, 0, files.splice(i, 1)[0]); onChange(files); draw(); }, html: ic('down').innerHTML }),
      h('button', { class: 'icon-btn', 'aria-label': `إزالة ${f.name}`, onclick: () => { files.splice(i, 1); onChange(files); draw(); }, html: ic('x').innerHTML })));
    // replaceChildren يكتب false وnull نصاً، فنمرر الموجود فقط
    box.replaceChildren(...[
      h('div', { class: 'card-h' }, h('h2', {}, multiple ? 'الملفات' : 'الملف'), files.length > 1 ? h('span', { class: 'tag' }, count(files.length, W.file)) : null),
      ...rows,
      (!files.length || multiple) && h('button', { class: `btn ${files.length ? 'ghost' : 'grad c-teal'} wide`, id: 'pick-files', onclick: async () => {
        const got = await pickFiles(kind, multiple);
        if (!got.length) return;
        for (const g of got) if (!files.some((f) => f.uri === g.uri)) files.push(g);
        if (!multiple) files.splice(0, files.length - 1);
        onChange(files);
        draw();
      } }, ic('plus'), files.length ? 'أضف ملفات أخرى' : multiple ? 'اختر الملفات' : 'اختر الملف'),
      !files.length && h('p', { class: 'hint center' }, 'يمكنك أيضاً البدء من «المكتبة»: حدّد الملفات ثم اختر الأداة.')].filter(Boolean));
  };
  draw();
  return { el: box, draw };
}

// يملأ سطر المعلومات المختصر لكل ملف (الدقة والمدة) ويحفظ الخصائص
async function probeAll(files, draw) {
  await Promise.all(files.filter((f) => !f.probe).map(async (f) => {
    try {
      f.probe = await Media.probe(f.uri, f.size);
      const v = f.probe.video;
      f.kind = v ? 'video' : 'audio';
      f.meta = [v ? `${codecName(v.codec)} ${resName(v.width, v.height)}` : codecName(f.probe.audio?.codec), f.probe.duration ? iso(fmtDur(f.probe.duration)) : ''].filter(Boolean).join(' · ');
    } catch (e) {
      f.meta = 'تعذّرت قراءته';
      f.error = e.message;
    }
  }));
  draw();
}

// العمليات التي أُطلقت من هذه الصفحة تظهر أسفلها مع تقدمها
function liveResults(ids) {
  const box = h('div', { class: 'jobs results', 'aria-live': 'polite' });
  const draw = () => box.replaceChildren(...allTasks().filter((t) => ids.has(t.id)).map(taskCard));
  return { el: box, draw, stop: onTasks(draw) };
}

async function start(tasks, results) {
  try {
    await startTasks();
    await Media.enqueue(tasks);
    tasks.forEach((t) => results.ids.add(t.id));
    results.draw();
    toast(tasks.length > 1 ? `بدأت ${count(tasks.length, W.op)}` : 'بدأت المعالجة', 'ok');
    results.el.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' });
  } catch (e) {
    toast(e.message || 'تعذّر البدء', 'bad');
  }
}

const ref = (f) => ({ uri: f.uri, name: f.name, size: f.size || 0, mime: f.mime || null });

// هيكل صفحة أداة: الرأس، والملفات، والإعدادات، والزر، والنتائج
function toolPage(ctx, { title, color = 'c-teal', icon, intro, render }) {
  ctx.open({ title, render: ({ page }) => {
    const ids = new Set();
    const res = liveResults(ids);
    const results = { ids, el: res.el, draw: res.draw };
    page.append(h('div', { class: `hero ${color}` }, ic(icon, 'h-ic'), h('b', {}, title), h('small', {}, intro)));
    render(page, results);
    page.append(res.el);
    return res.stop;
  } });
}

// ---------- تحويل صيغة الفيديو ----------
export function openConvert(ctx, preset = []) {
  toolPage(ctx, { title: 'تحويل الصيغة', icon: 'convert', intro: 'بين MP4 وMKV وMOV وWEBM وAVI. إن كانت الصيغة الجديدة تقبل الترميز نفسه يُغيَّر الغلاف فقط في ثوانٍ وبلا أي فقد في الجودة.', render: (page, results) => {
    const files = preset.filter((f) => f.kind !== 'audio' && f.kind !== 'image').map((f) => ({ ...f }));
    let target = prefs().convert || 'mp4';
    const notes = h('div', { class: 'mode-notes' });
    const btn = h('button', { id: 'run', class: 'btn grad c-teal wide' });
    const sync = () => {
      btn.disabled = !files.length;
      btn.replaceChildren(ic('convert'), files.length > 1 ? `حوّل ${count(files.length, W.file)} إلى ${target.toUpperCase()}` : `حوّل إلى ${target.toUpperCase()}`);
      notes.replaceChildren(...files.filter((f) => f.probe).map((f) => {
        const same = f.name.toLowerCase().endsWith(`.${target}`);
        const m = videoMode(target, f.probe);
        return h('p', { class: `hint mode ${m}` }, h('b', { dir: 'auto' }, f.name), ' — ', same ? 'بالصيغة نفسها أصلاً' : f.probe.video ? MODE_LABEL[m] : 'ملف صوتي: استعمل «استخراج الصوت»');
      }));
    };
    const fb = filesBox({ kind: 'video', multiple: true, files, onChange: () => { sync(); probeAll(files, () => { fb.draw(); sync(); }); } });
    probeAll(files, () => { fb.draw(); sync(); });
    btn.onclick = () => {
      const ok = files.filter((f) => !f.probe || f.probe.video);
      if (!ok.length) return toast('اختر ملف فيديو', 'bad');
      const batch = ok.length > 1 ? taskId() : '';
      start(ok.map((f) => ({ id: taskId(), op: 'convert', title: f.name, inputs: [ref(f)], params: { target }, batch })), results);
    };
    page.append(fb.el,
      h('div', { class: 'card' }, h('div', { class: 'opt-label' }, 'الصيغة الجديدة'),
        chips(VIDEO_TARGETS.map((x) => ({ value: x, label: x.toUpperCase() })), target, (v) => { target = v; setPref({ convert: v }); sync(); }, 'c-teal'),
        h('p', { class: 'hint' }, 'MP4 الأوسع توافقاً. MKV يقبل كل الترميزات. WEBM للويب. AVI للأجهزة القديمة.'), notes),
      btn);
    sync();
  } });
}

// ---------- استخراج الصوت وتحويله ----------
export function openAudio(ctx, preset = [], seg0 = null) {
  toolPage(ctx, { title: 'استخراج الصوت', color: 'c-violet', icon: 'audio', intro: 'من أي فيديو أو بين صيغ الصوت: MP3 وM4A وAAC وWAV وFLAC وOPUS، مع التحكم بالجودة ومعدل العينة.', render: (page, results) => {
    const files = preset.filter((f) => f.kind !== 'image').map((f) => ({ ...f }));
    const pr = prefs();
    const st = { format: pr.audioFormat || getSettings().audio || 'mp3', kbps: pr.audioKbps ?? 0, rate: pr.audioRate ?? 0 };
    const quality = h('div');
    const notes = h('div');
    const btn = h('button', { id: 'run', class: 'btn grad c-violet wide' });
    const lossless = () => st.format === 'wav' || st.format === 'flac';
    const sync = () => {
      btn.disabled = !files.length;
      btn.replaceChildren(ic('audio'), files.length > 1 ? `استخرج ${count(files.length, W.file)} بصيغة ${st.format.toUpperCase()}` : `استخرج بصيغة ${st.format.toUpperCase()}`);
      notes.replaceChildren(...files.filter((f) => f.probe?.audio).map((f) => h('p', { class: 'hint' }, h('b', { dir: 'auto' }, f.name), ' — ',
        audioCopy(st.format, f.probe.audio.codec, { kbps: lossless() ? 0 : st.kbps, sampleRate: st.rate }) ? 'نسخ الصوت كما هو دون إعادة ترميز' : 'تحويل الصوت')),
      ...files.filter((f) => f.probe && !f.probe.audio).map((f) => h('p', { class: 'err-text' }, h('b', { dir: 'auto' }, f.name), ' لا يحتوي صوتاً')));
    };
    const drawQuality = () => quality.replaceChildren(
      lossless() ? h('p', { class: 'hint' }, `${st.format.toUpperCase()} بلا فقد في الجودة؛ لا يُختار له معدل بت، والحجم أكبر.`)
        : h('div', {}, h('div', { class: 'opt-label' }, 'الجودة (معدل البت)'), chips([{ value: 0, label: 'تلقائي' }, ...[96, 128, 192, 256, 320].map((k) => ({ value: k, label: `${k}` }))], st.kbps,
          (v) => { st.kbps = v; setPref({ audioKbps: v }); sync(); }, 'c-violet'),
          h('p', { class: 'hint' }, 'بالكيلوبت في الثانية. 128 يكفي للكلام، و192 للموسيقى، و320 أعلى جودة. «تلقائي» ينسخ الصوت كما هو إن أمكن.')),
      h('div', { class: 'opt-label' }, 'معدل العينة'),
      chips([{ value: 0, label: 'كما هو' }, { value: 22050, label: '22.05 ك.هرتز' }, { value: 44100, label: '44.1 ك.هرتز' }, { value: 48000, label: '48 ك.هرتز' }], st.rate,
        (v) => { st.rate = v; setPref({ audioRate: v }); sync(); }, 'c-violet'),
      h('p', { class: 'hint' }, '44.1 للموسيقى، و48 للفيديو. OPUS يعمل على 48 دائماً.'));
    const fb = filesBox({ kind: 'av', multiple: !seg0, files, onChange: () => { sync(); probeAll(files, () => { fb.draw(); sync(); }); } });
    probeAll(files, () => { fb.draw(); sync(); });
    btn.onclick = () => {
      const ok = files.filter((f) => !f.probe || f.probe.audio);
      if (!ok.length) return toast('اختر ملفاً فيه صوت', 'bad');
      const batch = ok.length > 1 ? taskId() : '';
      const params = { format: st.format, kbps: lossless() ? 0 : st.kbps, sampleRate: st.rate, ...(seg0 || {}) };
      start(ok.map((f) => ({ id: taskId(), op: 'audio', title: f.name, inputs: [ref(f)], params, batch })), results);
    };
    page.append(...[fb.el,
      seg0 && h('p', { class: 'hint' }, `من المقطع ${iso(timeField(seg0.start))} إلى ${iso(timeField(seg0.end))} فقط.`),
      h('div', { class: 'card' }, h('div', { class: 'opt-label' }, 'الصيغة'),
        chips(AUDIO_TARGETS.map((x) => ({ value: x, label: x.toUpperCase() })), st.format, (v) => { st.format = v; setPref({ audioFormat: v }); drawQuality(); sync(); }, 'c-violet'),
        h('p', { class: 'hint' }, 'MP3 يعمل في كل مكان. M4A وAAC أصغر بالجودة نفسها. OPUS الأصغر. WAV وFLAC بلا فقد.'),
        quality, notes),
      btn].filter(Boolean));
    drawQuality();
    sync();
  } });
}

// ---------- ضغط الفيديو ----------
const LEVELS = [
  { value: 'smart', label: 'ذكي', hint: 'يحسب ما تحتاجه دقة الفيديو فعلاً: أفضل توازن بين الحجم والجودة. يتجاوز الملف المضغوط أصلاً.' },
  { value: 'light', label: 'خفيف', hint: 'توفير نحو الربع، والفرق في الجودة لا يكاد يُرى.' },
  { value: 'medium', label: 'متوسط', hint: 'نحو نصف الحجم، وجودة جيدة جداً على شاشة الهاتف.' },
  { value: 'strong', label: 'قوي', hint: 'أصغر حجم ممكن مع الحفاظ على الوضوح؛ قد تظهر نعومة في المشاهد السريعة.' },
];

export function openCompress(ctx, preset = []) {
  toolPage(ctx, { title: 'ضغط الفيديو', color: 'c-teal', icon: 'compress', intro: 'يصغّر الحجم ويُبقي الدقة والأبعاد ونسبة العرض إلى الارتفاع ومعدل الإطارات كما هي، بالترميز عبر عتاد الهاتف حين يتوفر.', render: (page, results) => {
    const files = preset.filter((f) => f.kind === 'video').map((f) => ({ ...f }));
    const pr = prefs();
    const st = { level: pr.level || 'smart', codec: pr.codec || 'auto' };
    const hint = h('p', { class: 'hint' });
    const table = h('div', { class: 'plan' });
    const totals = h('div', { class: 'stats two' });
    const btn = h('button', { id: 'run', class: 'btn grad c-teal wide' });
    let token = 0;
    const replan = async () => {
      const my = ++token;
      hint.textContent = LEVELS.find((l) => l.value === st.level).hint;
      btn.disabled = !files.length;
      btn.replaceChildren(ic('compress'), files.length > 1 ? `اضغط ${count(files.length, W.file)}` : 'اضغط الفيديو');
      if (!files.length) { table.replaceChildren(); totals.replaceChildren(); return; }
      table.replaceChildren(h('div', { class: 'center' }, h('div', { class: 'spinner sm' })));
      const plans = await Promise.all(files.map((f) => Media.plan(f.uri, f.size, st.level, st.codec).then((p) => ({ f, p }), (e) => ({ f, e }))));
      // نتيجة طلب قديم وصلت متأخرة بعد تغيير الإعداد: تُهمل
      if (my !== token) return;
      let src = 0, est = 0;
      table.replaceChildren(...plans.map(({ f, p, e }) => {
        if (e) return h('div', { class: 'plan-row' }, h('b', { class: 'more', dir: 'auto' }, f.name), h('small', { class: 'err-text more' }, e.message));
        f.plan = p;
        const size = p.size || f.size;
        // الذكي يتجاوز المضغوط أصلاً، ولا يُعرض حجم متوقع أكبر من الأصل
        const skip = !p.worth && st.level === 'smart';
        const after = skip ? size : Math.min(p.estimate, size);
        src += size; est += after;
        const save = size > 0 ? Math.round((1 - after / size) * 100) : 0;
        return h('div', { class: 'plan-row' },
          h('b', { class: 'more', dir: 'auto' }, f.name),
          h('small', {}, `${resName(p.probe.video?.width, p.probe.video?.height) || ''} · ${fmtBytes(size)}${skip || save <= 0 ? '' : ` ← نحو ${fmtBytes(after)}`}`),
          !p.worth && st.level === 'smart' ? h('span', { class: 'tag warn' }, 'مضغوط أصلاً') : h('span', { class: `tag ${save > 0 ? 'ok' : 'warn'}` }, save > 0 ? `−${iso(`${save}%`)}` : 'لن يصغر'));
      }));
      const save = src > 0 ? Math.round((1 - est / src) * 100) : 0;
      const hw = plans.some(({ p }) => p?.hw);
      const hevc = plans.some(({ p }) => p?.hevc);
      totals.replaceChildren(
        h('button', { class: 'stat c-slate', type: 'button', onclick: () => explain('الحجم الأصلي', 'مجموع أحجام الملفات المختارة كما هي على الهاتف.') }, h('small', {}, 'الحجم الأصلي', ic('info')), h('b', {}, fmtBytes(src) || '—')),
        h('button', { class: 'stat c-teal', type: 'button', onclick: () => explain('الحجم المتوقع', `معدل البت المستهدف للصورة × المدة، مضافاً إليه الصوت. الترميز ${hw ? 'بعتاد الهاتف' : 'بالمعالج'}${hevc ? ' بصيغة H.265' : ' بصيغة H.264'}. الحجم الفعلي يظهر بعد الانتهاء وقد يختلف قليلاً حسب تفاصيل المشاهد.`) },
          h('small', {}, 'المتوقع بعد الضغط', ic('info')), h('b', {}, `${fmtBytes(est) || '—'}${save > 0 ? ` · −${iso(`${save}%`)}` : ''}`)));
    };
    const fb = filesBox({ kind: 'video', multiple: true, files, onChange: replan });
    btn.onclick = () => {
      if (!files.length) return toast('اختر فيديو', 'bad');
      const batch = files.length > 1 ? taskId() : '';
      start(files.map((f) => ({ id: taskId(), op: 'compress', title: f.name, inputs: [ref(f)], params: { level: st.level, codec: st.codec }, batch })), results);
    };
    page.append(fb.el,
      h('div', { class: 'card' },
        h('div', { class: 'opt-label' }, 'مستوى الضغط'),
        seg(LEVELS, st.level, (v) => { st.level = v; setPref({ level: v }); replan(); }, 'c-teal'),
        hint,
        h('div', { class: 'opt-label' }, 'الترميز'),
        chips([{ value: 'auto', label: 'تلقائي' }, { value: 'h264', label: 'H.264' }, { value: 'h265', label: 'H.265' }], st.codec, (v) => { st.codec = v; setPref({ codec: v }); replan(); }, 'c-teal'),
        h('p', { class: 'hint' }, 'H.264 يعمل على كل الأجهزة. H.265 أصغر بنحو الثلث بالجودة نفسها، وقد لا تشغّله الأجهزة القديمة جداً. «تلقائي» يختار H.265 إن كان عتاد الهاتف يدعمه.')),
      table, totals, btn);
    replan();
  } });
}

// ---------- المحرر: قص، ولقطة، وصوت مقطع ----------
export function openEditor(ctx, preset = []) {
  toolPage(ctx, { title: 'قص ولقطة', color: 'c-sky', icon: 'cut', intro: 'حدّد البداية والنهاية لحفظ مقطع أو استخراج صوته، أو التقط صورة من أي لحظة. القص السريع لا يعيد الترميز فيحفظ الجودة الأصلية.', render: (page, results) => {
    const files = preset.filter((f) => f.kind !== 'image').slice(0, 1).map((f) => ({ ...f }));
    const body = h('div', { class: 'editor' });
    const fb = filesBox({ kind: 'av', multiple: false, files, onChange: () => { body.replaceChildren(); load(); } });
    page.append(fb.el, body);

    async function load() {
      const f = files[0];
      if (!f) return;
      body.replaceChildren(h('div', { class: 'card center' }, h('div', { class: 'spinner' })));
      await probeAll(files, fb.draw);
      if (!f.probe) return body.replaceChildren(h('div', { class: 'card err-text' }, f.error || 'تعذّرت قراءة الملف'));
      const dur = f.probe.duration || 0;
      const isVideo = Boolean(f.probe.video);
      const st = { start: 0, end: dur, pos: 0, precise: false };
      const src = Media.src(f.uri);
      const v = f.probe.video;
      const ratio = v ? (v.rotation % 180 ? `${v.height} / ${v.width}` : `${v.width} / ${v.height}`) : '16 / 9';
      const video = isVideo && src ? h('video', { class: 'pv-video', src, controls: true, playsinline: true, preload: 'metadata', style: `aspect-ratio:${ratio}` }) : null;
      const audio = !isVideo && src ? h('audio', { src, controls: true, preload: 'metadata', class: 'pv-audio' }) : null;
      const posRange = h('input', { type: 'range', class: 'range', min: 0, max: dur, step: 0.1, value: 0, 'aria-label': 'الموضع الحالي' });
      const posLbl = h('b', {}, iso(timeField(0)));
      const startIn = h('input', { class: 'field time-field', id: 'start', inputmode: 'decimal', dir: 'ltr', value: timeField(0), 'aria-label': 'البداية' });
      const endIn = h('input', { class: 'field time-field', id: 'end', inputmode: 'decimal', dir: 'ltr', value: timeField(dur), 'aria-label': 'النهاية' });
      const err = h('p', { class: 'err-text', role: 'alert' });
      const sum = h('div', { class: 'stats two' });
      const media = video || audio;
      const setPos = (t) => {
        st.pos = Math.max(0, Math.min(dur, t));
        posRange.value = st.pos;
        posLbl.textContent = iso(timeField(st.pos));
      };
      posRange.addEventListener('input', () => { setPos(Number(posRange.value)); if (media) media.currentTime = st.pos; });
      media?.addEventListener('timeupdate', () => setPos(media.currentTime));
      // التحقق أثناء الكتابة دون إعادة كتابة الحقل (حتى لا تُمسح «1:» قبل «1:30»)
      const check = () => {
        const s = parseTime(startIn.value), e = parseTime(endIn.value);
        let msg = '';
        if (Number.isNaN(s)) msg = 'وقت البداية غير مفهوم. اكتب مثلاً 1:30 أو 90.';
        else if (Number.isNaN(e)) msg = 'وقت النهاية غير مفهوم. اكتب مثلاً 2:45.';
        else if (s >= e) msg = 'النهاية يجب أن تكون بعد البداية.';
        else if (e > dur + 0.05) msg = `النهاية بعد آخر الملف (${timeField(dur)}).`;
        startIn.classList.toggle('bad', Number.isNaN(s) || (!msg ? false : s >= e));
        endIn.classList.toggle('bad', Number.isNaN(e) || e > dur + 0.05 || (!Number.isNaN(s) && s >= e));
        err.textContent = msg;
        if (!msg) { st.start = s; st.end = Math.min(e, dur); }
        const len = Math.max(0, st.end - st.start);
        const est = dur > 0 ? f.size * len / dur : 0;
        sum.replaceChildren(
          h('div', { class: 'stat c-slate' }, h('small', {}, 'الأصل'), h('b', {}, `${iso(fmtDur(dur))} · ${fmtBytes(f.size) || '—'}`)),
          h('button', { class: 'stat c-sky', type: 'button', onclick: () => explain('حجم المقطع المتوقع', 'حجم الأصل × (مدة المقطع ÷ مدة الأصل). في القص السريع قد يبدأ المقطع قبل البداية المحددة بثانية أو اثنتين لأنه يبدأ من أقرب إطار مفتاحي.') },
            h('small', {}, 'المقطع', ic('info')), h('b', {}, `${iso(fmtDur(len))} · نحو ${fmtBytes(est) || '—'}`)));
        return !msg;
      };
      startIn.addEventListener('input', check);
      endIn.addEventListener('input', check);
      const fromPos = (input) => { input.value = timeField(st.pos); check(); };
      const go = (op, params, title) => {
        if (!check() && op !== 'frame') return toast(err.textContent, 'bad');
        start([{ id: taskId(), op, title: title || f.name, inputs: [ref(f)], params }], results);
      };
      body.replaceChildren(
        h('div', { class: 'card preview-card' },
          media || h('div', { class: 'pv-ph', style: `aspect-ratio:${ratio}` }, ic(isVideo ? 'video' : 'audio'), h('small', {}, isNative ? 'تعذّرت المعاينة' : 'المعاينة تعمل على الهاتف')),
          h('div', { class: 'pos-row' }, h('span', { class: 'muted' }, 'الموضع الحالي'), posLbl), posRange),
        h('div', { class: 'card' },
          h('div', { class: 'range-grid' },
            h('label', { class: 'time-lbl col' }, h('span', {}, 'البداية'), startIn, h('button', { class: 'btn sm ghost', id: 'start-here', onclick: () => fromPos(startIn) }, 'من الموضع الحالي')),
            h('label', { class: 'time-lbl col' }, h('span', {}, 'النهاية'), endIn, h('button', { class: 'btn sm ghost', id: 'end-here', onclick: () => fromPos(endIn) }, 'من الموضع الحالي'))),
          err,
          h('p', { class: 'hint' }, 'اكتب الوقت بالدقائق والثواني مثل 1:30، أو بالثواني مثل 90، وتُقبل الأرقام العربية والفاصلة ٫.'),
          isVideo && h('div', {}, h('div', { class: 'opt-label' }, 'طريقة القص'),
            seg([{ value: false, label: 'سريع' }, { value: true, label: 'دقيق' }], false, (x) => { st.precise = x; }, 'c-sky'),
            h('p', { class: 'hint' }, 'السريع لا يعيد الترميز فيحفظ الجودة الأصلية في ثوانٍ، ويبدأ من أقرب إطار مفتاحي. الدقيق يبدأ من اللحظة نفسها بإعادة ترميز بجودة قريبة جداً من الأصل.'))),
        sum,
        h('div', { class: 'tool-acts' },
          h('button', { id: 'do-trim', class: 'btn grad c-sky', onclick: () => go('trim', { start: st.start, end: st.end, precise: st.precise }) }, ic('cut'), 'احفظ المقطع'),
          h('button', { id: 'do-audio', class: 'btn grad c-violet', onclick: () => go('audio', { format: prefs().audioFormat || getSettings().audio || 'mp3', start: st.start, end: st.end }) }, ic('audio'), 'صوت المقطع'),
          isVideo && h('button', { id: 'do-frame', class: 'btn grad c-amber', onclick: () => go('frame', { at: st.pos, format: 'jpg' }, `${f.name} @ ${timeField(st.pos)}`) }, ic('image'), 'لقطة من الموضع')),
      );
      check();
    }
    load();
  } });
}

// ---------- دمج المقاطع ----------
export function openMerge(ctx, preset = []) {
  toolPage(ctx, { title: 'دمج المقاطع', color: 'c-amber', icon: 'merge', intro: 'يجمع عدة فيديوهات في ملف واحد بالترتيب الذي تختاره. المقاطع المتشابهة تُدمج دون إعادة ترميز، والمختلفة تُوحَّد على أبعاد الأول دون قص.', render: (page, results) => {
    const files = preset.filter((f) => f.kind === 'video').map((f) => ({ ...f }));
    const info = h('div', { class: 'stats two' });
    const mode = h('p', { class: 'hint' });
    const btn = h('button', { id: 'run', class: 'btn grad c-amber wide' });
    const sync = () => {
      const ready = files.filter((f) => f.probe);
      const dur = ready.reduce((n, f) => n + (f.probe.duration || 0), 0);
      const size = files.reduce((n, f) => n + (f.size || 0), 0);
      btn.disabled = files.length < 2;
      btn.replaceChildren(ic('merge'), files.length >= 2 ? `ادمج ${count(files.length, W.clip)}` : 'اختر مقطعين على الأقل');
      info.replaceChildren(
        h('div', { class: 'stat c-amber' }, h('small', {}, 'المدة الكلية'), h('b', {}, dur ? iso(fmtDur(dur)) : '—')),
        h('div', { class: 'stat c-slate' }, h('small', {}, 'مجموع الأحجام'), h('b', {}, fmtBytes(size) || '—')));
      mode.textContent = ready.length === files.length && files.length >= 2
        ? (canConcatCopy(ready.map((f) => f.probe)) ? 'المقاطع متشابهة: دمج سريع دون إعادة ترميز وبلا فقد في الجودة.' : `المقاطع مختلفة: يُعاد ترميزها على أبعاد الأول (${resName(ready[0].probe.video?.width, ready[0].probe.video?.height)}) مع حواف عند الحاجة، ويُستبدل الصوت المفقود بصمت حتى لا يختل التوقيت.`)
        : '';
    };
    const fb = filesBox({ kind: 'video', multiple: true, order: true, files, onChange: () => { sync(); probeAll(files, () => { fb.draw(); sync(); }); } });
    probeAll(files, () => { fb.draw(); sync(); });
    btn.onclick = () => {
      if (files.length < 2) return toast('اختر مقطعين على الأقل', 'bad');
      if (files.some((f) => f.probe && !f.probe.video)) return toast('الدمج للفيديو فقط؛ أزل الملفات الصوتية', 'bad');
      start([{ id: taskId(), op: 'merge', title: `${files[0].name} + ${count(files.length - 1, W.file)}`, inputs: files.map(ref), params: {} }], results);
    };
    page.append(fb.el, h('p', { class: 'hint' }, 'رتّب المقاطع بالأسهم؛ الأول يحدد أبعاد الناتج.'), info, mode, btn);
    sync();
  } });
}

// ---------- لوحة الأدوات ----------
export const TOOLS = [
  { id: 'convert', title: 'تحويل الصيغة', sub: 'MP4 · MKV · MOV · WEBM · AVI', icon: 'convert', color: 'c-teal', open: openConvert },
  { id: 'compress', title: 'ضغط الفيديو', sub: 'بالدقة والأبعاد نفسها', icon: 'compress', color: 'c-sky', open: openCompress },
  { id: 'editor', title: 'قص ولقطة', sub: 'مقطع أو صورة أو صوته', icon: 'cut', color: 'c-pink', open: openEditor },
  { id: 'merge', title: 'دمج المقاطع', sub: 'عدة فيديوهات في ملف', icon: 'merge', color: 'c-amber', open: openMerge },
  { id: 'audio', title: 'استخراج الصوت', sub: 'MP3 · M4A · WAV · FLAC…', icon: 'audio', color: 'c-violet', open: openAudio },
];

export function openTools(ctx) {
  ctx.open({ title: 'أدوات الوسائط', render: ({ page }) => {
    startTasks().catch(() => {});
    page.append(
      h('div', { class: 'hero c-teal' }, ic('tools', 'h-ic'), h('b', {}, 'أدوات الوسائط'),
        h('small', {}, 'حوّل واضغط وقص وادمج على هاتفك مباشرة، دون رفع أي ملف إلى الإنترنت. الأصل يبقى كما هو، والناتج في مجلد «معالَج».')),
      h('div', { class: 'gcards two tools-grid' }, TOOLS.map((t) => { const c = gcard({ color: t.color, icon: t.icon, title: t.title, sub: t.sub, onClick: () => t.open(ctx) }); c.id = `tool-${t.id}`; return c; })),
      h('p', { class: 'hint' }, 'العمليات تعمل في الخلفية واحدة بعد الأخرى، ويظهر تقدمها في الإشعارات وفي صفحة «التنزيلات».'),
    );
  } });
}

export const opName = (op) => OPS[op]?.name || op;

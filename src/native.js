// الجسر إلى طبقة Kotlin. خارج أندرويد (المتصفح والاختبارات) يعمل محرك وهمي بنفس الواجهة.
import { Capacitor, registerPlugin } from '@capacitor/core';
import { siteOf } from './links.js';

const native = Capacitor.isNativePlatform();
const Plugin = native ? registerPlugin('Sayl') : null;
const UpdaterPlugin = native ? registerPlugin('Updater') : null;
const StatusesPlugin = native ? registerPlugin('Statuses') : null;

export const isNative = native;

// ---------- المحرك الوهمي ----------
const mockJobs = new Map();
const listeners = { progress: [], state: [], shared: [] };
const fire = (ev, data) => listeners[ev].forEach((cb) => cb(data));
// محاكاة مشاركة رابط من تطبيق آخر في الاختبارات
if (typeof window !== 'undefined') window.__share = (text) => fire('shared', { text });
let mockCookies = false;
let mockChannel = 'stable';

function mockInfo(url) {
  // ?delay=ms يؤخر الرد، و?title=… يحدد العنوان: لمحاكاة ردين يصلان بغير ترتيب الطلب
  const delay = Number((url.match(/[?&]delay=(\d+)/) || [])[1] || 0);
  if (delay) return new Promise((r) => setTimeout(r, delay)).then(() => mockInfo(url.replace(/[?&]delay=\d+/, '')));
  if (/fail|unsupported/.test(url)) return Promise.reject(new Error('ERROR: Unsupported URL: ' + url));
  // منشور صور تيك توك: ?n= عدد الصور
  // الرابط المختصر يفضي إلى المنشور نفسه، فهويته واحدة
  const short = url.match(/vm\.tiktok\.com\/(\w+)/);
  const ph = short ? [null, 'travel.ar', '7000000000000000001'] : url.match(/tiktok\.com\/@([\w.]+)\/photo\/(\d+)/);
  if (ph) {
    const n = Number((url.match(/[?&]n=(\d+)/) || [])[1] || 6);
    return Promise.resolve({ json: JSON.stringify({ _type: 'album', id: ph[2], title: 'رحلة الشتاء\nصور من الجبل #سفر', uploader: ph[1], site: 'tiktok',
      webpage_url: `https://www.tiktok.com/@${ph[1]}/photo/${ph[2]}`, audio: 'https://example.invalid/music.mp3',
      entries: Array.from({ length: n }, (_, i) => ({ index: i + 1, urls: [`https://example.invalid/img/${i + 1}.jpg`], width: 1080, height: 1440 })) }) });
  }
  if (/login/.test(url)) return Promise.reject(new Error('ERROR: Sign in to confirm you’re not a bot'));
  if (/list=/.test(url)) {
    return Promise.resolve({ json: JSON.stringify({ _type: 'playlist', id: 'PL1', title: 'قائمة تجريبية', entries: [
      { id: 'p1', title: 'المقطع الأول', url: 'https://www.youtube.com/watch?v=p1', duration: 95 },
      { id: 'p2', title: '[Private video]', url: 'https://www.youtube.com/watch?v=p2', duration: null },
      { id: 'p3', title: 'المقطع الثالث', url: 'https://www.youtube.com/watch?v=p3', duration: 300 },
    ] }) });
  }
  const t = (url.match(/[?&]title=([^&]+)/) || [])[1];
  return Promise.resolve({ json: JSON.stringify({ id: 'demo1', title: t ? decodeURIComponent(t) : 'مقطع تجريبي', uploader: 'قناة تجريبية', duration: 245,
    thumbnail: '', webpage_url: url, subtitles: { ar: [{}], fr: [{}] }, automatic_captions: { 'en-orig': [{}], en: [{}], ar: [{}], de: [{}] }, formats: [{ height: 1080, vcodec: 'avc1' }, { height: 720, vcodec: 'avc1' }, { height: 360, vcodec: 'avc1' }] }) });
}

// ما يكتمل تنزيله يظهر في مكتبة المحرك الوهمي كما في MediaStore
function mockSaved(j, name) {
  const folder = j.path.split('/').slice(0, -1).join('/');
  const kind = j.kind === 'image' ? 'image' : j.kind === 'audio' || j.kind === 'music' ? 'audio' : 'video';
  mockLib.push({ id: `p:${j.path}`.toLowerCase(), uri: j.uri, name, size: kind === 'image' ? 350000 + (j.choice?.index || 0) * 1000 : 52428800, date: Date.now(),
    duration: kind === 'image' ? 0 : 245000, width: 1080, height: kind === 'image' ? 1440 : 1920, mime: kind === 'image' ? 'image/jpeg' : kind === 'audio' ? 'audio/mpeg' : 'video/mp4',
    kind, source: 'sayl', folder, owned: true });
  mfire('libraryChanged', {});
}

function mockReorder(id, target) {
  const ids = [...mockJobs.values()].filter((j) => ['queued', 'waiting_network', 'retrying'].includes(j.state)).sort((a, b) => a.order - b.order).map((j) => j.id);
  const i = ids.indexOf(id);
  if (i < 0) return;
  ids.splice(target(ids, i), 0, ids.splice(i, 1)[0]);
  ids.forEach((x, n) => { mockJobs.get(x).order = n; });
  ids.forEach((x) => fire('state', { ...mockJobs.get(x) }));
}

function mockRun(j) {
  const t = setInterval(() => {
    const cur = mockJobs.get(j.id);
    if (!cur || cur.state !== 'running' && cur.state !== 'queued') return clearInterval(t);
    // المجدول والمنتظر للشبكة لا يبدأ في المحرك الوهمي
    if (cur.scheduledAt > Date.now() || cur.wifiOnly || cur.hold) return;
    cur.state = 'running';
    cur.pct = Math.min(100, cur.pct + (cur.slow ? 2 : 25));
    cur.total = 52428800; cur.downloaded = Math.round(cur.total * cur.pct / 100);
    cur.speed = 1572864; cur.eta = Math.round((100 - cur.pct) / 25);
    // window.__failImages: أرقام صور تفشل في أول محاولة (لاختبار إعادة المتعثرة فقط)
    const failIdx = window.__failImages || [];
    if (cur.kind === 'image' && failIdx.includes(cur.choice?.index) && !cur.attempts) {
      clearInterval(t);
      Object.assign(cur, { state: 'failed', attempts: 1, error: 'HTTP Error 403: Forbidden', finishedAt: Date.now() });
      return fire('state', { ...cur });
    }
    if (cur.pct >= 100) {
      clearInterval(t);
      if (cur.kind === 'image' || cur.kind === 'music') {
        const name = cur.kind === 'music' ? 'صوت المنشور.mp3' : `${String(cur.choice.index).padStart(3, '0')}.jpg`;
        Object.assign(cur, { state: 'done', path: `Download/سيل/تيك توك/${cur.folder}/${name}`, uri: 'mock://' + cur.id, finishedAt: Date.now() });
        mockSaved(cur, name);
        return fire('state', { ...cur });
      }
      const dir = `Download/سيل/${siteOf(cur.url).folder}${cur.folder ? '/' + cur.folder : ''}`;
      Object.assign(cur, { state: 'done', path: `${dir}/${cur.title}.${cur.kind === 'audio' ? 'mp3' : 'mp4'}`, uri: 'mock://' + cur.id, finishedAt: Date.now() });
      // ترجمة ملفاً بجانب الفيديو
      const sub = cur.choice?.subs;
      if (sub && !sub.embed) cur.extras = JSON.stringify([{ lang: sub.lang, uri: `mock://sub/${cur.id}`, path: `${dir}/${cur.title}.${sub.lang}.srt` }]);
      mockSaved(cur, cur.path.split('/').pop());
      fire('state', { ...cur });
      mockChain(cur, dir);
    } else fire('progress', { ...cur });
  }, 150);
}

const mock = {
  info: ({ url }) => mockInfo(url),
  enqueue: ({ jobs }) => {
    let added = 0;
    for (const j of jobs) {
      const dup = [...mockJobs.values()].some((x) => (x.state === 'queued' || x.state === 'running') && x.url === j.url && JSON.stringify(x.args) === JSON.stringify(j.args));
      if (dup) continue;
      const job = { ...j, slow: /slow/.test(j.url), hold: /hold/.test(j.url), order: Date.now() + added, state: 'queued', pct: 0, speed: 0, eta: -1, error: null, path: null, uri: null, attempts: 0, createdAt: Date.now(), finishedAt: 0 };
      mockJobs.set(j.id, job); added++;
      mockRun(job);
    }
    return Promise.resolve({ added, skipped: jobs.length - added });
  },
  list: () => Promise.resolve({ jobs: [...mockJobs.values()].map((j) => ({ ...j })) }),
  cancel: ({ id }) => { const j = mockJobs.get(id); if (j) { j.state = 'canceled'; fire('state', { ...j }); } return Promise.resolve(); },
  pause: ({ id }) => { const j = mockJobs.get(id); if (j && ['queued', 'running'].includes(j.state)) { j.state = 'paused'; j.speed = 0; fire('state', { ...j }); } return Promise.resolve(); },
  resume: ({ id }) => { const j = mockJobs.get(id); if (j && j.state === 'paused') { j.state = 'queued'; fire('state', { ...j }); mockRun(j); } return Promise.resolve(); },
  move: ({ id, delta }) => { mockReorder(id, (ids, i) => Math.max(0, Math.min(ids.length - 1, i + delta))); return Promise.resolve(); },
  toTop: ({ id }) => { mockReorder(id, () => 0); return Promise.resolve(); },
  startNow: ({ id }) => { const j = mockJobs.get(id); if (j) { Object.assign(j, { scheduledAt: -1, wifiOnly: false, state: 'queued' }); fire('state', { ...j }); } return Promise.resolve(); },
  retry: ({ id }) => { const j = mockJobs.get(id); if (j) { Object.assign(j, { state: 'queued', pct: 0, error: null }); fire('state', { ...j }); mockRun(j); } return Promise.resolve(); },
  openUrl: ({ url }) => { window.__opened = url; return Promise.resolve(); },
  // الملفات التي «حُذفت يدوياً» في الاختبار: window.__gone
  exists: ({ uris }) => Promise.resolve({ exists: uris.map((u) => {
    const j = [...mockJobs.values()].find((x) => `mock://${x.id}` === u);
    return !(window.__gone || []).includes(u) && !(j && (window.__goneIndex || []).includes(j.choice?.index));
  }) }),
  remove: ({ id }) => { mockJobs.delete(id); return Promise.resolve(); },
  clearFinished: () => { for (const [k, j] of mockJobs) if (j.state === 'failed' || j.state === 'canceled') mockJobs.delete(k); return Promise.resolve(); },
  open: () => Promise.resolve(),
  share: () => Promise.resolve(),
  engineVersion: () => Promise.resolve({ version: '2026.10.10' }),
  components: () => Promise.resolve({ ytdlp: '2026.10.10', channel: mockChannel, ytdlpUpdatedAt: 0, server2: 'NewPipe Extractor 0.26.5', ffmpeg: true, aria2c: true }),
  setChannel: ({ channel }) => { mockChannel = channel; return Promise.resolve(); },
  engineUpdate: () => Promise.resolve({ status: 'ALREADY_UP_TO_DATE', version: '2026.10.10' }),
  setConfig: () => Promise.resolve(),
  abi: () => Promise.resolve({ abi: 'arm64-v8a' }),
  hasCookies: () => Promise.resolve({ value: mockCookies }),
  importCookies: () => { mockCookies = true; return Promise.resolve(); },
  clearCookies: () => { mockCookies = false; return Promise.resolve(); },
  takeSharedText: () => Promise.resolve({ text: null, paste: false }),
  requestTile: () => Promise.resolve({ result: 'unsupported' }),
  addListener: (ev, cb) => { listeners[ev]?.push(cb); return Promise.resolve({ remove() {} }); },
};

const P = Plugin || mock;

// يُستعمل في الاختبارات لمحاكاة فشل التنزيل
export const mockControl = { fail(id, error) { const j = mockJobs.get(id); if (j) { Object.assign(j, { state: 'failed', error }); fire('state', { ...j }); } } };

export const Sayl = {
  info: async (url) => JSON.parse((await P.info({ url })).json),
  enqueue: (jobs) => P.enqueue({ jobs }),
  list: async () => (await P.list()).jobs,
  cancel: (id) => P.cancel({ id }),
  pause: (id) => P.pause({ id }),
  resume: (id) => P.resume({ id }),
  move: (id, delta) => P.move({ id, delta }),
  toTop: (id) => P.toTop({ id }),
  startNow: (id) => P.startNow({ id }),
  retry: (id) => P.retry({ id }),
  remove: (id, deleteFile = false) => P.remove({ id, deleteFile }),
  exists: async (uris) => (await P.exists({ uris })).exists,
  openUrl: (url) => P.openUrl({ url }),
  clearFinished: () => P.clearFinished(),
  open: (id) => P.open({ id }),
  share: (id) => P.share({ id }),
  engineVersion: async () => (await P.engineVersion()).version,
  engineUpdate: () => P.engineUpdate(),
  components: () => P.components(),
  setChannel: (channel) => P.setChannel({ channel }),
  setConfig: (cfg) => P.setConfig(cfg),
  abi: async () => (await P.abi()).abi,
  hasCookies: async () => (await P.hasCookies()).value,
  importCookies: () => P.importCookies(),
  clearCookies: () => P.clearCookies(),
  // ما فُتح به التطبيق: نص مشارَك، أو طلب «نزّل المنسوخ» من البلاطة أو الاختصار
  takeLaunch: async () => { const r = await P.takeSharedText(); return { text: r.text || null, paste: Boolean(r.paste) }; },
  onProgress: (cb) => { P.addListener('progress', cb); P.addListener('state', cb); },
  onShared: (cb) => P.addListener('shared', (e) => cb(e.text)),
  onPasteRequest: (cb) => P.addListener('paste', () => cb()),
  // إضافة بلاطة «نزّل المنسوخ» إلى الإعدادات السريعة (أندرويد 13+)
  requestTile: async () => (await P.requestTile()).result,
};

// الحافظة: في المتصفح والاختبارات تُقرأ من window.__clip
export const Clip = {
  read: async () => {
    if (!native) return String(window.__clip ?? '');
    const { Clipboard } = await import('@capacitor/clipboard');
    return (await Clipboard.read()).value || '';
  },
  write: async (text) => {
    if (!native) { window.__clip = text; return; }
    const { Clipboard } = await import('@capacitor/clipboard');
    await Clipboard.write({ string: text });
  },
};

// تحديث التطبيق نفسه: التنزيل والتحقق من الحزمة والتوقيع ثم التثبيت (Updater.kt)
const mockUpdater = {
  download: () => Promise.resolve({ ok: true }),
  install: () => Promise.resolve({ status: 'started' }),
  status: () => Promise.resolve({ ready: false }),
  addListener: () => Promise.resolve({ remove() {} }),
};
const U = UpdaterPlugin || mockUpdater;
export const AppUpdater = {
  download: (url) => U.download({ url }),
  install: () => U.install(),
  status: async () => (await U.status()).ready,
  onProgress: (cb) => U.addListener('progress', cb),
};

// حالات واتساب (StatusPlugin.kt)؛ المحرك الوهمي فيه 4 حالات لواتساب، وواتساب أعمال غير مثبّت
const mockStatus = { granted: false, saved: new Set(), gone: new Set() };
export const mockStatusControl = { vanish: (name) => mockStatus.gone.add(name) };
// للاختبارات في المتصفح فقط
if (!native && typeof window !== 'undefined') window.__mockStatus = mockStatusControl;
const mockItems = [
  { name: 'a1.jpg', video: false, duration: 0 }, { name: 'a2.mp4', video: true, duration: 15 },
  { name: 'a3.jpg', video: false, duration: 0 }, { name: 'a4.mp4', video: true, duration: 42 },
];
const mockStatuses = {
  access: ({ app }) => Promise.resolve({ granted: app === 'wa' && mockStatus.granted, installed: app === 'wa' }),
  request: () => { mockStatus.granted = true; return Promise.resolve({ granted: true }); },
  list: ({ app }) => Promise.resolve({ items: app === 'wa' ? mockItems.map((i, n) => ({ ...i, size: 1e6, time: Date.now() - n * 6e5, thumb: null, saved: mockStatus.saved.has(`${app}:${i.name}`) })) : [] }),
  // «gone» = حالة اختفت أثناء الحفظ (تنتهي بعد 24 ساعة)
  save: ({ app, names }) => {
    const ok = names.filter((n) => !mockStatus.gone.has(n));
    ok.forEach((n) => mockStatus.saved.add(`${app}:${n}`));
    // ما اختفى لم يعد في القائمة عند إعادة قراءتها
    mockStatus.gone.forEach((n) => { const k = mockItems.findIndex((i) => i.name === n); if (k >= 0) mockItems.splice(k, 1); });
    return Promise.resolve({ saved: ok.length, failed: names.filter((n) => mockStatus.gone.has(n)).map((name) => ({ name, error: 'الحالة لم تعد موجودة' })) });
  },
  open: () => Promise.resolve(),
};
const ST = StatusesPlugin || mockStatuses;
export const Statuses = {
  access: (app) => ST.access({ app }),
  request: (app) => ST.request({ app }),
  list: async (app) => (await ST.list({ app })).items,
  save: (app, names) => ST.save({ app, names }),
  open: (app, name) => ST.open({ app, name }),
  // الصور المصغرة ملفات في ذاكرة التطبيق المؤقتة؛ WebView يقرؤها بهذا الرابط
  src: (path) => (path ? Capacitor.convertFileSrc(path) : null),
};

// ---------- أدوات الوسائط والمكتبة (MediaPlugin.kt) ----------
const MediaPlugin = native ? registerPlugin('Media') : null;

// المحرك الوهمي: مكتبة فيها فيديو وصوت وصور بأحجام ومدد مختلفة، وملفان متطابقان، وملف كبير
const MB = 1024 * 1024;
const DAY = 864e5;
const mockNow = Date.now();
const mkItem = (i, o) => ({ id: `p:${o.folder}/${o.name}`.toLowerCase(), uri: `mock://lib/${i}`, date: mockNow - i * DAY / 3, duration: 0, width: 0, height: 0, owned: o.source === 'sayl', ...o });
const mockLib = [
  mkItem(1, { name: 'رحلة إلى الجبل.mp4', size: 48 * MB, duration: 245000, width: 1920, height: 1080, mime: 'video/mp4', kind: 'video', source: 'sayl', folder: 'Download/سيل' }),
  mkItem(2, { name: 'محاضرة الفيزياء - الفصل الثالث كاملاً مع الأمثلة والتمارين المحلولة #تعليم #فيزياء.mp4', size: 412 * MB, duration: 3725000, width: 1280, height: 720, mime: 'video/mp4', kind: 'video', source: 'sayl', folder: 'Download/سيل/دروس' }),
  mkItem(3, { name: 'نشيد الصباح.mp3', size: 6.2 * MB, duration: 198000, mime: 'audio/mpeg', kind: 'audio', source: 'sayl', folder: 'Download/سيل' }),
  mkItem(4, { name: 'نشيد الصباح (1).mp3', size: 6.2 * MB, duration: 198000, mime: 'audio/mpeg', kind: 'audio', source: 'sayl', folder: 'Download/سيل' }),
  mkItem(5, { name: 'clip_2026_final_v2.webm', size: 22 * MB, duration: 61000, width: 1080, height: 1920, mime: 'video/webm', kind: 'video', source: 'sayl', folder: 'Download/سيل' }),
  mkItem(6, { name: 'حالة 1.jpg', size: 0.4 * MB, width: 1080, height: 1920, mime: 'image/jpeg', kind: 'image', source: 'sayl', folder: 'Download/سيل/حالات واتساب' }),
  mkItem(7, { name: 'VID_20261101_183022.mp4', size: 1460 * MB, duration: 912000, width: 3840, height: 2160, mime: 'video/mp4', kind: 'video', source: 'device', folder: 'DCIM/Camera' }),
  mkItem(8, { name: 'IMG_20261102_101500.jpg', size: 3.1 * MB, width: 4000, height: 3000, mime: 'image/jpeg', kind: 'image', source: 'device', folder: 'DCIM/Camera' }),
  mkItem(9, { name: 'تسجيل صوتي 12.m4a', size: 1.8 * MB, duration: 95000, mime: 'audio/mp4', kind: 'audio', source: 'device', folder: 'Recordings' }),
  mkItem(10, { name: 'old_movie.avi', size: 700 * MB, duration: 5400000, width: 640, height: 480, mime: 'video/x-msvideo', kind: 'video', source: 'folder', folder: 'Movies' }),
];
const mockMedia = { device: 'denied', folders: [], out: null, tasks: new Map(), listeners: { task: [], libraryChanged: [], dupProgress: [] } };
const mfire = (ev, d) => mockMedia.listeners[ev].forEach((cb) => cb(d));
const codecOf = (it) => ({ 'video/mp4': ['h264', 'aac'], 'video/webm': ['vp9', 'opus'], 'video/x-msvideo': ['mpeg4', 'mp3'], 'audio/mpeg': [null, 'mp3'], 'audio/mp4': [null, 'aac'] }[it.mime] || ['h264', 'aac']);
function mockProbe(uri) {
  const it = mockLib.find((x) => x.uri === uri) || { ...mockLib[0], uri };
  const [vc, ac] = codecOf(it);
  const dur = (it.duration || 60000) / 1000;
  const total = Math.round(it.size * 8 / dur);
  return {
    duration: dur, size: it.size, bitrate: total, format: it.mime,
    video: it.kind === 'video' ? { codec: vc, width: it.width, height: it.height, fps: 30, bitrate: Math.max(0, total - 128000), rotation: 0 } : null,
    audio: ac ? { codec: ac, sampleRate: 44100, channels: 2, bitrate: 128000 } : null, audioCount: ac ? 1 : 0, subtitleCount: 0,
  };
}
function mockPlan(uri, level) {
  const p = mockProbe(uri);
  const v = p.video;
  const px = v.width * v.height;
  const need = px * 30 * 0.055;
  const t = { smart: Math.min(v.bitrate * 0.7, need), light: v.bitrate * 0.75, medium: v.bitrate * 0.5, strong: v.bitrate * 0.3 }[level] ?? v.bitrate * 0.5;
  const vb = Math.max(Math.max(250000, px * 30 * 0.025), t);
  return { videoBps: Math.round(vb), audioBps: 128000, estimate: Math.round((vb + 128000) * p.duration / 8), worth: vb < v.bitrate * 0.9, hevc: true, hw: true, size: p.size, probe: p };
}
// خطوات تلقائية بعد التنزيل (قص ثم ضغط): الناتج بجانب الأصل، ومعرّف الخطوة ثابت
function mockChain(j, dir) {
  const steps = j.choice?.after;
  if (!steps?.length) return;
  const name = j.path.split('/').pop();
  const input = { uri: j.uri, name, size: 52428800, path: j.path, mime: j.kind === 'audio' ? 'audio/mpeg' : 'video/mp4' };
  mockStep(`${j.id}-${steps[0].op}`, steps, 0, input, { job: j.id, saveIn: dir }, j.title);
}
function mockStep(id, steps, i, input, extra, title) {
  if (mockMedia.tasks.has(id)) return;
  const s = steps[i];
  mockMediaApi.enqueue({ tasks: [{ id, op: s.op, title, inputs: [input], params: { ...s.params, ...extra, chain: { steps, i } } }] });
}
const SUFFIX = { convert: 'محوّل', audio: 'صوت', compress: 'مضغوط', trim: 'مقطع', merge: 'مدموج', frame: 'لقطة' };
function mockRunTask(t) {
  const iv = setInterval(() => {
    const cur = mockMedia.tasks.get(t.id);
    if (!cur || cur.state !== 'queued' && cur.state !== 'running') return clearInterval(iv);
    if ([...mockMedia.tasks.values()].some((x) => x.state === 'running' && x.id !== t.id)) return;
    if (/fail/.test(cur.inputs[0].name)) { clearInterval(iv); Object.assign(cur, { state: 'failed', error: 'الملف تالف أو غير مكتمل أو بصيغة غير مدعومة.', finishedAt: Date.now() }); return mfire('task', { ...cur }); }
    cur.state = 'running';
    cur.pct = Math.min(100, cur.pct + (/slow/.test(cur.inputs[0].name) ? 1 : 20));
    cur.stage = cur.op === 'compress' ? 'ضغط بالعتاد' : 'تحويل';
    cur.engine = cur.op === 'compress' ? 'hw' : 'copy';
    if (cur.pct >= 100) {
      clearInterval(iv);
      const src = cur.inputs[0];
      const ext = { audio: cur.params.format || 'mp3', frame: cur.params.format || 'jpg', convert: cur.params.target || 'mp4' }[cur.op] || 'mp4';
      const name = `${src.name.replace(/\.[^.]+$/, '')} (${SUFFIX[cur.op]}).${ext}`;
      const size = cur.op === 'compress' ? Math.round(cur.srcSize * 0.42) : cur.op === 'frame' ? 900000 : Math.round(cur.srcSize * 0.9);
      const folder = cur.params.saveIn || 'Download/سيل/معالَج';
      const out = { uri: `mock://out/${cur.id}`, name, size, path: `${folder}/${name}`, mime: ext === 'mp3' ? 'audio/mpeg' : ext === 'jpg' ? 'image/jpeg' : 'video/mp4' };
      Object.assign(cur, { state: 'done', pct: 100, stage: '', outputs: [out], outSize: size, finishedAt: Date.now() });
      mockLib.push(mkItem(0, { name, size, uri: out.uri, mime: out.mime, kind: out.mime.split('/')[0], source: 'sayl', folder, date: Date.now(), duration: 60000, width: 1920, height: 1080 }));
      // الجزء فقط: يُحذف الكامل ويشير سجل التنزيل إلى الجزء
      const job = mockJobs.get(cur.params.job);
      if (cur.params.deleteSource) {
        const i = mockLib.findIndex((x) => x.uri === src.uri);
        if (i >= 0) mockLib.splice(i, 1);
        if (job) { Object.assign(job, { path: out.path, uri: out.uri }); fire('state', { ...job }); }
      }
      mfire('libraryChanged', {});
      const ch = cur.params.chain;
      if (ch && ch.steps[ch.i + 1]) setTimeout(() => mockStep(`${cur.id}-${ch.steps[ch.i + 1].op}`, ch.steps, ch.i + 1, out, { job: cur.params.job, saveIn: cur.params.saveIn }, cur.title), 0);
    }
    mfire('task', { ...cur });
  }, 150);
}
const mockMediaApi = {
  pick: ({ multiple }) => Promise.resolve({ files: (window.__pick || [mockLib[0]]).slice(0, multiple ? 99 : 1).map((x) => ({ uri: x.uri, name: x.name, size: x.size, mime: x.mime, kind: x.kind })) }),
  probe: ({ uri }) => Promise.resolve(mockProbe(uri)),
  plan: ({ uri, level }) => Promise.resolve(mockPlan(uri, level)),
  caps: () => Promise.resolve({ hevc: true, hw: true }),
  enqueue: ({ tasks }) => {
    for (const t of tasks) {
      const task = { ...t, state: 'queued', pct: 0, stage: '', engine: '', outputs: [], error: null, srcSize: t.inputs.reduce((n, x) => n + (x.size || 0), 0), outSize: 0, createdAt: Date.now(), finishedAt: 0 };
      mockMedia.tasks.set(t.id, task);
      mfire('task', { ...task });
      mockRunTask(task);
    }
    return Promise.resolve({ added: tasks.length });
  },
  tasks: () => Promise.resolve({ tasks: [...mockMedia.tasks.values()].map((t) => ({ ...t })) }),
  cancelTask: ({ id }) => { const t = mockMedia.tasks.get(id); if (t && (t.state === 'queued' || t.state === 'running')) { Object.assign(t, { state: 'canceled', stage: '', finishedAt: Date.now() }); mfire('task', { ...t }); } return Promise.resolve(); },
  retryTask: ({ id }) => { const t = mockMedia.tasks.get(id); if (t) { Object.assign(t, { state: 'queued', pct: 0, error: null }); mfire('task', { ...t }); mockRunTask(t); } return Promise.resolve(); },
  removeTask: ({ id }) => { mockMedia.tasks.delete(id); return Promise.resolve(); },
  clearTasks: () => { for (const [k, t] of mockMedia.tasks) if (t.state !== 'queued' && t.state !== 'running') mockMedia.tasks.delete(k); return Promise.resolve(); },
  open: () => Promise.resolve(),
  share: () => Promise.resolve(),
  outDir: () => Promise.resolve({ label: mockMedia.out || 'Download/سيل/معالَج', custom: Boolean(mockMedia.out) }),
  chooseOutDir: () => { mockMedia.out = 'Movies/سيل'; return Promise.resolve({ label: mockMedia.out, custom: true }); },
  resetOutDir: () => { mockMedia.out = null; return Promise.resolve({ label: 'Download/سيل/معالَج', custom: false }); },
  library: ({ sources }) => Promise.resolve({ items: mockLib.filter((x) => sources.includes(x.source) && (x.source !== 'device' || mockMedia.device !== 'denied') && (x.source !== 'folder' || mockMedia.folders.length)).map((x) => ({ ...x })) }),
  thumb: () => Promise.resolve({}),
  duplicates: ({ items }) => {
    const by = new Map();
    for (const it of items) if (it.size > 0) by.set(it.size, [...(by.get(it.size) || []), it.uri]);
    return Promise.resolve({ groups: [...by.values()].filter((g) => g.length > 1) });
  },
  folders: () => Promise.resolve({ folders: mockMedia.folders.map((label) => ({ uri: `mock://tree/${label}`, label })) }),
  addFolder: () => { mockMedia.folders.push('Movies'); mfire('libraryChanged', {}); return Promise.resolve({ uri: 'mock://tree/Movies', label: 'Movies' }); },
  removeFolder: ({ uri }) => { mockMedia.folders = mockMedia.folders.filter((l) => `mock://tree/${l}` !== uri); return Promise.resolve(); },
  deviceAccess: () => Promise.resolve({ state: mockMedia.device }),
  requestDeviceAccess: () => { mockMedia.device = 'granted'; return Promise.resolve({ state: 'granted', blocked: false }); },
  openAppSettings: () => Promise.resolve(),
  deleteItems: ({ uris }) => { let n = 0; for (const u of uris) { const i = mockLib.findIndex((x) => x.uri === u); if (i >= 0) { mockLib.splice(i, 1); n++; } } mfire('libraryChanged', {}); return Promise.resolve({ deleted: n }); },
  renameItem: ({ uri, name }) => { const it = mockLib.find((x) => x.uri === uri); if (!it) return Promise.reject(new Error('الملف غير موجود')); it.name = name; return Promise.resolve({ uri, name }); },
  addListener: (ev, cb) => { mockMedia.listeners[ev]?.push(cb); return Promise.resolve({ remove() {} }); },
};
const M = MediaPlugin || mockMediaApi;

export const Media = {
  pick: async (opts = {}) => (await M.pick({ multiple: Boolean(opts.multiple), types: opts.types || ['video/*', 'audio/*'] })).files,
  probe: (uri, size) => M.probe({ uri, size }),
  plan: (uri, size, level, codec) => M.plan({ uri, size, level, codec }),
  caps: () => M.caps(),
  enqueue: (tasks) => M.enqueue({ tasks }),
  tasks: async () => (await M.tasks()).tasks,
  cancel: (id) => M.cancelTask({ id }),
  retry: (id) => M.retryTask({ id }),
  remove: (id, deleteOutputs = false) => M.removeTask({ id, deleteOutputs }),
  clear: () => M.clearTasks(),
  open: (uri, mime, name) => M.open({ uri, mime, name }),
  share: (uris) => M.share({ uris }),
  outDir: () => M.outDir(),
  chooseOutDir: () => M.chooseOutDir(),
  resetOutDir: () => M.resetOutDir(),
  library: async (sources) => (await M.library({ sources })).items,
  thumb: (uri, kind, stamp) => M.thumb({ uri, kind, stamp }),
  duplicates: async (items) => (await M.duplicates({ items: items.map((x) => ({ uri: x.uri, size: x.size })) })).groups,
  folders: async () => (await M.folders()).folders,
  addFolder: () => M.addFolder(),
  removeFolder: (uri) => M.removeFolder({ uri }),
  deviceAccess: async () => (await M.deviceAccess()).state,
  requestDeviceAccess: () => M.requestDeviceAccess(),
  openAppSettings: () => M.openAppSettings(),
  deleteItems: (uris) => M.deleteItems({ uris }),
  rename: (uri, name) => M.renameItem({ uri, name }),
  onTask: (cb) => M.addListener('task', cb),
  onLibraryChanged: (cb) => M.addListener('libraryChanged', cb),
  onDupProgress: (cb) => M.addListener('dupProgress', cb),
  // معاينة الفيديو في الواجهة: رابط المحتوى عبر خادم Capacitor المحلي
  src: (uri) => (native && uri ? Capacitor.convertFileSrc(uri) : null),
};

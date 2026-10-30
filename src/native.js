// الجسر إلى طبقة Kotlin. خارج أندرويد (المتصفح والاختبارات) يعمل محرك وهمي بنفس الواجهة.
import { Capacitor, registerPlugin } from '@capacitor/core';

const native = Capacitor.isNativePlatform();
const Plugin = native ? registerPlugin('Sayl') : null;
const UpdaterPlugin = native ? registerPlugin('Updater') : null;
const StatusesPlugin = native ? registerPlugin('Statuses') : null;

export const isNative = native;

// ---------- المحرك الوهمي ----------
const mockJobs = new Map();
const listeners = { progress: [], state: [], shared: [] };
const fire = (ev, data) => listeners[ev].forEach((cb) => cb(data));
let mockCookies = false;
let mockChannel = 'stable';

function mockInfo(url) {
  if (/fail|unsupported/.test(url)) return Promise.reject(new Error('ERROR: Unsupported URL: ' + url));
  if (/login/.test(url)) return Promise.reject(new Error('ERROR: Sign in to confirm you’re not a bot'));
  if (/list=/.test(url)) {
    return Promise.resolve({ json: JSON.stringify({ _type: 'playlist', id: 'PL1', title: 'قائمة تجريبية', entries: [
      { id: 'p1', title: 'المقطع الأول', url: 'https://www.youtube.com/watch?v=p1', duration: 95 },
      { id: 'p2', title: '[Private video]', url: 'https://www.youtube.com/watch?v=p2', duration: null },
      { id: 'p3', title: 'المقطع الثالث', url: 'https://www.youtube.com/watch?v=p3', duration: 300 },
    ] }) });
  }
  return Promise.resolve({ json: JSON.stringify({ id: 'demo1', title: 'مقطع تجريبي', uploader: 'قناة تجريبية', duration: 245,
    thumbnail: '', webpage_url: url, formats: [{ height: 1080, vcodec: 'avc1' }, { height: 720, vcodec: 'avc1' }, { height: 360, vcodec: 'avc1' }] }) });
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
    if (cur.pct >= 100) {
      clearInterval(t);
      Object.assign(cur, { state: 'done', path: `Download/سيل/${cur.folder ? cur.folder + '/' : ''}${cur.title}.${cur.kind === 'audio' ? 'mp3' : 'mp4'}`, uri: 'mock://' + cur.id, finishedAt: Date.now() });
      fire('state', { ...cur });
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
  retry: ({ id }) => { const j = mockJobs.get(id); if (j) { Object.assign(j, { state: 'queued', pct: 0, error: null }); mockRun(j); } return Promise.resolve(); },
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
  takeSharedText: () => Promise.resolve({ text: null }),
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
  takeSharedText: async () => (await P.takeSharedText()).text,
  onProgress: (cb) => { P.addListener('progress', cb); P.addListener('state', cb); },
  onShared: (cb) => P.addListener('shared', (e) => cb(e.text)),
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
const mockStatus = { granted: false, saved: new Set() };
const mockItems = [
  { name: 'a1.jpg', video: false, duration: 0 }, { name: 'a2.mp4', video: true, duration: 15 },
  { name: 'a3.jpg', video: false, duration: 0 }, { name: 'a4.mp4', video: true, duration: 42 },
];
const mockStatuses = {
  access: ({ app }) => Promise.resolve({ granted: app === 'wa' && mockStatus.granted, installed: app === 'wa' }),
  request: () => { mockStatus.granted = true; return Promise.resolve({ granted: true }); },
  list: ({ app }) => Promise.resolve({ items: app === 'wa' ? mockItems.map((i, n) => ({ ...i, size: 1e6, time: Date.now() - n * 6e5, thumb: null, saved: mockStatus.saved.has(i.name) })) : [] }),
  save: ({ names }) => { names.forEach((n) => mockStatus.saved.add(n)); return Promise.resolve({ saved: names.length }); },
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

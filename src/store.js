// الإعدادات في localStorage، مع حصر كل قيمة في حدودها حتى لا يكسر ملف تالف التطبيق
const KEY = 'sayl.settings';
const META = 'sayl.meta';

export const DEFAULTS = Object.freeze({
  theme: 'auto', fontScale: 1, video: 'best', audio: 'mp3', kbps: 192, parallel: 1, aria2: false, clipboard: true,
  connections: 4, retries: 5, window: Object.freeze({ on: false, from: '01:00', to: '07:00' }),
});
const VIDEO = ['best', 2160, 1440, 1080, 720, 480, 360];

const read = (k) => { try { const v = JSON.parse(localStorage.getItem(k) || '{}'); return v && typeof v === 'object' ? v : {}; } catch { return {}; } };
const clamp = (n, lo, hi, d) => (Number.isFinite(Number(n)) ? Math.min(hi, Math.max(lo, Number(n))) : d);
const oneOf = (v, list, d) => (list.includes(v) ? v : d);
const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/;
const time = (v, d) => (HHMM.test(String(v)) ? String(v) : d);

function sanitize(s) {
  return {
    theme: oneOf(s.theme, ['auto', 'light', 'dark'], DEFAULTS.theme),
    fontScale: clamp(s.fontScale, 0.85, 1.3, DEFAULTS.fontScale),
    video: oneOf(s.video, VIDEO, DEFAULTS.video),
    audio: oneOf(s.audio, ['mp3', 'm4a'], DEFAULTS.audio),
    kbps: oneOf(Number(s.kbps), [128, 192, 320], DEFAULTS.kbps),
    parallel: Math.round(clamp(s.parallel, 1, 3, DEFAULTS.parallel)),
    aria2: typeof s.aria2 === 'boolean' ? s.aria2 : DEFAULTS.aria2,
    clipboard: typeof s.clipboard === 'boolean' ? s.clipboard : DEFAULTS.clipboard,
    connections: oneOf(Number(s.connections), [2, 4, 8], DEFAULTS.connections),
    retries: oneOf(Number(s.retries), [0, 3, 5, 10], DEFAULTS.retries),
    window: {
      on: Boolean(s.window?.on),
      from: time(s.window?.from, DEFAULTS.window.from),
      to: time(s.window?.to, DEFAULTS.window.to),
    },
  };
}

export const getSettings = () => sanitize({ ...DEFAULTS, ...read(KEY) });

export function setSettings(patch) {
  const s = sanitize({ ...getSettings(), ...patch });
  localStorage.setItem(KEY, JSON.stringify(s));
  return s;
}

// أوقات آخر فحص للتحديثات وما شابه
export const getMeta = () => read(META);
export const setMeta = (patch) => localStorage.setItem(META, JSON.stringify({ ...read(META), ...patch }));

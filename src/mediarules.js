// قواعد النسخ دون إعادة ترميز، مطابقة لـ MediaArgs.kt (اختبار وحدة يتحقق من تطابق الجدولين)،
// لتخبر الواجهة المستخدم مسبقاً: «سريع دون إعادة ترميز» أو «يحتاج إعادة ترميز».
export const VIDEO_TARGETS = ['mp4', 'mkv', 'mov', 'webm', 'avi'];
export const AUDIO_TARGETS = ['mp3', 'm4a', 'aac', 'wav', 'flac', 'opus'];

export const V_OK = {
  mp4: ['h264', 'hevc', 'mpeg4', 'av1', 'vp9'],
  mov: ['h264', 'hevc', 'mpeg4', 'mjpeg', 'prores'],
  mkv: null,
  webm: ['vp8', 'vp9', 'av1'],
  avi: ['h264', 'mpeg4', 'mjpeg', 'msmpeg4v3'],
};
export const A_OK = {
  mp4: ['aac', 'mp3', 'ac3', 'eac3', 'opus', 'alac', 'flac'],
  mov: ['aac', 'mp3', 'ac3', 'alac', 'pcm_s16le'],
  mkv: null,
  webm: ['opus', 'vorbis'],
  avi: ['mp3', 'ac3', 'pcm_s16le'],
};

const ok = (table, target, codec) => Boolean(codec) && (table[target] == null || table[target].includes(codec));

// copy | audio | encode
export function videoMode(target, probe) {
  if (!ok(V_OK, target, probe?.video?.codec)) return 'encode';
  if (probe?.audio && !ok(A_OK, target, probe.audio.codec)) return 'audio';
  return 'copy';
}

const SAME = { m4a: 'aac', aac: 'aac', mp3: 'mp3', opus: 'opus', flac: 'flac', wav: 'pcm_s16le' };
export const audioCopy = (format, codec, { kbps = 0, sampleRate = 0 } = {}) => !kbps && !sampleRate && SAME[format] === codec;

export function canConcatCopy(probes) {
  if (probes.length < 2) return false;
  const [f] = probes;
  if (!f.video) return false;
  return probes.every((p) => p.video && p.video.codec === f.video.codec && p.video.width === f.video.width && p.video.height === f.video.height
    && (p.video.rotation || 0) === (f.video.rotation || 0) && Boolean(p.audio) === Boolean(f.audio)
    && (!p.audio || (p.audio.codec === f.audio.codec && p.audio.sampleRate === f.audio.sampleRate && p.audio.channels === f.audio.channels)));
}

export const MODE_LABEL = { copy: 'سريع: تغيير الحاوية دون إعادة ترميز', audio: 'سريع: الصورة كما هي ويُعاد ترميز الصوت فقط', encode: 'يحتاج إعادة ترميز الصورة (أبطأ)' };

// الدقة بالاسم الشائع: 1920×1080 → «1080p»
export function resName(w, h) {
  const s = Math.min(w, h);
  if (!s) return '';
  const k = [[2160, '4K'], [1440, '1440p'], [1080, '1080p'], [720, '720p'], [480, '480p'], [360, '360p']].find(([x]) => s >= x * 0.95);
  return k ? k[1] : `${s}p`;
}

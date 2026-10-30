// اختيار نوع التنزيل وجودته: فيديو بدقة، أو صوت بصيغة
import { h, seg, chips } from '../ui.js';
import { HEIGHTS, buildArgs } from '../formats.js';
import { getSettings } from '../store.js';

const RES = { 2160: '4K', 1440: '1440p', 1080: '1080p', 720: '720p', 480: '480p', 360: '360p' };

// heights: الدقات المتاحة في المقطع (فارغة لقائمة التشغيل = كل الدقات)
export function choicePicker({ heights = [], type } = {}) {
  const s = getSettings();
  const max = heights.length ? heights[0] : Infinity;
  // ما فوق أعلى دقة في المقطع لا معنى له؛ ما دونها يختار yt-dlp أقرب دقة أقل منها
  const avail = HEIGHTS.filter((x) => x <= max * 1.1);
  const state = {
    type: type || 'video',
    height: s.video === 'best' || avail.includes(s.video) ? s.video : 'best',
    audio: s.audio,
    kbps: s.kbps,
  };
  const body = h('div', { class: 'choice' });
  const draw = () => {
    body.replaceChildren();
    if (state.type === 'video') {
      body.append(h('div', { class: 'opt-label' }, 'الدقة'), chips(
        [{ value: 'best', label: 'أفضل' }, ...avail.map((x) => ({ value: x, label: RES[x] }))],
        state.height, (v) => { state.height = v; }, 'c-sky'),
      h('p', { class: 'hint' }, 'كلما ارتفعت الدقة زاد الحجم. «أفضل» تختار أعلى دقة متاحة، و720p تكفي لشاشة الهاتف.'));
    } else {
      const val = state.audio === 'm4a' ? 'm4a' : state.kbps;
      body.append(h('div', { class: 'opt-label' }, 'الصيغة والجودة'), chips(
        [{ value: 128, label: 'MP3 ‏128' }, { value: 192, label: 'MP3 ‏192' }, { value: 320, label: 'MP3 ‏320' }, { value: 'm4a', label: 'M4A' }],
        val, (v) => { if (v === 'm4a') state.audio = 'm4a'; else { state.audio = 'mp3'; state.kbps = v; } }, 'c-violet'),
      h('p', { class: 'hint' }, 'MP3 يعمل على كل الأجهزة. M4A أسرع وبلا فقد في الجودة لأنه الصوت الأصلي دون تحويل.'));
    }
  };
  const el = h('div', { class: 'picker' },
    seg([{ value: 'video', label: 'فيديو', icon: 'video' }, { value: 'audio', label: 'صوت', icon: 'audio' }], state.type,
      (v) => { state.type = v; el.firstChild.className = `seg ${v === 'video' ? 'c-sky' : 'c-violet'}`; draw(); }, state.type === 'video' ? 'c-sky' : 'c-violet'),
    body);
  draw();
  return {
    el,
    kind: () => state.type,
    args: () => {
      const s2 = getSettings();
      const choice = state.type === 'video' ? { type: 'video', height: state.height } : { type: 'audio', format: state.audio, kbps: state.kbps };
      return buildArgs(choice, { aria2: s2.aria2, connections: s2.connections });
    },
  };
}

export const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 8);

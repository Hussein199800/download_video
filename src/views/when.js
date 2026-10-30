// متى يبدأ التنزيل: الآن أو في وقت محدد، ومع خيار «على الواي فاي فقط»
import { h, seg, switchRow } from '../ui.js';
import { nextAt } from '../schedule.js';

export function whenPicker() {
  const st = { later: false, time: '02:00', wifi: false };
  const time = h('input', { type: 'time', class: 'time-in', value: st.time, 'aria-label': 'وقت البدء', oninput: (e) => { st.time = e.target.value || st.time; } });
  const timeRow = h('div', { class: 'set-row', hidden: true }, h('span', {}, 'وقت البدء', h('small', {}, 'إن كان الوقت قد مضى اليوم يبدأ غداً.')), time);
  const el = h('div', { class: 'when' },
    h('div', { class: 'opt-label' }, 'متى يبدأ؟'),
    seg([{ value: false, label: 'الآن', icon: 'download' }, { value: true, label: 'في وقت محدد', icon: 'calendar' }], false,
      (v) => { st.later = v; timeRow.hidden = !v; }, 'c-indigo'),
    timeRow,
    switchRow('على الواي فاي فقط', 'ينتظر الاتصال بالواي فاي ليوفر باقة الهاتف.', false, (v) => { st.wifi = v; }));
  return { el, get: () => ({ scheduledAt: st.later ? nextAt(st.time) : 0, wifiOnly: st.wifi }) };
}

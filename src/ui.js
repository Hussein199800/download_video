// أدوات بناء الواجهة: عناصر DOM، وإشعار قصير، ونافذة سفلية، وبطاقات
import { icons } from './icons.js';

export const $ = (sel, root = document) => root.querySelector(sel);

// h('div', { class: 'x', onclick }, child1, 'نص', ...)
export function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else if (k === 'html') el.innerHTML = v;
    else if (k === 'class') el.className = v;
    else if (k in el && typeof v !== 'string') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat(Infinity)) if (c != null && c !== false) el.append(c.nodeType ? c : document.createTextNode(String(c)));
  return el;
}

export const ic = (name, cls = '') => h('span', { class: cls, html: icons[name] || '' });

let toastTimer;
export function toast(msg, kind = '') {
  let t = $('.toast');
  if (!t) document.body.append((t = h('div', { class: 'toast', role: 'status', 'aria-live': 'polite' })));
  t.className = `toast ${kind}`;
  t.textContent = msg;
  requestAnimationFrame(() => t.classList.add('show'));
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 2600);
}

// نافذة سفلية؛ تعيد دالة الإغلاق
export function sheet(...content) {
  const close = () => { m.remove(); document.removeEventListener('keydown', esc); };
  const esc = (e) => e.key === 'Escape' && close();
  const box = h('div', { class: 'sheet', role: 'dialog', 'aria-modal': 'true' }, ...content);
  const m = h('div', { class: 'modal', onclick: (e) => e.target === m && close() }, box);
  document.addEventListener('keydown', esc);
  document.body.append(m);
  return close;
}

export function confirmSheet({ title, text, yes = 'تأكيد', no = 'إلغاء', danger = false }) {
  return new Promise((resolve) => {
    let close;
    const done = (v) => { close(); resolve(v); };
    close = sheet(
      h('h3', {}, title), text && h('p', { class: 'muted' }, text),
      h('div', { class: 'row-btns' },
        h('button', { class: 'btn ghost', onclick: () => done(false) }, no),
        h('button', { class: `btn ${danger ? 'danger' : 'primary'}`, onclick: () => done(true) }, yes)),
    );
  });
}

// بطاقة ملونة في شبكة
export const gcard = ({ color, icon, title, sub, onClick }) =>
  h('button', { class: `gcard ${color}`, onclick: onClick }, ic(icon, 'g-ic'), h('b', {}, title), sub && h('small', {}, sub));

// بطاقة قائمة: أيقونة ملونة + عنوان + سطر شرح + سهم
export const lcard = ({ color, icon, title, sub, onClick, end }) =>
  h('button', { class: 'lcard', onclick: onClick },
    ic(icon, `l-ic ${color}`), h('span', { class: 'l-txt' }, h('b', {}, title), sub && h('small', {}, sub)),
    end ?? ic('chevL', 'l-go'));

export function seg(options, value, onChange, cls = '') {
  const box = h('div', { class: `seg ${cls}`, role: 'radiogroup' });
  for (const o of options) {
    box.append(h('button', {
      class: o.value === value ? 'on' : '', role: 'radio', 'aria-checked': String(o.value === value), 'data-v': String(o.value),
      onclick: (e) => {
        box.querySelectorAll('button').forEach((b) => { b.classList.toggle('on', b === e.currentTarget); b.setAttribute('aria-checked', String(b === e.currentTarget)); });
        onChange(o.value);
      },
    }, o.icon && ic(o.icon), o.label));
  }
  return box;
}

export function chips(options, value, onChange, cls = '') {
  const box = h('div', { class: `chips-sel ${cls}` });
  for (const o of options) {
    box.append(h('button', {
      class: o.value === value ? 'on' : '', disabled: o.disabled, 'data-v': String(o.value),
      onclick: (e) => { box.querySelectorAll('button').forEach((b) => b.classList.toggle('on', b === e.currentTarget)); onChange(o.value); },
    }, o.label));
  }
  return box;
}

// صف إعداد بمفتاح تشغيل
export const switchRow = (label, hint, checked, onChange) =>
  h('label', { class: 'set-row' }, h('span', {}, label, hint && h('small', {}, hint)),
    h('input', { type: 'checkbox', class: 'sw', checked, onchange: (e) => onChange(e.target.checked) }));

export const fmtDur = (s) => {
  if (!s && s !== 0) return '';
  s = Math.round(s);
  const hh = Math.floor(s / 3600), mm = Math.floor((s % 3600) / 60), ss = s % 60;
  const p = (n) => String(n).padStart(2, '0');
  return hh ? `${hh}:${p(mm)}:${p(ss)}` : `${mm}:${p(ss)}`;
};

export function fmtBytes(b) {
  if (!b || b < 0) return '';
  const u = ['ب', 'ك.ب', 'م.ب', 'ج.ب'];
  let i = 0;
  while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
  return `${b.toFixed(b < 10 && i ? 1 : 0)} ${u[i]}`;
}

// حجم بمنزلة عشرية واحدة من الميغابايت فما فوق: «23.4 م.ب»
function size1(b) {
  const u = ['ب', 'ك.ب', 'م.ب', 'ج.ب'];
  let i = 0;
  while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
  return `${b.toFixed(i >= 2 ? 1 : 0)} ${u[i]}`;
}

// «حُمّل 23.4 م.ب من 59.0 م.ب · بقي 35.6 م.ب · 2.1 م.ب/ث · 0:17»
export function progressLine(j) {
  if (!j.downloaded) return 'يبدأ…';
  const parts = [j.total > 0 ? `حُمّل ${size1(j.downloaded)} من ${size1(j.total)}` : `حُمّل ${size1(j.downloaded)}`];
  if (j.total > j.downloaded) parts.push(`بقي ${size1(j.total - j.downloaded)}`);
  if (j.speed > 0) parts.push(`${size1(j.speed)}/ث`);
  if (j.eta > 0) parts.push(fmtDur(j.eta));
  return parts.join(' · ');
}

export const fmtDate = (t) => (t ? new Date(t).toLocaleString('ar', { dateStyle: 'medium', timeStyle: 'short' }) : '');

// عدة روابط دفعة واحدة: رابط في كل سطر، وإعداد واحد للجميع
import { h, ic } from '../ui.js';
import { extractUrls, siteOf } from '../links.js';
import { choicePicker, uid } from './choice.js';
import { whenPicker } from './when.js';
import { enqueueJobs } from './home.js';

export function openBatch(ctx) {
  ctx.open({ title: 'عدة روابط', render: ({ page, go }) => {
    const picker = choicePicker({});
    const when = whenPicker();
    const count = h('span', { class: 'tag' }, '0 رابط');
    const add = h('button', { id: 'batch-add', class: 'btn primary wide', disabled: true }, ic('download'), 'أضف إلى الطابور');
    const area = h('textarea', { id: 'batch-text', class: 'search batch-in', rows: 7, dir: 'ltr', placeholder: 'https://…\nhttps://…' });
    const sync = () => {
      const n = extractUrls(area.value).length;
      count.textContent = `${n} رابط`;
      add.disabled = !n;
      add.replaceChildren(ic('download'), n ? `أضف ${n} إلى الطابور` : 'أضف إلى الطابور');
    };
    area.addEventListener('input', sync);
    add.onclick = async () => {
      add.disabled = true;
      const args = picker.args(), kind = picker.kind(), choice = picker.choice(), w = when.get();
      // العنوان الحقيقي يُعرف عند التنزيل؛ حتى ذلك الحين يظهر الرابط
      const jobs = extractUrls(area.value).map((url) => ({ id: uid(), url, title: url, kind, args, choice, thumb: '', site: siteOf(url).id, ...w }));
      const r = await enqueueJobs(jobs);
      if (r?.added) go('jobs'); else add.disabled = false;
    };
    page.append(
      h('div', { class: 'intro' }, h('p', {}, 'الصق عدة روابط، كل رابط في سطر، أو نصاً فيه روابط. المكرر يُحذف تلقائياً، ويُطبَّق الإعداد نفسه على الجميع.')),
      h('div', { class: 'card' }, h('div', { class: 'card-h' }, h('h2', {}, 'الروابط'), count), area),
      h('div', { class: 'card' }, picker.el, when.el),
      add,
    );
  } });
}

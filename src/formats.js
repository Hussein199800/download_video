// ما الذي ننزّله: بناء أوامر yt-dlp من اختيار المستخدم، وتحليل معلومات الرابط.
// مسار الإخراج يضيفه Kotlin وحده، فلا تحتوي هذه الأوامر على -o أو تنفيذ أوامر.

// العنوان يُقصّ إلى 150 بايت ويُلحق به المعرّف حتى لا يتكرر الاسم
export const OUTPUT_TEMPLATE = '%(title).150B [%(id)s].%(ext)s';
export const HEIGHTS = [2160, 1440, 1080, 720, 480, 360];

export function buildArgs(choice, settings = {}) {
  const a = ['--no-playlist', '--windows-filenames', '--no-mtime', '--retries', String(settings.retries ?? 3)];
  if (choice.type === 'audio') {
    if (choice.format === 'm4a') a.push('-f', 'ba[ext=m4a]/ba', '-x', '--audio-format', 'm4a');
    else a.push('-f', 'ba/b', '-x', '--audio-format', 'mp3', '--audio-quality', `${choice.kbps || 192}K`);
    a.push('--embed-thumbnail', '--embed-metadata');
  } else {
    const h = Number(choice.height);
    a.push('-f', h > 0 ? `bv*[height<=${h}]+ba/b[height<=${h}]/b` : 'bv*+ba/b', '--merge-output-format', 'mp4', '--embed-metadata');
  }
  if (settings.aria2) a.push('--downloader', 'libaria2c.so');
  return a;
}

const UNAVAILABLE = /^\[(private|deleted) video\]$/i;

function entryUrl(e) {
  if (e.url && /^https?:/.test(e.url)) return e.url;
  if (e.webpage_url) return e.webpage_url;
  if (e.id && /youtube/i.test(e.ie_key || '')) return `https://www.youtube.com/watch?v=${e.id}`;
  return e.url || null;
}

export function parseInfo(j) {
  if (j._type === 'playlist' || Array.isArray(j.entries)) {
    const entries = (j.entries || []).filter(Boolean).map((e) => {
      const url = entryUrl(e);
      return {
        id: e.id, title: e.title || '', url, duration: e.duration || null,
        available: Boolean(e.title && url && !UNAVAILABLE.test(e.title)),
      };
    });
    return { kind: 'playlist', id: j.id, title: j.title || 'قائمة تشغيل', count: entries.length, entries };
  }
  const heights = [...new Set((j.formats || [])
    .filter((f) => f.height && f.vcodec !== 'none')
    .map((f) => f.height))].sort((x, y) => y - x);
  return {
    kind: 'single', id: j.id, title: j.title || 'بدون عنوان', uploader: j.uploader || j.channel || '',
    duration: j.duration || null, thumbnail: j.thumbnail || null, url: j.webpage_url || j.original_url || j.url, heights,
  };
}

// تطبيق الثيم وحجم الخط من الإعدادات
import { getSettings } from './store.js';
import { isNative } from './native.js';

export function applyTheme() {
  const s = getSettings();
  const dark = s.theme === 'dark' || (s.theme === 'auto' && matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  document.documentElement.style.setProperty('--fs', s.fontScale);
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', dark ? '#070d1d' : '#eef1f8');
  if (isNative) import('@capacitor/status-bar').then(({ StatusBar, Style }) => StatusBar.setStyle({ style: dark ? Style.Dark : Style.Light }).catch(() => {}));
}

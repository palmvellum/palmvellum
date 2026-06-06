/**
 * Lightweight i18n for the PalmVellum PWA.
 *
 * Wire-up:
 *   - import { t, currentLang, setLang } from '$lib/i18n.svelte';
 *   - in markup: {t('key.path')}
 *   - the lang choice is persisted to localStorage and read on boot;
 *     a Svelte 5 rune wrapper exposes the current dictionary so any
 *     component automatically re-renders when the user switches.
 *
 * Adding a translation: edit the three dictionaries below. Keys are
 * dotted strings ('nav.organizers'). Missing keys fall back to the
 * English string; missing in English shows the key itself so the gap
 * is obvious during development.
 */
import { browser } from '$app/environment';

import en from './i18n/en';
import zhTW from './i18n/zh-TW';
import zhCN from './i18n/zh-CN';
import ja from './i18n/ja';
import ko from './i18n/ko';
import ru from './i18n/ru';

export type Lang = 'en' | 'zh-TW' | 'zh-CN' | 'ja' | 'ko' | 'ru';

export const SUPPORTED_LANGUAGES: Array<{ code: Lang; label: string }> = [
  { code: 'en', label: 'English' },
  { code: 'zh-TW', label: '繁體中文' },
  { code: 'zh-CN', label: '简体中文' },
  { code: 'ja', label: '日本語' },
  { code: 'ko', label: '한국어' },
  { code: 'ru', label: 'Русский' },
];

const DICTIONARIES: Record<Lang, Record<string, string>> = {
  en,
  'zh-TW': zhTW,
  'zh-CN': zhCN,
  ja,
  ko,
  ru,
};

const STORAGE_KEY = 'palmvellum.lang';
const KNOWN_LANGS: ReadonlyArray<Lang> = [
  'en',
  'zh-TW',
  'zh-CN',
  'ja',
  'ko',
  'ru',
];

function detectInitial(): Lang {
  if (!browser) return 'en';
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored && (KNOWN_LANGS as ReadonlyArray<string>).includes(stored)) {
      return stored as Lang;
    }
  } catch {
    /* localStorage might be blocked in private mode */
  }
  const bl = (navigator?.language ?? 'en').toLowerCase();
  if (bl.startsWith('zh-tw') || bl.startsWith('zh-hk') || bl.startsWith('zh-hant')) return 'zh-TW';
  if (
    bl.startsWith('zh-cn') ||
    bl.startsWith('zh-sg') ||
    bl.startsWith('zh-hans') ||
    bl.startsWith('zh')
  )
    return 'zh-CN';
  if (bl.startsWith('ja')) return 'ja';
  if (bl.startsWith('ko')) return 'ko';
  if (bl.startsWith('ru')) return 'ru';
  return 'en';
}

// Svelte 5 rune store wrapper. Exporting an object so any importer
// reads the current value via `currentLang.value` (reactive).
class LangState {
  value = $state<Lang>(detectInitial());

  set(next: Lang) {
    this.value = next;
    if (browser) {
      try {
        localStorage.setItem(STORAGE_KEY, next);
      } catch {
        /* ignore */
      }
      // Also reflect on <html lang="..."> for assistive tech / SEO.
      document.documentElement.lang = next;
    }
  }
}

export const currentLang = new LangState();

export function setLang(next: Lang): void {
  currentLang.set(next);
}

/**
 * Read a translated string. Pass a dotted key; falls back to English,
 * then to the literal key string. Optional `vars` interpolate `{name}`
 * placeholders.
 */
export function t(key: string, vars?: Record<string, string | number>): string {
  const lang = currentLang.value;
  const dict = DICTIONARIES[lang] ?? DICTIONARIES.en;
  let str = dict[key] ?? DICTIONARIES.en[key] ?? key;
  if (vars) {
    for (const [k, v] of Object.entries(vars)) {
      str = str.replaceAll(`{${k}}`, String(v));
    }
  }
  return str;
}

/**
 * Local-only UI preferences — persisted to localStorage, exposed as
 * a Svelte 5 rune singleton so any component automatically re-renders
 * when the value changes.
 *
 * Kept separate from `auth.settings` (which is the per-user row in
 * Supabase). These are device-local choices: which day starts the
 * week, density tweaks, etc. — they don't need to round-trip the
 * server.
 */
import { browser } from '$app/environment';

const STORAGE_KEY = 'palmvellum.prefs.v1';

export type WeekStart = 0 | 1; // 0 = Sunday, 1 = Monday

interface PrefsBlob {
  weekStart: WeekStart;
}

const DEFAULTS: PrefsBlob = {
  weekStart: 1, // Monday — the original Palm OS default in HK
};

function read(): PrefsBlob {
  if (!browser) return { ...DEFAULTS };
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return { ...DEFAULTS };
    const parsed = JSON.parse(raw) as Partial<PrefsBlob>;
    return { ...DEFAULTS, ...parsed };
  } catch {
    return { ...DEFAULTS };
  }
}

function persist(blob: PrefsBlob): void {
  if (!browser) return;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(blob));
  } catch {
    /* localStorage might be blocked in private mode */
  }
}

class PrefsState {
  weekStart = $state<WeekStart>(read().weekStart);

  setWeekStart(v: WeekStart): void {
    this.weekStart = v;
    persist({ weekStart: this.weekStart });
  }
}

export const prefs = new PrefsState();

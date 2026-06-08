/**
 * Global reactive store for the left-side navigation drawer.
 *
 * Two modes:
 * - narrow (viewport < 700px): overlay, opens via hamburger, closes via
 *   backdrop / × / nav click. State is transient (per session).
 * - wide   (viewport >= 700px): docked side rail, open by default.
 *   The user can collapse it via the × in the rail header; that choice
 *   is persisted to localStorage so subsequent visits respect it.
 */

const STORAGE_KEY = 'palmvellum.drawer.collapsed.v1';
const WIDE_QUERY = '(min-width: 700px)';

function readCollapsed(): boolean {
  if (typeof localStorage === 'undefined') return false;
  return localStorage.getItem(STORAGE_KEY) === '1';
}

function writeCollapsed(v: boolean): void {
  if (typeof localStorage === 'undefined') return;
  try {
    if (v) localStorage.setItem(STORAGE_KEY, '1');
    else localStorage.removeItem(STORAGE_KEY);
  } catch {
    /* private mode / disabled storage — ignore */
  }
}

class DrawerState {
  /** wide-screen layout currently active */
  wide = $state(false);
  /** narrow-mode overlay open flag */
  overlayOpen = $state(false);
  /** wide-mode user pref — persisted */
  collapsed = $state(readCollapsed());

  /** True whenever any drawer surface is visible. */
  get visible(): boolean {
    return this.wide ? !this.collapsed : this.overlayOpen;
  }

  /** True whenever the drawer is rendered docked (no backdrop, content shifts). */
  get docked(): boolean {
    return this.wide && !this.collapsed;
  }

  toggle(): void {
    if (this.wide) {
      this.collapsed = !this.collapsed;
      writeCollapsed(this.collapsed);
    } else {
      this.overlayOpen = !this.overlayOpen;
    }
  }

  show(): void {
    if (this.wide) {
      this.collapsed = false;
      writeCollapsed(false);
    } else {
      this.overlayOpen = true;
    }
  }

  close(): void {
    if (this.wide) {
      this.collapsed = true;
      writeCollapsed(true);
    } else {
      this.overlayOpen = false;
    }
  }

  /** Wire the viewport listener. Safe to call multiple times. */
  initViewport(): void {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const mq = window.matchMedia(WIDE_QUERY);
    this.wide = mq.matches;
    const handler = (e: MediaQueryListEvent) => {
      this.wide = e.matches;
    };
    if (typeof mq.addEventListener === 'function') mq.addEventListener('change', handler);
    else mq.addListener(handler);
  }
}

export const drawer = new DrawerState();

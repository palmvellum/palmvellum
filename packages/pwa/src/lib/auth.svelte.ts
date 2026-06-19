/**
 * Auth state container — a reactive singleton subscribed to the
 * Supabase auth events. Components import { authState } and read
 * fields as if they were local $state.
 *
 * The .svelte.ts extension lets us use Svelte 5 runes outside .svelte
 * files.
 */

import { supabase } from './supabase';
import { sync } from './sync.svelte';
import { browser } from '$app/environment';
import { base } from '$app/paths';
import { db } from './db';

const SETTINGS_CACHE_KEY = 'palmvellum.settings_cache.v1';

function readCachedSettings(): UserSettings | null {
  if (!browser) return null;
  try {
    const raw = localStorage.getItem(SETTINGS_CACHE_KEY);
    return raw ? (JSON.parse(raw) as UserSettings) : null;
  } catch { return null; }
}
function writeCachedSettings(s: UserSettings | null): void {
  if (!browser) return;
  try {
    if (s) localStorage.setItem(SETTINGS_CACHE_KEY, JSON.stringify(s));
    else   localStorage.removeItem(SETTINGS_CACHE_KEY);
  } catch { /* ignore */ }
}

/** Race a promise against a timeout. Resolves to a sentinel on timeout. */
function withTimeout<T>(p: Promise<T>, ms: number): Promise<T | { __timedOut: true }> {
  return Promise.race([
    p,
    new Promise<{ __timedOut: true }>((resolve) =>
      setTimeout(() => resolve({ __timedOut: true }), ms),
    ),
  ]);
}

export type AuthPhase =
  | 'loading'
  | 'unauthenticated'
  | 'uninvited'
  | 'ready';

export interface UserSettings {
  api_mode: 'byok' | 'platform';
  preferred_provider: 'openai' | 'anthropic' | 'gemini';
  openai_secret_id: string | null;
  openai_model: string;
  anthropic_secret_id: string | null;
  anthropic_model: string;
  gemini_secret_id: string | null;
  gemini_model: string;
  ical_token: string | null;
  subscription_status: string;
  credits_remaining: number;
  credits_used_month: number;
  balance_micro_usd: number;
  palm_enrolled: boolean;
  hotsync_token_issued_at: string | null;
  palm_serial: string | null;
  palm_model: string | null;
  invited: boolean;
  timezone: string;
}

class AuthState {
  phase = $state<AuthPhase>('loading');
  email = $state<string | null>(null);
  userId = $state<string | null>(null);
  settings = $state<UserSettings | null>(null);

  /** Re-fetch user_settings from Supabase and recompute phase.
   *  Crucially: every exit path must leave `phase` in a terminal state
   *  (`ready` / `uninvited` / `unauthenticated`) so the UI never gets
   *  stuck rendering the loading skeleton.
   *
   *  Offline policy: if we have a cached settings blob from a previous
   *  successful sign-in AND we're offline (or the fetch times out), we
   *  trust the cache and mark phase='ready' immediately. This is what
   *  makes the app usable in airplane mode without re-login.
   */
  async refreshSettings(): Promise<void> {
    if (!this.userId) {
      this.settings = null;
      this.phase = 'unauthenticated';
      return;
    }

    // Fast path: pre-fill from cache so the UI can paint before the
    // network round-trip completes (or fails).
    const cached = readCachedSettings();
    if (cached) {
      this.settings = cached;
      this.phase = cached.invited ? 'ready' : 'uninvited';
    }

    // Race the network fetch against a 4-second timeout. On flaky
    // mobile networks we'd rather show the cached state than spin.
    // We wrap the PostgREST builder in Promise.resolve so the type is
    // a real Promise (the builder is only thenable).
    const fetchPromise = Promise.resolve(
      supabase
        .from('user_settings')
        .select('*')
        .eq('user_id', this.userId)
        .maybeSingle(),
    );
    const result = await withTimeout(fetchPromise, 4000);

    if ('__timedOut' in result) {
      // Offline / slow network. Stick with whatever the cache gave us;
      // if there was no cache, we're still in 'loading' from the
      // constructor — flip to 'uninvited' so the UI shows the holding
      // page rather than spinning forever.
      if (!cached) {
        this.settings = null;
        this.phase = 'uninvited';
      }
      return;
    }

    const { data, error } = result;
    if (error) {
      console.warn('[PalmVellum] user_settings fetch failed:', error.message);
      if (!cached) {
        this.settings = null;
        this.phase = 'uninvited';
      }
      return;
    }

    this.settings = (data as UserSettings | null) ?? null;
    writeCachedSettings(this.settings);
    if (!this.settings || !this.settings.invited) {
      this.phase = 'uninvited';
    } else {
      this.phase = 'ready';
    }
  }

  async init(): Promise<void> {
    if (!browser) return;

    const { data } = await supabase.auth.getSession();
    if (data.session?.user) {
      this.email = data.session.user.email ?? null;
      this.userId = data.session.user.id;
      await this.refreshSettings();
      // Boot the offline-first sync engine — it wires the Capacitor
      // Network plugin + window online/offline events, kicks off an
      // initial pull, and starts pushing any queued outbox items.
      // Safe to call repeatedly: re-init re-binds the listeners.
      // Fire-and-forget — sync.init does its own network detection and
      // pull. Awaiting could block the UI when offline.
      void sync.init(this.userId!);
    } else {
      this.phase = 'unauthenticated';
    }

    supabase.auth.onAuthStateChange(async (_event, session) => {
      if (session?.user) {
        this.email = session.user.email ?? null;
        this.userId = session.user.id;
        await this.refreshSettings();
        // Fire-and-forget — sync.init does its own network detection and
      // pull. Awaiting could block the UI when offline.
      void sync.init(this.userId!);
      } else {
        this.email = null;
        this.userId = null;
        this.settings = null;
        this.phase = 'unauthenticated';
        // Tear down the network listeners; the next sign-in will
        // re-init with the new user id.
        sync.destroy();
      }
    });
  }

  async signOut(): Promise<void> {
    writeCachedSettings(null);
    try { await db.delete(); } catch { /* ignore */ }
    await supabase.auth.signOut();
  }
}

export const authState = new AuthState();

/** Build the full callback URL for magic-link email links.
 *
 *  - Web: uses the current origin + SvelteKit base, so the link lands
 *    in the same deployment the user signed in from (Vercel preview,
 *    localhost, production).
 *  - Capacitor (Android): always uses the production web URL
 *    (https://tatliving.dev/palmvellum/app/). The AndroidManifest
 *    intent-filter on that path opens the app; the appUrlOpen
 *    listener in lib/capacitor.svelte.ts grabs the URL fragment and
 *    calls supabase.auth.setSession. Without this branch, the link
 *    would point at the WebView's local origin (app.palmvellum.local),
 *    which is invisible to Android's URL-matching layer and the
 *    email client refuses to follow it.
 */
export function magicLinkRedirect(): string {
  if (!browser) return '';
  // Detect Capacitor inline to avoid pulling the runtime into every
  // browser bundle on the plain-PWA build.
  const cap = (globalThis as { Capacitor?: { isNativePlatform?: () => boolean } }).Capacitor;
  if (cap?.isNativePlatform?.()) {
    // Custom scheme so Chrome cannot internally absorb the 302 — it
    // must fire an external Intent which our AndroidManifest catches
    // and routes back into the app via App.appUrlOpen.
    return 'palmvellum://auth';
  }
  return window.location.origin + base + '/';
}

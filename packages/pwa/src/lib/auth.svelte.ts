/**
 * Auth state container — a reactive singleton subscribed to the
 * Supabase auth events. Components import { authState } and read
 * fields as if they were local $state.
 *
 * The .svelte.ts extension lets us use Svelte 5 runes outside .svelte
 * files.
 */

import { supabase } from './supabase';
import { browser } from '$app/environment';
import { base } from '$app/paths';

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
   *  stuck rendering the loading skeleton. */
  async refreshSettings(): Promise<void> {
    if (!this.userId) {
      this.settings = null;
      this.phase = 'unauthenticated';
      return;
    }
    try {
      const { data, error } = await supabase
        .from('user_settings')
        .select('*')
        .eq('user_id', this.userId)
        .maybeSingle();
      if (error) {
        // Real error from PostgREST (RLS denial, network blip, etc).
        // Treat as uninvited so the user sees the holding screen
        // rather than an indefinite spinner.
        console.error('[PalmVellum] user_settings fetch failed:', error);
        this.settings = null;
        this.phase = 'uninvited';
        return;
      }
      this.settings = (data as UserSettings | null) ?? null;
      if (!this.settings || !this.settings.invited) {
        this.phase = 'uninvited';
      } else {
        this.phase = 'ready';
      }
    } catch (e) {
      console.error('[PalmVellum] user_settings fetch threw:', e);
      this.settings = null;
      this.phase = 'uninvited';
    }
  }

  async init(): Promise<void> {
    if (!browser) return;

    const { data } = await supabase.auth.getSession();
    if (data.session?.user) {
      this.email = data.session.user.email ?? null;
      this.userId = data.session.user.id;
      await this.refreshSettings();
    } else {
      this.phase = 'unauthenticated';
    }

    supabase.auth.onAuthStateChange(async (_event, session) => {
      if (session?.user) {
        this.email = session.user.email ?? null;
        this.userId = session.user.id;
        await this.refreshSettings();
      } else {
        this.email = null;
        this.userId = null;
        this.settings = null;
        this.phase = 'unauthenticated';
      }
    });
  }

  async signOut(): Promise<void> {
    await supabase.auth.signOut();
  }
}

export const authState = new AuthState();

/** Build the full callback URL for magic-link email links. */
export function magicLinkRedirect(): string {
  if (!browser) return '';
  return window.location.origin + base + '/';
}

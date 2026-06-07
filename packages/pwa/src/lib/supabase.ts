/**
 * Supabase client for the PWA.
 *
 * Uses the publishable key (frontend-safe). RLS prevents reads of
 * other users' rows. We never carry the secret key in the browser.
 *
 * Both values are baked into the build at vite time. For local dev
 * set VITE_SUPABASE_URL and VITE_SUPABASE_PUBLISHABLE_KEY in .env
 * (gitignored).
 */

import { createClient, type SupabaseClient } from '@supabase/supabase-js';

const url =
  import.meta.env.VITE_SUPABASE_URL ??
  'https://jrkwncplngmznfzzqwee.supabase.co';

const publishableKey =
  import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY ??
  'sb_publishable_UoFQ7p6EPTm0cbqimURGPQ_J1HO_aR-';

export const supabase: SupabaseClient = createClient(url, publishableKey, {
  auth: {
    persistSession: true,
    autoRefreshToken: true,
    detectSessionInUrl: true,
    // PKCE flow puts the auth code in the URL query string (?code=...)
    // instead of the fragment (#access_token=...). The fragment-based
    // ('implicit') flow does not work for Android Capacitor deep
    // links because Chrome strips the URL fragment when it fires an
    // Intent for a non-https scheme (palmvellum://). PKCE is also the
    // more secure flow — Supabase recommends it for mobile apps.
    flowType: 'pkce',
  },
  realtime: {
    params: {
      eventsPerSecond: 8,
    },
  },
});

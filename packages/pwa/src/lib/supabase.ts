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
    // We use the 6-digit OTP code flow as the primary sign-in path on
    // Android. The code is typed inside the app — no deep link, no
    // browser, no Chrome scheme dispatch. This is the most reliable
    // sign-in flow on phones. The magic-link variant is kept as a
    // desktop fallback in the email template.
    //
    // PKCE is retained as the OAuth flow type for any deep-link flows
    // that still surface (the verify URL still works for desktop users
    // who click the email link).
    flowType: 'pkce',
  },
  realtime: {
    params: {
      eventsPerSecond: 8,
    },
  },
});

/**
 * Capacitor runtime bridge.
 *
 *   - Detects whether the PWA is running inside the Android wrapper
 *     (`isCapacitor`, `platform`).
 *   - Hooks the system back button so it navigates the SvelteKit
 *     history stack instead of closing the app.
 *   - Listens for `appUrlOpen` deep links (magic-link callbacks from
 *     Supabase Auth) and converts the URL fragment into a real
 *     Supabase session via `supabase.auth.setSession`.
 *   - Tags `<html>` with `data-platform="android"` so a tiny CSS layer
 *     can switch on Material-style chrome (Roboto, taller hit
 *     targets, status-bar safe-area, bottom-nav layout).
 *
 * All optional — when run as a plain web PWA every guarded branch is
 * a no-op.
 */
import { browser } from '$app/environment';
import { Capacitor } from '@capacitor/core';
import { App } from '@capacitor/app';
import { supabase } from './supabase';

export const isCapacitor = browser && Capacitor.isNativePlatform();
export const platform: 'web' | 'android' | 'ios' = !browser
  ? 'web'
  : (Capacitor.getPlatform() as 'web' | 'android' | 'ios');

let initialized = false;

export async function initCapacitor(): Promise<void> {
  if (!browser || !isCapacitor || initialized) return;
  initialized = true;

  // 1. Style hook for the Material layer.
  document.documentElement.setAttribute('data-platform', platform);
  document.documentElement.classList.add(`is-${platform}`);

  // 2. Status-bar colour follows the dark palette.
  try {
    const { StatusBar, Style } = await import('@capacitor/status-bar');
    await StatusBar.setStyle({ style: Style.Dark });
    await StatusBar.setBackgroundColor({ color: '#1c1c1c' });
  } catch {
    /* status-bar plugin unavailable on some surfaces; ignore */
  }

  // 3. Deep-link handler — magic-link callback lands here.
  //    The Supabase email link redirects to:
  //      https://tatliving.dev/palmvellum/app/#access_token=...&refresh_token=...
  //    The AndroidManifest intent-filter catches it; Android wakes
  //    the app and delivers the full URL through `appUrlOpen`. We
  //    parse the hash, set the session, then route to /palm.
  App.addListener('appUrlOpen', async ({ url }) => {
    try {
      console.log('[Capacitor] appUrlOpen url=' + url);
      // Two flows to handle:
      //   PKCE (default):    palmvellum://auth?code=<auth_code>
      //                        -> supabase.auth.exchangeCodeForSession(code)
      //   Implicit (legacy): palmvellum://auth#access_token=...&refresh_token=...
      //                        -> supabase.auth.setSession({access_token, refresh_token})
      //
      // PKCE is what we use now — Chrome strips the URL fragment when
      // it dispatches an Intent for a non-https scheme, but query
      // parameters survive. The implicit branch is kept as a fallback
      // in case the legacy flow surfaces somewhere.
      const queryIndex = url.indexOf('?');
      const hashIndex = url.indexOf('#');
      const query = queryIndex >= 0
        ? url.slice(queryIndex + 1, hashIndex >= 0 ? hashIndex : undefined)
        : '';
      const fragment = hashIndex >= 0 ? url.slice(hashIndex + 1) : '';

      const parse = (s: string): Record<string, string> => {
        const out: Record<string, string> = {};
        for (const part of s.split('&')) {
          const eq = part.indexOf('=');
          if (eq < 0) continue;
          out[decodeURIComponent(part.slice(0, eq))] = decodeURIComponent(part.slice(eq + 1));
        }
        return out;
      };
      const q = parse(query);
      const f = parse(fragment);

      // PKCE — auth code in query string
      const code = q['code'];
      if (code) {
        console.log('[Capacitor] appUrlOpen: PKCE code received');
        const { error } = await supabase.auth.exchangeCodeForSession(code);
        if (error) {
          console.error('[Capacitor] exchangeCodeForSession failed', error.message);
          return;
        }
        window.location.replace('/palm');
        return;
      }

      // Implicit — tokens in fragment
      const access = f['access_token'] ?? '';
      const refresh = f['refresh_token'] ?? '';
      if (access && refresh) {
        const { error } = await supabase.auth.setSession({
          access_token: access,
          refresh_token: refresh,
        });
        if (error) {
          console.error('[Capacitor] setSession failed', error.message);
          return;
        }
        window.location.replace('/palm');
        return;
      }

      console.warn('[Capacitor] appUrlOpen: no code or tokens in URL');
    } catch (e) {
      console.error('[Capacitor] appUrlOpen handler threw', e instanceof Error ? e.message : String(e));
    }
  });

  // 4. Hardware back button: in-WebView history first, then minimize.
  App.addListener('backButton', async ({ canGoBack }) => {
    if (canGoBack) {
      window.history.back();
    } else {
      try {
        await App.minimizeApp();
      } catch {
        /* falls through to default which closes the app — acceptable */
      }
    }
  });
}

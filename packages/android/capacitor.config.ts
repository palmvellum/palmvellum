import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'dev.tatliving.palmvellum.organizers',
  appName: 'Palm Organizers',
  // The web app produced by:
  //   PUBLIC_BASE_PATH='' PUBLIC_RUNTIME=capacitor pnpm --filter @palmvellum/pwa build
  // The empty base path is critical — the bundled assets must reference
  // /_app/... (absolute) so the WebView serves them off the bundled
  // file:// → https://app.palmvellum.local mount point Capacitor sets up.
  webDir: '../pwa/build',

  android: {
    allowMixedContent: false,
    captureInput: true,
    webContentsDebuggingEnabled: true,
  },

  // No `server.url` — bundled assets are the source of truth. The
  // app launches without internet; Supabase requests fail gracefully
  // and the Dexie offline mirror handles reads.
  server: {
    // Capacitor's WebView serves bundled assets at the (https) scheme
    // + hostname below, so the WebView origin is stable and HTTPS-only.
    // Supabase / fetch / Service Workers all treat this as a normal
    // secure origin.
    androidScheme: 'https',
    hostname: 'app.palmvellum.local',
    cleartext: false,
  },
};

export default config;

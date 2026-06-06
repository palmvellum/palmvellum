import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'dev.tatliving.palmvellum.organizers',
  appName: 'Palm Organizers',
  // The web app produced by `pnpm --filter @palmvellum/pwa build`.
  // Capacitor packages that directory into the Android assets.
  webDir: '../pwa/build',
  // Treat the app as standalone: no in-WebView address bar / pull-to-refresh.
  android: {
    allowMixedContent: false,
    captureInput: true,
    webContentsDebuggingEnabled: true,
  },
  // Use the production deploy URL so deep links + magic-link emails
  // resolve from anywhere. The bundled webDir is the offline fallback.
  server: {
    url: 'https://tatliving.dev/palmvellum/app/',
    androidScheme: 'https',
    cleartext: false,
  },
};

export default config;

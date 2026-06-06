# Palm Organizers — Android companion app

Capacitor wrapper around the PalmVellum SvelteKit shell. Same Supabase
backend, same UI, packaged as a native Android APK / AAB.

## Status: scaffold

This directory is configuration only. The Capacitor toolchain, Android
Studio project, and Play Store assets land in a follow-up commit. The
intent of this scaffold is to lock the package name, app name, and
high-level approach early so the rest of the monorepo can refer to it.

## Approach

- **Package name:** `dev.tatliving.palmvellum.organizers`
- **App display name:** `Palm Organizers`
- **Wrapper:** [Capacitor 7](https://capacitorjs.com/) — runs the
  SvelteKit static build in a `WebView` on Android, with a thin native
  bridge for any platform features we end up needing (file picker for
  the Memo Pad upload flow, push registration, share-sheet target).
- **Source of UI:** the same `packages/pwa/build/` artifact deployed to
  `tatliving.dev/palmvellum/app/`. No fork.
- **Auth:** magic-link flow uses universal links so the email-link
  callback opens directly back in the app.
- **Offline:** the SvelteKit app's existing Dexie cache stays — Capacitor
  exposes IndexedDB unchanged.

## Build steps (future)

```sh
# install Capacitor + Android platform
pnpm add -D @capacitor/core @capacitor/cli @capacitor/android

# init (we'll lock these values into capacitor.config.ts)
pnpm cap init "Palm Organizers" dev.tatliving.palmvellum.organizers \
  --web-dir=../pwa/build

# build the web bundle then sync into the Android project
pnpm --filter @palmvellum/pwa build
pnpm cap add android
pnpm cap sync android
pnpm cap open android  # opens Android Studio
```

## Why Capacitor and not native Compose

- The Organizers dashboard is 80% lists, forms, and dialogs — UI we've
  already built once. Rewriting it in Compose would take weeks and yield
  the same screens.
- Capacitor lets us share one build with the web and reuse every Supabase
  realtime / RLS / Vault BYOK flow unchanged.
- If we ever need true native UI (e.g. a Wear OS quick-capture surface),
  we can add a parallel Compose target alongside this wrapper. The
  decision is reversible.

## Branding

| Where               | Asset / string                                          |
|---------------------|---------------------------------------------------------|
| App label           | `Palm Organizers`                                       |
| Package id          | `dev.tatliving.palmvellum.organizers`                   |
| Status bar / theme  | matches PWA (`#2d2d2d`)                                 |
| Splash              | PalmVellum yellow dot on charcoal                       |
| Play Store category | Productivity                                            |
| Target API          | 35 (Android 15), min 26 (Android 8 — covers ~95% of base) |

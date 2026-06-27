# `packages/mac-native` — PalmVellum for macOS (native SwiftUI)

A native macOS organizer that mirrors the PalmVellum PWA: **local-first**
(works fully offline), syncing to the same Supabase backend when online. Same
data model, same sync contract, same no-emoji Palm OS 5 silver theme as the
PWA and the Android native app.

## Stack

- **SwiftUI** app (macOS 12+), built with **Swift Package Manager** — no Xcode
  project required (Command Line Tools are enough). `swift build` / `swift test`.
- **GRDB** (SQLite) local store — the single source of truth.
- **supabase-swift** — email-OTP auth + PostgREST + Storage + Realtime.
- Two targets: `PalmKit` (testable core: models, store, sync) and `PalmVellum`
  (the SwiftUI app shell).

## Architecture (mirrors Android `packages/android-native`)

- **4 local tables**: `events`, `records` (type-discriminated: todo / contact /
  thought / sketch / expense / mail / calsub), `event_drafts`, and a local-only
  `conflicts` table. Every syncable row carries local-only `is_dirty`,
  `remote_updated_at`, `sync_state` columns; deletes are soft (`deleted_at`).
- **Sync engine** (`SyncEngine.swift`): `claim → pull → push`. Conflict
  detection is the 3-way `remote_updated_at` comparison in `SyncDecision.swift`
  (unit-tested). Conflicts surface in the Conflicts screen (keep mine / keep
  cloud).
- **AI is server-side BYOK** — the client never holds a key. It just writes
  `ai_status = pending` (or an `event_drafts` row) and the result arrives on the
  next pull. Flows: Memo/To-Do `(ai)` prefix, Date Book "Plan with AI".
- IDs: ULID + Java-`hashCode`-compatible deterministic IDs for cross-device
  de-dup (`DeterministicId.swift`), matching the PWA / Android / Go clients.

## Build & run

```bash
swift build          # debug build
swift test           # run the PalmKit test suite (uses swift-testing, no XCTest)
swift run PalmVellum  # launch (a proper .app bundle is preferred — see below)
```

## Package a signed .app + .dmg

```bash
bash scripts/build-app.sh
```

Produces `dist/PalmVellum.app` (ad-hoc signed) and a timestamped `.dmg` in
`~/Desktop/mac-mini-output`. **Ad-hoc signed = sideload, use at your own risk**
(same model as the Android debug APKs). For a Gatekeeper-clean / notarized
build you need full Xcode + an Apple Developer ID on the build Mac.

## Implemented (v1)

- Launcher + 7 organizers: Date Book (agenda / week / month, repeat expansion,
  dated to-dos as `(TO DO)`), To Do, Address, Memo, Note Pad (read-only sketch
  gallery), Expense, Mail (inbox + sources).
- Local-first offline CRUD (no login required).
- Opt-in cloud sync (email OTP), conflict detection + resolution UI.
- AI: `(ai)` Memo/To-Do, Date Book "Plan with AI" (auto-accept parsed events).
- Settings: account/sync, week-start, link to web BYOK settings.

## Also implemented (P4.5)

- Calendar subscriptions: Google iCal URL / `.ics` file import, deterministic
  dedup, periodic refresh (off / 6h / 12h / daily) + refresh-on-launch.
- Memo file upload (PDF / DOCX / image ≤20 MB) → `summarize-upload`.
- iCal feed: mint / revoke a secret URL to subscribe your calendar elsewhere.
- 6-language i18n with an in-app language switch (en / zh-TW / zh-CN / ja / ko /
  ru) covering the launcher, screen titles, common actions and filters.

## Remaining for full PWA parity (next pass)

- Deep i18n of organizer *form field* labels (the chrome is localized; the
  per-field micro-labels inside editors are still English).
- Airwallex credit top-up in Settings (BYOK keys/credits are linked to the web
  settings page for now).
- Notarization for a Gatekeeper-clean install (needs full Xcode + Developer ID).

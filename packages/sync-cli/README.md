# vellum-sync — PalmOS native-DB ↔ Supabase

Bridges PalmOS' stock Memo Pad and To Do List databases to the
PalmVellum cloud. Manual flow today (you back up the .pdb from the
emulator, run the CLI, install it back); the same code becomes the
sync core when task #14 grows a real Network HotSync server.

## Build

```sh
cd packages/sync-cli
make            # ./bin/vellum-sync
```

## Configure

```sh
cp .env.example .env
# fill in SUPABASE_SERVICE_ROLE_KEY (Supabase Dashboard → Settings → API)
```

`.env` is git-ignored.

## What syncs where

| PalmOS app | Cloud `records.type` | Identifier on Palm |
|------------|----------------------|--------------------|
| Memo Pad, category **AI** | `aiquery` | `device_id="memo:<24-bit hex uid>"` |
| Memo Pad, any other category | `thought` | same scheme |
| To Do List | `todo` (with metadata for due_date, priority, completed, notes) | `device_id="todo:<hex>"` |

The cloud's existing AI worker fires on aiquery insert and writes the
answer to `ai_response`. On the next pull, the response is appended
to the memo's body under a `— AI —` separator, so the user sees Q + A
together in MemoPad.

## Workflow (CloudpilotEmu or any Palm)

```
           ┌────────────────────────┐         ┌──────────────────────┐
           │  CloudpilotEmu         │         │  Supabase            │
           │  Memo Pad / To Do      │         │  records table       │
           └──────────┬─────────────┘         └──────────┬───────────┘
   ⋮ Database         │                                  │
   backup ↓           │   MemoDB.pdb / ToDoDB.pdb        │
                      ├────────────► push ─────────────► │  AI worker
                      │                                  │  writes
   ⋮ Install ↑        │   updated .pdb (with answers)    │  ai_response
                      │ ◄──────── pull ◄─────────────────┤
```

## Commands

```sh
# Combined one-step (recommended for AI Mode):
./vellum memo sync ~/Downloads/MemoDB.pdb    # push + wait 8s + pull (in-place)
./vellum todo sync ~/Downloads/ToDoDB.pdb    # push + pull (in-place)

# Or the individual halves:
./vellum memo push ~/Downloads/MemoDB.pdb    # Palm → cloud
./vellum memo pull -out ~/Downloads/MemoDB.pdb  # cloud → Palm
./vellum todo push ~/Downloads/ToDoDB.pdb
./vellum todo pull -out ~/Downloads/ToDoDB.pdb

# Utilities
./vellum inspect <any.pdb>                   # auto-detect MemoDB / ToDoDB
./vellum starter memo -out ~/Downloads/MemoDB.pdb   # empty MemoDB with
                                                      # categories incl. AI
./vellum starter todo -out ~/Downloads/ToDoDB.pdb
```

## First-time setup

If you don't have an AI category in MemoPad yet, generate and install
a starter:

```sh
./vellum starter memo -out ~/Downloads/MemoDB-starter.pdb
# Then in CloudpilotEmu: ⋮ → Install database → MemoDB-starter.pdb
```

This drops in a fresh MemoDB with categories Unfiled, Personal,
Business, **AI** ready to use. Existing memos are erased — so do this
ONLY before you have content you want to keep, or use it on a fresh
session.

## Idempotency + identity

Each Palm record carries a 24-bit unique ID assigned by the OS. Push
records that as `device_id = "memo:<hex>"` or `"todo:<hex>"`. Re-runs
match on `(user_id, device_id)` so they update rather than duplicate.

Cloud rows that have no `device_id` (e.g. PWA-originated) get one
assigned on first pull — the CLI both writes the new ID into the
generated PDB **and** backfills the cloud row with the same ID before
returning. So the very next push finds them and updates in place.

## Limitations (will be fixed when task #14 lands)

- Pull is destructive: the entire MemoDB/ToDoDB is regenerated from
  cloud state. Local edits between push and pull are lost. Do
  push-then-pull as a single `sync` action to minimize the window.
- Editing the question portion of an AI memo doesn't re-trigger the
  AI worker (`records_enqueue_ai` trigger fires on INSERT only). To
  get a fresh answer for an updated question, delete the memo on
  Palm and create a new one.
- No conflict resolution. If both sides changed the same record since
  the last sync, last-write-wins.
- Capacity: 65,535 records per PDB (PalmOS hard cap), single user.

## Package layout

```
cmd/vellum-sync/        flat subcommand dispatcher
internal/pdb/           Palm Database (.pdb) reader/writer with optional
                        AppInfo block
internal/memodb/        MemoDB record codec + categories AppInfo
internal/tododb/        ToDoDB record codec + categories AppInfo (reuses
                        memodb.AppInfo for the first 276 bytes)
internal/cloud/         Thin Supabase PostgREST client + ULID generator
internal/vellum/        Legacy VellumDB record codec — deprecated; kept
                        for archaeology only
```

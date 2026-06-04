# vellum-sync — manual Palm ↔ Supabase round-trip

The eventual story is **task #14**: a real Network HotSync server on
your Mac that speaks SLP / PADP / CMP / DLP to CloudpilotEmu, so one
tap of the HotSync icon does it all. Until that ships, this CLI is the
*manual* path that:

1. lets you test every other moving piece of PalmVellum today, and
2. owns the PDB read/write code that the future daemon will reuse
   verbatim.

## Build

```sh
cd packages/sync-cli
make            # produces ./bin/vellum-sync
```

## Configure

```sh
cp .env.example .env
# fill in SUPABASE_SERVICE_ROLE_KEY (Supabase Dashboard → Settings → API)
```

`.env` stays git-ignored.

## Workflow (CloudpilotEmu)

```
       ┌────────────────────────┐         ┌──────────────────────┐
       │  CloudpilotEmu         │         │  Supabase            │
       │  (Vellum.prc + ROM)    │         │  records table       │
       └──────────┬─────────────┘         └──────────┬───────────┘
   ⋮ Database     │                                  │
   backup ↓       │   VellumDB.pdb                   │
                  ├─────────────────► push ────────► │  AI worker
                  │                                  │  writes
   ⋮ Install ↑    │   new.pdb                        │  ai_response
                  │ ◄──────── pull ◄─────────────────┤
```

Commands:

```sh
./vellum inspect VellumDB.pdb        # decode and dump without touching cloud
./vellum push    VellumDB.pdb        # upsert each Palm record to Supabase
./vellum pull    -out new.pdb        # build fresh VellumDB.pdb from cloud
```

Both directions are idempotent — push matches by `(user_id, device_id)`
where `device_id = "palm:<24-bit hex of Palm record uniqueID>"`, and
pull re-uses that same uniqueID when writing the new PDB. Re-running
either command without changing the source is a no-op (push prints
"~ updated", pull just rewrites the same bytes).

## Demo (round-trip with AI answer)

```sh
# 1. On the emu: open Vellum, type "What was Steve Jobs famous quote
#    about Newton?", tap AI tab, save. A new record appears in the log
#    with status '.' (draft).
#
# 2. CloudpilotEmu → Emulator tab → ⋮ → Database backup → VellumDB →
#    save to ~/Downloads/VellumDB.pdb

./vellum push ~/Downloads/VellumDB.pdb
# → "+ aiquery uid=000003 ..."

# 3. Open https://tatliving.dev/palmvellum/app/ — your record appears
#    on the "hotsync with palm" tab. After ~3-5s the AI answer shows up
#    in the detail view (or list status changes from pending → done).
#
# 4. Pull it back:

./vellum pull -out ~/Downloads/new.pdb

# 5. CloudpilotEmu → ⋮ → Install database → ~/Downloads/new.pdb.
#    Confirm overwrite of VellumDB. Open Vellum → tap the AI row →
#    detail form shows the answer.
```

## What ships in this package

```
cmd/vellum-sync/        Cobra-free flat dispatcher + subcommands
internal/pdb/           Palm Database (.pdb) reader/writer
internal/vellum/        VellumDB record codec (matches vellum.c)
internal/cloud/         Thin Supabase PostgREST client + ULID gen
```

Tests live alongside (`go test ./...`). When the daemon (task #14)
lands, it imports `internal/pdb` and `internal/vellum` verbatim.

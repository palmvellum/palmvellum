# Plan — Memory Stick Card Sync (Sony CLIE, HotSync-free)

**Status:** proposed / not started. Captured 2026-06-18 from the project owner's
spec (previously discussed but never written down, so it was lost — this file is
the record).

## Goal

Let a Sony CLIE (and any Palm with card backup) sync to the PalmVellum cloud
**without HotSync**, using the Memory Stick as the transport:

1. On the handheld, use the built-in **backup to card** to dump databases to the
   Memory Stick.
2. Insert the card into the computer's reader. A resident program **detects the
   card mount and runs automatically**.
3. The program reads the card's databases — **DateBook, MemoPad, Address, ToDo,
   Expense, Mail** — and for each one **compares and syncs against Supabase
   records**.
4. **Conflict policy (interim): keep both.** When the same logical record differs
   on card vs cloud, preserve *both* copies. No destructive resolution yet; a
   real merge/resolution UX comes later.
5. The program **rewrites the merged `.pdb` files back onto the card**.
6. Put the card back in the handheld and use **restore from card** to load the
   new records into the device.

## Why this is attractive

Card backup/restore is plain file I/O on a FAT volume, so it **sidesteps the
entire HotSync transport problem** (serial/USB sidecar, still unbuilt — see
`cross-platform-desktop-sync-feasibility.md`). The pure-Go `.pdb` engine in
`packages/sync-cli` already reads and writes `.pdb` files on disk, so it is the
natural foundation: "the card folder" is just another directory of `.pdb` files.

## What exists today (grounding)

- `packages/sync-cli` — pure-Go `.pdb` parser/serializer (`internal/pdb`), plus
  conduits for **only two** databases: `internal/memodb` (MemoDB) and
  `internal/tododb` (ToDoDB). Cloud client (`internal/cloud/supabase.go`) pulls
  `type=in.(aiquery,thought,todo)` and is **destructive last-write-wins** on pull.
- So **4 of the 6 requested databases have no conduit yet**: DateBook, Address,
  Expense, Mail.
- DateBook/events and Mail already have cloud-side representations (events table,
  v05 mail). Address/Expense cloud tables need to be confirmed or added.

## Gaps to close

1. **New `.pdb` conduits** for DatebookDB, AddressDB, ExpenseDB, MailDB
   (decode/encode + AppInfo/category parsing), mirroring `memodb`/`tododb`.
2. **Non-destructive sync** — replace last-write-wins with a "keep both" merge:
   when card and cloud disagree on a record, write both into the regenerated
   `.pdb` and keep both in cloud (tag provenance so a later pass can dedupe).
3. **Cloud schema** — verify/extend Supabase tables + RLS for all six record
   kinds; widen the pull filter beyond `(aiquery,thought,todo)`.
4. **Card layout** — determine where CLIE card-backup writes the `.pdb`/`.prc`
   files (e.g. `/Palm/...` or `MSSONY/...`), and where restore reads them from.
   **Needs real-device confirmation.**
5. **Auto-run on insert** — OS-level volume-mount watcher (macOS: DiskArbitration
   / launchd `WatchPaths`; Linux: udev/udisks; Windows: Service + WMI). Start
   with macOS.

## Staged plan (de-risked)

1. **Card recon (real device).** Back up a CLIE to a Memory Stick, mount on Mac,
   record the exact folder layout and which `.pdb` files appear for each of the 6
   apps. Confirm restore reads from the same place. (Unblocks everything.)
2. **Manual one-DB round-trip.** Point existing `vellum-sync memo`/`todo` at the
   card's actual MemoDB/ToDoDB path; prove card→cloud→card works end-to-end and
   that the handheld restore accepts the rewritten file.
3. **"Keep both" merge.** Replace destructive pull with keep-both for Memo+ToDo
   first; validate no data loss across a divergent round-trip.
4. **New conduits.** Add Datebook → Address → Expense → Mail one at a time, each
   with its own decode/encode + cloud mapping; extend the cloud pull filter.
5. **Auto-run watcher (macOS first).** Resident agent that fires the sync on card
   mount, logs a report, and signals done (so the user knows when to eject).
6. **Later:** real conflict resolution (reuse the cloud `sync_conflicts` table +
   the Android client's resolution UI), then Linux/Windows watchers.

## Open questions

- Exact CLIE card-backup/restore folder + file naming (real-device recon).
- Do Address/Expense get first-class cloud tables, or ride in `records.metadata`?
- "Keep both" provenance: how to mark duplicates so a future dedupe/merge can act
  without re-cloning every round.
- Expense and Mail `.pdb` formats + AppInfo specifics (least-documented of the six).

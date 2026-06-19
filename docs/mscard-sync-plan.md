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

## Real-device test log — 2026-06-18 (Sony CLIE, Memory Stick)

First end-to-end run of Stages 1–2 against a real CLIE + USB card reader on
macOS, using the `packages/sync-cli` `vellum-sync` binary (service-role key,
single user `hello@tatliving.dev`).

### What worked

- **Card layout confirmed** (closes open question #4). Sony MS Backup writes
  full-device backups to:
  ```
  <MS root>/PALM/PROGRAMS/MSBackup/<N>/   # N = backup set; "0" was active, "1" empty
  ```
  with one `.pdb`/`.prc` per database: `MemoDB.pdb`, `ToDoDB.pdb`,
  `DatebookDB.pdb`, `AddressDB.pdb`, `MailDB.pdb`, etc. (No `ExpenseDB` on this
  unit.) Restore reads from the same folder.
- **Round-trip mechanics.** `vellum-sync memo sync` / `todo sync` pushed (empty
  CLIE records correctly skipped), pulled cloud state into a regenerated PDB
  (8 memos + 12 todos, full fidelity: categories, AI answers, due/priority/
  completed), backfilled device_ids. Written back to the card, the CLIE
  **restore-from-card loaded all memos + todos successfully.**

### Bugs found (real device)

1. **AppleDouble `._*` files cause a soft reset on restore.** Copying the PDBs
   onto the FAT card with `cp` made macOS write `._MemoDB.pdb` / `._ToDoDB.pdb`
   sidecars (extended-attribute forks). Sony restore treats them as databases →
   garbage → **soft reset**. (Memos/todos still loaded afterward, but the reset
   is unacceptable for end users.)
2. **Blank/`— AI —` separator line shows as garbage on the Palm.** Root cause is
   the same as #3: the separator `"\n— AI —\n"` uses an em-dash `—` (U+2014 =
   `e2 80 94`), which is not valid Big5, so CJKOS renders it as 亂碼. Confirmed in
   the PDB hex. (Line endings are clean `\n`; not a CRLF issue.)
3. **Chinese text is garbage on the Palm.** `memodb.EncodeMemos` writes
   `[]byte(m.Text)` — raw UTF-8 — straight into the record (memodb.go:192), with
   **no charset conversion anywhere in the repo**. The CLIE runs CJKOS with
   Traditional Chinese = **Big5**, so UTF-8 bytes render as 亂碼. `tododb` has the
   same gap (its own comment already flags "UTF-8 / Palm Latin-1").

### Fixes (fold into Stage 2 / the desktop sync engine)

- **Fix A — UTF-8 ⇄ Big5 codec (fixes #2 and #3; one root cause).**
  - Add `golang.org/x/text/encoding/traditionalchinese.Big5`.
  - New `internal/charset`: `ToPalm(string) []byte` (UTF-8→Big5, un-mappable
    rune → `?`) and `FromPalm([]byte) string` (Big5→UTF-8).
  - Wire into `memodb` (Encode/DecodeMemos), `tododb` (description + notes), and
    AppInfo category names (so Chinese category names work).
  - `AISeparator`: em-dash exists in Big5 (0xA156) so the encoder can map it;
    consider switching to ASCII `"\n-- AI --\n"` for robustness. **Decision
    pending.**
  - ⚠️ **Big5 is lossy** vs UTF-8 (emoji, simplified-only chars, most of
    Unicode). Cloud stays the UTF-8 source of truth; the card is a lossy view.
    Because push is last-write-wins, pushing a Big5-decoded card record back can
    degrade the cloud copy — accept for v1, revisit with the "keep both" merge.
- **Fix B — no AppleDouble on the card (fixes #1).**
  - Desktop write-back must use a plain byte write (`os.WriteFile`), not `cp`/
    copyfile — Go does not emit AppleDouble forks.
  - Before eject, sweep `._*` and `.DS_Store` from the backup dir (`dot_clean` /
    `rm -f`).

Both fixes are implementable in `sync-cli` today and re-testable on the same
CLIE before the engine is lifted into `mac-daemon`.

## End-user GUI build — status 2026-06-18 (Phases 0–6 landed)

The card-sync engine was lifted into a shared module and an end-user menu-bar
app was built on top. All Go builds/vets clean; unit + live-cloud integration
tests pass. What remains is hardware/identity-gated and left to the owner.

**Architecture**

- New shared module `packages/palm-engine` (tied via root `go.work`):
  `pdb`, `memodb`, `tododb`, `cloud`, plus new `charset` (Fix A, UTF-8⇄Big5),
  `cardio` (Fix B, no AppleDouble + Clean), and `sync` (the reusable
  push/pull/SyncCard engine, ported from the CLI and returning result structs).
- `cloud.Client` auth split into `apikey` + bearer: **anon/publishable key +
  per-user access_token**, so RLS scopes every call. service_role never ships.
- `packages/mac-daemon`:
  - `internal/auth` — GoTrue **passwordless** login (emailed 6-digit OTP code;
    the owner uses magic-link, never a password), session stored in the macOS
    Keychain (`go-keyring`), auto-refresh.
  - `internal/cardwatch` — polls `/Volumes`, finds the newest MS Backup set.
  - CLI: `login` (`--otp`) / `logout` / `whoami` / `sync <set-dir>` / `app`.
  - `app` — windowed **Fyne** desktop app: passwordless email-code login,
    auto-sync toggle, "Sync now", and a live sync log (`sync.SyncCardLog`
    streams progress). Replaced the earlier menu-bar/systray prototype because
    the owner wanted a real settings + progress window.
  - **Supabase config required:** the Magic Link email template must include
    `{{ .Token }}` so the 6-digit code appears in the email.
  - `packaging/` — `build-app.sh` (builds `PalmVellum.app`, optional
    codesign + notarize), `Info.plist` (LSUIElement menu-bar app), LaunchAgent.

**Decisions made during the build**

- AI separator switched to ASCII `-- AI --` (verified: 0 em-dash bytes in a live
  pull). Big5-unmappable runes → `?`. CRLF/CR normalised to LF.
- The end-user daemon does **not** run the local AI worker — AI runs server-side
  in the existing Edge Functions; the daemon is purely a card↔cloud bridge.
- No launcher shim in the bundle: `PalmVellum` would collide with the
  `palmvellum` binary on a case-insensitive volume, so the binary detects a
  bundle launch (argv contains `/Contents/MacOS/`) and defaults to `menubar`.

**Verified**

- `charset` round-trips Traditional Chinese + ASCII; em-dash never leaks as raw
  UTF-8; emoji → `?`; newline normalisation.
- `memodb`/`sync` round-trip Chinese memos through real `.pdb` bytes; blank
  memos skipped; `SyncCard` sweeps `._*` / `.DS_Store`.
- GoTrue endpoint + publishable key reachable (bad creds → 400, not 401).
- Live cloud pull post-refactor: 8 memos, ASCII separator, zero em-dash bytes.

**On-device flow + soft reset (resolved 2026-06-19)**

The on-device half uses the **Sony CLIE's built-in MS Backup** app: back up
to the Memory Stick, sync on the Mac, restore from the card. No custom
software on the Palm. After Fix B removes the AppleDouble droppings, the CLIE
still does a brief **soft reset** when restoring from card — confirmed by the
owner to be **expected and harmless**: records load normally and the device
keeps working. So it is accepted behaviour, not an outstanding bug.

**All applicable conduits landed (2026-06-19)**

Beyond Memo + To Do, the remaining conduits now exist in `palm-engine` and
are wired into `SyncCard`, each verified against the real CLIE card + live
Supabase:

- **Date Book** ⇄ `events` table (`datebookdb` + `DatebookPush/Pull`). Real
  Palm appointments decoded + pushed; 24 cloud events pulled back. Repeat
  rules are preserved byte-exact on round-trip but not yet translated to/from
  iCalendar RRULE (single-occurrence semantics on the cloud side).
- **Address** ⇄ `records.type='contact'` (`addressdb` + `AddressPush/Pull`).
  Phone-label packing, 19-field bitmap, 22 AppInfo labels; Big5 names verified
  (`Siu Ming 張`).
- **Mail** → Palm Inbox, one-way (`maildb` + `MailPull`): cloud digest records
  (`metadata.mail_subject` / `mail_source_name`) written as Inbox messages.
  12 digests verified.
- **Expense** and **Note Pad** are **not present on the Sony CLIE**, so they
  are out of scope (no DB on the device, nothing to sync).

Formats were taken from pilot-link (libpisock) rather than guessed. Codecs are
byte-stable on round-trip; the cloud→card encoders still want a final on-device
restore check by the owner.

**Left to the owner (hardware / identity gated)**

- Real login from the app (needs the account password — not available to CI).
- On-device restore of a Big5 memo on the CLIE (final visual confirmation).
- Menu-bar UI visual pass; app icon (`packaging/icon.icns` not yet supplied).
- Code-sign + notarize (`DEVELOPER_ID` / `AC_KEYCHAIN_PROFILE`).
- `/desktop` page download link points at a not-yet-published artefact.
- `sync-cli` still has its own copy of the push/pull logic; fold it onto
  `palm-engine/sync` later to avoid drift.

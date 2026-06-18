# Feasibility Study — Cross-Platform Desktop HotSync (Windows / macOS / Linux)

**Question.** Can a vintage Palm Pilot, when it docks and presses HotSync on
*any* of Windows, macOS, or Linux, automatically sync its data to and from the
PalmVellum cloud database?

**Short answer.** **Yes, and it is well within reach.** The hard 70% — Palm
database parsing, the two-way cloud contract, conflict storage, and the AI
worker — is already written in **portable Go with no native dependencies and no
CGO**, so it runs on all three platforms today. What is *not* yet built is the
one layer that is genuinely platform-shaped: the **HotSync transport** that
reads bytes off a docked Palm over serial/USB. That layer is a stub. This study
maps exactly what exists, what each OS demands of that missing layer, and a
staged plan to close the gap.

> Status of claims: codebase facts below are cited to `file:line` from this
> repo at the time of writing. Hardware/driver behaviour is from general
> knowledge of the Palm HotSync stack and **must be confirmed by real-device
> testing** — the project has so far validated only against CloudpilotEmu, never
> physical hardware (ROADMAP Phase 6, "Real-hardware HotSync validation on Palm
> IIIe" is unchecked).

---

## 1. What already exists (and is already portable)

### 1.1 `packages/sync-cli` — Palm `.pdb` ↔ Supabase, pure Go

- The `.pdb` (Palm database) parser is **hand-written Go over `encoding/binary`**,
  big-endian PalmOS 3.x/4.x layout — read *and* write — with **no pilot-link and
  no C bindings** (`internal/pdb/pdb.go`, `Read()` ~L79–153, `Write()` ~L161–228).
- It does **not** touch hardware. It reads `.pdb` files already on disk
  (`os.ReadFile(args[0])`, `cmd/vellum-sync/main.go:139,304,561`) and writes
  regenerated `.pdb` files back. The current human workflow is: back up the DB
  from CloudpilotEmu → run `vellum-sync` → re-import (`sync-cli/README.md:39–50`).
- Cloud contract (`internal/cloud/supabase.go`): PostgREST against
  `…/rest/v1/records`, upsert keyed on `(user_id, device_id)` where `device_id`
  is the Palm's own 24-bit record UID encoded `memo:<hex>` / `todo:<hex>`
  (idempotent across re-sorts). Maps Memo "AI" category → `type=aiquery`, other
  memos → `thought`, To Do → `todo`. Category/priority/due-date preserved in the
  `metadata` JSON column so round-trips survive.
- **Portability: 100%.** `go.mod` requires only Go 1.23, no CGO, no OS
  assumptions. This binary already cross-compiles to all three platforms.
- Known limits (`sync-cli/README.md:98–108`): pull is **destructive**
  (regenerates the whole DB, cloud wins, no merge); editing an AI memo's
  question does not re-fire the worker (`records_enqueue_ai` is INSERT-only);
  65,535 records/PDB cap; single user.

### 1.2 `packages/mac-daemon` — the resident bridge, ~95% portable

A pure-Go localhost service (no CGO, `modernc.org/sqlite`). Verified portable
pieces:

| Component | File | Portable? |
|---|---|---|
| Local SQLite cache (mirrors cloud schema) | `internal/store/sqlite.go` | ✅ pure Go |
| Supabase REST client (records, ai_queue, ai_usage, user_settings; RPC `resolve_hotsync_token`, `read_user_api_key`) | `internal/supa/client.go` | ✅ HTTPS only |
| AI / Oracle worker (polls `ai_queue`, calls OpenAI/Anthropic, writes back) | `internal/aiworker/worker.go` | ✅ HTTPS only |
| HTTP API (`127.0.0.1:7733`) | `internal/api/server.go` | ✅ stdlib |
| Config (XDG `~/.local/share/palmvellum/…` paths) | `internal/config/config.go` | ✅ POSIX-style |

The **only** macOS-specific touches found, all trivial:

1. OrbStack path probe `/Applications/OrbStack.app` — **diagnostic-only**, in the
   `doctor` subcommand (`cmd/palmvellum/main.go:149`).
2. `defaultDeviceID()` hardcodes a `"mac-"` label prefix (`config.go:90–96`) —
   cosmetic.
3. `SMAppService`/launchd registration — **mentioned in docs but not in code.**

### 1.3 The gap: `internal/hotsync/sidecar.go` is a stub

This is the whole crux of the question. Today:

- `AwaitCradle()`, `PullDB()`, `PushPRC()` all return canned errors; real mode
  is "not yet implemented; see issue #10 / #14" (`sidecar.go:50–72`).
- The stored `socketPath` field is **dead code** (`sidecar.go:30,35`).
- The intended design (ROADMAP + README) is a **separate `palm-sync` Node
  sidecar** speaking the HotSync protocol, with the Go daemon talking to it over
  a Unix socket. **No serial/USB/pilot-link code exists in the daemon at all** —
  which is good news for portability, because it means the platform-specific
  weight lives entirely in one swappable sidecar.

---

## 2. The real problem: getting bytes off the Palm, per OS

HotSync is a session the **Palm initiates** when the user presses the HotSync
button: the desktop runs a *listener*; the Palm dials in; they speak DLP
(Desktop Link Protocol) over PADP/SLP, carried on one of three transports:

- **Serial (RS-232)** — Palm III / IIIe / IIIx family (our reference tier).
  Today this means a **USB-to-serial adapter** (FTDI / Prolific / CH340).
- **USB** — m125/m130, Zire, later models. The cradle is a USB device the host
  must claim and drive with the Palm USB/visor protocol.
- **Network HotSync (TCP)** — the Palm syncs to an IP. This is the cleanest path
  and is the basis of the ROADMAP's ESP32 "Refresh Puck" idea (a box on the
  cradle that proxies HotSync over Wi-Fi, so no machine has to stay awake).

What each desktop OS demands of the transport layer:

| | Serial (USB-serial adapter) | USB cradle | Network HotSync |
|---|---|---|---|
| **Linux** | Best case. `/dev/ttyUSB*` via in-tree `ftdi_sio`/`pl2303`. | `libusb` direct; historically `visor` kmod. udev rule for non-root. | Pure TCP — trivial. |
| **macOS** | FTDI/CH34x drivers (vendor or in-kernel on recent macOS). Apple Silicon OK with signed drivers. | `libusb`; no Apple-signed Palm driver exists (Palm Desktop is dead/32-bit). | Pure TCP — trivial. |
| **Windows** | COM port via vendor driver (FTDI/Prolific/CH340 all ship Win drivers). | **Hardest:** needs WinUSB/libusb — usually a Zadig-installed driver. Legacy HotSync Manager is 32-bit and unreliable on modern Win. | Pure TCP — trivial. |

**Take-away:** *Serial and Network are easy and uniform across all three OSes;
raw USB cradles are the only genuinely fiddly path, and Windows is the fiddliest.*
A serial-first strategy (FTDI cable + Palm IIIe) de-risks the whole thing and
matches the hardware the project already calls its reference device.

---

## 3. Choosing the transport implementation

Three ways to actually speak HotSync. All can be made cross-platform; they
differ in effort and dependency weight.

**Option A — `palm-sync` (TypeScript HotSync stack) as a Node sidecar.**
This is what the ROADMAP already chose (issue #10). It is a modern
JS/TS implementation of the HotSync/DLP protocol with pluggable transports
(serial via `node-serialport`, USB, and network), runnable headless in Node on
all three OSes.
- *Pros:* one codebase covers Win/Mac/Linux; `node-serialport` ships prebuilt
  binaries per platform; aligns with the existing socket-sidecar design; the
  daemon stays pure Go.
- *Cons:* adds a Node runtime to the desktop install; **its cross-platform
  transport support (esp. raw USB on Windows) must be verified on real hardware**.
- *Verdict:* **recommended.** Lowest total effort, already the chosen direction.

**Option B — `pilot-link` (the classic C toolkit).**
Mature, proven on real hardware for 20 years, has serial/USB/network backends.
- *Pros:* battle-tested against actual Palms; CLI tools (`pilot-xfer`, `dlpsh`)
  the daemon could shell out to.
- *Cons:* C build per platform; Windows support is the weakest and least
  maintained; adds a native toolchain dependency the project has so far
  deliberately avoided (the whole stack is CGO-free today).
- *Verdict:* strong **fallback / cross-check** — useful to validate that a real
  Palm round-trips at all, even if not the shipping transport.

**Option C — native Go HotSync implementation.**
Re-implement DLP/PADP/SLP + transports in Go.
- *Pros:* single static binary, no Node, no C, perfectly cross-compiled.
- *Cons:* by far the most work; re-deriving a 1990s protocol stack and its
  per-device quirks is weeks-to-months and high-risk.
- *Verdict:* only worth it later if the Node sidecar proves operationally
  painful.

---

## 4. Recommended target architecture

```
        ┌──────────────┐   HotSync (DLP over serial / USB / TCP)
        │  Palm Pilot  │  ── user presses HotSync ──┐
        └──────────────┘                            │
                                                     ▼
   ┌──────────────────────────────────────────────────────────┐
   │  Transport sidecar  (palm-sync, Node)  — PER-OS bits live  │
   │  here only: serialport / USB / TCP listener                │
   │  • AwaitCradle  • PullDB(name)->bytes  • PushDB(bytes)      │
   └───────────────┬──────────────────────────────────────────┘
        local IPC: Unix socket (mac/linux) | named pipe or 127.0.0.1 TCP (win)
                   │
   ┌───────────────▼──────────────────────────────────────────┐
   │  PalmVellum daemon  (Go — already ~95% portable)           │
   │  • .pdb parse/serialize (sync-cli pkg, pure Go)            │
   │  • SQLite cache   • conflict detection                     │
   │  • Supabase REST push/pull   • AI/Oracle worker            │
   │  • OS service: launchd | systemd | Windows Service         │
   └───────────────┬──────────────────────────────────────────┘
                   │  HTTPS (PostgREST + Edge Functions)
                   ▼
        ┌────────────────────────────────────────────┐
        │  Supabase  (records / events / ai_queue /    │
        │  sync_conflicts)  + Edge Functions           │
        └────────────────────────────────────────────┘
```

Key design choices that make "three platforms" cheap:

1. **Confine all OS-specific code to the transport sidecar.** Everything below
   the IPC line is already portable Go. The daemon should never know whether a
   byte arrived over a Windows COM port or a Linux `/dev/ttyUSB0`.
2. **Pick one IPC that works everywhere.** Unix domain sockets now work on
   Windows 10+, but a localhost TCP port (the daemon already binds
   `127.0.0.1:7733`) or a named pipe is the safest uniform choice. Define a tiny
   line/JSON protocol: `await-cradle`, `list-dbs`, `pull-db <name>` → bytes,
   `push-db <name> <bytes>`.
3. **"Automatic on dock" falls out of HotSync itself.** Because the Palm
   *initiates* the session, the sidecar simply keeps a listener open; pressing
   HotSync triggers it. No polling, no device hot-plug magic required for the
   serial/network paths.
4. **Reuse the `.pdb` engine.** `sync-cli`'s parser/serializer should be lifted
   into a shared internal package the daemon imports, so the daemon path and the
   manual-CLI path share one source of truth.
5. **Promote conflict handling to the daemon path.** The CLI is destructive
   last-write-wins, but the cloud already has a `sync_conflicts` table and the
   native Android client already implements a working conflict-resolution UI —
   that contract can be mirrored here instead of clobbering Palm data.

---

## 5. Per-OS "resident service" (so it's always listening)

| OS | Mechanism | Notes |
|---|---|---|
| macOS | `launchd` LaunchAgent (or `SMAppService` for 13+) | Already the documented intent; not yet coded. |
| Linux | `systemd --user` unit | Plus a udev rule granting the user access to `/dev/ttyUSB*`. |
| Windows | Windows Service (or Task Scheduler "at logon") | Service wrapper around the same daemon binary; driver install (FTDI/Zadig) is a one-time setup step. |

The Go daemon is a single static binary on every platform; only the
registration manifest and the bundled sidecar differ.

---

## 6. Staged plan (de-risked, serial-first)

1. **Define the sidecar IPC contract** (1–2 wks). Lock the daemon ↔ sidecar
   protocol; replace the dead `socketPath`/stub methods in `hotsync/sidecar.go`
   with a real client; keep stub mode for CI.
2. **One real round-trip, one OS** (the hard unknown) — FTDI serial cable +
   Palm IIIe on your Mac. Prove `pull MemoDB → cloud → pull back → install`
   against *physical* hardware. Cross-check with pilot-link's `pilot-xfer` to
   isolate "is it our code or the cable?".
3. **Generalise the transport** — Linux next (closest to Mac: serial + libusb),
   then Windows (COM port serial first; defer raw-USB cradles behind a
   Zadig/WinUSB setup doc).
4. **Resident service + auto-on-dock** packaging per OS (§5), plus installer
   bundling of the Node sidecar.
5. **Breadth** — extend conduits to Address Book / Date Book / Note Pad sketches
   (ROADMAP Phase 6 open items) and move the daemon path onto real conflict
   resolution via `sync_conflicts`.

A pragmatic shortcut worth weighing: the **Network HotSync + ESP32 Refresh
Puck** path sidesteps *all* per-OS USB/serial driver pain at once — the desktop
side becomes a plain TCP listener identical on every platform, and the only
hardware variability moves into one small well-defined device. If "works the
same on all three OSes with minimal driver hassle" is the priority, this is the
strongest long-term bet.

---

## 7. Risks & open questions

- **No physical-hardware validation yet** — everything to date is emulator-based.
  Real Palms have timing/quirk surprises; Step 2 above must come early.
- **`palm-sync` transport coverage on Windows raw-USB is unverified** — confirm
  before committing; serial-first avoids depending on it.
- **USB-cradle driver UX on Windows** (Zadig/WinUSB) is a real onboarding wart;
  serial or network sidesteps it.
- **Two-way conflicts** — must not ship the CLI's destructive pull as the daemon
  default; wire `sync_conflicts` instead.
- **Conduit coverage** — only Memo/To Do today; Address/Date Book/sketches
  pending.
- **Apple Silicon / signed kernel drivers** for USB-serial chips — verify on the
  actual target Macs.

---

## 8. Conclusion

Cross-platform automatic Palm→cloud sync is **feasible and architecturally
already most of the way there.** The decisive fact is that PalmVellum's sync
brain — `.pdb` parsing, the Supabase contract, conflict storage, the AI worker —
is **pure, dependency-free Go that runs unchanged on Windows, macOS, and Linux
today.** The only platform-shaped work is a single HotSync **transport sidecar**,
and the project has already chosen a portable approach for it (`palm-sync` over
local IPC). Confine the OS-specific bits to that sidecar, go **serial-first on
the Palm IIIe** to retire the biggest unknown (real hardware), add Linux then
Windows, and the "dock it anywhere and it just syncs" goal is reachable without
re-architecting anything that exists. Windows is the most work (service
registration + USB driver onboarding), but every piece has a known, documented
solution — and the Network-HotSync/Refresh-Puck route can erase even that
per-OS driver burden if uniformity is valued over hardware simplicity.

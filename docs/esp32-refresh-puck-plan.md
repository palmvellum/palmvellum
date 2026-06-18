# Implementation Plan — ESP32 "Refresh Puck" (Wi-Fi HotSync bridge)

**Goal.** A small box that plugs into a Palm's HotSync cradle and proxies the
HotSync session over Wi-Fi to the PalmVellum daemon, so that **press HotSync →
data syncs to the cloud automatically**, identically on Windows, macOS, and
Linux, with **no per-OS serial/USB driver install**.

**Why this de-risks the cross-platform problem.** In the serial/USB approach,
each desktop OS needs its own driver and device handling (see
`cross-platform-desktop-sync-feasibility.md`). The Refresh Puck **moves all
hardware variability into one ESP32**: the desktop side becomes a plain TCP/IP
endpoint that is byte-for-byte the same on every OS. Windows stops being special.

> Honesty up front — three things in this plan are **assumptions that must be
> bench-confirmed on a real Palm IIIe before committing hardware**, because the
> project has never tested against physical hardware (only CloudpilotEmu):
> 1. the **electrical level** at the cradle/connector,
> 2. how HotSync **baud-rate negotiation (CMP)** behaves through a transparent
>    pipe, and
> 3. that the chosen `palm-sync` stack can drive HotSync over a **network/serial
>    transport**.
> The plan is structured to answer all three *with a cable, before any custom
> PCB exists.*

---

## 1. Architecture

```
 ┌──────────┐  cradle / sync cable        ┌──────────────────────────┐
 │  Palm    │  RS-232 (±) or LVTTL         │  ESP32 Refresh Puck       │
 │  IIIe    │ ───────────────────────────► │  • RS-232<->3.3V level    │
 │ (HotSync │ ◄─────────────────────────── │    shift (MAX3232)        │
 │  button) │      TXD / RXD / GND         │  • UART  <->  TCP pump    │
 └──────────┘                              │  • Wi-Fi STA + mDNS       │
                                           └────────────┬─────────────┘
                                   Wi-Fi (LAN, TCP)     │  raw HotSync bytes
                                                        ▼
                              ┌──────────────────────────────────────────┐
                              │  PalmVellum daemon (Go, already portable)  │
                              │  • palm-sync sidecar: "network serial"     │
                              │    transport  ← connects to the Puck       │
                              │  • .pdb parse/serialize (sync-cli pkg)     │
                              │  • SQLite cache, conflict, Supabase push   │
                              └────────────────────┬─────────────────────┘
                                                   │ HTTPS (PostgREST + Edge Fns)
                                                   ▼
                                        Supabase (records / events / ai_queue)
```

**Division of labour (the key design rule):**
- The **Puck is a dumb-ish transparent pipe**: serial bytes ⟷ TCP bytes. It does
  *not* understand HotSync/DLP. All protocol logic stays in the daemon's
  `palm-sync` sidecar. This keeps the firmware tiny and the protocol brains in
  one testable place.
- The daemon's `palm-sync` transport already abstracts "a stream of HotSync
  bytes" — we add a **network transport** that points at the Puck instead of a
  local serial port. Everything below that (parse, cloud, AI) is unchanged.

---

## 2. Hardware (bill of materials)

| Part | Purpose | Notes |
|---|---|---|
| ESP32-WROOM-32 (or ESP32-C3) dev board | MCU + Wi-Fi | C3 is cheaper/smaller; WROOM has more examples. Either's UART1 is fine. |
| MAX3232 transceiver breakout | RS-232 ↔ 3.3V level shift | Needed **if** tapping the cradle's DB9 (true RS-232). Skip if tapping the Palm connector's LVTTL directly (see §3). |
| DB9 female connector | Mates the Palm serial cradle/cable | Or splice the cradle cable directly. |
| 5V USB power (wall adapter) | Powers the Puck | The Palm cradle data lines do **not** power the ESP32; give it its own supply. |
| Small enclosure | "Puck" form factor | 3D-printed or off-the-shelf. |
| (later) custom PCB | Integrate ESP32 + MAX3232 + DB9 | Only after the breadboard round-trip works. |

**Cost:** ~US$10–20 in parts for a working breadboard prototype.

### Wiring (cradle DB9 / RS-232 path — the safe default)
```
Palm cradle DB9  ──►  MAX3232 (RS-232 side)
MAX3232 (TTL side) ──► ESP32:  R1OUT→GPIO_RX,  T1IN→GPIO_TX,  GND↔GND
ESP32 powered from USB 5V (Vin), MAX3232 from ESP32 3V3
```
Cross TX↔RX correctly (the Palm's TXD goes to the ESP32's RX). Common ground is
mandatory.

---

## 3. The three hardware unknowns (bench-test these FIRST, with a cable)

These are the make-or-break questions. Answer each before designing a PCB.

**(a) Electrical level at the tap point.**
- The Palm's *cradle* presents **true RS-232 levels** on its DB9 (it has to drive
  a PC COM port), so tapping there needs the **MAX3232**.
- The Palm's *universal connector* itself carries **~3.3V LVTTL** serial (the
  cradle contains the RS-232 driver), so a direct connector tap could skip the
  MAX3232 — but needs a confirmed pinout for your exact model and a custom
  adapter.
- **Default: tap the cradle DB9 + MAX3232.** It reuses the original cradle and
  avoids guessing connector pinouts. Confirm with a multimeter/scope on idle
  TXD before powering anything.

**(b) Baud-rate negotiation (CMP) — the core firmware decision.**
HotSync starts at **9600 baud**, then the desktop and Palm use **CMP (Connection
Management Protocol)** to jump to a higher rate (57600/115200). A purely
transparent pipe has a problem: the daemon may say "switch to 57600," but the
ESP32↔Palm UART is still at 9600 → the link breaks.

Two strategies, pick the simple one first:
- **Strategy A — lock the speed (recommended start).** Configure the `palm-sync`
  sidecar to **not negotiate up** (stay at 9600), and hard-set the ESP32 UART to
  9600. Memo/To Do payloads are tiny; 9600 is slow but completely workable for a
  proof of life. Zero baud-tracking firmware.
- **Strategy B — sniff CMP (optimisation, later).** The ESP32 parses just the CMP
  handshake to learn the negotiated baud, then switches its UART. More
  throughput, more firmware. Only do this once A proves the whole chain works.

**(c) `palm-sync` network/serial transport.**
Confirm the chosen HotSync stack can run a session over a TCP socket treated as a
serial stream (or over a pluggable transport we feed). If it can only do a local
OS serial port, the Puck pairs with a host-side **virtual serial port** (e.g.
`socat`/`com0com`) bridged to the Puck's TCP — still uniform-ish, but messier;
prefer a stack with a native network transport.

---

## 4. Firmware modules (ESP32, ~a weekend of work once §3 is known)

1. **UART driver** — fixed 9600 8N1 to start (Strategy A); buffered.
2. **TCP transport** — Puck acts as **TCP client**, connecting *out* to the
   daemon (avoids inbound firewall issues on the desktop). Daemon listens on a
   LAN port (e.g. `7734`).
3. **Byte pump** — bidirectional, low-latency: UART→TCP and TCP→UART. This is the
   whole "transparent pipe." Keep buffers small; flush promptly (HotSync is
   latency-sensitive on ACK timing).
4. **Wi-Fi provisioning** — SoftAP + captive portal for first-run SSID/password
   (no hardcoding); persist to NVS. Re-enter provisioning on a held button.
5. **Discovery (mDNS)** — advertise/resolve `_palmvellum-puck._tcp` so the daemon
   finds the Puck (and vice-versa) without static IPs.
6. **Status LED** — idle / Wi-Fi-connected / session-active / error. The user's
   only feedback that "it's listening."
7. **OTA update** (nice-to-have) — push new firmware over Wi-Fi.
8. **Session framing** — the Puck opens the TCP connection when the Palm asserts
   activity (or keeps a persistent connection and just relays); the daemon
   treats connection + first bytes as "a Palm is syncing now."

Frameworks: **ESP-IDF** (most control over UART/timing — recommended) or Arduino
core (faster to prototype). Existing serial-over-TCP firmware (esp-link style) is
a useful reference but the baud handling (§3b) is the part you own.

---

## 5. Daemon / sidecar changes (Go side — small)

- Add a **network transport** to the `palm-sync` sidecar: instead of opening a
  local serial port, accept the Puck's TCP connection and present that stream to
  the HotSync state machine. Replace the stub methods in
  `packages/mac-daemon/internal/hotsync/sidecar.go` (`AwaitCradle` / `PullDB` /
  `PushPRC`) so `AwaitCradle` resolves when a Puck connects.
- **mDNS browse** for `_palmvellum-puck._tcp`; bind a LAN listener on `:7734`.
- On session: run existing pull → parse `.pdb` (lift the pure-Go parser out of
  `sync-cli/internal/pdb` into a shared package) → cloud push/pull → write
  regenerated DB back to the Palm.
- **Conflict handling:** do *not* reuse the CLI's destructive last-write-wins;
  wire the existing `sync_conflicts` table (the native Android client already has
  a working conflict-resolution UX to mirror).
- The daemon is already a single static cross-platform binary — once the
  transport is network, **Windows/macOS/Linux behave identically.**

---

## 6. The "automatic on dock" flow (end to end)

```
1. Puck is powered, on Wi-Fi, TCP-connected (or mDNS-discoverable) to the daemon.
2. User drops Palm in cradle, presses HotSync.
3. Palm opens a serial HotSync session  → bytes flow into the Puck's UART.
4. Puck pumps them over Wi-Fi to the daemon; daemon's palm-sync runs the session.
5. Daemon: pull DBs → parse → diff vs cloud → push changes, pull cloud changes,
   resolve conflicts → regenerate DBs → write back to Palm.
6. Palm shows "HotSync complete." Cloud + PWA + phones now in sync.
```
No polling, no hotplug detection, no per-OS driver. The Palm *initiates*; the
daemon just has to be listening.

---

## 7. Staged delivery (each step de-risks the next; nothing wasted)

**Step 0 — Cable round-trip, no ESP32 (proves the protocol).**
USB-serial cable (FTDI) from a Mac straight into the Palm cradle. Get
`palm-sync` to do a real `MemoDB` pull → cloud → push back → install on a
*physical Palm IIIe*. This isolates "does our HotSync code work on real
hardware?" from any ESP32 question. **Highest-value first step.** Cross-check
with pilot-link's `pilot-xfer` if it misbehaves.

**Step 1 — ESP32 as a local serial-over-TCP pipe.**
Breadboard ESP32 + MAX3232. Bridge it to the host with `socat`/`ser2net`; point
the Step-0 setup at the bridged port. Proves the byte pump + level shifting +
fixed-baud (Strategy A) carry a real HotSync session.

**Step 2 — Native network transport in the daemon.**
Replace the host-side virtual-serial shim with a real network transport in the
`palm-sync` sidecar (§5). Now Palm → Puck → Wi-Fi → daemon → cloud works with no
local serial port at all.

**Step 3 — Make it a product.**
Wi-Fi provisioning, mDNS discovery, status LED, enclosure, optional OTA. Daemon
auto-discovers the Puck and runs sessions unattended.

**Step 4 — Cross-platform victory lap.**
Because the desktop side is now pure TCP + the existing portable Go daemon, bring
up Windows and Linux. Expect this to be *packaging + service registration only*
(launchd / systemd / Windows Service), **not** new driver work.

**Step 5 — Throughput + breadth.**
CMP baud sniffing (Strategy B) for speed; extend conduits to Address Book / Date
Book / Note Pad sketches; harden conflict resolution.

---

## 8. Risks & open questions

| Risk | Mitigation |
|---|---|
| **Never tested on real hardware** (emulator only to date) | Step 0 uses a plain cable before any ESP32 spend. |
| **Cradle electrical level uncertain** (§3a) | Meter/scope the idle line; default to DB9 + MAX3232. |
| **CMP baud negotiation through a pipe** (§3b) | Strategy A (lock 9600) first; CMP sniffing later. |
| **`palm-sync` network-transport support unproven** (§3c) | Confirm in Step 0/2; fallback = virtual serial port shim. |
| **HotSync ACK timing over Wi-Fi latency/jitter** | Keep Puck buffers small + flush fast; test on real LAN; wired-AP fallback. |
| **Wi-Fi provisioning UX on a screenless box** | SoftAP captive portal; status LED; button to re-provision. |
| **Security: cloud creds + LAN exposure** | The Palm↔Puck↔daemon link is **LAN-only**; auth/keys stay in the *daemon* (never on the Puck); bind daemon listener to LAN; consider a shared token + TLS on the Puck↔daemon hop. The Puck only ever sees opaque HotSync bytes, never cloud credentials. |
| **Destructive pull** if CLI path reused | Daemon path uses `sync_conflicts`, not last-write-wins. |

---

## 9. Conclusion

The ESP32 Refresh Puck is the **highest-leverage way to hit "dock it on any OS
and it just syncs,"** because it collapses three platform-specific driver
problems into **one small, well-defined device** and leaves the desktop side as
identical portable Go + a plain TCP socket. The engineering is modest — a
level-shifter, an ESP32 UART⟷TCP pump, and a small network transport in the
existing daemon — and the plan front-loads the only real unknowns (real-hardware
HotSync, electrical level, baud negotiation) into a **cable-only Step 0 that
needs no custom hardware at all.** Start there: if a Palm IIIe round-trips over a
plain FTDI cable, everything after it is incremental, and Windows/macOS/Linux
parity comes essentially for free.

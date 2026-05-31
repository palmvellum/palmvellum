<div align="center">

# Palm Vellum

### Some things deserve to be written down.
### Not all of them deserve to be online.

*The hardware vellum for the post-cloud age.*

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Hardware](https://img.shields.io/badge/hardware-AAA_powered-orange)](docs/hardware-compatibility.md)
[![Palm OS](https://img.shields.io/badge/Palm_OS-1.0--4.1-blueviolet)](docs/architecture.md)
[![Targets](https://img.shields.io/badge/targets-19_devices-green)](docs/hardware-compatibility.md)
[![Status](https://img.shields.io/badge/status-pre--alpha-red)](ROADMAP.md)

</div>

---

## What this is

Medieval scribes used vellum because parchment outlived their patrons.
A page written on vellum in 1215 is still legible in 2026.

Palm Vellum is the same idea, made electronic.

A specific class of handheld computer — Palm Pilot family devices
manufactured between **1996 and 2003 that run on two AAA alkaline
batteries** — is the only consumer hardware ever shipped that
satisfies every property below simultaneously:

- **No radio of any kind, ever** (no Wi-Fi, no Bluetooth, no NFC, no cellular)
- **Firmware frozen for 20+ years** (zero CVEs filed since 2004)
- **User-serviceable primary cell** (AAA replaceable in seconds)
- **Survives cold storage for a decade** (no Li-ion degradation)
- **Real keyboard or stylus input** (real I/O, not a security token)
- **Costs $15 to acquire in 2026** (eBay, Yahoo Auctions, surplus stores)

That class is exactly **19 devices**. Palm Vellum supports all of them.

We turn them into the strongest password vault, TOTP authenticator,
cryptocurrency cold signer, and AI-bridged encrypted notebook you can own.

## The unfair advantage

| Property                       | 2-AAA Palm | iPhone 17 | YubiKey 5 | Trezor Safe 5 |
|--------------------------------|:----------:|:---------:|:---------:|:-------------:|
| Wireless radios present        | **0**      | 6         | 1 (NFC)   | 1 (Bluetooth) |
| Listed in any CVE database     | **No**     | Daily     | Sometimes | Sometimes     |
| Battery cell degrades on shelf | **No**     | Yes       | n/a       | n/a           |
| 10-year cold-storage revival   | **Yes**    | No        | n/a       | n/a           |
| User-swappable battery         | **Yes**    | No        | n/a       | n/a           |
| Acquire price (2026)           | **~$15**   | $1,500    | $50       | $169          |
| Open source end-to-end         | **Yes**    | No        | Partial   | Partial       |
| Real screen + keyboard         | **Yes**    | Yes       | No        | Limited       |

When the master phrase is in your head and the device is on you,
**no nation-state-grade adversary can extract your secrets.**

That is the moat.

## How it works

```
                    ┌──────────────────────────────┐
   You ─[Graffiti]─►│  Palm Pilot                  │
                    │  (vault + UI + crypto core)  │
                    └──────────┬───────────────────┘
                               │ serial cradle (HotSync)
                               ▼
                    ┌──────────────────────────────┐
                    │  Bridge (your Mac, Android,  │
                    │   or self-hosted ESP32 puck) │
                    │  - protocol translation       │
                    │  - TLS termination            │
                    │  - selective sync gateway     │
                    └──────────┬───────────────────┘
                               │ HTTPS (optional)
                               ▼
                    ┌──────────────────────────────┐
                    │  Cloud (Supabase or self-    │
                    │   hosted), encrypted blobs    │
                    │   only, never plaintext       │
                    └──────────────────────────────┘
```

Three record postures, declared per record type:

- 🔒 **`vault`** — never leaves the Palm. Master phrase stays with you.
- 🔐 **`sealed`** — AES-256-GCM ciphertext may sync; decryption only on Palm.
- 🌐 **`open`** — plaintext sync OK (todos, AI conversations, drafts).

The schema enforces these postures. The sync engine cannot leak a
`vault` record by accident.

## Features

### Planned for v1.0

- **🔐 Password Vault** — AES-256-GCM records, master phrase via
  Argon2id (on bridge) + HMAC-SHA1 stretch (on device)
- **🔑 TOTP Authenticator** — RFC 6238, fully offline, replaces
  Google Authenticator forever
- **✍️ Cold Signer** — Ed25519 / ECDSA-secp256k1 for PGP, age, SSH,
  Bitcoin, Ethereum. Sign offline, broadcast via QR
- **🧠 AI Oracle** — Graffiti a question, sync, get an answer back
  from Claude / Gemini / a local LLM. Vault and sealed records are
  never sent
- **📓 Encrypted Journal** — Per-entry encryption, optional weekly
  AI reflection that never sees plaintext
- **🎲 BIP-39 + Shamir** — Generate recovery phrases, split into
  N-of-M shares

### On the roadmap

See [`ROADMAP.md`](ROADMAP.md).

## Why this and not...

### ...a YubiKey?

A YubiKey is a token, not a device. It has no screen, no keyboard,
no UI. It cannot store passwords you can read. It cannot show you a
Bitcoin transaction before you sign it. It cannot become an
AI-bridged notebook. Palm Vellum is the YubiKey shaped like a 1998
Palm Pilot, with all the things a YubiKey gave up.

### ...a Trezor or ELLIPAL?

Both are excellent dedicated cryptocurrency signers. Both ship
firmware you must trust, are listed in CVE databases, and cost
$80–400. A 2-AAA Palm IIIe costs $30, runs firmware frozen in 1999,
and signs the same transactions via the same QR workflow — plus it
stores your passwords, TOTPs, and journal entries the dedicated
wallets cannot.

### ...[SeedSigner](https://seedsigner.com/)?

The closest spiritual sibling, and a strong inspiration. SeedSigner
picks a specific Raspberry Pi Zero v1.3 because that revision has
no radio. Palm Vellum picks 2-AAA Palm OS devices for the same
reason. SeedSigner is Bitcoin-only and stateless by design. Palm
Vellum is multi-purpose and stateful, with a real keyboard or
stylus.

### ...the old [Keyring for Palm OS](https://gnukeyring.sourceforge.net/)?

Keyring was a brilliant 2003 password manager for Palm OS,
abandoned around 2010. Palm Vellum is its great-grandchild, rebuilt
on modern crypto primitives, with optional zero-knowledge cloud
sync for non-secret records, and an AI bridge.

### ...just install Bitwarden?

Bitwarden is great if your threat model is *credential reuse* or
*phishing*. Palm Vellum's threat model is *nation-state network
surveillance combined with cloud compromise*. Different problem,
different tool. Use both.

See [`docs/competitive-landscape.md`](docs/competitive-landscape.md)
for the full analysis.

## Supported hardware

19 devices across three manufacturers. All powered by 2 AAA alkaline
cells. All from 1996–2003. All with zero radios.

Full table with buying guide in
[`docs/hardware-compatibility.md`](docs/hardware-compatibility.md).

**Quick picks**:

- **Best for first-timers**: Palm IIIxe or m105 (8MB RAM, $20–60)
- **Reference target**: Palm IIIe (1999 icon, 2MB, $30–80)
- **Hi-res variant**: Sony PEG-SL10 (320×320 mono, jog dial, $40–120)
- **Most RAM**: Visor Deluxe / Platinum / Neo (8MB, $25–70)

## Quickstart

> ⚠️ **Pre-alpha.** Production use of cryptographic features is
> not yet recommended. Track [`ROADMAP.md`](ROADMAP.md) for
> v1.0 readiness.

### Hardware checklist

- A supported Palm (see table above)
- A serial HotSync cradle that matches your model
- A USB-Serial adapter (genuine FTDI FT232R chip required)
- 2× fresh AAA alkaline batteries
- A computer running macOS 13+, Linux, or Windows 10/11

### Install (macOS)

```bash
# Coming soon. For now, build from source:
git clone https://github.com/palmvellum/palmvellum.git
cd palmvellum
./scripts/bootstrap.sh
make all
./packages/mac-daemon/bin/palmvellum doctor
```

### First-time setup

```bash
palmvellum enroll          # Pair your Palm and set master phrase
palmvellum hotsync         # Test the cradle and serial chain
palmvellum vault add       # Add your first password
```

Detailed docs in [`docs/installation/`](docs/installation/).

## Architecture

Palm Vellum is a monorepo of four packages:

```
palmvellum/
├── packages/
│   ├── shared-schema/    # TypeScript types + Zod + SQL migrations
│   ├── palm-app/         # Palm OS C app (m68k)
│   ├── mac-daemon/       # Go daemon (HotSync + AI bridge + crypto)
│   └── pwa/              # SvelteKit web companion
├── docs/                 # User and developer documentation
└── infra/                # Supabase migrations + Cloudflare config
```

Deep dive: [`docs/architecture.md`](docs/architecture.md).

## Security model

Palm Vellum assumes a nation-state-grade adversary with **full
network surveillance, compromised cloud providers, and compromised
modern endpoints**. The adversary cannot:

1. Physically access your Palm
2. Read your memory (master phrase)

Given those two constraints, **all your `vault` and `sealed`
records remain confidential.**

- Full [threat model](docs/threat-model.md)
- Full [cryptographic specification](docs/crypto-spec.md) — KDF
  parameters, AES-GCM record format, posture enforcement, signer
  workflows, BIP-39 + Shamir

## Contributing

Looking for help with:

- 📱 Hardware testing on devices we don't own yet (see compat issues)
- 🌍 Localization — English first; 繁中, 简中, 日本語, Español
       contributions welcome
- 🎨 Logo and visual identity
- 📚 Documentation and tutorials
- 🔍 Cryptographic review of primitives by qualified auditors

See [`CONTRIBUTING.md`](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE) © 2026 Palm Vellum contributors.

## Acknowledgments

Built on the shoulders of:

- [palm-sync](https://github.com/jichu4n/palm-sync) — modern HotSync
- [prc-tools-remix](https://github.com/jichu4n/prc-tools-remix) — m68k toolchain
- [CloudpilotEmu](https://cloudpilot-emu.github.io/) — emulator
- [SeedSigner](https://seedsigner.com/) — design philosophy inspiration
- [Anthropic](https://anthropic.com), [Supabase](https://supabase.com),
  the [Svelte](https://svelte.dev) team, the [Go](https://go.dev) team
- The original engineers at Palm Inc., Handspring, and Sony Clié (1996–2004)
- The lo-fi computing and permacomputing communities

> "The future is already here — it's just not very evenly distributed."
> — William Gibson

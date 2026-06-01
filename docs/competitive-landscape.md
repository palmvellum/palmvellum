# Competitive Landscape

A survey of projects adjacent to PalmVellum's mission, conducted
May 2026. Updated whenever a relevant new project surfaces.

## Direct competitors

**None found.** No active project (open source or commercial) ships
the following combination:

1. Real vintage 2-AAA Palm OS hardware as the trust root
2. Multi-purpose vault: password manager + TOTP + cold signer
3. Bridge architecture connecting vintage hardware to modern
   AI / cloud

This niche is structurally empty.

## Historical precedents (abandoned)

### [Keyring for Palm OS](https://gnukeyring.sourceforge.net/)

The 2003-era open-source password manager for Palm OS. Hosted on
SourceForge, last meaningful update circa 2010. No cloud sync, no
modern crypto primitives, no AI bridge, no maintained build.

**Relationship to PalmVellum**: spiritual great-grandparent. Palm
Vellum rebuilds the same use case on modern crypto, with a sync
bridge, and supports the same hardware Keyring targeted.

### PDApass

A one-time-password authentication system using a Palm Pilot as a
generator. Historical research curiosity.

### [David Wheeler's Palm Passwords survey](https://dwheeler.com/palm-passwords.html)

Academic 2000s-era survey of password storage on Palm devices.
Useful historical reading; the products it surveys are all
discontinued.

## Active dependencies (not competitors)

These projects are foundations PalmVellum builds upon and openly
credits.

| Project | Role | Maintainer | 2026 status |
|---|---|---|---|
| [palm-sync](https://github.com/jichu4n/palm-sync) | Modern TypeScript HotSync engine | jichu4n | ⭐ Active (Feb 2026 update) |
| [prc-tools-remix](https://github.com/jichu4n/prc-tools-remix) | m68k cross-compile toolchain | jichu4n | Active |
| [palm-os-sdk](https://github.com/jichu4n/palm-os-sdk) | SDK headers + PilRC | jichu4n | Active |
| [CloudpilotEmu](https://cloudpilot-emu.github.io/) | Modern Palm OS emulator (PWA) | Cloudpilot team | Active |
| [PalmDB](https://palmdb.net/) | Community Palm software archive | Community | Maintained |

The entire modern Palm OS developer ecosystem effectively rests on
the work of a small number of maintainers. PalmVellum aims to be
a user of, and contributor back to, this ecosystem.

## Adjacent: air-gapped crypto signers

These projects share the air-gap philosophy but target different
hardware and (mostly) cryptocurrency-only use cases.

### [SeedSigner](https://seedsigner.com/) — closest spiritual sibling

- **Hardware**: Raspberry Pi Zero v1.3 (selected specifically because
  this revision has no WiFi/Bluetooth)
- **Scope**: Bitcoin-only, stateless seed signing
- **Bridge**: QR codes both ways
- **Cost**: <$50 DIY

**Comparison to PalmVellum**: same philosophy applied to different
hardware. SeedSigner is stateless and Bitcoin-only by design. Palm
Vellum is stateful and multi-purpose. Both choose commodity vintage
hardware over premium specialty wallets because the threat model
rewards obscurity and longevity.

### [AirGap Vault](https://airgap.it/)

- **Hardware**: any old smartphone you donate
- **Scope**: Multi-chain crypto signer
- **Bridge**: QR codes between paired phones
- **Open source**: yes

### [Polkadot Vault (Parity Signer)](https://github.com/novasamatech/parity-signer)

- **Hardware**: old smartphone
- **Scope**: Polkadot / Kusama ecosystem
- **Open source**: yes

### Commercial premium signers (different category)

- **[NGRAVE ZERO](https://ngrave.io/)** — $400, EAL7 certified, proprietary firmware
- **[ELLIPAL Titan 2.0](https://ellipal.com/)** — $169, QR workflow, proprietary
- **[Keystone](https://keyst.one/)** — multiple price points, proprietary

These are excellent products. They are not what PalmVellum is.

## Adjacent: hardware password managers

Different form factor; mostly USB tokens without a real screen or
keyboard.

| Product | Form | Notes |
|---|---|---|
| [Nitrokey 3](https://www.nitrokey.com/) | USB stick + FIDO2 | Berlin team, $59, open source |
| [OnlyKey](https://onlykey.io/) | USB + PIN pad | $50, small display |
| [Mooltipass](https://www.themooltipass.com/) | USB + smartcard | Community, small display |
| YubiKey family | FIDO2 token | Proprietary, $50 |
| SoloKeys Solo 2 | FIDO2 token | Open source, $35 |

None of these can read passwords back on a screen the user controls,
display a Bitcoin transaction for verification before signing, or
operate as an AI-bridged notebook. They are tokens, not devices.

## Adjacent: lo-fi computing / permacomputing communities

- [permacomputing.net](https://permacomputing.net) — sustainable
  computing manifesto; no security tooling produced specifically by
  the community
- [Damaged Earth Catalog / 100r.co](https://100r.co/) — off-grid
  digital tools, primarily creative work
- [KemoNine's "Old School PDA" blog (2025-08)](https://blog.kemonine.info/blog/2025-08-19-old-school-pda/) —
  modern Palm Clié experience via Android emulation (Z Fold 4,
  Boox Palma + CloudpilotEmu); proves market interest, but
  emulation-based rather than real hardware

PalmVellum sits at the security-tooling intersection of these
communities, currently empty.

## Modern Palm-OS revival movement

- Hardware-emulation efforts (CloudpilotEmu, Mu, POSE) are healthy
- Real-hardware enthusiast communities active on Reddit
  (r/palmpilot), Discord servers, and Hackaday
- No project we found uses real hardware as a security device in 2026

## Strategic implications

1. **Risk of duplication: minimal.** No one is doing this.
2. **Risk of "why not YubiKey?" critique: moderate.** The README
   "Why this and not..." section addresses this directly.
3. **Closest collaborator opportunity**: SeedSigner. Shared
   philosophy, different hardware. Cross-promotion possible.
4. **Strongest dependency**: jichu4n's palm-sync. Active in 2026.
   We should acknowledge prominently and contribute back when our
   needs surface gaps.
5. **Community gap to fill**: lo-fi computing + serious crypto. No
   one is here yet.

## Methodology

This document is based on parallel multi-angle web searches
conducted in May 2026 across password managers, hardware wallets,
retro PDA modernization, air-gapped vault projects, Palm OS revival
communities, AI bridges, and lo-fi computing communities. See the
project's research notes for the full query log.

Updates welcome. If you know of a project that should be listed
here, please open a PR.

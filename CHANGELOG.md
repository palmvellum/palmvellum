# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Initial project scaffold
- Apache 2.0 license
- Threat model and security policy
- Hardware compatibility matrix (19 devices)
- Competitive landscape analysis
- Roadmap through v2.0
- `docs/crypto-spec.md` — v1.0 cryptographic specification covering
  posture system, KDF parameters, AES-256-GCM record format,
  Password Vault, TOTP Authenticator, Cold Signer (Ed25519 +
  ECDSA-secp256k1), BIP-39 + Shamir Secret Sharing, on-device PDB
  layout, sync-engine enforcement, and Palm IIIe performance
  budgets

### Toolchain (2026-06-01)

- `scripts/palm-toolchain.Dockerfile` — Ubuntu 24.04 + Rosetta amd64
  image carrying m68k-palmos-gcc 2.95.3, PilRC 3.2.90, build-prc 2.3,
  and Palm OS SDKs 1 through 5r4. Sidesteps macOS Command Line Tools
  version requirements entirely.
- `scripts/palm-build.sh` — one-line invoker for the toolchain image.
- `scripts/bootstrap.sh` — idempotent macOS setup: Homebrew packages,
  OrbStack, mise, Go, Node 22, pnpm 10, jichu4n/palm-os tap, Docker
  toolchain image, and a sanity-test compile.
- `packages/palm-app/src/hello.c` + `Makefile` — first compilable
  Palm OS .prc. 845 bytes. Targets -palmos3.5 (SDK 3.5 baseline;
  -palmos3.1 + Pilot.h variant planned for real Palm IIIe 3.1-ROM
  later).

### Vision restructure (2026-06-01)

- Project rebranded from "Palm Vellum" to single-word **PalmVellum**
- README now describes the **movement / platform model**:
  open-source app family (Apache 2.0) + opt-in commercial AI service
  with non-AI features always free
- App directory in README listing the planned open-source family:
  Palm Wallet, QR Card, VellumCN, Dream Diary, News Feed, plus the
  closed-source PalmVellum AI superapp
- `docs/apps/palm-wallet.md` — full design spec for the open-source
  cold signer, including entropy strategy, ECC library choice,
  QR / BBQr workflow, mandatory seed-phrase backup disclaimers
- `docs/preservation.md` — 5-tier preservation strategy
  (GitHub → archive.org → Codeberg mirror → IPFS → community channels)
  and ROM provenance manifest format

### Mac daemon scaffold (2026-06-01)

- `packages/mac-daemon/` end-to-end Go scaffold
  - `cmd/palmvellum` with cobra subcommands: serve / doctor / sync / version
  - `internal/config` — env-driven configuration with ~ expansion
  - `internal/store` — modernc.org/sqlite + embedded migrations
    (records / sync_conflicts / sync_state tables)
  - `internal/api` — local HTTP server on 127.0.0.1:7733
    (/health, /v1/records, /v1/sync)
  - `internal/hotsync`, `internal/supa`, `internal/ai` — typed stubs
    that error cleanly until implemented per issues #2 / #7 / #10 / #14
- Tested: build → version → doctor → serve → /health / /v1/records → clean shutdown

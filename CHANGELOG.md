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

### Landing page (2026-06-03)

- `website/` self-contained static landing page for hosting at
  `tatliving.dev/palmvellum`
- Y2K-meets-programming aesthetic: dark grey + accent yellow,
  IBM Plex Mono + Press Start 2P + VT323, CRT-style scanlines,
  status bar with animated SYNC counter, fake cradle device path
  poll, blinking cursor
- 7 sections: hero, counter strip, "what is this" + comparison
  table, manifesto, apps directory (open / commercial split), 19
  supported devices, get-involved
- `vercel.json` with security headers, `DEPLOY.md` documenting
  three deployment paths (subdirectory rewrite / subdomain / GitHub
  Pages mirror)
- Local preview: `cd website && python3 -m http.server 8765`

### Repo cleanup (2026-06-03)

- Removed `website/` from this public repository. The landing page
  source lives in the (separate) `tathome2025/tatlivingio` repo and
  is deployed at <https://tatliving.dev/palmvellum/>. Keeping a
  duplicate copy here added maintenance burden and exposed the same
  files in two public places. Added `website/` to `.gitignore` so
  future accidental copies won't be committed.

### Repo trim (2026-06-03)

- Trimmed the public repo to the minimum useful open-source surface
- Removed `docs/architecture.md` (placeholder), `docs/competitive-landscape.md`
  (internal market positioning), `docs/preservation.md` (internal ops
  strategy), and `docs/apps/palm-wallet.md` (will live in its own
  repo when the work starts)
- Removed empty placeholder dirs: `docs/development/`,
  `docs/installation/`, `docs/apps/`, `assets/`, `infra/`,
  `packages/palm-app/build/`, `packages/palm-app/rsc/`,
  `packages/shared-schema/migrations/`
- Removed `packages/pwa/` (placeholder README) — workspace entry will
  be reintroduced when the PWA workstream begins
- Removed `packages/palm-app/hello.o` (stale build artifact that
  slipped past `*.o` gitignore when it was originally tracked)
- Updated `pnpm-workspace.yaml` to drop the empty pwa workspace
- Fixed all README links pointing at removed docs

Resulting tree is ~45 tracked files: the three meta files
(LICENSE / README / CONTRIBUTING / etc.), three docs (hardware
compatibility, threat model, crypto spec), three packages (palm-app,
mac-daemon, shared-schema), and the build orchestration scripts.

### Supabase migration applied (2026-06-03)

- Migration `supabase/migrations/20260603120000_init.sql` applied to
  the live PalmVellum project (`jrkwncplngmznfzzqwee`, Singapore
  region) via psql against the session pooler
  `aws-1-ap-southeast-1.pooler.supabase.com:5432`.
- Verified post-apply state:
  - 3 tables present (records / ai_queue / sync_conflicts)
  - 8 indexes (5 explicit + 3 primary keys)
  - 7 RLS policies (all per-user `auth.uid() = user_id`)
  - 2 triggers (records_enqueue_ai AFTER INSERT,
    records_touch_updated_at BEFORE UPDATE)
  - 3 functions (sync_apply_diff, enqueue_ai_request,
    touch_updated_at)
  - `ai_queue` added to `supabase_realtime` publication
  - RLS enabled on all 3 tables (`rowsecurity = t`)
  - REST `/rest/v1/records` returns 200 + empty array
  - RPC `/rest/v1/rpc/sync_apply_diff` reachable
- `packages/mac-daemon/.env` (gitignored, chmod 600) now carries the
  real `SUPABASE_SECRET_KEY` so the daemon can stop being a stub for
  Supabase calls when issue #7 is implemented.

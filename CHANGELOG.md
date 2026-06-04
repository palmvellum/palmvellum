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

### AI worker + PWA scaffold (2026-06-04)

- `packages/mac-daemon/internal/aiworker/worker.go` — polling worker
  that drains `ai_queue`, claims one row at a time, calls Claude via
  `internal/ai`, writes the response (capped at 1024 bytes for the
  Palm IIIe heap) back to `records.ai_response` via `internal/supa`.
- `internal/supa/client.go` upgraded from stub to real PostgREST
  client (Enabled / Ping / ClaimNextAIQueueItem / GetRecord /
  UpdateRecordAI).
- `internal/ai/claude.go` upgraded from stub to real Anthropic
  Messages API call with cache_control on the Oracle persona system
  prompt.
- `cmd/palmvellum/main.go` wires the worker into `serve`, runs both
  the HTTP API and the worker as goroutines, propagates context
  cancellation. `doctor` now pings Supabase and reports whether
  Anthropic is configured.
- Built and smoke-tested: `palmvellum doctor` → supabase reachable,
  anthropic shows idle warning until key is set. `palmvellum serve`
  starts both subsystems and shuts down cleanly on SIGTERM.

- `packages/pwa` — SvelteKit 2 + Svelte 5 runes scaffold
  - `@supabase/supabase-js` 2.46 with the publishable key (RLS-safe)
  - `dexie` 4 for the IndexedDB mirror of records + outbox
  - `adapter-static` so the build is a portable static site for
    Cloudflare Pages / Vercel / nginx
  - Realtime subscription on `records` so the browser list stays
    live in step with Palm syncs + AI worker writes
  - Per-posture colored borders (vault red, sealed yellow, open
    green) matching docs/crypto-spec.md §1
  - Charcoal grey + accent yellow palette matches the landing page
  - Static build produces 16 files / ~340 KB total
- `pnpm-workspace.yaml` reopens packages/pwa

### AI provider abstraction + OpenAI default (2026-06-04)

- `internal/ai/provider.go` — new Provider interface (Enabled,
  ProviderName, Ask). `OracleSystemPrompt` is shared so the persona
  is consistent regardless of which backend answers.
- `internal/ai/openai.go` — OpenAI Chat Completions backend, model
  defaults to `gpt-4o-mini`, key prefix check (`sk-`), full Response
  shape with usage tokens.
- `internal/ai/anthropic.go` — renamed from `claude.go` and
  refactored to implement the new Provider interface; cache_control
  on the persona prompt preserved.
- `config.Load` now reads `AI_PROVIDER` (default `openai`),
  `OPENAI_API_KEY`, `OPENAI_MODEL`, `ANTHROPIC_API_KEY`,
  `ANTHROPIC_MODEL`. Daemon logs the chosen provider.
- `doctor` reports the selected provider's configured-or-not state.
- `aiworker.Worker` takes an `ai.Provider` (was `*ai.Client`).
- `.env.example` updated with the new provider knobs.

Smoke-tested:
- `palmvellum doctor` → "ai provider openai not configured" when
  key is absent, succeeds otherwise.
- `palmvellum serve` → starts worker as "provider=openai
  ai_ready=false", idles cleanly.

### PWA deployed to tatliving.dev/palmvellum/app/ (2026-06-04)

- `svelte.config.js`: `kit.paths.base = '/palmvellum/app'` so all
  asset URLs include the subdir prefix (overridable via
  `PUBLIC_BASE_PATH` env var for local dev or alternate deploys).
- Build copied to the tathome2025/tatlivingio repo at
  `palmvellum/app/` (16 files, ~432 KB).
- tatlivingio `vercel.json` gains an SPA-fallback rewrite so deep
  links to `/palmvellum/app/<anything>` resolve to the SvelteKit
  shell. Static assets under `/palmvellum/app/_app/*` continue to
  be served directly (Vercel prefers static matches over rewrites).
- Landing page hero gains "launch web companion" as the new primary
  CTA; "git clone" demoted to secondary.
- Verified live: index 200, deep-link 200, JS asset 200, landing
  page contains the new button.

### v0.2 SaaS data model applied (2026-06-04)

Migration `0002_v02_saas` landed on the live PalmVellum project. Adds
the multi-tenant SaaS substrate:

- `waitlist` — anon insert OK; only the owning user can read their
  row once their auth.users record exists.
- `user_settings` — per-user API mode (byok / platform), preferred
  provider, BYOK key references into Supabase Vault, subscription
  status, credits, Palm enrollment state. `invited` gate controls
  access to /app.
- `ai_usage` — per-call accounting (tokens, cost in credits, errors).
  v0.3 reads this for billing; v0.2 writes BYOK rows with
  cost_credits=0.
- 5 SECURITY DEFINER functions:
  - `init_user_settings` — auto-creates a settings row when auth.users
    gains a new member; auto-flips `invited` if the email is already
    off the waitlist.
  - `store_user_api_key(provider, plaintext)` — PWA-callable; pushes
    the plaintext into vault.secrets and writes the returned UUID to
    user_settings. Plaintext never reaches a regular column.
  - `read_user_api_key(target_user, provider)` — service-role-only;
    pulls plaintext back from vault.decrypted_secrets for the worker.
  - `enroll_palm()` — returns a fresh 64-char hex hotsync_token; only
    its sha256 is stored.
  - `resolve_hotsync_token(raw)` — service-role-only; returns the
    user_id whose token matches.

RLS enabled on every new table with auth.uid()-scoped policies.
Backfilled `user_settings` for the existing test auth user so the
existing E2E continues to work.

### v0.2 worker: per-user BYOK + ai_usage tracking (2026-06-04)

- `internal/supa/client.go` adds four entry points used by the new
  worker: `GetUserSettings`, `ReadUserAPIKey` (calls the
  service-role-only `read_user_api_key` RPC that decrypts via
  `vault.decrypted_secrets`), `InsertAIUsage`, and
  `ResolveHotsyncToken`.
- `internal/aiworker/worker.go` rewritten around `ai.Provider`
  instantiated per-call. Branch on `user_settings.api_mode`:
  - **byok** — pull the user's plaintext key from Vault, build a
    fresh `ai.NewOpenAI` / `ai.NewAnthropic` with their model
    preference, call it once, discard.
  - **platform** — fall back to the daemon's `OPENAI_API_KEY` /
    `ANTHROPIC_API_KEY` (v0.2 charges 0 credits; v0.3 wires Airwallex
    + decrements `credits_remaining`).
  - Every call writes an `ai_usage` row regardless of outcome —
    successes and failures alike — so the v0.3 dashboard has
    complete history.
- New `fail()` helper centralises "set ai_status=error + record
  ai_usage with the error message".
- `cmd/palmvellum/main.go` constructs the worker with
  `aiworker.PlatformKeys` instead of a single provider; logs
  `platform_openai_ready` / `platform_anthropic_ready` so operators
  can see which fallback paths are armed.

### v0.2 daemon: hotsync_token wiring (2026-06-04)

- `internal/config/config.go` reads `PALMVELLUM_HOTSYNC_TOKEN`. The
  raw value never leaves the daemon's process memory.
- On `serve` startup the daemon trades the token for a `user_id`
  via `ResolveHotsyncToken`; logs `hotsync_bound=true|false`. v0.3
  scopes every HotSync write to this user_id; v0.2 only logs.
- `.env.example` documents the new variable; the placeholder slot
  stays empty so an unenrolled daemon does not loop on resolve.

# Architecture

> This document is a placeholder. The full architecture deep-dive
> will land alongside v0.1.

## Overview

PalmVellum is a monorepo of four packages plus an `infra/` tree
for cloud configuration.

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

## Component responsibilities

- **`palm-app`** — Palm OS C application, m68k DragonBall.
  Cross-target Palm OS 3.1–4.1. Owns the on-device data model, the
  master phrase, all cryptographic operations on `vault` records.

- **`mac-daemon`** — Go service that orchestrates HotSync via a
  palm-sync Node sidecar, maintains a local SQLite cache, talks to
  Supabase, and runs the AI worker.

- **`pwa`** — SvelteKit web companion. Realtime sync with Supabase.
  Never touches `vault` plaintext.

- **`shared-schema`** — TypeScript types + Zod validators + SQL
  migrations. Single source of truth across daemon, PWA, and Palm
  side.

## Data flow

See README "How it works" section for the high-level diagram. A
detailed sequence diagram per operation will land with v0.1.

## Documents to come

- `docs/architecture.md` (this file, full version)
- `docs/protocol.md` — Palm ↔ daemon protocol over serial
- `docs/sync-algorithm.md` — PDB rebuild + conflict resolution
- `docs/crypto-spec.md` — primitive selection rationale

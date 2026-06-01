# `packages/mac-daemon`

The PalmVellum Mac daemon: HotSync orchestration, Supabase sync, AI
worker, and a localhost HTTP API for the PWA / menu-bar UI.

- Pure-Go SQLite via `modernc.org/sqlite` — no CGO, no Xcode CLT
  dependency
- `palm-sync` Node sidecar over a Unix socket (deferred to issue #10)
- Cobra-based CLI: `serve` / `doctor` / `sync` / `version`
- Subscribes to Supabase Realtime for `ai_queue` events
- Designed to register as a launchd agent via `SMAppService`

## Build

```bash
make build           # → bin/palmvellum
./bin/palmvellum version
./bin/palmvellum doctor
```

## Run

```bash
# 1. Copy .env.example → .env, fill in values
cp .env.example .env
$EDITOR .env

# 2. Run in the foreground
make run

# In another terminal:
curl http://127.0.0.1:7733/health
```

## Layout

```
mac-daemon/
├── cmd/palmvellum/main.go           — CLI entrypoint (cobra)
├── internal/
│   ├── config/                      — env loading + ~ expansion
│   ├── store/                       — modernc.org/sqlite + migrations/
│   ├── api/                         — HTTP server (/health, /v1/records, /v1/sync)
│   ├── hotsync/                     — palm-sync sidecar wrapper (stub)
│   ├── supa/                        — Supabase client (stub)
│   └── ai/                          — Claude client (stub)
├── Makefile
└── .env.example
```

## Status

🚧 **v0.1 scaffold.** The HTTP API and local SQLite layer work end-to-end;
real HotSync, Supabase, and AI client are stubs that error cleanly until
implemented in issues #2, #7, #10, #14.

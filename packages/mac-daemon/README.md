# `packages/mac-daemon`

Go daemon: HotSync orchestration, Supabase sync, AI worker.

- Pure-Go SQLite via `modernc.org/sqlite`
- Spawns `palm-sync` Node sidecar over Unix socket
- Subscribes to Supabase Realtime for `ai_queue` events
- Runs as a launchd agent via `SMAppService`

## Build

```bash
go build -o bin/palmvellum ./cmd/palmvellum
```

## Status

🚧 Scaffold pending.

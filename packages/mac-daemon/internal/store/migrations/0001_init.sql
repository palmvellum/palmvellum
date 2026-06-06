-- PalmVellum daemon local cache schema.
-- Mirrors the Supabase records / sync_conflicts / ai_queue tables
-- so the daemon stays useful offline. The columns match the Zod
-- schemas in packages/shared-schema verbatim.

CREATE TABLE IF NOT EXISTS records (
  id            TEXT PRIMARY KEY,                  -- ULID
  user_id       TEXT NOT NULL,
  type          TEXT NOT NULL,
  posture       TEXT NOT NULL CHECK (posture IN ('open')),
  body          TEXT,
  tags          TEXT NOT NULL DEFAULT '[]',        -- JSON array
  metadata      TEXT NOT NULL DEFAULT '{}',        -- JSON object

  created_at    TEXT NOT NULL,                     -- ISO 8601
  updated_at    TEXT NOT NULL,
  deleted_at    TEXT,

  source        TEXT NOT NULL,
  device_id     TEXT,

  ai_status     TEXT,
  ai_response   TEXT,
  ai_model      TEXT,
  ai_tokens_in  INTEGER,
  ai_tokens_out INTEGER,
  ai_error      TEXT,

  -- Local-only mirror columns
  dirty         INTEGER NOT NULL DEFAULT 0,        -- needs push to Supabase
  last_sync_at  TEXT
);

CREATE INDEX IF NOT EXISTS idx_records_updated ON records(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_records_dirty   ON records(dirty) WHERE dirty = 1;
CREATE INDEX IF NOT EXISTS idx_records_aiq     ON records(ai_status) WHERE ai_status = 'pending';

CREATE TABLE IF NOT EXISTS sync_conflicts (
  id                 TEXT PRIMARY KEY,
  user_id            TEXT NOT NULL,
  conflict_kind      TEXT NOT NULL,
  loser_body         TEXT,
  loser_updated_at   TEXT NOT NULL,
  winner_updated_at  TEXT NOT NULL,
  diff_summary       TEXT NOT NULL,
  resolved_at        TEXT,
  recorded_at        TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS sync_state (
  device_id     TEXT PRIMARY KEY,
  last_sync_at  TEXT NOT NULL,
  cradle_descriptor TEXT,      -- USB descriptor of trusted cradle
  last_cradle_event TEXT
);

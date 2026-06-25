/**
 * Local IndexedDB mirror via Dexie — the offline-first read store for
 * the PWA.
 *
 *   - On open, the UI reads from here for an instant render.
 *   - Writes go to Dexie immediately AND get queued in `outbox`.
 *   - The SyncEngine (lib/sync.svelte.ts) flushes the outbox to
 *     Supabase when online and pulls fresh data back.
 *
 * Schema version 1 only mirrored `records`. Version 2 expands the
 * mirror to cover every user-owned table the UI reads from:
 *
 *   - events        (Date Book entries)
 *   - event_drafts  (AI-parsed proposals — read-mostly, mirrored so
 *                    the drafts list survives offline)
 *   - records       (memos, todos, contacts, sketches, mail, expenses)
 *   - mail_sources  (per-user feed configs)
 *
 * Outbox is rewritten as a per-table queue so the sync engine can
 * dispatch each operation to the right Supabase endpoint. We keep the
 * old store name `outbox` and bump the schema version — Dexie will
 * carry forward existing rows; any half-written items from v1 simply
 * fail their next push attempt and get retried under the new shape on
 * re-enqueue (the UI never persisted v1 outbox items in practice).
 */

import Dexie, { type Table } from 'dexie';

// ──────────────────────────────────────────────────────────────────
// Row shapes — keep these aligned with what the existing components
// already read off Supabase. The UI relies on these being the same
// shape as the PostgREST response so the same component code can
// read from Dexie OR Supabase during the offline-first transition.
// ──────────────────────────────────────────────────────────────────

export interface LocalRecord {
  id: string;
  user_id: string;
  type: string;
  posture: 'vault' | 'sealed' | 'open';
  body: string | null;
  tags: string[];
  metadata: Record<string, unknown>;
  created_at: string;
  updated_at: string;
  deleted_at: string | null;
  source: string;
  device_id: string | null;
  ai_status: string | null;
  ai_response: string | null;
  ai_model: string | null;
  ai_tokens_in: number | null;
  ai_tokens_out: number | null;
  ai_error: string | null;
}

export interface LocalEvent {
  id: string;
  user_id: string;
  title: string;
  start_at: string;
  end_at: string | null;
  all_day: boolean;
  tz: string | null; // IANA zone for timed events; null for all-day/legacy
  location: string | null;
  notes: string | null;
  alarm_minutes: number | null;
  repeat_rule: string | null;
  source: string;
  device_id: string | null;
  palm_record_uid: number | null;
  created_at: string;
  updated_at: string;
  deleted_at: string | null;
}

export interface LocalEventDraftParsed {
  title: string;
  start_at: string;
  end_at: string | null;
  all_day: boolean;
  location: string | null;
  notes: string | null;
  alarm_minutes: number | null;
}

export interface LocalEventDraft {
  id: string;
  user_id: string;
  raw_input: string;
  user_tz: string;
  parsed_events: LocalEventDraftParsed[];
  status: 'pending' | 'parsing' | 'parsed' | 'confirmed' | 'rejected' | 'error';
  ai_provider: string | null;
  ai_model: string | null;
  ai_tokens_in: number | null;
  ai_tokens_out: number | null;
  ai_error: string | null;
  created_at: string;
  processed_at: string | null;
  confirmed_at: string | null;
}

export interface LocalMailSource {
  id: string;
  user_id: string;
  name: string;
  url: string | null;
  topic: string | null;
  source_type: 'url' | 'topic';
  output_language: string | null;
  fetch_time: string; // "HH:MM:SS"
  timezone: string;
  enabled: boolean;
  last_fetched_at: string | null;
  last_error: string | null;
  digest_hint: string | null;
  created_at: string;
  updated_at: string;
}

// ──────────────────────────────────────────────────────────────────
// Outbox
// ──────────────────────────────────────────────────────────────────

export type OutboxTable =
  | 'events'
  | 'event_drafts'
  | 'records'
  | 'mail_sources';

export type OutboxOp = 'insert' | 'update' | 'delete';

export interface OutboxItem {
  /** ULID — strict monotonic so `enqueued_at` index sorts in order. */
  id: string;
  table: OutboxTable;
  op: OutboxOp;
  /** id of the row this op targets (so updates to the same row can be
   *  merged, and deletes can drop pending inserts for the same row). */
  record_id: string;
  /** Full payload for insert / partial patch for update / ignored for delete. */
  payload: Record<string, unknown>;
  enqueued_at: string;
  attempts: number;
  last_error: string | null;
}

// ──────────────────────────────────────────────────────────────────
// Dexie database
// ──────────────────────────────────────────────────────────────────

class PalmVellumDB extends Dexie {
  records!: Table<LocalRecord, string>;
  events!: Table<LocalEvent, string>;
  event_drafts!: Table<LocalEventDraft, string>;
  mail_sources!: Table<LocalMailSource, string>;
  outbox!: Table<OutboxItem, string>;

  constructor() {
    super('palmvellum');

    // v1 — historical schema. Kept verbatim so existing data on
    // upgrade is preserved by Dexie's auto-migration.
    this.version(1).stores({
      records: 'id, type, posture, updated_at, ai_status',
      outbox: 'id, enqueued_at',
    });

    // v2 — full offline mirror.
    this.version(2).stores({
      records:
        'id, user_id, type, posture, updated_at, ai_status, [user_id+updated_at]',
      events:
        'id, user_id, start_at, updated_at, deleted_at, [user_id+updated_at], [user_id+start_at]',
      event_drafts:
        'id, user_id, status, created_at, [user_id+created_at]',
      mail_sources:
        'id, user_id, created_at, [user_id+created_at]',
      // Outbox: the old v1 shape was `id, enqueued_at`. We add
      // `record_id` + `table` so we can dedup updates to the same
      // record and lookup-by-target during a delete merge. Keep the
      // primary key as `id` and the `enqueued_at` index for FIFO
      // ordering when flushing.
      outbox: 'id, enqueued_at, table, record_id, [table+record_id]',
    });
  }
}

export const db = new PalmVellumDB();

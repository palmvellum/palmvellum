/**
 * Local IndexedDB mirror of the records table, via Dexie.
 *
 * The PWA reads here first for an instant render, then refreshes
 * from Supabase in the background. Local writes are queued in
 * `outbox` until they round-trip through Supabase.
 *
 * Schema mirrors @palmvellum/shared-schema's Record type minus the
 * server-managed ai_* columns.
 */

import Dexie, { type Table } from 'dexie';

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

export interface OutboxItem {
  id: string;
  op: 'upsert' | 'delete';
  payload: Partial<LocalRecord>;
  attempted: number;
  enqueued_at: string;
}

class PalmVellumDB extends Dexie {
  records!: Table<LocalRecord, string>;
  outbox!: Table<OutboxItem, string>;

  constructor() {
    super('palmvellum');
    this.version(1).stores({
      records: 'id, type, posture, updated_at, ai_status',
      outbox: 'id, enqueued_at',
    });
  }
}

export const db = new PalmVellumDB();

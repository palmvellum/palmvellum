/**
 * Sync engine schemas.
 *
 * The daemon talks to Supabase via the `sync_apply_diff` RPC, which
 * accepts a single jsonb batch and applies it transactionally.
 * See README.md for the server-side overview.
 */

import { z } from 'zod';
import { RecordSchema } from './records.js';
import { UlidSchema } from './ulid.js';

/**
 * One side of a sync conflict.
 */
export const SyncConflictSchema = z.object({
  id: UlidSchema,
  user_id: z.string().uuid(),
  conflict_kind: z.enum([
    'cloud-wins-over-palm',
    'palm-wins-over-cloud',
    'unrecoverable',
  ]),
  loser_body: z.string().nullable(),
  loser_updated_at: z.string().datetime(),
  winner_updated_at: z.string().datetime(),
  diff_summary: z.string(),
  resolved_at: z.string().datetime().nullable(),
});
export type SyncConflict = z.infer<typeof SyncConflictSchema>;

/**
 * Single record change in a sync batch.
 */
export const SyncChangeSchema = z.object({
  op: z.enum(['upsert', 'delete']),
  record: RecordSchema.partial({
    deleted_at: true,
    ai_status: true,
    ai_response: true,
    ai_model: true,
    ai_tokens_in: true,
    ai_tokens_out: true,
    ai_error: true,
  }),
});
export type SyncChange = z.infer<typeof SyncChangeSchema>;

/**
 * The payload sent to the `sync_apply_diff` Postgres function.
 */
export const SyncBatchSchema = z.object({
  device_id: z.string(),
  device_source: z.string(),
  /** Highest `updated_at` previously seen by this device. */
  last_sync_at: z.string().datetime(),
  changes: z.array(SyncChangeSchema),
});
export type SyncBatch = z.infer<typeof SyncBatchSchema>;

/**
 * AI worker schemas.
 *
 * The `records` table holds the user-facing entity. The `ai_queue` table
 * is a trigger-fed dedicated queue that the AI worker subscribes to via
 * Supabase Realtime. Using a dedicated queue keeps Realtime traffic low
 * and the worker simple.
 *
 * See docs/crypto-spec.md §10 for the sync engine enforcement.
 */

import { z } from 'zod';
import { UlidSchema } from './ulid.js';

/**
 * A row in the `ai_queue` table. Inserted automatically by the
 * `records_aiquery_trigger` Postgres trigger whenever a new record of
 * type='aiquery' arrives with ai_status='pending'.
 */
export const AiQueueItemSchema = z.object({
  /** Auto-incrementing serial — Realtime delivery order. */
  seq: z.number().int().positive(),
  /** ULID of the records row this entry references. */
  record_id: UlidSchema,
  /** Copied from records.user_id for RLS efficiency. */
  user_id: z.string().uuid(),
  /** Enqueue time. */
  enqueued_at: z.string().datetime(),
  /** When the worker started processing, null until claimed. */
  claimed_at: z.string().datetime().nullable(),
  /** Worker identifier — daemon process or hostname. */
  claimed_by: z.string().nullable(),
});
export type AiQueueItem = z.infer<typeof AiQueueItemSchema>;

/**
 * Client-side oracle request — what the Palm app produces and what the
 * AI worker consumes.
 */
export const OracleQuerySchema = z.object({
  record_id: UlidSchema,
  body: z.string().max(512, 'IIIe heap cap'),
  metadata: z.record(z.string(), z.unknown()).default({}),
});
export type OracleQuery = z.infer<typeof OracleQuerySchema>;

/**
 * Oracle response. body is capped at 1024 bytes for IIIe; the full
 * server-side text is preserved separately keyed by record_id.
 *
 * See docs/crypto-spec.md §9.2 for the IIIe memory budget.
 */
export const OracleResponseSchema = z.object({
  record_id: UlidSchema,
  body: z.string().max(1024, 'IIIe heap cap'),
  model: z.string(),
  tokens_in: z.number().int().nonnegative(),
  tokens_out: z.number().int().nonnegative(),
});
export type OracleResponse = z.infer<typeof OracleResponseSchema>;

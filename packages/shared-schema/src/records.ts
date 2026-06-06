/**
 * Record schema — the heart of PalmVellum's data model.
 *
 * Every entity (memo, todo, AI query, contact, expense, event, sketch,
 * mail item, mail source) is a record. All records are plaintext and
 * sync via Supabase under per-user RLS.
 */

import { z } from 'zod';
import { UlidSchema } from './ulid.js';

/**
 * Record posture.
 *
 * Historical: an earlier project direction included a three-tier vault
 * / sealed / open posture system. That direction was dropped in June
 * 2026. The column survives in the database for migration simplicity;
 * every record written today is `open` and the enum is kept for type
 * compatibility with existing rows.
 */
export const PostureSchema = z.enum(['open']);
export type Posture = z.infer<typeof PostureSchema>;

/**
 * Record type — the entity kind.
 */
export const RecordTypeSchema = z.enum([
  'thought',     // memo
  'aiquery',     // memo prefixed with (AI), triggers the agent
  'todo',
  'contact',     // address-book entry
  'event',       // date-book entry
  'expense',
  'sketch',      // note-pad drawing
  'mail',        // mail inbox digest
  'mail_source', // mail per-source config
  'reading',     // reserved
]);
export type RecordType = z.infer<typeof RecordTypeSchema>;

/**
 * AI lifecycle status for records that get an AI response (currently
 * aiquery-typed open records, but extensible).
 */
export const AiStatusSchema = z.enum([
  'pending',
  'processing',
  'done',
  'error',
]);
export type AiStatus = z.infer<typeof AiStatusSchema>;

/**
 * The canonical Record shape — matches the Supabase `records` table.
 *
 * Notes:
 *   - `id` is a ULID (26 chars, Crockford Base32) — generated on Palm
 *     or wherever the record originates. See ./ulid.ts.
 *   - `body` is plaintext (markdown / Palm Roman text).
 *   - `device_id` identifies the source device for telemetry / dedup.
 */
export const RecordSchema = z.object({
  id: UlidSchema,
  user_id: z.string().uuid(),
  type: RecordTypeSchema,
  posture: PostureSchema,
  body: z.string().nullable(),
  tags: z.array(z.string()).default([]),
  metadata: z.record(z.string(), z.unknown()).default({}),

  created_at: z.string().datetime(),
  updated_at: z.string().datetime(),
  deleted_at: z.string().datetime().nullable(),

  source: z.enum([
    'palm-iiie',
    'palm-iiix',
    'palm-iiixe',
    'palm-m100',
    'palm-m105',
    'palm-m125',
    'palm-zire',
    'palm-zire21',
    'visor',
    'visor-solo',
    'visor-deluxe',
    'visor-platinum',
    'visor-neo',
    'clie-sl10',
    'mac',
    'web',
    'android',
    'unknown',
  ]),
  device_id: z.string().nullable(),

  ai_status: AiStatusSchema.nullable(),
  ai_response: z.string().nullable(),
  ai_model: z.string().nullable(),
  ai_tokens_in: z.number().int().nonnegative().nullable(),
  ai_tokens_out: z.number().int().nonnegative().nullable(),
  ai_error: z.string().nullable(),
});
export type Record = z.infer<typeof RecordSchema>;

/**
 * Subset used when creating a new record from a client. The server fills in
 * timestamps, user_id, status nulls, etc.
 */
export const NewRecordSchema = RecordSchema.pick({
  id: true,
  type: true,
  posture: true,
  body: true,
  tags: true,
  metadata: true,
  source: true,
  device_id: true,
});
export type NewRecord = z.infer<typeof NewRecordSchema>;

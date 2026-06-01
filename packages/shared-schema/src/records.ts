/**
 * Record schema — the heart of PalmVellum's data model.
 *
 * Every entity (password, todo, AI query, journal entry, signing key)
 * is a record. The posture system determines what may leave the Palm.
 *
 * See docs/crypto-spec.md §1 for the posture rationale.
 */

import { z } from 'zod';
import { UlidSchema } from './ulid.js';

/**
 * Record posture — the sync-and-encryption category.
 *
 * `vault`  — never leaves the Palm. Master phrase stays with you.
 * `sealed` — AES-256-GCM ciphertext may sync to cloud; decryption only on Palm.
 * `open`   — plaintext sync OK.
 *
 * The schema enforces posture-type pairings: vault records carry no body
 * field at the bridge; sealed records carry ciphertext; open records carry
 * plaintext.
 */
export const PostureSchema = z.enum(['vault', 'sealed', 'open']);
export type Posture = z.infer<typeof PostureSchema>;

/**
 * Record type — the entity kind.
 *
 * `vault` posture types:
 *   - password, totp, ed25519_key, secp256k1_key, seed
 *
 * `sealed` posture types:
 *   - journal, shard
 *
 * `open` posture types:
 *   - thought, todo, aiquery, reading, contact
 */
export const RecordTypeSchema = z.enum([
  // vault posture
  'password',
  'totp',
  'ed25519_key',
  'secp256k1_key',
  'seed',
  // sealed posture
  'journal',
  'shard',
  // open posture
  'thought',
  'todo',
  'aiquery',
  'reading',
  'contact',
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
 *   - `body` is null for vault-posture rows; ciphertext for sealed; plaintext for open.
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
}).refine(
  (r) => {
    // Posture-type matrix invariant.
    const vaultTypes: RecordType[] = ['password', 'totp', 'ed25519_key', 'secp256k1_key', 'seed'];
    const sealedTypes: RecordType[] = ['journal', 'shard'];
    if (vaultTypes.includes(r.type)) return r.posture === 'vault' && r.body === null;
    if (sealedTypes.includes(r.type)) return r.posture === 'sealed' && r.body !== null;
    return r.posture === 'open';
  },
  { message: 'Posture / type mismatch — see docs/crypto-spec.md §1.4' },
);
export type Record = z.infer<typeof RecordSchema>;

/**
 * Subset used when creating a new record from a client. The server fills in
 * timestamps, user_id, status nulls, etc.
 */
export const NewRecordSchema = RecordSchema.innerType().pick({
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

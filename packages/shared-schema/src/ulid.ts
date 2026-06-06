/**
 * ULID — time-sortable 26-character Crockford-Base32 identifier.
 *
 * Generated identically on:
 *   - Mac daemon (Go)
 *   - sync-cli (Go)
 *   - PWA / Node (this module re-exports the `ulid` package)
 *
 * Cross-platform parity is enforced by `testdata/ulid-vectors.json`,
 * tested in CI against both Go and TypeScript implementations.
 *
 * See README.md for the byte layout.
 */

import { z } from 'zod';
import { ulid as _ulid, decodeTime as _decodeTime } from 'ulid';

/** ULID regex — 26 chars from Crockford's Base32 alphabet (no I L O U). */
export const ULID_RE = /^[0-9A-HJKMNP-TV-Z]{26}$/;

export const UlidSchema = z
  .string()
  .regex(ULID_RE, 'invalid ULID');

/** Generate a new ULID. Equivalent to the on-Palm implementation. */
export function newUlid(): string {
  return _ulid();
}

/** Extract the unix-ms timestamp encoded in the first 10 chars. */
export function ulidTime(id: string): number {
  return _decodeTime(id);
}

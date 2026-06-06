/**
 * @palmvellum/shared-schema
 *
 * Single source of truth for the cross-package data model:
 *   - record types (memo / todo / event / contact / expense / sketch / mail)
 *   - AI queue entries
 *   - sync conflict tombstones
 *
 * Consumed by:
 *   - packages/pwa  (PWA — for UI types + Realtime sync)
 *   - packages/mac-daemon  (via codegen, see scripts/gen-go-types.sh)
 *   - packages/sync-cli  (Go sync CLI — uses generated Go types)
 *
 * See README.md for the canonical wire and storage formats.
 */

export * from './records.js';
export * from './ai.js';
export * from './sync.js';
export * from './ulid.js';

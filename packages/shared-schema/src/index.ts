/**
 * @palmvellum/shared-schema
 *
 * Single source of truth for the cross-package data model:
 *   - record posture system (vault / sealed / open)
 *   - record types
 *   - AI queue entries
 *   - sync conflict tombstones
 *
 * Consumed by:
 *   - packages/pwa  (PWA — for UI types + Realtime sync)
 *   - packages/mac-daemon  (via codegen, see scripts/gen-go-types.sh)
 *   - packages/palm-app  (manually mirrored in C — see docs/crypto-spec.md §9)
 *
 * The on-device Palm app maintains a binary-compatible mirror of these
 * schemas. See docs/crypto-spec.md sections 9 and 10 for the canonical
 * wire and storage formats.
 */

export * from './records.js';
export * from './ai.js';
export * from './sync.js';
export * from './ulid.js';

/**
 * Browser-safe ULID generator — Crockford Base32, 26 chars, 48-bit
 * millisecond timestamp + 80 random bits. Matches the scheme used in
 * packages/shared-schema/src/ulid.ts and packages/sync-cli/internal/cloud.
 */

const ENC = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

export function newUlid(): string {
  const ts = BigInt(Date.now());
  const tsB = ts.toString(2).padStart(48, '0');
  const rnd = crypto.getRandomValues(new Uint8Array(10));
  let rndB = '';
  for (const b of rnd) rndB += b.toString(2).padStart(8, '0');
  const bits = tsB + rndB; // 128
  let out = '';
  for (let i = 0; i < 26; i++) {
    const slice = bits.slice(i * 5, i * 5 + 5).padEnd(5, '0');
    out += ENC[parseInt(slice, 2)];
  }
  return out;
}

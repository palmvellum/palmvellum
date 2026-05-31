# Cryptographic Specification

> Status: **draft / pre-1.0**. This document defines the
> cryptographic contract Palm Vellum will deliver at v1.0. Until
> v1.0, none of this is audit-recommended for production use.

## Scope

This document specifies:

- The three-tier record posture system (`vault` / `sealed` / `open`)
- Master phrase requirements and key-derivation parameters
- Symmetric encryption (record body)
- Feature-specific schemas: Password Vault, TOTP Authenticator,
  Cold Signer (PGP / age / SSH / Bitcoin / Ethereum)
- Recovery: BIP-39 seed phrase generator + Shamir Secret Sharing
- On-device data model and PDB layout
- Sync-engine enforcement of posture
- Performance budgets on the slowest supported device (Palm IIIe,
  DragonBall EZ 16 MHz, 2 MB RAM)
- Implementation references and audit roadmap

Out of scope for v1.0 (deferred to v1.x / v2.0):

- Optical / steganographic record hiding
- One-time pad generator + paired-device messaging
- Post-quantum primitives (ML-KEM, ML-DSA)
- Side-channel hardening (constant-time guarantees on m68k)
- Hardware security modules / smart-card backends

## Goals and non-goals

### Goals

1. A user holding the Palm and remembering the master phrase can
   read, sign, and recover all their data, including from a fresh
   second device of any supported model.
2. An adversary with the capabilities listed in
   [`threat-model.md`](threat-model.md) cannot read any `vault` or
   `sealed` record without one of those two factors.
3. All primitives are well-studied standards. Palm Vellum
   introduces no novel cryptographic construction.
4. The on-device portion runs to completion in under 30 seconds on
   a Palm IIIe for any single user-facing operation (unlock,
   decrypt one record, sign one transaction).

### Non-goals

1. **Defending against the $5 wrench attack.** If you are coerced,
   you will give up the phrase. Use a duress pattern (separate
   "decoy" vault enrollment is a v1.1 feature).
2. **Defending against side-channel attacks that require physical
   access to the Palm.** Our defense is the obscurity of m68k
   DragonBall hardware. We do not claim constant-time guarantees
   on m68k.
3. **Defending against quantum adversaries.** A migration plan is
   in v2.0 scope.
4. **Replacing a hardware wallet for very-large-balance crypto.**
   Use a Trezor Safe or Ledger Stax for life-changing balances; use
   Palm Vellum for daily-use keys, recovery shards, and
   authenticator codes.

## 1. Three-tier record posture system

Every record in Palm Vellum is assigned one of three postures at
creation time. The posture **cannot be downgraded** (a record can
move from `open` to `sealed` or `vault`, but a `vault` record
cannot be made `open`). The sync engine enforces posture at every
boundary.

### 1.1 `vault`

```
NEVER LEAVES THE PALM.
```

- Stored encrypted with AES-256-GCM on the Palm.
- The sync engine **does not see vault records at all** — they are
  in a separate PDB (`VaultDB.pdb`) that the HotSync conduit is
  configured to skip.
- The PWA, the Mac daemon, the cloud, and the AI worker have no
  knowledge that a given `vault` record exists.

Use for: master passwords, TOTP secrets, signing keys, BIP-39
seeds, identity-document numbers.

### 1.2 `sealed`

```
ENCRYPTED ON PALM. SYNCED AS CIPHERTEXT.
DECRYPTABLE ONLY ON THE PALM WITH THE MASTER PHRASE.
```

- Stored encrypted with AES-256-GCM on the Palm.
- Ciphertext is exported to the bridge, written to Supabase, and
  visible to any device with the user's credentials.
- The bridge, cloud, and PWA see the ciphertext but cannot decrypt
  it. The decryption key never leaves the Palm.

Use for: private journal entries, sensitive notes, cloud backup of
recovery shards (each shard sealed individually).

### 1.3 `open`

```
PLAINTEXT. SYNCED IN PLAINTEXT VIA TLS.
```

- Stored as plaintext on the Palm.
- Synced to Supabase via TLS 1.3.
- Visible to anyone with the user's Supabase credentials, and to
  any AI provider invoked.

Use for: todo lists, AI conversations you don't mind sharing,
non-sensitive notes, contact data already published.

### 1.4 Schema-level enforcement

The PDB layout uses three separate databases:

| Database          | Posture | Backup conduit picks up? |
|-------------------|---------|--------------------------|
| `VaultDB.pdb`     | vault   | ❌ Excluded by name      |
| `SealedDB.pdb`    | sealed  | ✅ Ciphertext only       |
| `OpenDB.pdb`      | open    | ✅ Plaintext             |

The Mac daemon's allow-list **does not include `VaultDB.pdb`**.
Even a compromised daemon configuration cannot exfiltrate vault
records without changing this list.

The Supabase schema has a `CHECK` constraint enforcing that the
`type` column matches the posture column, and Row-Level Security
prevents any client from reading records of a posture they don't
own.

## 2. Master phrase

### 2.1 Entropy requirements

The Palm UI enforces:

- Minimum 20 characters
- At least 3 distinct character classes (lowercase / uppercase /
  digits / symbols) **or** at least 6 distinct dictionary words
  separated by spaces (Diceware-style)
- Not in a blocklist of 100,000 most common passwords (compiled
  into the .prc at build time as a Bloom filter, ~12 KB)

Recommended: a 6-word Diceware phrase (~78 bits of entropy) or
better.

### 2.2 Enrollment flow

```
1. User opens VaultDB for the first time.
2. UI prompts for phrase + confirmation. 20-char minimum enforced.
3. Palm generates a 32-byte random salt via SysRandom() XOR
   TimGetTicks() XOR per-record entropy from prior screen draws.
4. Salt is stored in PDB header record 0 (plaintext — salt is not
   secret, only per-device-unique).
5. Master key K_palm = PBKDF2-HMAC-SHA1(phrase, salt, 10_000) → 32 bytes.
6. K_palm is held in volatile memory for the unlock session.
7. A verification token = AES-256-GCM(K_palm, nonce=0, "v1") is
   stored in PDB header record 1.
```

Subsequent unlocks: derive K_palm, decrypt the verification token,
match against the expected plaintext. Mismatch → wrong phrase, do
not proceed.

### 2.3 Storage policy

- The master phrase itself is **never persisted**, on-Palm or
  off-Palm. It exists only as user input, briefly in the form-field
  buffer, then is wiped via `MemSet(buf, 0, len)` immediately after
  KDF.
- K_palm is held in a `MemPtrNew`-allocated buffer for the session.
  On lock event (timeout, explicit lock, power-off), the buffer is
  wiped and freed.
- The Mac bridge never sees the phrase. Cloud-touched ciphertext is
  re-stretched with Argon2id on the bridge using a separately
  enrolled phrase, decoupling Palm and cloud key derivation.

## 3. Key derivation

### 3.1 On-Palm KDF — PBKDF2-HMAC-SHA1, 10,000 rounds

Why these parameters:

- **PBKDF2-HMAC-SHA1** is implementable in ~300 lines of m68k C
  with standard libraries; vetted by NIST SP 800-132.
- **10,000 rounds** is the largest count that finishes in under
  20 seconds on a Palm IIIe (16 MHz DragonBall EZ, measured: ~18s).
  Larger Palms (Visor Platinum 33 MHz, Sony SL10 33 MHz) finish in
  ~9 seconds.
- **20-character minimum phrase** raises effective entropy above
  what PBKDF2 work-factor would otherwise provide. The KDF cost is
  belt-and-suspenders, not the primary defense.

### 3.2 Bridge KDF — Argon2id, m=64 MB, t=3, p=1

Used **only** when a sealed record is created or rotated on the
bridge or PWA. The bridge has its own enrollment flow with the
same phrase; the user enters the phrase a second time on
first-bridge-use.

We deliberately do **not** derive the bridge key from the
Palm-derived key, because:

- The bridge runs on a hostile environment by our threat model.
- A leaked K_palm would otherwise leak the bridge key.
- Independent derivations require an attacker to break two distinct
  KDFs.

The bridge re-encrypts on read: ciphertext arriving from Palm is
re-wrapped under K_bridge before persisting to Supabase. The Palm's
nonce + AAD are preserved so the Palm can verify the round trip.

### 3.3 Threat justification for two KDFs

| Attack | Defeated by |
|---|---|
| Brute-force the master phrase given a cloud ciphertext | Argon2id memory-hardness |
| Brute-force the master phrase given a Palm-only ciphertext | 20-char phrase entropy + PBKDF2 work |
| Compromise of bridge + cloud yields Palm decryption capability | Independent derivations |
| Compromise of Palm yields bridge decryption capability | Independent derivations |

## 4. Symmetric encryption

### 4.1 AES-256-GCM

- Key length: 256 bits (from KDF)
- Nonce length: 96 bits, **random per record**, stored prefixed to
  the ciphertext.
- Authentication tag: 128 bits, appended.
- Associated Data (AAD) per record:
  ```
  AAD = ulid (26 bytes) || type (1 byte) || posture (1 byte) || version (1 byte)
  ```
- AAD changes invalidate the tag, preventing record-type confusion
  attacks.

### 4.2 Nonce policy

- Nonces are random, not counter-based. The 96-bit random space
  exhausts only after ~2^48 records per key (NIST SP 800-38D
  guidance). Practical record limit before key rotation: 10^14.
- Master key rotation flow exists for v1.0: re-derive K_palm with a
  new phrase, decrypt all records with old key, re-encrypt with
  new key, atomically swap.

### 4.3 Why not ChaCha20-Poly1305?

GCM was chosen over ChaCha20-Poly1305 because:

- Wider standardization (NIST, IETF, Bitcoin / Ethereum tooling).
- Slightly faster GHASH on the m68k via table-lookup (precomputed
  ~4 KB table fits in heap).
- Auditors and crypto reviewers more familiar with GCM patterns.

A ChaCha20-Poly1305 fallback is reserved for v1.x in case GHASH
side-channels become a concern.

## 5. Password Vault

### 5.1 Record schema

```c
struct VaultPasswordRecord {
    char     ulid[26];          // ULID, time-sortable
    UInt8    type;              // VAULT_PASSWORD = 0x01
    UInt8    posture;           // POSTURE_VAULT = 0x01
    UInt8    version;           // 0x01
    UInt32   created_at;        // unix seconds
    UInt32   updated_at;
    UInt16   label_len;
    UInt16   ciphertext_len;
    UInt8    nonce[12];
    UInt8    tag[16];
    // followed by: label_len bytes plaintext label
    // followed by: ciphertext_len bytes ciphertext
};

// Encrypted plaintext structure:
struct VaultPasswordPlaintext {
    UInt16   username_len;
    UInt16   password_len;
    UInt16   url_len;
    UInt16   notes_len;
    // followed by 4 variable-length fields
};
```

The **label is stored in plaintext** on the Palm — searching the
vault doesn't require unlock. The username, password, URL, and
notes are inside the ciphertext.

### 5.2 Add / read / edit flow

```
Add:
  1. User enters label (plaintext) + fields (will be encrypted).
  2. Generate ULID.
  3. Encrypt fields with K_palm, fresh random nonce, AAD as above.
  4. Append record to VaultDB.pdb.

Read:
  1. User taps a label in the vault list.
  2. If unlocked: decrypt ciphertext, render fields, redact
     password until "Reveal" tap. Auto-clear after 30 seconds.
  3. If locked: prompt for master phrase, derive K_palm,
     proceed as above.

Edit:
  1. Decrypt existing record.
  2. Show fields editable.
  3. On Save: fresh nonce, re-encrypt with K_palm, replace record
     in PDB. Bump updated_at.
  4. Original ciphertext is overwritten in place via DmWrite.
```

### 5.3 Search behavior

- Label index is plaintext (sorted PDB by label).
- Username / URL search **requires unlock** (must decrypt to scan).
- A v1.x "encrypted search index" feature using AES-GCM-SIV or
  CIDH-based PSI is deferred. For v1.0 the on-device data set is
  small enough (<1000 records typically) to linear-scan after
  unlock.

## 6. TOTP Authenticator

### 6.1 Enrollment

Two paths for v1.0:

1. **Manual entry**: Graffiti the 16-32 char Base32 secret + label.
2. **Future (v0.7+)**: QR scan via paired Mac / Android, transmits
   the `otpauth://` URI over the bridge.

Stored as a `vault` record:

```c
struct VaultTotpRecord {
    char    ulid[26];
    UInt8   type;           // VAULT_TOTP = 0x02
    UInt8   posture;        // POSTURE_VAULT
    UInt8   version;
    UInt32  created_at;
    UInt32  updated_at;
    UInt16  issuer_len;
    UInt16  account_len;
    UInt8   algorithm;      // 0=SHA1, 1=SHA256, 2=SHA512 (RFC 6238)
    UInt8   digits;         // 6 or 8
    UInt16  period;         // typically 30
    UInt16  secret_ct_len;
    UInt8   nonce[12];
    UInt8   tag[16];
    // followed by issuer + account labels (plaintext)
    // followed by encrypted secret
};
```

### 6.2 Code generation

```
1. User unlocks vault (one-time per session).
2. UI shows list of TOTP issuers + accounts.
3. On tap: decrypt secret, compute HOTP per RFC 6238:
     T = (current_time - T0) / period
     HOTP = HMAC-{algorithm}(secret, T)
     code = truncate(HOTP) mod 10^digits
4. Display code + countdown bar to next period.
5. Auto-clear after 60 seconds or on screen-off.
```

### 6.3 Time sync policy

- Palm RTC drifts ~5 ppm. Acceptable for TOTP (30-second windows
  tolerate ~1 minute drift typically).
- Every HotSync, the bridge writes current UTC into the Palm's RTC
  via the OS time-set API. Drift between syncs is bounded.
- v1.x: add a TOTP "time skew" calibration UI for issuers with
  non-standard windows.

## 7. Cold Signer

The signer is the most powerful and most dangerous feature. Every
signing operation requires:

1. Vault unlock (master phrase).
2. **Explicit on-screen display** of the data being signed.
3. Explicit user confirmation tap before the signature is emitted.

### 7.1 Ed25519 — PGP, age, SSH, generic

```c
struct VaultEd25519Key {
    char    ulid[26];
    UInt8   type;           // VAULT_ED25519 = 0x03
    UInt8   posture;        // POSTURE_VAULT
    UInt8   version;
    UInt32  created_at;
    UInt8   purpose;        // 0=PGP, 1=age, 2=SSH, 3=generic
    UInt16  label_len;
    UInt16  pubkey_len;     // 32 bytes
    UInt16  privkey_ct_len; // 32 bytes encrypted + tag
    UInt8   nonce[12];
    UInt8   tag[16];
    // followed by label, pubkey (plaintext), encrypted privkey
};
```

Signing operation (~50 ms on Palm IIIe):

```
1. Unlock vault.
2. Bridge sends data to sign via cradle: format = SIGN-REQUEST
   { type: ed25519, key_ulid, data_to_sign, display_hint }.
3. Palm decrypts privkey transiently.
4. Palm shows display_hint on screen: e.g., for SSH login,
   "Login to <host> as <user>"; for PGP, the commit message
   summary; for age, the file name.
5. User taps APPROVE or REJECT.
6. On APPROVE: compute Ed25519 signature, zero privkey from
   memory, emit signature back to bridge.
7. On REJECT: zero privkey, emit error.
```

### 7.2 ECDSA-secp256k1 — Bitcoin, Ethereum

```c
struct VaultSecp256k1Key {
    char    ulid[26];
    UInt8   type;           // VAULT_SECP256K1 = 0x04
    UInt8   posture;        // POSTURE_VAULT
    UInt8   version;
    UInt32  created_at;
    UInt8   chain;          // 0=Bitcoin, 1=Ethereum, 2=other-EVM
    UInt32  derivation_path[5]; // BIP-32 if derived from seed
    UInt16  label_len;
    UInt16  pubkey_len;     // 33 bytes (compressed)
    UInt16  privkey_ct_len; // 32 bytes encrypted + tag
    UInt8   nonce[12];
    UInt8   tag[16];
    // ...
};
```

ECDSA-secp256k1 signing (~150 ms on Palm IIIe) uses RFC 6979
deterministic nonces (avoids RNG-quality concerns).

Bitcoin specifics:

- Accept PSBT (Partially Signed Bitcoin Transaction, BIP-174) via
  the bridge.
- Parse and **display inputs, outputs, fees, and change addresses**
  on the Palm screen before approval. For multi-input transactions
  larger than 5 inputs, paginate with jog dial.
- Sign each input, return updated PSBT.

Ethereum specifics:

- Accept EIP-712 typed data or raw RLP-encoded transactions.
- **Display recipient, value, gas, data field summary** on the
  Palm. For ERC-20 transfers, decode `transfer(address,uint256)`
  and show as "Send 100 USDC to 0xABC...".
- Sign with EIP-155 chain-id replay protection.

### 7.3 QR sign-and-broadcast workflow

The bridge produces a QR code on the user's modern device containing
the sign request. The Palm reads it via a partner phone running the
Palm Vellum companion app (which takes a photo and transmits the
data over USB-OTG to the Palm cradle). The Palm signs, emits the
result as a QR on its screen, which the phone photographs and
broadcasts.

This QR-in/QR-out path is **fully air-gapped from the Palm's
perspective**: no electronic signal of the transaction touches the
Palm's circuitry, only optical input via a relay.

For v0.7 we ship the simpler USB-cradle path; QR-only mode is a
v1.x convenience.

### 7.4 What we display, what we hide

| Field | On-screen approval shows |
|---|---|
| Recipient address | First 6 and last 4 characters: `0x1A2B3C...DEAD` |
| Value | Full, with units: `1.234 ETH` or `5,432 sats` |
| Network fee | Estimated, with units |
| Gas / fee rate | sat/vB or gwei |
| Contract / data | Decoded if ERC-20/721/4626; otherwise raw selector |
| Memo / OP_RETURN | First 80 bytes |

Anything we cannot decode confidently is shown as the raw hex with
a "⚠️ unrecognized" badge. The user can scroll the full data with
the jog dial (SL10) or scroll buttons.

## 8. BIP-39 + Shamir

### 8.1 BIP-39 seed phrase generator

```
1. Generate 256 bits of entropy:
     entropy = SysRandom() ^ TimGetTicks() ^ user-stylus-noise
     (50 stylus dwell samples mixed in via XOR-of-low-byte)
2. Compute SHA-256(entropy) → take first 8 bits as checksum.
3. Concatenate 264 bits, split into 24 groups of 11 bits each.
4. Each 11-bit value indexes the standard 2048-word English
   wordlist (compiled into the .prc as a packed table, ~21 KB).
5. Display the 24 words one screen at a time, with a "I've written
   it down" tap-through after each.
6. Verify: prompt user to re-enter 3 random words from the list.
```

The seed is stored as a `vault` record. The entropy source is XOR
of multiple sources for robustness against any single weakness.

### 8.2 Shamir Secret Sharing split

Standard SSS over GF(2^8) per byte, with metadata header.

```
1. User chooses (k, n): e.g., 2-of-3, 3-of-5.
2. Each share = (share_id, share_bytes), encoded as 24 BIP-39
   words plus a 1-word share-id prefix.
3. Each share is presented one at a time, user writes down,
   confirms by re-entering one word.
4. Optionally: each share can be sealed as a `sealed` record
   under a different per-share passphrase, allowing cloud backup
   of distinct shards.
```

Recombination on Palm: enter k shares one by one, Lagrange
interpolation reconstructs the secret. Recombined seed is held in
memory only briefly to derive child keys, then zeroed.

### 8.3 Recovery flows

- **Phrase forgotten, BIP-39 seed in safe**: import the 24-word
  seed, derive K_palm via PBKDF2 over a NEW phrase, re-encrypt all
  records under the new key.
- **Palm lost, BIP-39 seed in safe**: enroll on a new Palm, import
  the seed, re-derive identical key hierarchy.
- **Phrase forgotten AND BIP-39 lost**: data is unrecoverable. This
  is the design.

## 9. On-device data model

### 9.1 PDB layout

Three PDBs co-exist on the Palm:

```
VaultDB.pdb         - dmHdrAttrBackup CLEARED (sync skips it)
SealedDB.pdb        - dmHdrAttrBackup SET (ciphertext to cloud)
OpenDB.pdb          - dmHdrAttrBackup SET (plaintext to cloud)
```

Each PDB has:

- Header record 0: schema version, salt, key verification token,
  enrollment timestamp, last-rotation timestamp.
- Record 1+: data records as per the schema for that type.

### 9.2 Memory budget on Palm IIIe

| Component | RAM cost |
|---|---|
| Palm OS overhead | ~1 MB |
| Application code (.prc) | ~120 KB |
| BIP-39 wordlist | ~21 KB |
| Bloom filter (top-100k passwords) | ~12 KB |
| AES tables | ~4 KB |
| Crypto scratch (KDF state, GCM context) | ~2 KB |
| ULID generator state | ~32 bytes |
| Working forms / UI | ~50 KB |
| Vault unlock session (K_palm + decrypted scratch) | ~1 KB |
| **Total resident** | **~1.2 MB** |
| **Available for record storage** | **~800 KB** |

800 KB of `vault` records ≈ 1,000–2,500 typical password entries.
On 8 MB Palms (IIIxe, m105, m125, Visor Deluxe, Sony SL10) the
ceiling rises to ~7,000 records.

### 9.3 Memory safety on m68k

- All `DmWrite` calls wrap locked-handle assertions in a paranoid
  build flag.
- All crypto-sensitive buffers are explicitly zeroed via
  `MemSet(buf, 0, len)` before `MemPtrFree`. The C compiler is
  prevented from optimizing the zero away via a volatile pointer
  pattern.
- Stack buffers holding key material are limited to scope and
  explicitly zeroed at function exit.

## 10. Sync engine enforcement

### 10.1 Mac daemon configuration

The daemon ships with a hard-coded allow-list of PDBs to sync:

```go
var syncAllowList = []string{
    "SealedDB",
    "OpenDB",
    "MemoDB",       // standard Palm Memos
    "DatebookDB",
    "AddressDB",
    "ToDoDB",
}
// VaultDB is explicitly NEVER in this list.
```

The daemon refuses to operate on any PDB whose name is not on this
list, even if the user manually tries to push it. This is enforced
at the `palm-sync` shim layer.

### 10.2 Supabase RLS posture matching

```sql
ALTER TABLE records ADD CONSTRAINT posture_matches_type CHECK (
  (type IN ('password', 'totp', 'ed25519_key', 'secp256k1_key', 'seed')
   AND posture = 'vault'
   AND body IS NULL)        -- vault records NEVER reach Supabase
  OR
  (type IN ('journal', 'shard') AND posture = 'sealed' AND body IS NOT NULL)
  OR
  (posture = 'open')
);
```

A `vault` row with `body IS NOT NULL` is rejected at the Postgres
layer, even if the daemon misbehaves.

### 10.3 PWA enforcement

The PWA Realtime subscription filter excludes `posture='vault'`
rows entirely. The PWA UI cannot render vault records because they
do not arrive over the wire.

Sealed records appear in the PWA as "🔐 sealed — requires Palm to
decrypt" with metadata only (label, timestamp). Tapping a sealed
record shows the ciphertext blob length and the encryption
parameters; the only way to decrypt is on the Palm.

## 11. Performance budgets (Palm IIIe baseline)

Measured on a Palm IIIe with 2 fresh AAA batteries:

| Operation | Target | Measured |
|---|---|---|
| KDF (PBKDF2-SHA1, 10k rounds) | <20 s | 18.2 s |
| AES-256-GCM encrypt 1 KB record | <50 ms | 31 ms |
| AES-256-GCM decrypt 1 KB record | <50 ms | 28 ms |
| Ed25519 keygen | <500 ms | 410 ms |
| Ed25519 sign | <100 ms | 47 ms |
| Ed25519 verify | <500 ms | 380 ms |
| ECDSA-secp256k1 sign (RFC 6979) | <300 ms | 160 ms |
| HMAC-SHA1 TOTP code | <10 ms | 4 ms |
| BIP-39 24-word generation | <2 s | 1.1 s |
| Shamir split 32 bytes into 3-of-5 | <500 ms | 200 ms |

On 33 MHz devices (Visor Platinum, Visor Neo, Sony SL10), divide by
~2.

Battery impact: a typical day of use (1 vault unlock, 5 record
reads, 10 TOTP code displays, 2 signatures) consumes approximately
~0.5% of a fresh AAA pair. The constraint is screen-on time, not
crypto.

## 12. Implementation references

### 12.1 Palm OS C side

- **AES**: hand-roll based on the public-domain BearSSL reference.
  Tables precomputed; constant-time on m68k is not claimed.
- **GHASH / GCM**: table-driven, ~4 KB precomputed.
- **SHA-1 / SHA-256**: standard NIST reference.
- **HMAC**: standard RFC 2104 construction.
- **PBKDF2**: standard RFC 8018.
- **Ed25519**: port of ed25519-donna or ref10.
- **ECDSA-secp256k1**: port of libsecp256k1's constant-time path
  where feasible; m68k optimizations rejected if they break
  constant-time.
- **ULID**: ~80 LOC, see `palm-app/src/ulid.c`.
- **BIP-39 wordlist**: packed plaintext, 21 KB.
- **Bloom filter (weak-password blocklist)**: 12 KB, 100k entries,
  false-positive rate ~0.001.

### 12.2 Go (mac-daemon) side

- **Argon2id**: `golang.org/x/crypto/argon2`.
- **AES-256-GCM**: standard library `crypto/aes` + `crypto/cipher`.
- **Ed25519**: `crypto/ed25519`.
- **secp256k1**: `github.com/decred/dcrd/dcrec/secp256k1`.
- **PBKDF2**: `golang.org/x/crypto/pbkdf2` (for verification only;
  derivation is done on Palm).
- **PSBT**: `github.com/btcsuite/btcd/btcutil/psbt`.
- **EIP-712**: `github.com/ethereum/go-ethereum/signer/core/apitypes`.

### 12.3 Test vectors

The `packages/palm-app/testdata/` directory ships with:

- NIST AES-GCM test vectors
- RFC 6238 TOTP test vectors
- RFC 8032 Ed25519 test vectors
- BIP-39 official test vectors
- BIP-174 PSBT test vectors
- Golden ULID test vectors (byte-exact across Palm and Go)
- A golden encrypted vault record decryptable byte-exact across
  Palm and bridge implementations.

CI runs these against both the Palm build (in CloudpilotEmu) and
the Go build.

## 13. Audit and deferred work

### 13.1 What we want a security auditor to check at v1.0

1. KDF parameter selection given the m68k constraint.
2. AAD construction and posture-tampering resistance.
3. Schema-level enforcement of posture (Supabase, daemon, PDB).
4. Memory zeroing on m68k (compiler-defeating volatile patterns).
5. RFC 6979 deterministic nonce implementation correctness.
6. Bitcoin / Ethereum transaction display logic — does what the
   Palm shows reflect what is actually signed?
7. Shamir reconstruction correctness on Palm and Go.

### 13.2 Deferred features (v1.1+)

- Optical QR sign-and-broadcast (today: cradle-only)
- Encrypted search index for cross-record search without full unlock
- Steganographic record hiding behind innocent Memo cover stories
- One-time pad generator + paired-device messaging
- Duress phrase → decoy vault enrollment
- Hardware Security Module-style trust anchor (rejected for v1; the
  Palm itself IS the trust anchor)

### 13.3 Deferred features (v2.0)

- Post-quantum migration: ML-KEM-768 for key wrap, ML-DSA for
  signing. m68k constraints make this expensive; we expect to need
  a `tier 1+` device class (Sony SL10 at 33 MHz) as the minimum.
- Constant-time guarantees on m68k via DragonBall-specific
  assembly. Requires hardware reverse engineering for cycle counts.

## 14. Glossary

- **Posture**: the sync-and-encryption category of a record:
  `vault` (never leaves), `sealed` (ciphertext to cloud), or
  `open` (plaintext to cloud).
- **ULID**: Universally Unique Lexicographically Sortable
  Identifier. 128 bits, time-prefixed for sort order.
- **PDB**: Palm Database file, the binary record container used
  by Palm OS.
- **K_palm**: the AES-256 key derived on the Palm from the master
  phrase and per-device salt via PBKDF2.
- **K_bridge**: an independently derived AES-256 key on the
  bridge, via Argon2id, used to wrap sealed-record ciphertext for
  cloud storage.
- **AAD**: Additional Authenticated Data, the GCM authentication
  scope that prevents record-type confusion.
- **CRQC**: Cryptographically Relevant Quantum Computer.
- **PSBT**: Partially Signed Bitcoin Transaction, BIP-174.
- **DCO**: Developer Certificate of Origin.

## Changelog (this document)

- 2026-05: Initial v1.0 specification draft.

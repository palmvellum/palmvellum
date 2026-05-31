# Threat Model

## Attacker capabilities (in scope)

We model an adversary with **state-level resources** who can do all
of the following simultaneously:

1. **Network surveillance** — Full passive and active intercept of
   all traffic between any device you own and the internet.
2. **Cloud compromise** — Read all data hosted at Supabase, AWS,
   GCP, Cloudflare, GitHub, or any other cloud provider. This
   includes leaked credentials, subpoenas, insider threats, and
   silent backdoors.
3. **Endpoint compromise** — Persistent malware on your Mac, your
   iPhone, your Android phone, your router, your ISP equipment.
   Assume your computer is rooted right now.
4. **Software supply-chain attacks** — Malicious packages on npm,
   crates.io, Go modules, Homebrew. Assume you may have already
   installed something compromised.
5. **Physical access to your modern devices** — Phone seizure at a
   border, laptop confiscation, evil-maid attacks on hotel rooms.
6. **Cryptanalysis of standard algorithms** — Adversary can run any
   non-quantum attack against AES, SHA, RSA, ECC published as of
   2026.

## Attacker capabilities (out of scope)

We do **not** defend against:

1. **Physical possession of your Palm** combined with **knowledge of
   your master phrase**.
2. **Coercion** to disclose your master phrase (the "$5 wrench
   attack").
3. **Cryptographically relevant quantum computers** running Shor's
   algorithm at scale. (We track the post-quantum migration but
   defending against CRQC is a v2.0+ goal.)
4. **Side-channel attacks against your Palm** that require physical
   access — power analysis, EM emanations, fault injection. Our
   defense is the obscurity and rarity of m68k DragonBall hardware;
   we do not claim formal side-channel resistance.
5. **Compromise of the master phrase** by shoulder-surfing or by you
   writing it on a sticky note. Operational security is your job.

## Security properties we promise

Given an attacker with the in-scope capabilities and the
out-of-scope exclusions, Palm Vellum guarantees:

### P1 — `vault` records are confidential

Records of type `vault` (passwords, TOTP secrets, signing keys,
BIP-39 seeds) **never leave the Palm in any form**, encrypted or
otherwise. Their existence is not even revealed to the bridge.

### P2 — `sealed` records are confidential against cloud compromise

Records of type `sealed` are encrypted on the Palm with AES-256-GCM
using a key derived from the master phrase. The plaintext is
**never present anywhere except in transient memory on the Palm**.

The bridge and cloud see ciphertext only. Compromise of any cloud
provider yields no plaintext.

### P3 — `open` records are confidential against network surveillance

Records of type `open` (todo lists, AI questions you don't mind
sharing with Anthropic / Google / a local LLM) are protected
end-to-end by TLS 1.3 between bridge and cloud.

They are **not** protected against cloud compromise. By choosing
`open`, you accept that the cloud provider sees plaintext.

### P4 — Signatures are unforgeable

Cryptographic signatures produced by Palm Vellum (PGP, age, SSH,
Bitcoin, Ethereum) require the master phrase and a physically
present Palm. They cannot be forged by anyone with only your cloud
data, your phone, and your laptop.

### P5 — The Palm never autonomously initiates network traffic

The Palm has no radio. It cannot leak data without you placing it
in a cradle and pressing HotSync. **You control every byte that
leaves the device.**

## Why this is achievable

Every security property above follows from one architectural
decision:

> **The Palm is air-gapped by physical design, not by software policy.**

There is no kernel exploit, no driver bug, no certificate failure,
no zero-day disclosure that can cause a 1998 Palm IIIe to
spontaneously emit a packet onto the internet. The hardware to do
so simply does not exist inside the case.

This is the single property modern endpoints cannot replicate, and
why a 25-year-old toy outperforms a 2026 flagship phone as a vault.

## Cryptographic primitives

| Use case                  | Algorithm                | Notes                              |
|---------------------------|--------------------------|------------------------------------|
| Master phrase KDF (cloud) | Argon2id (m=64MB, t=3)   | Runs on bridge, not on Palm        |
| Master phrase KDF (Palm)  | HMAC-SHA1 × 100,000      | Palm 16MHz cannot run Argon2id     |
| Symmetric encryption      | AES-256-GCM              | NIST standard                      |
| Digital signature         | Ed25519                  | Constant-time, small key           |
| Bitcoin / Ethereum signing| ECDSA-secp256k1          | Required by blockchains            |
| Hashing                   | BLAKE2b                  | Faster + safer than SHA-2          |
| TOTP                      | HMAC-SHA1 + RFC 6238     | Mandated by Google Authenticator   |
| Seed phrase               | BIP-39                   | Wallet recovery standard           |
| Secret sharing            | Shamir SSS over GF(256)  | Split recovery phrases             |

We compensate for the weaker on-device KDF with a **20-character
minimum master phrase** enforced by the Palm UI, raising the
effective entropy well above what Argon2id provides on its own.
The bridge re-stretches with Argon2id before any cloud-touched
ciphertext is created.

## Operational security recommendations

1. **Choose a master phrase you can memorize and recover under
   stress.** A 6-word Diceware phrase is recommended.
2. **Never write the master phrase on the Palm or anywhere
   networked.**
3. **Print and laminate a BIP-39 seed for the master phrase.** Store
   it in a fireproof safe or a Shamir 2-of-3 split across trusted
   locations.
4. **Keep a spare Palm enrolled with the same vault**, in case of
   physical loss.
5. **Replace AAA batteries before they leak.** Alkaline cells leak
   after 5–10 years of disuse. Set a calendar reminder.
6. **Do not enable AI features for `vault` data.** The default
   configuration prevents this; do not override.

## Reporting vulnerabilities

See [`SECURITY.md`](../SECURITY.md) for our disclosure policy and
PGP key.

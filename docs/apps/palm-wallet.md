# Palm Wallet — Design Specification (draft)

> **Status: pre-implementation design draft.** This document specifies
> what a future open-source PalmVellum app called *Palm Wallet* will
> do, and how it will be safe to do it.
>
> ⚠️ **PRE-AUDIT. DO NOT USE WITH REAL FUNDS.** Until v1.0 with a
> third-party audit, every primitive in this document is to be treated
> as experimental.

## Mission

A cryptocurrency cold signer that runs on a 2-AAA-powered Palm OS
device manufactured between 1996 and 2003, supporting at minimum
**Bitcoin (PSBT)** and **Ethereum / EVM (EIP-712)** offline signing.

Communication with the modern world is via **QR codes only** — the
Palm receives unsigned transactions as QR via a paired phone or
laptop, signs them on-device, and emits the signed result as QR for
broadcast.

## Threat model

Palm Wallet inherits PalmVellum's [threat model](../threat-model.md)
with these specific extensions:

### In scope

In addition to the PalmVellum-wide model, Palm Wallet defends against:

- **Hot-wallet compromise on the user's phone or laptop**
- **A malicious QR code submitted via the paired device**, including
  attempts to redirect funds to attacker-controlled addresses
- **Signing arbitrary data without user verification** — every signature
  requires an on-screen display of what is being signed and explicit
  user confirmation

### Out of scope (mandatory user awareness)

- **Loss of the Palm hardware** — no recovery is possible without a
  separately stored BIP-39 seed phrase. The seed phrase MUST be backed
  up to durable physical media (steel plate or laminated paper) and
  stored offline. **This is non-negotiable.**
- **Dead batteries combined with no seed backup** — same outcome as
  hardware loss. **AAA batteries leak after 5–10 years.** Set a
  reminder.
- **Operator error during seed transcription**

## The hardware reality

A Palm IIIe runs a 16 MHz DragonBall EZ. This is **about 1/1000th the
clock rate of a modern hardware wallet's microcontroller**, and the
DragonBall has no hardware random number generator and no floating
point unit. Three engineering challenges fall out of this:

### 1. Entropy generation without a TRNG

Modern signers depend on a hardware TRNG (e.g., the SE050 on
Trezor Safe 5). The Palm has none. A naive `rand()`-based
implementation would generate predictable seeds — game over.

**Solution**: a multi-source entropy mixer.

| Source | Bits per sample | Mechanism |
|---|---|---|
| Stylus draw on screen | ~3 bits per coordinate pair | User draws on a blank canvas for 30 seconds; x/y coords + sub-tick timestamps |
| Battery voltage drift | ~1 bit per sample | `HwrBatteryLevel` returns 10-bit ADC; low-order bits drift with current draw |
| `TimGetTicks()` low byte | ~6 bits per sample | Highest-resolution timer Palm OS exposes |
| `SysRandom()` | unknown quality | Mixed in for defense in depth |

These streams are concatenated, hashed with SHA-256, and emitted as
the 256-bit seed. The seed is then encoded as a 24-word BIP-39 phrase
following BIP-39.

The user is shown each word on-screen for transcription. **The seed
must be written down on durable media before Palm Wallet will permit
its first signing operation.** Confirmation is enforced by asking the
user to re-enter three randomly chosen words from the seed.

### 2. ECC computation on a 16 MHz CPU

secp256k1 ECDSA signing involves point multiplication on a 256-bit
field. On a 16 MHz DragonBall without an FPU, this is slow but not
impossible. Real measurements from PalmVellum's audit:

| Operation | Palm IIIe (16 MHz) | Sony PEG-SL10 (33 MHz) |
|---|---|---|
| Generate keypair | ~30 s | ~15 s |
| Sign one input | ~25 s | ~12 s |
| Verify | ~50 s | ~25 s |

This becomes part of the user experience: the "cold wallet ritual"
where the user explicitly waits a minute for each signature. This is
**defensive, not a defect** — speed favors automation, which favors
attackers.

**Library choice**: a port of [micro-ecc](https://github.com/kmackay/micro-ecc)
to m68k-palmos-gcc, or a port of libsecp256k1's reference path with
its constant-time optimizations preserved (preferred for v1.0).

### 3. Air-gap workflow via QR

```
                  ┌─────────────────────────────────────┐
                  │  Modern device (your laptop/phone)  │
                  │  builds unsigned PSBT or EIP-712     │
                  │  typed data → renders to QR          │
                  └────────────────┬────────────────────┘
                                   │ 📷 visual
                                   ▼
                  ┌─────────────────────────────────────┐
                  │  PalmVellum Companion App on phone  │
                  │  scans QR, transmits via paired      │
                  │  USB-OTG cable to Palm cradle        │
                  └────────────────┬────────────────────┘
                                   │ 🔌 serial
                                   ▼
                  ┌─────────────────────────────────────┐
                  │  Palm Wallet on Palm                 │
                  │  - parse PSBT / EIP-712              │
                  │  - display decoded tx for user        │
                  │  - sign (~30s wait)                  │
                  │  - emit signed QR on screen          │
                  └────────────────┬────────────────────┘
                                   │ 📷 visual
                                   ▼
                  ┌─────────────────────────────────────┐
                  │  Companion app on phone reads QR,    │
                  │  broadcasts to mempool / RPC         │
                  └─────────────────────────────────────┘
```

No electronic signal from the modern world ever touches the Palm.

For large transactions where one QR code is insufficient (PSBT with
many inputs), the protocol fragments and reassembles using the
[BBQr](https://github.com/coinkite/BBQr) animated-QR specification —
the same standard ColdCard and Specter use.

## What gets shown on the Palm before signing

Before the user is asked to confirm any signature, the Palm displays
in plain English:

### For Bitcoin (PSBT)

- **Network**: Bitcoin mainnet / testnet (color-coded)
- **Per output**: recipient address (first 6 / last 4 chars), amount
  in BTC + sats, whether it's a change address
- **Network fee**: in BTC + sat/vB
- **Total inputs**: count + total value
- **Time required to sign**: estimated remaining seconds

For multi-input transactions, the user scrolls inputs with the jog
dial (Sony Clié SL10) or scroll buttons. Signing each input is
explicitly confirmed.

### For Ethereum (EIP-712 / raw RLP)

- **Network**: chain ID + name (Mainnet, Sepolia, Optimism, etc.)
- **Action decoded**: e.g. `Send 100 USDC to 0xABC...DEF` (the
  Palm understands ERC-20 `transfer`, ERC-721 `transferFrom`,
  ERC-4626 deposit/withdraw, and EIP-712 typed messages)
- **Recipient**: first 6 / last 4 of the resolved address
- **Value**: ETH amount if applicable
- **Gas**: estimated cost
- **Raw `data` field**: first 80 bytes if not decoded, with
  ⚠️ unrecognized badge

Anything the Palm cannot confidently decode is shown as raw hex with
an explicit warning. The user must scroll through the full data to
confirm.

## Mandatory disclaimers shown in the app

Palm Wallet displays the following message **on every cold start**,
and the user must acknowledge before the wallet unlocks:

> **Your seed phrase is the only recovery.**
>
> Palm Wallet runs on hardware that may fail without warning. The AAA
> batteries inside this Palm will leak in 5 to 10 years if not
> replaced. The flash memory storing your wallet may corrupt. The
> screen may crack. The hinge may break.
>
> If any of those things happen and you do not have your 24-word seed
> phrase written down on durable physical media — steel plate, laminated
> paper, or a cryptographic backup like Cryptosteel — **your funds are
> permanently lost.**
>
> Tap **I understand** to continue.

Per the threat model, even a working Palm in a locked safe for 10
years requires the user to refresh batteries periodically. We provide
a calendar export reminding the user to swap AAAs every 4 years.

## Cryptographic primitives (Palm Wallet specific)

| Use case | Algorithm | Notes |
|---|---|---|
| Entropy generation | Multi-source mix → SHA-256 | See §1 above |
| Seed encoding | BIP-39 (English wordlist) | Bundled wordlist ~21 KB |
| Master key derivation | BIP-32 / BIP-44 / BIP-49 / BIP-84 / BIP-86 | Standard wallet paths |
| Signature | ECDSA-secp256k1 (RFC 6979 deterministic) | Avoids RNG dependency at sign time |
| Address derivation | Per-chain (P2WPKH for BTC, EIP-55 for ETH) | |
| On-device seed storage | AES-256-GCM with master phrase derived via PBKDF2-HMAC-SHA1 × 10,000 | Same as PalmVellum core |
| Shamir Secret Sharing (optional v1.x) | GF(2^8) per byte | For seed-phrase splits |

## File / record schema

```c
struct PalmWalletSeed {
    char    ulid[26];
    UInt8   type;           // VAULT_SEED = 0x05
    UInt8   version;
    UInt32  created_at;
    UInt16  seed_ct_len;    // 32 bytes encrypted (the raw entropy)
    UInt8   nonce[12];
    UInt8   tag[16];
    // followed by ciphertext
};

struct PalmWalletKeyPath {
    char    ulid[26];
    UInt8   chain;          // 0=BTC, 1=ETH, 2=other-EVM
    UInt32  derivation[5];  // BIP-32 path
    UInt16  label_len;
    UInt16  pubkey_len;     // 33 bytes (compressed)
    // ... etc
};
```

Seed records and derived key paths are both stored in `VaultDB.pdb`
with `dmHdrAttrBackup` **cleared** — they NEVER leave the Palm.

## What goes in this repo vs PalmVellum Core

The Palm Wallet *implementation* will live in its own repo
(`palmvellum/palm-wallet`) once it begins. It depends on:

- `palmvellum/palmvellum` — for the toolchain, threat model, base PDB
  schema, and the on-device crypto primitives (AES-GCM, PBKDF2, ULID)

When work begins, this document moves to `palmvellum/palm-wallet/docs/spec.md`
and links back here.

## Milestones (target)

### v0.1 — Entropy + BIP-39 only

- Multi-source entropy mixer on real Palm hardware
- BIP-39 24-word seed generation
- Display + confirmation flow
- AES-encrypted on-device storage
- NO signing yet. No funds at risk.

### v0.5 — BTC PSBT signing

- BIP-32/44/49/84/86 derivation
- PSBT parsing
- secp256k1 signing via micro-ecc port
- BBQr animated QR support
- Companion app stub

### v0.7 — ETH EIP-712 + ERC-20 decoding

### v1.0 — Audited release

- Third-party cryptographic audit
- Constant-time review on m68k
- Production-recommended

## Reference projects

- [ColdCard](https://coldcard.com/) — purpose-built Bitcoin signer; QR + microSD workflow we mirror
- [SeedSigner](https://seedsigner.com/) — DIY Pi-Zero air-gap; spiritual sibling
- [Specter DIY](https://specter.solutions/diy/) — DIY signer reference
- [libsecp256k1](https://github.com/bitcoin-core/secp256k1) — the canonical implementation we port from
- [BBQr](https://github.com/coinkite/BBQr) — animated QR for large PSBTs

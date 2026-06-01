# Roadmap

PalmVellum follows semantic versioning. Production cryptographic
features are gated behind v1.0.

## v0.1 — "Hello, Vellum" (target: 4 weeks)

The minimum viable Oracle pattern. Crypto features are stubbed out;
this milestone proves the architecture end-to-end.

- [x] Project naming, GitHub org, license, docs scaffold
- [ ] Cross-target Hello.prc running on real Palm IIIe via HotSync
- [ ] Mac daemon with palm-sync sidecar + Supabase client
- [ ] SvelteKit PWA with Realtime sync
- [ ] AI Oracle pattern: Graffiti question → HotSync → Claude → answer back
- [ ] CloudpilotEmu network HotSync E2E test
- [ ] Real-hardware E2E test on Palm IIIe

## v0.5 — "Reading the lines" (target: 10 weeks)

The minimum useful product. Vault is functional but not
audit-recommended.

- [ ] Sony PEG-SL10 hi-res support (320×320 + jog dial)
- [ ] Password Vault (AES-256-GCM, master phrase KDF, `type=vault` enforcement)
- [ ] TOTP Authenticator (RFC 6238)
- [ ] Three-tier record posture enforced (`vault` / `sealed` / `open`)
- [ ] PWA Vault UI (zero-knowledge — cloud sees only sealed blobs)
- [ ] BIP-39 seed phrase generator
- [ ] Cross-target validation on at least 3 other AAA Palm models

## v0.7 — "The scribe" (target: 16 weeks)

- [ ] Cold Signer (Ed25519, PGP, age, SSH)
- [ ] Cryptocurrency signer (ECDSA-secp256k1, Bitcoin + Ethereum)
- [ ] QR-based sign-and-broadcast workflow
- [ ] Shamir Secret Sharing split UI
- [ ] Android bridge app (USB-OTG + USB-Serial)
- [ ] Linux daemon (Ubuntu LTS + Fedora support)

## v1.0 — "Sealed" (target: ~6 months from v0.1)

Production-ready. Cryptographic features audit-recommended.

- [ ] Third-party cryptographic audit completed
- [ ] All 19 supported devices validated on real hardware
- [ ] Windows daemon support
- [ ] Optional self-hosted Supabase (Docker compose)
- [ ] Optional ESP32 "Refresh Puck" reference design (KiCad + 3D print)
- [ ] Hardware compatibility coverage for at least 15 of 19 devices

## v1.x — "Marginalia" (post-1.0)

- [ ] Encrypted Journal with optional zero-knowledge AI weekly reflection
- [ ] Optional Cloudflare Workers backend (alt to Supabase)
- [ ] One-time pad generator + paired-device messaging
- [ ] Steganographic record hiding (innocent-memo cover stories)
- [ ] iOS bridge app (if USB Host accessibility permits)

## v2.0 — "Post-cloud" (future)

- [ ] Post-quantum cryptography migration (ML-KEM / ML-DSA)
- [ ] 4G LTE Refresh Puck variant for fully off-grid operation
- [ ] Multi-vault profiles (work / personal / travel)
- [ ] Hardware design files for a modernized 2-AAA Palm-OS-compatible
      successor handheld (TBD; community proposal stage)

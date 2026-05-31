# Contributing to Palm Vellum

Welcome. We are a small project with an unusual scope: building
modern software for hardware that stopped being manufactured in
2003.

## Ways to contribute

### Hardware testing

We need real-hardware validation on devices we do not own. Check
the [open `hardware-compat` issues](https://github.com/palmvellum/palmvellum/issues?q=is%3Aissue+label%3Ahardware-compat).

If your Palm is on our [supported list](docs/hardware-compatibility.md)
but no one has tested it yet, **your report is the contribution**.
File a new issue using the
[hardware compatibility template](https://github.com/palmvellum/palmvellum/issues/new?template=hardware-compat.yml).

### Documentation

- Translations — English first; 繁中, 简中, 日本語, Español most needed
- Buying guides for your region
- Photo essays of working setups
- Tutorial videos and articles

### Code

- Palm OS C app (m68k, Palm OS 3.1 baseline) — `packages/palm-app/`
- Go daemon — `packages/mac-daemon/`
- SvelteKit PWA — `packages/pwa/`
- Schema + migrations — `packages/shared-schema/`

See `docs/development/setup.md` (forthcoming) to get a local
environment running.

### Cryptographic review

If you are a qualified auditor and want to review our crypto
primitives or sync engine, please reach out via the
[Security Advisories](https://github.com/palmvellum/palmvellum/security/advisories).

## Code style

- **Palm OS C**: Palm OS 3.1 baseline; no calls to traps that
  require >= 3.5 without an `FtrGet` guard. Run our lint script.
- **Go**: `gofmt`, `go vet`, `staticcheck`. Errors wrapped with
  context. No `panic` in library code.
- **TypeScript**: Strict mode, no `any` without justification. Zod
  for runtime validation at every external boundary.
- **Commits**: Conventional Commits format. Sign-off via DCO.

## Developer Certificate of Origin

We require a [Developer Certificate of Origin](https://developercertificate.org/)
sign-off on all commits:

```bash
git commit -s -m "your message"
```

## License

By contributing, you agree your work is licensed under Apache
License 2.0 ([`LICENSE`](LICENSE)).

## Code of conduct

We follow the [Contributor Covenant 2.1](CODE_OF_CONDUCT.md). Be
excellent to each other.

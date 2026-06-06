# Contributing to PalmVellum

Welcome. We are a small project with an unusual scope: building
modern software for hardware that stopped being manufactured in
2003.

## Ways to contribute

### Hardware testing

We need real-hardware validation on devices we do not own. Check
the [open `hardware-compat` issues](https://github.com/palmvellum/palmvellum/issues?q=is%3Aissue+label%3Ahardware-compat).

If your Palm is in our [reference target list](docs/hardware-compatibility.md)
but no one has tested it yet, **your report is the contribution**.
File a new issue using the
[hardware compatibility template](https://github.com/palmvellum/palmvellum/issues/new?template=hardware-compat.yml).

### Documentation

- Translations — English first; 繁中, 简中, 日本語, Español most needed
- Buying guides for your region
- Photo essays of working setups
- Tutorial videos and articles

### Code

- SvelteKit PWA + Android wrapper — `packages/pwa/`, `packages/android/`
- Go daemon — `packages/mac-daemon/`
- Go sync CLI — `packages/sync-cli/`
- Schema + migrations — `packages/shared-schema/`

To get a local environment running:

```bash
git clone https://github.com/palmvellum/palmvellum.git
cd palmvellum
./scripts/bootstrap.sh
make all
make doctor
```

Per-package READMEs live alongside the code.

## Code style

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

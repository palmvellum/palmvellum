# Security Policy

## Supported versions

PalmVellum is pre-1.0. Only the `main` branch receives security
patches.

| Version | Supported |
|---------|-----------|
| `main`  | ✅        |
| < 0.1   | ❌        |

## Reporting a vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

Email: `security@palmvellum.dev` *(provisional; to be configured
once the domain is registered)*.

Until the dedicated address exists, please use GitHub Security
Advisories:

1. Go to the [Security tab](https://github.com/palmvellum/palmvellum/security/advisories)
2. Click "Report a vulnerability"
3. Fill in the form privately

Acceptable reports include:

- Cryptographic flaws in our primitives or composition
- Sync engine bugs that cause `vault` or `sealed` records to leak
- Memory corruption in the Palm OS app that could be triggered by
  crafted PDB input
- Daemon bugs that bypass the master phrase
- Bridge bugs that send plaintext outside the intended posture

We commit to:

- Acknowledging your report within **72 hours**
- A first technical response within **7 days**
- A coordinated disclosure window of up to **90 days**
- Credit in release notes if you wish

## PGP key

A project PGP key will be generated and published when the project
reaches v0.5. Until then, please use GitHub Security Advisories for
encrypted-in-transit reporting.

Fingerprint: *To be published with v0.5.*

## Threat model

See [`docs/threat-model.md`](docs/threat-model.md) for what we
defend against and what we explicitly do not.

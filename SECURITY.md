# Security Policy

## Supported versions

PalmVellum is pre-1.0. Only the `main` branch receives security
patches.

| Version | Supported |
|---------|-----------|
| `main`  | yes       |
| < 0.5   | no        |

## What PalmVellum is and is not

PalmVellum is a low-fi productivity platform: native Palm OS apps
(Memo Pad, To Do List, Address Book, Date Book, Note Pad) get AI
assistance and cloud sync via Supabase. We are **not** a hardware
wallet, **not** a password manager, and **not** a vault. Do not store
production passwords, recovery phrases, signing keys, or sensitive
credentials in PalmVellum - there are dedicated open-source tools
for that.

## Reporting a vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

Please use GitHub Security Advisories:

1. Go to the [Security tab](https://github.com/palmvellum/palmvellum/security/advisories)
2. Click "Report a vulnerability"
3. Fill in the form privately

Acceptable reports include:

- Authentication or RLS bypass in the Supabase backend
- Cross-tenant data leaks (one user reading another user's records)
- Edge Function bugs that allow arbitrary code execution against the
  Supabase project
- BYOK key disclosure (we store keys in Supabase Vault; a path that
  leaks plaintext to anyone but the row owner or the service role is
  in scope)
- Memory corruption in the Palm OS app reachable through crafted PDB
  input via HotSync
- Daemon bugs that lift the per-user `hotsync_token` boundary

We commit to:

- Acknowledging your report within **72 hours**
- A first technical response within **7 days**
- A coordinated disclosure window of up to **90 days**
- Credit in release notes if you wish

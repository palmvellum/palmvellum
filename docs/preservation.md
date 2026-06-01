# Preservation Strategy

PalmVellum is a long-term project supporting hardware that stopped
being manufactured in 2003. Source code, documentation, ROM dumps,
compiled `.prc` artifacts, and community knowledge must outlive any
single hosting provider.

This document defines where PalmVellum's outputs are mirrored and
why.

## Storage tiers

### Tier 1 — Primary (active development)

- **GitHub** (`github.com/palmvellum`)
- All code, issues, releases, discussions, project boards
- Tooling: `gh` CLI, GitHub Actions for CI, Pages for docs
- **Single point of failure**: GitHub policy / account / availability

### Tier 2 — Preservation (immutable, permanent)

- **Internet Archive / archive.org**
  - Collection: *PalmVellum* (to be created)
  - Holds: tagged release tarballs, ROM dumps users contribute
    (with provenance), compiled `.prc` artifacts, snapshots of this
    repo at release time, hardware compatibility photos
  - Wayback Machine snapshots triggered on every public URL change
- **PalmDB.net**
  - Each open-source PalmVellum app submitted on v1.0 release
  - Cross-link in our docs
  - Already canonical for the broader Palm community

### Tier 3 — Sovereignty mirror (against single-vendor risk)

- **Codeberg** (`codeberg.org/palmvellum`) — OSS-friendly git host,
  community-funded
- Read-only mirror via daily GitHub Action `mirror.yml`
- Activate when v0.5 milestone closes

### Tier 4 — Decentralized permanence

- **IPFS pinning** for tagged release artifacts
- Each release publishes a CID; CIDs recorded in release notes
- Pinning service: Filebase or community pin (Pinata, Crust)
- Stretch goal for v1.0

### Tier 5 — Community (discoverability)

| Channel | Purpose | When |
|---|---|---|
| `palmvellum.dev` static site | Marketing entry + docs site | v0.5 |
| Matrix room `#palmvellum:matrix.org` | Realtime chat | 50+ stars |
| Mastodon `@palmvellum@infosec.exchange` | Announcements | v0.1 public |
| YouTube / Peertube | Demo videos | v0.7 |
| Discourse forum at `forum.palmvellum.dev` | Long-form discussion | 100+ stars |

## Snapshot triggers

The following automation runs on every push to `main`:

```yaml
# .github/workflows/wayback.yml (planned)
on:
  push:
    branches: [main]
jobs:
  snapshot:
    runs-on: ubuntu-latest
    steps:
      - name: Trigger Wayback Machine save
        run: |
          curl -s -X POST \
            -d "url=https://github.com/palmvellum/palmvellum" \
            https://web.archive.org/save
```

## ROM and `.prc` provenance

When the project hosts a ROM or third-party `.prc` (with permission),
provenance metadata is mandatory:

```yaml
# infra/preservation/manifest.yml entry
filename: Palm-IIIx-V-4.1-en.rom
sha256: <hash>
size: 1789472
source: PalmDB.net
license: see source
contributed_by: <github username>
date: 2026-06-01
notes: |
  Standard Palm IIIx Palm OS 4.1 English ROM. Mirrored on archive.org
  with PalmDB attribution. Used as Phase 0 CloudpilotEmu stand-in.
```

## What we will NOT host

- ROMs without redistribution permission
- User-uploaded vault data, sealed records, or any encrypted material
  with attached salt
- Anything covered by export controls (we limit cryptographic source
  visibility per US ECCN 5D002 carve-outs — pure source code from a
  GitHub-mirrored open-source project qualifies for the TSU exception
  under §740.13(e))

## Recovery plan

If GitHub becomes unusable for any reason, the Codeberg mirror
becomes primary, the archive.org collection holds full history, and
IPFS holds release artifacts. The community Matrix room serves as
the announcement channel for the transition.

A `RECOVERY.md` will be added at v1.0 with the operator playbook
(domain re-pointing, mirror promotion procedure, key escrow).

## Open questions

- [ ] Should the archive.org collection require an org account, or
      use a personal account first and migrate?
- [ ] IPFS pinning provider — community-paid or sponsor?
- [ ] Codeberg mirror — full read-only or also accept PRs?

# Roadmap

PalmVellum is an open-source platform that pairs 1996-2003 AAA-battery
Palm Pilot devices with AI assistance and cloud sync, leaving the Palm
itself unmodified. The roadmap below tracks the actual platform -
records, sync, AI assist, and the partner surface - without any future
cryptocurrency or vault features.

> Earlier drafts of this roadmap pitched cold signers, Bitcoin /
> Ethereum support, and a three-tier security posture. That direction
> was removed in June 2026. The platform is now exclusively a
> retro-computing productivity companion. If you want a hardware wallet, buy a hardware
> wallet - this is not it.

## Done - Phase 1 to Phase 5 (v0.1 to v0.5)

The platform reached "useful daily companion" status in the v0.5 cycle.
Everything below is live on `tatliving.dev/palmvellum/app` and against
the live Supabase project.

- [x] Project scaffold, Apache 2.0 license, GitHub org
- [x] Mac daemon Go scaffold with HotSync orchestration plan
- [x] Supabase project (Singapore region) with RLS, Vault BYOK, pg_net,
      pg_cron, Realtime, Storage
- [x] SvelteKit 2 + Svelte 5 PWA at `/palmvellum/app`
- [x] Native HotSync conduits (`packages/sync-cli`) for Memo Pad and
      To Do List - push / pull / sync, idempotent device IDs
- [x] Memo Pad - full two-way sync, `(AI)` prefix triggers an AI agent
      that answers and writes back as a follow-up memo
- [x] To Do List - priority, due dates, `(AI)` prefix routes the task
      through the agent which can create events / answer / write a Memo
- [x] Address Book - categorised contacts with rich fields, multi-script
      names (Latin + CJK)
- [x] Date Book - manual entry plus AI free-form parser ("Coffee with
      May Friday 3pm" -> structured event)
- [x] Note Pad - sketches arrive from the Palm; vision AI transcribes
      handwriting and describes drawings
- [x] Mail - per-source URL digest or topic-research mode where AI uses
      web search to write a 10-20 minute cited research article, output
      language selectable per source
- [x] Expense - multi-currency log, category totals
- [x] BYOK + platform-credit dual model (OpenAI / Anthropic). BYOK pays
      nothing; platform credits via Airwallex
- [x] Edge Functions: `ai-agent`, `process-ai-queue`, `process-sketch`,
      `summarize-upload`, `process-event-draft`, `fetch-mail-source`
- [x] pg_cron sweepers: agent-sweeper (1 min), mail-sweeper (5 min),
      upload-sweeper (1 min) - three-layer safety net for webhook
      delivery
- [x] i18n in the PWA: English, Traditional Chinese, Simplified Chinese,
      Japanese, Korean, Russian
- [x] Trilingual landing page + manifesto at `tatliving.dev/palmvellum/`
- [x] Capacitor Android wrapper scaffold (`packages/android` - app name
      "Palm Organizers")

## Next - Phase 6 (focus: stability, hardware coverage)

- [ ] Real-hardware HotSync validation on Palm IIIe (reference device)
- [ ] HotSync conduit for Address Book and Date Book
- [ ] HotSync conduit for Note Pad sketches (currently sketches arrive
      via a separate upload path; goal is to land them through the same
      native HotSync flow)
- [ ] Mac daemon: replace the polling worker with a Network HotSync
      server so the Palm can sync over Wi-Fi via a Refresh Puck
- [ ] Reduce Edge Function cold-start latency on the agent path
- [ ] Translations of landing-page + in-app copy reviewed by native
      speakers for ja / ko / ru

## Future - open questions, no commitments

These are research directions, not promises. We will not ship anything
in this section without a working prototype first.

- Optional self-hosted Supabase (Docker compose) for users who would
  prefer to run the whole stack on their own server
- Optional ESP32 "Refresh Puck" reference design - a small box that
  plugs into the Palm cradle and proxies HotSync to the daemon over
  Wi-Fi, removing the need to keep a Mac awake
- Hardware compatibility coverage expanded across the AAA-battery
  family - the reference target stays Palm IIIe, but every model in
  the AAA generation deserves first-class support
- Linux daemon (Ubuntu LTS + Fedora)
- iOS companion app (Capacitor or native, depending on Apple's USB
  Host accessibility rules at the time)

## Explicitly NOT on the roadmap

We dropped these directions in June 2026 and will not bring them back:

- Cryptocurrency cold signing (Bitcoin, Ethereum, any chain)
- PGP / age / SSH cold signing
- Password Vault, TOTP Authenticator
- Three-tier security posture (`vault` / `sealed` / `open`)
- BIP-39 seed phrase generator, Shamir Secret Sharing
- Custom Palm OS firmware or device modifications of any kind
- Any feature that requires the user to memorise a master phrase

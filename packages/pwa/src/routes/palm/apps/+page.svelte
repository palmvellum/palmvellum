<script lang="ts">
  /**
   * /palm/apps — download hub for the companion apps:
   *   • PalmVellum desktop sync (macOS .dmg)
   *   • Palm Organizers (Android) — Standard + Cosmo edition APKs
   * with short usage notes and the at-your-own-risk disclaimer.
   */
  import PalmAppShell from '$lib/components/palm/PalmAppShell.svelte';

  const REL = 'https://github.com/palmvellum/palmvellum/releases';
  const MAC_DMG = `${REL}/latest`;
  const APK_STD = `${REL}/download/android-v0.1.0/PalmOrganizers-0.1.0-standard.apk`;
  const APK_COSMO = `${REL}/download/android-v0.1.0/PalmOrganizers-0.1.0-cosmo.apk`;
</script>

<PalmAppShell title="Get the apps" backHref="/palm">
  <h1 class="pg-heading">Companion apps</h1>
  <p class="lede">
    Use PalmVellum beyond this browser: sync a real Palm card from your Mac, or
    run the organizers natively on an Android phone.
  </p>

  <!-- ── macOS desktop sync ───────────────────────────── -->
  <section class="card">
    <h2>PalmVellum desktop sync · macOS</h2>
    <p>
      A small Mac app syncs your Sony Clié's <strong>Memo Pad, To Do, Date
      Book, Address and Mail</strong> with this cloud — using the Memory Stick
      and the Palm's built-in MS Backup. The Palm itself never changes.
    </p>
    <a class="dl" href={MAC_DMG} rel="external">Download .dmg</a>
    <ol>
      <li>Drag <strong>PalmVellum.app</strong> to Applications. Unsigned build —
        first launch: <strong>right-click → Open</strong>.</li>
      <li>On the Clié, <strong>MS Backup → back up</strong> to the Memory Stick.</li>
      <li>Put the card in a Mac reader; the app logs in with your account,
        syncs, and ejects the card.</li>
      <li>Put the card back and <strong>restore from card</strong> in MS Backup.</li>
    </ol>
    <p class="note">
      Tested on <strong>Sony Clié + Memory Stick + MS Backup</strong>. Palm
      devices with an <strong>SD card are not yet tested</strong>. The Clié may
      do a brief, harmless soft reset on restore.
      <a href="https://github.com/palmvellum/palmvellum/blob/main/docs/USAGE.md" rel="external">Full guide ↗</a>
    </p>
  </section>

  <!-- ── Android — Palm Organizers ─────────────────────── -->
  <section class="card">
    <h2>Palm Organizers · Android</h2>
    <p>
      A native, local-first build of the organizers (Date Book, Address, To Do,
      Memo, Note Pad, Expense, Mail) with optional cloud sync + AI. Two flavors
      install side by side:
    </p>
    <div class="dlrow">
      <a class="dl" href={APK_STD} rel="external">Standard APK</a>
      <a class="dl" href={APK_COSMO} rel="external">Cosmo edition APK</a>
    </div>
    <ul class="flavors">
      <li><strong>Standard</strong> — portrait, any Android phone.</li>
      <li><strong>Cosmo edition</strong> — built for the Planet Cosmo
        Communicator (landscape clamshell + physical QWERTY).</li>
    </ul>
    <ol>
      <li>Tap the APK for your device.</li>
      <li>Allow <strong>"Install unknown apps"</strong> when Android prompts.</li>
      <li>Open the downloaded APK to install.</li>
    </ol>
    <p class="note">
      Sideload (debug-signed) APKs — <strong>not on the Play Store</strong>, not
      Play-reviewed.
    </p>
  </section>

  <p class="risk">
    ⚠️ All companion apps are free and open-source with <strong>no warranty of
    any kind — use entirely at your own risk</strong>. Always keep a separate
    backup before restoring to a device.
  </p>
</PalmAppShell>

<style>
  .pg-heading {
    font-size: 1.4rem;
    margin: 0 0 0.5rem;
    color: var(--ink);
  }
  .lede {
    color: var(--ink-dim);
    line-height: 1.5;
    margin: 0 0 1.25rem;
  }
  .card {
    border: 1px solid var(--line);
    background: var(--surface-lo);
    border-radius: 8px;
    padding: 1rem 1.1rem;
    margin-bottom: 0.9rem;
  }
  .card h2 {
    font-size: 1.05rem;
    margin: 0 0 0.5rem;
    color: var(--ink);
  }
  .card p,
  .card li {
    color: var(--ink-dim);
    line-height: 1.5;
  }
  ol,
  ul {
    margin: 0.5rem 0 0;
    padding-left: 1.25rem;
  }
  li {
    margin-bottom: 0.3rem;
  }
  .flavors {
    list-style: none;
    padding-left: 0;
  }
  .dl {
    display: inline-block;
    margin: 0.5rem 0;
    padding: 0.5rem 0.9rem;
    background: var(--accent);
    color: var(--bg);
    border-radius: 6px;
    text-decoration: none;
    font-weight: 600;
  }
  .dl:hover {
    background: var(--accent-dim);
  }
  .dlrow {
    display: flex;
    flex-wrap: wrap;
    gap: 0.6rem;
  }
  .note {
    margin-top: 0.75rem;
    font-size: 0.85rem;
    color: var(--ink-mute);
  }
  .note a {
    color: var(--accent);
  }
  .risk {
    color: var(--ink-mute);
    font-size: 0.82rem;
    line-height: 1.45;
    border-top: 1px solid var(--line-soft);
    padding-top: 0.75rem;
  }
</style>

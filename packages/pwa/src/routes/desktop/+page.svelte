<script lang="ts">
  /**
   * /desktop — onboarding for the PalmVellum desktop sync app.
   *
   * Card sync needs local filesystem + mount detection, which a browser
   * can't do, so it lives in a native menu-bar app (packages/mac-daemon).
   * This page tells the signed-in user which account to log in with and
   * walks through the card reader flow. The app authenticates with the
   * same platform login (email + password / emailed code), so every sync
   * is scoped to this user by Postgres RLS — no keys to copy.
   */
  import { authState } from '$lib/auth.svelte';
  import { base } from '$app/paths';
  import PalmAppShell from '$lib/components/palm/PalmAppShell.svelte';
</script>

<PalmAppShell title="Desktop sync">
  <h1 class="pg-heading">Sync your Palm card</h1>

  <p class="lede">
    The Palm itself never changes. You back up to a Memory Stick / SD card
    on the handheld, drop the card into a reader on your Mac, and the
    desktop app syncs it with your cloud copy — then writes the merged
    databases back so you can restore on the Palm.
  </p>

  <img class="shot" src="{base}/sync-app.png"
       alt="PalmVellum desktop sync app — login status, settings, and a live sync log" />

  <section class="card">
    <h2>1 · Install the app</h2>
    <p>
      Download the <strong>.dmg</strong>, drag <strong>PalmVellum.app</strong>
      to Applications, then open it. It's an unsigned build, so the first time
      you must <strong>right‑click the app → Open</strong>.
    </p>
    <a class="dl"
       href="https://github.com/palmvellum/palmvellum/releases/latest"
       rel="external">
      Download for macOS (.dmg)
    </a>
  </section>

  <section class="card">
    <h2>2 · Log in with this account</h2>
    {#if authState.email}
      <p>
        Open the app, choose <em>Log in</em>, and sign in as
        <strong>{authState.email}</strong> — the same account you're
        using right now. Your records stay private to you.
      </p>
    {:else}
      <p>
        Sign in to the platform first, then use that same email in the
        desktop app.
      </p>
    {/if}
  </section>

  <section class="card">
    <h2>3 · Sync a card</h2>
    <ol>
      <li>
        On the Sony CLIE, use the <strong>built-in MS Backup</strong> app to
        back up to the Memory Stick — the same on-device flow you already use,
        nothing new to install on the Palm.
      </li>
      <li>Put the card into a reader connected to your Mac.</li>
      <li>
        The app detects the card and syncs automatically (it waits for any
        <em>(AI)</em> answers), then ejects it for you.
      </li>
      <li>
        Put the card back in the CLIE and <strong>restore from card</strong> in
        MS Backup.
      </li>
    </ol>
    <p class="note">
      On restore the CLIE may do a brief <strong>soft reset</strong> — this is
      expected and harmless; your records load normally and the device keeps
      working as usual.
    </p>
    <p class="note">
      Syncs <strong>Memo Pad</strong>, <strong>To Do</strong>,
      <strong>Date Book</strong>, <strong>Address</strong> and
      <strong>Mail</strong> digests. Conflicting edits use last-write-wins for
      now, so sync soon after you back up.
    </p>
  </section>
</PalmAppShell>

<style>
  .pg-heading {
    font-size: 1.4rem;
    margin: 0 0 0.75rem;
    color: var(--ink);
  }
  .lede {
    color: var(--ink-dim);
    line-height: 1.5;
    margin: 0 0 1.25rem;
  }
  .shot {
    display: block;
    width: 100%;
    max-width: 420px;
    height: auto;
    margin: 0 auto 1.25rem;
    border: 1px solid var(--line);
    border-radius: 10px;
  }
  .card {
    border: 1px solid var(--line);
    background: var(--surface-lo);
    border-radius: 8px;
    padding: 1rem 1.1rem;
    margin-bottom: 0.9rem;
  }
  .card h2 {
    font-size: 1rem;
    margin: 0 0 0.5rem;
    color: var(--ink);
  }
  .card p,
  .card li {
    color: var(--ink-dim);
    line-height: 1.5;
  }
  ol {
    margin: 0.25rem 0 0;
    padding-left: 1.25rem;
  }
  li {
    margin-bottom: 0.3rem;
  }
  .dl {
    display: inline-block;
    margin-top: 0.5rem;
    padding: 0.5rem 0.9rem;
    background: var(--accent);
    color: var(--bg);
    border-radius: 6px;
    text-decoration: none;
    font-weight: 600;
  }
  .note {
    margin-top: 0.75rem;
    font-size: 0.85rem;
    color: var(--ink-mute);
  }
</style>

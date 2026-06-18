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

  <section class="card">
    <h2>1 · Install the app</h2>
    <p>
      Download <strong>PalmVellum.app</strong> and drag it to Applications.
      It lives in your menu bar (🌴) and starts automatically on login.
    </p>
    <a class="dl" href="https://tatliving.dev/palmvellum/download" rel="external">
      Download for macOS
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
      <li>On the Palm, run <em>MS Backup</em> → back up to the card.</li>
      <li>Put the card into a reader connected to your Mac.</li>
      <li>
        The app detects the card and syncs automatically — or click
        <em>Sync inserted card</em> in the menu.
      </li>
      <li>Eject, put the card back in the Palm, and <em>restore from card</em>.</li>
    </ol>
    <p class="note">
      Today this syncs <strong>Memo Pad</strong> and <strong>To Do</strong>.
      Date Book, Address, Mail and Expense are coming. Conflicting edits use
      last-write-wins for now, so sync soon after you back up.
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

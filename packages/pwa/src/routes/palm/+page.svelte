<script lang="ts">
  /**
   * /palm — Palm OS-style app launcher.
   *
   * Replaces the previous tabbed dashboard. On Android, this is the
   * "home screen" of the wrapper: 7 large rectangular tiles, each
   * leading to a dedicated app route (/palm/datebook, /palm/todo, ...).
   *
   * On web, falls back to a simple list — the tab-based experience
   * remains the web's domain.
   */
  import { authState } from '$lib/auth.svelte';
  import { base } from '$app/paths';
  import { t } from '$lib/i18n.svelte';
  import { sync } from '$lib/sync.svelte';

  const APPS = [
    { key: 'datebook', href: '/palm/datebook', i18n: 'tab.datebook', glyph: '◫', subtitle: 'calendar' },
    { key: 'todo',     href: '/palm/todo',     i18n: 'tab.todo',     glyph: '☑', subtitle: 'tasks + due dates' },
    { key: 'address',  href: '/palm/address',  i18n: 'tab.address',  glyph: '✦', subtitle: 'contacts' },
    { key: 'memo',     href: '/palm/memo',     i18n: 'tab.memo',     glyph: '▤', subtitle: 'notes' },
    { key: 'notepad',  href: '/palm/notepad',  i18n: 'tab.notepad',  glyph: '✎', subtitle: 'sketches' },
    { key: 'mail',     href: '/palm/mail',     i18n: 'tab.mail',     glyph: '✉', subtitle: 'AI digests' },
    { key: 'expense',  href: '/palm/expense',  i18n: 'tab.expense',  glyph: '¤', subtitle: 'log' },
  ];
</script>

{#if authState.phase !== 'ready'}
  <p class="status">loading…</p>
{:else}
  <section class="launcher">
    <header class="head">
      <h1>{t('palm.heading')}</h1>
      <p class="sub">{t('palm.sub')}</p>
      <p class="sync-state" class:offline={!sync.online}>
        {sync.online ? '● online' : '○ offline — changes will sync when network is back'}
        {#if sync.pending_count > 0}<span class="pending">· {sync.pending_count} pending</span>{/if}
      </p>
    </header>

    <div class="grid">
      {#each APPS as app (app.key)}
        <a class="tile" href={base + app.href}>
          <span class="glyph" aria-hidden="true">{app.glyph}</span>
          <span class="label">{t(app.i18n)}</span>
          <span class="sublbl">{app.subtitle}</span>
        </a>
      {/each}
    </div>
  </section>
{/if}

<style>
  .status {
    text-align: center;
    color: var(--ink-mute);
    padding: 2rem 0;
  }
  .launcher {
    max-width: 720px;
    margin: 0 auto;
  }
  .head {
    margin-bottom: 1.5rem;
  }
  h1 {
    font-size: 1.5rem;
    margin: 0 0 0.25rem;
    color: var(--accent);
    letter-spacing: 0.04em;
  }
  .sub {
    color: var(--ink-dim);
    font-size: 0.85rem;
    margin: 0 0 0.5rem;
  }
  .sync-state {
    font-size: 0.7rem;
    color: var(--green);
    margin: 0;
    letter-spacing: 0.05em;
  }
  .sync-state.offline {
    color: var(--ink-mute);
  }
  .sync-state .pending {
    color: var(--accent);
  }

  /* On Android, render as a Palm-style 2-col grid of square tiles */
  :global(html[data-platform='android']) .launcher {
    padding-top: 0.5rem;
  }
  :global(html[data-platform='android']) h1 {
    font-size: 1.25rem;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
    gap: 0.7rem;
  }
  .tile {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    aspect-ratio: 1 / 1;
    background: var(--surface-lo);
    border: 1px solid var(--accent);
    color: var(--ink);
    text-decoration: none;
    padding: 0.9rem 0.4rem;
    gap: 0.35rem;
    text-align: center;
    transition: background 0.12s ease;
  }
  .tile:hover, .tile:active {
    background: var(--accent);
    color: var(--bg);
  }
  .tile:hover .glyph,
  .tile:active .glyph,
  .tile:hover .sublbl,
  .tile:active .sublbl {
    color: var(--bg);
  }
  .glyph {
    font-size: 2.4rem;
    line-height: 1;
    color: var(--accent);
    font-family: 'IBM Plex Mono', system-ui, monospace;
  }
  .label {
    font-size: 0.95rem;
    font-weight: 600;
    line-height: 1.1;
  }
  .sublbl {
    font-size: 0.7rem;
    color: var(--ink-mute);
    line-height: 1.2;
  }
  @media (max-width: 480px) {
    .grid {
      grid-template-columns: repeat(2, 1fr);
      gap: 0.6rem;
    }
    .tile { padding: 0.75rem 0.3rem; gap: 0.25rem; }
    .glyph { font-size: 2rem; }
    .label { font-size: 0.85rem; }
    .sublbl { font-size: 0.65rem; }
  }
</style>

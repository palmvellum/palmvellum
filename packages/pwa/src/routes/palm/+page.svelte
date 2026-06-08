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
  import PalmAppShell from '$lib/components/palm/PalmAppShell.svelte';

  const APPS = [
    { key: 'datebook', href: '/palm/datebook', i18n: 'tab.datebook', glyph: '◫', subI18n: 'palm.sub.datebook' },
    { key: 'todo',     href: '/palm/todo',     i18n: 'tab.todo',     glyph: '☑', subI18n: 'palm.sub.todo' },
    { key: 'address',  href: '/palm/address',  i18n: 'tab.address',  glyph: '✦', subI18n: 'palm.sub.address' },
    { key: 'memo',     href: '/palm/memo',     i18n: 'tab.memo',     glyph: '▤', subI18n: 'palm.sub.memo' },
    { key: 'notepad',  href: '/palm/notepad',  i18n: 'tab.notepad',  glyph: '✎', subI18n: 'palm.sub.notepad' },
    { key: 'mail',     href: '/palm/mail',     i18n: 'tab.mail',     glyph: '✉', subI18n: 'palm.sub.mail' },
    { key: 'expense',  href: '/palm/expense',  i18n: 'tab.expense',  glyph: '¤', subI18n: 'palm.sub.expense' },
    { key: 'settings', href: '/settings',      i18n: 'nav.setting',  glyph: '⚙', subI18n: 'palm.sub.settings' },
  ];
</script>

{#if authState.phase !== 'ready'}
  <p class="status">{t('common.loading')}</p>
{:else}
  <PalmAppShell title={t('palm.launcher')}>
    <section class="launcher">
      <div class="grid">
        {#each APPS as app (app.key)}
          <a class="tile" href={base + app.href}>
            <span class="glyph" aria-hidden="true">{app.glyph}</span>
            <span class="label">{t(app.i18n)}</span>
            <span class="sublbl">{t(app.subI18n)}</span>
          </a>
        {/each}
      </div>
    </section>
  </PalmAppShell>
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
    color: #fff;
  }
  .tile:hover .glyph,
  .tile:active .glyph,
  .tile:hover .sublbl,
  .tile:active .sublbl {
    color: #fff;
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

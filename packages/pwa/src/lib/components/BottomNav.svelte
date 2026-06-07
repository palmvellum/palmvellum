<script lang="ts">
  /**
   * Material-style bottom navigation for the Android wrapper.
   * Rendered globally from the +layout; hidden on web via CSS.
   *
   * Five destinations — same hierarchy as the desktop nav, but
   * the labels are short and each entry has an icon.
   */
  import { page } from '$app/state';
  import { base } from '$app/paths';
  import { t } from '$lib/i18n.svelte';
  import { authState } from '$lib/auth.svelte';

  // Show only when signed in + ready.
  let visible = $derived(authState.phase === 'ready');

  const items = [
    { key: 'palm',     href: '/palm',     icon: '', label: 'Organizers' },
    { key: 'settings', href: '/settings', icon: '', label: 'Settings' },
  ];

  function isActive(href: string): boolean {
    const p = page.url.pathname.replace(base, '') || '/';
    return p === href || p.startsWith(href + '/');
  }
</script>

{#if visible}
  <nav class="bottom-nav" aria-label="primary">
    {#each items as it (it.key)}
      <a
        class="bn-tab"
        class:active={isActive(it.href)}
        href={base + it.href}
      >
        <span class="bn-icon" aria-hidden="true">{it.icon}</span>
        <span class="bn-label">{it.key === 'palm' ? t('nav.organizers') : t('nav.setting')}</span>
      </a>
    {/each}
  </nav>
{/if}

<style>
  .bottom-nav {
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    background: var(--surface-lo, #1c1c1c);
    border-top: 1px solid var(--line, #3a3a3a);
    z-index: 20;
    padding: 6px 0 calc(6px + env(safe-area-inset-bottom));
    align-items: stretch;
    justify-content: space-around;
  }
  .bn-tab {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 2px;
    flex: 1;
    padding: 6px 0;
    color: var(--ink-mute, #888);
    text-decoration: none;
    font-size: 0.7rem;
    letter-spacing: 0.02em;
    line-height: 1;
    transition: color 0.12s ease;
  }
  .bn-tab:hover {
    color: var(--ink, #e8e8e8);
  }
  .bn-tab.active {
    color: var(--accent, #ffd600);
  }
  .bn-icon {
    /* Use Material Symbols glyphs if available; falls back to Unicode. */
    font-family: 'Material Symbols Rounded', 'Material Icons', system-ui, sans-serif;
    font-size: 22px;
    line-height: 1;
  }
  .bn-label {
    font-weight: 500;
  }
</style>

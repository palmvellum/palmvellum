<script lang="ts">
  /**
   * PalmDrawer — left-side slide-in navigation overlay.
   *
   * Hosts the global navigation that used to live in the desktop top
   * nav and the (now-hidden) bottom nav: home/launcher link, settings,
   * language picker, sign out. State is driven by `drawerOpen` from
   * `lib/drawer.svelte.ts` so any header in the app can flip the
   * hamburger.
   */
  import { drawer } from '$lib/drawer.svelte';
  import { base } from '$app/paths';
  import { authState } from '$lib/auth.svelte';
  import {
    SUPPORTED_LANGUAGES,
    currentLang,
    setLang,
    t,
    type Lang,
  } from '$lib/i18n.svelte';
  import { sync } from '$lib/sync.svelte';

  let visible = $derived(drawer.open);

  function close() { drawer.close(); }
  function go(href: string) { close(); /* SvelteKit follows anchor */ void href; }
</script>

{#if visible}
  <div class="bk" onclick={close} role="presentation"></div>
  <aside class="drw" role="dialog" aria-label="navigation">
    <header class="hd">
      <h2>PalmVellum</h2>
      <button type="button" class="close" onclick={close} aria-label="close">×</button>
    </header>

    {#if authState.phase === 'ready'}
      <p class="email">{authState.email}</p>

      <nav class="lst">
        <a class="row" href="{base}/palm" onclick={() => go('/palm')}>
          <span class="ic">⌂</span>
          <span class="lbl">{t('nav.organizers')}</span>
        </a>
        <a class="row" href="{base}/settings" onclick={() => go('/settings')}>
          <span class="ic">⚙</span>
          <span class="lbl">{t('nav.setting')}</span>
        </a>
      </nav>

      <div class="sep"></div>

      <div class="meta">
        <span class="dot" class:on={sync.online}></span>
        {sync.online ? t('drawer.online') : t('drawer.offline')}
        {#if sync.pending_count > 0}
          <span class="pending">{sync.pending_count} {t('drawer.pending')}</span>
        {/if}
      </div>
    {/if}
  </aside>
{/if}

<style>
  .bk {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.45);
    z-index: 100;
  }
  .drw {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    width: 78vw;
    max-width: 320px;
    background: var(--surface-lo);
    border-right: 1px solid var(--line);
    z-index: 101;
    display: flex;
    flex-direction: column;
    padding-top: env(safe-area-inset-top);
    box-shadow: 4px 0 12px rgba(0, 0, 0, 0.25);
    animation: slideIn 0.18s ease-out;
  }
  @keyframes slideIn {
    from { transform: translateX(-100%); }
    to { transform: translateX(0); }
  }
  .hd {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0.7rem 0.9rem;
    border-bottom: 1px solid var(--line);
    background: var(--surface-dk);
    color: #fff;
  }
  .hd h2 {
    margin: 0;
    font-size: 0.95rem;
    font-weight: 700;
    letter-spacing: 0.02em;
    color: #fff;
  }
  .close {
    background: transparent;
    border: 0;
    color: #fff;
    font-size: 1.5rem;
    line-height: 1;
    padding: 0 0.4rem;
    cursor: pointer;
    min-height: 0;
  }
  .email {
    padding: 0.5rem 0.9rem;
    font-size: 0.78rem;
    color: var(--ink-mute);
    margin: 0;
    border-bottom: 1px solid var(--line-soft);
    background: var(--surface-hi);
  }
  .lst {
    display: flex;
    flex-direction: column;
    padding: 0.4rem 0;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 0.7rem;
    padding: 0.7rem 0.9rem;
    color: var(--ink);
    text-decoration: none;
    border: 0;
    font-size: 0.95rem;
    font-family: inherit;
    background: transparent;
    cursor: pointer;
  }
  .row:hover,
  .row:active {
    background: var(--bg);
  }
  .row .ic {
    width: 24px;
    text-align: center;
    color: var(--ink-mute);
    font-size: 1.05rem;
  }
  .sep {
    flex: 1;
    border-top: 1px solid var(--line-soft);
    margin: 0.4rem 0.9rem 0;
  }
  .meta {
    padding: 0.7rem 0.9rem calc(0.9rem + env(safe-area-inset-bottom));
    font-size: 0.78rem;
    color: var(--ink-mute);
    display: flex;
    align-items: center;
    gap: 0.4rem;
  }
  .meta .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--ink-mute);
    display: inline-block;
  }
  .meta .dot.on {
    background: var(--green);
  }
  .meta .pending {
    margin-left: auto;
    color: var(--accent);
    font-weight: 600;
  }
</style>

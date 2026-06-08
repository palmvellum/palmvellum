<script lang="ts">
  /**
   * PalmDrawer — left-side slide-in navigation overlay.
   *
   * Top section lists every organizer app (Date Book → Expense) so
   * the user can jump to any of them without going back to the
   * launcher. Bottom section: Settings + sign-out + sync status.
   */
  import { drawer } from '$lib/drawer.svelte';
  import { base } from '$app/paths';
  import { page } from '$app/state';
  import { authState } from '$lib/auth.svelte';
  import { t } from '$lib/i18n.svelte';
  import { sync } from '$lib/sync.svelte';

  let visible = $derived(drawer.open);

  const APPS = [
    { href: '/palm',           i18n: 'palm.heading', glyph: '⌂', alwaysExact: true },
    { href: '/palm/datebook',  i18n: 'tab.datebook', glyph: '◫' },
    { href: '/palm/todo',      i18n: 'tab.todo',     glyph: '☑' },
    { href: '/palm/address',   i18n: 'tab.address',  glyph: '✦' },
    { href: '/palm/memo',      i18n: 'tab.memo',     glyph: '▤' },
    { href: '/palm/notepad',   i18n: 'tab.notepad',  glyph: '✎' },
    { href: '/palm/mail',      i18n: 'tab.mail',     glyph: '✉' },
    { href: '/palm/expense',   i18n: 'tab.expense',  glyph: '¤' },
  ];

  function close() { drawer.close(); }

  function isActive(href: string, exact = false): boolean {
    const path = page.url.pathname.replace(base, '') || '/';
    if (exact) return path === href;
    return path === href;
  }
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
        {#each APPS as item (item.href)}
          <a
            class="row"
            class:active={isActive(item.href, item.alwaysExact)}
            href={base + item.href}
            onclick={close}
          >
            <span class="ic">{item.glyph}</span>
            <span class="lbl">{t(item.i18n)}</span>
          </a>
        {/each}
      </nav>

      <div class="sep"></div>

      <nav class="lst settings">
        <a class="row" href={base + '/settings'} onclick={close}>
          <span class="ic">⚙</span>
          <span class="lbl">{t('nav.setting')}</span>
        </a>
      </nav>

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
    max-width: 300px;
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
    font-size: 0.74rem;
    color: var(--ink-mute);
    margin: 0;
    border-bottom: 1px solid var(--line-soft);
    background: var(--surface-hi);
  }
  .lst {
    display: flex;
    flex-direction: column;
    padding: 0.25rem 0;
  }
  .lst.settings { padding: 0.1rem 0 0.3rem; }
  .row {
    display: flex;
    align-items: center;
    gap: 0.7rem;
    padding: 0.55rem 0.9rem;
    color: var(--ink);
    text-decoration: none;
    border: 0;
    font-size: 0.9rem;
    font-family: inherit;
    background: transparent;
    cursor: pointer;
  }
  .row:hover, .row:active {
    background: var(--bg);
  }
  .row.active {
    background: var(--bg);
    border-left: 3px solid var(--ink);
    padding-left: calc(0.9rem - 3px);
    font-weight: 700;
  }
  .row .ic {
    width: 22px;
    text-align: center;
    color: var(--ink-mute);
    font-size: 1rem;
  }
  .row.active .ic { color: var(--ink); }
  .sep {
    border-top: 1px solid var(--line-soft);
    margin: 0.3rem 0.9rem;
  }
  .meta {
    margin-top: auto;
    padding: 0.55rem 0.9rem calc(0.9rem + env(safe-area-inset-bottom));
    font-size: 0.74rem;
    color: var(--ink-mute);
    display: flex;
    align-items: center;
    gap: 0.4rem;
    border-top: 1px solid var(--line-soft);
    background: var(--surface-hi);
  }
  .meta .dot {
    width: 8px; height: 8px; border-radius: 50%;
    background: var(--ink-mute);
    display: inline-block;
  }
  .meta .dot.on { background: var(--green); }
  .meta .pending {
    margin-left: auto;
    color: var(--ink);
    font-weight: 600;
  }
</style>

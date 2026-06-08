<script lang="ts">
  /**
   * PalmAppShell — Palm OS-style title bar + body container for each
   * organizer screen. Title bar has a hamburger on the left (opens
   * the global drawer), the app title in the centre, and an optional
   * top-right snippet for per-app actions. Body content sits below.
   *
   * Applies the same chrome on every platform (web + Android) so the
   * organizer surface looks identical across surfaces.
   */
  import { drawer } from '$lib/drawer.svelte';
  import { sync } from '$lib/sync.svelte';
  import { base } from '$app/paths';
  import { goto } from '$app/navigation';
  import type { Snippet } from 'svelte';

  interface Props {
    title: string;
    backHref?: string;        // when set, shows a back arrow instead of hamburger
    category?: {
      value: string;
      options: { value: string; label: string }[];
      onchange: (v: string) => void;
    } | null;
    topRight?: Snippet;
    toolbar?: Snippet;
    children: Snippet;
  }

  let {
    title,
    backHref = '',
    category = null,
    topRight,
    toolbar,
    children,
  }: Props = $props();

  function openDrawer() { drawer.toggle(); }
  function goBack() {
    if (backHref) {
      void goto(base + backHref, { replaceState: true });
    } else if (history.length > 1) {
      history.back();
    } else {
      void goto(base + '/palm', { replaceState: true });
    }
  }
</script>

<section class="palm-app">
  <header class="bar">
    {#if backHref}
      <button type="button" class="ham" onclick={goBack} aria-label="Back">
        <span aria-hidden="true">◀</span>
      </button>
    {:else}
      <button type="button" class="ham" onclick={openDrawer} aria-label="Menu">
        <span aria-hidden="true">≡</span>
      </button>
    {/if}
    <h1 class="title">{title}</h1>
    {#if category}
      <select
        class="cat"
        value={category.value}
        onchange={(e) => category.onchange((e.currentTarget as HTMLSelectElement).value)}
        aria-label="Category"
      >
        {#each category.options as opt (opt.value)}
          <option value={opt.value}>{opt.label}</option>
        {/each}
      </select>
    {/if}
    {#if topRight}
      <div class="tr">{@render topRight()}</div>
    {/if}
    <span
      class="net"
      class:on={sync.online}
      aria-label={sync.online ? 'online' : 'offline'}
      title={sync.online ? 'online' : 'offline'}
    >
      <span class="net-dot"></span>
      {#if sync.pending_count > 0}<span class="net-pending">{sync.pending_count}</span>{/if}
    </span>
  </header>

  <div class="body">
    {@render children()}
  </div>

  {#if toolbar}
    <footer class="palm-toolbar">
      {@render toolbar()}
    </footer>
  {/if}
</section>

<style>
  .palm-app {
    display: flex;
    flex-direction: column;
    min-height: 100vh;
    background: var(--bg);
  }
  .bar {
    display: flex;
    align-items: center;
    gap: 0.3rem;
    /* extra top padding so the bar sits below the device status bar
       (Capacitor WebView extends edge-to-edge by default) */
    padding: max(env(safe-area-inset-top), 0px) 0.45rem 0;
    height: calc(38px + max(env(safe-area-inset-top), 0px));
    box-sizing: border-box;
    background: var(--surface-dk);
    color: #fff;
    position: sticky;
    top: 0;
    z-index: 5;
    border-bottom: 1px solid #1a1a1a;
  }
  .ham {
    background: transparent;
    border: 0;
    color: #fff;
    font-size: 1.4rem;
    line-height: 1;
    padding: 0 0.55rem;
    cursor: pointer;
    min-height: 36px;
    border-radius: 4px;
  }
  .ham:hover,
  .ham:active {
    background: rgba(255, 255, 255, 0.12);
  }
  .title {
    flex: 1;
    margin: 0;
    font-size: 0.95rem;
    font-weight: 700;
    letter-spacing: 0.01em;
    color: #fff;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .cat {
    background: var(--surface-hi);
    border: 1px solid #1a1a1a;
    color: var(--ink);
    border-radius: 3px;
    padding: 0.15rem 0.4rem;
    font: inherit;
    font-size: 0.78rem;
    max-width: 8rem;
  }
  .tr {
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
    color: #fff;
  }
  /* Always-on online/offline indicator in the top-right of the bar. */
  .net {
    display: inline-flex;
    align-items: center;
    gap: 0.3rem;
    margin-left: 0.4rem;
    margin-right: 0.2rem;
    color: #fff;
    font-size: 0.7rem;
    font-weight: 600;
    letter-spacing: 0.02em;
  }
  .net-dot {
    width: 9px;
    height: 9px;
    border-radius: 50%;
    background: #c0392b;
    box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.4);
  }
  .net.on .net-dot {
    background: #2ecc71;
  }
  .net-pending {
    color: #ffd17a;
    font-weight: 700;
  }
  .body {
    flex: 1;
    padding: 0.75rem 0.85rem calc(0.5rem + env(safe-area-inset-bottom));
    overflow-y: auto;
    background: var(--bg);
  }
  .palm-toolbar {
    position: sticky;
    bottom: 0;
    background: var(--surface-lo);
    border-top: 1px solid var(--line);
    padding: 0.45rem 0.7rem calc(0.45rem + env(safe-area-inset-bottom));
    display: flex;
    align-items: center;
    gap: 0.4rem;
    z-index: 4;
  }
  /* On wide web viewports, cap the Palm surface so it doesn't sprawl. */
  @media (min-width: 720px) {
    :global(html:not([data-platform='android'])) .palm-app {
      max-width: 720px;
      margin: 0 auto;
      min-height: calc(100vh - 80px);
      border: 1px solid var(--line);
      box-shadow: 0 8px 32px rgba(0, 0, 0, 0.15);
    }
  }

  /* When the side drawer is docked open, leave a 260px gutter on the
     left so the shell content + sticky title bar align with the
     visible area (the rail itself is position:fixed and overlays the
     gutter). */
  :global(html.drawer-docked) .palm-app {
    padding-left: 260px;
  }
  @media (min-width: 720px) {
    :global(html.drawer-docked:not([data-platform='android'])) .palm-app {
      max-width: 980px;
      /* Left-align so the 260px padding gutter sits flush against the
         fixed rail. Without this the `margin: 0 auto` inherited from the
         wide-viewport rule above centres the shell, opening an empty band
         between the docked drawer and the content. */
      margin: 0;
    }
  }
</style>

<script lang="ts">
  /**
   * PalmAppShell — full-screen Android-only chrome for each
   * organizer app. Renders nothing extra on web; on Android it
   * paints a high-contrast Palm-inspired top bar (title + back
   * arrow + optional category selector) and a fixed bottom action
   * bar where a + new button or other tools live.
   *
   * Slot the app body via the `children` prop. Optional snippets:
   *   - `topRight` for a small action in the header
   *   - `toolbar`  for the bottom action row
   */
  import { base } from '$app/paths';
  import { goto } from '$app/navigation';
  import type { Snippet } from 'svelte';

  interface Props {
    title: string;
    backHref?: string;
    category?: { value: string; options: { value: string; label: string }[]; onchange: (v: string) => void } | null;
    topRight?: Snippet;
    toolbar?: Snippet;
    children: Snippet;
  }

  let {
    title,
    backHref = '/palm',
    category = null,
    topRight,
    toolbar,
    children,
  }: Props = $props();

  function goBack() {
    if (history.length > 1 && history.state) {
      history.back();
    } else {
      void goto(base + backHref, { replaceState: true });
    }
  }
</script>

<section class="palm-app">
  <header class="palm-app-bar">
    <button type="button" class="back-btn" onclick={goBack} aria-label="Back">
      <span aria-hidden="true">‹</span>
    </button>
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
      <div class="top-right">{@render topRight()}</div>
    {/if}
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
    margin: -1.5rem -1.5rem -4rem;
    background: var(--bg);
  }
  @media (max-width: 720px) {
    .palm-app {
      margin: -0.9rem -0.85rem -3rem;
    }
  }
  @media (max-width: 480px) {
    .palm-app {
      margin: -0.75rem -0.7rem -3rem;
    }
  }
  .palm-app-bar {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    height: 44px;
    padding: 0 0.5rem;
    background: var(--surface-lo);
    border-bottom: 2px solid var(--accent);
    position: sticky;
    top: 0;
    z-index: 5;
  }
  .back-btn {
    background: transparent;
    border: 0;
    color: var(--accent);
    font-size: 1.8rem;
    line-height: 1;
    padding: 0 0.5rem;
    cursor: pointer;
    height: 38px;
    min-height: 38px;
  }
  .back-btn:hover {
    color: var(--ink);
  }
  .title {
    flex: 1;
    margin: 0;
    font-size: 1rem;
    font-weight: 600;
    letter-spacing: 0.02em;
    color: var(--ink);
    text-transform: lowercase;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .cat {
    background: var(--surface-hi);
    border: 1px solid var(--line);
    color: var(--ink);
    border-radius: 4px;
    padding: 0.2rem 0.4rem;
    font: inherit;
    font-size: 0.8rem;
    max-width: 8rem;
  }
  .top-right {
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
  }
  .body {
    flex: 1;
    padding: 0.75rem 0.85rem calc(60px + env(safe-area-inset-bottom));
    overflow-y: auto;
  }
  .palm-toolbar {
    position: fixed;
    left: 0;
    right: 0;
    bottom: 60px;
    background: var(--surface-lo);
    border-top: 1px solid var(--line);
    padding: 0.5rem 0.75rem calc(0.5rem + env(safe-area-inset-bottom));
    display: flex;
    align-items: center;
    gap: 0.5rem;
    z-index: 4;
  }
  /* On Android, bottom-nav lives at the very bottom so push our toolbar above it */
  :global(html[data-platform='android']) .palm-toolbar {
    bottom: calc(60px + env(safe-area-inset-bottom));
  }
  /* Hide on web — the Palm chrome is Android-only */
  :global(html:not([data-platform='android'])) .palm-app {
    margin: 0;
    min-height: auto;
  }
  :global(html:not([data-platform='android'])) .palm-app-bar {
    display: none;
  }
  :global(html:not([data-platform='android'])) .palm-toolbar {
    display: none;
  }
  :global(html:not([data-platform='android'])) .body {
    padding: 0;
  }
</style>

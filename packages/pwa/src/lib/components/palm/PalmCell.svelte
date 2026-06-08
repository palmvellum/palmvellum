<script lang="ts">
  /**
   * PalmCell — single tappable row inside a PalmList. Mimics the
   * Palm OS list row: small leading bullet/dot + bold title + small
   * trailing meta. Cell auto-grows for multi-line body.
   */
  import type { Snippet } from 'svelte';

  interface Props {
    leading?: string;          // single-char bullet (e.g. '·', '✓', '□')
    title: string;
    meta?: string;             // small grey text on the right
    metaAccent?: boolean;      // render meta in dark red (e.g. clock times)
    onclick?: () => void;
    href?: string;
    accent?: boolean;          // highlight in accent yellow
    dim?: boolean;             // grey out (e.g. completed todo)
    children?: Snippet;        // optional body (notes / preview)
  }

  let { leading, title, meta, metaAccent = false, onclick, href, accent = false, dim = false, children }: Props = $props();
</script>

{#if href}
  <a class="cell" class:accent class:dim href={href}>
    {#if leading}<span class="lead">{leading}</span>{/if}
    <span class="body">
      <span class="title">{title}</span>
      {#if children}<span class="sub">{@render children()}</span>{/if}
    </span>
    {#if meta}<span class="meta" class:meta-accent={metaAccent}>{meta}</span>{/if}
  </a>
{:else}
  <button type="button" class="cell" class:accent class:dim onclick={onclick}>
    {#if leading}<span class="lead">{leading}</span>{/if}
    <span class="body">
      <span class="title">{title}</span>
      {#if children}<span class="sub">{@render children()}</span>{/if}
    </span>
    {#if meta}<span class="meta" class:meta-accent={metaAccent}>{meta}</span>{/if}
  </button>
{/if}

<style>
  .cell {
    display: flex;
    align-items: flex-start;
    gap: 0.6rem;
    background: transparent;
    border: 0;
    border-bottom: 1px solid var(--line-soft);
    text-align: left;
    padding: 0.55rem 0.65rem;
    color: var(--ink);
    font: inherit;
    cursor: pointer;
    text-decoration: none;
    min-height: 44px;
  }
  .cell:last-child {
    border-bottom: 0;
  }
  .cell:hover,
  .cell:active {
    background: var(--surface-hi);
  }
  .cell.accent .title {
    color: var(--accent);
    font-weight: 600;
  }
  .cell.dim .title,
  .cell.dim .sub {
    color: var(--ink-mute);
    text-decoration: line-through;
  }
  .lead {
    color: var(--accent);
    font-size: 0.95rem;
    line-height: 1.35;
    flex-shrink: 0;
    width: 1rem;
    text-align: center;
  }
  .body {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .title {
    font-size: 0.95rem;
    line-height: 1.3;
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  }
  .sub {
    font-size: 0.78rem;
    color: var(--ink-mute);
    line-height: 1.3;
  }
  .meta {
    font-size: 0.72rem;
    color: var(--ink-mute);
    flex-shrink: 0;
    margin-left: 0.4rem;
    align-self: flex-start;
    padding-top: 2px;
  }
  /* Dark red highlight for clock times so the schedule reads at a glance. */
  .meta.meta-accent {
    color: #8b1a1a;
    font-weight: 700;
    font-size: 0.78rem;
  }
</style>

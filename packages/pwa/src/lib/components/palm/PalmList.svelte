<script lang="ts">
  /** PalmList — bordered list container with optional header label. */
  import type { Snippet } from 'svelte';
  interface Props { label?: string; children: Snippet; }
  let { label, children }: Props = $props();
</script>

<div class="palm-list">
  {#if label}<div class="hdr">{label}</div>{/if}
  <div class="rows">{@render children()}</div>
</div>

<style>
  .palm-list {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    margin-bottom: 1rem;
  }
  .hdr {
    background: var(--accent);
    color: #fff;
    font-size: 0.7rem;
    text-transform: uppercase;
    letter-spacing: 0.1em;
    font-weight: 700;
    padding: 0.25rem 0.65rem;
  }
  .rows {
    display: flex;
    flex-direction: column;
  }
</style>

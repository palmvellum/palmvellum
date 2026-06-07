<script lang="ts">
  /** PalmCategoryStrip — horizontal scrollable category tabs. */
  interface Props {
    items: { key: string; label: string; count?: number }[];
    active: string;
    onpick: (key: string) => void;
  }
  let { items, active, onpick }: Props = $props();
</script>

<nav class="strip" role="tablist">
  {#each items as it (it.key)}
    <button
      type="button"
      role="tab"
      aria-selected={active === it.key}
      class:active={active === it.key}
      onclick={() => onpick(it.key)}
    >
      {it.label}{#if it.count != null}<span class="ct">{it.count}</span>{/if}
    </button>
  {/each}
</nav>

<style>
  .strip {
    display: flex;
    gap: 4px;
    overflow-x: auto;
    padding: 0.4rem 0;
    margin-bottom: 0.6rem;
    scrollbar-width: none;
  }
  .strip::-webkit-scrollbar { display: none; }
  .strip button {
    flex-shrink: 0;
    background: var(--surface-hi);
    border: 1px solid var(--line);
    color: var(--ink-dim);
    padding: 0.3rem 0.75rem;
    font: inherit;
    font-size: 0.78rem;
    cursor: pointer;
    border-radius: 0;
    min-height: 36px;
  }
  .strip button.active {
    background: var(--accent);
    color: var(--bg);
    border-color: var(--accent);
    font-weight: 600;
  }
  .strip button .ct {
    margin-left: 0.35rem;
    font-size: 0.7rem;
    opacity: 0.85;
  }
</style>

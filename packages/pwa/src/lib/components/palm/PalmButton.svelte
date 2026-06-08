<script lang="ts">
  /** PalmButton — accent / outline / ghost styles. */
  import type { Snippet } from 'svelte';
  interface Props {
    variant?: 'primary' | 'ghost' | 'outline';
    type?: 'button' | 'submit';
    disabled?: boolean;
    onclick?: (e: MouseEvent) => void;
    children: Snippet;
  }
  let {
    variant = 'primary',
    type = 'button',
    disabled = false,
    onclick,
    children,
  }: Props = $props();
</script>

<button
  class="palm-btn {variant}"
  {type}
  {disabled}
  onclick={onclick}
>
  {@render children()}
</button>

<style>
  .palm-btn {
    background: var(--accent);
    color: #fff;
    border: 1px solid var(--accent);
    padding: 0.45rem 0.85rem;
    font: inherit;
    font-weight: 600;
    cursor: pointer;
    min-height: 38px;
    border-radius: 0;
    letter-spacing: 0.02em;
  }
  .palm-btn:hover:not(:disabled) {
    background: var(--accent-dim);
  }
  .palm-btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .palm-btn.ghost {
    background: transparent;
    border-color: transparent;
    color: var(--ink-mute);
    font-weight: normal;
  }
  .palm-btn.ghost:hover:not(:disabled) {
    background: var(--surface-hi);
    color: var(--ink);
  }
  .palm-btn.outline {
    background: transparent;
    color: var(--accent);
    border-color: var(--accent);
  }
  .palm-btn.outline:hover:not(:disabled) {
    background: var(--accent);
    color: #fff;
  }
</style>

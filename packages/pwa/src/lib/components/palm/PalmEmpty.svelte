<script lang="ts">
  interface Props { title: string; hint?: string; }
  let { title, hint }: Props = $props();
</script>

<div class="empty">
  <p class="t">{title}</p>
  {#if hint}<p class="h">{hint}</p>{/if}
</div>

<style>
  .empty {
    padding: 2.5rem 1rem;
    text-align: center;
    color: var(--ink-mute);
  }
  .t {
    font-size: 0.95rem;
    margin: 0 0 0.4rem;
  }
  .h {
    font-size: 0.8rem;
    margin: 0;
    color: var(--ink-mute);
  }
</style>

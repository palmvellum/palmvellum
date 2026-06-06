<script lang="ts">
  /**
   * /devices/[id] — legacy URL. Per-device tabs no longer exist;
   * everything routes to /palm now.
   */
  import { onMount } from 'svelte';
  import { base } from '$app/paths';
  import { goto } from '$app/navigation';

  onMount(() => {
    void goto(base + '/palm');
  });
</script>

<p class="redirect">
  Per-device pages were removed — all Palms now share one dataset.
  <a href="{base}/palm">Go to my palm &gt;</a>
</p>

<style>
  .redirect {
    text-align: center;
    padding: 3rem 1rem;
    color: var(--ink-mute);
  }
  a {
    color: var(--accent);
  }
</style>

<script lang="ts">
  /**
   * /devices — legacy URL. Multi-device data partitioning was
   * cancelled in v0.5 Phase 1.5 (one user = one database). The
   * single-page dashboard now lives at /palm; device registration
   * for hardware tracking moved under /settings.
   */
  import { onMount } from 'svelte';
  import { base } from '$app/paths';
  import { goto } from '$app/navigation';

  onMount(() => {
    void goto(base + '/palm');
  });
</script>

<p class="redirect">
  Devices moved — every Palm now shares one dataset.
  <a href="{base}/palm">Go to my palm →</a>
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

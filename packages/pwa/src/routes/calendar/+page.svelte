<script lang="ts">
  /**
   * /calendar — legacy URL.
   *
   * As of v0.5 Phase 1 the calendar lives inside each device's
   * Date Book tab (/devices/[id]/datebook). This stub bounces
   * back to the device picker so old bookmarks still land
   * somewhere useful.
   */
  import { onMount } from 'svelte';
  import { base } from '$app/paths';
  import { goto } from '$app/navigation';

  onMount(() => {
    void goto(base + '/devices');
  });
</script>

<p class="redirect">
  The calendar moved — each Palm now has its own Date Book tab.
  <a href="{base}/devices">Go to my devices →</a>
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

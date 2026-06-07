<script lang="ts">
  import PalmAppShell from '$lib/components/palm/PalmAppShell.svelte';
  import DateBookPalm from '$lib/components/palm/DateBookPalm.svelte';
  import { authState } from '$lib/auth.svelte';
  import { t } from '$lib/i18n.svelte';
</script>

<svelte:head>
  <title>PalmVellum · Date Book</title>
</svelte:head>

{#if authState.phase === 'ready'}
  <PalmAppShell title={t('tab.datebook')}>
    <DateBookPalm />
  </PalmAppShell>
{:else}
  <p class="loading">loading…</p>
{/if}

<style>
  .loading { text-align: center; padding: 2rem 0; color: var(--ink-mute); }
</style>

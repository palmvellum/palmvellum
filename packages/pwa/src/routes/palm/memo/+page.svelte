<script lang="ts">
  import PalmAppShell from '$lib/components/palm/PalmAppShell.svelte';
  import MemoPad from '$lib/components/MemoPad.svelte';
  import { authState } from '$lib/auth.svelte';
  import { t } from '$lib/i18n.svelte';
</script>

<svelte:head>
  <title>PalmVellum · Memo</title>
</svelte:head>

{#if authState.phase === 'ready'}
  <PalmAppShell title={t('tab.memo')}>
    <MemoPad />
  </PalmAppShell>
{:else}
  <p class="loading">{t('common.loading')}</p>
{/if}

<style>
  .loading { text-align: center; padding: 2rem 0; color: var(--ink-mute); }
</style>

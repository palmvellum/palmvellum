<script lang="ts">
  import PalmAppShell from '$lib/components/palm/PalmAppShell.svelte';
  import ExpenseLog from '$lib/components/ExpenseLog.svelte';
  import { authState } from '$lib/auth.svelte';
  import { t } from '$lib/i18n.svelte';
</script>

<svelte:head>
  <title>PalmVellum · Expense</title>
</svelte:head>

{#if authState.phase === 'ready'}
  <PalmAppShell title={t('tab.expense')}>
    <ExpenseLog />
  </PalmAppShell>
{:else}
  <p class="loading">{t('common.loading')}</p>
{/if}

<style>
  .loading { text-align: center; padding: 2rem 0; color: var(--ink-mute); }
</style>

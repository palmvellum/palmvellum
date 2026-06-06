<script lang="ts">
  /**
   * /palm — single-user dashboard, replaces the per-device
   * /devices/[id] page. Every user has ONE shared dataset; if
   * they own multiple Palms, those Palms all sync to/from this
   * same set of records / events.
   *
   * Five tabs are functional today: Date Book / Memo Pad /
   * To Do List / Address / Expense. Note Pad and Mail still
   * carry phase notes because they need new infrastructure
   * (bitmap rendering + Storage; scheduled fetcher + summarizer).
   */
  import { authState } from '$lib/auth.svelte';
  import DateBook from '$lib/components/DateBook.svelte';
  import MemoPad from '$lib/components/MemoPad.svelte';
  import TodoList from '$lib/components/TodoList.svelte';
  import AddressBook from '$lib/components/AddressBook.svelte';
  import ExpenseLog from '$lib/components/ExpenseLog.svelte';
  import NotePad from '$lib/components/NotePad.svelte';
  import Mail from '$lib/components/Mail.svelte';

  const TABS = [
    { key: 'datebook', label: 'Date Book' },
    { key: 'todo', label: 'To Do List' },
    { key: 'address', label: 'Address' },
    { key: 'memo', label: 'Memo Pad' },
    { key: 'notepad', label: 'Note Pad' },
    { key: 'mail', label: 'Mail' },
    { key: 'expense', label: 'Expense' },
  ];

  let activeTab = $state<string>('datebook');
</script>

{#if authState.phase !== 'ready'}
  <p class="status">loading…</p>
{:else}
  <section class="palm">
    <header class="head">
      <h1>my palm</h1>
      <p class="sub">
        one shared dataset for every Palm you own — Date Book / To Do List /
        Address / Memo Pad / Note Pad / Mail / Expense.
      </p>
    </header>

    <div class="tabs" role="tablist" aria-label="palm apps">
      {#each TABS as t (t.key)}
        <button
          role="tab"
          aria-selected={activeTab === t.key}
          class:active={activeTab === t.key}
          onclick={() => (activeTab = t.key)}
        >
          {t.label}
        </button>
      {/each}
    </div>

    {#if activeTab === 'datebook'}
      <div class="host" role="tabpanel"><DateBook /></div>
    {:else if activeTab === 'memo'}
      <div class="host" role="tabpanel"><MemoPad /></div>
    {:else if activeTab === 'todo'}
      <div class="host" role="tabpanel"><TodoList /></div>
    {:else if activeTab === 'address'}
      <div class="host" role="tabpanel"><AddressBook /></div>
    {:else if activeTab === 'expense'}
      <div class="host" role="tabpanel"><ExpenseLog /></div>
    {:else if activeTab === 'notepad'}
      <div class="host" role="tabpanel"><NotePad /></div>
    {:else if activeTab === 'mail'}
      <div class="host" role="tabpanel"><Mail /></div>
    {/if}
  </section>
{/if}

<style>
  .status {
    text-align: center;
    color: var(--ink-mute);
    padding: 2rem 0;
  }
  .palm {
    max-width: 1000px;
    margin: 0 auto;
  }
  .head {
    margin-bottom: 1.2rem;
  }
  h1 {
    margin: 0 0 0.3rem;
    font-size: 1.4rem;
    color: var(--accent);
    text-transform: lowercase;
    letter-spacing: 0.05em;
  }
  .sub {
    margin: 0;
    color: var(--ink-mute);
    font-size: 0.85rem;
  }

  .tabs {
    display: flex;
    flex-wrap: wrap;
    border-bottom: 1px solid var(--line);
    margin-bottom: 1rem;
  }
  .tabs button {
    flex: 1 1 auto;
    background: transparent;
    color: var(--ink-mute);
    border: none;
    border-right: 1px solid var(--line);
    border-radius: 0;
    padding: 0.65rem 0.6rem;
    font: inherit;
    font-size: 0.85rem;
    cursor: pointer;
    text-transform: lowercase;
    letter-spacing: 0.04em;
    min-width: 90px;
  }
  .tabs button:last-child {
    border-right: none;
  }
  .tabs button:hover {
    color: var(--ink);
    background: var(--surface);
  }
  .tabs button.active {
    color: var(--accent);
    background: var(--bg);
    box-shadow: inset 0 -2px 0 var(--accent);
  }

  .host {
    /* Component owns its own internal layout. */
    margin: 0;
  }
  .panel {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 1.2rem 1.4rem;
    border-radius: 2px;
  }
  .panel h2 {
    margin: 0 0 0.7rem;
    font-size: 1.1rem;
    color: var(--accent);
    text-transform: lowercase;
    letter-spacing: 0.05em;
  }
  .panel p {
    margin: 0 0 0.7rem;
    color: var(--ink-dim);
    line-height: 1.55;
    font-size: 0.92rem;
  }
  .phase-note {
    color: var(--ink-mute) !important;
    font-size: 0.8rem !important;
    font-style: italic;
    margin-top: 1rem !important;
  }
</style>

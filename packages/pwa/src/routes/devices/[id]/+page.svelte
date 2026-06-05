<script lang="ts">
  /**
   * /devices/[id] — per-Palm detail with seven app tabs.
   *
   * Phase 0 ships placeholders for every tab. Phase 1 hooks the
   * Memo Pad / To Do List / Date Book tabs into the existing
   * records + events tables. Phase 2 adds Address / Expense /
   * Mail / Note Pad with brand-new PDB codecs and storage paths.
   */
  import { onMount } from 'svelte';
  import { base } from '$app/paths';
  import { page } from '$app/stores';
  import { goto } from '$app/navigation';
  import { supabase } from '$lib/supabase';
  import { authState } from '$lib/auth.svelte';
  import DateBook from '$lib/components/DateBook.svelte';

  interface Device {
    id: string;
    user_id: string;
    name: string;
    model: string;
    serial: string | null;
    last_sync_at: string | null;
    created_at: string;
  }

  const TABS = [
    { key: 'datebook', label: 'Date Book',    phase: 1 },
    { key: 'todo',     label: 'To Do List',   phase: 1 },
    { key: 'address',  label: 'Address',      phase: 2 },
    { key: 'memo',     label: 'Memo Pad',     phase: 1 },
    { key: 'notepad',  label: 'Note Pad',     phase: 3 },
    { key: 'mail',     label: 'Mail',         phase: 5 },
    { key: 'expense',  label: 'Expense',      phase: 2 },
  ];

  let device = $state<Device | null>(null);
  let loadError = $state<string | null>(null);
  let loading = $state(true);
  let activeTab = $state<string>('datebook');
  let deleting = $state(false);

  $effect(() => {
    const id = $page.params.id;
    if (id && authState.userId && device?.id !== id) {
      void load(id);
    }
  });

  async function load(id: string) {
    loading = true;
    loadError = null;
    const { data, error } = await supabase
      .from('devices')
      .select('*')
      .eq('id', id)
      .maybeSingle();
    loading = false;
    if (error) {
      loadError = error.message;
      return;
    }
    if (!data) {
      loadError = 'device not found';
      return;
    }
    device = data as Device;
  }

  async function deleteDevice() {
    if (!device) return;
    if (!confirm(`Delete "${device.name}"? Records pushed to this Palm will lose their device link but won't be deleted.`)) return;
    deleting = true;
    const { error } = await supabase.from('devices').delete().eq('id', device.id);
    deleting = false;
    if (error) {
      alert('delete failed: ' + error.message);
      return;
    }
    await goto(`${base}/devices`);
  }

  function fmtTime(s: string | null): string {
    if (!s) return 'never';
    return new Date(s).toLocaleString();
  }

  onMount(() => {
    if (authState.phase === 'ready' && $page.params.id) {
      void load($page.params.id);
    }
  });
</script>

{#if loading}
  <p class="status">loading…</p>
{:else if loadError}
  <p class="status error">{loadError}</p>
  <p><a class="link" href="{base}/devices">← back to my devices</a></p>
{:else if device}
  <section class="device">
    <header class="head">
      <div>
        <a class="back" href="{base}/devices">← my devices</a>
        <h1>{device.name}</h1>
        <p class="meta">
          <span class="model">{device.model}</span>
          {#if device.serial}<span class="sep">·</span><span>serial {device.serial}</span>{/if}
          <span class="sep">·</span><span>last sync {fmtTime(device.last_sync_at)}</span>
        </p>
      </div>
      <button class="del-btn" onclick={deleteDevice} disabled={deleting}>
        {deleting ? 'deleting…' : 'remove'}
      </button>
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
      <!-- Full-width Date Book — no .panel wrapper, the component
           has its own 2-column layout (month grid + AI panel). -->
      <div class="datebook-host" role="tabpanel">
        <DateBook deviceId={device.id} />
      </div>
    {:else}
    <div class="panel" role="tabpanel">
      {#if activeTab === 'todo'}
        <h2>To Do List</h2>
        <p>
          Bidirectional sync with <em>ToDoDB.pdb</em> (description,
          due date, priority, completed). Tasks starting with
          <code>(AI)</code> get executed by an AI worker — the result
          is written as a Memo titled <em>AI Result: …</em> and the
          task is marked completed.
        </p>
        <p class="phase-note">Sync: Phase 1. (AI) trigger: Phase 4.</p>
      {:else if activeTab === 'address'}
        <h2>Address</h2>
        <p>
          Bidirectional sync with <em>AddressDB.pdb</em>. Phase 2
          adds the contact-record codec.
        </p>
        <p class="phase-note">Phase 2.</p>
      {:else if activeTab === 'memo'}
        <h2>Memo Pad</h2>
        <p>
          Bidirectional sync with <em>MemoDB.pdb</em>. Memos starting
          with <code>(AI)</code> get analyzed — the AI extracts
          mentions of calendar events, todos, etc. and creates them
          on the appropriate apps, then appends what it did to the
          memo body.
        </p>
        <p class="phase-note">Sync: Phase 1. (AI) trigger: Phase 4.</p>
      {:else if activeTab === 'notepad'}
        <h2>Note Pad</h2>
        <p>
          Note Pad freehand drawings on your Palm are rendered to
          JPG on the platform; AI runs OCR / sketch interpretation
          and stores the text version alongside the original image.
        </p>
        <p class="phase-note">Phase 3.</p>
      {:else if activeTab === 'mail'}
        <h2>Mail</h2>
        <p>
          Configure websites; AI visits each at your chosen daily
          time, summarizes the day's content, and delivers it to
          your Palm's <em>MailDB.pdb</em> as a regular email
          message — like a personalized morning paper.
        </p>
        <p class="phase-note">Phase 5.</p>
      {:else if activeTab === 'expense'}
        <h2>Expense</h2>
        <p>
          Bidirectional sync with <em>ExpenseDB.pdb</em> (amount,
          currency, category, vendor, payment method, attendees).
        </p>
        <p class="phase-note">Phase 2.</p>
      {/if}
    </div>
    {/if}
  </section>
{/if}

<style>
  .status {
    text-align: center;
    color: var(--ink-mute);
    padding: 2rem 0;
  }
  .status.error {
    color: #ff6b6b;
  }
  .link {
    color: var(--accent);
  }
  .device {
    max-width: 820px;
    margin: 0 auto;
  }
  .head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 1rem;
    margin-bottom: 1.5rem;
  }
  .back {
    display: inline-block;
    margin-bottom: 0.4rem;
    color: var(--ink-mute);
    font-size: 0.8rem;
    text-decoration: none;
    border-bottom: 1px dotted transparent;
  }
  .back:hover {
    color: var(--accent);
    border-bottom-color: var(--accent-dim);
  }
  h1 {
    margin: 0 0 0.3rem;
    font-size: 1.6rem;
    color: var(--ink);
  }
  .meta {
    margin: 0;
    color: var(--ink-mute);
    font-size: 0.85rem;
  }
  .model {
    color: var(--accent);
  }
  .sep {
    color: var(--line);
    margin: 0 0.5rem;
  }
  .del-btn {
    background: none;
    border: 1px solid var(--line);
    color: var(--ink-mute);
    padding: 0.4rem 0.8rem;
    font: inherit;
    font-size: 0.8rem;
    cursor: pointer;
  }
  .del-btn:hover:not(:disabled) {
    border-color: #ff6b6b;
    color: #ff6b6b;
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
    font-family: inherit;
    font-size: 0.85rem;
    cursor: pointer;
    text-transform: lowercase;
    letter-spacing: 0.04em;
    min-width: 88px;
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
  .panel code {
    background: var(--surface);
    padding: 0.1rem 0.35rem;
    border: 1px solid var(--line);
    border-radius: 2px;
    color: var(--accent);
    font-size: 0.85rem;
  }
  .phase-note {
    color: var(--ink-mute) !important;
    font-size: 0.8rem !important;
    font-style: italic;
    margin-top: 1rem !important;
  }
  .datebook-host {
    /* DateBook component owns its own internal layout grid. */
    margin: 0;
  }
</style>

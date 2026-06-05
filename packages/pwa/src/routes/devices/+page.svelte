<script lang="ts">
  /**
   * /devices — the new "my device" landing.
   *
   * Lists every Palm the signed-in user has registered (read from
   * public.devices via RLS) and offers an inline form to add another.
   * Tapping a card lands on /devices/[id] where the seven native-app
   * tabs live (Date Book / To Do List / Address / Memo Pad /
   * Note Pad / Mail / Expense).
   */
  import { onMount } from 'svelte';
  import { base } from '$app/paths';
  import { supabase } from '$lib/supabase';
  import { authState } from '$lib/auth.svelte';
  import { newUlid } from '$lib/ulid';

  interface Device {
    id: string;
    user_id: string;
    name: string;
    model: string;
    serial: string | null;
    last_sync_at: string | null;
    created_at: string;
  }

  // The 19 supported AAA-Palms, grouped. See docs/hardware-compatibility.md.
  const MODEL_GROUPS: Array<{ label: string; options: string[] }> = [
    {
      label: 'Palm Inc — Tier 1',
      options: [
        'Palm III',
        'Palm IIIe',
        'Palm IIIx',
        'Palm IIIxe',
        'Palm m100',
        'Palm m105',
        'Palm m125',
        'Palm Zire',
        'Palm Zire 21',
      ],
    },
    {
      label: 'Palm Inc — Stretch (pre-3.0)',
      options: [
        'Pilot 1000',
        'Pilot 5000',
        'PalmPilot Personal',
        'PalmPilot Professional',
      ],
    },
    {
      label: 'Handspring',
      options: [
        'Visor (original)',
        'Visor Solo',
        'Visor Deluxe',
        'Visor Platinum',
        'Visor Neo',
      ],
    },
    {
      label: 'Sony Clié',
      options: ['Sony PEG-SL10'],
    },
  ];

  let devices = $state<Device[]>([]);
  let loading = $state(true);
  let loadError = $state<string | null>(null);

  let showAddForm = $state(false);
  let addName = $state('');
  let addModel = $state('Palm IIIe');
  let addSerial = $state('');
  let addBusy = $state(false);
  let addError = $state<string | null>(null);

  async function loadDevices() {
    if (!authState.userId) return;
    loading = true;
    loadError = null;
    const { data, error } = await supabase
      .from('devices')
      .select('*')
      .order('created_at', { ascending: true });
    loading = false;
    if (error) {
      loadError = error.message;
      return;
    }
    devices = (data ?? []) as Device[];
  }

  async function submitAdd(e: Event) {
    e.preventDefault();
    if (!authState.userId) return;
    if (!addName.trim()) {
      addError = 'name required';
      return;
    }
    addBusy = true;
    addError = null;
    const { error } = await supabase.from('devices').insert({
      id: newUlid(),
      user_id: authState.userId,
      name: addName.trim(),
      model: addModel,
      serial: addSerial.trim() || null,
    });
    addBusy = false;
    if (error) {
      addError = error.message;
      return;
    }
    addName = '';
    addSerial = '';
    showAddForm = false;
    await loadDevices();
  }

  function fmtTime(s: string | null): string {
    if (!s) return 'never';
    const d = new Date(s);
    return d.toLocaleString();
  }

  $effect(() => {
    if (authState.phase === 'ready' && authState.userId) {
      void loadDevices();
    }
  });

  onMount(() => {
    if (authState.phase === 'ready') void loadDevices();
  });
</script>

<section class="device-list">
  <header class="head">
    <h1>my device</h1>
    <button class="add-btn" onclick={() => (showAddForm = !showAddForm)}>
      {showAddForm ? 'cancel' : '+ add palm'}
    </button>
  </header>

  {#if showAddForm}
    <form class="add-form" onsubmit={submitAdd}>
      <label>
        <span>name</span>
        <input bind:value={addName} placeholder="Office IIIe" required />
      </label>
      <label>
        <span>model</span>
        <select bind:value={addModel}>
          {#each MODEL_GROUPS as g (g.label)}
            <optgroup label={g.label}>
              {#each g.options as m (m)}
                <option value={m}>{m}</option>
              {/each}
            </optgroup>
          {/each}
        </select>
      </label>
      <label>
        <span>HotSync user / serial <span class="optional">(optional)</span></span>
        <input bind:value={addSerial} placeholder="leave blank if unknown" />
      </label>
      {#if addError}
        <p class="error">{addError}</p>
      {/if}
      <button type="submit" disabled={addBusy}>{addBusy ? 'adding…' : 'add'}</button>
    </form>
  {/if}

  {#if loading}
    <p class="loading">loading…</p>
  {:else if loadError}
    <p class="error">{loadError}</p>
  {:else if devices.length === 0}
    <p class="empty">
      no Palm registered yet. <button class="link-btn" onclick={() => (showAddForm = true)}
        >add your first Palm</button
      >
      to start syncing.
    </p>
  {:else}
    <ul class="cards">
      {#each devices as d (d.id)}
        <li>
          <a class="card" href="{base}/devices/{d.id}">
            <h2>{d.name}</h2>
            <p class="model">{d.model}</p>
            <dl>
              {#if d.serial}<dt>serial</dt>
                <dd>{d.serial}</dd>{/if}
              <dt>last sync</dt>
              <dd>{fmtTime(d.last_sync_at)}</dd>
            </dl>
          </a>
        </li>
      {/each}
    </ul>
  {/if}
</section>

<style>
  .device-list {
    max-width: 720px;
    margin: 0 auto;
  }
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 1.5rem;
  }
  h1 {
    margin: 0;
    font-size: 1.4rem;
    text-transform: lowercase;
    letter-spacing: 0.05em;
    color: var(--accent);
  }
  .add-btn {
    background: none;
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.4rem 0.8rem;
    font-family: inherit;
    font-size: 0.85rem;
    cursor: pointer;
  }
  .add-btn:hover {
    border-color: var(--accent);
    color: var(--accent);
  }
  .add-form {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 1rem 1.1rem;
    margin-bottom: 1.5rem;
    display: grid;
    gap: 0.7rem;
    border-radius: 2px;
  }
  .add-form label {
    display: grid;
    gap: 0.25rem;
    font-size: 0.85rem;
    color: var(--ink-mute);
  }
  .add-form input,
  .add-form select {
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.45rem 0.55rem;
    font-family: inherit;
    font-size: 0.9rem;
  }
  .add-form button {
    background: var(--accent);
    color: var(--bg);
    border: 1px solid var(--accent);
    padding: 0.5rem 1rem;
    font-family: inherit;
    font-weight: 600;
    cursor: pointer;
    justify-self: start;
  }
  .add-form button:disabled {
    opacity: 0.6;
  }
  .optional {
    color: var(--line);
  }
  .cards {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
    gap: 1rem;
  }
  .card {
    display: block;
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 1rem 1.1rem;
    color: var(--ink);
    border-bottom: 1px solid var(--line);
    border-radius: 2px;
    text-decoration: none;
  }
  .card:hover {
    border-color: var(--accent);
    background: var(--surface);
  }
  .card h2 {
    margin: 0 0 0.4rem;
    font-size: 1rem;
    color: var(--ink);
  }
  .model {
    margin: 0 0 0.6rem;
    color: var(--accent);
    font-size: 0.85rem;
  }
  dl {
    margin: 0;
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 0.2rem 0.6rem;
    font-size: 0.8rem;
    color: var(--ink-mute);
  }
  dt {
    color: var(--ink-mute);
  }
  dd {
    margin: 0;
    color: var(--ink-dim);
  }
  .empty,
  .loading,
  .error {
    color: var(--ink-mute);
    text-align: center;
    padding: 2rem 1rem;
  }
  .error {
    color: #ff6b6b;
  }
  .link-btn {
    background: none;
    border: none;
    color: var(--accent);
    padding: 0;
    font: inherit;
    cursor: pointer;
    text-decoration: underline;
  }
</style>

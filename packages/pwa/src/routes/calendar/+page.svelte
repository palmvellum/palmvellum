<script lang="ts">
  import { onMount } from 'svelte';
  import { base } from '$app/paths';
  import { goto } from '$app/navigation';

  import { supabase } from '$lib/supabase';
  import { authState } from '$lib/auth.svelte';
  import {
    type CalendarEvent,
    startOfMonth,
    startOfNextMonth,
    atMidnight,
    isoDow,
    monthGridDays,
    monthLabel,
    shortDayLabel,
    hhmm,
    ymd,
    sameDay,
    bucketByDay,
    localInputToISO,
    isoToLocalInput,
  } from '$lib/calendar';

  // ── Reactive state ─────────────────────────────────────
  let viewMonth = $state(startOfMonth(new Date()));
  let selectedDay = $state<Date>(atMidnight(new Date()));
  let events = $state<CalendarEvent[]>([]);
  let loading = $state(false);
  let cloudError = $state<string | null>(null);

  // Form state for both create + edit
  let editing = $state<CalendarEvent | null>(null);
  let showForm = $state(false);
  let formTitle = $state('');
  let formStart = $state('');
  let formEnd = $state('');
  let formAllDay = $state(false);
  let formLocation = $state('');
  let formNotes = $state('');
  let formAlarm = $state<string>('');
  let formSubmitting = $state(false);
  let formError = $state<string | null>(null);

  // Derived: events grouped by day for cell badges
  const byDay = $derived(bucketByDay(events));
  const grid = $derived(monthGridDays(viewMonth));
  const selectedDayKey = $derived(ymd(selectedDay));
  const selectedEvents = $derived(byDay.get(selectedDayKey) ?? []);

  // Today reference (re-evaluates if the page is open across midnight)
  let now = $state(new Date());
  onMount(() => {
    const t = setInterval(() => (now = new Date()), 60_000);
    return () => clearInterval(t);
  });

  function newUlid(): string {
    const ts = BigInt(Date.now());
    const ENC = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
    const tsB = ts.toString(2).padStart(48, '0');
    const rnd = crypto.getRandomValues(new Uint8Array(10));
    let rndB = '';
    for (const b of rnd) rndB += b.toString(2).padStart(8, '0');
    const bits = tsB + rndB;
    let out = '';
    for (let i = 0; i < 26; i++) {
      const slice = bits.slice(i * 5, i * 5 + 5).padEnd(5, '0');
      out += ENC[parseInt(slice, 2)];
    }
    return out;
  }

  // ── Auth guard ─────────────────────────────────────────
  $effect(() => {
    if (authState.phase === 'unauthenticated') {
      void goto(base + '/');
    }
  });

  // ── Load events for the visible month (+ a small buffer) ─
  async function loadEvents() {
    if (!authState.userId) return;
    loading = true;
    cloudError = null;
    const from = startOfMonth(viewMonth);
    const to = startOfNextMonth(viewMonth);
    // Pull a 1-week buffer either side so grid edges render correctly
    from.setDate(from.getDate() - 7);
    to.setDate(to.getDate() + 7);

    const { data, error } = await supabase
      .from('events')
      .select('*')
      .is('deleted_at', null)
      .gte('start_at', from.toISOString())
      .lt('start_at', to.toISOString())
      .order('start_at');
    if (error) {
      cloudError = error.message;
    } else if (data) {
      events = data as CalendarEvent[];
    }
    loading = false;
  }

  // Initial + on-month-change + on-auth-ready loads
  let channelHandle: ReturnType<typeof supabase.channel> | null = null;
  $effect(() => {
    if (authState.phase !== 'ready') return;
    void loadEvents();

    channelHandle?.unsubscribe();
    channelHandle = supabase
      .channel('events-feed')
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'events' },
        async () => {
          await loadEvents();
        },
      )
      .subscribe();

    return () => {
      channelHandle?.unsubscribe();
      channelHandle = null;
    };
  });

  // Trigger reload when viewMonth changes
  $effect(() => {
    // depend on viewMonth (and authState.phase as gate)
    void viewMonth;
    if (authState.phase === 'ready') void loadEvents();
  });

  // ── Navigation ─────────────────────────────────────────
  function prevMonth() {
    viewMonth = new Date(viewMonth.getFullYear(), viewMonth.getMonth() - 1, 1);
  }
  function nextMonth() {
    viewMonth = new Date(viewMonth.getFullYear(), viewMonth.getMonth() + 1, 1);
  }
  function gotoToday() {
    const today = new Date();
    viewMonth = startOfMonth(today);
    selectedDay = atMidnight(today);
  }
  function selectDay(d: Date) {
    selectedDay = atMidnight(d);
  }

  // ── Form: open / reset / submit / delete ───────────────
  function defaultStart(): string {
    // Default to the selected day at the next round hour
    const d = new Date(selectedDay);
    const h = new Date().getHours();
    d.setHours(h + 1, 0, 0, 0);
    return isoToLocalInput(d.toISOString());
  }
  function defaultEnd(start: string): string {
    if (!start) return '';
    const d = new Date(start);
    d.setHours(d.getHours() + 1);
    return isoToLocalInput(d.toISOString());
  }

  function openCreate() {
    editing = null;
    formTitle = '';
    formStart = defaultStart();
    formEnd = defaultEnd(formStart);
    formAllDay = false;
    formLocation = '';
    formNotes = '';
    formAlarm = '';
    formError = null;
    showForm = true;
  }

  function openEdit(e: CalendarEvent) {
    editing = e;
    formTitle = e.title;
    formStart = isoToLocalInput(e.start_at);
    formEnd = isoToLocalInput(e.end_at);
    formAllDay = e.all_day;
    formLocation = e.location ?? '';
    formNotes = e.notes ?? '';
    formAlarm = e.alarm_minutes != null ? String(e.alarm_minutes) : '';
    formError = null;
    showForm = true;
  }

  function closeForm() {
    showForm = false;
    editing = null;
  }

  async function submitForm(ev: Event) {
    ev.preventDefault();
    if (!authState.userId) return;
    if (!formTitle.trim() || !formStart) {
      formError = 'Title and start time are required.';
      return;
    }
    formError = null;
    formSubmitting = true;

    const alarm =
      formAlarm.trim() === '' ? null : Math.max(0, Number.parseInt(formAlarm, 10));
    const payload: Partial<CalendarEvent> = {
      title: formTitle.trim(),
      start_at: localInputToISO(formStart),
      end_at: formEnd ? localInputToISO(formEnd) : null,
      all_day: formAllDay,
      location: formLocation.trim() || null,
      notes: formNotes.trim() || null,
      alarm_minutes: alarm,
    };

    if (editing) {
      const { error } = await supabase
        .from('events')
        .update(payload)
        .eq('id', editing.id);
      formSubmitting = false;
      if (error) {
        formError = error.message;
        return;
      }
    } else {
      const { error } = await supabase.from('events').insert({
        id: newUlid(),
        user_id: authState.userId,
        source: 'web',
        ...payload,
      });
      formSubmitting = false;
      if (error) {
        formError = error.message;
        return;
      }
    }
    closeForm();
    await loadEvents();
  }

  async function deleteEvent(e: CalendarEvent) {
    if (!confirm(`Delete "${e.title}"?`)) return;
    const { error } = await supabase
      .from('events')
      .update({ deleted_at: new Date().toISOString() })
      .eq('id', e.id);
    if (error) {
      alert('Delete failed: ' + error.message);
      return;
    }
    if (editing?.id === e.id) closeForm();
    await loadEvents();
  }

  const DOW = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'] as const;
</script>

<svelte:head>
  <title>PalmVellum · calendar</title>
</svelte:head>

{#if authState.phase !== 'ready'}
  <p class="muted">loading…</p>
{:else}
  <header class="hdr">
    <div class="nav-l">
      <button type="button" class="step" onclick={prevMonth} aria-label="previous month">◄</button>
      <h1>{monthLabel(viewMonth)}</h1>
      <button type="button" class="step" onclick={nextMonth} aria-label="next month">►</button>
      <button type="button" class="today" onclick={gotoToday}>today</button>
    </div>
    <div class="nav-r">
      {#if loading}
        <span class="muted">syncing…</span>
      {/if}
      <button type="button" class="primary" onclick={openCreate}>+ event</button>
    </div>
  </header>

  {#if cloudError}
    <p class="error">{cloudError}</p>
  {/if}

  <!-- Month grid -->
  <div class="dow-row">
    {#each DOW as d}<span>{d}</span>{/each}
  </div>
  <div class="grid">
    {#each grid as d (d.toISOString())}
      {@const isCur = d.getMonth() === viewMonth.getMonth()}
      {@const isToday = sameDay(d, now)}
      {@const isSel = sameDay(d, selectedDay)}
      {@const dayEvents = byDay.get(ymd(d)) ?? []}
      <button
        type="button"
        class="cell {isCur ? '' : 'out'} {isToday ? 'today' : ''} {isSel ? 'sel' : ''}"
        onclick={() => selectDay(d)}
        aria-label={shortDayLabel(d)}
      >
        <span class="num">{d.getDate()}</span>
        {#if dayEvents.length > 0}
          <span class="dots">
            {#each dayEvents.slice(0, 3) as _e (_e.id)}<span class="dot"></span>{/each}
            {#if dayEvents.length > 3}<span class="more">+{dayEvents.length - 3}</span>{/if}
          </span>
        {/if}
      </button>
    {/each}
  </div>

  <!-- Selected day panel -->
  <section class="day-panel">
    <h2>{shortDayLabel(selectedDay)}</h2>
    {#if selectedEvents.length === 0}
      <p class="empty">Nothing scheduled. <button class="link" onclick={openCreate}>add an event ↑</button></p>
    {:else}
      <ul class="day-list">
        {#each selectedEvents as e (e.id)}
          <li class="ev">
            <div class="ev-time">
              {#if e.all_day}all-day{:else}{hhmm(e.start_at)}{#if e.end_at}–{hhmm(e.end_at)}{/if}{/if}
            </div>
            <div class="ev-body">
              <div class="ev-title">{e.title}</div>
              {#if e.location}<div class="ev-meta">📍 {e.location}</div>{/if}
              {#if e.notes}<div class="ev-meta notes">{e.notes}</div>{/if}
              {#if e.alarm_minutes != null}<div class="ev-meta">⏰ {e.alarm_minutes} min before</div>{/if}
            </div>
            <div class="ev-actions">
              <button type="button" class="link" onclick={() => openEdit(e)}>edit</button>
              <button type="button" class="link danger" onclick={() => deleteEvent(e)}>×</button>
            </div>
          </li>
        {/each}
      </ul>
    {/if}
  </section>

  <!-- Create / edit form -->
  {#if showForm}
    <section class="form-card">
      <header class="form-h">
        <h3>{editing ? 'edit event' : 'new event'}</h3>
        <button type="button" class="link" onclick={closeForm}>cancel</button>
      </header>
      <form onsubmit={submitForm}>
        <label>
          title
          <input type="text" bind:value={formTitle} required maxlength="256" placeholder="What is it?" />
        </label>
        <div class="grid-2">
          <label>
            start
            <input type="datetime-local" bind:value={formStart} required />
          </label>
          <label>
            end <span class="optional">(optional)</span>
            <input type="datetime-local" bind:value={formEnd} />
          </label>
        </div>
        <label class="check">
          <input type="checkbox" bind:checked={formAllDay} />
          all-day
        </label>
        <label>
          location <span class="optional">(optional)</span>
          <input type="text" bind:value={formLocation} maxlength="256" />
        </label>
        <label>
          notes <span class="optional">(optional)</span>
          <textarea bind:value={formNotes} rows="2"></textarea>
        </label>
        <label>
          alarm <span class="optional">(minutes before; blank = none)</span>
          <input type="number" min="0" max="10080" bind:value={formAlarm} />
        </label>
        {#if formError}<p class="error">{formError}</p>{/if}
        <div class="form-actions">
          {#if editing}
            <button type="button" class="link danger" onclick={() => deleteEvent(editing!)}>delete</button>
          {/if}
          <button type="submit" class="primary" disabled={formSubmitting}>
            {formSubmitting ? 'saving…' : editing ? 'save changes' : 'create event'}
          </button>
        </div>
      </form>
    </section>
  {/if}
{/if}

<style>
  .hdr {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 1rem;
    gap: 0.75rem;
    flex-wrap: wrap;
  }
  .nav-l,
  .nav-r {
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }
  h1 {
    font-size: 1.3rem;
    margin: 0;
    min-width: 11rem;
    text-align: center;
  }
  .step,
  .today,
  .primary {
    background: var(--surface);
    color: var(--ink);
    border: 1px solid var(--line);
    padding: 0.4rem 0.8rem;
    font-family: inherit;
    font-size: 0.9rem;
    cursor: pointer;
    border-radius: 2px;
  }
  .step {
    padding: 0.4rem 0.6rem;
  }
  .step:hover,
  .today:hover {
    border-color: var(--accent);
    color: var(--accent);
  }
  .primary {
    background: var(--accent);
    color: var(--bg);
    border-color: var(--accent);
    font-weight: 600;
  }
  .primary:hover:not(:disabled) {
    background: var(--accent-dim);
  }
  .primary:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  .muted {
    color: var(--ink-mute);
    font-family: 'VT323', monospace;
    font-size: 15px;
  }
  .error {
    color: #ff6b6b;
    font-size: 0.9rem;
    margin: 0.5rem 0;
  }

  .dow-row {
    display: grid;
    grid-template-columns: repeat(7, 1fr);
    text-align: center;
    color: var(--ink-mute);
    font-size: 0.75rem;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    padding: 0.3rem 0;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(7, 1fr);
    gap: 1px;
    background: var(--line-soft);
    border: 1px solid var(--line-soft);
  }
  .cell {
    background: var(--bg);
    border: none;
    aspect-ratio: 1.05;
    min-height: 56px;
    padding: 0.35rem 0.45rem;
    text-align: left;
    cursor: pointer;
    color: var(--ink);
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    font-family: inherit;
    font-size: 0.85rem;
  }
  .cell.out {
    color: var(--ink-mute);
    background: var(--surface-lo);
  }
  .cell:hover {
    background: var(--surface);
  }
  .cell.today {
    outline: 1px solid var(--accent);
    outline-offset: -1px;
  }
  .cell.sel {
    background: var(--surface);
    box-shadow: inset 0 0 0 2px var(--accent);
  }
  .num {
    font-weight: 600;
  }
  .dots {
    display: flex;
    gap: 3px;
    align-items: center;
  }
  .dot {
    width: 5px;
    height: 5px;
    background: var(--accent);
    border-radius: 50%;
  }
  .more {
    color: var(--accent-dim);
    font-size: 0.7rem;
    margin-left: 0.25rem;
  }

  .day-panel {
    margin-top: 1.25rem;
    border-top: 1px solid var(--line);
    padding-top: 1rem;
  }
  .day-panel h2 {
    font-size: 1rem;
    margin: 0 0 0.6rem;
    color: var(--accent);
  }
  .empty {
    color: var(--ink-mute);
  }
  .link {
    background: none;
    border: none;
    color: var(--accent);
    cursor: pointer;
    padding: 0;
    font-family: inherit;
    font-size: inherit;
    border-bottom: 1px dotted var(--accent-dim);
  }
  .link:hover {
    color: var(--accent-dim);
  }
  .link.danger {
    color: #ff6b6b;
    border-bottom-color: #ff6b6b;
  }
  .day-list {
    list-style: none;
    padding: 0;
    margin: 0;
    display: grid;
    gap: 0.5rem;
  }
  .ev {
    display: grid;
    grid-template-columns: 7rem 1fr auto;
    gap: 0.75rem;
    background: var(--surface-lo);
    border: 1px solid var(--line);
    border-left: 3px solid var(--accent);
    padding: 0.55rem 0.75rem;
  }
  .ev-time {
    color: var(--accent);
    font-size: 0.9rem;
    font-variant-numeric: tabular-nums;
  }
  .ev-title {
    font-weight: 600;
  }
  .ev-meta {
    color: var(--ink-mute);
    font-size: 0.85rem;
  }
  .ev-meta.notes {
    color: var(--ink-dim);
    white-space: pre-wrap;
  }
  .ev-actions {
    display: flex;
    gap: 0.5rem;
    align-items: center;
  }

  /* Form card */
  .form-card {
    margin-top: 1.25rem;
    background: var(--surface-lo);
    border: 1px solid var(--accent);
    padding: 1rem;
  }
  .form-h {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 0.75rem;
  }
  .form-h h3 {
    font-size: 0.95rem;
    color: var(--accent);
    margin: 0;
    text-transform: lowercase;
    letter-spacing: 0.05em;
  }
  .form-card form {
    display: grid;
    gap: 0.65rem;
  }
  .form-card label {
    display: grid;
    gap: 0.25rem;
    color: var(--ink-mute);
    font-size: 0.85rem;
  }
  .form-card label.check {
    flex-direction: row;
    display: flex;
    align-items: center;
    gap: 0.5rem;
    color: var(--ink);
  }
  .form-card input[type='text'],
  .form-card input[type='number'],
  .form-card input[type='datetime-local'],
  .form-card textarea {
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.45rem 0.6rem;
    font-family: inherit;
    font-size: 0.9rem;
  }
  .form-card .optional {
    color: var(--line);
  }
  .grid-2 {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0.65rem;
  }
  @media (max-width: 520px) {
    .grid-2 {
      grid-template-columns: 1fr;
    }
    .ev {
      grid-template-columns: 1fr;
    }
  }
  .form-actions {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: 0.5rem;
  }
  .form-actions button[type='submit'] {
    margin-left: auto;
  }
</style>

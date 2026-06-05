<script lang="ts">
  /**
   * <DateBook deviceId={...} />
   *
   * Per-device calendar view: month grid + AI free-form parser +
   * manual event form. All event reads/writes are scoped to the
   * supplied device via events.palm_device_id; AI-parsed events
   * also get the same palm_device_id when accepted from a draft.
   *
   * This is the same UI that used to live at /calendar — that
   * route now redirects here once the user picks a device.
   */
  import { onMount } from 'svelte';
  import { base } from '$app/paths';
  import { goto } from '$app/navigation';

  import { supabase } from '$lib/supabase';
  import { authState } from '$lib/auth.svelte';
  import { newUlid as sharedNewUlid } from '$lib/ulid';

  interface Props {
    deviceId: string;
  }
  let { deviceId }: Props = $props();
  import {
    type CalendarEvent,
    startOfMonth,
    startOfNextMonth,
    atMidnight,
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

  // ── Calendar state ─────────────────────────────────────
  let viewMonth = $state(startOfMonth(new Date()));
  let selectedDay = $state<Date>(atMidnight(new Date()));
  let events = $state<CalendarEvent[]>([]);
  let loading = $state(false);
  let cloudError = $state<string | null>(null);

  // Manual create / edit form
  let editing = $state<CalendarEvent | null>(null);
  let showManualForm = $state(false);
  let formTitle = $state('');
  let formStart = $state('');
  let formEnd = $state('');
  let formAllDay = $state(false);
  let formLocation = $state('');
  let formNotes = $state('');
  let formAlarm = $state<string>('');
  let formSubmitting = $state(false);
  let formError = $state<string | null>(null);

  // AI free-form
  interface ParsedDraftEvent {
    title: string;
    start_at: string;
    end_at: string | null;
    all_day: boolean;
    location: string | null;
    notes: string | null;
    alarm_minutes: number | null;
  }
  interface EventDraft {
    id: string;
    user_id: string;
    raw_input: string;
    user_tz: string;
    parsed_events: ParsedDraftEvent[];
    status: 'pending' | 'parsing' | 'parsed' | 'confirmed' | 'rejected' | 'error';
    ai_model: string | null;
    ai_tokens_in: number | null;
    ai_tokens_out: number | null;
    ai_error: string | null;
    created_at: string;
  }
  let aiInput = $state('');
  let aiSubmitting = $state(false);
  let aiError = $state<string | null>(null);
  let drafts = $state<EventDraft[]>([]);

  const byDay = $derived(bucketByDay(events));
  const grid = $derived(monthGridDays(viewMonth));
  const selectedDayKey = $derived(ymd(selectedDay));
  const selectedEvents = $derived(byDay.get(selectedDayKey) ?? []);

  let now = $state(new Date());
  onMount(() => {
    const t = setInterval(() => (now = new Date()), 60_000);
    return () => clearInterval(t);
  });

  const newUlid = sharedNewUlid;

  // ── Auth guard ─────────────────────────────────────────
  $effect(() => {
    if (authState.phase === 'unauthenticated') {
      void goto(base + '/');
    }
  });

  // ── Load events ────────────────────────────────────────
  async function loadEvents() {
    if (!authState.userId) return;
    loading = true;
    cloudError = null;
    const from = startOfMonth(viewMonth);
    const to = startOfNextMonth(viewMonth);
    from.setDate(from.getDate() - 7);
    to.setDate(to.getDate() + 7);

    const { data, error } = await supabase
      .from('events')
      .select('*')
      .eq('palm_device_id', deviceId)
      .is('deleted_at', null)
      .gte('start_at', from.toISOString())
      .lt('start_at', to.toISOString())
      .order('start_at');
    if (error) cloudError = error.message;
    else if (data) events = data as CalendarEvent[];
    loading = false;
  }

  // ── Load drafts (pending review) ───────────────────────
  async function loadDrafts() {
    if (!authState.userId) return;
    const { data, error } = await supabase
      .from('event_drafts')
      .select('*')
      .in('status', ['pending', 'parsing', 'parsed', 'error'])
      .order('created_at', { ascending: false })
      .limit(10);
    if (!error && data) drafts = data as EventDraft[];
  }

  // ── Live subscriptions ─────────────────────────────────
  let eventsChannel: ReturnType<typeof supabase.channel> | null = null;
  let draftsChannel: ReturnType<typeof supabase.channel> | null = null;

  $effect(() => {
    if (authState.phase !== 'ready') return;
    void loadEvents();
    void loadDrafts();

    eventsChannel?.unsubscribe();
    eventsChannel = supabase
      .channel('cal-events-' + deviceId)
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'events' },
        async (payload) => {
          // Only refresh when the change belongs to THIS device — the
          // realtime payload's `new` row carries palm_device_id.
          const row = (payload.new ?? payload.old) as { palm_device_id?: string } | undefined;
          if (row?.palm_device_id === deviceId) await loadEvents();
        },
      )
      .subscribe();

    draftsChannel?.unsubscribe();
    draftsChannel = supabase
      .channel('cal-drafts')
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'event_drafts' },
        async () => { await loadDrafts(); },
      )
      .subscribe();

    return () => {
      eventsChannel?.unsubscribe();
      draftsChannel?.unsubscribe();
      eventsChannel = null;
      draftsChannel = null;
    };
  });

  $effect(() => {
    void viewMonth;
    void deviceId;
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

  // ── Manual form ───────────────────────────────────────
  function defaultStart(): string {
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
    showManualForm = true;
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
    showManualForm = true;
  }

  function closeManualForm() {
    showManualForm = false;
    editing = null;
  }

  async function submitManual(ev: Event) {
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
    const payload = {
      title: formTitle.trim(),
      start_at: localInputToISO(formStart),
      end_at: formEnd ? localInputToISO(formEnd) : null,
      all_day: formAllDay,
      location: formLocation.trim() || null,
      notes: formNotes.trim() || null,
      alarm_minutes: alarm,
    };

    if (editing) {
      const { error } = await supabase.from('events').update(payload).eq('id', editing.id);
      formSubmitting = false;
      if (error) {
        formError = error.message;
        return;
      }
    } else {
      const { error } = await supabase.from('events').insert({
        id: newUlid(),
        user_id: authState.userId,
        palm_device_id: deviceId,
        source: 'web',
        ...payload,
      });
      formSubmitting = false;
      if (error) {
        formError = error.message;
        return;
      }
    }
    closeManualForm();
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
    if (editing?.id === e.id) closeManualForm();
    await loadEvents();
  }

  // ── AI submit + draft handling ─────────────────────────
  async function submitAI(ev: Event) {
    ev.preventDefault();
    if (!authState.userId) return;
    const raw = aiInput.trim();
    if (!raw) {
      aiError = 'Type something for the AI to parse.';
      return;
    }
    aiError = null;
    aiSubmitting = true;
    const tz =
      authState.settings?.timezone ||
      Intl.DateTimeFormat().resolvedOptions().timeZone ||
      'UTC';

    const { error } = await supabase.from('event_drafts').insert({
      id: newUlid(),
      user_id: authState.userId,
      raw_input: raw,
      user_tz: tz,
      status: 'pending',
    });
    aiSubmitting = false;
    if (error) {
      aiError = error.message;
      return;
    }
    aiInput = '';
    // Realtime will surface the draft as it parses.
  }

  async function acceptOneDraftEvent(draft: EventDraft, idx: number) {
    if (!authState.userId) return;
    const e = draft.parsed_events[idx];
    if (!e) return;
    const { error } = await supabase.from('events').insert({
      id: newUlid(),
      user_id: authState.userId,
      palm_device_id: deviceId,
      source: 'ai',
      title: e.title,
      start_at: e.start_at,
      end_at: e.end_at,
      all_day: e.all_day,
      location: e.location,
      notes: e.notes,
      alarm_minutes: e.alarm_minutes,
    });
    if (error) {
      alert(`Insert failed: ${error.message}`);
      return;
    }
    // Remove the accepted item from the draft's parsed_events list
    const remaining = draft.parsed_events.filter((_, i) => i !== idx);
    const newStatus = remaining.length === 0 ? 'confirmed' : 'parsed';
    const patch: { parsed_events: ParsedDraftEvent[]; status: string; confirmed_at?: string } = {
      parsed_events: remaining,
      status: newStatus,
    };
    if (newStatus === 'confirmed') patch.confirmed_at = new Date().toISOString();
    await supabase.from('event_drafts').update(patch).eq('id', draft.id);
    await loadDrafts();
    await loadEvents();
  }

  async function acceptAllDrafts(draft: EventDraft) {
    if (!authState.userId || draft.parsed_events.length === 0) return;
    const rows = draft.parsed_events.map((e) => ({
      id: newUlid(),
      user_id: authState.userId,
      palm_device_id: deviceId,
      source: 'ai',
      title: e.title,
      start_at: e.start_at,
      end_at: e.end_at,
      all_day: e.all_day,
      location: e.location,
      notes: e.notes,
      alarm_minutes: e.alarm_minutes,
    }));
    const { error } = await supabase.from('events').insert(rows);
    if (error) {
      alert(`Bulk insert failed: ${error.message}`);
      return;
    }
    await supabase
      .from('event_drafts')
      .update({
        parsed_events: [],
        status: 'confirmed',
        confirmed_at: new Date().toISOString(),
      })
      .eq('id', draft.id);
    await loadDrafts();
    await loadEvents();
  }

  async function rejectDraft(draft: EventDraft) {
    await supabase
      .from('event_drafts')
      .update({ status: 'rejected', parsed_events: [] })
      .eq('id', draft.id);
    await loadDrafts();
  }

  async function dismissOneDraftEvent(draft: EventDraft, idx: number) {
    const remaining = draft.parsed_events.filter((_, i) => i !== idx);
    const newStatus = remaining.length === 0 ? 'rejected' : 'parsed';
    await supabase
      .from('event_drafts')
      .update({ parsed_events: remaining, status: newStatus })
      .eq('id', draft.id);
    await loadDrafts();
  }

  function draftEventTimeLabel(e: ParsedDraftEvent): string {
    if (e.all_day) return 'all-day';
    const start = hhmm(e.start_at);
    if (!e.end_at) return start;
    return `${start}–${hhmm(e.end_at)}`;
  }

  function draftEventDateLabel(e: ParsedDraftEvent): string {
    return new Date(e.start_at).toLocaleDateString(undefined, {
      weekday: 'short',
      month: 'short',
      day: 'numeric',
    });
  }

  const DOW = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'] as const;
</script>

{#if authState.phase !== 'ready'}
  <p class="muted">loading…</p>
{:else}
  <div class="layout">
    <!-- LEFT: Calendar -->
    <section class="left">
      <header class="hdr">
        <div class="nav-l">
          <button type="button" class="step" onclick={prevMonth} aria-label="previous month">◄</button>
          <h1>{monthLabel(viewMonth)}</h1>
          <button type="button" class="step" onclick={nextMonth} aria-label="next month">►</button>
          <button type="button" class="today" onclick={gotoToday}>today</button>
        </div>
        <div class="nav-r">
          {#if loading}<span class="muted">syncing…</span>{/if}
        </div>
      </header>

      {#if cloudError}<p class="error">{cloudError}</p>{/if}

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

      <section class="day-panel">
        <h2>{shortDayLabel(selectedDay)}</h2>
        {#if selectedEvents.length === 0}
          <p class="empty">Nothing scheduled.</p>
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
    </section>

    <!-- RIGHT: AI panel + manual form -->
    <aside class="right">
      <section class="ai-card">
        <h2><span class="sparkle">✨</span> plan with AI</h2>
        <p class="sub">
          Paste anything — free-form notes, conversations, "next week: gym Mon Wed Fri 7am, dentist Thu 3pm" — and the AI extracts events you can review.
        </p>
        <form onsubmit={submitAI}>
          <textarea
            bind:value={aiInput}
            placeholder={'Try:\n"Coffee with May Friday 3pm at Tsim Sha Tsui"\n"Gym every Mon Wed Fri 7-8am next month"\n"Dentist appt next Thursday morning, 30min reminder"'}
            rows="7"
            maxlength="8000"
          ></textarea>
          <div class="ai-row">
            <span class="hint muted">{aiInput.length} / 8000</span>
            {#if aiError}<span class="error">{aiError}</span>{/if}
            <button type="submit" class="primary" disabled={aiSubmitting}>
              {aiSubmitting ? 'sending…' : 'analyze with AI'}
            </button>
          </div>
        </form>

        <!-- Drafts list -->
        {#if drafts.length > 0}
          <div class="drafts">
            <h3>recent drafts</h3>
            {#each drafts as draft (draft.id)}
              <article class="draft draft-{draft.status}">
                <header class="draft-h">
                  <span class="status-tag status-{draft.status}">{draft.status}</span>
                  <span class="muted">"{draft.raw_input.slice(0, 60)}{draft.raw_input.length > 60 ? '…' : ''}"</span>
                </header>

                {#if draft.status === 'pending' || draft.status === 'parsing'}
                  <p class="muted parsing">⟳ AI parsing…</p>
                {:else if draft.status === 'error'}
                  <p class="error">⚠ {draft.ai_error ?? 'parse failed'}</p>
                  <button type="button" class="link" onclick={() => rejectDraft(draft)}>dismiss</button>
                {:else if draft.parsed_events.length === 0}
                  <p class="muted">No events found in that input.</p>
                  <button type="button" class="link" onclick={() => rejectDraft(draft)}>dismiss</button>
                {:else}
                  <ul class="proposed">
                    {#each draft.parsed_events as pe, i (i)}
                      <li class="proposal">
                        <div class="prop-when">
                          <div>{draftEventDateLabel(pe)}</div>
                          <div class="prop-time">{draftEventTimeLabel(pe)}</div>
                        </div>
                        <div class="prop-body">
                          <div class="prop-title">{pe.title}</div>
                          {#if pe.location}<div class="muted small">📍 {pe.location}</div>{/if}
                          {#if pe.alarm_minutes != null}<div class="muted small">⏰ {pe.alarm_minutes} min</div>{/if}
                          {#if pe.notes}<div class="muted small notes">{pe.notes}</div>{/if}
                        </div>
                        <div class="prop-actions">
                          <button type="button" class="mini primary" onclick={() => acceptOneDraftEvent(draft, i)}>keep</button>
                          <button type="button" class="mini" onclick={() => dismissOneDraftEvent(draft, i)}>skip</button>
                        </div>
                      </li>
                    {/each}
                  </ul>
                  <div class="bulk-row">
                    <button type="button" class="primary" onclick={() => acceptAllDrafts(draft)}>
                      keep all {draft.parsed_events.length}
                    </button>
                    <button type="button" class="link danger" onclick={() => rejectDraft(draft)}>reject all</button>
                  </div>
                {/if}
              </article>
            {/each}
          </div>
        {/if}
      </section>

      <!-- Manual form trigger / inline form -->
      {#if !showManualForm}
        <button type="button" class="manual-trigger" onclick={openCreate}>
          + add event manually
        </button>
      {:else}
        <section class="form-card">
          <header class="form-h">
            <h3>{editing ? 'edit event' : 'new event'}</h3>
            <button type="button" class="link" onclick={closeManualForm}>cancel</button>
          </header>
          <form onsubmit={submitManual}>
            <label>
              title
              <input type="text" bind:value={formTitle} required maxlength="256" />
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
    </aside>
  </div>
{/if}

<style>
  .layout {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(320px, 380px);
    gap: 1.5rem;
    align-items: start;
  }
  @media (max-width: 920px) {
    .layout {
      grid-template-columns: 1fr;
    }
  }

  .left {
    min-width: 0;
  }
  .right {
    display: grid;
    gap: 1rem;
    position: sticky;
    top: 1rem;
  }
  @media (max-width: 920px) {
    .right {
      position: static;
    }
  }

  .hdr {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 0.75rem;
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
    font-size: 1.2rem;
    margin: 0;
    min-width: 10rem;
    text-align: center;
  }
  .step,
  .today {
    background: var(--surface);
    color: var(--ink);
    border: 1px solid var(--line);
    padding: 0.35rem 0.7rem;
    font-family: inherit;
    font-size: 0.85rem;
    cursor: pointer;
  }
  .step {
    padding: 0.35rem 0.55rem;
  }
  .step:hover,
  .today:hover {
    border-color: var(--accent);
    color: var(--accent);
  }
  .primary {
    background: var(--accent);
    color: var(--bg);
    border: 1px solid var(--accent);
    padding: 0.45rem 0.9rem;
    font-family: inherit;
    font-weight: 600;
    cursor: pointer;
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
  .small {
    font-size: 0.8rem;
  }
  .error {
    color: #ff6b6b;
    font-size: 0.85rem;
    margin: 0;
  }
  .hint {
    font-size: 0.75rem;
  }

  /* Month grid */
  .dow-row {
    display: grid;
    grid-template-columns: repeat(7, 1fr);
    text-align: center;
    color: var(--ink-mute);
    font-size: 0.7rem;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    padding: 0.25rem 0;
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
    aspect-ratio: 1.1;
    min-height: 50px;
    padding: 0.3rem 0.4rem;
    text-align: left;
    cursor: pointer;
    color: var(--ink);
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    font-family: inherit;
    font-size: 0.8rem;
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
    font-size: 0.65rem;
    margin-left: 0.25rem;
  }

  /* Day events */
  .day-panel {
    margin-top: 1rem;
    border-top: 1px solid var(--line);
    padding-top: 0.75rem;
  }
  .day-panel h2 {
    font-size: 0.95rem;
    margin: 0 0 0.5rem;
    color: var(--accent);
  }
  .empty {
    color: var(--ink-mute);
    font-size: 0.9rem;
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
    grid-template-columns: 6rem 1fr auto;
    gap: 0.75rem;
    background: var(--surface-lo);
    border: 1px solid var(--line);
    border-left: 3px solid var(--accent);
    padding: 0.5rem 0.75rem;
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

  /* Right column — AI card */
  .ai-card {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 1rem;
  }
  .ai-card h2 {
    font-size: 1rem;
    margin: 0 0 0.4rem;
    color: var(--accent);
    text-transform: lowercase;
    letter-spacing: 0.05em;
  }
  .ai-card h2 .sparkle {
    margin-right: 0.35rem;
  }
  .sub {
    color: var(--ink-mute);
    font-size: 0.85rem;
    margin: 0 0 0.6rem;
  }
  .ai-card form {
    display: grid;
    gap: 0.5rem;
  }
  .ai-card textarea {
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.6rem 0.7rem;
    font-family: inherit;
    font-size: 0.9rem;
    resize: vertical;
    min-height: 8rem;
    white-space: pre-wrap;
  }
  .ai-row {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    flex-wrap: wrap;
  }
  .ai-row .primary {
    margin-left: auto;
  }

  /* Drafts */
  .drafts {
    margin-top: 0.85rem;
    padding-top: 0.85rem;
    border-top: 1px dashed var(--line-soft);
    display: grid;
    gap: 0.6rem;
  }
  .drafts h3 {
    margin: 0;
    font-size: 0.8rem;
    color: var(--ink-mute);
    text-transform: uppercase;
    letter-spacing: 0.06em;
  }
  .draft {
    background: var(--bg);
    border: 1px solid var(--line);
    padding: 0.55rem 0.7rem;
  }
  .draft-h {
    display: flex;
    gap: 0.5rem;
    align-items: baseline;
    margin-bottom: 0.4rem;
    flex-wrap: wrap;
  }
  .status-tag {
    font-size: 0.65rem;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    padding: 0.05rem 0.4rem;
    background: var(--surface);
    color: var(--ink-mute);
  }
  .status-parsed { color: var(--accent); }
  .status-confirmed { color: var(--green); }
  .status-error { color: #ff6b6b; }
  .status-parsing,
  .status-pending { color: var(--ink-dim); }

  .parsing {
    color: var(--accent);
    animation: pulse 1.4s ease-in-out infinite;
    margin: 0.3rem 0;
  }
  @keyframes pulse {
    50% { opacity: 0.5; }
  }

  .proposed {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 0.4rem;
  }
  .proposal {
    display: grid;
    grid-template-columns: 5rem 1fr auto;
    gap: 0.5rem;
    background: var(--surface-lo);
    border: 1px solid var(--line);
    border-left: 2px solid var(--accent);
    padding: 0.45rem 0.55rem;
    align-items: start;
  }
  .prop-when {
    font-size: 0.8rem;
    color: var(--accent);
  }
  .prop-time {
    color: var(--ink-mute);
    font-size: 0.75rem;
  }
  .prop-title {
    font-weight: 600;
    font-size: 0.9rem;
  }
  .prop-actions {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
  }
  .mini {
    font-size: 0.75rem;
    padding: 0.25rem 0.55rem;
    background: var(--surface);
    color: var(--ink);
    border: 1px solid var(--line);
    cursor: pointer;
    font-family: inherit;
  }
  .mini.primary {
    background: var(--accent);
    color: var(--bg);
    border-color: var(--accent);
    font-weight: 600;
  }
  .mini:hover {
    border-color: var(--accent);
  }
  .bulk-row {
    margin-top: 0.5rem;
    display: flex;
    gap: 0.6rem;
    align-items: center;
  }

  /* Manual form trigger + form */
  .manual-trigger {
    background: var(--surface-lo);
    border: 1px dashed var(--line);
    color: var(--ink-mute);
    padding: 0.7rem;
    font-family: inherit;
    cursor: pointer;
  }
  .manual-trigger:hover {
    border-color: var(--accent);
    color: var(--accent);
  }
  .form-card {
    background: var(--surface-lo);
    border: 1px solid var(--accent);
    padding: 0.85rem;
  }
  .form-h {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 0.6rem;
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
    gap: 0.55rem;
  }
  .form-card label {
    display: grid;
    gap: 0.2rem;
    color: var(--ink-mute);
    font-size: 0.8rem;
  }
  .form-card label.check {
    flex-direction: row;
    display: flex;
    align-items: center;
    gap: 0.4rem;
    color: var(--ink);
  }
  .form-card input[type='text'],
  .form-card input[type='number'],
  .form-card input[type='datetime-local'],
  .form-card textarea {
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.4rem 0.55rem;
    font-family: inherit;
    font-size: 0.9rem;
  }
  .form-card .optional {
    color: var(--line);
  }
  .grid-2 {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0.55rem;
  }
  @media (max-width: 520px) {
    .grid-2 {
      grid-template-columns: 1fr;
    }
    .ev {
      grid-template-columns: 1fr;
    }
    .proposal {
      grid-template-columns: 1fr;
    }
    .prop-actions {
      flex-direction: row;
    }
  }
  .form-actions {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: 0.4rem;
  }
  .form-actions button[type='submit'] {
    margin-left: auto;
  }
</style>

<script lang="ts">
  /**
   * <DateBook />
   *
   * User-wide calendar view: month grid + AI free-form parser +
   * manual event form. Reads every non-deleted event belonging to
   * the signed-in user — there is no per-device partitioning. All
   * Palms registered to the user share this same dataset.
   */
  import { onMount } from 'svelte';
  import { base } from '$app/paths';
  import { goto } from '$app/navigation';

  import { supabase } from '$lib/supabase';
  import { authState } from '$lib/auth.svelte';
  import { newUlid as sharedNewUlid } from '$lib/ulid';
  import { t } from '$lib/i18n.svelte';
  import { palmConfirm } from '$lib/confirm.svelte';
  import { calsubs } from '$lib/stores/calsubs.svelte';
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

  // ── Load events + open to-dos with due dates ────────────
  async function loadEvents() {
    if (!authState.userId) return;
    loading = true;
    cloudError = null;
    const from = startOfMonth(viewMonth);
    const to = startOfNextMonth(viewMonth);
    from.setDate(from.getDate() - 7);
    to.setDate(to.getDate() + 7);

    const [evRes, todoRes] = await Promise.all([
      supabase
        .from('events')
        .select('*')
        .is('deleted_at', null)
        .gte('start_at', from.toISOString())
        .lt('start_at', to.toISOString())
        .order('start_at'),
      // Open to-dos with a due date in the visible window.
      supabase
        .from('records')
        .select('id, user_id, body, metadata, updated_at, deleted_at')
        .eq('type', 'todo')
        .is('deleted_at', null)
        .limit(500),
    ]);

    if (evRes.error) cloudError = evRes.error.message;
    const evs = (evRes.data ?? []) as CalendarEvent[];

    // Map to-do records with a due date into pseudo CalendarEvents.
    type TodoRow = {
      id: string;
      user_id: string;
      body: string | null;
      metadata: {
        palm_due_date?: string;
        palm_completed?: boolean;
        palm_priority?: number;
        palm_notes?: string;
      } | null;
      updated_at: string;
      deleted_at: string | null;
    };
    const fromMs = from.getTime();
    const toMs = to.getTime();
    const todoEvs: CalendarEvent[] = ((todoRes.data ?? []) as TodoRow[])
      .map((r) => {
        const md = r.metadata ?? {};
        const due = (md.palm_due_date ?? '').trim();
        if (!due || !/^\d{4}-\d{2}-\d{2}$/.test(due)) return null;
        // All-day at local midnight of the due date.
        const [y, m, d] = due.split('-').map(Number);
        const dt = new Date(y, m - 1, d, 0, 0, 0, 0);
        const ms = dt.getTime();
        if (ms < fromMs || ms >= toMs) return null;
        // BUG FIX (2026-06-07): completed to-dos were still rendering on the
        // calendar because we only set the todo_completed flag instead of
        // dropping the row. Drop them outright — they appear in the To Do
        // List 'done' tab, that is enough.
        if (md.palm_completed === true) return null;
        const ce: CalendarEvent = {
          id: `todo-${r.id}`,
          user_id: r.user_id,
          title: (r.body ?? '').trim() || '(untitled to-do)',
          start_at: dt.toISOString(),
          end_at: null,
          all_day: true,
          location: null,
          notes: md.palm_notes ?? null,
          alarm_minutes: null,
          repeat_rule: null,
          source: 'todo',
          deleted_at: null,
          updated_at: r.updated_at,
          kind: 'todo',
          todo_completed: false,
        };
        return ce;
      })
      .filter((x): x is CalendarEvent => x !== null);

    events = [...evs, ...todoEvs].sort(
      (a, b) => new Date(a.start_at).getTime() - new Date(b.start_at).getTime(),
    );
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
  let todosChannel: ReturnType<typeof supabase.channel> | null = null;

  $effect(() => {
    if (authState.phase !== 'ready') return;
    void loadEvents();
    void loadDrafts();
    // Best-effort pull of subscribed external calendars (Google iCal,
    // etc.), throttled by the user's interval; reload once it lands.
    void calsubs.autoRefresh().then(() => loadEvents());

    eventsChannel?.unsubscribe();
    eventsChannel = supabase
      .channel('cal-events')
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'events' },
        async () => { await loadEvents(); },
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

    // To-do records: filter to type=todo so we only reload when the
    // record kind we actually surface on the calendar changes. Catches
    // TodoList ticking a checkbox (UPDATE), new (AI)-triggered todos
    // (INSERT), and todo deletions (UPDATE deleted_at).
    todosChannel?.unsubscribe();
    todosChannel = supabase
      .channel('cal-todos')
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'records', filter: 'type=eq.todo' },
        async () => { await loadEvents(); },
      )
      .subscribe();

    return () => {
      eventsChannel?.unsubscribe();
      draftsChannel?.unsubscribe();
      todosChannel?.unsubscribe();
      eventsChannel = null;
      draftsChannel = null;
      todosChannel = null;
    };
  });

  $effect(() => {
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
    if (!(await palmConfirm(`Delete "${e.title}"?`, { danger: true }))) return;
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

  // ── iCal subscription feed ──────────────────────────────
  // One opaque, per-user token. Each click of "get .ics link" either
  // reuses the existing token (if already minted) or mints a new one,
  // then opens an inline panel with the URL + copy + Apple subscribe.
  const ICAL_FN_BASE = 'https://jrkwncplngmznfzzqwee.supabase.co/functions/v1/ical-feed';
  let icalOpen = $state(false);
  let icalBusy = $state(false);
  let icalCopied = $state(false);
  let icalError = $state<string | null>(null);

  function icalHttpsUrl(): string | null {
    const tok = authState.settings?.ical_token;
    return tok ? `${ICAL_FN_BASE}/${tok}.ics` : null;
  }
  function icalWebcalUrl(): string | null {
    const u = icalHttpsUrl();
    return u ? u.replace(/^https?:\/\//, 'webcal://') : null;
  }
  async function openIcalPanel() {
    icalError = null;
    icalCopied = false;
    // If user already has a token, just open the panel.
    if (authState.settings?.ical_token) {
      icalOpen = true;
      return;
    }
    // Otherwise mint one on demand.
    icalBusy = true;
    const { error } = await supabase.rpc('mint_ical_token');
    icalBusy = false;
    if (error) {
      icalError = error.message;
      return;
    }
    await authState.refreshSettings();
    icalOpen = true;
  }
  async function copyIcalUrl() {
    const u = icalHttpsUrl();
    if (!u) return;
    await navigator.clipboard.writeText(u);
    icalCopied = true;
    setTimeout(() => { icalCopied = false; }, 2000);
  }
  async function revokeIcal() {
    icalBusy = true;
    icalError = null;
    const { error } = await supabase.rpc('revoke_ical_token');
    icalBusy = false;
    if (error) {
      icalError = error.message;
      return;
    }
    await authState.refreshSettings();
    icalOpen = false;
  }
</script>

{#if authState.phase !== 'ready'}
  <p class="muted">loading…</p>
{:else}
  <div class="layout">
    <!-- 1. Today's / selected day events -->
    <section class="day-panel">
      <h2>{shortDayLabel(selectedDay)}{sameDay(selectedDay, now) ? ' · today' : ''}</h2>
      {#if cloudError}<p class="error">{cloudError}</p>{/if}
      {#if selectedEvents.length === 0}
        <p class="empty">Nothing scheduled.</p>
      {:else}
        <ul class="day-list">
          {#each selectedEvents as e (e.id)}
            <li class="ev {e.kind === 'todo' ? 'is-todo' : ''}">
              <div class="ev-time">
                {#if e.kind === 'todo'}
                  to-do
                {:else if e.all_day}
                  all-day
                {:else}
                  {hhmm(e.start_at)}{#if e.end_at}–{hhmm(e.end_at)}{/if}
                {/if}
              </div>
              <div class="ev-body">
                <div class="ev-title">{e.title}</div>
                {#if e.location}<div class="ev-meta">at {e.location}</div>{/if}
                {#if e.notes}<div class="ev-meta notes">{e.notes}</div>{/if}
                {#if e.alarm_minutes != null}<div class="ev-meta">alarm: {e.alarm_minutes} min before</div>{/if}
              </div>
              <div class="ev-actions">
                {#if e.kind === 'todo'}
                  <a class="link" href={base + '/palm?tab=todo'}>open</a>
                {:else}
                  <button type="button" class="link" onclick={() => openEdit(e)}>edit</button>
                  <button type="button" class="del" onclick={() => deleteEvent(e)}>{t('common.delete')}</button>
                {/if}
              </div>
            </li>
          {/each}
        </ul>
      {/if}
    </section>

    <!-- 2. Plan with AI -->
    <section class="right">
      <section class="ai-card">
        <h2>plan with AI</h2>
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
                  <p class="muted parsing">[...] AI parsing</p>
                {:else if draft.status === 'error'}
                  <p class="error">[err] {draft.ai_error ?? 'parse failed'}</p>
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
                          {#if pe.location}<div class="muted small">at {pe.location}</div>{/if}
                          {#if pe.alarm_minutes != null}<div class="muted small">alarm: {pe.alarm_minutes} min</div>{/if}
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
    </section>

    <!-- 3. Calendar (month grid) -->
    <section class="left">
      <header class="hdr">
        <div class="nav-l">
          <button type="button" class="step" onclick={prevMonth} aria-label="previous month">&lt;</button>
          <h1>{monthLabel(viewMonth)}</h1>
          <button type="button" class="step" onclick={nextMonth} aria-label="next month">&gt;</button>
          <button type="button" class="today" onclick={gotoToday}>today</button>
        </div>
        <div class="nav-r">
          {#if loading}<span class="muted">syncing…</span>{/if}
        </div>
      </header>

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
                {#each dayEvents.slice(0, 3) as _e (_e.id)}
                  <span class="dot {_e.kind === 'todo' ? 'dot-todo' : ''}"></span>
                {/each}
                {#if dayEvents.length > 3}<span class="more">+{dayEvents.length - 3}</span>{/if}
              </span>
            {/if}
          </button>
        {/each}
      </div>
    </section>

    <!-- 4. Add event manually + iCal subscription -->
    <section class="manual">
      {#if !showManualForm}
        <div class="manual-actions">
          <button type="button" class="manual-trigger" onclick={openCreate}>
            + add event manually
          </button>
          <button
            type="button"
            class="manual-trigger"
            onclick={openIcalPanel}
            disabled={icalBusy}
            title={t('datebook.icalGetLinkTitle')}
          >
            {icalBusy ? '...' : t('datebook.icalGetLink')}
          </button>
        </div>

        {#if icalOpen && authState.settings?.ical_token}
          <div class="ical-panel">
            <p class="ical-explain">{t('datebook.icalExplain')}</p>
            <div class="ical-url-box">
              <code>{icalHttpsUrl()}</code>
            </div>
            <div class="ical-row">
              <a class="ical-apple" href={icalWebcalUrl()} rel="noopener">
                {t('datebook.icalSubscribeApple')}
              </a>
              <button type="button" onclick={copyIcalUrl} disabled={icalBusy}>
                {icalCopied ? t('datebook.icalCopied') : t('datebook.icalCopy')}
              </button>
              <button type="button" class="ical-revoke" onclick={revokeIcal} disabled={icalBusy}>
                {t('datebook.icalRevoke')}
              </button>
              <button type="button" class="ical-close" onclick={() => (icalOpen = false)} aria-label="close">×</button>
            </div>
            {#if icalError}<p class="ical-err">{icalError}</p>{/if}
          </div>
        {/if}
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
                <button type="button" class="del" onclick={() => deleteEvent(editing!)}>{t('common.delete')}</button>
              {/if}
              <button type="submit" class="primary" disabled={formSubmitting}>
                {formSubmitting ? 'saving…' : editing ? 'save changes' : 'create event'}
              </button>
            </div>
          </form>
        </section>
      {/if}
    </section>
  </div>
{/if}

<style>
  .layout {
    display: flex;
    flex-direction: column; /* mobile: 1. today / 2. AI / 3. calendar / 4. manual */
    gap: 1.5rem;
  }

  .left,
  .right,
  .manual {
    min-width: 0;
  }
  .right {
    display: grid;
    gap: 1rem;
  }
  /* Desktop: today full-width, then AI + Calendar side-by-side, then
     manual full-width. The grid-area names match the DOM order so the
     flex column stays mobile-friendly while desktop rearranges into a
     two-column band. */
  @media (min-width: 720px) {
    .layout {
      display: grid;
      grid-template-columns: 1fr 1fr;
      grid-template-areas:
        'today today'
        'ai    calendar'
        'manual manual';
      gap: 1.25rem 1.5rem;
      align-items: start;
    }
    .day-panel { grid-area: today; }
    .right    { grid-area: ai; }
    .left     { grid-area: calendar; }
    .manual   { grid-area: manual; }
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
  .dot.dot-todo {
    background: var(--cat-todo, #f4d35e);
    border: 1px solid var(--ink-mute);
    width: 4px;
    height: 4px;
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
    border-left: 3px solid var(--cat-event);
    padding: 0.5rem 0.75rem;
  }
  .ev.is-todo {
    border-left-color: var(--cat-todo, #f4d35e);
  }
  .ev.is-todo .ev-time {
    color: var(--cat-todo, #f4d35e);
    text-transform: uppercase;
    letter-spacing: 0.05em;
    font-size: 0.72rem;
  }
  @media (max-width: 600px) {
    .ev {
      grid-template-columns: 1fr auto;
      gap: 0.35rem 0.6rem;
    }
    .ev-time {
      grid-column: 1 / -1;
      font-size: 0.85rem;
    }
    .ev-body {
      grid-column: 1;
    }
    .ev-actions {
      grid-column: 2;
      grid-row: 2;
      align-self: start;
    }
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
  @media (max-width: 600px) {
    .proposal {
      grid-template-columns: 1fr auto;
    }
    .prop-when {
      grid-column: 1 / -1;
      display: flex;
      gap: 0.5rem;
      align-items: baseline;
    }
    .prop-time {
      font-size: 0.78rem;
    }
    .prop-actions {
      flex-direction: row;
      align-self: end;
    }
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

  /* Unified Delete style — text "delete" in red outline → red fill on
     press. Matches every organizer. */
  .del {
    background: transparent;
    border: 1px solid #c62828;
    color: #c62828;
    padding: 0 0.95rem;
    font: inherit;
    font-size: 0.88rem;
    font-weight: 700;
    line-height: 1;
    min-height: 40px;
    border-radius: 4px;
    cursor: pointer;
    text-transform: lowercase;
    letter-spacing: 0.02em;
  }
  .del:hover,
  .del:active {
    background: #c62828;
    color: #fff;
  }

  /* iCal subscription inline panel (button itself reuses .manual-trigger) */
  .manual-actions {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0.5rem;
  }
  @media (max-width: 480px) {
    .manual-actions {
      grid-template-columns: 1fr;
    }
  }
  .manual-actions .manual-trigger:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
  .ical-panel {
    background: var(--surface-lo);
    border: 1px solid var(--accent);
    padding: 0.75rem 0.9rem;
    margin: 0 0 0.9rem;
    border-radius: 2px;
  }
  .ical-explain {
    color: var(--ink-dim);
    font-size: 0.82rem;
    margin: 0 0 0.5rem;
  }
  .ical-url-box {
    background: var(--bg);
    border: 1px solid var(--line);
    padding: 0.45rem 0.6rem;
    overflow-x: auto;
    margin-bottom: 0.6rem;
  }
  .ical-url-box code {
    background: transparent;
    color: var(--accent);
    font-family: inherit;
    font-size: 0.75rem;
    white-space: nowrap;
    user-select: all;
  }
  .ical-row {
    display: flex;
    flex-wrap: wrap;
    gap: 0.45rem;
    align-items: center;
  }
  .ical-row a.ical-apple {
    background: var(--accent);
    color: var(--bg);
    border: 1px solid var(--accent);
    padding: 0.4rem 0.75rem;
    font-family: inherit;
    font-weight: 600;
    text-decoration: none;
    font-size: 0.85rem;
  }
  .ical-row a.ical-apple:hover {
    background: var(--accent-dim);
  }
  .ical-row button {
    background: transparent;
    color: var(--ink);
    border: 1px solid var(--line);
    padding: 0.4rem 0.75rem;
    font-family: inherit;
    font-size: 0.85rem;
    cursor: pointer;
  }
  .ical-row button:hover:not(:disabled) {
    background: var(--surface-hi);
  }
  .ical-row button.ical-revoke {
    color: var(--ink-mute);
  }
  .ical-row button.ical-close {
    margin-left: auto;
    padding: 0.2rem 0.55rem;
    color: var(--ink-mute);
  }
  .ical-err {
    color: #ff6b6b;
    font-size: 0.8rem;
    margin: 0.4rem 0 0;
  }
</style>

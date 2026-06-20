<script lang="ts">
  /**
   * DateBookPalm — Palm OS-style date book with three view modes.
   *
   *   AGENDA (default) — list of upcoming events grouped by day. The
   *     screen opens on "today" and the user scrolls forward.
   *   WEEK — current 7-day grid (Mon..Sun) starting at the week
   *     containing the selected day.
   *   MONTH — month grid; tap a day to see its events under the grid.
   *
   * A bottom tab strip switches between the modes. Above the strip:
   *   - The "+ new event" button (always visible)
   *   - A "plan with AI" input (collapsed by default, expands on tap)
   *     that pipes natural-language text to the event_drafts table;
   *     the Edge Function parses it into structured events. Hidden
   *     when offline.
   */
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { base } from '$app/paths';
  import { authState } from '$lib/auth.svelte';
  import { newUlid } from '$lib/ulid';
  import { supabase } from '$lib/supabase';
  import { t } from '$lib/i18n.svelte';
  import { palmConfirm } from '$lib/confirm.svelte';
  import {
    type CalendarEvent,
    startOfMonth,
    startOfNextMonth,
    atMidnight,
    monthGridDays,
    monthLabel,
    hhmm,
    ymd,
    sameDay,
    bucketByDay,
    localInputToISO,
    isoToLocalInput,
    shortDayLabel,
  } from '$lib/calendar';
  import PalmList from './PalmList.svelte';
  import PalmCell from './PalmCell.svelte';
  import PalmEmpty from './PalmEmpty.svelte';
  import PalmButton from './PalmButton.svelte';
  import { listEvents, createEvent, updateEvent, deleteEvent } from '$lib/stores/events.svelte';
  import { listTodos } from '$lib/stores/todos.svelte';
  import { sync } from '$lib/sync.svelte';
  import { prefs } from '$lib/prefs.svelte';

  type Mode = 'agenda' | 'week' | 'month';

  // A row on the calendar is either a real event or a dated to-do
  // surfaced as a read-only, all-day pseudo-event. `kind === 'todo'`
  // marks the latter; tapping it jumps to the To Do List rather than
  // opening the event edit sheet.
  type Row = CalendarEvent & { kind?: 'todo'; todoId?: string };

  interface ParsedDraftEvent {
    title: string;
    start_at: string;
    end_at: string | null;
    all_day: boolean;
    location: string | null;
    notes: string | null;
    alarm_minutes: number | null;
  }

  const now = new Date();

  let mode = $state<Mode>('agenda');

  // Anchor date — Agenda uses it as the start of the list; Week uses it
  // as the week containing it; Month uses it as the month to render.
  let anchor = $state(atMidnight(now));
  let selectedDay = $state(atMidnight(now));

  let events = $state<Row[]>([]);
  let loading = $state(true);
  let loadErr = $state<string | null>(null);

  // ── AI input ───────────────────────────────────────────────
  let aiOpen = $state(false);
  let aiInput = $state('');
  let aiSubmitting = $state(false);
  let aiError = $state<string | null>(null);
  // Banner shown while the worker is parsing / after we auto-accept
  // the parsed events. The Palm UI doesn't have a draft-review screen,
  // so we accept anything the worker returns immediately and surface
  // the result inline instead.
  let aiStatus = $state<string | null>(null);
  let aiPendingDraftId = $state<string | null>(null);
  // Drafts we've already auto-accepted in this session — keeps the
  // realtime subscription idempotent across re-renders/re-deliveries.
  const acceptedDrafts = new Set<string>();

  // ── edit sheet ─────────────────────────────────────────────
  let sheetOpen = $state(false);
  let lightboxDay = $state<Date | null>(null);
  let editing = $state<CalendarEvent | null>(null);
  let fTitle = $state('');
  let fStart = $state('');
  let fEnd = $state('');
  let fAllDay = $state(false);
  let fLocation = $state('');
  let fNotes = $state('');
  let saving = $state(false);

  // Canonical Sunday-anchored arrays — we rotate them at render time
  // to respect the user's `prefs.weekStart` choice (0 = Sun, 1 = Mon).
  const DOW_LONG_SUN = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'] as const;
  const DOW_SHORT_SUN = ['S', 'M', 'T', 'W', 'T', 'F', 'S'] as const;
  const dowLong = $derived(
    prefs.weekStart === 0
      ? DOW_LONG_SUN
      : ([...DOW_LONG_SUN.slice(1), DOW_LONG_SUN[0]] as readonly string[]),
  );
  const dowShort = $derived(
    prefs.weekStart === 0
      ? DOW_SHORT_SUN
      : ([...DOW_SHORT_SUN.slice(1), DOW_SHORT_SUN[0]] as readonly string[]),
  );

  // Visible window depends on mode
  function visibleWindow(): { from: Date; to: Date } {
    if (mode === 'month') {
      const from = startOfMonth(anchor);
      const to = startOfNextMonth(anchor);
      from.setDate(from.getDate() - 7);
      to.setDate(to.getDate() + 7);
      return { from, to };
    }
    if (mode === 'week') {
      const dow = (anchor.getDay() - prefs.weekStart + 7) % 7;
      const from = new Date(anchor.getFullYear(), anchor.getMonth(), anchor.getDate() - dow);
      const to = new Date(from);
      to.setDate(to.getDate() + 7);
      return { from, to };
    }
    // agenda: from today, look 60 days forward
    const from = atMidnight(anchor);
    const to = new Date(from);
    to.setDate(to.getDate() + 60);
    return { from, to };
  }

  const grid = $derived(monthGridDays(anchor, prefs.weekStart));
  const byDay = $derived(bucketByDay(events));

  // Agenda groups — one entry per day with events, plus an explicit
  // entry for "today" when today has none (so the user always sees a
  // clear "no events today" line at the top instead of an empty list).
  const agendaGroups = $derived.by(() => {
    const win = visibleWindow();
    const fromMs = win.from.getTime();
    const toMs = win.to.getTime();
    const groups: { key: string; date: Date; events: Row[]; isToday: boolean }[] = [];
    const seen = new Map<string, { key: string; date: Date; events: Row[]; isToday: boolean }>();
    for (const e of events) {
      const ts = new Date(e.start_at).getTime();
      if (ts < fromMs || ts >= toMs) continue;
      const d = atMidnight(new Date(e.start_at));
      const k = ymd(d);
      let g = seen.get(k);
      if (!g) {
        g = { key: k, date: d, events: [], isToday: sameDay(d, now) };
        seen.set(k, g);
        groups.push(g);
      }
      g.events.push(e);
    }
    // Ensure today is always represented as the first group (placeholder
    // with no events if there isn't a real group for it).
    const today = atMidnight(now);
    if (today.getTime() >= fromMs && today.getTime() < toMs) {
      const k = ymd(today);
      if (!seen.has(k)) {
        groups.push({ key: k, date: today, events: [], isToday: true });
      }
    }
    groups.sort((a, b) => a.date.getTime() - b.date.getTime());
    for (const g of groups) g.events.sort((a, b) => a.start_at.localeCompare(b.start_at));
    return groups;
  });

  // Week days array — starts on the user-preferred first day.
  const weekDays = $derived.by(() => {
    const dow = (anchor.getDay() - prefs.weekStart + 7) % 7;
    const start = new Date(anchor.getFullYear(), anchor.getMonth(), anchor.getDate() - dow);
    return Array.from({ length: 7 }, (_, i) => {
      const d = new Date(start);
      d.setDate(start.getDate() + i);
      return d;
    });
  });

  async function load() {
    if (!authState.userId) return;
    loading = true;
    loadErr = null;
    try {
      try { await sync.pull(); } catch (_) { /* offline OK */ }
      const win = visibleWindow();
      const [evs, todos] = await Promise.all([
        listEvents({ from: win.from, to: win.to }),
        listTodos(),
      ]);

      // Surface open to-dos that carry a due date inside the visible
      // window as all-day pseudo-events, so they show up alongside real
      // events in every view. Completed ones are dropped — they live in
      // the To Do List 'done' tab, that is enough.
      const fromMs = win.from.getTime();
      const toMs = win.to.getTime();
      const todoEvs: Row[] = [];
      for (const r of todos) {
        const md = r.metadata as {
          palm_due_date?: string;
          palm_completed?: boolean;
          palm_notes?: string;
        };
        const due = (md.palm_due_date ?? '').trim();
        if (!due || !/^\d{4}-\d{2}-\d{2}$/.test(due)) continue;
        if (md.palm_completed === true) continue;
        const parts = due.split('-').map(Number);
        const y = parts[0] ?? 0;
        const mo = parts[1] ?? 1;
        const d = parts[2] ?? 1;
        const dt = new Date(y, mo - 1, d, 0, 0, 0, 0);
        const ms = dt.getTime();
        if (ms < fromMs || ms >= toMs) continue;
        todoEvs.push({
          id: `todo-${r.id}`,
          user_id: r.user_id,
          title: (r.body ?? '').trim() || t('datebook.todoUntitled'),
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
          todoId: r.id,
        });
      }
      events = [...evs, ...todoEvs];
    } catch (e) {
      loadErr = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  $effect(() => {
    if (authState.phase === 'ready') void load();
  });
  $effect(() => {
    void sync.last_pulled_at;
    if (authState.phase === 'ready') void load();
  });
  $effect(() => {
    // Re-load when mode / anchor changes
    void mode;
    void anchor;
    if (authState.phase === 'ready') void load();
  });

  onMount(() => { void load(); });

  // Realtime subscription to event_drafts so we can auto-accept any
  // draft the worker finishes parsing. Without this, AI submissions
  // sat in a "pending" row forever and the user never saw the events.
  $effect(() => {
    if (authState.phase !== 'ready') return;
    if (!authState.userId) return;
    const ch = supabase
      .channel(`palm-drafts-${authState.userId}`)
      .on(
        'postgres_changes',
        {
          event: '*',
          schema: 'public',
          table: 'event_drafts',
          filter: `user_id=eq.${authState.userId}`,
        },
        (payload) => {
          const row = payload.new as {
            id: string;
            status: string | null;
            parsed_events: ParsedDraftEvent[] | null;
            error?: string | null;
          } | null;
          if (!row) return;
          void autoAcceptDraft(row);
        },
      )
      .subscribe();
    return () => { void ch.unsubscribe(); };
  });

  // On (re)mount, scan recent drafts already in 'parsed' state and
  // accept any that finished while the page was elsewhere.
  $effect(() => {
    if (authState.phase !== 'ready') return;
    if (!authState.userId) return;
    void (async () => {
      const { data } = await supabase
        .from('event_drafts')
        .select('id,status,parsed_events,error')
        .eq('user_id', authState.userId!)
        .in('status', ['parsed', 'error'])
        .order('created_at', { ascending: false })
        .limit(10);
      for (const d of data ?? []) {
        await autoAcceptDraft(d as Parameters<typeof autoAcceptDraft>[0]);
      }
    })();
  });

  function setMode(m: Mode) { mode = m; }

  /** Month-grid tap handler — opens a lightbox showing that day's
   *  schedule over a dimmed calendar (instead of switching to Agenda). */
  function onMonthCellClick(d: Date): void {
    selectedDay = atMidnight(d);
    lightboxDay = atMidnight(d);
  }
  function closeLightbox(): void { lightboxDay = null; }

  /** Row tap: real events open the edit sheet; a dated to-do jumps to
   *  the To Do List, where it is actually editable. */
  function openRow(e: Row): void {
    if (e.kind === 'todo') {
      void goto(base + '/palm/todo');
      return;
    }
    openEdit(e);
  }

  function prevPeriod() {
    if (mode === 'month') {
      anchor = new Date(anchor.getFullYear(), anchor.getMonth() - 1, 1);
    } else if (mode === 'week') {
      anchor = new Date(anchor.getFullYear(), anchor.getMonth(), anchor.getDate() - 7);
    } else {
      anchor = new Date(anchor.getFullYear(), anchor.getMonth(), anchor.getDate() - 14);
    }
  }
  function nextPeriod() {
    if (mode === 'month') {
      anchor = new Date(anchor.getFullYear(), anchor.getMonth() + 1, 1);
    } else if (mode === 'week') {
      anchor = new Date(anchor.getFullYear(), anchor.getMonth(), anchor.getDate() + 7);
    } else {
      anchor = new Date(anchor.getFullYear(), anchor.getMonth(), anchor.getDate() + 14);
    }
  }
  function gotoToday() {
    anchor = atMidnight(now);
    selectedDay = atMidnight(now);
  }
  function selectDay(d: Date) {
    selectedDay = atMidnight(d);
    if (mode === 'month') return;
    if (mode === 'week') return;
  }

  function periodLabel(): string {
    if (mode === 'month') return monthLabel(anchor);
    if (mode === 'week') {
      const start = weekDays[0]!;
      const end = weekDays[6]!;
      return start.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
        + ' – ' +
        end.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
    }
    return anchor.toLocaleDateString(undefined, { month: 'long', day: 'numeric', weekday: 'short' });
  }

  // ── AI plan ───────────────────────────────────────────────
  async function submitAI(ev: Event) {
    ev.preventDefault();
    if (!authState.userId || !aiInput.trim()) return;
    aiError = null;
    aiStatus = null;
    aiSubmitting = true;
    try {
      const draftId = newUlid();
      const userTz = Intl.DateTimeFormat().resolvedOptions().timeZone;
      const { error } = await supabase.from('event_drafts').insert({
        id: draftId,
        user_id: authState.userId,
        raw_input: aiInput.trim(),
        user_tz: userTz,
        status: 'pending',
      });
      if (error) { aiError = error.message; return; }
      aiPendingDraftId = draftId;
      aiStatus = t('datebook.aiAnalyzing');
      aiInput = '';
      aiOpen = false;
    } catch (e) {
      aiError = e instanceof Error ? e.message : String(e);
    } finally {
      aiSubmitting = false;
    }
  }

  // Accept a parsed draft inline: insert all parsed_events into
  // the events table and mark the draft confirmed. The Palm UI
  // skips the manual review step.
  async function autoAcceptDraft(draft: {
    id: string;
    status: string | null;
    parsed_events: ParsedDraftEvent[] | null;
    error?: string | null;
  }) {
    if (acceptedDrafts.has(draft.id)) return;
    if (!authState.userId) return;
    if (draft.status === 'error') {
      acceptedDrafts.add(draft.id);
      aiPendingDraftId = null;
      aiError = draft.error ?? 'AI parse failed';
      aiStatus = null;
      return;
    }
    if (draft.status !== 'parsed') return;
    const list = draft.parsed_events ?? [];
    if (list.length === 0) {
      acceptedDrafts.add(draft.id);
      aiPendingDraftId = null;
      aiStatus = t('datebook.aiNoEvents');
      setTimeout(() => { if (aiStatus === t('datebook.aiNoEvents')) aiStatus = null; }, 3500);
      return;
    }
    acceptedDrafts.add(draft.id);
    const rows = list.map((e) => ({
      id: newUlid(),
      user_id: authState.userId!,
      source: 'ai',
      title: e.title,
      start_at: e.start_at,
      end_at: e.end_at,
      all_day: e.all_day,
      location: e.location,
      notes: e.notes,
      alarm_minutes: e.alarm_minutes,
    }));
    const insertRes = await supabase.from('events').insert(rows);
    if (insertRes.error) {
      aiError = insertRes.error.message;
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
    aiPendingDraftId = null;
    // Jump the agenda to the first parsed event so the user can see it
    // landed somewhere. Without this they only see the success toast
    // disappear and assume nothing happened (the event is on a future
    // date scrolled off-screen).
    const first = list[0];
    const firstDate = first ? new Date(first.start_at) : null;
    if (firstDate && !isNaN(firstDate.getTime())) {
      mode = 'agenda';
      anchor = atMidnight(firstDate);
      selectedDay = atMidnight(firstDate);
    }
    const dateLabel = firstDate && !isNaN(firstDate.getTime())
      ? firstDate.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' })
      : '';
    const msg =
      rows.length === 1 && first
        ? t('datebook.aiAddedOne', { title: first.title, date: dateLabel })
        : t('datebook.aiAdded', { count: String(rows.length) });
    aiStatus = msg;
    setTimeout(() => { if (aiStatus === msg) aiStatus = null; }, 8000);
    await load();
  }

  // ── edit sheet ───────────────────────────────────────────
  function openCreate(forDay?: Date) {
    editing = null;
    fTitle = '';
    const base = forDay ? new Date(forDay) : new Date();
    if (forDay) { base.setHours(9, 0, 0, 0); }
    fStart = isoToLocalInput(base.toISOString());
    fEnd = '';
    fAllDay = false;
    fLocation = '';
    fNotes = '';
    sheetOpen = true;
  }
  function openEdit(e: CalendarEvent) {
    editing = e;
    fTitle = e.title;
    fStart = isoToLocalInput(e.start_at);
    fEnd = isoToLocalInput(e.end_at);
    fAllDay = e.all_day;
    fLocation = e.location ?? '';
    fNotes = e.notes ?? '';
    sheetOpen = true;
  }
  function closeSheet() { sheetOpen = false; editing = null; }
  async function saveSheet(ev: Event) {
    ev.preventDefault();
    if (!authState.userId || !fTitle.trim() || !fStart) return;
    saving = true;
    try {
      const startIso = localInputToISO(fStart);
      const endIso = fEnd ? localInputToISO(fEnd) : null;
      const payload: Partial<CalendarEvent> = {
        title: fTitle.trim(),
        start_at: startIso,
        // Drop an end that is empty, on an all-day event, or before the
        // start — the events table enforces end_at >= start_at, and a
        // rejected save would leave this sheet stuck open.
        end_at: !fAllDay && endIso && endIso >= startIso ? endIso : null,
        all_day: fAllDay,
        location: fLocation.trim() || null,
        notes: fNotes.trim() || null,
      };
      if (editing) {
        await updateEvent(editing.id, payload);
      } else {
        await createEvent({
          title: payload.title!,
          start_at: payload.start_at!,
          end_at: payload.end_at ?? null,
          all_day: !!payload.all_day,
          location: payload.location ?? null,
          notes: payload.notes ?? null,
        });
      }
      closeSheet();
      await load();
    } catch (e) {
      loadErr = e instanceof Error ? e.message : String(e);
    } finally {
      saving = false;
    }
  }
  async function removeEvent(e: CalendarEvent) {
    if (!(await palmConfirm(t('datebook.confirmDelete', { title: e.title }), { danger: true }))) return;
    await deleteEvent(e.id);
    await load();
  }
</script>

<div class="db">
  <!-- Header rows: navigation, then today + mode toggle group -->
  <header class="ctrl">
    <button type="button" class="nav" onclick={prevPeriod} aria-label="‹">‹</button>
    <div class="title">{periodLabel()}</div>
    <button type="button" class="nav" onclick={nextPeriod} aria-label="›">›</button>
  </header>
  <div class="modes">
    <button type="button" class="pill" onclick={gotoToday}>{t('common.today')}</button>
    <span class="pills">
      <button
        type="button" class="pill" class:on={mode === 'agenda'}
        onclick={() => setMode('agenda')}>{t('datebook.mode.agenda')}</button>
      <button
        type="button" class="pill" class:on={mode === 'week'}
        onclick={() => setMode('week')}>{t('datebook.mode.week')}</button>
      <button
        type="button" class="pill" class:on={mode === 'month'}
        onclick={() => setMode('month')}>{t('datebook.mode.month')}</button>
    </span>
    <button
      type="button" class="pill pill-add"
      onclick={() => openCreate(mode === 'month' ? selectedDay : undefined)}
    >{t('datebook.addNew')}</button>
  </div>

  {#if loadErr}<p class="err">{loadErr}</p>{/if}

  <!-- AI processing banner — shown after submit and once parsed -->
  {#if aiStatus || aiError || aiPendingDraftId}
    <div class="ai-banner" class:err={aiError}>
      {#if aiError}{aiError}
      {:else if aiPendingDraftId}{t('datebook.aiAnalyzing')}
      {:else if aiStatus}{aiStatus}{/if}
    </div>
  {/if}

  <!-- AI input — collapsed by default, hidden when offline -->
  {#if sync.online}
    <section class="ai-card">
      {#if !aiOpen}
        <button type="button" class="ai-open" onclick={() => (aiOpen = true)}>
          {t('datebook.aiOpen')}
        </button>
      {:else}
        <form onsubmit={submitAI}>
          <p class="ai-hint">{t('datebook.aiHint')}</p>
          <textarea
            bind:value={aiInput}
            rows="3"
            placeholder={t('datebook.aiPlaceholder')}
            required
          ></textarea>
          {#if aiError}<p class="err">{aiError}</p>{/if}
          <div class="ai-actions">
            <button type="button" class="ai-cancel" onclick={() => { aiOpen = false; aiInput = ''; }}>{t('datebook.aiCancel')}</button>
            <button type="submit" disabled={aiSubmitting || !aiInput.trim()} class="ai-go">
              {aiSubmitting ? t('datebook.aiSending') : t('datebook.aiAnalyse')}
            </button>
          </div>
        </form>
      {/if}
    </section>
  {/if}

  <!-- AGENDA view -->
  {#if mode === 'agenda'}
    {#if loading && agendaGroups.length === 0}
      <PalmEmpty title={t('common.loading')} />
    {:else if agendaGroups.length === 0}
      <PalmEmpty title={t('datebook.noEventsInRange')} hint={t('datebook.tapNewHint')} />
    {:else}
      {#each agendaGroups as g (g.key)}
        {@const labelDate = g.date.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'short' })}
        <PalmList label={g.isToday ? t('datebook.todayLabel') + ' · ' + labelDate : labelDate}>
          {#if g.events.length === 0}
            <div class="empty-row">{t('datebook.todayNoEvents')}</div>
          {:else}
            {#each g.events as e (e.id)}
              <PalmCell
                leading={e.all_day ? '●' : '·'}
                title={e.title}
                meta={e.kind === 'todo'
                  ? t('datebook.todoTag')
                  : e.all_day
                    ? t('datebook.allDay')
                    : (e.end_at ? hhmm(e.start_at) + '–' + hhmm(e.end_at) : hhmm(e.start_at))}
                metaAccent
                onclick={() => openRow(e)}
              >
                {#if e.location}{t('datebook.atLocation', { location: e.location })}{/if}
                {#if e.notes && !e.location}{e.notes}{/if}
              </PalmCell>
            {/each}
          {/if}
        </PalmList>
      {/each}
    {/if}
  {/if}

  <!-- WEEK view — weekday name centred + enlarged on its own line,
       short date slid below it on the left in a smaller weight. -->
  {#if mode === 'week'}
    <div class="week-grid">
      {#each weekDays as d (d.toISOString())}
        {@const evs = byDay.get(ymd(d)) ?? []}
        {@const isToday = sameDay(d, now)}
        {@const dayIdx = d.getDay()}
        <div
          class="wday"
          class:today={isToday}
          class:sun={dayIdx === 0}
          class:sat={dayIdx === 6}
        >
          <div class="wday-h">
            <div class="wday-name">{dowLong[(dayIdx - prefs.weekStart + 7) % 7]}</div>
            <div class="wday-date">
              {d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })}
            </div>
          </div>
          {#if evs.length === 0}
            <p class="muted">—</p>
          {:else}
            <ul class="wev">
              {#each evs as e (e.id)}
                <li>
                  <button type="button" class="wev-row" onclick={() => openRow(e)}>
                    <span class="t">
                      {e.kind === 'todo'
                        ? t('datebook.todoTag')
                        : e.all_day
                          ? t('datebook.allDay')
                          : (e.end_at ? hhmm(e.start_at) + '–' + hhmm(e.end_at) : hhmm(e.start_at))}
                    </span>
                    <span class="ti">{e.title}</span>
                  </button>
                </li>
              {/each}
            </ul>
          {/if}
        </div>
      {/each}
    </div>
  {/if}

  <!-- MONTH view — column tint reflects the actual calendar day, so
       Sun stays pink and Sat stays grey regardless of weekStart. -->
  {#if mode === 'month'}
    <div class="dow">
      {#each dowShort as d, i (i)}<span>{d}</span>{/each}
    </div>
    <div class="grid">
      {#each grid as d (d.toISOString())}
        {@const isCur = d.getMonth() === anchor.getMonth()}
        {@const isToday = sameDay(d, now)}
        {@const isSel = sameDay(d, selectedDay)}
        {@const dayRows = byDay.get(ymd(d)) ?? []}
        {@const evs = dayRows.filter((r) => r.kind !== 'todo')}
        {@const todoCount = dayRows.length - evs.length}
        {@const dayIdx = d.getDay()}
        <button
          type="button" class="cell"
          class:out={!isCur} class:today={isToday} class:sel={isSel}
          class:sun={dayIdx === 0} class:sat={dayIdx === 6}
          onclick={() => onMonthCellClick(d)}
        >
          <span class="n">{d.getDate()}</span>
          {#if evs.length > 0 || todoCount > 0}
            <span class="ev-dots">
              {#each evs as _ev, i (i)}
                <span class="ev-dot" aria-hidden="true"></span>
              {/each}
              {#if todoCount > 0}
                <span class="td-num">+{todoCount}</span>
              {/if}
            </span>
          {/if}
        </button>
      {/each}
    </div>
    <p class="cal-hint">{t('datebook.tapNewDayHint')}</p>
  {/if}

</div>

<!-- Day lightbox (month mode): that day's schedule over a dimmed calendar -->
{#if lightboxDay}
  {@const lbRows = byDay.get(ymd(lightboxDay)) ?? []}
  <div class="lb-backdrop" onclick={closeLightbox} role="presentation"></div>
  <div class="lb" role="dialog" aria-modal="true">
    <div class="lb-head">
      <span class="lb-title">{shortDayLabel(lightboxDay)}</span>
      <button type="button" class="lb-close" onclick={closeLightbox} aria-label="×">×</button>
    </div>
    {#if lbRows.length === 0}
      <PalmEmpty title={t('datebook.nothingScheduled')} hint={t('datebook.tapNewDayHint')} />
    {:else}
      <PalmList>
        {#each lbRows as e (e.id)}
          <PalmCell
            leading={e.all_day ? '●' : '·'}
            title={e.title}
            meta={e.kind === 'todo'
              ? t('datebook.todoTag')
              : e.all_day
                ? t('datebook.allDay')
                : (e.end_at ? hhmm(e.start_at) + '–' + hhmm(e.end_at) : hhmm(e.start_at))}
            metaAccent
            onclick={() => { const d = lightboxDay; closeLightbox(); if (d) openRow(e); }}
          >
            {#if e.location}{t('datebook.atLocation', { location: e.location })}{/if}
            {#if e.notes && !e.location}{e.notes}{/if}
          </PalmCell>
        {/each}
      </PalmList>
    {/if}
    <div class="lb-actions">
      <PalmButton variant="primary" onclick={() => { const d = lightboxDay; closeLightbox(); openCreate(d ?? undefined); }}>
        {t('datebook.newEvent')}
      </PalmButton>
    </div>
  </div>
{/if}

{#if sheetOpen}
  <div class="sheet-backdrop" onclick={closeSheet} role="presentation"></div>
  <div class="sheet" role="dialog" aria-modal="true">
    <div class="sheet-head">
      <h3>{editing ? t('datebook.sheetEditEvent') : t('datebook.sheetNewEvent')}</h3>
      <button type="button" class="sheet-close" onclick={closeSheet} aria-label="×">×</button>
    </div>
    <form onsubmit={saveSheet}>
      <label>{t('datebook.fldTitle')}<input type="text" bind:value={fTitle} required maxlength={256} /></label>
      <label class="inline"><input type="checkbox" bind:checked={fAllDay} />{t('datebook.allDay')}</label>
      <label>{t('datebook.fldStart')}<input type="datetime-local" bind:value={fStart} required /></label>
      <label>{t('datebook.fldEnd')}<input type="datetime-local" bind:value={fEnd} /></label>
      <label>{t('datebook.fldLocation')}<input type="text" bind:value={fLocation} maxlength={120} /></label>
      <label>{t('datebook.fldNotes')}<textarea bind:value={fNotes} rows="3"></textarea></label>
      <div class="sheet-actions">
        {#if editing}
          <PalmButton variant="ghost" onclick={() => editing && removeEvent(editing)}>{t('datebook.delete')}</PalmButton>
        {/if}
        <span class="grow"></span>
        <PalmButton variant="ghost" onclick={closeSheet}>{t('datebook.cancel')}</PalmButton>
        <PalmButton type="submit" disabled={saving || !fTitle.trim()}>
          {saving ? t('datebook.saving') : t('datebook.save')}
        </PalmButton>
      </div>
    </form>
  </div>
{/if}

<style>
  .db {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }
  .ctrl {
    display: flex;
    align-items: center;
    gap: 0.4rem;
    padding: 0 0.25rem;
  }
  .ctrl .title {
    flex: 1;
    text-align: center;
    font-weight: 700;
    font-size: 0.95rem;
    color: var(--ink);
  }
  .ctrl .nav {
    background: var(--surface-hi);
    border: 1px solid var(--line);
    color: var(--ink);
    font-size: 1.2rem;
    line-height: 1;
    padding: 0.2rem 0.55rem;
    cursor: pointer;
    min-height: 34px;
    border-radius: 3px;
  }
  /* Today + agenda/week/month row — small pill buttons aligned right. */
  .modes {
    display: flex;
    align-items: center;
    gap: 0.4rem;
    padding: 0 0.25rem;
    justify-content: flex-end;
    flex-wrap: wrap;
    row-gap: 0.3rem;
  }
  .modes .pills {
    display: inline-flex;
    border: 1px solid var(--line);
    border-radius: 3px;
    overflow: hidden;
  }
  .modes .pill {
    background: var(--surface-hi);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.22rem 0.65rem;
    font-size: 0.78rem;
    font-weight: 600;
    cursor: pointer;
    border-radius: 3px;
    min-height: 30px;
    line-height: 1.1;
  }
  .modes .pills .pill {
    border: 0;
    border-right: 1px solid var(--line);
    border-radius: 0;
  }
  .modes .pills .pill:last-child { border-right: 0; }
  .modes .pill.on {
    background: var(--surface-dk);
    color: #fff;
  }
  /* "+ new event" — primary action sitting to the right of the mode pills. */
  .modes .pill-add {
    background: var(--accent);
    color: #fff;
    border-color: var(--accent);
    font-weight: 700;
  }
  .modes .pill-add:hover:not(:disabled) {
    background: var(--accent-dim);
  }
  .err { color: #c00; font-size: 0.8rem; padding: 0 0.25rem; }
  /* Used inside an Agenda group when the (today) bucket has no events. */
  .empty-row {
    padding: 0.6rem 0.65rem;
    font-size: 0.85rem;
    color: var(--ink-mute);
    font-style: italic;
  }

  /* MONTH grid */
  .dow {
    display: grid;
    grid-template-columns: repeat(7, 1fr);
    text-align: center;
    color: var(--ink-mute);
    font-size: 0.65rem;
    text-transform: uppercase;
    letter-spacing: 0.1em;
    padding: 0 1px;
    margin-bottom: 2px;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(7, 1fr);
    gap: 1px;
    background: var(--line);
    border: 1px solid var(--line);
    margin-bottom: 0.5rem;
  }
  .cell {
    position: relative;
    background: var(--surface-lo);
    border: 0;
    color: var(--ink);
    font: inherit;
    padding: 0.2rem 0.1rem 0.15rem;
    /* Compact fixed height (instead of a square aspect-ratio) so the
       month grid stays small but readable. */
    min-height: 42px;
    cursor: pointer;
  }
  .cell.out { color: var(--ink-mute); background: var(--surface-hi); }
  /* Weekend column tint — Sunday a touch pink, Saturday a touch grey. */
  .cell.sun { background: #fbe6e6; }
  .cell.sat { background: #ececec; }
  /* Out-of-month weekend cells stay dim but keep the column hue. */
  .cell.out.sun { background: #f5dada; }
  .cell.out.sat { background: #dddddd; }
  /* Today wins over weekend column tints. */
  .cell.today { background: #fff8d0; }
  .cell.sel { outline: 2px solid var(--ink); outline-offset: -2px; z-index: 1; }
  .cell .n {
    position: absolute;
    top: 4px;
    left: 6px;
    font-size: 0.85rem;
    line-height: 1;
    font-weight: 600;
  }
  /* Event dots — one dot per event, packed bottom-right, growing
     leftward and wrapping upward when the row runs out. */
  .cell .ev-dots {
    position: absolute;
    bottom: 3px;
    right: 4px;
    left: 4px;
    display: flex;
    flex-wrap: wrap-reverse;
    justify-content: flex-end;
    align-content: flex-end;
    gap: 2px;
    pointer-events: none;
  }
  .cell .ev-dot {
    display: inline-block;
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #8b1a1a;
  }
  .cell .td-num {
    color: var(--cat-todo, #c69400);
    font-weight: 700;
    font-size: 0.62rem;
    line-height: 1;
    margin-left: 2px;
  }

  /* WEEK grid */
  .week-grid {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .wday {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 0.4rem 0.55rem;
  }
  .wday.sun { background: #fbe6e6; }
  .wday.sat { background: #ececec; }
  .wday.today {
    border-color: var(--ink);
    background: #fff8d0;
  }
  /* Two-line header: weekday name centred + enlarged, then short date
     on the next line, left-aligned and smaller. */
  .wday-h {
    border-bottom: 1px solid var(--line-soft);
    padding-bottom: 0.25rem;
    margin-bottom: 0.3rem;
  }
  .wday-h .wday-name {
    text-align: center;
    font-size: 1.05rem;
    font-weight: 700;
    letter-spacing: 0.04em;
    color: var(--ink);
    line-height: 1.2;
  }
  .wday-h .wday-date {
    text-align: left;
    font-size: 0.7rem;
    color: var(--ink-mute);
    margin-top: 1px;
    line-height: 1.1;
  }
  .wday .muted { font-size: 0.8rem; color: var(--ink-mute); margin: 0; padding: 0.2rem 0; }
  .wev { list-style: none; padding: 0; margin: 0; }
  .wev-row {
    display: flex;
    gap: 0.6rem;
    width: 100%;
    background: transparent;
    border: 0;
    text-align: left;
    padding: 0.3rem 0;
    font: inherit;
    color: var(--ink);
    cursor: pointer;
    border-bottom: 1px solid var(--line-soft);
  }
  .wev-row:last-child { border-bottom: 0; }
  .wev-row .t {
    width: 3.2rem;
    font-size: 0.78rem;
    color: #8b1a1a;       /* dark red clock time, matches PalmCell meta */
    font-weight: 700;
    flex-shrink: 0;
  }
  .wev-row .ti { flex: 1; font-size: 0.9rem; }

  .day-head { margin: 0.5rem 0 0.3rem; padding: 0 0.25rem; font-size: 0.85rem; color: var(--ink); }
  .dh-label { color: var(--ink); font-weight: 700; }
  .dh-today { color: var(--ink-mute); font-size: 0.75rem; }

  /* AI processing banner — sits above the AI card */
  .ai-banner {
    background: var(--surface-hi);
    border: 1px solid var(--line);
    border-left: 3px solid var(--accent);
    padding: 0.5rem 0.7rem;
    margin-bottom: 0.4rem;
    font-size: 0.85rem;
    color: var(--ink);
    border-radius: 3px;
  }
  .ai-banner.err {
    border-left-color: #8b1a1a;
    color: #8b1a1a;
    font-weight: 600;
  }

  /* AI card */
  .ai-card {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 0.4rem 0.55rem;
    /* Sits at the very top of the date book body, right under the
       prev / period / next header — let the surrounding gap come from
       the parent .db `gap`. */
    margin: 0;
  }
  .ai-open {
    width: 100%;
    background: transparent;
    border: 0;
    color: var(--ink);
    text-align: left;
    padding: 0.45rem 0.3rem;
    font: inherit;
    font-size: 0.9rem;
    cursor: pointer;
  }
  .ai-open:hover { background: var(--surface-hi); }
  .ai-card form { display: flex; flex-direction: column; gap: 0.4rem; padding: 0.4rem 0; }
  .ai-hint { font-size: 0.75rem; color: var(--ink-mute); margin: 0; }
  .ai-card textarea {
    background: var(--surface-hi);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.45rem 0.55rem;
    font: inherit;
    font-size: 0.9rem;
    resize: vertical;
    border-radius: 3px;
  }
  .ai-actions { display: flex; justify-content: flex-end; gap: 0.4rem; }
  .ai-cancel, .ai-go {
    background: var(--surface-hi); color: var(--ink);
    border: 1px solid var(--line);
    padding: 0.35rem 0.7rem; font: inherit; font-weight: 700;
    cursor: pointer; border-radius: 3px;
  }
  .ai-go { background: var(--surface-dk); color: #fff; border-color: #1a1a1a; }
  .ai-go:disabled { opacity: 0.5; cursor: not-allowed; }

  /* Bottom sheet */
  .sheet-backdrop {
    position: fixed; inset: 0; background: rgba(0,0,0,0.55); z-index: 50;
  }
  .sheet {
    position: fixed;
    left: 0; right: 0; bottom: 0;
    background: var(--surface-lo);
    border-top: 1px solid var(--line);
    z-index: 51;
    padding: 0.75rem 0.9rem calc(1rem + env(safe-area-inset-bottom));
    max-height: 92vh;
    overflow-y: auto;
    box-shadow: 0 -10px 30px rgba(0,0,0,0.4);
  }
  /* When the side drawer is docked open it renders at a higher z-index
     than the sheet and overlaps its left edge — exactly where every
     field label, the sheet title and the left-aligned input values sit,
     hiding them behind the rail. Shift the sheet + backdrop clear of the
     260px rail so the whole form stays visible. */
  :global(html.drawer-docked) .sheet-backdrop,
  :global(html.drawer-docked) .sheet {
    left: 260px;
  }
  .sheet-head {
    display: flex; align-items: center; justify-content: space-between;
    border-bottom: 1px solid var(--line); padding-bottom: 0.5rem; margin-bottom: 0.65rem;
  }
  .sheet-head h3 { margin: 0; font-size: 1rem; color: var(--ink); font-weight: 700; }
  .sheet-close { background: transparent; border: 0; color: var(--ink-mute); font-size: 1.5rem; line-height: 1; cursor: pointer; padding: 0 0.4rem; }
  .sheet form { display: flex; flex-direction: column; gap: 0.6rem; }

  /* Compact, centred month calendar */
  .dow, .grid { width: 100%; max-width: 440px; margin-left: auto; margin-right: auto; }
  .cal-hint { text-align: center; color: var(--ink-mute); font-size: 0.72rem; margin: 0.4rem 0 0; }

  /* Day lightbox (month mode) */
  .lb-backdrop {
    position: fixed; inset: 0; background: rgba(0,0,0,0.6); z-index: 50;
  }
  .lb {
    position: fixed;
    top: 50%; left: 50%; transform: translate(-50%, -50%);
    width: min(92vw, 420px);
    max-height: 80vh; overflow-y: auto;
    background: var(--surface-lo);
    border: 1px solid var(--line);
    border-radius: 8px;
    z-index: 51;
    padding: 0.75rem 0.9rem 1rem;
    box-shadow: 0 12px 40px rgba(0,0,0,0.5);
  }
  :global(html.drawer-docked) .lb { left: calc(50% + 130px); }
  .lb-head {
    display: flex; align-items: center; justify-content: space-between;
    border-bottom: 1px solid var(--line); padding-bottom: 0.5rem; margin-bottom: 0.6rem;
  }
  .lb-title { font-size: 1rem; font-weight: 700; color: var(--ink); }
  .lb-close { background: transparent; border: 0; color: var(--ink-mute); font-size: 1.5rem; line-height: 1; cursor: pointer; padding: 0 0.4rem; }
  .lb-actions { margin-top: 0.75rem; display: flex; justify-content: flex-end; }
  .sheet label { display: flex; flex-direction: column; gap: 0.2rem; color: var(--ink-mute); font-size: 0.74rem; text-transform: uppercase; letter-spacing: 0.05em; }
  .sheet label.inline { flex-direction: row; align-items: center; gap: 0.5rem; color: var(--ink); text-transform: none; letter-spacing: 0; font-size: 0.9rem; }
  .sheet input, .sheet textarea { background: var(--surface-hi); border: 1px solid var(--line); color: var(--ink); padding: 0.45rem 0.55rem; font: inherit; font-size: 0.9rem; border-radius: 3px; }
  .sheet textarea { resize: vertical; }
  .sheet-actions { display: flex; gap: 0.4rem; align-items: center; margin-top: 0.4rem; }
  .sheet-actions .grow { flex: 1; }
</style>

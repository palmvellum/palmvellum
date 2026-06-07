<script lang="ts">
  /**
   * DateBookPalm — Palm OS-style date book for the Android wrapper.
   *
   * Design notes:
   *   - Mobile-first layout. NEVER tries to render side-by-side
   *     panels; everything stacks vertically.
   *   - Narrow-screen friendly: month grid uses CSS Grid `1fr` units
   *     so the 7 columns always exactly fill the viewport width.
   *     Each cell shows: the day number + 1 dot (yellow for event,
   *     dim yellow for open to-do). No event titles in cells.
   *   - Selected day's events render in a PalmList below the grid;
   *     tap a row to open the edit sheet.
   *   - New event is a bottom-sheet modal, NOT an inline form.
   *   - Reads + writes go through the offline store (`$lib/stores/events`)
   *     so the screen works offline.
   */
  import { onMount } from 'svelte';
  import { authState } from '$lib/auth.svelte';
  
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
  } from '$lib/calendar';
  import PalmList from './PalmList.svelte';
  import PalmCell from './PalmCell.svelte';
  import PalmEmpty from './PalmEmpty.svelte';
  import PalmButton from './PalmButton.svelte';
  import { listEvents, createEvent, updateEvent, deleteEvent } from '$lib/stores/events.svelte';
  import { listTodos } from '$lib/stores/todos.svelte';
  import { sync } from '$lib/sync.svelte';

  const now = new Date();

  // ── view state ────────────────────────────────────────────
  let viewMonth = $state(startOfMonth(now));
  let selectedDay = $state(atMidnight(now));
  let events = $state<CalendarEvent[]>([]);
  let todoDots = $state<Map<string, number>>(new Map());
  let loading = $state(true);
  let loadErr = $state<string | null>(null);

  // ── edit sheet ────────────────────────────────────────────
  let sheetOpen = $state(false);
  let editing = $state<CalendarEvent | null>(null);
  let fTitle = $state('');
  let fStart = $state('');
  let fEnd = $state('');
  let fAllDay = $state(false);
  let fLocation = $state('');
  let fNotes = $state('');
  let saving = $state(false);

  const DOW = ['M', 'T', 'W', 'T', 'F', 'S', 'S'] as const;

  const grid = $derived(monthGridDays(viewMonth));
  const byDay = $derived(bucketByDay(events));
  const selectedKey = $derived(ymd(selectedDay));
  const selectedEvents = $derived(byDay.get(selectedKey) ?? []);

  async function load() {
    if (!authState.userId) return;
    loading = true;
    loadErr = null;
    try {
      // Kick a sync pull so Dexie has the freshest server rows BEFORE
      // we read. await so events show up on first open from a cold install.
      // If offline the pull will reject quickly and we render whatever
      // Dexie has from the last session.
      try { await sync.pull(); } catch (_) { /* offline ok */ }
      const from = startOfMonth(viewMonth);
      const to = startOfNextMonth(viewMonth);
      from.setDate(from.getDate() - 7);
      to.setDate(to.getDate() + 7);
      const [evs, todos] = await Promise.all([
        listEvents({ from, to }),
        listTodos(),
      ]);
      events = evs;
      // Filter open to-dos with a due date in the visible window and
      // bucket them by date for dot rendering.
      const fromMs = from.getTime();
      const toMs = to.getTime();
      const m = new Map<string, number>();
      for (const r of todos) {
        const md = r.metadata as { palm_due_date?: string; palm_completed?: boolean };
        const due = (md.palm_due_date ?? '').trim();
        if (!due || !/^\d{4}-\d{2}-\d{2}$/.test(due)) continue;
        if (md.palm_completed === true) continue;
        const [y, mo, d] = due.split('-').map(Number);
        const dt = new Date(y, mo - 1, d, 0, 0, 0, 0).getTime();
        if (dt < fromMs || dt >= toMs) continue;
        m.set(due, (m.get(due) ?? 0) + 1);
      }
      todoDots = m;
    } catch (e) {
      loadErr = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  $effect(() => {
    if (authState.phase === 'ready') void load();
  });

  // Re-render after every sync pull so the calendar picks up server
  // rows once they land in Dexie (initial install + offline -> online).
  $effect(() => {
    // touch reactive trigger so this effect re-runs on pulls
    void sync.last_pulled_at;
    if (authState.phase === 'ready') void load();
  });

  onMount(() => { void load(); });

  function prevMonth() {
    viewMonth = new Date(viewMonth.getFullYear(), viewMonth.getMonth() - 1, 1);
    void load();
  }
  function nextMonth() {
    viewMonth = new Date(viewMonth.getFullYear(), viewMonth.getMonth() + 1, 1);
    void load();
  }
  function gotoToday() {
    viewMonth = startOfMonth(now);
    selectedDay = atMidnight(now);
    void load();
  }
  function selectDay(d: Date) {
    selectedDay = atMidnight(d);
  }

  function openCreate() {
    editing = null;
    fTitle = '';
    const local = isoToLocalInput(new Date().toISOString());
    fStart = local;
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
  function closeSheet() {
    sheetOpen = false;
    editing = null;
  }
  async function saveSheet(ev: Event) {
    ev.preventDefault();
    if (!authState.userId || !fTitle.trim() || !fStart) return;
    saving = true;
    try {
      const payload: Partial<CalendarEvent> = {
        title: fTitle.trim(),
        start_at: localInputToISO(fStart),
        end_at: fEnd ? localInputToISO(fEnd) : null,
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
    if (!confirm('Delete event "' + e.title + '"?')) return;
    await deleteEvent(e.id);
    await load();
  }
</script>

<div class="db">
  <header class="ctrl">
    <button type="button" class="nav" onclick={prevMonth} aria-label="previous month">‹</button>
    <div class="title">{monthLabel(viewMonth)}</div>
    <button type="button" class="nav" onclick={nextMonth} aria-label="next month">›</button>
    <button type="button" class="today" onclick={gotoToday}>today</button>
  </header>

  <div class="dow">
    {#each DOW as d, i (i)}<span>{d}</span>{/each}
  </div>

  <div class="grid">
    {#each grid as d (d.toISOString())}
      {@const isCur = d.getMonth() === viewMonth.getMonth()}
      {@const isToday = sameDay(d, now)}
      {@const isSel = sameDay(d, selectedDay)}
      {@const evs = byDay.get(ymd(d)) ?? []}
      {@const todoCount = todoDots.get(ymd(d)) ?? 0}
      <button
        type="button"
        class="cell"
        class:out={!isCur}
        class:today={isToday}
        class:sel={isSel}
        onclick={() => selectDay(d)}
        aria-label={ymd(d)}
      >
        <span class="n">{d.getDate()}</span>
        {#if evs.length > 0 || todoCount > 0}
          <span class="dots">
            {#if evs.length > 0}<span class="dot ev"></span>{/if}
            {#if todoCount > 0}<span class="dot td"></span>{/if}
            {#if (evs.length + todoCount) > 2}<span class="more">·</span>{/if}
          </span>
        {/if}
      </button>
    {/each}
  </div>

  {#if loadErr}<p class="err">{loadErr}</p>{/if}

  <div class="day-head">
    <span class="dh-label">{selectedDay.toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' })}</span>
    {#if sameDay(selectedDay, now)}<span class="dh-today">· today</span>{/if}
  </div>

  {#if loading && selectedEvents.length === 0}
    <PalmEmpty title="loading…" />
  {:else if selectedEvents.length === 0}
    <PalmEmpty title="Nothing scheduled." hint="Tap + to add an event." />
  {:else}
    <PalmList>
      {#each selectedEvents as e (e.id)}
        <PalmCell
          leading={e.all_day ? '●' : '·'}
          title={e.title}
          meta={e.all_day ? 'all-day' : hhmm(e.start_at)}
          onclick={() => openEdit(e)}
        >
          {#if e.location}at {e.location}{/if}
          {#if e.notes && !e.location}{e.notes}{/if}
        </PalmCell>
      {/each}
    </PalmList>
  {/if}

  <div class="actions">
    <PalmButton onclick={openCreate}>＋ new event</PalmButton>
  </div>
</div>

{#if sheetOpen}
  <div class="sheet-backdrop" onclick={closeSheet} role="presentation"></div>
  <div class="sheet" role="dialog" aria-modal="true">
    <div class="sheet-head">
      <h3>{editing ? 'edit event' : 'new event'}</h3>
      <button type="button" class="sheet-close" onclick={closeSheet} aria-label="close">×</button>
    </div>
    <form onsubmit={saveSheet}>
      <label>
        title
        <input type="text" bind:value={fTitle} required maxlength={256} />
      </label>
      <label class="row inline">
        <input type="checkbox" bind:checked={fAllDay} />
        all-day
      </label>
      <label>
        start
        <input type="datetime-local" bind:value={fStart} required />
      </label>
      <label>
        end
        <input type="datetime-local" bind:value={fEnd} />
      </label>
      <label>
        location
        <input type="text" bind:value={fLocation} maxlength={120} />
      </label>
      <label>
        notes
        <textarea bind:value={fNotes} rows="3"></textarea>
      </label>
      <div class="sheet-actions">
        {#if editing}
          <PalmButton variant="ghost" onclick={() => editing && removeEvent(editing)}>delete</PalmButton>
        {/if}
        <span class="grow"></span>
        <PalmButton variant="ghost" onclick={closeSheet}>cancel</PalmButton>
        <PalmButton type="submit" disabled={saving || !fTitle.trim()}>
          {saving ? 'saving…' : 'save'}
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
    margin-bottom: 0.3rem;
  }
  .ctrl .title {
    flex: 1;
    text-align: center;
    font-weight: 600;
    font-size: 1.05rem;
    color: var(--ink);
  }
  .ctrl .nav {
    background: var(--surface-hi);
    border: 1px solid var(--line);
    color: var(--accent);
    font-size: 1.4rem;
    line-height: 1;
    padding: 0.2rem 0.6rem;
    cursor: pointer;
    min-height: 36px;
  }
  .ctrl .today {
    background: var(--accent);
    color: var(--bg);
    border: 1px solid var(--accent);
    padding: 0.3rem 0.7rem;
    font-weight: 600;
    cursor: pointer;
    font-size: 0.78rem;
    text-transform: lowercase;
    min-height: 36px;
  }

  /* Day-of-week labels — single-letter on narrow screens */
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

  /* Calendar grid — never overflows, fills viewport */
  .grid {
    display: grid;
    grid-template-columns: repeat(7, 1fr);
    gap: 1px;
    background: var(--line);
    border: 1px solid var(--line);
    margin-bottom: 0.5rem;
  }
  .cell {
    background: var(--surface-lo);
    border: 0;
    color: var(--ink);
    font: inherit;
    padding: 0.25rem 0.1rem 0.2rem;
    aspect-ratio: 1 / 1;
    cursor: pointer;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: flex-start;
    gap: 2px;
    position: relative;
    min-height: 0;
  }
  .cell.out { color: var(--ink-mute); background: var(--bg); }
  .cell.today { background: var(--surface-hi); }
  .cell.today .n { color: var(--accent); font-weight: 700; }
  .cell.sel { outline: 2px solid var(--accent); outline-offset: -2px; z-index: 1; }
  .cell .n {
    font-size: 0.85rem;
    line-height: 1;
  }
  .cell .dots {
    display: inline-flex;
    align-items: center;
    gap: 2px;
  }
  .cell .dot {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: var(--accent);
  }
  .cell .dot.td {
    background: var(--cat-todo, #f4d35e);
    width: 4px;
    height: 4px;
    border: 1px solid var(--ink-mute);
  }
  .cell .more {
    font-size: 0.7rem;
    color: var(--accent);
    line-height: 0.5;
  }

  .day-head {
    margin-top: 0.5rem;
    margin-bottom: 0.4rem;
    padding: 0 0.25rem;
    font-size: 0.85rem;
    color: var(--ink);
  }
  .dh-label { color: var(--accent); font-weight: 600; }
  .dh-today { color: var(--ink-mute); font-size: 0.75rem; }
  .err { color: #ff6b6b; font-size: 0.8rem; padding: 0 0.25rem; }
  .actions {
    margin-top: 0.5rem;
    display: flex;
    justify-content: center;
  }

  /* Bottom sheet for new / edit event */
  .sheet-backdrop {
    position: fixed; inset: 0;
    background: rgba(0,0,0,0.65);
    z-index: 50;
  }
  .sheet {
    position: fixed;
    left: 0; right: 0; bottom: 0;
    background: var(--surface-lo);
    border-top: 2px solid var(--accent);
    z-index: 51;
    padding: 0.75rem 0.9rem calc(1rem + env(safe-area-inset-bottom));
    max-height: 90vh;
    overflow-y: auto;
    box-shadow: 0 -10px 30px rgba(0,0,0,0.4);
  }
  .sheet-head {
    display: flex; align-items: center; justify-content: space-between;
    border-bottom: 1px solid var(--line); padding-bottom: 0.5rem; margin-bottom: 0.65rem;
  }
  .sheet-head h3 { margin: 0; font-size: 1rem; color: var(--accent); }
  .sheet-close {
    background: transparent; border: 0; color: var(--ink-mute);
    font-size: 1.5rem; line-height: 1; cursor: pointer; padding: 0 0.4rem;
  }
  .sheet form {
    display: flex; flex-direction: column; gap: 0.65rem;
  }
  .sheet label {
    display: flex; flex-direction: column; gap: 0.2rem;
    color: var(--ink-mute); font-size: 0.78rem; text-transform: uppercase; letter-spacing: 0.05em;
  }
  .sheet label.inline {
    flex-direction: row; align-items: center; gap: 0.5rem;
    color: var(--ink); text-transform: none; letter-spacing: 0;
    font-size: 0.9rem;
  }
  .sheet input, .sheet textarea {
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.5rem 0.6rem;
    font: inherit;
    font-size: 0.95rem;
    border-radius: 0;
  }
  .sheet textarea { resize: vertical; }
  .sheet-actions {
    display: flex; gap: 0.4rem; align-items: center; margin-top: 0.4rem;
  }
  .sheet-actions .grow { flex: 1; }
</style>

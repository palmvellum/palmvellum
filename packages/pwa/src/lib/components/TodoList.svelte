<script lang="ts">
  /**
   * <TodoList />
   *
   * User-wide To Do List. Reads every non-deleted record with
   * type='todo' for the signed-in user. Each todo carries
   * structured fields inside `metadata`:
   *
   *   palm_due_date      ISO yyyy-mm-dd (string) or empty
   *   palm_priority      1..5 (number)
   *   palm_completed     boolean
   *   palm_notes         string
   *   palm_category_name string (default "Unfiled")
   *
   * (AI) prefix detection (case-insensitive) flags a task as
   * agentic — visual badge for now; Phase 4 will wire the
   * actual AI executor that turns the prompt into a Memo result.
   */
  import { supabase } from '$lib/supabase';
  import { authState } from '$lib/auth.svelte';
  import { newUlid } from '$lib/ulid';
  import { t } from '$lib/i18n.svelte';

  interface Todo {
    id: string;
    user_id: string;
    type: 'todo';
    body: string; // description
    source: string;
    ai_status: string | null;
    ai_error: string | null;
    metadata: {
      palm_due_date?: string;
      palm_priority?: number;
      palm_completed?: boolean;
      palm_notes?: string;
      palm_category_name?: string;
      agent_summary?: string;
      agent_result_memo?: string;
      agent_processed?: boolean;
    } | null;
    created_at: string;
    updated_at: string;
    palm_device_id: string | null;
  }

  let todos = $state<Todo[]>([]);
  let loading = $state(true);
  let loadError = $state<string | null>(null);

  let filter = $state<'open' | 'done' | 'all'>('open');

  // Create
  let showCreate = $state(false);
  let createBody = $state('');
  let createDue = $state('');
  let createPriority = $state(3);
  let createNotes = $state('');
  let createBusy = $state(false);
  let createError = $state<string | null>(null);

  // Edit
  let editingId = $state<string | null>(null);
  let editBody = $state('');
  let editDue = $state('');
  let editPriority = $state(3);
  let editNotes = $state('');
  let editBusy = $state(false);

  async function load() {
    if (!authState.userId) return;
    loading = true;
    loadError = null;
    const { data, error } = await supabase
      .from('records')
      .select('*')
      .eq('type', 'todo')
      .is('deleted_at', null)
      .order('updated_at', { ascending: false });
    loading = false;
    if (error) {
      loadError = error.message;
      return;
    }
    todos = (data ?? []) as Todo[];
  }

  function isAIBody(body: string): boolean {
    return /^\s*\(ai\)/i.test(body);
  }

  function priority(t: Todo): number {
    return Math.min(5, Math.max(1, t.metadata?.palm_priority ?? 3));
  }
  function completed(t: Todo): boolean {
    return !!t.metadata?.palm_completed;
  }
  function dueDate(t: Todo): string | null {
    const d = t.metadata?.palm_due_date;
    return d && d.length > 0 ? d : null;
  }

  function resetCreate() {
    createBody = '';
    createDue = '';
    createPriority = 3;
    createNotes = '';
    createError = null;
  }

  async function createTodo() {
    if (!authState.userId) return;
    const body = createBody.trim();
    if (!body) {
      createError = 'description required';
      return;
    }
    createError = null;
    createBusy = true;
    // (AI) prefix routes through the agentic worker — mark pending so
    // the Edge Function's claim filter matches before the trigger fires.
    const isAgent = /^\s*\(ai\)/i.test(body);
    const { error } = await supabase.from('records').insert({
      id: newUlid(),
      user_id: authState.userId,
      type: 'todo',
      posture: 'open',
      body,
      source: 'web',
      ai_status: isAgent ? 'pending' : null,
      metadata: {
        palm_due_date: createDue || '',
        palm_priority: createPriority,
        palm_completed: false,
        palm_notes: createNotes.trim(),
        palm_category_name: 'Unfiled',
      },
    });
    createBusy = false;
    if (error) {
      createError = error.message;
      return;
    }
    showCreate = false;
    resetCreate();
    await load();
  }

  function startEdit(t: Todo) {
    editingId = t.id;
    editBody = t.body;
    editDue = t.metadata?.palm_due_date ?? '';
    editPriority = priority(t);
    editNotes = t.metadata?.palm_notes ?? '';
  }

  function cancelEdit() {
    editingId = null;
  }

  async function saveEdit(t: Todo) {
    if (!editBody.trim()) return;
    editBusy = true;
    const meta = {
      ...(t.metadata ?? {}),
      palm_due_date: editDue || '',
      palm_priority: editPriority,
      palm_notes: editNotes.trim(),
    };
    const { error } = await supabase
      .from('records')
      .update({ body: editBody.trim(), metadata: meta })
      .eq('id', t.id);
    editBusy = false;
    if (error) {
      alert(error.message);
      return;
    }
    cancelEdit();
    await load();
  }

  async function toggleDone(t: Todo) {
    const newCompleted = !completed(t);
    const meta = { ...(t.metadata ?? {}), palm_completed: newCompleted };
    const { error } = await supabase.from('records').update({ metadata: meta }).eq('id', t.id);
    if (error) {
      alert(error.message);
      return;
    }
    await load();
  }

  async function deleteTodo(t: Todo) {
    if (!confirm(`Delete "${t.body.slice(0, 40)}"?`)) return;
    const { error } = await supabase
      .from('records')
      .update({ deleted_at: new Date().toISOString() })
      .eq('id', t.id);
    if (error) {
      alert(error.message);
      return;
    }
    if (editingId === t.id) cancelEdit();
    await load();
  }

  function fmtDue(s: string | null): string {
    if (!s) return '';
    const d = new Date(s + 'T00:00:00');
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const diff = Math.round((d.getTime() - today.getTime()) / 86_400_000);
    if (diff === 0) return 'today';
    if (diff === 1) return 'tomorrow';
    if (diff === -1) return 'yesterday';
    if (diff > 0 && diff < 7) return `in ${diff}d`;
    if (diff < 0 && diff > -7) return `${-diff}d ago`;
    return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  }

  function dueClass(s: string | null): string {
    if (!s) return '';
    const d = new Date(s + 'T00:00:00');
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const diff = Math.round((d.getTime() - today.getTime()) / 86_400_000);
    if (diff < 0) return 'overdue';
    if (diff === 0) return 'today';
    return '';
  }

  const filteredTodos = $derived(
    filter === 'all'
      ? todos
      : todos.filter((t) => (filter === 'open' ? !completed(t) : completed(t))),
  );

  const openCount = $derived(todos.filter((t) => !completed(t)).length);
  const doneCount = $derived(todos.filter((t) => completed(t)).length);

  $effect(() => {
    if (authState.phase === 'ready') void load();
  });

  $effect(() => {
    const channel = supabase
      .channel('todo-all')
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'records' },
        async (payload) => {
          const row = (payload.new ?? payload.old) as { type?: string };
          if (row?.type !== 'todo') return;
          await load();
        },
      )
      .subscribe();
    return () => {
      channel.unsubscribe();
    };
  });
</script>

<section class="todolist">
  <header class="head">
    <div class="filters">
      <button class:active={filter === 'open'} onclick={() => (filter = 'open')}>{t('todo.filterOpen')} ({openCount})</button>
      <button class:active={filter === 'done'} onclick={() => (filter = 'done')}>{t('todo.filterDone')} ({doneCount})</button>
      <button class:active={filter === 'all'} onclick={() => (filter = 'all')}>{t('todo.filterAll')} ({todos.length})</button>
    </div>
    <button class="add" onclick={() => (showCreate = !showCreate)}>
      {showCreate ? t('common.cancel') : t('todo.newTask')}
    </button>
  </header>

  {#if showCreate}
    <form class="create" onsubmit={(e) => { e.preventDefault(); void createTodo(); }}>
      <input
        bind:value={createBody}
        placeholder="What needs doing? Prefix with (AI) to make it an agentic task."
        maxlength="256"
        required
      />
      <div class="meta-row">
        <label>
          <span>due</span>
          <input type="date" bind:value={createDue} />
        </label>
        <label>
          <span>priority</span>
          <select bind:value={createPriority}>
            <option value={1}>1 — top</option>
            <option value={2}>2</option>
            <option value={3}>3 — normal</option>
            <option value={4}>4</option>
            <option value={5}>5 — low</option>
          </select>
        </label>
      </div>
      <textarea
        bind:value={createNotes}
        rows="2"
        placeholder="notes (optional)"
        maxlength="1024"
      ></textarea>
      <div class="row">
        {#if createError}<span class="error">{createError}</span>{/if}
        <button type="submit" class="primary" disabled={createBusy}>
          {createBusy ? 'saving…' : 'add'}
        </button>
      </div>
    </form>
  {/if}

  {#if loading}
    <p class="status">loading…</p>
  {:else if loadError}
    <p class="status error">{loadError}</p>
  {:else if filteredTodos.length === 0}
    <p class="status">
      {todos.length === 0
        ? 'No tasks on this Palm yet. Add one above or push from ToDo via vellum-sync.'
        : filter === 'open'
          ? 'nothing open.'
          : 'No completed tasks.'}
    </p>
  {:else}
    <ul class="list">
      {#each filteredTodos as t (t.id)}
        {@const ai = isAIBody(t.body)}
        {@const done = completed(t)}
        {@const due = dueDate(t)}
        <li class="item" class:done class:ai-item={ai}>
          {#if editingId === t.id}
            <form class="edit" onsubmit={(e) => { e.preventDefault(); void saveEdit(t); }}>
              <input bind:value={editBody} maxlength="256" required />
              <div class="meta-row">
                <label>
                  <span>due</span>
                  <input type="date" bind:value={editDue} />
                </label>
                <label>
                  <span>priority</span>
                  <select bind:value={editPriority}>
                    <option value={1}>1</option>
                    <option value={2}>2</option>
                    <option value={3}>3</option>
                    <option value={4}>4</option>
                    <option value={5}>5</option>
                  </select>
                </label>
              </div>
              <textarea bind:value={editNotes} rows="2"></textarea>
              <div class="row">
                <button type="button" onclick={cancelEdit}>cancel</button>
                <button class="primary" type="submit" disabled={editBusy}>
                  {editBusy ? 'saving…' : 'save'}
                </button>
              </div>
            </form>
          {:else}
            <div class="row main">
              <label class="check">
                <input
                  type="checkbox"
                  checked={done}
                  onchange={() => toggleDone(t)}
                  aria-label="mark complete"
                />
              </label>
              <span class="prio prio-{priority(t)}" title="priority {priority(t)}">{priority(t)}</span>
              <div class="body">
                {#if ai}<span class="ai-badge" title="agentic task">AI</span>{/if}
                <span class="desc" class:strike={done}>{t.body}</span>
                {#if t.metadata?.palm_notes}<div class="notes">{t.metadata.palm_notes}</div>{/if}
                {#if ai && (t.ai_status === 'pending' || t.ai_status === 'processing')}
                  <div class="agent-status pending">[...] agent working</div>
                {:else if ai && t.ai_status === 'done' && t.metadata?.agent_summary}
                  <div class="agent-status done">
                    [done] <strong>agent.</strong>
                    {t.metadata.agent_summary}
                    <span class="result-note">(full result in Memo Pad)</span>
                  </div>
                {:else if ai && t.ai_status === 'error'}
                  <div class="agent-status err">[err] {t.ai_error ?? 'agent failed'}</div>
                {/if}
              </div>
              {#if due}
                <span class="due {dueClass(due)}">{fmtDue(due)}</span>
              {/if}
              <button class="link" onclick={() => startEdit(t)}>edit</button>
              <button class="link danger" onclick={() => deleteTodo(t)}>×</button>
            </div>
          {/if}
        </li>
      {/each}
    </ul>
  {/if}
</section>

<style>
  .todolist {
    max-width: 820px;
  }
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 1rem;
    flex-wrap: wrap;
    gap: 0.6rem;
  }
  .filters {
    display: flex;
    gap: 0.5rem;
  }
  .filters button {
    background: var(--surface);
    color: var(--ink-mute);
    border: 1px solid var(--line);
    padding: 0.35rem 0.7rem;
    font: inherit;
    font-size: 0.85rem;
    cursor: pointer;
    text-transform: lowercase;
  }
  .filters button.active {
    border-color: var(--accent);
    color: var(--accent);
    background: var(--bg);
  }
  .add,
  .primary {
    background: var(--accent);
    color: var(--bg);
    border: 1px solid var(--accent);
    padding: 0.4rem 0.9rem;
    font: inherit;
    font-weight: 600;
    cursor: pointer;
  }
  .add:hover:not(:disabled),
  .primary:hover:not(:disabled) {
    background: var(--accent-dim);
  }
  .primary:disabled {
    opacity: 0.6;
  }

  .create,
  .edit {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 0.9rem 1.1rem;
    margin-bottom: 1rem;
    display: grid;
    gap: 0.6rem;
    border-radius: 2px;
  }
  .create input[type='text'],
  .create input:not([type]),
  .edit input[type='text'],
  .edit input:not([type]) {
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.5rem 0.6rem;
    font: inherit;
    font-size: 0.95rem;
  }
  textarea {
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.5rem 0.6rem;
    font: inherit;
    font-size: 0.9rem;
    resize: vertical;
  }
  .meta-row {
    display: flex;
    gap: 1rem;
    flex-wrap: wrap;
  }
  @media (max-width: 600px) {
    .meta-row {
      gap: 0.6rem 1rem;
    }
    .meta-row label {
      flex: 1 1 calc(50% - 0.5rem);
    }
    .main {
      flex-wrap: wrap;
    }
    .body {
      flex: 1 1 100%;
      order: 4;
      margin-top: 0.2rem;
    }
    .due {
      order: 5;
    }
    .head {
      flex-wrap: wrap;
    }
  }
  .meta-row label {
    display: grid;
    gap: 0.25rem;
    font-size: 0.8rem;
    color: var(--ink-mute);
  }
  .meta-row input,
  .meta-row select {
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.4rem 0.5rem;
    font: inherit;
    font-size: 0.9rem;
  }

  .row {
    display: flex;
    align-items: center;
    gap: 0.7rem;
    flex-wrap: wrap;
  }
  .row .primary {
    margin-left: auto;
  }
  .edit .row button:not(.primary) {
    background: var(--surface);
    color: var(--ink);
    border: 1px solid var(--line);
    padding: 0.4rem 0.9rem;
    font: inherit;
    cursor: pointer;
  }

  .status {
    color: var(--ink-mute);
    padding: 2rem 1rem;
    text-align: center;
  }
  .status.error,
  .error {
    color: #ff6b6b;
    font-size: 0.85rem;
  }

  .list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 0.4rem;
  }
  .item {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    border-left: 3px solid var(--cat-todo);
    padding: 0.55rem 0.8rem;
    border-radius: 2px;
  }
  .item.ai-item {
    border-left-color: var(--cat-ai);
  }
  .item.done {
    opacity: 0.55;
  }
  .main {
    flex-wrap: nowrap;
  }
  .check {
    display: inline-flex;
    align-items: center;
    cursor: pointer;
  }
  .check input {
    width: 1rem;
    height: 1rem;
    cursor: pointer;
  }
  .prio {
    display: inline-block;
    width: 1.4rem;
    height: 1.4rem;
    line-height: 1.4rem;
    text-align: center;
    border-radius: 50%;
    font-size: 0.75rem;
    font-weight: 600;
    background: var(--surface);
    color: var(--ink-mute);
    border: 1px solid var(--line);
  }
  .prio-1 {
    background: #6d2020;
    color: #fff;
    border-color: #6d2020;
  }
  .prio-2 {
    background: #6d4020;
    color: #fff;
    border-color: #6d4020;
  }
  .prio-3 {
    color: var(--ink);
  }
  .prio-4,
  .prio-5 {
    color: var(--ink-mute);
  }
  .body {
    flex: 1;
    min-width: 0;
  }
  .desc {
    color: var(--ink);
    word-break: break-word;
  }
  .desc.strike {
    text-decoration: line-through;
    color: var(--ink-mute);
  }
  .notes {
    color: var(--ink-mute);
    font-size: 0.8rem;
    margin-top: 0.15rem;
    white-space: pre-wrap;
  }
  .ai-badge {
    background: var(--accent);
    color: var(--bg);
    font-size: 0.65rem;
    padding: 0.05rem 0.35rem;
    margin-right: 0.4rem;
    font-weight: 700;
    letter-spacing: 0.05em;
  }
  .agent-status {
    font-size: 0.78rem;
    margin-top: 0.25rem;
    line-height: 1.4;
  }
  .agent-status.pending {
    color: #ffaf60;
  }
  .agent-status.done {
    color: var(--ink-dim);
  }
  .agent-status.done strong {
    color: var(--accent);
  }
  .agent-status.err {
    color: #ff6b6b;
  }
  .result-note {
    color: var(--ink-mute);
    font-size: 0.72rem;
    margin-left: 0.3rem;
  }
  .due {
    color: var(--ink-mute);
    font-size: 0.75rem;
    padding: 0.1rem 0.4rem;
    border: 1px solid var(--line);
    border-radius: 2px;
    white-space: nowrap;
  }
  .due.overdue {
    color: #ff6b6b;
    border-color: #ff6b6b;
  }
  .due.today {
    color: var(--accent);
    border-color: var(--accent-dim);
  }
  .link {
    background: none;
    border: none;
    color: var(--ink-mute);
    font: inherit;
    font-size: 0.8rem;
    cursor: pointer;
    padding: 0.1rem 0.3rem;
  }
  .link:hover {
    color: var(--accent);
  }
  .link.danger:hover {
    color: #ff6b6b;
  }
</style>

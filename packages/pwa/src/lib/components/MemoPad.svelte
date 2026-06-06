<script lang="ts">
  /**
   * <MemoPad />
   *
   * User-wide Memo Pad. Reads every non-deleted record with
   * type IN ('aiquery','thought') for the signed-in user.
   * Memos created here as AI queries get ai_status=pending on
   * insert and the AI worker writes ai_response — which we render
   * inline once it arrives.
   *
   * (AI) prefix in the body is auto-detected at create time and
   * routes the memo into the AI flow even if the user didn't
   * explicitly pick the AI radio.
   */
  import { supabase } from '$lib/supabase';
  import { authState } from '$lib/auth.svelte';
  import { newUlid } from '$lib/ulid';

  interface Memo {
    id: string;
    user_id: string;
    type: 'aiquery' | 'thought';
    posture: string;
    body: string;
    source: string;
    ai_status: string | null;
    ai_response: string | null;
    metadata: Record<string, unknown>;
    created_at: string;
    updated_at: string;
    palm_device_id: string | null;
  }

  let memos = $state<Memo[]>([]);
  let loading = $state(true);
  let loadError = $state<string | null>(null);

  let filter = $state<'all' | 'ai' | 'note'>('all');

  let showCreate = $state(false);
  let createBody = $state('');
  let createBusy = $state(false);
  let createError = $state<string | null>(null);

  let editingId = $state<string | null>(null);
  let editBody = $state('');
  let editBusy = $state(false);

  // File upload state
  let fileInput: HTMLInputElement | null = $state(null);
  let uploadBusy = $state(false);
  let uploadError = $state<string | null>(null);
  let dragOver = $state(false);

  const MAX_UPLOAD_BYTES = 20 * 1024 * 1024; // 20 MB
  const ACCEPT_TYPES = '.pdf,.docx,image/jpeg,image/png,image/webp,image/heic';

  async function load() {
    if (!authState.userId) return;
    loading = true;
    loadError = null;
    const { data, error } = await supabase
      .from('records')
      .select('*')
      .is('deleted_at', null)
      .in('type', ['aiquery', 'thought'])
      .order('updated_at', { ascending: false });
    loading = false;
    if (error) {
      loadError = error.message;
      return;
    }
    memos = (data ?? []) as Memo[];
  }

  async function createMemo() {
    if (!authState.userId) return;
    const body = createBody.trim();
    if (!body) {
      createError = 'type something';
      return;
    }
    createError = null;
    createBusy = true;

    // One AI path on the platform side:
    //   body starts with "(AI)"  → records.type='thought' +
    //                              ai_status='pending'
    //                              → agentic worker (ai-agent)
    //                              answers, can also create events /
    //                              todos, and appends a summary to
    //                              the memo body.
    //
    // Plain notes have no AI involvement and just sync to MemoDB on
    // the Palm at next HotSync. Older records pushed up from sync-cli
    // as records.type='aiquery' still display in this list — they
    // came from the legacy Q&A flow and remain readable.
    const isAgent = /^\s*\(ai\)/i.test(body);

    const { error } = await supabase.from('records').insert({
      id: newUlid(),
      user_id: authState.userId,
      type: 'thought',
      posture: 'open',
      body,
      source: 'web',
      ai_status: isAgent ? 'pending' : null,
      metadata: {
        palm_category_name: isAgent ? 'AI Agent' : 'Unfiled',
      },
    });
    createBusy = false;
    if (error) {
      createError = error.message;
      return;
    }
    createBody = '';
    showCreate = false;
    await load();
  }

  function startEdit(m: Memo) {
    editingId = m.id;
    editBody = m.body;
  }

  function cancelEdit() {
    editingId = null;
    editBody = '';
  }

  async function saveEdit(m: Memo) {
    if (!editBody.trim()) return;
    editBusy = true;
    const isAgent = /^\s*\(ai\)/i.test(editBody);
    const patch: Record<string, unknown> = {
      body: editBody.trim(),
      metadata: {
        ...(m.metadata ?? {}),
        palm_category_name: isAgent ? 'AI Agent' : 'Unfiled',
      },
    };
    // The agent webhook fires on INSERT only, so UPDATEs can't re-
    // trigger. Editing here just persists the body; to re-run the
    // agent the user has to delete and re-create.
    const { error } = await supabase.from('records').update(patch).eq('id', m.id);
    editBusy = false;
    if (error) {
      alert(error.message);
      return;
    }
    cancelEdit();
    await load();
  }

  async function deleteMemo(m: Memo) {
    if (!confirm('Delete this memo?')) return;
    const { error } = await supabase
      .from('records')
      .update({ deleted_at: new Date().toISOString() })
      .eq('id', m.id);
    if (error) {
      alert(error.message);
      return;
    }
    if (editingId === m.id) cancelEdit();
    await load();
  }

  function isAI(m: Memo): boolean {
    return m.type === 'aiquery';
  }

  function isAgentMemo(m: Memo): boolean {
    return m.type === 'thought' && /^\s*\(ai\)/i.test(m.body ?? '');
  }

  function isUploadMemo(m: Memo): boolean {
    return !!(m.metadata as { upload_path?: string } | null)?.upload_path;
  }

  function uploadFilenameOf(m: Memo): string {
    return (
      ((m.metadata as { upload_filename?: string } | null)?.upload_filename ?? '') ||
      'file'
    );
  }

  async function handleFiles(files: FileList | File[]) {
    if (!authState.userId) return;
    const list = Array.from(files);
    if (list.length === 0) return;
    uploadError = null;
    uploadBusy = true;
    try {
      for (const file of list) {
        await uploadOne(file);
      }
    } catch (e) {
      uploadError = e instanceof Error ? e.message : String(e);
    } finally {
      uploadBusy = false;
      if (fileInput) fileInput.value = '';
    }
  }

  async function uploadOne(file: File) {
    if (file.size > MAX_UPLOAD_BYTES) {
      throw new Error(`${file.name} too large (max 20 MB)`);
    }
    const lowered = file.name.toLowerCase();
    const okExt =
      lowered.endsWith('.pdf') ||
      lowered.endsWith('.docx') ||
      file.type.startsWith('image/');
    if (!okExt) {
      throw new Error(`${file.name} — only PDF / DOCX / image accepted`);
    }
    const recordId = newUlid();
    const ext = (file.name.split('.').pop() || 'bin').toLowerCase();
    const path = `${authState.userId}/${recordId}.${ext}`;

    const { error: upErr } = await supabase.storage
      .from('memo-uploads')
      .upload(path, file, { contentType: file.type, upsert: false });
    if (upErr) throw new Error(`upload: ${upErr.message}`);

    const placeholder = `${file.name}\n\nAI is reading the file. This memo will fill in within a few seconds.`;
    const { error: insErr } = await supabase.from('records').insert({
      id: recordId,
      user_id: authState.userId,
      type: 'thought',
      posture: 'open',
      body: placeholder,
      source: 'web',
      ai_status: 'pending',
      metadata: {
        palm_category_name: 'Uploads',
        upload_path: path,
        upload_filename: file.name,
        upload_mimetype: file.type,
        upload_size: file.size,
      },
    });
    if (insErr) {
      // best-effort cleanup of storage object
      await supabase.storage.from('memo-uploads').remove([path]);
      throw new Error(`record: ${insErr.message}`);
    }
  }

  function onPickFile(e: Event) {
    const target = e.target as HTMLInputElement;
    if (target.files && target.files.length > 0) void handleFiles(target.files);
  }

  function onDrop(e: DragEvent) {
    e.preventDefault();
    dragOver = false;
    const files = e.dataTransfer?.files;
    if (files && files.length > 0) void handleFiles(files);
  }
  function onDragOver(e: DragEvent) {
    e.preventDefault();
    dragOver = true;
  }
  function onDragLeave() {
    dragOver = false;
  }

  function fmtTime(s: string): string {
    return new Date(s).toLocaleString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  const filteredMemos = $derived(
    filter === 'all'
      ? memos
      : memos.filter((m) => (filter === 'ai' ? m.type === 'aiquery' : m.type === 'thought')),
  );

  const aiPendingCount = $derived(
    memos.filter(
      (m) =>
        (m.type === 'aiquery' || isAgentMemo(m) || isUploadMemo(m)) &&
        (m.ai_status === 'pending' || m.ai_status === 'processing'),
    ).length,
  );

  $effect(() => {
    if (authState.phase === 'ready') void load();
  });

  // Realtime — refresh on any memo-ish record change.
  $effect(() => {
    const channel = supabase
      .channel('memo-all')
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'records' },
        async (payload) => {
          const row = (payload.new ?? payload.old) as { type?: string };
          if (row?.type !== 'aiquery' && row?.type !== 'thought') return;
          await load();
        },
      )
      .subscribe();
    return () => {
      channel.unsubscribe();
    };
  });
</script>

<section class="memopad">
  <header class="head">
    <div class="filters">
      <button class:active={filter === 'all'} onclick={() => (filter = 'all')}>all ({memos.length})</button>
      <button class:active={filter === 'ai'} onclick={() => (filter = 'ai')}>
        AI ({memos.filter((m) => m.type === 'aiquery').length})
        {#if aiPendingCount > 0}<span class="pending-dot" title="{aiPendingCount} awaiting AI">[{aiPendingCount}]</span>{/if}
      </button>
      <button class:active={filter === 'note'} onclick={() => (filter = 'note')}
        >note ({memos.filter((m) => m.type === 'thought').length})</button
      >
    </div>
    <button class="add" onclick={() => (showCreate = !showCreate)}>
      {showCreate ? 'cancel' : '+ new memo'}
    </button>
  </header>

  <div
    class="upload"
    class:drag-over={dragOver}
    class:busy={uploadBusy}
    onclick={() => fileInput?.click()}
    onkeydown={(e) => e.key === 'Enter' && fileInput?.click()}
    ondrop={onDrop}
    ondragover={onDragOver}
    ondragleave={onDragLeave}
    role="button"
    tabindex="0"
    aria-label="upload file for AI summary"
  >
    <input
      bind:this={fileInput}
      type="file"
      accept={ACCEPT_TYPES}
      multiple
      onchange={onPickFile}
      hidden
    />
    {#if uploadBusy}
      <p>uploading…</p>
    {:else}
      <p class="upload-line">
        <strong>drop a file</strong> or click here — PDF / DOCX / image (≤ 20 MB).
        AI reads it and creates a memo summary.
      </p>
    {/if}
    {#if uploadError}<p class="error">{uploadError}</p>{/if}
  </div>

  {#if showCreate}
    <form class="create" onsubmit={(e) => { e.preventDefault(); void createMemo(); }}>
      <p class="ai-hint">
        Plain text becomes a note. Start with <code>(AI)</code> to run
        the AI agent — it answers, and if your text implies events
        or tasks it'll also create them in Date Book / To Do List.
      </p>
      <textarea
        bind:value={createBody}
        placeholder={"What's on your mind? Prefix with (AI) to involve the agent."}
        rows="5"
        maxlength="4000"
        required
      ></textarea>
      <div class="row">
        <span class="hint">{createBody.length} / 4000</span>
        {#if createError}<span class="error">{createError}</span>{/if}
        <button type="submit" class="primary" disabled={createBusy}>
          {createBusy ? 'saving…' : 'save'}
        </button>
      </div>
    </form>
  {/if}

  {#if loading}
    <p class="status">loading…</p>
  {:else if loadError}
    <p class="status error">{loadError}</p>
  {:else if filteredMemos.length === 0}
    <p class="status">
      {memos.length === 0
        ? 'No memos on this Palm yet. Add one above or push from MemoPad via vellum-sync.'
        : `Nothing in ${filter === 'ai' ? 'AI' : filter} for now.`}
    </p>
  {:else}
    <ul class="list">
      {#each filteredMemos as m (m.id)}
        <li class="item" class:ai-item={isAI(m) || isAgentMemo(m)}>
          {#if editingId === m.id}
            <div class="edit-form">
              <textarea bind:value={editBody} rows="5" maxlength="4000"></textarea>
              <div class="row">
                <button class="primary" onclick={() => saveEdit(m)} disabled={editBusy}>
                  {editBusy ? 'saving…' : 'save'}
                </button>
                <button onclick={cancelEdit}>cancel</button>
              </div>
            </div>
          {:else}
            <header class="item-h">
              <span class="tag tag-{m.type}">
                {isUploadMemo(m)
                  ? 'upload'
                  : isAI(m)
                    ? 'AI Q'
                    : isAgentMemo(m)
                      ? 'agent'
                      : 'note'}
              </span>
              {#if isUploadMemo(m)}
                <span class="upload-name" title={uploadFilenameOf(m)}>{uploadFilenameOf(m)}</span>
              {/if}
              {#if m.ai_status === 'pending' || m.ai_status === 'processing'}
                <span class="pending">[...] {isUploadMemo(m) ? 'reading file' : isAgentMemo(m) ? 'agent working' : 'AI parsing'}</span>
              {:else if m.ai_status === 'done' && m.ai_response}
                <span class="answered">[done] answered</span>
              {:else if m.ai_status === 'done' && isAgentMemo(m)}
                <span class="answered">[done] agent</span>
              {:else if m.ai_status === 'done' && isUploadMemo(m)}
                <span class="answered">[done] summary ready</span>
              {:else if m.ai_status === 'error'}
                <span class="errored">[err] AI error</span>
              {/if}
              <time>{fmtTime(m.updated_at)}</time>
              <button class="link" onclick={() => startEdit(m)}>edit</button>
              <button class="link danger" onclick={() => deleteMemo(m)}>×</button>
            </header>
            <pre class="body">{m.body}</pre>
            {#if m.ai_response}
              <div class="ai-resp">
                <div class="ai-resp-label">-- AI --</div>
                <pre>{m.ai_response}</pre>
              </div>
            {/if}
          {/if}
        </li>
      {/each}
    </ul>
  {/if}
</section>

<style>
  .memopad {
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
    font-family: inherit;
    font-size: 0.85rem;
    cursor: pointer;
    text-transform: lowercase;
  }
  .filters button.active {
    border-color: var(--accent);
    color: var(--accent);
    background: var(--bg);
  }
  .pending-dot {
    color: #ffaf60;
    margin-left: 0.3rem;
  }
  .add,
  .primary {
    background: var(--accent);
    color: var(--bg);
    border: 1px solid var(--accent);
    padding: 0.4rem 0.9rem;
    font-family: inherit;
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

  .create {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 1rem 1.1rem;
    margin-bottom: 1rem;
    display: grid;
    gap: 0.6rem;
    border-radius: 2px;
  }
  .ai-hint {
    margin: 0;
    font-size: 0.82rem;
    color: var(--ink-mute);
    line-height: 1.45;
  }
  code {
    background: var(--surface);
    padding: 0.05rem 0.3rem;
    border: 1px solid var(--line);
    color: var(--accent);
    font-size: 0.8rem;
  }
  textarea {
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.55rem 0.7rem;
    font-family: inherit;
    font-size: 0.95rem;
    resize: vertical;
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
  .hint {
    color: var(--ink-mute);
    font-size: 0.8rem;
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
    gap: 0.7rem;
  }
  .item {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 0.9rem 1.1rem;
    border-radius: 2px;
  }
  .item.ai-item {
    border-left: 3px solid var(--accent);
  }
  .upload {
    background: var(--surface-lo);
    border: 1px dashed var(--line);
    padding: 0.9rem 1rem;
    margin-bottom: 1rem;
    text-align: center;
    cursor: pointer;
    color: var(--ink-mute);
    border-radius: 2px;
    transition: border-color 0.1s, background 0.1s, color 0.1s;
  }
  .upload:hover,
  .upload.drag-over {
    border-color: var(--accent);
    color: var(--accent);
    background: var(--surface);
  }
  .upload.busy {
    opacity: 0.6;
    cursor: progress;
  }
  .upload p {
    margin: 0;
    font-size: 0.88rem;
  }
  .upload .upload-line strong {
    color: var(--ink);
  }
  .upload-name {
    color: var(--ink-mute);
    font-size: 0.75rem;
    max-width: 220px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .item-h {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    margin-bottom: 0.5rem;
    flex-wrap: wrap;
    font-size: 0.8rem;
  }
  .tag {
    font-size: 0.7rem;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    padding: 0.1rem 0.4rem;
    border: 1px solid var(--line);
    color: var(--ink-mute);
  }
  .tag-aiquery {
    color: var(--accent);
    border-color: var(--accent-dim);
  }
  .pending {
    color: #ffaf60;
  }
  .answered {
    color: #6dd581;
  }
  .errored {
    color: #ff6b6b;
  }
  time {
    color: var(--ink-mute);
  }
  .item-h .link {
    margin-left: auto;
  }
  .item-h .link.danger {
    margin-left: 0;
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
  pre.body,
  .ai-resp pre {
    margin: 0;
    font-family: inherit;
    font-size: 0.92rem;
    color: var(--ink);
    white-space: pre-wrap;
    word-break: break-word;
    line-height: 1.5;
  }
  .ai-resp {
    margin-top: 0.7rem;
    padding-top: 0.7rem;
    border-top: 1px dashed var(--line);
  }
  @media (max-width: 600px) {
    .head {
      flex-wrap: wrap;
    }
    .filters {
      flex: 1 1 100%;
      flex-wrap: wrap;
      justify-content: flex-start;
    }
    .add {
      order: -1;
      flex: 1 1 100%;
    }
    .upload p {
      font-size: 0.82rem;
    }
    .item-h {
      gap: 0.4rem;
    }
    .item-h time {
      flex: 1 1 100%;
      order: 5;
    }
    .item-h .link {
      margin-left: 0;
    }
  }
  .ai-resp-label {
    font-size: 0.7rem;
    color: var(--accent);
    text-transform: uppercase;
    letter-spacing: 0.05em;
    margin-bottom: 0.3rem;
  }
  .edit-form {
    display: grid;
    gap: 0.6rem;
  }
  .edit-form .row button {
    background: var(--surface);
    color: var(--ink);
    border: 1px solid var(--line);
    padding: 0.4rem 0.9rem;
    font-family: inherit;
    cursor: pointer;
  }
</style>

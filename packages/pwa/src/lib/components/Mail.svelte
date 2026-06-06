<script lang="ts">
  /**
   * <Mail />
   *
   * Inbox + source manager for the mail digest pipeline:
   *   - mail_sources rows hold each subscription (name, URL,
   *     local fetch_time, timezone, enabled, optional digest_hint).
   *   - pg_cron sweeper fires the fetch-mail-source Edge Function
   *     when a source's local fetch_time has passed today.
   *   - The Edge Function summarizes the page and INSERTs a
   *     records.type='mail' row that appears in the inbox below.
   */
  import { supabase } from '$lib/supabase';
  import { authState } from '$lib/auth.svelte';
  import { newUlid } from '$lib/ulid';

  interface MailSource {
    id: string;
    user_id: string;
    name: string;
    url: string;
    fetch_time: string; // "HH:MM:SS"
    timezone: string;
    enabled: boolean;
    last_fetched_at: string | null;
    last_error: string | null;
    digest_hint: string | null;
    created_at: string;
    updated_at: string;
  }

  interface MailMeta {
    mail_subject?: string;
    mail_from?: string;
    mail_source_id?: string;
    mail_source_name?: string;
    mail_source_url?: string;
    mail_fetched_at?: string;
    mail_date_local?: string;
  }

  interface MailRecord {
    id: string;
    user_id: string;
    type: 'mail';
    body: string | null;
    ai_status: string | null;
    metadata: MailMeta | null;
    created_at: string;
    updated_at: string;
  }

  let sources = $state<MailSource[]>([]);
  let mails = $state<MailRecord[]>([]);
  let loading = $state(true);
  let loadError = $state<string | null>(null);

  let showSources = $state(true);

  // Add-source form state
  let showAdd = $state(false);
  let fName = $state('');
  let fUrl = $state('');
  let fTime = $state('07:00');
  let fTimezone = $state(
    (typeof Intl !== 'undefined' && Intl.DateTimeFormat().resolvedOptions().timeZone) ||
      'UTC',
  );
  let fHint = $state('');
  let addBusy = $state(false);
  let addError = $state<string | null>(null);

  // Inline edit of a source
  let editingSrcId = $state<string | null>(null);
  let editName = $state('');
  let editUrl = $state('');
  let editTime = $state('07:00');
  let editTimezone = $state('UTC');
  let editHint = $state('');
  let editEnabled = $state(true);

  // Open mail
  let activeMailId = $state<string | null>(null);

  // Manual fetch state per source
  let fetchingIds = $state<Set<string>>(new Set());

  async function loadAll() {
    if (!authState.userId) return;
    loading = true;
    loadError = null;
    const [sRes, mRes] = await Promise.all([
      supabase.from('mail_sources').select('*').order('created_at', { ascending: true }),
      supabase
        .from('records')
        .select('*')
        .eq('type', 'mail')
        .is('deleted_at', null)
        .order('created_at', { ascending: false })
        .limit(200),
    ]);
    loading = false;
    if (sRes.error) {
      loadError = sRes.error.message;
      return;
    }
    if (mRes.error) {
      loadError = mRes.error.message;
      return;
    }
    sources = (sRes.data ?? []) as MailSource[];
    mails = (mRes.data ?? []) as MailRecord[];
  }

  async function addSource() {
    if (!authState.userId) return;
    if (!fName.trim() || !fUrl.trim()) {
      addError = 'name and URL required';
      return;
    }
    try {
      new URL(fUrl.trim());
    } catch {
      addError = 'URL must be a full https://… address';
      return;
    }
    addError = null;
    addBusy = true;
    const { error } = await supabase.from('mail_sources').insert({
      id: newUlid(),
      user_id: authState.userId,
      name: fName.trim(),
      url: fUrl.trim(),
      fetch_time: fTime + ':00',
      timezone: fTimezone,
      enabled: true,
      digest_hint: fHint.trim() || null,
    });
    addBusy = false;
    if (error) {
      addError = error.message;
      return;
    }
    fName = '';
    fUrl = '';
    fTime = '07:00';
    fHint = '';
    showAdd = false;
    await loadAll();
  }

  function startEdit(s: MailSource) {
    editingSrcId = s.id;
    editName = s.name;
    editUrl = s.url;
    editTime = s.fetch_time.slice(0, 5);
    editTimezone = s.timezone;
    editHint = s.digest_hint ?? '';
    editEnabled = s.enabled;
  }

  function cancelEdit() {
    editingSrcId = null;
  }

  async function saveEdit() {
    if (!editingSrcId) return;
    if (!editName.trim() || !editUrl.trim()) return;
    try {
      new URL(editUrl.trim());
    } catch {
      alert('URL must be a full https://… address');
      return;
    }
    const { error } = await supabase
      .from('mail_sources')
      .update({
        name: editName.trim(),
        url: editUrl.trim(),
        fetch_time: editTime + ':00',
        timezone: editTimezone,
        enabled: editEnabled,
        digest_hint: editHint.trim() || null,
      })
      .eq('id', editingSrcId);
    if (error) {
      alert(error.message);
      return;
    }
    editingSrcId = null;
    await loadAll();
  }

  async function toggleEnabled(s: MailSource) {
    await supabase.from('mail_sources').update({ enabled: !s.enabled }).eq('id', s.id);
    await loadAll();
  }

  async function deleteSource(s: MailSource) {
    if (!confirm(`Stop following "${s.name}"? Existing digests stay in your inbox.`))
      return;
    const { error } = await supabase.from('mail_sources').delete().eq('id', s.id);
    if (error) {
      alert(error.message);
      return;
    }
    if (editingSrcId === s.id) editingSrcId = null;
    await loadAll();
  }

  async function fetchNow(s: MailSource) {
    if (fetchingIds.has(s.id)) return;
    fetchingIds = new Set(fetchingIds).add(s.id);
    try {
      const { error } = await supabase.functions.invoke('fetch-mail-source', {
        body: { source_id: s.id },
      });
      if (error) alert('fetch failed: ' + error.message);
    } finally {
      const next = new Set(fetchingIds);
      next.delete(s.id);
      fetchingIds = next;
      await loadAll();
    }
  }

  async function deleteMail(m: MailRecord) {
    if (!confirm('Delete this mail?')) return;
    const { error } = await supabase
      .from('records')
      .update({ deleted_at: new Date().toISOString() })
      .eq('id', m.id);
    if (error) {
      alert(error.message);
      return;
    }
    if (activeMailId === m.id) activeMailId = null;
    await loadAll();
  }

  function openMail(m: MailRecord) {
    activeMailId = m.id;
  }

  function closeMail() {
    activeMailId = null;
  }

  function fmtTime(s: string | null): string {
    if (!s) return 'never';
    return new Date(s).toLocaleString();
  }

  function fmtDateOnly(s: string): string {
    return new Date(s).toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    });
  }

  function subjectOf(m: MailRecord): string {
    return m.metadata?.mail_subject ?? '(no subject)';
  }
  function fromOf(m: MailRecord): string {
    return m.metadata?.mail_from ?? m.metadata?.mail_source_name ?? 'unknown';
  }

  const activeMail = $derived(
    activeMailId ? mails.find((m) => m.id === activeMailId) ?? null : null,
  );

  $effect(() => {
    if (authState.phase === 'ready') void loadAll();
  });

  $effect(() => {
    const ch1 = supabase
      .channel('mail-records')
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'records' },
        async (payload) => {
          const row = (payload.new ?? payload.old) as { type?: string };
          if (row?.type !== 'mail') return;
          await loadAll();
        },
      )
      .subscribe();
    const ch2 = supabase
      .channel('mail-sources')
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'mail_sources' },
        async () => {
          await loadAll();
        },
      )
      .subscribe();
    return () => {
      ch1.unsubscribe();
      ch2.unsubscribe();
    };
  });
</script>

<section class="mail">
  <header class="head">
    <h2>mail</h2>
    <p class="sub">
      add a URL, pick a time, and the AI delivers a daily digest to your
      Palm's mail every morning. cron checks every 5 min; missed days
      retry automatically.
    </p>
  </header>

  <section class="sources">
    <header class="sec-h">
      <button class="link" onclick={() => (showSources = !showSources)} aria-expanded={showSources}>
        {showSources ? '▼' : '▶'} sources ({sources.length})
      </button>
      <button class="add" onclick={() => (showAdd = !showAdd)}>
        {showAdd ? 'cancel' : '+ add source'}
      </button>
    </header>

    {#if showAdd}
      <form class="src-form" onsubmit={(e) => { e.preventDefault(); void addSource(); }}>
        <div class="row-2">
          <label>
            <span>name</span>
            <input bind:value={fName} placeholder="Hacker News front page" maxlength="120" required />
          </label>
          <label>
            <span>URL</span>
            <input bind:value={fUrl} type="url" placeholder="https://news.ycombinator.com/" required />
          </label>
        </div>
        <div class="row-3">
          <label>
            <span>fetch time</span>
            <input type="time" bind:value={fTime} />
          </label>
          <label>
            <span>timezone</span>
            <input bind:value={fTimezone} list="tz-suggest" placeholder="Asia/Hong_Kong" />
          </label>
          <label>
            <span>&nbsp;</span>
            <button type="submit" class="primary" disabled={addBusy}>
              {addBusy ? 'adding…' : 'add'}
            </button>
          </label>
        </div>
        <label>
          <span>digest hint <span class="optional">(optional — tell AI what to focus on)</span></span>
          <textarea
            bind:value={fHint}
            rows="2"
            maxlength="500"
            placeholder="e.g. 'Focus on TypeScript and Rust stories, skip crypto.'"
          ></textarea>
        </label>
        {#if addError}<p class="error">{addError}</p>{/if}
      </form>
      <datalist id="tz-suggest">
        <option value="Asia/Hong_Kong" />
        <option value="Asia/Taipei" />
        <option value="Asia/Tokyo" />
        <option value="Asia/Singapore" />
        <option value="America/Los_Angeles" />
        <option value="America/New_York" />
        <option value="Europe/London" />
        <option value="UTC" />
      </datalist>
    {/if}

    {#if showSources}
      {#if sources.length === 0}
        <p class="empty">no sources yet — add one above.</p>
      {:else}
        <ul class="src-list">
          {#each sources as s (s.id)}
            <li class="src" class:disabled={!s.enabled}>
              {#if editingSrcId === s.id}
                <form class="src-form inline" onsubmit={(e) => { e.preventDefault(); void saveEdit(); }}>
                  <div class="row-2">
                    <label><span>name</span><input bind:value={editName} required /></label>
                    <label><span>URL</span><input type="url" bind:value={editUrl} required /></label>
                  </div>
                  <div class="row-3">
                    <label><span>fetch time</span><input type="time" bind:value={editTime} /></label>
                    <label><span>timezone</span><input bind:value={editTimezone} /></label>
                    <label class="check">
                      <input type="checkbox" bind:checked={editEnabled} /> enabled
                    </label>
                  </div>
                  <label><span>hint</span><textarea rows="2" bind:value={editHint}></textarea></label>
                  <div class="row">
                    <button type="button" onclick={cancelEdit}>cancel</button>
                    <button class="primary" type="submit">save</button>
                  </div>
                </form>
              {:else}
                <div class="src-main">
                  <div class="src-meta">
                    <div class="src-name">{s.name}</div>
                    <div class="src-url">
                      <a href={s.url} target="_blank" rel="noopener">{s.url}</a>
                    </div>
                    <div class="src-when">
                      daily {s.fetch_time.slice(0, 5)} {s.timezone} ·
                      last {fmtTime(s.last_fetched_at)}
                      {#if s.last_error}<span class="err">· [err] {s.last_error}</span>{/if}
                    </div>
                    {#if s.digest_hint}
                      <div class="src-hint">hint: {s.digest_hint}</div>
                    {/if}
                  </div>
                  <div class="src-actions">
                    <button class="ghost" onclick={() => fetchNow(s)} disabled={fetchingIds.has(s.id)}>
                      {fetchingIds.has(s.id) ? 'fetching…' : 'fetch now'}
                    </button>
                    <button class="ghost" onclick={() => toggleEnabled(s)}>
                      {s.enabled ? 'pause' : 'resume'}
                    </button>
                    <button class="link" onclick={() => startEdit(s)}>edit</button>
                    <button class="link danger" onclick={() => deleteSource(s)}>delete</button>
                  </div>
                </div>
              {/if}
            </li>
          {/each}
        </ul>
      {/if}
    {/if}
  </section>

  <section class="inbox">
    <header class="sec-h">
      <h3>inbox ({mails.length})</h3>
    </header>
    {#if loading}
      <p class="status">loading…</p>
    {:else if loadError}
      <p class="status error">{loadError}</p>
    {:else if mails.length === 0}
      <p class="status">no mail yet. add a source + give it a few minutes.</p>
    {:else}
      <ul class="mail-list">
        {#each mails as m (m.id)}
          <li>
            <button class="mail-row" type="button" onclick={() => openMail(m)}>
              <span class="from">{fromOf(m)}</span>
              <span class="subj">{subjectOf(m)}</span>
              <span class="date">{fmtDateOnly(m.created_at)}</span>
            </button>
          </li>
        {/each}
      </ul>
    {/if}
  </section>

  {#if activeMail}
    <div
      class="modal-bg"
      onclick={closeMail}
      onkeydown={(e) => e.key === 'Escape' && closeMail()}
      role="button"
      tabindex="-1"
      aria-label="close"
    >
      <article
        class="modal"
        role="dialog"
        aria-modal="true"
        tabindex="0"
        onclick={(e) => e.stopPropagation()}
        onkeydown={(e) => e.stopPropagation()}
      >
        <header class="modal-h">
          <div class="m-from">
            from <strong>{fromOf(activeMail)}</strong>
            {#if activeMail.metadata?.mail_source_url}
              · <a href={activeMail.metadata.mail_source_url} target="_blank" rel="noopener">source</a>
            {/if}
          </div>
          <button class="close" onclick={closeMail} aria-label="close">×</button>
        </header>
        <h3 class="m-subj">{subjectOf(activeMail)}</h3>
        <div class="m-date">{fmtTime(activeMail.created_at)}</div>
        <pre class="m-body">{activeMail.body ?? ''}</pre>
        <footer class="m-foot">
          <button class="del" onclick={() => deleteMail(activeMail!)}>delete</button>
        </footer>
      </article>
    </div>
  {/if}
</section>

<style>
  .mail {
    max-width: 900px;
  }
  .head {
    margin-bottom: 1.2rem;
  }
  h2 {
    margin: 0 0 0.2rem;
    font-size: 1.1rem;
    color: var(--accent);
    text-transform: lowercase;
    letter-spacing: 0.05em;
  }
  .sub {
    margin: 0;
    color: var(--ink-mute);
    font-size: 0.85rem;
  }

  .sources,
  .inbox {
    margin-bottom: 1.4rem;
  }
  .sec-h {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 0.7rem;
  }
  .sec-h h3 {
    margin: 0;
    font-size: 0.95rem;
    color: var(--accent);
    text-transform: lowercase;
    letter-spacing: 0.05em;
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
    white-space: nowrap;
  }
  .add:hover,
  .primary:hover:not(:disabled) {
    background: var(--accent-dim);
  }
  .primary:disabled,
  .ghost:disabled {
    opacity: 0.6;
  }
  .ghost {
    background: var(--surface);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.3rem 0.65rem;
    font: inherit;
    font-size: 0.8rem;
    cursor: pointer;
  }
  .ghost:hover:not(:disabled) {
    border-color: var(--accent);
    color: var(--accent);
  }

  .link {
    background: none;
    border: none;
    color: var(--ink-mute);
    font: inherit;
    font-size: 0.85rem;
    cursor: pointer;
    padding: 0;
  }
  .link:hover {
    color: var(--accent);
  }
  .link.danger:hover {
    color: #ff6b6b;
  }

  .src-form {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 1rem 1.1rem;
    margin-bottom: 0.7rem;
    display: grid;
    gap: 0.6rem;
    border-radius: 2px;
  }
  .src-form.inline {
    margin: 0;
  }
  .src-form label {
    display: grid;
    gap: 0.2rem;
    font-size: 0.8rem;
    color: var(--ink-mute);
  }
  .src-form input,
  .src-form textarea {
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.4rem 0.55rem;
    font: inherit;
    font-size: 0.9rem;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    justify-content: flex-end;
  }
  .row button:not(.primary) {
    background: var(--surface);
    color: var(--ink);
    border: 1px solid var(--line);
    padding: 0.4rem 0.9rem;
    font: inherit;
    cursor: pointer;
  }
  .row-2 {
    display: grid;
    grid-template-columns: 1fr 2fr;
    gap: 0.7rem;
  }
  .row-3 {
    display: grid;
    grid-template-columns: auto 1fr auto;
    gap: 0.7rem;
    align-items: end;
  }
  .check {
    display: flex;
    align-items: center;
    gap: 0.4rem;
    color: var(--ink);
  }
  .optional {
    color: var(--line);
  }

  .empty,
  .status {
    color: var(--ink-mute);
    padding: 1rem;
    text-align: center;
  }
  .status.error,
  .error {
    color: #ff6b6b;
    font-size: 0.85rem;
  }

  .src-list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 0.5rem;
  }
  .src {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 0.7rem 1rem;
    border-radius: 2px;
  }
  .src.disabled {
    opacity: 0.6;
  }
  .src-main {
    display: flex;
    justify-content: space-between;
    gap: 0.8rem;
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .src-meta {
    min-width: 0;
    flex: 1;
  }
  .src-name {
    font-weight: 600;
    color: var(--ink);
  }
  .src-url {
    font-size: 0.8rem;
    margin-top: 0.1rem;
  }
  .src-url a {
    color: var(--accent);
    text-decoration: none;
    word-break: break-all;
  }
  .src-when {
    font-size: 0.75rem;
    color: var(--ink-mute);
    margin-top: 0.2rem;
  }
  .err {
    color: #ff6b6b;
  }
  .src-hint {
    font-size: 0.78rem;
    color: var(--ink-mute);
    margin-top: 0.25rem;
    font-style: italic;
  }
  .src-actions {
    display: flex;
    gap: 0.4rem;
    flex-wrap: wrap;
    align-items: center;
  }

  .mail-list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 0.2rem;
  }
  .mail-row {
    width: 100%;
    background: var(--surface-lo);
    border: 1px solid var(--line);
    color: var(--ink);
    text-align: left;
    font: inherit;
    padding: 0.55rem 0.85rem;
    cursor: pointer;
    display: grid;
    grid-template-columns: minmax(120px, auto) 1fr auto;
    gap: 0.8rem;
    align-items: baseline;
    border-radius: 2px;
  }
  .mail-row:hover {
    border-color: var(--accent);
  }
  .from {
    color: var(--ink-mute);
    font-size: 0.85rem;
  }
  .subj {
    color: var(--ink);
    font-weight: 500;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .date {
    color: var(--ink-mute);
    font-size: 0.78rem;
  }

  .modal-bg {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.7);
    display: grid;
    place-items: center;
    padding: 1rem;
    z-index: 100;
  }
  .modal {
    background: var(--bg);
    border: 1px solid var(--line);
    max-width: 720px;
    width: 100%;
    max-height: 90vh;
    overflow: auto;
    padding: 1.2rem 1.4rem;
    border-radius: 2px;
  }
  .modal-h {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    padding-bottom: 0.5rem;
    border-bottom: 1px solid var(--line);
    margin-bottom: 0.7rem;
  }
  .m-from {
    color: var(--ink-mute);
    font-size: 0.85rem;
  }
  .m-from a {
    color: var(--accent);
  }
  .close {
    background: none;
    border: none;
    color: var(--ink-mute);
    font-size: 1.4rem;
    cursor: pointer;
    line-height: 1;
    padding: 0;
    width: 1.6rem;
    height: 1.6rem;
  }
  .close:hover {
    color: var(--accent);
  }
  .m-subj {
    margin: 0.3rem 0 0.2rem;
    font-size: 1.1rem;
    color: var(--ink);
  }
  .m-date {
    color: var(--ink-mute);
    font-size: 0.8rem;
    margin-bottom: 0.8rem;
  }
  .m-body {
    margin: 0;
    font-family: inherit;
    font-size: 0.95rem;
    line-height: 1.55;
    color: var(--ink);
    white-space: pre-wrap;
    word-break: break-word;
  }
  .m-foot {
    margin-top: 1rem;
    padding-top: 0.8rem;
    border-top: 1px solid var(--line);
    display: flex;
    justify-content: flex-end;
  }
  .del {
    background: none;
    border: 1px solid var(--line);
    color: var(--ink-mute);
    padding: 0.35rem 0.8rem;
    font: inherit;
    font-size: 0.8rem;
    cursor: pointer;
  }
  .del:hover {
    border-color: #ff6b6b;
    color: #ff6b6b;
  }
</style>

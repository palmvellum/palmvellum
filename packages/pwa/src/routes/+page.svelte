<script lang="ts">
  import { onMount } from 'svelte';
  import { supabase } from '$lib/supabase';
  import { db, type LocalRecord } from '$lib/db';

  let records = $state<LocalRecord[]>([]);
  let loading = $state(true);
  let cloudError = $state<string | null>(null);
  let session = $state<{ email: string } | null>(null);

  // Initial paint from local Dexie cache (instant), then refresh from cloud.
  async function loadLocal() {
    records = await db.records.orderBy('updated_at').reverse().limit(50).toArray();
  }

  async function refreshFromCloud() {
    cloudError = null;
    const { data, error } = await supabase
      .from('records')
      .select('*')
      .order('updated_at', { ascending: false })
      .limit(50);

    if (error) {
      cloudError = error.message;
      return;
    }
    if (!data) return;

    // Replace local cache with the authoritative cloud rows.
    await db.records.clear();
    await db.records.bulkPut(data as LocalRecord[]);
    records = data as LocalRecord[];
  }

  async function captureSession() {
    const { data } = await supabase.auth.getSession();
    if (data.session?.user.email) {
      session = { email: data.session.user.email };
    }
  }

  // onMount cleanup must be synchronous in Svelte 5, so we keep the
  // channel handle in scope and return a sync teardown.
  let channelHandle: ReturnType<typeof supabase.channel> | null = null;

  onMount(() => {
    void (async () => {
      await captureSession();
      await loadLocal();
      await refreshFromCloud();
      loading = false;

      channelHandle = supabase
        .channel('records-feed')
        .on(
          'postgres_changes',
          { event: '*', schema: 'public', table: 'records' },
          async () => {
            await refreshFromCloud();
          },
        )
        .subscribe();
    })();

    return () => {
      if (channelHandle) {
        void supabase.removeChannel(channelHandle);
        channelHandle = null;
      }
    };
  });

  function fmtTime(s: string): string {
    const d = new Date(s);
    return d.toLocaleString();
  }
</script>

<svelte:head>
  <title>PalmVellum · records</title>
</svelte:head>

<header class="hdr">
  <div>
    <h1>PalmVellum</h1>
    <p class="sub">records browser · pre-alpha</p>
  </div>
  <div class="who">
    {#if session}
      <span class="ok">signed in · {session.email}</span>
    {:else}
      <span class="warn">not signed in — enrol via Supabase Studio to see records</span>
    {/if}
  </div>
</header>

{#if loading}
  <p class="loading">loading…</p>
{:else if cloudError}
  <section class="error">
    <h2>cloud error</h2>
    <pre>{cloudError}</pre>
    <p class="hint">
      RLS may be blocking — the publishable key only sees rows where
      <code>user_id = auth.uid()</code>. Sign in via Supabase Studio to
      seed a user, or write rows server-side first.
    </p>
  </section>
{:else if records.length === 0}
  <section class="empty">
    <p>No records yet.</p>
    <p class="hint">
      Insert one from the SQL editor to test the live update:
    </p>
    <pre><code>{`-- as the project owner in Supabase Studio, after auth.uid() exists:
INSERT INTO records (id, user_id, type, posture, body, source)
VALUES (
  '01HZZZZZZZZZZZZZZZZZZZZZZZ',
  auth.uid(),
  'thought',
  'open',
  'first PalmVellum record',
  'web'
);`}</code></pre>
  </section>
{:else}
  <section class="list">
    {#each records as r (r.id)}
      <article class={`row posture-${r.posture}`}>
        <header class="row-h">
          <span class="type">{r.type}</span>
          <span class="posture">{r.posture}</span>
          <time>{fmtTime(r.updated_at)}</time>
        </header>
        {#if r.body}
          <p class="body">{r.body}</p>
        {:else}
          <p class="body muted">⟨no body — vault tier⟩</p>
        {/if}
        {#if r.ai_status}
          <footer class={`ai ai-${r.ai_status}`}>
            <span class="badge">{r.ai_status}</span>
            {#if r.ai_response}
              <p class="reply">{r.ai_response}</p>
            {/if}
          </footer>
        {/if}
      </article>
    {/each}
  </section>
{/if}

<style>
  .hdr {
    display: flex;
    justify-content: space-between;
    align-items: flex-end;
    flex-wrap: wrap;
    gap: 1rem;
    padding-bottom: 1.5rem;
    border-bottom: 1px solid var(--line);
    margin-bottom: 2rem;
  }
  h1 {
    font-size: 1.6rem;
    font-weight: 600;
    margin: 0;
  }
  .sub {
    margin: 0.25rem 0 0;
    color: var(--ink-mute);
    font-size: 0.9rem;
  }
  .who {
    font-size: 0.85rem;
  }
  .ok {
    color: var(--green);
  }
  .warn {
    color: var(--ink-mute);
  }

  .loading,
  .empty,
  .error {
    padding: 1.5rem 0;
    color: var(--ink-dim);
  }
  .empty .hint,
  .error .hint {
    margin-top: 0.75rem;
    color: var(--ink-mute);
    font-size: 0.9rem;
  }
  pre {
    background: var(--surface-lo);
    border: 1px solid var(--line-soft);
    padding: 0.75rem;
    overflow-x: auto;
    font-size: 0.85rem;
    border-radius: 4px;
  }

  .list {
    display: grid;
    gap: 0.75rem;
  }
  .row {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    border-left: 3px solid var(--ink-mute);
    padding: 0.75rem 1rem;
    border-radius: 2px;
  }
  .row.posture-vault {
    border-left-color: #ff6b6b;
  }
  .row.posture-sealed {
    border-left-color: var(--accent);
  }
  .row.posture-open {
    border-left-color: var(--green);
  }
  .row-h {
    display: flex;
    gap: 0.75rem;
    align-items: baseline;
    font-size: 0.8rem;
    color: var(--ink-mute);
    margin-bottom: 0.5rem;
  }
  .type {
    color: var(--ink);
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }
  .posture {
    color: var(--accent);
  }
  time {
    margin-left: auto;
  }
  .body {
    margin: 0;
    color: var(--ink-dim);
    white-space: pre-wrap;
    word-break: break-word;
  }
  .body.muted {
    color: var(--ink-mute);
    font-style: italic;
  }
  .ai {
    margin-top: 0.6rem;
    padding-top: 0.5rem;
    border-top: 1px dashed var(--line-soft);
  }
  .badge {
    display: inline-block;
    font-size: 0.7rem;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    padding: 0.05rem 0.4rem;
    border-radius: 2px;
    background: var(--surface);
    color: var(--ink-mute);
  }
  .ai-pending .badge {
    color: var(--accent);
  }
  .ai-processing .badge {
    color: var(--accent);
    animation: pulse 1.4s ease-in-out infinite;
  }
  .ai-done .badge {
    color: var(--green);
  }
  .ai-error .badge {
    color: #ff6b6b;
  }
  .reply {
    margin: 0.4rem 0 0;
    color: var(--ink);
  }
  @keyframes pulse {
    50% {
      opacity: 0.45;
    }
  }
</style>

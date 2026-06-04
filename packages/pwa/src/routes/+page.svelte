<script lang="ts">
  import { onMount } from 'svelte';
  import { supabase } from '$lib/supabase';
  import { db, type LocalRecord } from '$lib/db';
  import { authState, magicLinkRedirect } from '$lib/auth.svelte';

  // ── Waitlist signup state ───────────────────────────────
  let waitlistEmail = $state('');
  let waitlistNote = $state('');
  let waitlistSubmitting = $state(false);
  let waitlistDone = $state(false);
  let waitlistError = $state<string | null>(null);

  async function submitWaitlist(e: Event) {
    e.preventDefault();
    waitlistError = null;
    waitlistSubmitting = true;
    const { error } = await supabase.from('waitlist').insert({
      email: waitlistEmail.trim().toLowerCase(),
      note: waitlistNote.trim() || null,
      referrer: typeof document !== 'undefined' ? document.referrer || null : null,
    });
    waitlistSubmitting = false;
    if (error) {
      waitlistError =
        error.code === '23505'
          ? "You're already on the list — we'll be in touch."
          : error.message;
      return;
    }
    waitlistDone = true;
  }

  // ── Sign-in (magic link) ────────────────────────────────
  let signinEmail = $state('');
  let signinSubmitting = $state(false);
  let signinSent = $state(false);
  let signinError = $state<string | null>(null);

  async function submitSignin(e: Event) {
    e.preventDefault();
    signinError = null;
    signinSubmitting = true;
    const { error } = await supabase.auth.signInWithOtp({
      email: signinEmail.trim().toLowerCase(),
      options: { emailRedirectTo: magicLinkRedirect() },
    });
    signinSubmitting = false;
    if (error) {
      signinError = error.message;
      return;
    }
    signinSent = true;
  }

  // ── Capture (authed) ────────────────────────────────────
  let captureBody = $state('');
  let captureType = $state<'aiquery' | 'thought' | 'todo'>('aiquery');
  let captureSubmitting = $state(false);
  let captureError = $state<string | null>(null);

  const CAPTURE_MODES: Array<{
    key: 'aiquery' | 'thought' | 'todo';
    label: string;
    placeholder: string;
    submitLabel: string;
  }> = [
    {
      key: 'aiquery',
      label: 'AI mode',
      placeholder: 'Ask the Oracle anything — answers come back in seconds.',
      submitLabel: 'ask',
    },
    {
      key: 'thought',
      label: 'thought',
      placeholder: "What's on your mind? Captured to your Palm — no AI.",
      submitLabel: 'save',
    },
    {
      key: 'todo',
      label: 'todo',
      placeholder: 'A single thing to do later.',
      submitLabel: 'add',
    },
  ];

  function captureMode() {
    return CAPTURE_MODES.find((m) => m.key === captureType) ?? CAPTURE_MODES[0]!;
  }

  function newUlid(): string {
    // Browser-safe ULID — matches packages/shared-schema/src/ulid.ts
    const ts = BigInt(Date.now());
    const ENC = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
    const tsB = ts.toString(2).padStart(48, '0');
    const rnd = crypto.getRandomValues(new Uint8Array(10));
    let rndB = '';
    for (const b of rnd) rndB += b.toString(2).padStart(8, '0');
    const bits = tsB + rndB; // 128
    let out = '';
    for (let i = 0; i < 26; i++) {
      const slice = bits.slice(i * 5, i * 5 + 5).padEnd(5, '0');
      out += ENC[parseInt(slice, 2)];
    }
    return out;
  }

  async function submitCapture(e: Event) {
    e.preventDefault();
    if (!authState.userId) return;
    captureError = null;
    captureSubmitting = true;
    const body = captureBody.trim();
    const { error } = await supabase.from('records').insert({
      id: newUlid(),
      user_id: authState.userId,
      type: captureType,
      posture: 'open',
      body,
      source: 'web',
      ai_status: captureType === 'aiquery' ? 'pending' : null,
    });
    captureSubmitting = false;
    if (error) {
      captureError = error.message;
      return;
    }
    captureBody = '';
    await refreshFromCloud();
  }

  // ── Records list (authed) ───────────────────────────────
  let records = $state<LocalRecord[]>([]);
  let recordsLoading = $state(false);
  let recordsError = $state<string | null>(null);

  async function refreshFromCloud() {
    recordsError = null;
    const { data, error } = await supabase
      .from('records')
      .select('*')
      .order('updated_at', { ascending: false })
      .limit(50);
    if (error) {
      recordsError = error.message;
      return;
    }
    if (data) {
      // Take the plain Supabase rows BEFORE handing them to $state.
      // Once they're assigned to `records` (which is a reactive
      // $state array) they get wrapped in a Proxy; passing those
      // proxies to IndexedDB / Dexie blows up the structured-clone
      // algorithm with `DataCloneError: could not be cloned`.
      const fresh = data as LocalRecord[];
      records = fresh;
      try {
        await db.records.clear();
        await db.records.bulkPut(fresh);
      } catch (e) {
        console.warn('[PalmVellum] local cache write failed (non-fatal):', e);
      }
    }
  }

  let channelHandle: ReturnType<typeof supabase.channel> | null = null;

  $effect(() => {
    if (authState.phase !== 'ready') {
      channelHandle?.unsubscribe();
      channelHandle = null;
      return;
    }

    let cancelled = false;

    (async () => {
      recordsLoading = true;
      try {
        try {
          const local = await db.records
            .orderBy('updated_at')
            .reverse()
            .limit(50)
            .toArray();
          if (!cancelled) records = local;
        } catch (e) {
          console.error('[PalmVellum] local cache load failed:', e);
        }
        try {
          await refreshFromCloud();
        } catch (e) {
          console.error('[PalmVellum] cloud refresh failed:', e);
          if (!cancelled)
            recordsError = e instanceof Error ? e.message : String(e);
        }
      } finally {
        if (!cancelled) recordsLoading = false;
      }

      if (cancelled) return;

      channelHandle = supabase
        .channel('records-feed')
        .on(
          'postgres_changes',
          { event: '*', schema: 'public', table: 'records' },
          async () => {
            try {
              await refreshFromCloud();
            } catch (e) {
              console.error('[PalmVellum] realtime refresh failed:', e);
            }
          },
        )
        .subscribe();
    })();

    return () => {
      cancelled = true;
      channelHandle?.unsubscribe();
      channelHandle = null;
    };
  });

  function fmtTime(s: string): string {
    return new Date(s).toLocaleString();
  }
</script>

<svelte:head>
  <title>PalmVellum · web companion</title>
</svelte:head>

{#if authState.phase === 'loading'}
  <p class="loading">loading…</p>

{:else if authState.phase === 'unauthenticated'}
  <!-- Waitlist + sign-in side by side -->
  <section class="hero">
    <h1>The web companion for your Palm.</h1>
    <p>
      Live records, AI Oracle responses, and Palm sync — all in one
      browser tab. Bring your own OpenAI or Anthropic key (free
      forever), or join the waitlist for the paid platform tier.
    </p>
  </section>

  <div class="split">
    <section class="card">
      <h2>Join the waitlist</h2>
      {#if waitlistDone}
        <p class="ok">✓ On the list. We'll email when invites open.</p>
      {:else}
        <form onsubmit={submitWaitlist}>
          <label>
            email
            <input
              type="email"
              bind:value={waitlistEmail}
              required
              placeholder="you@example.com"
            />
          </label>
          <label>
            tell us about yourself <span class="optional">(optional)</span>
            <textarea
              bind:value={waitlistNote}
              rows="3"
              placeholder="Which Palm do you have? What do you want to use it for?"
            ></textarea>
          </label>
          {#if waitlistError}
            <p class="error">{waitlistError}</p>
          {/if}
          <button type="submit" disabled={waitlistSubmitting}>
            {waitlistSubmitting ? 'submitting…' : 'join waitlist'}
          </button>
        </form>
      {/if}
    </section>

    <section class="card">
      <h2>Already invited?</h2>
      {#if signinSent}
        <p class="ok">✓ Magic link sent to {signinEmail}. Check your inbox.</p>
      {:else}
        <form onsubmit={submitSignin}>
          <label>
            email
            <input
              type="email"
              bind:value={signinEmail}
              required
              placeholder="you@example.com"
            />
          </label>
          {#if signinError}
            <p class="error">{signinError}</p>
          {/if}
          <button type="submit" disabled={signinSubmitting}>
            {signinSubmitting ? 'sending…' : 'send magic link'}
          </button>
        </form>
      {/if}
    </section>
  </div>

{:else if authState.phase === 'uninvited'}
  <section class="card center">
    <h2>You're signed in.</h2>
    <p>
      <strong>{authState.email}</strong> hasn't been invited yet. We'll send
      another email when your seat opens up.
    </p>
    <p class="hint">
      If you weren't expecting this, you may have signed in with the wrong
      email. <a href="#sign-out" onclick={(e) => { e.preventDefault(); void authState.signOut(); }}>Sign out</a>
      and try again.
    </p>
  </section>

{:else}
  <!-- Authenticated + invited: capture form + records list -->
  <section class="capture-card">
    <div class="tabs" role="tablist" aria-label="Capture mode">
      {#each CAPTURE_MODES as m (m.key)}
        <button
          type="button"
          role="tab"
          aria-selected={captureType === m.key}
          class:active={captureType === m.key}
          onclick={() => (captureType = m.key)}
        >
          {m.label}
        </button>
      {/each}
    </div>

    <form onsubmit={submitCapture}>
      <textarea
        bind:value={captureBody}
        rows="3"
        placeholder={captureMode().placeholder}
        required
      ></textarea>
      <div class="capture-row">
        {#if captureError}
          <span class="error">{captureError}</span>
        {/if}
        <button type="submit" disabled={captureSubmitting}>
          {captureSubmitting ? 'sending…' : captureMode().submitLabel}
        </button>
      </div>
    </form>
  </section>

  <h2 class="list-title">records</h2>
  {#if recordsLoading}
    <p class="loading">loading…</p>
  {:else if recordsError}
    <section class="error-block">
      <p>{recordsError}</p>
    </section>
  {:else if records.length === 0}
    <p class="empty">No records yet. Capture something above ↑</p>
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
{/if}

<style>
  .hero {
    margin-bottom: 1.5rem;
  }
  .hero h1 {
    font-size: 1.6rem;
    font-weight: 600;
    margin: 0 0 0.5rem;
  }
  .hero p {
    color: var(--ink-dim);
    max-width: 56ch;
  }

  .split {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 1rem;
  }
  @media (max-width: 640px) {
    .split {
      grid-template-columns: 1fr;
    }
  }
  .card,
  .capture-card,
  .center,
  .error-block {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 1rem 1.1rem;
    border-radius: 2px;
  }
  .card h2,
  .capture-card h2 {
    font-size: 1rem;
    margin: 0 0 0.75rem;
    color: var(--accent);
    text-transform: lowercase;
    letter-spacing: 0.05em;
  }
  .center {
    text-align: center;
    max-width: 480px;
    margin: 2rem auto;
  }
  form {
    display: grid;
    gap: 0.6rem;
  }
  label {
    display: grid;
    gap: 0.25rem;
    color: var(--ink-mute);
    font-size: 0.85rem;
  }
  .optional {
    color: var(--line);
  }
  input,
  textarea,
  select {
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.5rem 0.6rem;
    font-family: inherit;
    font-size: 0.95rem;
  }
  textarea {
    resize: vertical;
    min-height: 3.5rem;
  }
  input:focus,
  textarea:focus,
  select:focus {
    outline: 1px solid var(--accent);
  }
  button {
    background: var(--accent);
    color: var(--bg);
    border: 1px solid var(--accent);
    padding: 0.55rem 1rem;
    font-family: inherit;
    font-weight: 600;
    cursor: pointer;
  }
  button:hover:not(:disabled) {
    background: var(--accent-dim);
  }
  button:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  .ok {
    color: var(--green);
  }
  .error {
    color: #ff6b6b;
    font-size: 0.85rem;
  }
  .hint {
    color: var(--ink-mute);
    font-size: 0.85rem;
    margin-top: 0.5rem;
  }

  .capture-card {
    margin-bottom: 1.5rem;
    padding: 0;
  }
  .tabs {
    display: flex;
    border-bottom: 1px solid var(--line);
  }
  .tabs button {
    flex: 1;
    background: transparent;
    color: var(--ink-mute);
    border: none;
    border-right: 1px solid var(--line);
    border-radius: 0;
    padding: 0.75rem 0.5rem;
    font-family: inherit;
    font-size: 0.9rem;
    font-weight: 500;
    cursor: pointer;
    text-transform: lowercase;
    letter-spacing: 0.05em;
  }
  .tabs button:last-child {
    border-right: none;
  }
  .tabs button:hover {
    color: var(--ink);
    background: var(--surface);
  }
  .tabs button.active {
    color: var(--accent);
    background: var(--bg);
    box-shadow: inset 0 -2px 0 var(--accent);
  }
  .capture-card form {
    padding: 1rem 1.1rem;
  }
  .capture-row {
    display: flex;
    gap: 0.6rem;
    align-items: center;
    flex-wrap: wrap;
    margin-top: 0.4rem;
  }
  .capture-row button {
    margin-left: auto;
    min-width: 6rem;
  }

  .list-title {
    font-size: 0.85rem;
    color: var(--ink-mute);
    text-transform: uppercase;
    letter-spacing: 0.06em;
    margin-bottom: 0.75rem;
  }
  .list {
    display: grid;
    gap: 0.75rem;
  }
  .loading,
  .empty {
    color: var(--ink-mute);
    padding: 0.8rem 0;
  }
  .row {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    border-left: 3px solid var(--ink-mute);
    padding: 0.75rem 1rem;
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

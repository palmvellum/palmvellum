<script lang="ts">
  import { supabase } from '$lib/supabase';
  import { authState } from '$lib/auth.svelte';
  import { base } from '$app/paths';
  import { goto } from '$app/navigation';

  // ── Settings: BYOK key paste ────────────────────────────
  let provider = $state<'openai' | 'anthropic'>('openai');
  let plaintext = $state('');
  let saving = $state(false);
  let saveOk = $state(false);
  let saveError = $state<string | null>(null);

  async function saveKey(e: Event) {
    e.preventDefault();
    saveError = null;
    saveOk = false;
    saving = true;
    const { error } = await supabase.rpc('store_user_api_key', {
      provider_name: provider,
      plaintext: plaintext.trim(),
    });
    saving = false;
    if (error) {
      saveError = error.message;
      return;
    }
    saveOk = true;
    plaintext = '';
    await authState.refreshSettings();
  }

  async function updatePreferredProvider(p: 'openai' | 'anthropic') {
    const { error } = await supabase
      .from('user_settings')
      .update({ preferred_provider: p })
      .eq('user_id', authState.userId!);
    if (!error) await authState.refreshSettings();
  }

  // ── Palm enrollment ─────────────────────────────────────
  let enrolling = $state(false);
  let enrollToken = $state<string | null>(null);
  let enrollError = $state<string | null>(null);

  async function enrollPalm() {
    enrolling = true;
    enrollError = null;
    const { data, error } = await supabase.rpc('enroll_palm');
    enrolling = false;
    if (error) {
      enrollError = error.message;
      return;
    }
    enrollToken = String(data);
    await authState.refreshSettings();
  }

  function copyToken() {
    if (enrollToken) {
      void navigator.clipboard.writeText(enrollToken);
    }
  }

  // Guard: redirect to / if not signed in
  $effect(() => {
    if (authState.phase === 'unauthenticated') {
      void goto(base + '/');
    }
  });
</script>

<svelte:head>
  <title>PalmVellum · settings</title>
</svelte:head>

{#if authState.phase === 'loading'}
  <p>loading…</p>
{:else if authState.phase === 'unauthenticated'}
  <p>redirecting…</p>
{:else if !authState.settings}
  <p>your settings row hasn't been created yet — try signing out and in again.</p>
{:else}
  <h1>settings</h1>
  <p class="hint">Account: {authState.email}</p>

  <!-- ── BYOK section ── -->
  <section class="card">
    <h2>API keys</h2>
    <p class="sub">
      Paste your OpenAI or Anthropic key. We push it straight into Supabase
      Vault — only the encrypted blob ever sits in the database. The
      worker decrypts it just before each Oracle call.
    </p>

    <div class="status">
      <div>
        <span class="label">openai</span>
        {#if authState.settings.openai_secret_id}
          <span class="ok">[ok] stored</span>
        {:else}
          <span class="warn">not set</span>
        {/if}
        <span class="muted">· model: {authState.settings.openai_model}</span>
      </div>
      <div>
        <span class="label">anthropic</span>
        {#if authState.settings.anthropic_secret_id}
          <span class="ok">[ok] stored</span>
        {:else}
          <span class="warn">not set</span>
        {/if}
        <span class="muted">· model: {authState.settings.anthropic_model}</span>
      </div>
      <div>
        <span class="label">preferred</span>
        <select
          value={authState.settings.preferred_provider}
          onchange={(e) => updatePreferredProvider((e.currentTarget as HTMLSelectElement).value as 'openai' | 'anthropic')}
        >
          <option value="openai">openai</option>
          <option value="anthropic">anthropic</option>
        </select>
      </div>
    </div>

    <form onsubmit={saveKey}>
      <label>
        provider
        <select bind:value={provider}>
          <option value="openai">openai (sk-…)</option>
          <option value="anthropic">anthropic (sk-ant-…)</option>
        </select>
      </label>
      <label>
        api key
        <input
          type="password"
          bind:value={plaintext}
          required
          autocomplete="off"
          spellcheck="false"
          placeholder="paste here — encrypted in Vault on submit"
        />
      </label>
      {#if saveError}
        <p class="error">{saveError}</p>
      {/if}
      {#if saveOk}
        <p class="ok">[ok] saved (encrypted to Vault)</p>
      {/if}
      <button type="submit" disabled={saving}>
        {saving ? 'storing…' : 'store key'}
      </button>
    </form>
  </section>

  <!-- ── Palm enrollment ── -->
  <section class="card">
    <h2>Palm enrollment</h2>
    {#if authState.settings.palm_enrolled}
      <p class="ok">
        [ok] A Palm is enrolled
        {#if authState.settings.palm_model}
          (<strong>{authState.settings.palm_model}</strong>)
        {/if}
      </p>
      <p class="muted">
        token issued {authState.settings.hotsync_token_issued_at &&
          new Date(authState.settings.hotsync_token_issued_at).toLocaleString()}
      </p>
      <p class="hint">
        Need to enroll a second Palm or rotate the token? Click below — the
        previous token is invalidated immediately.
      </p>
    {:else}
      <p>
        Generate a one-time HotSync token. Paste it into your Mac daemon's
        <code>.env</code> as <code>PALMVELLUM_HOTSYNC_TOKEN</code>. The
        daemon trades it for your user_id on startup and uses it for every
        sync from your Palm.
      </p>
    {/if}

    {#if enrollToken}
      <div class="token-box">
        <p class="muted">[!] shown once. copy it now.</p>
        <code class="token">{enrollToken}</code>
        <button type="button" onclick={copyToken}>copy to clipboard</button>
      </div>
    {/if}

    {#if enrollError}
      <p class="error">{enrollError}</p>
    {/if}

    <button type="button" onclick={enrollPalm} disabled={enrolling}>
      {enrolling ? 'minting…' : authState.settings.palm_enrolled
        ? 're-issue token'
        : 'enroll a Palm'}
    </button>
  </section>

  <!-- ── Credits + subscription ── -->
  <section class="card">
    <h2>credits</h2>
    <p>
      <strong>{authState.settings.credits_remaining}</strong> remaining,
      {authState.settings.credits_used_month} used this month.
      Subscription: <strong>{authState.settings.subscription_status}</strong>.
    </p>
    <p class="hint">
      BYOK keys are free forever. Platform credits (via Airwallex
      checkout, US$5 = 1000 credits) ship in v0.3.
    </p>
  </section>
{/if}

<style>
  h1 {
    font-size: 1.4rem;
    margin: 0 0 0.25rem;
  }
  .hint {
    color: var(--ink-mute);
    font-size: 0.85rem;
    margin-bottom: 1rem;
  }
  .card {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 1rem 1.1rem;
    margin-bottom: 1rem;
    border-radius: 2px;
  }
  h2 {
    font-size: 1rem;
    margin: 0 0 0.6rem;
    color: var(--accent);
    text-transform: lowercase;
    letter-spacing: 0.05em;
  }
  .sub {
    color: var(--ink-dim);
    margin: 0 0 0.8rem;
    font-size: 0.9rem;
  }
  .status {
    display: grid;
    gap: 0.4rem;
    font-size: 0.9rem;
    margin-bottom: 1rem;
    padding-bottom: 0.8rem;
    border-bottom: 1px dashed var(--line-soft);
  }
  .status .label {
    color: var(--ink-mute);
    display: inline-block;
    width: 6.5rem;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    font-size: 0.75rem;
  }
  .ok {
    color: var(--green);
  }
  .warn {
    color: var(--ink-mute);
  }
  .muted {
    color: var(--ink-mute);
    font-size: 0.85rem;
    margin-left: 0.5rem;
  }
  .error {
    color: #ff6b6b;
    font-size: 0.85rem;
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
  input,
  select {
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.5rem 0.6rem;
    font-family: inherit;
    font-size: 0.95rem;
  }
  button {
    background: var(--accent);
    color: var(--bg);
    border: 1px solid var(--accent);
    padding: 0.5rem 0.9rem;
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
  .token-box {
    background: var(--bg);
    border: 1px solid var(--accent);
    padding: 0.8rem;
    margin: 0.8rem 0;
  }
  .token {
    display: block;
    background: transparent;
    color: var(--accent);
    font-family: inherit;
    font-size: 0.85rem;
    word-break: break-all;
    margin: 0.4rem 0;
    user-select: all;
  }
  code {
    background: var(--bg);
    color: var(--ink);
    padding: 0 0.25rem;
    font-family: inherit;
  }
</style>

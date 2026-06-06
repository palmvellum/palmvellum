<script lang="ts">
  import { supabase } from '$lib/supabase';
  import { authState } from '$lib/auth.svelte';
  import { base } from '$app/paths';
  import { goto } from '$app/navigation';
  import { t, currentLang, setLang, SUPPORTED_LANGUAGES, type Lang } from '$lib/i18n.svelte';

  type Provider = 'openai' | 'anthropic' | 'gemini';

  // ── Settings: BYOK key paste ────────────────────────────
  let provider = $state<Provider>('openai');
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

  async function updatePreferredProvider(p: Provider) {
    const { error } = await supabase
      .from('user_settings')
      .update({ preferred_provider: p })
      .eq('user_id', authState.userId!);
    if (!error) await authState.refreshSettings();
  }

  // Guard: redirect to / if not signed in
  $effect(() => {
    if (authState.phase === 'unauthenticated') {
      void goto(base + '/');
    }
  });
</script>

<svelte:head>
  <title>PalmVellum · {t('settings.heading')}</title>
</svelte:head>

{#if authState.phase === 'loading'}
  <p>{t('common.loading')}</p>
{:else if authState.phase === 'unauthenticated'}
  <p>{t('common.loading')}</p>
{:else if !authState.settings}
  <p>your settings row hasn't been created yet — try signing out and in again.</p>
{:else}
  <h1>{t('settings.heading')}</h1>
  <p class="hint">{t('settings.account')}: {authState.email}</p>

  <!-- Language picker -->
  <section class="card">
    <h2>{t('settings.language')}</h2>
    <p class="sub">{t('settings.languageHint')}</p>
    <select
      value={currentLang.value}
      onchange={(e) => setLang((e.currentTarget as HTMLSelectElement).value as Lang)}
    >
      {#each SUPPORTED_LANGUAGES as lng (lng.code)}
        <option value={lng.code}>{lng.label}</option>
      {/each}
    </select>
  </section>

  <!-- BYOK section -->
  <section class="card">
    <h2>{t('settings.apiKeys')}</h2>
    <p class="sub">{t('settings.apiKeysSub')}</p>

    <div class="status">
      <div>
        <span class="label">openai</span>
        {#if authState.settings.openai_secret_id}
          <span class="ok">{t('settings.stored')}</span>
        {:else}
          <span class="warn">{t('settings.notSet')}</span>
        {/if}
        <span class="muted">· {t('settings.model')}: {authState.settings.openai_model}</span>
      </div>
      <div>
        <span class="label">anthropic</span>
        {#if authState.settings.anthropic_secret_id}
          <span class="ok">{t('settings.stored')}</span>
        {:else}
          <span class="warn">{t('settings.notSet')}</span>
        {/if}
        <span class="muted">· {t('settings.model')}: {authState.settings.anthropic_model}</span>
      </div>
      <div>
        <span class="label">gemini</span>
        {#if authState.settings.gemini_secret_id}
          <span class="ok">{t('settings.stored')}</span>
        {:else}
          <span class="warn">{t('settings.notSet')}</span>
        {/if}
        <span class="muted">· {t('settings.model')}: {authState.settings.gemini_model}</span>
      </div>
      <div>
        <span class="label">{t('settings.preferredProvider')}</span>
        <select
          value={authState.settings.preferred_provider}
          onchange={(e) => updatePreferredProvider((e.currentTarget as HTMLSelectElement).value as Provider)}
        >
          <option value="openai">openai</option>
          <option value="anthropic">anthropic</option>
          <option value="gemini">gemini</option>
        </select>
      </div>
    </div>

    <form onsubmit={saveKey}>
      <label>
        {t('settings.providerLabel')}
        <select bind:value={provider}>
          <option value="openai">openai (sk-...)</option>
          <option value="anthropic">anthropic (sk-ant-...)</option>
          <option value="gemini">gemini (AIza...)</option>
        </select>
      </label>
      <label>
        {t('settings.apiKeyLabel')}
        <input
          type="password"
          bind:value={plaintext}
          required
          autocomplete="off"
          spellcheck="false"
          placeholder={t('settings.apiKeyPh')}
        />
      </label>
      {#if saveError}
        <p class="error">{saveError}</p>
      {/if}
      {#if saveOk}
        <p class="ok">{t('settings.saveOk')}</p>
      {/if}
      <button type="submit" disabled={saving}>
        {saving ? t('settings.storing') : t('settings.storeKey')}
      </button>
    </form>
  </section>

  <!-- Credits + subscription -->
  <section class="card">
    <h2>{t('settings.creditsHeading')}</h2>
    <p>
      <strong>{authState.settings.credits_remaining}</strong> {t('settings.creditsRemaining')},
      {authState.settings.credits_used_month} {t('settings.creditsUsed')}.
      {t('settings.subscription')}: <strong>{authState.settings.subscription_status}</strong>.
    </p>
    <p class="hint">{t('settings.creditsHint')}</p>
  </section>
{/if}

<style>
  h1 {
    font-size: 1.4rem;
    margin: 0 0 0.25rem;
  }
  @media (max-width: 600px) {
    h1 {
      font-size: 1.15rem;
    }
    .card {
      padding: 0.85rem;
    }
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
  code {
    background: var(--bg);
    color: var(--ink);
    padding: 0 0.25rem;
    font-family: inherit;
  }
</style>

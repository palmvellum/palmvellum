<script lang="ts">
  import { supabase } from '$lib/supabase';
  import { authState } from '$lib/auth.svelte';
  import { base } from '$app/paths';
  import { goto } from '$app/navigation';
  import { t, currentLang, setLang, SUPPORTED_LANGUAGES, type Lang } from '$lib/i18n.svelte';
  import { prefs } from '$lib/prefs.svelte';
  import { calsubs } from '$lib/stores/calsubs.svelte';
  import PalmAppShell from '$lib/components/palm/PalmAppShell.svelte';

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

  // ── iCal feed token ─────────────────────────────────────
  // Public Supabase Function base. The token is appended as /<token>.ics.
  const ICAL_FN_BASE = 'https://jrkwncplngmznfzzqwee.supabase.co/functions/v1/ical-feed';
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

  async function mintIcal() {
    icalBusy = true;
    icalError = null;
    icalCopied = false;
    const { error } = await supabase.rpc('mint_ical_token');
    icalBusy = false;
    if (error) {
      icalError = error.message;
      return;
    }
    await authState.refreshSettings();
  }

  async function revokeIcal() {
    icalBusy = true;
    icalError = null;
    icalCopied = false;
    const { error } = await supabase.rpc('revoke_ical_token');
    icalBusy = false;
    if (error) {
      icalError = error.message;
      return;
    }
    await authState.refreshSettings();
  }

  // ── Platform credits (pay-as-you-go) ────────────────────
  const MIN_TOPUP = 1; // TEMP: $1 for testing
  let topupUsd = $state(1);
  let topupBusy = $state(false);
  let topupError = $state<string | null>(null);

  function balanceUsd(): string {
    return ((authState.settings?.balance_micro_usd ?? 0) / 1_000_000).toFixed(2);
  }

  async function buyCredits() {
    topupError = null;
    if (!Number.isFinite(topupUsd) || topupUsd < MIN_TOPUP) {
      topupError = `Minimum top-up is $${MIN_TOPUP}.`;
      return;
    }
    topupBusy = true;
    const { data, error } = await supabase.functions.invoke('create-topup', {
      body: { amount_usd: topupUsd },
    });
    if (error || !data?.client_secret) {
      topupBusy = false;
      topupError = error?.message ?? data?.error ?? 'Could not start top-up.';
      return;
    }
    // Hand off to Airwallex's Hosted Payment Page (full-page redirect). The
    // balance is credited by the airwallex-webhook on payment success — never
    // client-side. redirectToCheckout navigates away, so topupBusy stays true.
    try {
      const env = data.env === 'live' ? 'prod' : 'demo';
      const awx: any = await import(/* @vite-ignore */ 'https://esm.sh/airwallex-payment-elements@1');
      // loadAirwallex() injects the remote checkout bundle and runs init().
      // init()/redirectToCheckout() are no-ops until this resolves — skipping
      // it leaves the button stuck on "starting" with no error.
      const loaded = await awx.loadAirwallex({ env, origin: location.origin });
      if (!loaded) throw new Error('Could not load the Airwallex checkout SDK.');
      awx.redirectToCheckout({
        env,
        intent_id: data.intent_id,
        client_secret: data.client_secret,
        currency: 'USD',
        successUrl: location.origin + base + '/settings?topup=ok',
        failUrl: location.origin + base + '/settings?topup=fail',
      });
    } catch (e) {
      topupBusy = false;
      topupError = 'Payment failed to start: ' + String(e);
    }
  }

  async function setApiMode(mode: 'byok' | 'platform') {
    await supabase.from('user_settings').update({ api_mode: mode }).eq('user_id', authState.userId!);
    await authState.refreshSettings();
  }

  async function copyIcal() {
    const u = icalHttpsUrl();
    if (!u) return;
    await navigator.clipboard.writeText(u);
    icalCopied = true;
    setTimeout(() => { icalCopied = false; }, 2000);
  }

  // ── Subscribed calendars (inbound iCal: Google Calendar etc.) ──
  // Reads external feeds INTO Date Book (read-only). URL fetches go
  // through the fetch-ics Edge Function (browsers can't fetch most
  // feeds cross-origin); .ics file import is fully client-side.
  let subName = $state('');
  let subUrl = $state('');
  let subBusy = $state(false);
  let subMsg = $state<string | null>(null);
  let subError = $state<string | null>(null);
  let icsFileInput = $state<HTMLInputElement | null>(null);

  // The subscription list is a synced record now — reload it once auth is
  // ready so feeds added on another device (or Android) show up here.
  $effect(() => {
    if (authState.userId) void calsubs.load();
  });

  async function addSub(e: Event) {
    e.preventDefault();
    subError = null;
    subMsg = null;
    const url = subUrl.trim();
    if (!url) return;
    await calsubs.add({ name: subName.trim() || url, url });
    subName = '';
    subUrl = '';
    void refreshSubs();
  }

  async function refreshSubs() {
    subError = null;
    subMsg = null;
    subBusy = true;
    try {
      const n = await calsubs.refresh();
      subMsg = `refreshed — ${n} event${n === 1 ? '' : 's'} added/updated`;
    } catch (err) {
      subError = err instanceof Error ? err.message : String(err);
    } finally {
      subBusy = false;
    }
  }

  async function importIcsFile(e: Event) {
    subError = null;
    subMsg = null;
    const input = e.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    subBusy = true;
    try {
      const text = await file.text();
      const n = await calsubs.importText(text);
      subMsg = `imported ${n} event${n === 1 ? '' : 's'} from ${file.name}`;
    } catch (err) {
      subError = err instanceof Error ? err.message : String(err);
    } finally {
      subBusy = false;
      input.value = '';
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
  <title>PalmVellum · {t('settings.heading')}</title>
</svelte:head>

{#if authState.phase === 'loading'}
  <p>{t('common.loading')}</p>
{:else if authState.phase === 'unauthenticated'}
  <p>{t('common.loading')}</p>
{:else if !authState.settings}
  <p>your settings row hasn't been created yet — try signing out and in again.</p>
{:else}
  <PalmAppShell title={t('settings.heading')}>
    <h1 class="pg-heading">{t('settings.heading')}</h1>
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

  <!-- First day of week (used by Date Book month + week grids) -->
  <section class="card">
    <h2>{t('settings.weekStart')}</h2>
    <p class="sub">{t('settings.weekStartHint')}</p>
    <div class="radio-row">
      <label>
        <input
          type="radio" name="weekStart" value="1"
          checked={prefs.weekStart === 1}
          onchange={() => prefs.setWeekStart(1)}
        />
        {t('settings.weekStart.mon')}
      </label>
      <label>
        <input
          type="radio" name="weekStart" value="0"
          checked={prefs.weekStart === 0}
          onchange={() => prefs.setWeekStart(0)}
        />
        {t('settings.weekStart.sun')}
      </label>
    </div>
  </section>

  <!-- AI provider: platform credits (default) vs BYOK -->
  <section class="card">
    <h2>{t('settings.apiKeys')}</h2>
    <p class="sub">
      Choose how AI is powered: top up <strong>platform credits</strong>
      (pay-as-you-go), or bring your own API key.
    </p>

    <div class="mode-switch" role="group" aria-label="AI provider mode">
      <button
        type="button"
        class="seg"
        class:active={(authState.settings?.api_mode ?? 'platform') === 'platform'}
        onclick={() => setApiMode('platform')}
      >Platform credits</button>
      <button
        type="button"
        class="seg"
        class:active={authState.settings?.api_mode === 'byok'}
        onclick={() => setApiMode('byok')}
      >Your own key</button>
    </div>
    <p class="sub mode-note">
      {#if (authState.settings?.api_mode ?? 'platform') === 'platform'}
        Pay as you go — OpenAI cost + 50%, drawn from your balance.
      {:else}
        You pay the provider directly with your own API key.
      {/if}
    </p>

    {#if (authState.settings?.api_mode ?? 'platform') === 'platform'}
      <!-- Platform credits: balance + top-up -->
      <p class="balance">Balance: <strong>${balanceUsd()}</strong></p>
      <label class="field">
        Top up (USD, min ${MIN_TOPUP})
        <input type="number" min={MIN_TOPUP} step="1" bind:value={topupUsd} />
      </label>
      {#if topupError}<p class="error">{topupError}</p>{/if}
      <button type="button" class="buy-btn" onclick={buyCredits} disabled={topupBusy}>
        {topupBusy ? 'Starting…' : `Buy $${topupUsd} of credits`}
      </button>
      <p class="hint">
        Secure payment via Airwallex; minimum ${MIN_TOPUP}. We never store your
        card. Credit is added once payment clears.
      </p>
    {:else}
      <!-- BYOK: provider status + key paste -->
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
        {#if saveError}<p class="error">{saveError}</p>{/if}
        {#if saveOk}<p class="ok">{t('settings.saveOk')}</p>{/if}
        <button type="submit" disabled={saving}>
          {saving ? t('settings.storing') : t('settings.storeKey')}
        </button>
      </form>
    {/if}
  </section>

  <!-- iCal subscription feed -->
  <section class="card">
    <h2>{t('settings.icalHeading')}</h2>
    <p class="sub">{t('settings.icalSub')}</p>

    {#if authState.settings.ical_token}
      <div class="ical-url">
        <code>{icalHttpsUrl()}</code>
      </div>
      <div class="ical-actions">
        <a class="btn-link" href={icalWebcalUrl()} rel="noopener">
          {t('settings.icalSubscribeApple')}
        </a>
        <button type="button" onclick={copyIcal} disabled={icalBusy}>
          {icalCopied ? t('settings.icalCopied') : t('settings.icalCopy')}
        </button>
        <button type="button" class="secondary" onclick={revokeIcal} disabled={icalBusy}>
          {icalBusy ? t('common.loading') : t('settings.icalRevoke')}
        </button>
      </div>
      <p class="hint">{t('settings.icalRefreshHint')}</p>
    {:else}
      <p>{t('settings.icalEnableExplain')}</p>
      <button type="button" onclick={mintIcal} disabled={icalBusy}>
        {icalBusy ? t('common.loading') : t('settings.icalEnable')}
      </button>
    {/if}

    {#if icalError}<p class="error">{icalError}</p>{/if}
  </section>

  <!-- Subscribed calendars (inbound: Google Calendar / .ics) -->
  <section class="card">
    <h2>subscribed calendars</h2>
    <p class="sub">
      pull an external calendar into your date book (read-only). paste a
      google calendar "secret address in iCal format", or an .ics url —
      or import an .ics file. events refresh when you open the app.
    </p>

    {#if calsubs.subs.length > 0}
      <ul class="sub-list">
        {#each calsubs.subs as s (s.url)}
          <li>
            <div class="sub-info">
              <span class="sub-name">{s.name}</span>
              <span class="sub-url">{s.url}</span>
            </div>
            <button type="button" class="secondary" onclick={() => calsubs.remove(s.url)} disabled={subBusy}>
              remove
            </button>
          </li>
        {/each}
      </ul>
      <div class="sub-actions">
        <button type="button" onclick={refreshSubs} disabled={subBusy}>
          {subBusy ? 'refreshing…' : 'refresh now'}
        </button>
        <label class="interval">
          auto-refresh
          <select
            value={String(calsubs.intervalHours)}
            onchange={(e) => calsubs.setIntervalHours(+(e.currentTarget as HTMLSelectElement).value)}
          >
            <option value="0">on open</option>
            <option value="6">every 6h</option>
            <option value="12">every 12h</option>
            <option value="24">daily</option>
          </select>
        </label>
      </div>
    {/if}

    <form class="sub-form" onsubmit={addSub}>
      <label>
        name (optional)
        <input bind:value={subName} placeholder="e.g. Work, Family" maxlength="80" />
      </label>
      <label>
        iCal URL
        <input
          bind:value={subUrl}
          placeholder="https://calendar.google.com/calendar/ical/.../basic.ics"
          inputmode="url"
        />
      </label>
      <button type="submit" disabled={subBusy || !subUrl.trim()}>add subscription</button>
    </form>

    <div class="ics-import">
      <button type="button" class="secondary" onclick={() => icsFileInput?.click()} disabled={subBusy}>
        import .ics file
      </button>
      <input
        bind:this={icsFileInput}
        type="file"
        accept=".ics,text/calendar"
        onchange={importIcsFile}
        hidden
      />
    </div>

    {#if subMsg}<p class="ok">{subMsg}</p>{/if}
    {#if subError}<p class="error">{subError}</p>{/if}
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

  <!-- Sign out -->
  <section class="card">
    <h2>{t('settings.signOutHeading')}</h2>
    <p class="sub">{t('settings.signOutHint')}</p>
    <button type="button" class="signout-btn" onclick={() => void authState.signOut()}>
      {t('nav.signOut')}
    </button>
  </section>
  </PalmAppShell>
{/if}

<style>
  .pg-heading {
    font-size: 1.3rem;
    font-weight: 700;
    margin: 0.4rem 0 0.25rem;
  }
  .signout-btn {
    background: var(--surface-dk);
    color: #fff;
    border: 1px solid #1a1a1a;
    padding: 0.55rem 1rem;
    font-family: inherit;
    font-weight: 700;
    cursor: pointer;
    border-radius: 4px;
    margin-top: 0.5rem;
  }
  .signout-btn:hover { background: #2c2c2a; }
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
  .radio-row {
    display: flex;
    gap: 1.2rem;
    flex-wrap: wrap;
  }
  .radio-row label {
    display: inline-flex;
    align-items: center;
    gap: 0.45rem;
    font-size: 0.95rem;
    cursor: pointer;
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
    color: #fff;
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
  .ical-url {
    background: var(--bg);
    border: 1px solid var(--line);
    padding: 0.55rem 0.7rem;
    margin: 0.5rem 0 0.7rem;
    overflow-x: auto;
  }
  .ical-url code {
    background: transparent;
    color: var(--accent);
    font-size: 0.78rem;
    white-space: nowrap;
    user-select: all;
  }
  .ical-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    align-items: center;
    margin-bottom: 0.6rem;
  }
  .ical-actions .btn-link {
    background: var(--accent);
    color: #fff;
    border: 1px solid var(--accent);
    padding: 0.45rem 0.85rem;
    font-family: inherit;
    font-weight: 600;
    text-decoration: none;
  }
  .ical-actions .btn-link:hover {
    background: var(--accent-dim);
  }
  .ical-actions button.secondary {
    background: transparent;
    color: var(--ink-mute);
    border: 1px solid var(--line);
  }
  .ical-actions button.secondary:hover:not(:disabled) {
    background: var(--surface-hi);
    color: var(--ink);
  }

  /* Subscribed calendars (inbound) */
  .sub-list {
    list-style: none;
    margin: 0 0 0.8rem;
    padding: 0;
    display: grid;
    gap: 0.4rem;
  }
  .sub-list li {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    background: var(--bg);
    border: 1px solid var(--line);
    padding: 0.5rem 0.65rem;
  }
  .sub-info {
    display: grid;
    min-width: 0;
    flex: 1;
  }
  .sub-name {
    color: var(--ink);
    font-weight: 600;
    font-size: 0.9rem;
  }
  .sub-url {
    color: var(--ink-mute);
    font-size: 0.72rem;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .sub-actions {
    display: flex;
    align-items: center;
    gap: 0.8rem;
    flex-wrap: wrap;
    margin-bottom: 0.9rem;
    padding-bottom: 0.9rem;
    border-bottom: 1px dashed var(--line-soft);
  }
  .sub-actions .interval {
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
    flex-direction: row;
    color: var(--ink-mute);
    font-size: 0.85rem;
  }
  .sub-actions .interval select {
    width: auto;
  }
  .sub-form {
    margin-bottom: 0.7rem;
  }
  .ics-import {
    margin-top: 0.3rem;
  }
  button.secondary {
    background: transparent;
    color: var(--ink-mute);
    border: 1px solid var(--line);
  }
  button.secondary:hover:not(:disabled) {
    background: var(--surface-hi);
    color: var(--ink);
  }

  /* Platform / BYOK left-right segmented switch */
  .mode-switch {
    display: flex;
    width: 100%;
    border: 1px solid var(--accent);
    border-radius: 8px;
    overflow: hidden;
    margin: 0.25rem 0 0.5rem;
  }
  .mode-switch .seg {
    flex: 1;
    padding: 0.55rem 0.5rem;
    background: var(--surface-lo);
    color: var(--ink-dim);
    border: none;
    border-radius: 0;
    font-size: 0.9rem;
    font-weight: 600;
    cursor: pointer;
    transition: background 0.12s ease, color 0.12s ease;
  }
  .mode-switch .seg + .seg {
    border-left: 1px solid var(--accent);
  }
  .mode-switch .seg.active {
    background: var(--accent);
    color: #fff;
  }
  .mode-note {
    margin-top: 0;
  }
  .buy-btn {
    margin-bottom: 1.25rem;
  }
</style>

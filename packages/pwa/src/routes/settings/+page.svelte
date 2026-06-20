<script lang="ts">
  import { onMount } from 'svelte';
  import { supabase } from '$lib/supabase';
  import { authState } from '$lib/auth.svelte';
  import { base } from '$app/paths';
  import { goto, replaceState } from '$app/navigation';
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

  // ── PalmVellum Credits (pay-as-you-go) ──────────────────
  const MIN_TOPUP = 10; // US$10 minimum = 1,000 credits
  let topupUsd = $state(10);
  let topupBusy = $state(false);
  let topupError = $state<string | null>(null);

  // Credits are a display unit over the real micro-USD balance. The
  // server charges exact micro-USD per call (raw OpenAI cost × N retail
  // markup, see _shared/pricing.ts); we show it as credits.
  //   1 credit = 10,000 micro-USD  →  US$1 = 100 credits, US$10 = 1,000.
  // Calibrated so US$10 (1,000 credits) ≈ ~3,000 typical AI Date Book
  // records (~a few micro-USD each on gpt-4o-mini at the N× markup).
  const MICRO_PER_CREDIT = 10_000;
  const CREDITS_PER_USD = 1_000_000 / MICRO_PER_CREDIT; // 100

  function balanceCredits(): number {
    return Math.round((authState.settings?.balance_micro_usd ?? 0) / MICRO_PER_CREDIT);
  }
  function microToCredits(micro: number): number {
    return Math.round(micro / MICRO_PER_CREDIT);
  }
  function fmtCredits(n: number): string {
    return n.toLocaleString('en-US');
  }

  // ── Post-checkout success lightbox ──────────────────────
  // Airwallex redirects back to ?topup=ok|fail after the hosted page.
  // The balance is credited by the webhook, which can lag a second or
  // two, so we poll refreshSettings() until it lands (or the user taps
  // Refresh). The modal shows the balance live.
  let topupSuccess = $state(false);
  let balanceUpdating = $state(false);

  async function refreshBalance() {
    balanceUpdating = true;
    await authState.refreshSettings();
    await loadReceipts();
    balanceUpdating = false;
  }

  async function pollBalance(prevMicro: number) {
    balanceUpdating = true;
    for (let i = 0; i < 6; i++) {
      await authState.refreshSettings();
      if ((authState.settings?.balance_micro_usd ?? 0) > prevMicro) break;
      await new Promise((r) => setTimeout(r, 2000));
    }
    // The webhook also writes the credit_ledger row this receipt reads.
    await loadReceipts();
    balanceUpdating = false;
  }

  // ── Top-up receipts (downloadable PDF) ──────────────────
  // Each successful top-up is an immutable credit_ledger row (kind=topup);
  // RLS lets the owner read their own. We render each as a PDF receipt.
  type Receipt = {
    id: string;
    created_at: string;
    amount_micro_usd: number;
    balance_after: number;
    ref: string | null;
  };
  let receipts = $state<Receipt[]>([]);

  async function loadReceipts() {
    if (!authState.userId) return;
    const { data } = await supabase
      .from('credit_ledger')
      .select('id, created_at, amount_micro_usd, balance_after, ref')
      .eq('kind', 'topup')
      .order('created_at', { ascending: false })
      .limit(5);
    receipts = (data as Receipt[]) ?? [];
  }

  // Load the receipt history once the user id is available (auth boots
  // asynchronously, so onMount alone can be too early).
  let receiptsLoaded = false;
  $effect(() => {
    if (authState.userId && !receiptsLoaded) {
      receiptsLoaded = true;
      void loadReceipts();
    }
  });

  function receiptDate(iso: string): string {
    try {
      return new Date(iso).toLocaleString('en-US', {
        year: 'numeric', month: 'short', day: '2-digit',
        hour: '2-digit', minute: '2-digit',
      });
    } catch { return iso; }
  }

  async function downloadReceipt(r: Receipt) {
    // jsPDF via esm.sh (same dynamic-import pattern as the Airwallex SDK)
    // so we don't carry it in the bundle.
    const mod: any = await import(/* @vite-ignore */ 'https://esm.sh/jspdf@2.5.2');
    const JsPDF = mod.jsPDF ?? mod.default;
    const doc = new JsPDF({ unit: 'pt', format: 'a4' });

    const usd = (r.amount_micro_usd / 1_000_000).toFixed(2);
    const credits = fmtCredits(microToCredits(r.amount_micro_usd));
    const balCredits = fmtCredits(microToCredits(r.balance_after));
    const left = 56;
    let y = 72;

    doc.setFont('helvetica', 'bold');
    doc.setFontSize(20);
    doc.text('PalmVellum', left, y);
    doc.setFont('helvetica', 'normal');
    doc.setFontSize(11);
    doc.text('Credit top-up receipt', left, (y += 20));

    doc.setDrawColor(180);
    doc.line(left, (y += 14), 539, y);
    y += 26;

    const row = (label: string, value: string, bold = false) => {
      doc.setFont('helvetica', 'normal');
      doc.setFontSize(11);
      doc.setTextColor(110);
      doc.text(label, left, y);
      doc.setTextColor(20);
      doc.setFont('helvetica', bold ? 'bold' : 'normal');
      doc.text(value, 539, y, { align: 'right' });
      y += 24;
    };

    row('Receipt no.', r.id);
    row('Date', receiptDate(r.created_at));
    row('Billed to', authState.email ?? '—');
    row('Payment method', 'Card via Airwallex');
    if (r.ref) row('Payment reference', r.ref);
    y += 8;
    doc.setDrawColor(220);
    doc.line(left, y, 539, y);
    y += 26;
    row('Amount paid', `US$${usd}`, true);
    row('Credits added', `${credits} credits`, true);
    row('Balance after', `${balCredits} credits`);

    y += 18;
    doc.setDrawColor(180);
    doc.line(left, y, 539, y);
    y += 24;
    doc.setFont('helvetica', 'normal');
    doc.setFontSize(9.5);
    doc.setTextColor(120);
    doc.text('Thank you for supporting PalmVellum.', left, y);
    doc.text('Credits power on-device AI features. 1 credit = US$0.01.', left, (y += 16));

    doc.save(`palmvellum-receipt-${r.id}.pdf`);
  }

  function dismissTopupSuccess() {
    topupSuccess = false;
    try { sessionStorage.removeItem('pv_topup'); } catch { /* ignore */ }
  }

  // The Airwallex success redirect is a full page load, after which auth
  // re-init / routing can remount this component and wipe local $state.
  // Persist the "show success" signal in sessionStorage so it survives a
  // remount (onMount re-reads it); only Done clears it.
  onMount(() => {
    // Airwallex returns to #topup=ok|fail (hash). Fall back to ?topup=…
    // for any redirect issued before this build went live.
    const raw = location.hash.slice(1) || location.search.slice(1);
    const topup = new URLSearchParams(raw).get('topup');
    if (topup === 'ok' || topup === 'fail') {
      try { sessionStorage.setItem('pv_topup', topup); } catch { /* ignore */ }
      // Strip the hash/query (shallow — no remount) so refresh won't reprocess.
      replaceState(base + '/settings', {});
    }
    let flag: string | null = null;
    try { flag = sessionStorage.getItem('pv_topup'); } catch { /* ignore */ }
    if (flag === 'ok') {
      topupBusy = false;
      topupError = null;
      topupSuccess = true;
      void pollBalance(authState.settings?.balance_micro_usd ?? 0);
    } else if (flag === 'fail') {
      topupBusy = false;
      topupError = t('settings.payNotCompleted');
      try { sessionStorage.removeItem('pv_topup'); } catch { /* ignore */ }
    }
  });

  async function buyCredits() {
    topupError = null;
    if (!Number.isFinite(topupUsd) || topupUsd < MIN_TOPUP) {
      topupError = t('settings.errMin', { min: MIN_TOPUP });
      return;
    }
    topupBusy = true;
    const { data, error } = await supabase.functions.invoke('create-topup', {
      body: { amount_usd: topupUsd },
    });
    if (error || !data?.client_secret) {
      topupBusy = false;
      topupError = error?.message ?? data?.error ?? t('settings.errStart');
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
      if (!loaded) throw new Error(t('settings.errSdk'));
      awx.redirectToCheckout({
        env,
        intent_id: data.intent_id,
        client_secret: data.client_secret,
        currency: 'USD',
        // Return to a hash, not a query string: a query on the boot URL
        // stalls Supabase's detectSessionInUrl and leaves the app stuck
        // on the loading screen. A hash is invisible to it.
        successUrl: location.origin + base + '/settings#topup=ok',
        failUrl: location.origin + base + '/settings#topup=fail',
      });
    } catch (e) {
      topupBusy = false;
      topupError = t('settings.errPayStart', { detail: String(e) });
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
      subMsg = t('settings.subRefreshed', { n });
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

{#if topupSuccess}
  <div class="lb-bk" onclick={dismissTopupSuccess} role="presentation"></div>
  <div class="lb-dlg" role="dialog" aria-modal="true" aria-label={t('settings.topupOkTitle')}>
    <h2 class="lb-title">{t('settings.topupOkTitle')}</h2>
    <p class="lb-msg">{t('settings.topupOkBody')}</p>
    <p class="lb-balance">
      <strong>{t('settings.balanceLine', { credits: fmtCredits(balanceCredits()) })}</strong>
      {#if balanceUpdating}<span class="lb-sync">{t('settings.updating')}</span>{/if}
    </p>
    <p class="lb-hint">{t('settings.topupOkHint')}</p>
    <div class="lb-actions">
      {#if receipts.length}
        <button type="button" class="lb-btn refresh" onclick={() => downloadReceipt(receipts[0])}>
          {t('settings.downloadReceipt')}
        </button>
      {/if}
      <button type="button" class="lb-btn refresh" onclick={refreshBalance} disabled={balanceUpdating}>
        {balanceUpdating ? t('settings.refreshing') : t('settings.refreshBalance')}
      </button>
      <button type="button" class="lb-btn done" onclick={dismissTopupSuccess}>{t('settings.done')}</button>
    </div>
  </div>
{/if}

{#if authState.phase === 'loading'}
  <p>{t('common.loading')}</p>
{:else if authState.phase === 'unauthenticated'}
  <p>{t('common.loading')}</p>
{:else if !authState.settings}
  <p>{t('settings.noRow')}</p>
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
    <p class="sub">{t('settings.aiHow')}</p>

    <div class="mode-switch" role="group" aria-label="AI provider mode">
      <button
        type="button"
        class="seg"
        class:active={(authState.settings?.api_mode ?? 'platform') === 'platform'}
        onclick={() => setApiMode('platform')}
      >{t('settings.modePlatform')}</button>
      <button
        type="button"
        class="seg"
        class:active={authState.settings?.api_mode === 'byok'}
        onclick={() => setApiMode('byok')}
      >{t('settings.modeByok')}</button>
    </div>
    {#if authState.settings?.api_mode === 'byok'}
      <p class="sub mode-note">{t('settings.byokNote')}</p>
    {/if}

    {#if (authState.settings?.api_mode ?? 'platform') === 'platform'}
      <!-- PalmVellum Credits: balance + top-up -->
      <p class="balance"><strong>{t('settings.balanceLine', { credits: fmtCredits(balanceCredits()) })}</strong></p>
      <p class="hint rate-note">{t('settings.rateNote')}</p>
      <div class="topup-row">
        <label class="field">
          {t('settings.topupLabel', { min: MIN_TOPUP })}
          <input type="number" min={MIN_TOPUP} step="1" bind:value={topupUsd} />
        </label>
        <button type="button" class="buy-btn" onclick={buyCredits} disabled={topupBusy}>
          {topupBusy ? t('settings.starting') : t('settings.buyCredits', { credits: fmtCredits(Math.round((Number(topupUsd) || 0) * CREDITS_PER_USD)) })}
        </button>
      </div>
      {#if Number.isFinite(Number(topupUsd)) && Number(topupUsd) > 0}
        <p class="hint credits-eq">{t('settings.creditsEq', { usd: Number(topupUsd), credits: fmtCredits(Math.round(Number(topupUsd) * CREDITS_PER_USD)) })}</p>
      {/if}
      {#if topupError}<p class="error">{topupError}</p>{/if}
      <p class="hint">{t('settings.payHint', { min: MIN_TOPUP })}</p>

      {#if receipts.length}
        <div class="receipts">
          <h3>{t('settings.receiptsHead')}</h3>
          <p class="hint">{t('settings.receiptsNote')}</p>
          <ul>
            {#each receipts as r (r.id)}
              <li class="receipt-row">
                <span class="r-date">{receiptDate(r.created_at)}</span>
                <span class="r-amt">${(r.amount_micro_usd / 1_000_000).toFixed(2)} · {t('settings.creditsVal', { credits: fmtCredits(microToCredits(r.amount_micro_usd)) })}</span>
                <button type="button" class="r-dl" onclick={() => downloadReceipt(r)}>{t('settings.downloadPdf')}</button>
              </li>
            {/each}
          </ul>
        </div>
      {/if}
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
    <h2>{t('settings.subsHead')}</h2>
    <p class="sub">{t('settings.subsSub')}</p>

    {#if calsubs.subs.length > 0}
      <ul class="sub-list">
        {#each calsubs.subs as s (s.url)}
          <li>
            <div class="sub-info">
              <span class="sub-name">{s.name}</span>
              <span class="sub-url">{s.url}</span>
            </div>
            <button type="button" class="secondary" onclick={() => calsubs.remove(s.url)} disabled={subBusy}>
              {t('settings.subRemove')}
            </button>
          </li>
        {/each}
      </ul>
      <div class="sub-actions">
        <button type="button" onclick={refreshSubs} disabled={subBusy}>
          {subBusy ? t('settings.subRefreshing') : t('settings.subRefreshNow')}
        </button>
        <label class="interval">
          {t('settings.subAutoRefresh')}
          <select
            value={String(calsubs.intervalHours)}
            onchange={(e) => calsubs.setIntervalHours(+(e.currentTarget as HTMLSelectElement).value)}
          >
            <option value="0">{t('settings.subOnOpen')}</option>
            <option value="6">{t('settings.subEvery6')}</option>
            <option value="12">{t('settings.subEvery12')}</option>
            <option value="24">{t('settings.subDaily')}</option>
          </select>
        </label>
      </div>
    {/if}

    <form class="sub-form" onsubmit={addSub}>
      <label>
        {t('settings.subNameLabel')}
        <input bind:value={subName} placeholder={t('settings.subNamePh')} maxlength="80" />
      </label>
      <label>
        {t('settings.subUrlLabel')}
        <input
          bind:value={subUrl}
          placeholder="https://calendar.google.com/calendar/ical/.../basic.ics"
          inputmode="url"
        />
      </label>
      <button type="submit" disabled={subBusy || !subUrl.trim()}>{t('settings.subAdd')}</button>
    </form>

    <div class="ics-import">
      <button type="button" class="secondary" onclick={() => icsFileInput?.click()} disabled={subBusy}>
        {t('settings.subImport')}
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
  /* ── Top-up success lightbox (mirrors PalmConfirm styling) ── */
  .lb-bk {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.55);
    z-index: 200;
  }
  .lb-dlg {
    position: fixed;
    z-index: 201;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    background: var(--surface-lo, #e6e6e1);
    border: 1px solid var(--line, #1a1a1a);
    box-shadow: 0 12px 36px rgba(0, 0, 0, 0.4);
    width: min(380px, calc(100vw - 2rem));
    padding: 1.1rem 1.2rem 1rem;
    border-radius: 4px;
  }
  .lb-title {
    margin: 0 0 0.5rem;
    font-size: 1.05rem;
    font-weight: 700;
    color: var(--accent, #7e2b22);
  }
  .lb-msg {
    margin: 0 0 0.7rem;
    font-size: 0.92rem;
    line-height: 1.4;
    color: var(--ink, #000);
  }
  .lb-balance {
    margin: 0 0 0.5rem;
    font-size: 1rem;
    color: var(--ink, #000);
  }
  .lb-balance strong { font-size: 1.15rem; }
  .lb-sync {
    margin-left: 0.5rem;
    font-size: 0.78rem;
    color: var(--ink-mute, #555);
  }
  .lb-hint {
    margin: 0 0 0.9rem;
    font-size: 0.8rem;
    line-height: 1.35;
    color: var(--ink-mute, #555);
  }
  .lb-actions {
    display: flex;
    justify-content: flex-end;
    gap: 0.5rem;
  }
  .lb-btn {
    min-height: 40px;
    padding: 0.5rem 1rem;
    border-radius: 3px;
    font-family: inherit;
    font-weight: 700;
    font-size: 0.9rem;
    cursor: pointer;
    border: 1px solid var(--line, #1a1a1a);
  }
  .lb-btn.refresh {
    background: var(--surface-hi, #f4f4ee);
    color: var(--ink, #000);
  }
  .lb-btn.done {
    background: var(--surface-dk, #4a4a48);
    color: #fff;
    border-color: #1a1a1a;
  }
  .lb-btn.done:hover { background: #2c2c2a; }
  .lb-btn:disabled { opacity: 0.6; cursor: default; }

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
  .topup-row {
    display: flex;
    align-items: flex-end;
    gap: 0.6rem;
    margin-bottom: 0.5rem;
  }
  .topup-row .field {
    flex: 1;
    min-width: 0;
  }
  .buy-btn {
    margin-bottom: 0;
    white-space: nowrap;
  }
  .credits-eq {
    margin-top: 0.35rem;
    color: var(--ink-mute);
  }
  .receipts {
    margin-top: 1.1rem;
    border-top: 1px solid var(--line);
    padding-top: 0.8rem;
  }
  .receipts h3 {
    margin: 0 0 0.5rem;
    font-size: 0.95rem;
    font-weight: 700;
  }
  .receipts ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.4rem;
  }
  .receipt-row {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    flex-wrap: wrap;
  }
  .receipt-row .r-date {
    flex: 1 1 9rem;
    min-width: 0;
    font-size: 0.82rem;
    color: var(--ink-mute);
  }
  .receipt-row .r-amt {
    font-size: 0.85rem;
    color: var(--ink);
  }
  .receipt-row .r-dl {
    background: var(--surface-hi, #f4f4ee);
    color: var(--ink, #000);
    border: 1px solid var(--line);
    padding: 0.3rem 0.6rem;
    font-size: 0.8rem;
    font-weight: 600;
    white-space: nowrap;
    cursor: pointer;
  }
  .receipt-row .r-dl:hover { border-color: var(--accent); color: var(--accent); }
</style>

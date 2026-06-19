<script lang="ts">
  import { onMount } from 'svelte';
  import { supabase } from '$lib/supabase';
  import { authState } from '$lib/auth.svelte';
  import { base } from '$app/paths';
  import { goto } from '$app/navigation';
  import { t } from '$lib/i18n.svelte';

  // Two-step OTP flow:
  //   step 1: type email + send code   -> Supabase emails a 6-digit code
  //   step 2: type code in app + verify -> session granted, no deep link
  // The same email also includes the legacy magic-link as a fallback
  // for desktop users, but the OTP code path is what we actively
  // surface — it works on every Android device regardless of how the
  // mail / browser app handles deep links.
  let signinEmail = $state('');
  let otpCode = $state('');
  let submitting = $state(false);
  let codeSent = $state(false);
  let verifying = $state(false);
  let signinError = $state<string | null>(null);

  async function sendCode(e: Event) {
    e.preventDefault();
    signinError = null;
    submitting = true;
    const { error } = await supabase.auth.signInWithOtp({
      email: signinEmail.trim().toLowerCase(),
      options: {
        shouldCreateUser: false,
      },
    });
    submitting = false;
    if (error) {
      signinError = error.message;
      return;
    }
    codeSent = true;
  }

  async function verifyCode(e: Event) {
    e.preventDefault();
    signinError = null;
    verifying = true;
    const { error } = await supabase.auth.verifyOtp({
      email: signinEmail.trim().toLowerCase(),
      token: otpCode.trim(),
      type: 'email',
    });
    verifying = false;
    if (error) {
      signinError = error.message;
      return;
    }
    // verifyOtp installs the session via the auth store; the $effect
    // below will catch authState.phase === 'ready' and route to /palm.
  }

  function resetSignin() {
    codeSent = false;
    otpCode = '';
    signinError = null;
  }

  $effect(() => {
    if (authState.phase === 'ready') {
      void goto(base + '/palm', { replaceState: true });
    }
  });

  onMount(() => {
    if (authState.phase === 'ready') {
      void goto(base + '/palm', { replaceState: true });
    }
  });
</script>

<svelte:head>
  <title>PalmVellum</title>
</svelte:head>

<div class="auth-screen">
{#if authState.phase === 'loading'}
  <p class="loading">{t('common.loading')}</p>

{:else if authState.phase === 'unauthenticated'}
  <section class="card center">
    <h1>PalmVellum</h1>
    <p class="lede">
      {t('palm.sub')}
    </p>

    {#if !codeSent}
      <!-- Step 1: type email + send code -->
      <form onsubmit={sendCode}>
        <label>
          email
          <input
            type="email"
            bind:value={signinEmail}
            required
            autocomplete="email"
            placeholder="you@example.com"
          />
        </label>
        {#if signinError}
          <p class="error">{signinError}</p>
        {/if}
        <button type="submit" disabled={submitting}>
          {submitting ? 'sending…' : 'send code'}
        </button>
      </form>
    {:else}
      <!-- Step 2: enter code from email -->
      <p class="ok">
        [ok] code sent to <strong>{signinEmail}</strong>. enter the 6-digit code below.
      </p>
      <form onsubmit={verifyCode}>
        <label>
          code
          <input
            type="text"
            inputmode="numeric"
            autocomplete="one-time-code"
            bind:value={otpCode}
            required
            maxlength="6"
            placeholder="123456"
            class="otp-input"
          />
        </label>
        {#if signinError}
          <p class="error">{signinError}</p>
        {/if}
        <button type="submit" disabled={verifying || otpCode.length !== 6}>
          {verifying ? 'verifying…' : 'sign in'}
        </button>
        <button type="button" class="link" onclick={resetSignin}>
          back / re-send code
        </button>
      </form>
    {/if}

    <p class="muted">
      <a href="https://tatliving.dev/palmvellum/">about the project ↗</a>
    </p>
  </section>

  <section class="card center desktop-card">
    <h2>Sync your Palm card</h2>
    <p class="lede">
      A small macOS app syncs your Sony Clié's Memo Pad, To Do, Date Book,
      Address and Mail with the cloud — using the Memory Stick + the Palm's
      built‑in MS Backup. The Palm itself never changes.
    </p>
    <a class="dlbtn" href="https://github.com/palmvellum/palmvellum/releases/latest" rel="external">
      Download for macOS (.dmg)
    </a>
    <p class="muted">
      <a href="{base}/desktop">How it works ↗</a> ·
      <a href="https://github.com/palmvellum/palmvellum" rel="external">source ↗</a>
    </p>
    <p class="risk">
      Unsigned — first launch: right‑click → Open. No warranty; use at your own
      risk. Tested on Sony Clié + Memory Stick + MS Backup; SD‑card Palms not
      yet tested.
    </p>
  </section>

{:else if authState.phase === 'uninvited'}
  <section class="card center">
    <h2>You're signed in.</h2>
    <p>
      <strong>{authState.email}</strong> hasn't been invited yet. We'll send
      another email when your seat opens up.
    </p>
    <p class="hint">
      Signed in with the wrong email? <a href="#sign-out" onclick={(e) => { e.preventDefault(); void authState.signOut(); }}>Sign out</a>
      and try again.
    </p>
  </section>

{:else}
  <p class="loading">{t('common.loading')}</p>
{/if}
</div>

<style>
  /* Vertically centre the sign-in / holding card in the viewport.
     Scoped to this root route only — palm routes render their own
     full-bleed chrome and never mount this page. The dvh minus a
     fixed gutter leaves room for the shell's own top/bottom padding
     so the card lands optically centred rather than pinned to the top. */
  .auth-screen {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    min-height: calc(100dvh - 6rem);
  }
  .loading {
    text-align: center;
    padding: 2rem 0;
    color: var(--ink-mute);
  }
  .card {
    background: var(--surface-lo);
    border: 1px solid var(--line);
    padding: 1.25rem 1.4rem;
    margin: 0 auto;
    max-width: 28rem;
    border-radius: 2px;
  }
  .card.center {
    text-align: center;
  }
  h1 {
    font-size: 1.6rem;
    margin: 0 0 0.4rem;
    color: var(--accent);
    letter-spacing: 0.04em;
  }
  h2 {
    font-size: 1.1rem;
    margin: 0 0 0.6rem;
    color: var(--accent);
  }
  .lede {
    color: var(--ink-dim);
    font-size: 0.92rem;
    margin: 0 0 1.1rem;
  }
  form {
    display: grid;
    gap: 0.6rem;
    text-align: left;
    margin: 0.8rem 0;
  }
  label {
    display: grid;
    gap: 0.3rem;
    color: var(--ink-mute);
    font-size: 0.85rem;
  }
  input {
    background: var(--bg);
    border: 1px solid var(--line);
    color: var(--ink);
    padding: 0.55rem 0.7rem;
    font-family: inherit;
    font-size: 0.95rem;
  }
  input.otp-input {
    font-size: 1.6rem;
    letter-spacing: 0.4em;
    text-align: center;
    font-weight: 600;
    color: var(--accent);
  }
  button {
    background: var(--accent);
    color: var(--bg);
    border: 1px solid var(--accent);
    padding: 0.55rem 1rem;
    font-family: inherit;
    font-weight: 600;
    cursor: pointer;
    margin-top: 0.2rem;
  }
  button:hover:not(:disabled) {
    background: var(--accent-dim);
  }
  button:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
  button.link {
    background: transparent;
    color: var(--ink-mute);
    border: none;
    padding: 0.2rem;
    text-decoration: underline;
    font-size: 0.85rem;
    font-weight: normal;
    margin-top: 0.4rem;
  }
  button.link:hover {
    color: var(--accent);
    background: transparent;
  }
  .muted {
    color: var(--ink-mute);
    font-size: 0.85rem;
    margin-top: 1rem;
  }
  .muted a {
    color: var(--ink-mute);
    text-decoration: underline;
  }
  .hint {
    color: var(--ink-mute);
    font-size: 0.85rem;
  }
  .ok {
    color: var(--green);
    margin: 0.6rem 0;
    font-size: 0.9rem;
  }
  .error {
    color: #ff6b6b;
    font-size: 0.85rem;
  }
  .desktop-card {
    margin-top: 1rem;
  }
  .dlbtn {
    display: inline-block;
    margin: 0.4rem 0 0.2rem;
    padding: 0.55rem 1rem;
    background: var(--accent);
    color: var(--bg);
    border-radius: 6px;
    text-decoration: none;
    font-weight: 600;
  }
  .dlbtn:hover {
    background: var(--accent-dim);
  }
  .risk {
    color: var(--ink-mute);
    font-size: 0.78rem;
    line-height: 1.4;
    margin-top: 0.6rem;
  }
</style>

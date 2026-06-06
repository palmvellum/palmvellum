<script lang="ts">
  import { onMount } from 'svelte';
  import { supabase } from '$lib/supabase';
  import { authState, magicLinkRedirect } from '$lib/auth.svelte';
  import { base } from '$app/paths';
  import { goto } from '$app/navigation';
  import { t } from '$lib/i18n.svelte';

  // Sign-in (magic link) state
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

  // Whenever auth becomes 'ready' (signed-in + invited), forward to
  // /palm — this page no longer hosts a capture form, it's just the
  // auth gate.
  $effect(() => {
    if (authState.phase === 'ready') {
      void goto(base + '/palm', { replaceState: true });
    }
  });

  onMount(() => {
    // If user lands here already signed in, fire the same redirect on
    // the very first tick.
    if (authState.phase === 'ready') {
      void goto(base + '/palm', { replaceState: true });
    }
  });
</script>

<svelte:head>
  <title>PalmVellum</title>
</svelte:head>

{#if authState.phase === 'loading'}
  <p class="loading">{t('common.loading')}</p>

{:else if authState.phase === 'unauthenticated'}
  <section class="card center">
    <h1>PalmVellum</h1>
    <p class="lede">
      {t('palm.sub')}
    </p>
    {#if signinSent}
      <p class="ok">[ok] Magic link sent to <strong>{signinEmail}</strong>. Check your inbox.</p>
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
    <p class="muted">
      <a href="https://tatliving.dev/palmvellum/">about the project ↗</a>
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

<style>
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
  }
  .error {
    color: #ff6b6b;
    font-size: 0.85rem;
  }
</style>

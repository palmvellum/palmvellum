<script lang="ts">
  import '../app.css';
  import { onMount } from 'svelte';
  import { base } from '$app/paths';
  import { authState } from '$lib/auth.svelte';
  import {
    currentLang,
    setLang,
    t,
    SUPPORTED_LANGUAGES,
    type Lang,
  } from '$lib/i18n.svelte';

  let { children } = $props();

  onMount(() => {
    void authState.init();
    // Reflect persisted lang on <html lang="..."> for SEO + a11y.
    if (typeof document !== 'undefined') {
      document.documentElement.lang = currentLang.value;
    }
  });
</script>

<div class="shell">
  <header class="topnav">
    <a class="brand" href="{base}/">
      <span class="dot"></span>
      PalmVellum
    </a>

    <nav class="links">
      {#if authState.phase === 'ready'}
        <a href="{base}/palm">{t('nav.organizers')}</a>
        <a href="{base}/settings">{t('nav.setting')}</a>
      {/if}
      <a href="/palmvellum/">{t('nav.manifesto')}</a>
      <a href="https://github.com/palmvellum/palmvellum" rel="noopener">{t('nav.github')}</a>

      <select
        class="lang-select"
        aria-label={t('nav.language')}
        value={currentLang.value}
        onchange={(e) => setLang((e.currentTarget as HTMLSelectElement).value as Lang)}
      >
        {#each SUPPORTED_LANGUAGES as L (L.code)}
          <option value={L.code}>{L.label}</option>
        {/each}
      </select>

      {#if authState.phase === 'ready'}
        <button class="signout" onclick={() => void authState.signOut()}>
          {t('nav.signOut')}
        </button>
      {:else if authState.phase === 'uninvited' || authState.phase === 'loading'}
        <span class="email-tag">{authState.email ?? '…'}</span>
      {/if}
    </nav>
  </header>

  {@render children()}
</div>

<style>
  .topnav {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0.6rem 0 1.4rem;
    border-bottom: 1px solid var(--line);
    margin-bottom: 1.5rem;
    flex-wrap: wrap;
    gap: 0.75rem;
  }
  @media (max-width: 720px) {
    .topnav {
      padding: 0.45rem 0 0.9rem;
      margin-bottom: 1rem;
      gap: 0.4rem 0.5rem;
    }
    .links {
      gap: 0.55rem;
      font-size: 0.78rem;
    }
    .brand {
      font-size: 0.95rem;
    }
  }
  @media (max-width: 480px) {
    .topnav {
      flex-direction: column;
      align-items: stretch;
      gap: 0.4rem;
    }
    .links {
      justify-content: flex-start;
      flex-wrap: wrap;
      gap: 0.6rem 0.8rem;
      font-size: 0.78rem;
    }
  }
  .brand {
    font-weight: 600;
    color: var(--ink);
    border-bottom: none;
    display: inline-flex;
    align-items: center;
    gap: 0.5rem;
    font-size: 1rem;
  }
  .brand:hover {
    background: transparent;
    color: var(--accent);
  }
  .dot {
    width: 8px;
    height: 8px;
    background: var(--accent);
    display: inline-block;
  }
  .links {
    display: flex;
    align-items: center;
    gap: 1rem;
    font-size: 0.85rem;
  }
  .links a {
    color: var(--ink-dim);
    border-bottom: 1px dotted transparent;
  }
  .links a:hover {
    background: transparent;
    color: var(--accent);
    border-bottom-color: var(--accent-dim);
  }
  .signout {
    background: none;
    border: 1px solid var(--line);
    color: var(--ink-dim);
    padding: 0.25rem 0.55rem;
    font-family: inherit;
    font-size: 0.85rem;
    cursor: pointer;
  }
  .signout:hover {
    border-color: var(--accent);
    color: var(--accent);
  }
  .email-tag {
    color: var(--ink-mute);
    font-size: 0.8rem;
  }
  .lang-select {
    background: var(--bg);
    color: var(--ink);
    border: 1px solid var(--line);
    padding: 0.25rem 0.4rem;
    font: inherit;
    font-size: 0.78rem;
    cursor: pointer;
  }
  .lang-select:hover {
    border-color: var(--accent);
  }
</style>

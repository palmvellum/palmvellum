<script lang="ts">
  import '../app.css';
  import '../android.css';
  import { onMount } from 'svelte';
  import { base } from '$app/paths';
  import { page } from '$app/state';
  import { goto } from '$app/navigation';
  import { browser } from '$app/environment';
  import { authState } from '$lib/auth.svelte';
  import { initCapacitor, isCapacitor } from '$lib/capacitor.svelte';
  import { drawer } from '$lib/drawer.svelte';
  import BottomNav from '$lib/components/BottomNav.svelte';
  import PalmDrawer from '$lib/components/palm/PalmDrawer.svelte';
  import PalmConfirm from '$lib/components/palm/PalmConfirm.svelte';
  import {
    currentLang,
    setLang,
    t,
    SUPPORTED_LANGUAGES,
    type Lang,
  } from '$lib/i18n.svelte';

  let { children } = $props();

  // The Palm chrome owns the organizer + settings surfaces — they
  // render their own title bar / drawer trigger, so the global topnav
  // is redundant there. Hide it on those routes (and always on
  // Capacitor, where the whole app is Palm-themed).
  const routePath = $derived(page.url.pathname.replace(base, '') || '/');
  // The login screen ('/') wears the same Palm silver chrome as the app
  // so it doesn't look like a different site.
  const isLoginRoute = $derived(routePath === '/');
  const palmRoute = $derived(
    routePath.startsWith('/palm') || routePath.startsWith('/settings') || isLoginRoute,
  );
  const showTopnav = $derived(!isCapacitor && !palmRoute);

  // Reflect palm-route on <html> so global CSS can override only the
  // organizer surface — the marketing landing keeps its own palette.
  $effect(() => {
    if (!browser) return;
    document.documentElement.classList.toggle('palm-route', palmRoute);
  });

  // Reflect docked-drawer state on <html> so the Palm shell can leave
  // room for the side rail on wide viewports.
  $effect(() => {
    if (!browser) return;
    // Only leave room for the side rail once the drawer is actually shown
    // (i.e. signed in) — never on the login screen.
    document.documentElement.classList.toggle(
      'drawer-docked',
      palmRoute && drawer.docked && authState.phase === 'ready',
    );
  });

  // Bounce unauthenticated visits to /palm/* or /settings back to the
  // login screen. The login screen itself ('/') must never bounce.
  $effect(() => {
    if (!palmRoute || isLoginRoute) return;
    if (authState.phase !== 'unauthenticated') return;
    void goto(base + '/', { replaceState: true });
  });

  onMount(() => {
    void authState.init();
    void initCapacitor();
    drawer.initViewport();
    // Reflect persisted lang on <html lang="..."> for SEO + a11y.
    if (typeof document !== 'undefined') {
      document.documentElement.lang = currentLang.value;
    }
  });
</script>

<div class="shell" class:palm-route={palmRoute}>
  {#if showTopnav}
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
  {/if}

  {@render children()}

  {#if !palmRoute}
    <BottomNav />
  {/if}
  {#if authState.phase === 'ready'}
    <PalmDrawer />
  {/if}
  <PalmConfirm />
</div>

<style>
  /* On palm routes (/palm/*, /settings) the Palm chrome is the only
     chrome — let the shell take the full viewport so the title bar
     can hug the top of the screen. */
  :global(html) .shell.palm-route { padding: 0; max-width: none; }
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

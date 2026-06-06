<script lang="ts">
  import '../app.css';
  import { onMount } from 'svelte';
  import { base } from '$app/paths';
  import { authState } from '$lib/auth.svelte';

  let { children } = $props();

  onMount(() => {
    void authState.init();
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
        <a href="{base}/palm">Organizers</a>
        <a href="{base}/settings">Setting</a>
      {/if}
      <a href="/palmvellum/">Manifesto</a>
      <a href="https://github.com/palmvellum/palmvellum" rel="noopener">Github</a>
      {#if authState.phase === 'ready'}
        <button class="signout" onclick={() => void authState.signOut()}>
          sign out
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
</style>

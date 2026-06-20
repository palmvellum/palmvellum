<script lang="ts">
  /**
   * /palm/apps — download hub for the companion apps:
   *   • PalmVellum desktop sync (macOS .dmg)
   *   • Palm Organizers (Android) — Standard + Cosmo edition APKs
   * Fully localised via t(); steps/flavor lists use {@html} since the
   * translated strings carry <li> markup.
   */
  import { t } from '$lib/i18n.svelte';
  import PalmAppShell from '$lib/components/palm/PalmAppShell.svelte';

  const REL = 'https://github.com/palmvellum/palmvellum/releases';
  const MAC_DMG = `${REL}/latest`;
  const APK_STD = `${REL}/download/android-v0.1.0/PalmOrganizers-0.1.0-standard.apk`;
  const APK_COSMO = `${REL}/download/android-v0.1.0/PalmOrganizers-0.1.0-cosmo.apk`;
  const GUIDE = 'https://github.com/palmvellum/palmvellum/blob/main/docs/USAGE.md';
  // Donation link — same target as the landing page's "support the research".
  const DONATE = 'https://pay.airwallex.com/hkhjmem9gpkz';
</script>

<PalmAppShell title={t('apps.heading')} backHref="/palm">
  <h1 class="pg-heading">{t('apps.heading')}</h1>
  <p class="lede">{t('apps.lede')}</p>

  <a class="support-banner" href={DONATE} target="_blank" rel="noopener noreferrer">
    <strong><span class="s-mark">$</span>{t('support.cta')}</strong>
    <span>{t('support.tagline')}</span>
  </a>

  <!-- ── macOS desktop sync ───────────────────────────── -->
  <section class="card">
    <h2>{t('apps.mac.title')}</h2>
    <p>{t('apps.mac.desc')}</p>
    <a class="dl" href={MAC_DMG} rel="external">{t('apps.mac.dl')}</a>
    <ol>{@html t('apps.mac.steps')}</ol>
    <p class="note">
      {t('apps.mac.note')}
      <a href={GUIDE} rel="external">{t('apps.mac.guide')}</a>
    </p>
  </section>

  <!-- ── Android — Palm Organizers ─────────────────────── -->
  <section class="card">
    <h2>{t('apps.android.title')}</h2>
    <p>{t('apps.android.desc')}</p>
    <div class="dlrow">
      <a class="dl" href={APK_STD} rel="external">{t('apps.android.std')}</a>
      <a class="dl" href={APK_COSMO} rel="external">{t('apps.android.cosmo')}</a>
    </div>
    <ul class="flavors">{@html t('apps.android.flavors')}</ul>
    <ol>{@html t('apps.android.steps')}</ol>
    <p class="note">{t('apps.android.note')}</p>
  </section>

  <p class="risk">{t('apps.risk')}</p>
</PalmAppShell>

<style>
  .pg-heading {
    font-size: 1.4rem;
    margin: 0 0 0.5rem;
    color: var(--ink);
  }
  .lede {
    color: var(--ink-dim);
    line-height: 1.5;
    margin: 0 0 1rem;
  }
  /* Support / donate statement — top of the page, links to the same
     Airwallex pay page as the landing's "support the research". */
  .support-banner {
    display: block;
    margin: 0 0 1.25rem;
    padding: 0.85rem 1rem;
    background: var(--surface-lo);
    border: 1px dashed var(--accent);
    border-radius: 8px;
    text-decoration: none;
    transition: background 0.12s ease;
  }
  .support-banner:hover { background: var(--accent); }
  .support-banner strong {
    display: block;
    color: var(--accent);
    font-size: 0.95rem;
    margin-bottom: 0.2rem;
  }
  .support-banner span {
    display: block;
    color: var(--ink-dim);
    font-size: 0.85rem;
    line-height: 1.4;
  }
  .support-banner:hover strong,
  .support-banner:hover span { color: #fff; }
  .s-mark {
    font-family: 'IBM Plex Mono', system-ui, monospace;
    margin-right: 0.3rem;
  }
  .card {
    border: 1px solid var(--line);
    background: var(--surface-lo);
    border-radius: 8px;
    padding: 1rem 1.1rem;
    margin-bottom: 0.9rem;
  }
  .card h2 {
    font-size: 1.05rem;
    margin: 0 0 0.5rem;
    color: var(--ink);
  }
  .card p,
  .card :global(li) {
    color: var(--ink-dim);
    line-height: 1.5;
  }
  ol,
  ul {
    margin: 0.5rem 0 0;
    padding-left: 1.25rem;
  }
  :global(.flavors) {
    list-style: none;
    padding-left: 0;
  }
  :global(.card li) {
    margin-bottom: 0.3rem;
  }
  .dl {
    display: inline-block;
    margin: 0.5rem 0;
    padding: 0.5rem 0.9rem;
    background: var(--accent);
    color: var(--bg);
    border-radius: 6px;
    text-decoration: none;
    font-weight: 600;
  }
  .dl:hover {
    background: var(--accent-dim);
  }
  .dlrow {
    display: flex;
    flex-wrap: wrap;
    gap: 0.6rem;
  }
  .note {
    margin-top: 0.75rem;
    font-size: 0.85rem;
    color: var(--ink-mute);
  }
  .note a {
    color: var(--accent);
  }
  .risk {
    color: var(--ink-mute);
    font-size: 0.82rem;
    line-height: 1.45;
    border-top: 1px solid var(--line-soft);
    padding-top: 0.75rem;
  }
</style>

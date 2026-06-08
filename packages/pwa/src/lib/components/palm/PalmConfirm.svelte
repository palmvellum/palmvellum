<script lang="ts">
  /**
   * PalmConfirm — global modal dialog backing palmConfirm(). Mounted
   * once at the root layout. Reads confirmState and renders nothing
   * unless a confirm is pending.
   */
  import { confirmState } from '$lib/confirm.svelte';
  import { t } from '$lib/i18n.svelte';
</script>

{#if confirmState.open}
  <div class="bk" onclick={() => confirmState.answer(false)} role="presentation"></div>
  <div class="dlg" role="dialog" aria-modal="true">
    <p class="msg">{confirmState.message}</p>
    <div class="row">
      <button type="button" class="btn cancel" onclick={() => confirmState.answer(false)}>
        {t('common.cancel')}
      </button>
      <button type="button" class="btn ok" onclick={() => confirmState.answer(true)}>
        {t('common.delete')}
      </button>
    </div>
  </div>
{/if}

<style>
  .bk {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.55);
    z-index: 200;
  }
  .dlg {
    position: fixed;
    z-index: 201;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    background: var(--surface-lo, #e6e6e1);
    border: 1px solid var(--line, #1a1a1a);
    box-shadow: 0 12px 36px rgba(0, 0, 0, 0.4);
    width: min(360px, calc(100vw - 2rem));
    padding: 1rem 1.1rem 0.9rem;
    border-radius: 4px;
  }
  .msg {
    margin: 0 0 0.9rem;
    color: var(--ink, #000);
    font-size: 0.95rem;
    line-height: 1.35;
    white-space: pre-line;
  }
  .row {
    display: flex;
    justify-content: flex-end;
    gap: 0.5rem;
  }
  .btn {
    min-height: 40px;
    padding: 0.5rem 1rem;
    border-radius: 3px;
    font-family: inherit;
    font-weight: 700;
    font-size: 0.9rem;
    cursor: pointer;
    border: 1px solid var(--line, #1a1a1a);
  }
  .btn.cancel {
    background: var(--surface-hi, #f4f4ee);
    color: var(--ink, #000);
  }
  .btn.ok {
    background: #8b1a1a;
    color: #fff;
    border-color: #6d2020;
  }
  .btn.ok:hover { background: #6d2020; }
</style>

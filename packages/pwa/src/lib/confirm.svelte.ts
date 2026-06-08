/**
 * palmConfirm — promise-based replacement for window.confirm().
 *
 * The Capacitor Android WebView blocks the native confirm() dialog,
 * so any organizer that called confirm() before delete silently
 * failed on device. Components await palmConfirm(message); a global
 * <PalmConfirm /> mounted in +layout.svelte renders the actual modal
 * and resolves the in-flight promise when the user picks an answer.
 */

export interface ConfirmOptions {
  /** Optional second-line context (smaller, dim). */
  detail?: string;
  /** Label of the affirmative button. */
  confirmLabel?: string;
  /** Label of the cancel button. */
  cancelLabel?: string;
  /** Render the confirm button in destructive red. */
  danger?: boolean;
}

type Pending = { resolve: (ok: boolean) => void } | null;

class ConfirmState {
  open = $state(false);
  message = $state<string>('');
  detail = $state<string | undefined>(undefined);
  confirmLabel = $state('OK');
  cancelLabel = $state('cancel');
  danger = $state(false);
  private pending: Pending = null;

  ask(message: string, opts: ConfirmOptions = {}): Promise<boolean> {
    // Only one dialog at a time — auto-reject any previous request.
    if (this.pending) this.pending.resolve(false);
    this.message = message;
    this.detail = opts.detail;
    this.confirmLabel = opts.confirmLabel ?? 'OK';
    this.cancelLabel = opts.cancelLabel ?? 'cancel';
    this.danger = !!opts.danger;
    this.open = true;
    return new Promise((resolve) => {
      this.pending = { resolve };
    });
  }

  answer(ok: boolean): void {
    const p = this.pending;
    this.pending = null;
    this.open = false;
    this.message = '';
    this.detail = undefined;
    this.danger = false;
    if (p) p.resolve(ok);
  }
}

export const confirmState = new ConfirmState();

export function palmConfirm(
  message: string,
  opts: ConfirmOptions = {},
): Promise<boolean> {
  return confirmState.ask(message, opts);
}

/**
 * palmConfirm — promise-based replacement for window.confirm().
 *
 * The Capacitor Android WebView blocks the native confirm() dialog,
 * so any organizer that called confirm() before delete silently
 * failed on device. Components await palmConfirm(message); a global
 * <PalmConfirm /> mounted in +layout.svelte renders the actual modal
 * and resolves the in-flight promise when the user picks an answer.
 */

type Pending = { resolve: (ok: boolean) => void } | null;

class ConfirmState {
  message = $state<string>('');
  open = $state(false);
  private pending: Pending = null;

  ask(message: string): Promise<boolean> {
    // Only one dialog at a time — auto-reject any previous request.
    if (this.pending) this.pending.resolve(false);
    this.message = message;
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
    if (p) p.resolve(ok);
  }
}

export const confirmState = new ConfirmState();

export function palmConfirm(message: string): Promise<boolean> {
  return confirmState.ask(message);
}

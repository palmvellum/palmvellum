/**
 * Global reactive store for the left-side navigation drawer.
 *
 * Used by PalmDrawer (the overlay) and by the hamburger button in
 * PalmAppShell.
 */

class DrawerState {
  open = $state(false);
  toggle() { this.open = !this.open; }
  show() { this.open = true; }
  close() { this.open = false; }
}

export const drawer = new DrawerState();

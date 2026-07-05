/**
 * SyncEngine — the offline-first sync coordinator.
 *
 *   - `init(userId)` wires Capacitor's Network plugin AND the
 *     browser `online`/`offline` events; both feed the single
 *     `online` field. The Capacitor plugin only works in the native
 *     wrapper, so we load it lazily and fall back silently when it
 *     isn't there (plain web PWA).
 *
 *   - `pull()` fetches every user-owned row from the four mirrored
 *     tables (events, event_drafts, records, mail_sources) and
 *     upserts them into Dexie. We never bulk-delete the local store
 *     — that would clobber rows the user created offline that haven't
 *     been pushed yet.
 *
 *   - `push()` walks the outbox in `enqueued_at` order and dispatches
 *     each item to Supabase. On success the outbox row is deleted; on
 *     failure we bump `attempts` and stash `last_error`. We stop after
 *     5 consecutive failures so a single sick row can't hammer
 *     Supabase forever.
 *
 *   - `enqueue()` is what the per-table stores call. It writes to
 *     Dexie immediately (so subsequent reads see the change) AND
 *     appends to the outbox. If we're online, it kicks off a push.
 *
 * Svelte 5 runes only — `$state`, `$derived`, `$effect`. The
 * exported singleton `sync` is consumed via `import { sync } from
 * '$lib/sync.svelte'` and read just like a regular reactive object.
 */

import { browser } from '$app/environment';
import { supabase } from './supabase';
import { db, type OutboxItem, type OutboxTable, type OutboxOp } from './db';
import { newUlid } from './ulid';

// ──────────────────────────────────────────────────────────────────
// Network plugin — optional dynamic import. Capacitor's plugin is
// only installed in the android package; on the plain web PWA we
// fall back to `window.online`/`window.offline`.
// ──────────────────────────────────────────────────────────────────

interface NetworkStatus {
  connected: boolean;
  connectionType?: string;
}
interface NetworkPlugin {
  addListener: (
    event: 'networkStatusChange',
    cb: (status: NetworkStatus) => void,
  ) => Promise<{ remove: () => Promise<void> }>;
  getStatus: () => Promise<NetworkStatus>;
}

// ──────────────────────────────────────────────────────────────────
// Paginated fetch — PostgREST enforces a hard `db.max_rows` cap
// (1000 on Supabase) that silently overrides `.limit()`. A plain
// `.select('*')` therefore returns AT MOST 1000 rows, dropping the
// rest with no error. Users with >1000 events would never receive the
// overflow on the device (invisible in the Date Book even though the
// cloud has them). Page through with `.range()` on a stable `id`
// order until a short page signals the end. Same class of bug that
// hit the `ical-feed` Edge Function (fixed 2026-06-27).
// ──────────────────────────────────────────────────────────────────

// Kept below the server cap (Supabase db.max_rows = 1000) so a short
// page reliably means "no more rows" regardless of the cap's value.
const PULL_PAGE_SIZE = 500;

async function fetchAllForUser<T>(
  table: 'events' | 'event_drafts' | 'records' | 'mail_sources',
  userId: string,
): Promise<{ data: T[] | null; error: { message: string } | null }> {
  const rows: T[] = [];
  for (let page = 0; ; page++) {
    const from = page * PULL_PAGE_SIZE;
    const to = from + PULL_PAGE_SIZE - 1;
    const res = await supabase
      .from(table)
      .select('*')
      .eq('user_id', userId)
      .order('id', { ascending: true })
      .range(from, to);
    if (res.error) return { data: null, error: res.error };
    const batch = (res.data ?? []) as T[];
    rows.push(...batch);
    if (batch.length < PULL_PAGE_SIZE) break;
  }
  return { data: rows, error: null };
}

async function loadNetworkPlugin(): Promise<NetworkPlugin | null> {
  if (!browser) return null;
  // Check Capacitor at runtime — the import only works inside the
  // native wrapper, but @capacitor/network is bundled by the android
  // app build. On plain web we get a module-not-found and fall back
  // to the window events.
  try {
    const cap = (
      globalThis as { Capacitor?: { isNativePlatform?: () => boolean } }
    ).Capacitor;
    if (!cap?.isNativePlatform?.()) return null;
    const mod = (await import(
      /* @vite-ignore */ '@capacitor/network'
    )) as { Network: NetworkPlugin };
    return mod.Network;
  } catch {
    return null;
  }
}

// ──────────────────────────────────────────────────────────────────
// SyncEngine
// ──────────────────────────────────────────────────────────────────

const PUSH_FAILURE_CIRCUIT_BREAK = 5;

export class SyncEngine {
  online = $state(false);
  pulling = $state(false);
  pushing = $state(false);
  last_pulled_at = $state<string | null>(null);
  pending_count = $state(0);
  last_error = $state<string | null>(null);

  private userId: string | null = null;
  private initialized = false;

  /** Cleanup handles for any subscriptions taken during init(). */
  private cleanups: Array<() => void | Promise<void>> = [];

  /** Coalesces concurrent push() calls — a push that's already running
   *  must not be re-entered, but any new enqueue that arrives mid-flight
   *  should trigger one more pass after the current one finishes. */
  private pushQueued = false;

  async init(userId: string): Promise<void> {
    if (!browser) return;
    if (this.initialized) {
      // Re-init with a different user (sign-out → sign-in). Tear down
      // the old listeners and start fresh.
      await this.destroyInternal();
    }
    this.userId = userId;
    this.initialized = true;

    // Seed `online` from whatever's available right now.
    this.online =
      typeof navigator !== 'undefined' && 'onLine' in navigator
        ? navigator.onLine
        : true;

    // Wire window events (works in web + Capacitor WebView).
    const onOnline = (): void => {
      void this.handleOnlineChange(true);
    };
    const onOffline = (): void => {
      void this.handleOnlineChange(false);
    };
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    this.cleanups.push(() => {
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
    });

    // Wire Capacitor Network plugin if present — gives us a more
    // reliable signal on Android (window events sometimes lag).
    const network = await loadNetworkPlugin();
    if (network) {
      try {
        const status = await network.getStatus();
        this.online = status.connected;
      } catch (e) {
        console.warn('[Sync] Network.getStatus failed', e);
      }
      try {
        const sub = await network.addListener(
          'networkStatusChange',
          (status) => {
            void this.handleOnlineChange(status.connected);
          },
        );
        this.cleanups.push(() => sub.remove());
      } catch (e) {
        console.warn('[Sync] Network.addListener failed', e);
      }
    }

    // Refresh the pending count from the persisted outbox.
    await this.refreshPendingCount();

    // Initial pull — succeeds online, no-ops offline. Either way the
    // UI has yesterday's data from Dexie in the meantime.
    void this.pull();
  }

  /** Called by network listeners. False→true transitions kick off a
   *  push then a pull. */
  private async handleOnlineChange(nowOnline: boolean): Promise<void> {
    const wasOnline = this.online;
    this.online = nowOnline;
    if (!wasOnline && nowOnline) {
      await this.push();
      await this.pull();
    }
  }

  async pull(): Promise<void> {
    if (!browser) return;
    if (!this.userId) return;
    // Don't bail on this.online — that flag can lag behind reality on
    // Capacitor at boot. Let the actual fetch fail if truly offline.
    if (this.pulling) return;
    this.pulling = true;
    this.last_error = null;
    try {
      // Fan-out the four selects. Each runs under RLS so it sees
      // only the signed-in user's rows.
      const [evRes, drRes, rcRes, msRes] = await Promise.all([
        fetchAllForUser<Record<string, unknown>>('events', this.userId),
        fetchAllForUser<Record<string, unknown>>('event_drafts', this.userId),
        fetchAllForUser<Record<string, unknown>>('records', this.userId),
        fetchAllForUser<Record<string, unknown>>('mail_sources', this.userId),
      ]);

      if (evRes.error) {
        console.warn('[Sync] pull events failed', evRes.error.message);
        this.last_error = evRes.error.message;
      } else if (evRes.data) {
        await db.events.bulkPut(evRes.data as Parameters<typeof db.events.bulkPut>[0]);
      }

      if (drRes.error) {
        console.warn('[Sync] pull event_drafts failed', drRes.error.message);
        this.last_error = drRes.error.message;
      } else if (drRes.data) {
        await db.event_drafts.bulkPut(
          drRes.data as Parameters<typeof db.event_drafts.bulkPut>[0],
        );
      }

      if (rcRes.error) {
        console.warn('[Sync] pull records failed', rcRes.error.message);
        this.last_error = rcRes.error.message;
      } else if (rcRes.data) {
        await db.records.bulkPut(rcRes.data as Parameters<typeof db.records.bulkPut>[0]);
      }

      if (msRes.error) {
        console.warn('[Sync] pull mail_sources failed', msRes.error.message);
        this.last_error = msRes.error.message;
      } else if (msRes.data) {
        await db.mail_sources.bulkPut(
          msRes.data as Parameters<typeof db.mail_sources.bulkPut>[0],
        );
      }

      this.last_pulled_at = new Date().toISOString();
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      console.warn('[Sync] pull threw', msg);
      this.last_error = msg;
    } finally {
      this.pulling = false;
    }
  }

  async push(): Promise<void> {
    if (!browser) return;
    if (!this.userId) return;
    if (!this.online) return;
    if (this.pushing) {
      // Already running; queue one more pass after current finishes.
      this.pushQueued = true;
      return;
    }
    this.pushing = true;
    let consecutiveFailures = 0;

    try {
      // Drain FIFO. Re-query each iteration in case `enqueue` appends
      // mid-push.
      // eslint-disable-next-line no-constant-condition
      while (true) {
        const next = await db.outbox.orderBy('enqueued_at').first();
        if (!next) break;
        if (consecutiveFailures >= PUSH_FAILURE_CIRCUIT_BREAK) {
          console.warn(
            '[Sync] push circuit-breaker tripped after 5 consecutive failures',
          );
          break;
        }

        const ok = await this.dispatchOutboxItem(next);
        if (ok) {
          await db.outbox.delete(next.id);
          consecutiveFailures = 0;
        } else {
          consecutiveFailures++;
        }
      }
      await this.refreshPendingCount();
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      console.warn('[Sync] push threw', msg);
      this.last_error = msg;
    } finally {
      this.pushing = false;
      if (this.pushQueued) {
        this.pushQueued = false;
        // Schedule the follow-up without recursing on the call stack.
        queueMicrotask(() => void this.push());
      }
    }
  }

  /** Returns true on success, false on a per-row error (caller bumps
   *  the failure counter). Network-level throws are caught and treated
   *  as failures so we don't crash the whole push. */
  private async dispatchOutboxItem(item: OutboxItem): Promise<boolean> {
    try {
      const table = item.table;
      if (item.op === 'insert') {
        const { error } = await supabase.from(table).insert(item.payload);
        if (error) return await this.markFailed(item, error.message);
      } else if (item.op === 'update') {
        const { error } = await supabase
          .from(table)
          .update(item.payload)
          .eq('id', item.record_id);
        if (error) return await this.markFailed(item, error.message);
      } else {
        // delete: for the tables that use soft-delete (events, records)
        // an UPDATE deleted_at is preferable, but the existing UI also
        // calls it via update(). To keep this layer simple we treat
        // op:'delete' as a hard DELETE. Callers that want soft-delete
        // should enqueue an UPDATE with { deleted_at: ... } instead.
        const { error } = await supabase
          .from(table)
          .delete()
          .eq('id', item.record_id);
        if (error) return await this.markFailed(item, error.message);
      }
      return true;
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      return await this.markFailed(item, msg);
    }
  }

  private async markFailed(item: OutboxItem, msg: string): Promise<boolean> {
    console.warn(
      `[Sync] push ${item.table}/${item.op}/${item.record_id} failed: ${msg}`,
    );
    this.last_error = msg;
    await db.outbox.update(item.id, {
      attempts: item.attempts + 1,
      last_error: msg,
    });
    return false;
  }

  /** Enqueue a write. Also applies the change to Dexie immediately so
   *  the UI sees it on the next read, and kicks off a push if online. */
  async enqueue(
    item: Omit<OutboxItem, 'id' | 'enqueued_at' | 'attempts' | 'last_error'>,
  ): Promise<void> {
    if (!browser) return;

    // 1. Apply locally.
    await this.applyLocally(item.table, item.op, item.record_id, item.payload);

    // 2. Persist to outbox. Multiple updates to the same record
    //    collapse: if an unflushed UPDATE for the same (table, id)
    //    already exists, merge the new patch into its payload instead
    //    of appending a second row. Inserts and deletes always append.
    if (item.op === 'update') {
      const existing = await db.outbox
        .where('[table+record_id]')
        .equals([item.table, item.record_id])
        .filter((o) => o.op === 'update')
        .first();
      if (existing) {
        await db.outbox.update(existing.id, {
          payload: { ...existing.payload, ...item.payload },
          last_error: null,
          attempts: 0,
        });
        await this.refreshPendingCount();
        if (this.online) void this.push();
        return;
      }
    }

    const row: OutboxItem = {
      id: newUlid(),
      table: item.table,
      op: item.op,
      record_id: item.record_id,
      payload: item.payload,
      enqueued_at: new Date().toISOString(),
      attempts: 0,
      last_error: null,
    };
    await db.outbox.add(row);
    await this.refreshPendingCount();

    // 3. Kick off a push if we have connectivity.
    if (this.online) void this.push();
  }

  /** Mirror the queued change into Dexie so the next read reflects it. */
  private async applyLocally(
    table: OutboxTable,
    op: OutboxOp,
    recordId: string,
    payload: Record<string, unknown>,
  ): Promise<void> {
    if (op === 'delete') {
      if (table === 'events') await db.events.delete(recordId);
      else if (table === 'event_drafts') await db.event_drafts.delete(recordId);
      else if (table === 'records') await db.records.delete(recordId);
      else if (table === 'mail_sources') await db.mail_sources.delete(recordId);
      return;
    }

    if (op === 'insert') {
      if (table === 'events') {
        await db.events.put(payload as Parameters<typeof db.events.put>[0]);
      } else if (table === 'event_drafts') {
        await db.event_drafts.put(
          payload as Parameters<typeof db.event_drafts.put>[0],
        );
      } else if (table === 'records') {
        await db.records.put(payload as Parameters<typeof db.records.put>[0]);
      } else if (table === 'mail_sources') {
        await db.mail_sources.put(
          payload as Parameters<typeof db.mail_sources.put>[0],
        );
      }
      return;
    }

    // op === 'update' — merge patch onto the existing row.
    if (table === 'events') {
      const existing = await db.events.get(recordId);
      if (existing) {
        await db.events.put({
          ...existing,
          ...(payload as Partial<typeof existing>),
        });
      }
    } else if (table === 'event_drafts') {
      const existing = await db.event_drafts.get(recordId);
      if (existing) {
        await db.event_drafts.put({
          ...existing,
          ...(payload as Partial<typeof existing>),
        });
      }
    } else if (table === 'records') {
      const existing = await db.records.get(recordId);
      if (existing) {
        await db.records.put({
          ...existing,
          ...(payload as Partial<typeof existing>),
        });
      }
    } else if (table === 'mail_sources') {
      const existing = await db.mail_sources.get(recordId);
      if (existing) {
        await db.mail_sources.put({
          ...existing,
          ...(payload as Partial<typeof existing>),
        });
      }
    }
  }

  private async refreshPendingCount(): Promise<void> {
    this.pending_count = await db.outbox.count();
  }

  destroy(): void {
    void this.destroyInternal();
  }

  private async destroyInternal(): Promise<void> {
    for (const c of this.cleanups) {
      try {
        await c();
      } catch (e) {
        console.warn('[Sync] cleanup threw', e);
      }
    }
    this.cleanups = [];
    this.initialized = false;
    this.userId = null;
  }
}

export const sync = new SyncEngine();

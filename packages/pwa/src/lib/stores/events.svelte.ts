/**
 * events store — offline-first facade for the `events` table.
 *
 * Components used to call `supabase.from('events')` directly; the
 * offline-first migration routes them through this module instead.
 * Reads come from Dexie (instant, works offline); writes hit Dexie
 * immediately and are queued in the outbox for the SyncEngine to
 * push when network returns.
 *
 * Row shape matches the existing CalendarEvent + database column
 * set so the calling components don't need to relearn anything.
 */

import { db, type LocalEvent } from '../db';
import { sync } from '../sync.svelte';
import { newUlid } from '../ulid';
import { authState } from '../auth.svelte';

export type EventRow = LocalEvent;

export interface NewEvent {
  title: string;
  start_at: string;
  end_at?: string | null;
  all_day?: boolean;
  location?: string | null;
  notes?: string | null;
  alarm_minutes?: number | null;
  repeat_rule?: string | null;
  source?: string;
  device_id?: string | null;
}

export interface EventWindow {
  from: Date;
  to: Date;
}

/** List events for the signed-in user, soft-delete filtered. Optional
 *  `[from, to)` window matches against `start_at`. */
export async function listEvents(window?: EventWindow): Promise<EventRow[]> {
  const uid = authState.userId;
  if (!uid) return [];
  let rows: EventRow[];
  if (window) {
    const fromIso = window.from.toISOString();
    const toIso = window.to.toISOString();
    rows = await db.events
      .where('[user_id+start_at]')
      .between([uid, fromIso], [uid, toIso], true, false)
      .toArray();
  } else {
    rows = await db.events.where({ user_id: uid }).toArray();
  }
  return rows
    .filter((e) => e.deleted_at === null)
    .sort(
      (a, b) =>
        new Date(a.start_at).getTime() - new Date(b.start_at).getTime(),
    );
}

export async function getEvent(id: string): Promise<EventRow | null> {
  const row = await db.events.get(id);
  return row ?? null;
}

export async function createEvent(e: NewEvent): Promise<EventRow> {
  const uid = authState.userId;
  if (!uid) throw new Error('not signed in');
  const now = new Date().toISOString();
  const row: EventRow = {
    id: newUlid(),
    user_id: uid,
    title: e.title,
    start_at: e.start_at,
    end_at: e.end_at ?? null,
    all_day: e.all_day ?? false,
    location: e.location ?? null,
    notes: e.notes ?? null,
    alarm_minutes: e.alarm_minutes ?? null,
    repeat_rule: e.repeat_rule ?? null,
    source: e.source ?? 'web',
    device_id: e.device_id ?? null,
    palm_record_uid: null,
    created_at: now,
    updated_at: now,
    deleted_at: null,
  };
  await sync.enqueue({
    table: 'events',
    op: 'insert',
    record_id: row.id,
    payload: row as unknown as Record<string, unknown>,
  });
  return row;
}

export async function updateEvent(
  id: string,
  patch: Partial<EventRow>,
): Promise<void> {
  const now = new Date().toISOString();
  const payload: Record<string, unknown> = {
    ...patch,
    updated_at: now,
  };
  await sync.enqueue({
    table: 'events',
    op: 'update',
    record_id: id,
    payload,
  });
}

/** Soft-delete — sets `deleted_at`. Matches existing UI semantics. */
export async function deleteEvent(id: string): Promise<void> {
  const now = new Date().toISOString();
  await sync.enqueue({
    table: 'events',
    op: 'update',
    record_id: id,
    payload: { deleted_at: now, updated_at: now },
  });
}

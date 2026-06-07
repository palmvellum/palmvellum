/**
 * mail_sources store — offline-first facade for the mail_sources
 * table (per-user feed configs that drive the digest pipeline).
 */

import { db, type LocalMailSource } from '../db';
import { sync } from '../sync.svelte';
import { newUlid } from '../ulid';
import { authState } from '../auth.svelte';

export type MailSourceRow = LocalMailSource;

export interface NewMailSource {
  name: string;
  source_type: 'url' | 'topic';
  url?: string | null;
  topic?: string | null;
  fetch_time: string; // "HH:MM:SS"
  timezone: string;
  output_language?: string | null;
  digest_hint?: string | null;
  enabled?: boolean;
}

export async function listMailSources(): Promise<MailSourceRow[]> {
  const uid = authState.userId;
  if (!uid) return [];
  const rows = await db.mail_sources
    .where('[user_id+created_at]')
    .between([uid, ''], [uid, '￿'], true, true)
    .toArray();
  return rows.sort(
    (a, b) =>
      new Date(a.created_at).getTime() - new Date(b.created_at).getTime(),
  );
}

export async function createMailSource(
  s: NewMailSource,
): Promise<MailSourceRow> {
  const uid = authState.userId;
  if (!uid) throw new Error('not signed in');
  const now = new Date().toISOString();
  const row: MailSourceRow = {
    id: newUlid(),
    user_id: uid,
    name: s.name,
    url: s.url ?? null,
    topic: s.topic ?? null,
    source_type: s.source_type,
    output_language: s.output_language ?? null,
    fetch_time: s.fetch_time,
    timezone: s.timezone,
    enabled: s.enabled ?? true,
    last_fetched_at: null,
    last_error: null,
    digest_hint: s.digest_hint ?? null,
    created_at: now,
    updated_at: now,
  };
  await sync.enqueue({
    table: 'mail_sources',
    op: 'insert',
    record_id: row.id,
    payload: row as unknown as Record<string, unknown>,
  });
  return row;
}

export async function updateMailSource(
  id: string,
  patch: Partial<MailSourceRow>,
): Promise<void> {
  const now = new Date().toISOString();
  await sync.enqueue({
    table: 'mail_sources',
    op: 'update',
    record_id: id,
    payload: { ...patch, updated_at: now },
  });
}

export async function deleteMailSource(id: string): Promise<void> {
  await sync.enqueue({
    table: 'mail_sources',
    op: 'delete',
    record_id: id,
    payload: {},
  });
}

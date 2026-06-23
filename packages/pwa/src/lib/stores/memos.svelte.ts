/**
 * memos store — offline-first facade for records of type
 * 'thought' (notes + agent prompts) and 'aiquery' (legacy Q&A).
 *
 * The (AI) prefix detection still routes a thought into the
 * agentic worker via ai_status='pending' — matches the existing
 * <MemoPad /> behaviour.
 */

import { db, type LocalRecord } from '../db';
import { sync } from '../sync.svelte';
import { newUlid } from '../ulid';
import { authState } from '../auth.svelte';

export type MemoType = 'thought' | 'aiquery';

export interface MemoMetadata {
  palm_category_name?: string;
  upload_path?: string;
  upload_filename?: string;
  upload_mimetype?: string;
  upload_size?: number;
  [k: string]: unknown;
}

export type MemoRow = LocalRecord & {
  type: MemoType;
  metadata: MemoMetadata;
};

export interface NewMemo {
  body: string;
  type?: MemoType;
  metadata?: MemoMetadata;
  /** When true, force ai_status='pending' regardless of the body. */
  ai?: boolean;
}

export async function listMemos(): Promise<MemoRow[]> {
  const uid = authState.userId;
  if (!uid) return [];
  const rows = await db.records
    .where('[user_id+updated_at]')
    .between([uid, ''], [uid, '￿'], true, true)
    .toArray();
  return rows
    .filter(
      (r) =>
        (r.type === 'thought' || r.type === 'aiquery') && r.deleted_at === null,
    )
    .sort(
      // Order by creation time, not updated_at: a HotSync re-stamps updated_at
      // on every record, which would otherwise reshuffle the whole list.
      (a, b) =>
        new Date(b.created_at).getTime() - new Date(a.created_at).getTime(),
    ) as MemoRow[];
}

export async function createMemo(m: NewMemo): Promise<MemoRow> {
  const uid = authState.userId;
  if (!uid) throw new Error('not signed in');
  const now = new Date().toISOString();
  const body = m.body;
  const isAgent = m.ai ?? /^\s*\(ai\)/i.test(body);
  const type = m.type ?? 'thought';
  const row: MemoRow = {
    id: newUlid(),
    user_id: uid,
    type,
    posture: 'open',
    body,
    tags: [],
    metadata: {
      palm_category_name: isAgent ? 'AI Agent' : 'Unfiled',
      ...(m.metadata ?? {}),
    },
    created_at: now,
    updated_at: now,
    deleted_at: null,
    source: 'web',
    device_id: null,
    ai_status: isAgent ? 'pending' : null,
    ai_response: null,
    ai_model: null,
    ai_tokens_in: null,
    ai_tokens_out: null,
    ai_error: null,
  };
  await sync.enqueue({
    table: 'records',
    op: 'insert',
    record_id: row.id,
    payload: row as unknown as Record<string, unknown>,
  });
  return row;
}

export async function updateMemo(
  id: string,
  patch: Partial<MemoRow>,
): Promise<void> {
  const now = new Date().toISOString();
  await sync.enqueue({
    table: 'records',
    op: 'update',
    record_id: id,
    payload: { ...patch, updated_at: now },
  });
}

export async function deleteMemo(id: string): Promise<void> {
  const now = new Date().toISOString();
  await sync.enqueue({
    table: 'records',
    op: 'update',
    record_id: id,
    payload: { deleted_at: now, updated_at: now },
  });
}

/**
 * todos store — offline-first facade for records.type='todo'.
 *
 * Mirrors the structured metadata the existing <TodoList /> already
 * writes (palm_due_date, palm_priority, palm_completed, palm_notes,
 * palm_category_name).
 */

import { db, type LocalRecord } from '../db';
import { sync } from '../sync.svelte';
import { newUlid } from '../ulid';
import { authState } from '../auth.svelte';

export interface TodoMetadata {
  palm_due_date?: string;
  palm_priority?: number;
  palm_completed?: boolean;
  palm_notes?: string;
  palm_category_name?: string;
  agent_summary?: string;
  agent_result_memo?: string;
  agent_processed?: boolean;
  [k: string]: unknown;
}

export type TodoRow = LocalRecord & { type: 'todo'; metadata: TodoMetadata };

export interface NewTodo {
  body: string;
  due?: string;
  priority?: number;
  notes?: string;
  category?: string;
  ai?: boolean;
}

export async function listTodos(): Promise<TodoRow[]> {
  const uid = authState.userId;
  if (!uid) return [];
  const rows = await db.records
    .where('[user_id+updated_at]')
    .between([uid, ''], [uid, '￿'], true, true)
    .toArray();
  return rows
    .filter((r) => r.type === 'todo' && r.deleted_at === null)
    .sort(
      (a, b) =>
        new Date(b.updated_at).getTime() - new Date(a.updated_at).getTime(),
    ) as TodoRow[];
}

export async function createTodo(t: NewTodo): Promise<TodoRow> {
  const uid = authState.userId;
  if (!uid) throw new Error('not signed in');
  const now = new Date().toISOString();
  const isAgent = t.ai ?? /^\s*\(ai\)/i.test(t.body);
  const row: TodoRow = {
    id: newUlid(),
    user_id: uid,
    type: 'todo',
    posture: 'open',
    body: t.body,
    tags: [],
    metadata: {
      palm_due_date: t.due ?? '',
      palm_priority: t.priority ?? 3,
      palm_completed: false,
      palm_notes: t.notes ?? '',
      palm_category_name: t.category ?? 'Unfiled',
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

export async function updateTodo(
  id: string,
  patch: Partial<TodoRow>,
): Promise<void> {
  const now = new Date().toISOString();
  await sync.enqueue({
    table: 'records',
    op: 'update',
    record_id: id,
    payload: { ...patch, updated_at: now },
  });
}

export async function toggleTodoDone(id: string, done: boolean): Promise<void> {
  const existing = await db.records.get(id);
  const meta = {
    ...((existing?.metadata as TodoMetadata | undefined) ?? {}),
    palm_completed: done,
  };
  await updateTodo(id, { metadata: meta });
}

export async function deleteTodo(id: string): Promise<void> {
  const now = new Date().toISOString();
  await sync.enqueue({
    table: 'records',
    op: 'update',
    record_id: id,
    payload: { deleted_at: now, updated_at: now },
  });
}

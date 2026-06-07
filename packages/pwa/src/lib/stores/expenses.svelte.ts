/**
 * expenses store — offline-first facade for records.type='expense'.
 *
 * Matches the existing <ExpenseLog /> schema: vendor in records.body,
 * the Palm Expense fields in metadata.
 */

import { db, type LocalRecord } from '../db';
import { sync } from '../sync.svelte';
import { newUlid } from '../ulid';
import { authState } from '../auth.svelte';

export interface ExpenseMetadata {
  palm_amount?: number;
  palm_currency?: string;
  palm_vendor?: string;
  palm_expense_type?: string;
  palm_payment?: string;
  palm_expense_date?: string;
  palm_city?: string;
  palm_attendees?: string;
  palm_notes?: string;
  palm_category_name?: string;
  [k: string]: unknown;
}

export type ExpenseRow = LocalRecord & {
  type: 'expense';
  metadata: ExpenseMetadata;
};

export interface NewExpense {
  vendor: string;
  metadata: ExpenseMetadata;
}

export async function listExpenses(): Promise<ExpenseRow[]> {
  const uid = authState.userId;
  if (!uid) return [];
  const rows = await db.records.where({ user_id: uid }).toArray();
  return rows
    .filter((r) => r.type === 'expense' && r.deleted_at === null)
    .sort(
      (a, b) =>
        new Date(b.created_at).getTime() - new Date(a.created_at).getTime(),
    ) as ExpenseRow[];
}

export async function createExpense(e: NewExpense): Promise<ExpenseRow> {
  const uid = authState.userId;
  if (!uid) throw new Error('not signed in');
  const now = new Date().toISOString();
  const row: ExpenseRow = {
    id: newUlid(),
    user_id: uid,
    type: 'expense',
    posture: 'open',
    body: e.vendor,
    tags: [],
    metadata: e.metadata,
    created_at: now,
    updated_at: now,
    deleted_at: null,
    source: 'web',
    device_id: null,
    ai_status: null,
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

export async function updateExpense(
  id: string,
  patch: Partial<ExpenseRow>,
): Promise<void> {
  const now = new Date().toISOString();
  await sync.enqueue({
    table: 'records',
    op: 'update',
    record_id: id,
    payload: { ...patch, updated_at: now },
  });
}

export async function deleteExpense(id: string): Promise<void> {
  const now = new Date().toISOString();
  await sync.enqueue({
    table: 'records',
    op: 'update',
    record_id: id,
    payload: { deleted_at: now, updated_at: now },
  });
}

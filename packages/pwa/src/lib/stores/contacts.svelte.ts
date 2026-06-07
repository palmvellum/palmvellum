/**
 * contacts store — offline-first facade for records.type='contact'.
 *
 * The display name is stored in records.body so list queries can
 * sort/search without cracking metadata — matches the existing
 * <AddressBook /> convention.
 */

import { db, type LocalRecord } from '../db';
import { sync } from '../sync.svelte';
import { newUlid } from '../ulid';
import { authState } from '../auth.svelte';

export type PhoneType =
  | 'Work'
  | 'Home'
  | 'Fax'
  | 'Other'
  | 'E-mail'
  | 'Main'
  | 'Pager'
  | 'Mobile';

export interface PhoneEntry {
  label: PhoneType;
  value: string;
}

export interface ContactMetadata {
  palm_first_name?: string;
  palm_last_name?: string;
  palm_company?: string;
  palm_title?: string;
  palm_phones?: PhoneEntry[];
  palm_address?: string;
  palm_city?: string;
  palm_state?: string;
  palm_zip?: string;
  palm_country?: string;
  palm_notes?: string;
  palm_category_name?: string;
  [k: string]: unknown;
}

export type ContactRow = LocalRecord & {
  type: 'contact';
  metadata: ContactMetadata;
};

export interface NewContact {
  displayName: string;
  metadata: ContactMetadata;
}

export async function listContacts(): Promise<ContactRow[]> {
  const uid = authState.userId;
  if (!uid) return [];
  const rows = await db.records.where({ user_id: uid }).toArray();
  return rows
    .filter((r) => r.type === 'contact' && r.deleted_at === null)
    .sort((a, b) =>
      (a.body ?? '').localeCompare(b.body ?? '', undefined, {
        sensitivity: 'base',
      }),
    ) as ContactRow[];
}

export async function createContact(c: NewContact): Promise<ContactRow> {
  const uid = authState.userId;
  if (!uid) throw new Error('not signed in');
  const now = new Date().toISOString();
  const row: ContactRow = {
    id: newUlid(),
    user_id: uid,
    type: 'contact',
    posture: 'open',
    body: c.displayName,
    tags: [],
    metadata: c.metadata,
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

export async function updateContact(
  id: string,
  patch: Partial<ContactRow>,
): Promise<void> {
  const now = new Date().toISOString();
  await sync.enqueue({
    table: 'records',
    op: 'update',
    record_id: id,
    payload: { ...patch, updated_at: now },
  });
}

export async function deleteContact(id: string): Promise<void> {
  const now = new Date().toISOString();
  await sync.enqueue({
    table: 'records',
    op: 'update',
    record_id: id,
    payload: { deleted_at: now, updated_at: now },
  });
}

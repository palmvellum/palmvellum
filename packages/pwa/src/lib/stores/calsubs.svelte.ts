/**
 * calsubs store — read-only external calendar subscriptions + .ics import.
 *
 * Web port of the native app's data/CalendarSubscriptions.kt. A subscription
 * is a name + an iCal feed URL (e.g. a Google Calendar "Secret address in
 * iCal format").
 *
 * The subscription LIST is a cloud-synced record (type='calsub') — body holds
 * the URL, metadata.name the display name — so feeds you add on the web show
 * up on Android (and vice versa) through the normal records sync. The id is
 * deterministic ("calsub" + the URL's Java hashCode), matching the native
 * CalSubs.idFor, so the same feed on two devices de-dupes to one row. The
 * refresh cadence (intervalHours) stays device-local in localStorage.
 *
 * The events a feed pulls in go through the events sync with a deterministic
 * id too. Browsers can't fetch most feeds cross-origin (no CORS), so the URL
 * fetch is proxied through the `fetch-ics` Supabase Edge Function; the .ics
 * FILE import is fully client-side. Read-only: events removed upstream are
 * NOT deleted locally — a deliberate simplification matching native.
 */

import { browser } from '$app/environment';
import { supabase } from '../supabase';
import { db, type LocalRecord } from '../db';
import { sync } from '../sync.svelte';
import { authState } from '../auth.svelte';
import { getEvent, createEvent } from './events.svelte';
import { parseIcs, type IcsEvent } from '../ics';
import type { LocalEvent } from '../db';

export interface CalSub {
  name: string;
  url: string;
}

// Device-local refresh prefs (cadence + last run); the subscription list
// itself lives in synced records, not here.
const PREF_KEY = 'palmvellum.calsubs.prefs.v1';

interface Prefs {
  intervalHours: number; // 0 = manual only
  lastRefreshAt: string | null;
}

const DEFAULTS: Prefs = { intervalHours: 0, lastRefreshAt: null };

function readPrefs(): Prefs {
  if (!browser) return { ...DEFAULTS };
  try {
    const raw = localStorage.getItem(PREF_KEY);
    if (!raw) return { ...DEFAULTS };
    return { ...DEFAULTS, ...(JSON.parse(raw) as Partial<Prefs>) };
  } catch {
    return { ...DEFAULTS };
  }
}

/** Java String.hashCode — replicated so a URL maps to the SAME deterministic
 *  record/event id on web and on native, avoiding duplicates across devices. */
function javaHashCode(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  return Math.abs(h);
}

/** Subscription record id (matches native CalSubs.idFor). */
function calsubId(url: string): string {
  return 'calsub' + javaHashCode(url);
}

function icsEventId(url: string, e: IcsEvent): string {
  const key = e.uid ?? e.summary + e.startIso;
  return 'ics' + javaHashCode(url + '|' + key);
}

class CalSubsState {
  subs = $state<CalSub[]>([]);
  intervalHours = $state<number>(readPrefs().intervalHours);
  lastRefreshAt = $state<string | null>(readPrefs().lastRefreshAt);
  refreshing = $state(false);

  constructor() {
    if (browser) void this.load();
  }

  private persistPrefs(): void {
    if (!browser) return;
    try {
      localStorage.setItem(
        PREF_KEY,
        JSON.stringify({
          intervalHours: this.intervalHours,
          lastRefreshAt: this.lastRefreshAt,
        } satisfies Prefs),
      );
    } catch {
      /* private mode — ignore */
    }
  }

  /** (Re)load the subscription list from the local (synced) records table. */
  async load(): Promise<void> {
    if (!browser) return;
    const uid = authState.userId;
    if (!uid) {
      this.subs = [];
      return;
    }
    const rows = await db.records.where({ user_id: uid }).toArray();
    this.subs = rows
      .filter((r) => r.type === 'calsub' && r.deleted_at === null)
      .sort((a, b) => (b.updated_at ?? '').localeCompare(a.updated_at ?? ''))
      .map((r) => ({
        name: ((r.metadata?.name as string | undefined) || r.body) ?? '',
        url: r.body ?? '',
      }));
  }

  async add(sub: CalSub): Promise<void> {
    const uid = authState.userId;
    if (!uid) return;
    const url = sub.url.trim();
    if (!url || this.subs.some((s) => s.url === url)) return;
    const name = sub.name.trim() || url;
    const id = calsubId(url);
    const now = new Date().toISOString();
    const existing = await db.records.get(id);
    if (existing) {
      await sync.enqueue({
        table: 'records',
        op: 'update',
        record_id: id,
        payload: { body: url, metadata: { name }, deleted_at: null, updated_at: now },
      });
    } else {
      const row: LocalRecord = {
        id,
        user_id: uid,
        type: 'calsub',
        posture: 'open',
        body: url,
        tags: [],
        metadata: { name },
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
        record_id: id,
        payload: row as unknown as Record<string, unknown>,
      });
    }
    await this.load();
  }

  async remove(url: string): Promise<void> {
    const id = calsubId(url);
    const now = new Date().toISOString();
    await sync.enqueue({
      table: 'records',
      op: 'update',
      record_id: id,
      payload: { deleted_at: now, updated_at: now },
    });
    await this.load();
  }

  setIntervalHours(h: number): void {
    this.intervalHours = h;
    this.persistPrefs();
  }

  /** One-off import of a .ics document's VEVENTs as new events. */
  async importText(text: string): Promise<number> {
    const parsed = parseIcs(text);
    for (const e of parsed) {
      await createEvent({
        title: e.summary,
        start_at: e.startIso,
        end_at: e.endIso,
        all_day: e.allDay,
        location: e.location,
        notes: e.description,
        source: 'ics-import',
      });
    }
    return parsed.length;
  }

  /** Upsert one feed event under a deterministic id, skipping unchanged
   *  rows so re-fetching doesn't churn the sync queue. Returns 1 if it
   *  wrote, 0 if unchanged. */
  private async upsertFeedEvent(url: string, e: IcsEvent): Promise<number> {
    const uid = authState.userId;
    if (!uid) return 0;
    const id = icsEventId(url, e);
    const existing = await getEvent(id);
    if (
      existing &&
      existing.title === e.summary &&
      existing.start_at === e.startIso &&
      existing.end_at === (e.endIso ?? null) &&
      existing.all_day === e.allDay &&
      existing.location === (e.location ?? null) &&
      existing.notes === (e.description ?? null) &&
      existing.deleted_at === null
    ) {
      return 0;
    }
    const now = new Date().toISOString();
    if (existing) {
      await sync.enqueue({
        table: 'events',
        op: 'update',
        record_id: id,
        payload: {
          title: e.summary,
          start_at: e.startIso,
          end_at: e.endIso ?? null,
          all_day: e.allDay,
          location: e.location ?? null,
          notes: e.description ?? null,
          deleted_at: null,
          updated_at: now,
        },
      });
    } else {
      const row: LocalEvent = {
        id,
        user_id: uid,
        title: e.summary,
        start_at: e.startIso,
        end_at: e.endIso ?? null,
        all_day: e.allDay,
        // Subscribed events are tz-independent all-day dates or true UTC
        // instants — no per-event zone to anchor.
        tz: null,
        location: e.location ?? null,
        notes: e.description ?? null,
        alarm_minutes: null,
        repeat_rule: null,
        source: 'ics-sub',
        device_id: null,
        palm_record_uid: null,
        created_at: now,
        updated_at: now,
        deleted_at: null,
      };
      await sync.enqueue({
        table: 'events',
        op: 'insert',
        record_id: id,
        payload: row as unknown as Record<string, unknown>,
      });
    }
    return 1;
  }

  /** Fetch every subscribed feed (via the proxy) and upsert its events.
   *  Returns the number of events added/updated; throws on the last
   *  fetch error if nothing succeeded. */
  async refresh(): Promise<number> {
    if (this.refreshing) return 0;
    await this.load();
    if (this.subs.length === 0) return 0;
    this.refreshing = true;
    let changed = 0;
    let lastError: string | null = null;
    try {
      for (const sub of this.subs) {
        let text: string;
        try {
          const { data, error } = await supabase.functions.invoke('fetch-ics', {
            body: { url: sub.url },
          });
          if (error) throw new Error(error.message);
          if (data?.error) throw new Error(data.error);
          text = String(data?.text ?? '');
        } catch (e) {
          lastError = e instanceof Error ? e.message : String(e);
          continue;
        }
        for (const e of parseIcs(text)) {
          changed += await this.upsertFeedEvent(sub.url, e);
        }
      }
      this.lastRefreshAt = new Date().toISOString();
      this.persistPrefs();
    } finally {
      this.refreshing = false;
    }
    if (changed === 0 && lastError) throw new Error(lastError);
    return changed;
  }

  /** Best-effort refresh on app open, throttled by intervalHours. */
  async autoRefresh(): Promise<void> {
    if (!browser || !sync.online) return;
    if (!authState.userId) return;
    await this.load();
    if (this.subs.length === 0) return;
    const last = this.lastRefreshAt ? new Date(this.lastRefreshAt).getTime() : 0;
    const ageH = (Date.now() - last) / 3_600_000;
    const minH = this.intervalHours > 0 ? this.intervalHours : 0;
    if (last && ageH < minH) return;
    try {
      await this.refresh();
    } catch {
      /* best effort — surfaced explicitly only on manual refresh */
    }
  }
}

export const calsubs = new CalSubsState();

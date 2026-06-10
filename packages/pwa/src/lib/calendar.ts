/**
 * Small date/time helpers for the calendar route.
 *
 * Pure functions only — no Supabase, no Svelte state. The view layer
 * owns the timezone (read from user_settings) and feeds it in where
 * needed.
 *
 * We deliberately avoid an external date library; Date.UTC and
 * Intl.DateTimeFormat cover everything a month-grid v0.4 needs.
 */

export interface CalendarEvent {
  id: string;
  user_id: string;
  title: string;
  start_at: string; // ISO
  end_at: string | null;
  all_day: boolean;
  location: string | null;
  notes: string | null;
  alarm_minutes: number | null;
  repeat_rule: string | null;
  source: string;
  deleted_at: string | null;
  updated_at: string;
}

/** Start of the month containing `d`, at 00:00 local. */
export function startOfMonth(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), 1, 0, 0, 0, 0);
}

/** Start of the next month after `d`. */
export function startOfNextMonth(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth() + 1, 1, 0, 0, 0, 0);
}

/** Same calendar date as `d` at midnight local. */
export function atMidnight(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate(), 0, 0, 0, 0);
}

/**
 * Day-of-week index where the start-of-week=0..6.
 * `weekStart`: 0 = Sunday (US), 1 = Monday (HK/ISO, default).
 */
export function isoDow(d: Date, weekStart: 0 | 1 = 1): number {
  // JS Date.getDay() is Sun=0..Sat=6. Shift so the chosen start day → 0.
  return (d.getDay() - weekStart + 7) % 7;
}

/**
 * 6×7 grid (42 days) starting from the first day of the user's chosen
 * week (`weekStart`) on or before the first day of `month`. Includes
 * leading days from the prior month and trailing days from the next
 * month so every visual row is full.
 */
export function monthGridDays(month: Date, weekStart: 0 | 1 = 1): Date[] {
  const first = startOfMonth(month);
  const lead = isoDow(first, weekStart); // 0..6
  const out: Date[] = [];
  for (let i = 0; i < 42; i++) {
    const d = new Date(first);
    d.setDate(first.getDate() - lead + i);
    out.push(d);
  }
  return out;
}

/** YYYY-MM-DD using the device timezone. */
export function ymd(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/** "Mon 4 Jun" style. */
export function shortDayLabel(d: Date): string {
  return d.toLocaleDateString(undefined, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
  });
}

/** "June 2026". */
export function monthLabel(d: Date): string {
  return d.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
}

/** "14:00" using 24-hour locale-neutral format. */
export function hhmm(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
}

/**
 * Group active (non-deleted) events by their local YMD start date.
 * Multi-day events appear under their start date only — v0.4.1 will
 * spread them across the days they span.
 */
export function bucketByDay<T extends CalendarEvent>(events: T[]): Map<string, T[]> {
  const out = new Map<string, T[]>();
  for (const e of events) {
    if (e.deleted_at) continue;
    const key = ymd(new Date(e.start_at));
    const list = out.get(key);
    if (list) list.push(e);
    else out.set(key, [e]);
  }
  // Sort each day's list by start time
  for (const list of out.values()) {
    list.sort((a, b) => a.start_at.localeCompare(b.start_at));
  }
  return out;
}

/**
 * Convert an <input type="datetime-local"> string ("2026-06-04T14:30")
 * to an ISO-8601 timestamp in the device's local timezone offset so
 * that round-tripping through Supabase (TIMESTAMPTZ) preserves the
 * wall-clock time the user typed.
 */
export function localInputToISO(input: string): string {
  // input is "YYYY-MM-DDTHH:MM"; new Date() interprets it as local.
  if (!input) return '';
  return new Date(input).toISOString();
}

/**
 * Inverse: ISO string → "YYYY-MM-DDTHH:MM" for <input type="datetime-local">.
 * Uses device-local fields, not UTC.
 */
export function isoToLocalInput(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}`
  );
}

export function sameDay(a: Date, b: Date): boolean {
  return ymd(a) === ymd(b);
}

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
  start_at: string; // ISO (UTC instant; or UTC-midnight pin for all-day)
  end_at: string | null;
  all_day: boolean;
  tz?: string | null; // IANA zone the timed wall-clock is anchored to
  // Set when a row is a dated to-do surfaced as an all-day pseudo-event.
  kind?: 'todo';
  todo_completed?: boolean;
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

// ──────────────────────────────────────────────────────────────────
// Timezone-aware helpers
//
// The Date Book has a *view timezone* (the lens the grid is read
// through) and each timed event carries its own *event timezone*
// (the zone its wall-clock is anchored to). Instants are always
// stored as UTC ("…Z") in `start_at`/`end_at`; these helpers convert
// between a wall-clock in an arbitrary IANA zone and that UTC instant.
//
// All-day events are deliberately tz-INDEPENDENT: they are pinned to
// UTC midnight (`YYYY-MM-DDT00:00:00.000Z`) and read back via UTC so
// the same calendar date shows in every zone — this is what fixes the
// "Apple shows every all-day event one day early" bug, which was
// caused by storing all-day events at *local* midnight (e.g. HK
// 00:00 = previous-day 16:00 UTC) and then reading the UTC date.
// ──────────────────────────────────────────────────────────────────

/** Default Date Book timezone when the user has not chosen one. */
export const DEFAULT_TZ = 'Asia/Hong_Kong';

/** Curated IANA zones offered in the Date Book timezone pickers. */
export const TZ_OPTIONS: readonly string[] = [
  'Asia/Hong_Kong',
  'Asia/Taipei',
  'Asia/Shanghai',
  'Asia/Tokyo',
  'Asia/Seoul',
  'Asia/Singapore',
  'Asia/Bangkok',
  'Asia/Kolkata',
  'Asia/Dubai',
  'Europe/London',
  'Europe/Paris',
  'Europe/Moscow',
  'America/New_York',
  'America/Chicago',
  'America/Los_Angeles',
  'America/Sao_Paulo',
  'Australia/Sydney',
  'Pacific/Auckland',
  'UTC',
];

/** Merge the curated list with the device zone and any extra in-use
 *  zones (e.g. an event's own tz) so every selectable value appears. */
export function tzChoices(...extra: (string | null | undefined)[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const z of [deviceTz(), ...TZ_OPTIONS, ...extra]) {
    if (z && !seen.has(z)) {
      seen.add(z);
      out.push(z);
    }
  }
  return out;
}

/** The device's own IANA timezone, or DEFAULT_TZ if unavailable. */
export function deviceTz(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || DEFAULT_TZ;
  } catch {
    return DEFAULT_TZ;
  }
}

interface ZonedParts {
  year: number;
  month: number; // 1-12
  day: number;
  hour: number; // 0-23
  minute: number;
  second: number;
}

/** Wall-clock components of an instant as seen in IANA zone `tz`. */
export function partsInZone(iso: string, tz: string): ZonedParts {
  const dtf = new Intl.DateTimeFormat('en-US', {
    timeZone: tz,
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
  const parts = dtf.formatToParts(new Date(iso));
  const get = (type: string) =>
    +(parts.find((p) => p.type === type)?.value ?? '0');
  let hour = get('hour');
  if (hour === 24) hour = 0; // some engines render midnight as 24
  return {
    year: get('year'),
    month: get('month'),
    day: get('day'),
    hour,
    minute: get('minute'),
    second: get('second'),
  };
}

/**
 * Convert a wall-clock time in IANA zone `tz` to a UTC ISO instant.
 * Reads the zone's actual UTC offset at that moment (DST-correct).
 */
export function zonedWallClockToISO(
  y: number,
  mo: number,
  d: number,
  h: number,
  mi: number,
  tz: string,
): string {
  const guess = Date.UTC(y, mo - 1, d, h, mi, 0);
  const p = partsInZone(new Date(guess).toISOString(), tz);
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  const offsetMs = asUtc - guess;
  return new Date(guess - offsetMs).toISOString();
}

/** YYYY-MM-DD of a timed instant, in zone `tz`. */
export function ymdInZone(iso: string, tz: string): string {
  const p = partsInZone(iso, tz);
  return `${p.year}-${pad2(p.month)}-${pad2(p.day)}`;
}

/** "HH:MM" (24h) of a timed instant, in zone `tz`. */
export function hhmmInZone(iso: string, tz: string): string {
  const p = partsInZone(iso, tz);
  return `${pad2(p.hour)}:${pad2(p.minute)}`;
}

/**
 * <input type="datetime-local"> string ("YYYY-MM-DDTHH:MM") interpreted
 * as a wall-clock in zone `tz` → UTC ISO instant.
 */
export function zonedInputToISO(input: string, tz: string): string {
  if (!input) return '';
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(input);
  if (!m) return new Date(input).toISOString();
  return zonedWallClockToISO(+m[1]!, +m[2]!, +m[3]!, +m[4]!, +m[5]!, tz);
}

/** UTC ISO → "YYYY-MM-DDTHH:MM" datetime-local string in zone `tz`. */
export function isoToZonedInput(iso: string | null, tz: string): string {
  if (!iso) return '';
  const p = partsInZone(iso, tz);
  return `${p.year}-${pad2(p.month)}-${pad2(p.day)}T${pad2(p.hour)}:${pad2(p.minute)}`;
}

// ─── All-day events: tz-independent dates pinned to UTC midnight ───

/** "YYYY-MM-DD" → all-day instant pinned at UTC midnight. */
export function allDayYmdToISO(ymdStr: string): string {
  return `${ymdStr}T00:00:00.000Z`;
}

/** Any all-day instant → its "YYYY-MM-DD" (read via UTC, tz-independent). */
export function allDayIsoToYmd(iso: string): string {
  return new Date(iso).toISOString().slice(0, 10);
}

/**
 * The local calendar date an event falls on, for grid bucketing.
 * All-day events use their UTC-pinned date (tz-independent); timed
 * events use their wall-clock date in the view timezone.
 */
export function eventDayKey(
  iso: string,
  allDay: boolean,
  viewTz: string,
): string {
  return allDay ? allDayIsoToYmd(iso) : ymdInZone(iso, viewTz);
}

function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

/**
 * Bucket events onto local calendar days. All-day events bucket by
 * their tz-independent UTC date; timed events by their wall-clock date
 * in `viewTz`. Replaces the device-tz-only `bucketByDay`.
 */
export function bucketByDayTz<T extends CalendarEvent>(
  events: T[],
  viewTz: string,
): Map<string, T[]> {
  const out = new Map<string, T[]>();
  for (const e of events) {
    if (e.deleted_at) continue;
    const key = eventDayKey(e.start_at, e.all_day, viewTz);
    const list = out.get(key);
    if (list) list.push(e);
    else out.set(key, [e]);
  }
  for (const list of out.values()) {
    list.sort((a, b) => a.start_at.localeCompare(b.start_at));
  }
  return out;
}

/**
 * Minimal iCalendar (RFC 5545) reader — a TypeScript port of the native
 * app's `util/Ics.kt`. Enough to import VEVENTs from a .ics file or a
 * subscribed feed. Ignores VTIMEZONE blocks; TZID values are resolved
 * against the JS Intl tz database when the browser supports it, else
 * fall back to the local zone.
 *
 * Recurrence (RRULE) IS expanded: a recurring VEVENT yields one IcsEvent
 * per occurrence (from DTSTART up to UNTIL/COUNT, open-ended rules capped
 * at RRULE_FUTURE_CAP into the future). Each occurrence gets a distinct
 * synthetic uid `"<uid>@<yyyymmdd>"` so the caller's deterministic id
 * (icsEventId) is unique per occurrence and stable across refreshes —
 * without expansion, monthly/yearly events (bills, birthdays) only ever
 * showed on their first date. EXDATE-excluded occurrences are dropped.
 *
 * start/end are returned as ISO-8601 UTC instants ("…Z") so they drop
 * straight into the events table's `start_at` / `end_at` columns.
 */

export interface IcsEvent {
  uid: string | null;
  summary: string;
  startIso: string;
  endIso: string | null;
  allDay: boolean;
  location: string | null;
  description: string | null;
}

export function parseIcs(text: string): IcsEvent[] {
  const lines = unfold(text);
  const out: IcsEvent[] = [];
  let inEvent = false;
  let uid: string | null = null;
  let summary = '';
  let location: string | null = null;
  let description: string | null = null;
  let start: { iso: string; allDay: boolean } | null = null;
  let end: { iso: string; allDay: boolean } | null = null;
  let rrule: string | null = null;
  let exdates: string[] = [];

  const flush = (ev: IcsEvent): void => {
    if (rrule) out.push(...expandRrule(ev, rrule, exdates));
    else out.push(ev);
  };

  for (const line of lines) {
    if (line === 'BEGIN:VEVENT') {
      inEvent = true;
      uid = null;
      summary = '';
      location = null;
      description = null;
      start = null;
      end = null;
      rrule = null;
      exdates = [];
    } else if (line === 'END:VEVENT') {
      if (inEvent && start) {
        flush({
          uid,
          summary: summary.trim() || '(untitled)',
          startIso: start.iso,
          endIso: end?.iso ?? null,
          allDay: start.allDay,
          location: location && location.trim() ? location : null,
          description: description && description.trim() ? description : null,
        });
      }
      inEvent = false;
    } else if (inEvent) {
      const prop = splitProp(line);
      if (!prop) continue;
      const [name, params, value] = prop;
      switch (name.toUpperCase()) {
        case 'UID':
          uid = value;
          break;
        case 'SUMMARY':
          summary = unescape(value);
          break;
        case 'LOCATION':
          location = unescape(value);
          break;
        case 'DESCRIPTION':
          description = unescape(value);
          break;
        case 'DTSTART':
          start = parseDt(params, value);
          break;
        case 'DTEND':
          end = parseDt(params, value);
          break;
        case 'RRULE':
          rrule = value;
          break;
        case 'EXDATE':
          // one or more comma-separated date(-times); we key exclusions
          // by their yyyymmdd prefix (matches the occurrence's UTC date).
          for (const v of value.split(',')) {
            const d = v.trim().slice(0, 8);
            if (/^\d{8}$/.test(d)) exdates.push(d);
          }
          break;
      }
    }
  }
  return out;
}

/** Open-ended (no UNTIL/COUNT) rules are materialised this far into the
 *  future; past occurrences are always included from DTSTART. Kept as a
 *  window so a client refresh doesn't emit an unbounded stream. */
const RRULE_FUTURE_DAYS = 730;
/** Safety cap on occurrences per event, to bound a pathological rule. */
const RRULE_MAX_OCCURRENCES = 2000;

/** Expand one recurring VEVENT into its occurrences. Handles the FREQ /
 *  INTERVAL / COUNT / UNTIL / BYMONTHDAY subset present in real Apple /
 *  Google feeds (DAILY, WEEKLY, MONTHLY, YEARLY). Each occurrence keeps
 *  the base event's fields, shifts the date, and gets a per-date uid so
 *  the deterministic event id is unique. */
function expandRrule(
  base: IcsEvent,
  rrule: string,
  exdates: string[],
): IcsEvent[] {
  const parts: Record<string, string> = {};
  for (const kv of rrule.split(';')) {
    const eq = kv.indexOf('=');
    if (eq > 0) parts[kv.slice(0, eq).toUpperCase()] = kv.slice(eq + 1);
  }
  const freq = (parts.FREQ || '').toUpperCase();
  if (!['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'].includes(freq)) return [base];

  const interval = Math.max(1, parseInt(parts.INTERVAL || '1', 10) || 1);
  const count = parts.COUNT ? parseInt(parts.COUNT, 10) : null;
  const untilMs = parts.UNTIL ? untilToMs(parts.UNTIL) : null;
  const byMonthDay = parts.BYMONTHDAY
    ? parseInt(parts.BYMONTHDAY, 10)
    : null;

  const baseMs = Date.parse(base.startIso);
  if (isNaN(baseMs)) return [base];
  const baseDate = new Date(baseMs);
  const futureCapMs = Date.now() + RRULE_FUTURE_DAYS * 86_400_000;
  const stopMs = untilMs !== null ? Math.min(untilMs, futureCapMs) : futureCapMs;
  const exSet = new Set(exdates);

  const out: IcsEvent[] = [];
  for (let n = 0; n < RRULE_MAX_OCCURRENCES; n++) {
    if (count !== null && n >= count) break;
    const occ = advance(baseDate, freq, interval * n, byMonthDay);
    const occMs = occ.getTime();
    // UNTIL/future cap is inclusive of the day; add a day of slack for
    // all-day (UTC-midnight) vs timed comparisons.
    if (occMs > stopMs + 86_400_000) break;
    const ymd =
      String(occ.getUTCFullYear()).padStart(4, '0') +
      String(occ.getUTCMonth() + 1).padStart(2, '0') +
      String(occ.getUTCDate()).padStart(2, '0');
    if (exSet.has(ymd)) continue;
    const shift = occMs - baseMs;
    out.push({
      uid: base.uid ? `${base.uid}@${ymd}` : null,
      summary: base.summary,
      startIso: new Date(baseMs + shift).toISOString(),
      endIso:
        base.endIso !== null
          ? new Date(Date.parse(base.endIso) + shift).toISOString()
          : null,
      allDay: base.allDay,
      location: base.location,
      description: base.description,
    });
  }
  return out.length ? out : [base];
}

/** DTSTART advanced by `step` periods of `freq`, in UTC. Month/year steps
 *  clamp an overflowing day (e.g. Jan-31 monthly → Feb-28). */
function advance(
  base: Date,
  freq: string,
  step: number,
  byMonthDay: number | null,
): Date {
  const y = base.getUTCFullYear();
  const mo = base.getUTCMonth();
  const d = base.getUTCDate();
  const h = base.getUTCHours();
  const mi = base.getUTCMinutes();
  const s = base.getUTCSeconds();
  const ms = base.getUTCMilliseconds();
  if (freq === 'DAILY') return new Date(Date.UTC(y, mo, d + step, h, mi, s, ms));
  if (freq === 'WEEKLY')
    return new Date(Date.UTC(y, mo, d + 7 * step, h, mi, s, ms));
  if (freq === 'MONTHLY') {
    const targetMonth = mo + step;
    const yy = y + Math.floor(targetMonth / 12);
    const mm = ((targetMonth % 12) + 12) % 12;
    const day = clampDay(yy, mm, byMonthDay ?? d);
    return new Date(Date.UTC(yy, mm, day, h, mi, s, ms));
  }
  // YEARLY
  const yy = y + step;
  const day = clampDay(yy, mo, byMonthDay ?? d);
  return new Date(Date.UTC(yy, mo, day, h, mi, s, ms));
}

function clampDay(year: number, month0: number, day: number): number {
  const last = new Date(Date.UTC(year, month0 + 1, 0)).getUTCDate();
  return Math.min(day, last);
}

/** UNTIL is either a DATE (yyyymmdd) or a UTC/local date-time. Return ms. */
function untilToMs(v: string): number | null {
  if (/^\d{8}$/.test(v)) return Date.UTC(+v.slice(0, 4), +v.slice(4, 6) - 1, +v.slice(6, 8), 23, 59, 59);
  const m = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z?$/.exec(v);
  if (!m) return null;
  return Date.UTC(+m[1]!, +m[2]! - 1, +m[3]!, +m[4]!, +m[5]!, +m[6]!);
}

/** RFC 5545 line unfolding: a leading space/tab continues the prior line. */
function unfold(text: string): string[] {
  const res: string[] = [];
  for (const raw of text.split('\n')) {
    const line = raw.replace(/\r+$/, '');
    if ((line.startsWith(' ') || line.startsWith('\t')) && res.length > 0) {
      res[res.length - 1] += line.slice(1);
    } else {
      res.push(line);
    }
  }
  return res;
}

/** "NAME;PARAM=x:value" → [NAME, "PARAM=x", "value"]. */
function splitProp(line: string): [string, string, string] | null {
  const colon = line.indexOf(':');
  if (colon < 0) return null;
  const left = line.slice(0, colon);
  const value = line.slice(colon + 1);
  const semi = left.indexOf(';');
  return semi < 0
    ? [left, '', value]
    : [left.slice(0, semi), left.slice(semi + 1), value];
}

function unescape(v: string): string {
  return v
    .replace(/\\n/g, '\n')
    .replace(/\\N/g, '\n')
    .replace(/\\,/g, ',')
    .replace(/\\;/g, ';')
    .replace(/\\\\/g, '\\');
}

/** Returns { iso, allDay } or null if unparseable. */
function parseDt(
  params: string,
  value: string,
): { iso: string; allDay: boolean } | null {
  const isDate =
    /VALUE=DATE/i.test(params) || (value.length === 8 && !value.includes('T'));
  const tzid = /TZID=([^;]+)/i.exec(params)?.[1];
  try {
    if (isDate) {
      // yyyymmdd — an all-day DATE is timezone-independent, so pin it to
      // UTC midnight (NOT local midnight). Storing local midnight would
      // shift the UTC date back a day for positive-offset zones like HK
      // and make Apple Calendar show every all-day event one day early.
      const yyyy = value.slice(0, 4);
      const mm = value.slice(4, 6);
      const dd = value.slice(6, 8);
      const stamp = Date.UTC(+yyyy, +mm - 1, +dd, 0, 0, 0);
      if (isNaN(stamp)) return null;
      return { iso: `${yyyy}-${mm}-${dd}T00:00:00.000Z`, allDay: true };
    }
    // yyyymmddThhmmss(Z)
    const m = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(Z)?$/.exec(value);
    if (!m) return null;
    const y = +m[1]!;
    const mo = +m[2]!;
    const d = +m[3]!;
    const h = +m[4]!;
    const mi = +m[5]!;
    const s = +m[6]!;
    if (m[7] === 'Z') {
      const dt = new Date(Date.UTC(y, mo - 1, d, h, mi, s));
      return { iso: dt.toISOString(), allDay: false };
    }
    if (tzid) {
      const iso = zonedToUtcIso(y, mo, d, h, mi, s, tzid);
      if (iso) return { iso, allDay: false };
    }
    // Floating / local time.
    const dt = new Date(y, mo - 1, d, h, mi, s);
    if (isNaN(dt.getTime())) return null;
    return { iso: dt.toISOString(), allDay: false };
  } catch {
    return null;
  }
}

/**
 * Convert a wall-clock time in IANA zone `tz` to a UTC ISO instant.
 * Uses Intl to read the zone's offset at that moment. Falls back to
 * null (caller treats as floating/local) if the zone is unknown.
 */
function zonedToUtcIso(
  y: number,
  mo: number,
  d: number,
  h: number,
  mi: number,
  s: number,
  tz: string,
): string | null {
  try {
    // Offset (minutes) the zone has at this approximate instant.
    const guess = Date.UTC(y, mo - 1, d, h, mi, s);
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
    const parts = dtf.formatToParts(new Date(guess));
    const get = (t: string) => +(parts.find((p) => p.type === t)?.value ?? '0');
    const asUtc = Date.UTC(
      get('year'),
      get('month') - 1,
      get('day'),
      get('hour'),
      get('minute'),
      get('second'),
    );
    const offsetMs = asUtc - guess;
    return new Date(guess - offsetMs).toISOString();
  } catch {
    return null;
  }
}

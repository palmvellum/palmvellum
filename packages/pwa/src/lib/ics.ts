/**
 * Minimal iCalendar (RFC 5545) reader — a TypeScript port of the native
 * app's `util/Ics.kt`. Enough to import VEVENTs from a .ics file or a
 * subscribed feed. Ignores recurrence (RRULE) and VTIMEZONE blocks;
 * TZID values are resolved against the JS Intl tz database when the
 * browser supports it, else fall back to the local zone.
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

  for (const line of lines) {
    if (line === 'BEGIN:VEVENT') {
      inEvent = true;
      uid = null;
      summary = '';
      location = null;
      description = null;
      start = null;
      end = null;
    } else if (line === 'END:VEVENT') {
      if (inEvent && start) {
        out.push({
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
      }
    }
  }
  return out;
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

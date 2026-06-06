/**
 * ical-feed - Supabase Edge Function (Deno).
 *
 * Serves a per-user RFC 5545 (iCalendar) feed of the Date Book
 * events table at:
 *
 *   GET /functions/v1/ical-feed?token=<hex>
 *   GET /functions/v1/ical-feed/<hex>.ics
 *
 * Apple Calendar, Google Calendar, Outlook and the iOS Calendar
 * app subscribe to this URL via webcal://. The Cache-Control header
 * tells them to refresh once an hour - matching the user's "every
 * hour" requirement without us running a cron.
 *
 * Authentication: opaque 160-bit hex token minted by mint_ical_token()
 * (per-user, single, revocable). The Edge Function resolves the token
 * to a user_id via the service-role-only resolve_ical_token() RPC; the
 * token itself never leaves Supabase Vault-equivalent storage.
 *
 * Deploy: same Management API multipart upload as the other Edge
 *         Functions in supabase/functions/.
 */

// @ts-expect-error Deno runtime
import { createClient } from 'jsr:@supabase/supabase-js@2';

// @ts-expect-error Deno globals
const env = (k: string): string => Deno.env.get(k) ?? '';

const SUPABASE_URL = env('SUPABASE_URL');
const SERVICE_KEY  = env('SUPABASE_SERVICE_ROLE_KEY');

const supa = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });

const CORS = {
  'Access-Control-Allow-Origin':  '*',
  'Access-Control-Allow-Headers': 'content-type, authorization, apikey',
  'Access-Control-Allow-Methods': 'GET, OPTIONS',
};

interface EventRow {
  id: string;
  title: string;
  start_at: string;
  end_at: string | null;
  all_day: boolean;
  location: string | null;
  notes: string | null;
  alarm_minutes: number | null;
  created_at: string;
  updated_at: string;
}

interface TodoRow {
  id: string;
  body: string | null;
  metadata: {
    palm_due_date?: string;     // YYYY-MM-DD or empty
    palm_completed?: boolean;
    palm_notes?: string;
    palm_priority?: number;     // 1-3 (Palm priority)
  } | null;
  created_at: string;
  updated_at: string;
}

// @ts-expect-error Deno.serve is provided by the runtime
Deno.serve(async (req: Request) => {
  if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
  if (req.method !== 'GET') return text(405, 'method not allowed');

  // 1. Extract token. Accept both /ical-feed/<token>.ics and ?token=<token>.
  const url = new URL(req.url);
  let token = url.searchParams.get('token') ?? '';
  if (!token) {
    // Last path segment; strip .ics suffix.
    const seg = url.pathname.split('/').pop() ?? '';
    token = seg.replace(/\.ics$/i, '');
    // Function name itself is in the path too; skip if the seg matches.
    if (token === 'ical-feed') token = '';
  }
  token = token.trim().toLowerCase();
  if (!/^[a-f0-9]{40}$/.test(token)) return text(400, 'invalid token');

  // 2. Resolve token -> user_id.
  const { data: uid, error: tokErr } = await supa.rpc('resolve_ical_token', { tok: token });
  if (tokErr) return text(500, `token lookup: ${tokErr.message}`);
  if (!uid)   return text(404, 'token not found');

  // 3a. Pull this user's active events (Apple Calendar handles ~5k
  //     events without issue; cap at 2000 for safety).
  const { data: events, error: evErr } = await supa
    .from('events')
    .select('id, title, start_at, end_at, all_day, location, notes, alarm_minutes, created_at, updated_at')
    .eq('user_id', String(uid))
    .is('deleted_at', null)
    .order('start_at', { ascending: true })
    .limit(2000);

  if (evErr) return text(500, `events: ${evErr.message}`);

  // 3b. Pull this user's open todos that carry a due date. They will
  //     render as all-day VEVENTs on the due date so they appear on
  //     the calendar grid (Apple Calendar / Google Calendar hide
  //     VTODOs from the main grid). Completed todos and todos with no
  //     due date are excluded — when the user marks one done it
  //     simply stops appearing on the next refresh.
  const { data: todosRaw, error: tdErr } = await supa
    .from('records')
    .select('id, body, metadata, created_at, updated_at')
    .eq('user_id', String(uid))
    .eq('type', 'todo')
    .is('deleted_at', null)
    .order('updated_at', { ascending: false })
    .limit(2000);

  if (tdErr) return text(500, `todos: ${tdErr.message}`);

  const todos: TodoRow[] = (todosRaw ?? []).filter((r: TodoRow) => {
    const md = r.metadata ?? {};
    const due = (md.palm_due_date ?? '').trim();
    if (!due) return false;
    if (md.palm_completed === true) return false;
    // Sanity-check the date string.
    if (!/^\d{4}-\d{2}-\d{2}$/.test(due)) return false;
    return true;
  });

  // 4. Format VCALENDAR.
  const body = buildIcs(events ?? [], todos);

  return new Response(body, {
    status: 200,
    headers: {
      ...CORS,
      'Content-Type':  'text/calendar; charset=utf-8',
      // 1-hour refresh interval. The X-PUBLISHED-TTL inside the body
      // is a hint; Cache-Control is what Apple actually honours.
      'Cache-Control': 'public, max-age=3600, s-maxage=3600',
      // Filename so manual downloads land as palmvellum.ics.
      'Content-Disposition': 'inline; filename="palmvellum.ics"',
    },
  });
});

function text(status: number, msg: string): Response {
  return new Response(msg + '\n', {
    status,
    headers: { ...CORS, 'Content-Type': 'text/plain; charset=utf-8' },
  });
}

// ─── iCalendar (RFC 5545) formatting ────────────────────────

const PRODID = '-//PalmVellum//Date Book v0.6//EN';

function buildIcs(events: EventRow[], todos: TodoRow[]): string {
  // CRLF line breaks per RFC 5545.
  const lines: string[] = [];
  lines.push('BEGIN:VCALENDAR');
  lines.push('VERSION:2.0');
  lines.push(`PRODID:${PRODID}`);
  lines.push('CALSCALE:GREGORIAN');
  lines.push('METHOD:PUBLISH');
  lines.push('X-WR-CALNAME:PalmVellum Date Book');
  lines.push('X-WR-CALDESC:Events + open to-dos with due dates. Refreshes hourly. Completed to-dos disappear on the next refresh.');
  lines.push('X-PUBLISHED-TTL:PT1H');
  lines.push('REFRESH-INTERVAL;VALUE=DURATION:PT1H');

  const now = nowUtc();

  // ── 4a. Events ─────────────────────────────────────────
  for (const e of events) {
    const dtstart = formatDate(e.start_at, e.all_day);
    const dtend   = e.end_at
                    ? formatDate(e.end_at, e.all_day)
                    : e.all_day
                      ? dtstart  // single all-day, end = start + 1 day handled by Apple
                      : null;

    lines.push('BEGIN:VEVENT');
    lines.push(`UID:${e.id}@palmvellum.dev`);
    lines.push(`DTSTAMP:${now}`);
    lines.push(`SUMMARY:${escapeText(e.title)}`);
    if (e.all_day) {
      lines.push(`DTSTART;VALUE=DATE:${dtstart}`);
      if (dtend && dtend !== dtstart) lines.push(`DTEND;VALUE=DATE:${dtend}`);
    } else {
      lines.push(`DTSTART:${dtstart}`);
      if (dtend) lines.push(`DTEND:${dtend}`);
    }
    if (e.location) lines.push(`LOCATION:${escapeText(e.location)}`);
    if (e.notes)    lines.push(`DESCRIPTION:${escapeText(e.notes)}`);
    if (e.alarm_minutes != null && e.alarm_minutes >= 0) {
      lines.push('BEGIN:VALARM');
      lines.push('ACTION:DISPLAY');
      lines.push(`TRIGGER:-PT${e.alarm_minutes}M`);
      lines.push(`DESCRIPTION:${escapeText(e.title)}`);
      lines.push('END:VALARM');
    }
    lines.push(`CREATED:${utc(e.created_at)}`);
    lines.push(`LAST-MODIFIED:${utc(e.updated_at)}`);
    lines.push('END:VEVENT');
  }

  // ── 4b. Open to-dos with a due date, rendered as all-day events.
  //
  //   A different UID namespace (todo-<id>@palmvellum.dev) ensures
  //   the row never collides with a real event of the same ULID.
  //
  //   When a to-do is marked complete in PalmVellum the next feed
  //   refresh simply omits it; iCal clients honour 'METHOD:PUBLISH'
  //   semantics and drop any UID that has fallen out of the feed.
  for (const t of todos) {
    const md   = t.metadata ?? {};
    const due  = (md.palm_due_date ?? '').trim(); // YYYY-MM-DD
    const dt   = due.replace(/-/g, '');            // YYYYMMDD
    const txt  = (t.body ?? '').trim() || '(untitled to-do)';
    // Visual marker so the user can distinguish to-dos from real events
    // on their calendar grid.
    const summary = `[to-do] ${txt}`;

    lines.push('BEGIN:VEVENT');
    lines.push(`UID:todo-${t.id}@palmvellum.dev`);
    lines.push(`DTSTAMP:${now}`);
    lines.push(`SUMMARY:${escapeText(summary)}`);
    lines.push(`DTSTART;VALUE=DATE:${dt}`);
    // Single-day all-day event; clients infer DTEND as the day after.

    const descParts: string[] = [];
    if (md.palm_priority != null) {
      descParts.push(`Priority: ${md.palm_priority}`);
    }
    if (md.palm_notes && md.palm_notes.trim()) {
      descParts.push(md.palm_notes.trim());
    }
    descParts.push('Mark this to-do done in PalmVellum to remove it from your calendar at the next refresh.');
    lines.push(`DESCRIPTION:${escapeText(descParts.join('\n\n'))}`);

    lines.push(`CREATED:${utc(t.created_at)}`);
    lines.push(`LAST-MODIFIED:${utc(t.updated_at)}`);
    lines.push('END:VEVENT');
  }

  lines.push('END:VCALENDAR');

  return lines.map(foldLine).join('\r\n') + '\r\n';
}

function formatDate(iso: string, allDay: boolean): string {
  const d = new Date(iso);
  if (allDay) {
    // YYYYMMDD in UTC; for Apple this is fine since all-day events
    // are stored at 00:00 UTC by the platform.
    return d.toISOString().slice(0, 10).replace(/-/g, '');
  }
  return utc(iso);
}

function utc(iso: string): string {
  // RFC 5545 UTC form: YYYYMMDDTHHMMSSZ
  const d = new Date(iso);
  const yyyy = d.getUTCFullYear().toString().padStart(4, '0');
  const mm   = (d.getUTCMonth() + 1).toString().padStart(2, '0');
  const dd   = d.getUTCDate().toString().padStart(2, '0');
  const hh   = d.getUTCHours().toString().padStart(2, '0');
  const mi   = d.getUTCMinutes().toString().padStart(2, '0');
  const ss   = d.getUTCSeconds().toString().padStart(2, '0');
  return `${yyyy}${mm}${dd}T${hh}${mi}${ss}Z`;
}

function nowUtc(): string {
  // Edge Function can call Date here (we are NOT in a workflow script).
  const d = new Date();
  const yyyy = d.getUTCFullYear().toString().padStart(4, '0');
  const mm   = (d.getUTCMonth() + 1).toString().padStart(2, '0');
  const dd   = d.getUTCDate().toString().padStart(2, '0');
  const hh   = d.getUTCHours().toString().padStart(2, '0');
  const mi   = d.getUTCMinutes().toString().padStart(2, '0');
  const ss   = d.getUTCSeconds().toString().padStart(2, '0');
  return `${yyyy}${mm}${dd}T${hh}${mi}${ss}Z`;
}

// Escape per RFC 5545 §3.3.11.
function escapeText(s: string): string {
  return s
    .replace(/\\/g, '\\\\')
    .replace(/;/g, '\;')
    .replace(/,/g, '\\,')
    .replace(/\r?\n/g, '\\n');
}

// Fold lines at 75 octets per RFC 5545 §3.1: a CRLF + leading space is
// a continuation.
function foldLine(line: string): string {
  if (line.length <= 75) return line;
  const out: string[] = [];
  let start = 0;
  while (start < line.length) {
    const chunk = line.slice(start, start + (start === 0 ? 75 : 74));
    out.push(start === 0 ? chunk : ' ' + chunk);
    start += start === 0 ? 75 : 74;
  }
  return out.join('\r\n');
}

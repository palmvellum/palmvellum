/**
 * fetch-ics — Supabase Edge Function (Deno).
 *
 * A small authenticated proxy that fetches an external iCalendar (.ics)
 * feed server-side and returns its text. The PWA needs this because
 * browsers block cross-origin fetches of calendar feeds (Google
 * Calendar, Outlook, etc. send no CORS headers), while the native
 * Android app fetches them directly. The inbound "subscribe to a
 * calendar URL" feature on web routes through here.
 *
 *   POST /functions/v1/fetch-ics   { "url": "https://.../basic.ics" }
 *     → 200 { "text": "<raw ics>" }   or   { "error": "<message>" }
 *
 * Auth: the PWA invokes this with the signed-in user's JWT (via
 * supabase.functions.invoke), so verify_jwt keeps it from being an
 * open proxy. We additionally restrict to http(s) and block obvious
 * private / loopback hosts to limit SSRF, and cap the response size.
 *
 * Deploy:
 *   SUPABASE_ACCESS_TOKEN=$(cat ~/.supabase/access-token) \
 *     supabase functions deploy fetch-ics --project-ref jrkwncplngmznfzzqwee
 */

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers':
    'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

const MAX_BYTES = 5_000_000; // 5 MB — generous for a calendar feed

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, 'content-type': 'application/json' },
  });
}

/** Reject non-http(s) and obvious internal targets to limit SSRF. */
function isAllowed(u: URL): boolean {
  if (u.protocol !== 'https:' && u.protocol !== 'http:') return false;
  const host = u.hostname.toLowerCase();
  if (
    host === 'localhost' ||
    host === '0.0.0.0' ||
    host.endsWith('.localhost') ||
    host.endsWith('.internal')
  ) {
    return false;
  }
  // Block literal private / loopback IPv4 ranges and IPv6 loopback.
  if (/^127\./.test(host) || /^10\./.test(host) || /^192\.168\./.test(host)) {
    return false;
  }
  if (/^172\.(1[6-9]|2[0-9]|3[0-1])\./.test(host)) return false;
  if (host === '::1' || host === '[::1]') return false;
  return true;
}

// @ts-expect-error Deno global
Deno.serve(async (req: Request) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: CORS });
  if (req.method !== 'POST') return json({ error: 'POST only' }, 405);

  let url = '';
  try {
    const body = await req.json();
    url = String(body?.url ?? '').trim();
  } catch {
    return json({ error: 'invalid JSON body' }, 400);
  }
  if (!url) return json({ error: 'url required' }, 400);

  // webcal:// is just https for fetch purposes — normalise it.
  if (url.startsWith('webcal://')) url = 'https://' + url.slice('webcal://'.length);

  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return json({ error: 'invalid url' }, 400);
  }
  if (!isAllowed(parsed)) return json({ error: 'url not allowed' }, 400);

  let resp: Response;
  try {
    resp = await fetch(parsed.toString(), {
      redirect: 'follow',
      headers: {
        // A polite, generic UA; some feeds 403 an empty UA.
        'User-Agent': 'PalmVellum/1.0 (+https://palmvellum.dev)',
        Accept: 'text/calendar, text/plain, */*',
      },
    });
  } catch (e) {
    return json({ error: `fetch failed: ${e instanceof Error ? e.message : e}` }, 502);
  }
  if (!resp.ok) return json({ error: `upstream HTTP ${resp.status}` }, 502);

  const buf = new Uint8Array(await resp.arrayBuffer());
  if (buf.byteLength > MAX_BYTES) return json({ error: 'feed too large' }, 413);
  const text = new TextDecoder('utf-8').decode(buf);
  if (!text.includes('BEGIN:VCALENDAR') && !text.includes('BEGIN:VEVENT')) {
    return json({ error: 'not an iCalendar feed' }, 422);
  }
  return json({ text });
});

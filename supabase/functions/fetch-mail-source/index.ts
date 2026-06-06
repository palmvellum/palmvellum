/**
 * fetch-mail-source — Supabase Edge Function (Deno).
 *
 * Called by the pg_cron sweeper (run_due_mail_fetches) every 5
 * minutes for each source whose local fetch_time has passed today
 * and that hasn't been fetched yet today. Also callable directly
 * from the PWA's "fetch now" button with the same POST body.
 *
 * Pipeline:
 *   1. Read mail_sources by id (service-role bypasses RLS).
 *   2. GET the URL with a polite UA. Bail (record last_error) on
 *      HTTP errors or non-HTML content.
 *   3. Strip scripts/styles/HTML tags and truncate to ~80KB of
 *      text so the AI prompt stays under the model's context.
 *   4. Call the user's BYOK provider with a JSON-schema prompt
 *      asking for { subject, body } — body in Palm-friendly plain
 *      text ≤ 800 words.
 *   5. INSERT records row type='mail' with the digest, including
 *      source metadata so the PWA inbox can render it.
 *   6. UPDATE mail_sources.last_fetched_at = now(), clear
 *      last_error. On failure write last_error and leave
 *      last_fetched_at alone so the sweeper retries later.
 */

// @ts-expect-error Deno runtime
import { createClient } from 'jsr:@supabase/supabase-js@2';

// @ts-expect-error Deno globals
const env = (k: string): string => Deno.env.get(k) ?? '';

const SUPABASE_URL = env('SUPABASE_URL');
const SERVICE_KEY = env('SUPABASE_SERVICE_ROLE_KEY');
const PLATFORM_OPENAI = env('PLATFORM_OPENAI_API_KEY');
const PLATFORM_ANTHROPIC = env('PLATFORM_ANTHROPIC_API_KEY');

const supa = createClient(SUPABASE_URL, SERVICE_KEY, {
  auth: { persistSession: false },
});

const MAX_HTML_TEXT = 80_000; // chars of stripped text passed to AI

interface Source {
  id: string;
  user_id: string;
  name: string;
  url: string;
  digest_hint: string | null;
  timezone: string;
}

const DIGEST_SCHEMA = {
  type: 'object',
  properties: {
    subject: {
      type: 'string',
      description:
        'Email subject line, ≤ 100 chars. Date in YYYY-MM-DD prepended, then a short summary headline for the day.',
    },
    body: {
      type: 'string',
      description:
        'Plain-text email body — a COMPREHENSIVE enumeration of every distinct news item / post / story you can see on the page. For each item: one-line headline on its own line, then 1-3 lines of summary (who/what/when/why). Blank line between items. No markdown, no emoji, no bullet symbols. Palm-friendly plain text. Up to ~3000 words is fine if the page has lots of items.',
    },
  },
  required: ['subject', 'body'],
  additionalProperties: false,
} as const;

function systemPrompt(source: Source, dateLocal: string): string {
  const hint = source.digest_hint?.trim();
  return `You produce a daily digest of one website for the user's Palm Pilot mail inbox. The user wants COMPREHENSIVE coverage -- enumerate every distinct news item / story / post on the page, not just the top one.

Today: ${dateLocal} (${source.timezone}).
Source name: ${source.name}.
Source URL: ${source.url}.

PALM CHARACTER SET CONSTRAINT -- subject and body are shown on a Palm Pilot:
- ASCII or Mac Roman / Palm Roman characters ONLY.
- NO emoji whatsoever.
- NO arrow / checkmark / star / etc. symbols (use ASCII like ->).
- NO bullet glyphs (use plain hyphen "- " or just paragraphs).
- ASCII quotes only.

Rules:
1. Enumerate EVERY distinct news item / story / post you can identify on the page. Don't pick favourites; the user wants a full scan of what's there today.
2. Format per item -- one-line headline on its own line, then 1-3 lines of summary (who, what, when, key fact). Blank line between items.
3. Plain text only. No markdown headers. Short paragraphs.
4. Match the source language. RTHK Chinese -> write in Traditional Chinese. English source -> English.
5. Subject: "${dateLocal} - " prepended, then a short headline summarising today's biggest story (max 100 chars total).
6. If a section header divides the page (e.g. local / china / world / sport), keep that grouping with the header as a single line before its items.
7. If the page is paywalled / login-walled / shows no fresh content, output subject "${dateLocal} - (no fresh content)" and body explaining briefly.
${hint ? `\nUser hint for this source:\n${hint}` : ''}`;
}

// @ts-expect-error Deno-only API
Deno.serve(async (req: Request) => {
  let payload: { source_id?: string };
  try {
    payload = await req.json();
  } catch {
    return jsonResp({ error: 'bad json' }, 400);
  }
  const sourceId = payload.source_id;
  if (!sourceId) return jsonResp({ error: 'missing source_id' }, 400);

  // Load source
  const { data: source, error: srcErr } = await supa
    .from('mail_sources')
    .select('id, user_id, name, url, digest_hint, timezone, enabled')
    .eq('id', sourceId)
    .single();
  if (srcErr || !source) {
    return jsonResp({ error: 'source-not-found' }, 200);
  }
  if (!source.enabled) {
    return jsonResp({ skipped: 'disabled' }, 200);
  }

  // Resolve BYOK API key
  const { data: settings } = await supa
    .from('user_settings')
    .select('api_mode, preferred_provider, openai_model, anthropic_model')
    .eq('user_id', source.user_id)
    .single();
  if (!settings) {
    await markError(sourceId, 'no user_settings');
    return jsonResp({ error: 'no-settings' }, 200);
  }
  let apiKey = '';
  if (settings.api_mode === 'byok') {
    const { data: k } = await supa.rpc('read_user_api_key', {
      target_user: source.user_id,
      provider_name: settings.preferred_provider,
    });
    if (!k) {
      await markError(sourceId, `no ${settings.preferred_provider} key in vault`);
      return jsonResp({ error: 'no-byok-key' }, 200);
    }
    apiKey = String(k);
  } else {
    apiKey = settings.preferred_provider === 'openai' ? PLATFORM_OPENAI : PLATFORM_ANTHROPIC;
    if (!apiKey) {
      await markError(sourceId, 'platform key not configured');
      return jsonResp({ error: 'no-platform-key' }, 200);
    }
  }

  // Fetch the page
  let pageText: string;
  try {
    pageText = await fetchPageText(source.url);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    await markError(sourceId, `fetch failed: ${msg}`);
    return jsonResp({ error: msg }, 200);
  }

  // Summarize
  const dateLocal = nowLocalISODate(source.timezone);
  let digest: { subject: string; body: string };
  let model = '';
  let tokensIn = 0;
  let tokensOut = 0;
  try {
    if (settings.preferred_provider === 'openai') {
      const r = await callOpenAIDigest(
        apiKey,
        settings.openai_model,
        source,
        pageText,
        dateLocal,
      );
      digest = r.digest;
      model = r.model;
      tokensIn = r.tokensIn;
      tokensOut = r.tokensOut;
    } else {
      const r = await callAnthropicDigest(
        apiKey,
        settings.anthropic_model,
        source,
        pageText,
        dateLocal,
      );
      digest = r.digest;
      model = r.model;
      tokensIn = r.tokensIn;
      tokensOut = r.tokensOut;
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    await markError(sourceId, `AI failed: ${msg}`);
    return jsonResp({ error: msg }, 200);
  }

  // Insert the mail record
  const recordId = newUlid();
  const { error: insErr } = await supa.from('records').insert({
    id: recordId,
    user_id: source.user_id,
    type: 'mail',
    posture: 'open',
    body: digest.body,
    source: 'mail-fetcher',
    ai_status: 'done',
    ai_model: model,
    ai_tokens_in: tokensIn,
    ai_tokens_out: tokensOut,
    metadata: {
      mail_subject: digest.subject,
      mail_from: hostnameOf(source.url),
      mail_source_id: source.id,
      mail_source_name: source.name,
      mail_source_url: source.url,
      mail_fetched_at: new Date().toISOString(),
      mail_date_local: dateLocal,
    },
  });
  if (insErr) {
    await markError(sourceId, `insert failed: ${insErr.message}`);
    return jsonResp({ error: insErr.message }, 200);
  }

  await supa
    .from('mail_sources')
    .update({
      last_fetched_at: new Date().toISOString(),
      last_error: null,
    })
    .eq('id', sourceId);

  await supa.from('ai_usage').insert({
    user_id: source.user_id,
    api_mode: settings.api_mode,
    provider: settings.preferred_provider,
    model,
    tokens_in: tokensIn,
    tokens_out: tokensOut,
    cost_credits: 0,
  });

  return jsonResp({ ok: true, record_id: recordId, subject: digest.subject }, 200);
});

// ─────────────────────────── helpers ──────────────────────────

async function markError(id: string, msg: string): Promise<void> {
  await supa.from('mail_sources').update({ last_error: msg }).eq('id', id);
}

function jsonResp(body: unknown, status: number = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function hostnameOf(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return url;
  }
}

function nowLocalISODate(tz: string): string {
  try {
    const fmt = new Intl.DateTimeFormat('en-CA', {
      timeZone: tz,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    });
    return fmt.format(new Date());
  } catch {
    return new Date().toISOString().slice(0, 10);
  }
}

async function fetchPageText(url: string): Promise<string> {
  const resp = await fetch(url, {
    headers: {
      'User-Agent':
        'Mozilla/5.0 (compatible; PalmVellum-MailFetcher/1.0; +https://tatliving.dev/palmvellum)',
      Accept: 'text/html,application/xhtml+xml,*/*;q=0.8',
      'Accept-Language': 'en,zh;q=0.8',
    },
    redirect: 'follow',
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const ct = resp.headers.get('content-type') ?? '';
  if (!ct.startsWith('text/') && !ct.includes('html') && !ct.includes('xml')) {
    throw new Error(`unexpected content-type: ${ct}`);
  }
  const html = await resp.text();

  // Strip script/style/noscript blocks
  let stripped = html
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ');

  // Drop most tags, keep their inner text. Preserve paragraph breaks.
  stripped = stripped
    .replace(/<\/(p|div|section|article|li|h[1-6]|br|tr)>/gi, '\n')
    .replace(/<br\s*\/?\s*>/gi, '\n')
    .replace(/<[^>]+>/g, ' ');

  // HTML entity decode for the common ones
  stripped = stripped
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .replace(/&apos;/gi, "'");

  // Collapse runs of whitespace; keep paragraph breaks
  stripped = stripped.replace(/[ \t]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim();

  if (stripped.length > MAX_HTML_TEXT) {
    stripped = stripped.slice(0, MAX_HTML_TEXT) + '\n…[truncated]';
  }
  return stripped;
}

interface DigestResult {
  digest: { subject: string; body: string };
  model: string;
  tokensIn: number;
  tokensOut: number;
}

async function callOpenAIDigest(
  apiKey: string,
  model: string,
  source: Source,
  pageText: string,
  dateLocal: string,
): Promise<DigestResult> {
  const resp = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model: model || 'gpt-4o-mini',
      messages: [
        { role: 'system', content: systemPrompt(source, dateLocal) },
        { role: 'user', content: `Source page text:\n\n${pageText}` },
      ],
      response_format: {
        type: 'json_schema',
        json_schema: {
          name: 'digest',
          strict: true,
          schema: DIGEST_SCHEMA,
        },
      },
      max_completion_tokens: 4096,
    }),
  });
  if (!resp.ok) throw new Error(`openai ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  const txt = j.choices?.[0]?.message?.content ?? '{"subject":"","body":""}';
  const parsed = JSON.parse(txt) as { subject: string; body: string };
  return {
    digest: parsed,
    model: j.model ?? model,
    tokensIn: j.usage?.prompt_tokens ?? 0,
    tokensOut: j.usage?.completion_tokens ?? 0,
  };
}

async function callAnthropicDigest(
  apiKey: string,
  model: string,
  source: Source,
  pageText: string,
  dateLocal: string,
): Promise<DigestResult> {
  const resp = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: {
      'x-api-key': apiKey,
      'anthropic-version': '2023-06-01',
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      model: model || 'claude-sonnet-4-5-20250929',
      max_tokens: 4096,
      system: [
        {
          type: 'text',
          text: systemPrompt(source, dateLocal),
          cache_control: { type: 'ephemeral' },
        },
      ],
      tools: [
        {
          name: 'submit_digest',
          description: 'Emit the structured digest.',
          input_schema: DIGEST_SCHEMA,
        },
      ],
      tool_choice: { type: 'tool', name: 'submit_digest' },
      messages: [
        {
          role: 'user',
          content: `Source page text:\n\n${pageText}`,
        },
      ],
    }),
  });
  if (!resp.ok) throw new Error(`anthropic ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  let digest = { subject: '', body: '' };
  for (const block of j.content ?? []) {
    if (block.type === 'tool_use' && block.name === 'submit_digest') {
      digest = block.input as { subject: string; body: string };
      break;
    }
  }
  return {
    digest,
    model: j.model ?? model,
    tokensIn: j.usage?.input_tokens ?? 0,
    tokensOut: j.usage?.output_tokens ?? 0,
  };
}

// Browser-safe ULID matching packages/pwa/src/lib/ulid.ts.
function newUlid(): string {
  const ENC = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  const ts = BigInt(Date.now());
  const tsB = ts.toString(2).padStart(48, '0');
  // @ts-expect-error Deno crypto
  const rnd = crypto.getRandomValues(new Uint8Array(10));
  let rndB = '';
  for (const b of rnd) rndB += b.toString(2).padStart(8, '0');
  const bits = tsB + rndB;
  let out = '';
  for (let i = 0; i < 26; i++) {
    const slice = bits.slice(i * 5, i * 5 + 5).padEnd(5, '0');
    out += ENC[parseInt(slice, 2)];
  }
  return out;
}

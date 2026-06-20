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
import { chargeUsage } from '../_shared/billing.ts';

// @ts-expect-error Deno globals
const env = (k: string): string => Deno.env.get(k) ?? '';

const SUPABASE_URL = env('SUPABASE_URL');
const SERVICE_KEY = env('SUPABASE_SERVICE_ROLE_KEY');
const PLATFORM_OPENAI = env('PLATFORM_OPENAI_API_KEY');
const PLATFORM_ANTHROPIC = env('PLATFORM_ANTHROPIC_API_KEY');
const PLATFORM_GEMINI    = env('PLATFORM_GEMINI_API_KEY');

const supa = createClient(SUPABASE_URL, SERVICE_KEY, {
  auth: { persistSession: false },
});

const MAX_HTML_TEXT = 80_000; // chars of stripped text passed to AI

// CORS headers so the PWA can invoke this function directly from the
// browser via supabase.functions.invoke('fetch-mail-source', ...).
// Server-to-server callers (the cron sweeper, pg_net webhooks)
// ignore them.
const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers':
    'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

interface Source {
  id: string;
  user_id: string;
  name: string;
  url: string | null;
  topic: string | null;
  source_type: 'url' | 'topic';
  output_language: string | null;
  digest_hint: string | null;
  timezone: string;
}

// Maps the language code stored in mail_sources.output_language to a
// natural-language instruction we paste into the AI system prompt.
function languageInstruction(lang: string | null): string {
  if (!lang || lang === 'auto') return 'Match the source language.';
  const map: Record<string, string> = {
    'zh-TW': 'Write the entire body and subject in Traditional Chinese.',
    'zh-CN': 'Write the entire body and subject in Simplified Chinese.',
    en: 'Write the entire body and subject in English.',
    ja: 'Write the entire body and subject in Japanese.',
    ko: 'Write the entire body and subject in Korean.',
    fr: 'Write the entire body and subject in French.',
    de: 'Write the entire body and subject in German.',
    es: 'Write the entire body and subject in Spanish.',
  };
  return map[lang] ?? `Write the entire body and subject in ${lang}.`;
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
  const langLine = languageInstruction(source.output_language);
  return `You produce a daily digest of one website for the user's Palm Pilot mail inbox. The user wants COMPREHENSIVE coverage -- enumerate every distinct news item / story / post on the page, not just the top one.

Today: ${dateLocal} (${source.timezone}).
Source name: ${source.name}.
Source URL: ${source.url}.

LANGUAGE: ${langLine}

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
4. Subject: "${dateLocal} - " prepended, then a short headline summarising today's biggest story (max 100 chars total).
5. If a section header divides the page (e.g. local / china / world / sport), keep that grouping with the header as a single line before its items.
6. If the page is paywalled / login-walled / shows no fresh content, output subject "${dateLocal} - (no fresh content)" and body explaining briefly.
${hint ? `\nUser hint for this source:\n${hint}` : ''}`;
}

// ─── Topic research prompt + helpers ────────────────────────────

function researchSystemPrompt(source: Source, dateLocal: string): string {
  const hint = source.digest_hint?.trim();
  const langLine = languageInstruction(source.output_language);
  return `You research a topic for the user's daily mail digest and write a long-form article they read on a Palm Pilot.

Today: ${dateLocal} (${source.timezone}).
Topic / interest: ${source.topic}.

LANGUAGE: ${langLine}

Process:
1. Use the web search tool aggressively -- run 6-10 distinct queries covering different angles of the topic (recent news, primary sources, expert analysis, contrasting viewpoints, background context). Prefer results from the last 7 days but include foundational pieces when they help the reader understand context.
2. Read the most relevant 6-12 results, synthesise across them, and write a 10-20 minute reading article (target 2000-4000 words).
3. The article should be coherent narrative paragraphs, not bullet lists. Lead with the most important development, then unfold across related angles, contrasting views, expert quotes, numbers / dates / names, and what to watch next. Use section break paragraphs (a blank line and a short subhead line such as "Background.", "What happened.", "Why it matters.", "What's next.") rather than markdown headers.
4. Aim for depth and detail rather than brevity -- the user explicitly wants a long read. Don't repeat yourself; keep moving forward with new facts and analysis.
5. After the article body, output a section EXACTLY in this form (separator line included):

==REFERENCES==
<url 1>
<url 2>
<url 3>
...

PALM CHARACTER SET CONSTRAINT -- output is shown on a Palm Pilot:
- ASCII or Mac Roman / Palm Roman characters ONLY.
- NO emoji whatsoever.
- NO arrow / star / checkmark / etc. symbols.
- NO bullet glyphs.
- ASCII quotes only.

Output format -- nothing else, no preamble:
SUBJECT: <one-line subject, max 100 chars, prefixed with "${dateLocal} - ">

<article body 2000-4000 words>

==REFERENCES==
<url 1>
<url 2>
...
${hint ? `\nUser hint:\n${hint}` : ''}`;
}

// Parse "SUBJECT: ...\n\n<body>\n==REFERENCES==\n<urls>" into structure.
function parseResearchOutput(text: string): {
  subject: string;
  body: string;
  references: string[];
} {
  let subject = '';
  let body = text;
  let references: string[] = [];

  const subjMatch = text.match(/^\s*SUBJECT:\s*(.+)$/m);
  if (subjMatch) {
    subject = subjMatch[1].trim();
    body = text.slice(subjMatch.index! + subjMatch[0].length).trim();
  }

  const refMarker = body.search(/==\s*REFERENCES\s*==/i);
  if (refMarker >= 0) {
    const refText = body.slice(refMarker).replace(/^==\s*REFERENCES\s*==/i, '').trim();
    body = body.slice(0, refMarker).trim();
    references = refText
      .split(/\r?\n/)
      .map((s) => s.replace(/^[-*\s>]+/, '').trim())
      .filter((s) => /^https?:\/\//i.test(s));
  }

  if (!subject) {
    const firstLine = body.split(/\r?\n/, 1)[0];
    subject = firstLine?.slice(0, 100) ?? 'Daily research digest';
  }
  return { subject, body, references };
}

// @ts-expect-error Deno-only API
Deno.serve(async (req: Request) => {
  // Preflight
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: CORS_HEADERS });
  }
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
    .select('id, user_id, name, url, topic, source_type, output_language, digest_hint, timezone, enabled')
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
    .select('api_mode, preferred_provider, openai_model, anthropic_model, gemini_model, balance_micro_usd, low_balance_threshold_micro')
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
    apiKey = settings.preferred_provider === 'openai'    ? PLATFORM_OPENAI
           : settings.preferred_provider === 'anthropic'  ? PLATFORM_ANTHROPIC
           :                                                PLATFORM_GEMINI;
    if (!apiKey) {
      await markError(sourceId, 'platform key not configured');
      return jsonResp({ error: 'no-platform-key' }, 200);
    }
    // Pay-as-you-go: refuse a metered call when the balance is exhausted.
    if ((settings.balance_micro_usd ?? 0) <= 0) {
      await markError(sourceId, 'insufficient credit — please top up');
      return jsonResp({ error: 'no-credit' }, 200);
    }
  }

  const dateLocal = nowLocalISODate(source.timezone);
  let digest: { subject: string; body: string };
  let references: string[] = [];
  let model = '';
  let tokensIn = 0;
  let tokensOut = 0;

  if (source.source_type === 'topic') {
    // ── Topic research path ─────────────────────────────────
    try {
      const r = settings.preferred_provider === 'openai'
        ? await callOpenAIResearch(apiKey, source, dateLocal)
        : settings.preferred_provider === 'anthropic'
        ? await callAnthropicResearch(apiKey, settings.anthropic_model, source, dateLocal)
        : await callGeminiResearch(apiKey, settings.gemini_model, source, dateLocal);
      digest = { subject: r.subject, body: r.body };
      references = r.references;
      model = r.model;
      tokensIn = r.tokensIn;
      tokensOut = r.tokensOut;
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      await markError(sourceId, `research failed: ${msg}`);
      return jsonResp({ error: msg }, 200);
    }
  } else {
    // ── URL fetch + summarise path (existing) ────────────────
    let pageText: string;
    try {
      pageText = await fetchPageText(source.url!);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      await markError(sourceId, `fetch failed: ${msg}`);
      return jsonResp({ error: msg }, 200);
    }
    try {
      const r = settings.preferred_provider === 'openai'
        ? await callOpenAIDigest(apiKey, settings.openai_model, source, pageText, dateLocal)
        : settings.preferred_provider === 'anthropic'
        ? await callAnthropicDigest(apiKey, settings.anthropic_model, source, pageText, dateLocal)
        : await callGeminiDigest(apiKey, settings.gemini_model, source, pageText, dateLocal);
      digest = r.digest;
      model = r.model;
      tokensIn = r.tokensIn;
      tokensOut = r.tokensOut;
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      await markError(sourceId, `AI failed: ${msg}`);
      return jsonResp({ error: msg }, 200);
    }
  }

  // Append references to body for Palm visibility (Palm doesn't have
  // a separate UI for them but reads them inline at the bottom).
  let finalBody = digest.body;
  if (references.length > 0) {
    finalBody += '\n\n==REFERENCES==\n' + references.join('\n');
  }

  const recordId = newUlid();
  const { error: insErr } = await supa.from('records').insert({
    id: recordId,
    user_id: source.user_id,
    type: 'mail',
    posture: 'open',
    body: finalBody,
    source: 'mail-fetcher',
    ai_status: 'done',
    ai_model: model,
    ai_tokens_in: tokensIn,
    ai_tokens_out: tokensOut,
    metadata: {
      mail_subject: digest.subject,
      mail_from: source.source_type === 'topic' ? 'AI research' : hostnameOf(source.url!),
      mail_source_id: source.id,
      mail_source_name: source.name,
      mail_source_url: source.url,
      mail_source_type: source.source_type,
      mail_topic: source.topic,
      mail_references: references,
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

  // Deduct platform credit (no-op for BYOK). Keyed on the inserted record
  // id so a retry of the same digest cannot double-charge.
  const costMicro = await chargeUsage(
    supa, settings.api_mode, source.user_id, model, tokensIn, tokensOut, recordId,
  );
  await supa.from('ai_usage').insert({
    user_id: source.user_id,
    api_mode: settings.api_mode,
    provider: settings.preferred_provider,
    model,
    tokens_in: tokensIn,
    tokens_out: tokensOut,
    cost_credits: 0,
    cost_micro_usd: costMicro,
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
    headers: { ...CORS_HEADERS, 'content-type': 'application/json' },
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

// ─── Topic research callers (built-in web search) ───────────────

interface ResearchResult {
  subject: string;
  body: string;
  references: string[];
  model: string;
  tokensIn: number;
  tokensOut: number;
}

async function callOpenAIResearch(
  apiKey: string,
  source: Source,
  dateLocal: string,
): Promise<ResearchResult> {
  // gpt-4o-mini-search-preview has built-in web search; the user's
  // openai_model setting is ignored here because not all OpenAI models
  // support search.
  const model = 'gpt-4o-mini-search-preview';
  const resp = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model,
      messages: [
        { role: 'system', content: researchSystemPrompt(source, dateLocal) },
        {
          role: 'user',
          content: `Research the topic and write the daily article: ${source.topic}. End with ==REFERENCES== listing every URL you consulted.`,
        },
      ],
      web_search_options: { search_context_size: 'high' },
      max_completion_tokens: 16000,
    }),
  });
  if (!resp.ok) throw new Error(`openai research ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  const txt: string = j.choices?.[0]?.message?.content ?? '';
  const parsed = parseResearchOutput(txt);
  // Harvest any URLs OpenAI cites via annotations (in case the model
  // forgot to repeat them in ==REFERENCES==).
  const ann = j.choices?.[0]?.message?.annotations as
    | Array<{ type?: string; url_citation?: { url?: string } }>
    | undefined;
  if (ann) {
    for (const a of ann) {
      const u = a?.url_citation?.url;
      if (u && !parsed.references.includes(u)) parsed.references.push(u);
    }
  }
  return {
    ...parsed,
    model: j.model ?? model,
    tokensIn: j.usage?.prompt_tokens ?? 0,
    tokensOut: j.usage?.completion_tokens ?? 0,
  };
}

async function callAnthropicResearch(
  apiKey: string,
  model: string,
  source: Source,
  dateLocal: string,
): Promise<ResearchResult> {
  const resp = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: {
      'x-api-key': apiKey,
      'anthropic-version': '2023-06-01',
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      model: model || 'claude-sonnet-4-5-20250929',
      max_tokens: 16000,
      system: [
        {
          type: 'text',
          text: researchSystemPrompt(source, dateLocal),
          cache_control: { type: 'ephemeral' },
        },
      ],
      tools: [
        {
          type: 'web_search_20250305',
          name: 'web_search',
          max_uses: 12,
        },
      ],
      messages: [
        {
          role: 'user',
          content: `Research the topic and write the daily article: ${source.topic}. End with ==REFERENCES== listing every URL you consulted.`,
        },
      ],
    }),
  });
  if (!resp.ok) throw new Error(`anthropic research ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  let text = '';
  const visitedUrls = new Set<string>();
  for (const block of j.content ?? []) {
    if (block.type === 'text') text += block.text;
    if (block.type === 'web_search_tool_result' && Array.isArray(block.content)) {
      for (const r of block.content) {
        if (r?.url) visitedUrls.add(r.url);
      }
    }
  }
  const parsed = parseResearchOutput(text);
  for (const u of visitedUrls) {
    if (!parsed.references.includes(u)) parsed.references.push(u);
  }
  return {
    ...parsed,
    model: j.model ?? model,
    tokensIn: j.usage?.input_tokens ?? 0,
    tokensOut: j.usage?.output_tokens ?? 0,
  };
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

async function callGeminiDigest(
  apiKey: string,
  model: string,
  source: Source,
  pageText: string,
  dateLocal: string,
): Promise<DigestResult> {
  const m = model || 'gemini-2.5-flash';
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(m)}:generateContent?key=${encodeURIComponent(apiKey)}`;
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: systemPrompt(source, dateLocal) }] },
      contents: [{
        role: 'user',
        parts: [{ text: `Source page text:\n\n${pageText}` }],
      }],
      generationConfig: {
        responseMimeType: 'application/json',
        responseSchema: DIGEST_SCHEMA,
        maxOutputTokens: 4096,
      },
    }),
  });
  if (!resp.ok) throw new Error(`gemini ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  const parts = j.candidates?.[0]?.content?.parts ?? [];
  let txt = '';
  for (const p of parts) if (typeof p.text === 'string') txt += p.text;
  let digest = { subject: '', body: '' };
  try {
    digest = JSON.parse(txt || '{"subject":"","body":""}') as { subject: string; body: string };
  } catch {
    digest = { subject: '', body: txt };
  }
  return {
    digest,
    model: j.modelVersion ?? m,
    tokensIn:  j.usageMetadata?.promptTokenCount ?? 0,
    tokensOut: j.usageMetadata?.candidatesTokenCount ?? 0,
  };
}

async function callGeminiResearch(
  apiKey: string,
  model: string,
  source: Source,
  dateLocal: string,
): Promise<ResearchResult> {
  // Gemini's built-in google_search tool grounds the response and
  // returns citations on candidates[0].groundingMetadata.
  // responseMimeType / responseSchema are NOT supported alongside
  // google_search, so we parse the SUBJECT/body/==REFERENCES== text
  // format the same way the OpenAI / Anthropic paths do.
  const m = model || 'gemini-2.5-flash';
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(m)}:generateContent?key=${encodeURIComponent(apiKey)}`;
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: researchSystemPrompt(source, dateLocal) }] },
      contents: [{
        role: 'user',
        parts: [{
          text: `Research the topic and write the daily article: ${source.topic}. End with ==REFERENCES== listing every URL you consulted.`,
        }],
      }],
      tools: [{ google_search: {} }],
      generationConfig: { maxOutputTokens: 16000, temperature: 0.7 },
    }),
  });
  if (!resp.ok) throw new Error(`gemini research ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  const parts = j.candidates?.[0]?.content?.parts ?? [];
  let text = '';
  for (const p of parts) if (typeof p.text === 'string') text += p.text;
  const parsed = parseResearchOutput(text);

  // Harvest grounding URLs the model may not have repeated in
  // ==REFERENCES==.
  const grounding = j.candidates?.[0]?.groundingMetadata;
  const chunks = grounding?.groundingChunks as
    | Array<{ web?: { uri?: string; title?: string } }>
    | undefined;
  if (chunks) {
    for (const c of chunks) {
      const u = c?.web?.uri;
      if (u && !parsed.references.includes(u)) parsed.references.push(u);
    }
  }

  return {
    ...parsed,
    model: j.modelVersion ?? m,
    tokensIn:  j.usageMetadata?.promptTokenCount ?? 0,
    tokensOut: j.usageMetadata?.candidatesTokenCount ?? 0,
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

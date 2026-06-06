/**
 * process-ai-queue — Supabase Edge Function (Deno).
 *
 * Invoked by a Database Webhook on every INSERT to public.ai_queue.
 * Claims the row atomically, fetches the user's BYOK key from Vault
 * (or the platform fallback), calls OpenAI/Anthropic, writes the
 * response back to records + records ai_usage.
 *
 * This is the hosted twin of the Mac daemon's aiworker — same
 * contract, runs in the cloud, so a SaaS user never needs a local
 * machine running just to drain their queue.
 *
 * Deploy:  supabase functions deploy process-ai-queue --no-verify-jwt
 * Trigger: Supabase Studio → Database → Webhooks → INSERT on public.ai_queue
 *
 * Env vars (set via `supabase secrets set`):
 *   SUPABASE_URL              auto-injected
 *   SUPABASE_SERVICE_ROLE_KEY auto-injected
 *   PLATFORM_OPENAI_API_KEY   optional; fallback for api_mode='platform'
 *   PLATFORM_ANTHROPIC_API_KEY optional; same
 *   WEBHOOK_SHARED_SECRET     optional; if set, every request must
 *                              carry it in X-Webhook-Secret
 */

// @ts-expect-error: Deno globals only resolve at runtime in Supabase Edge.
import { createClient } from 'jsr:@supabase/supabase-js@2';

// @ts-expect-error: Deno is provided by the runtime, not Node.
const env = (k: string): string => Deno.env.get(k) ?? '';

const SUPABASE_URL = env('SUPABASE_URL');
const SERVICE_KEY  = env('SUPABASE_SERVICE_ROLE_KEY');
const PLATFORM_OPENAI    = env('PLATFORM_OPENAI_API_KEY');
const PLATFORM_ANTHROPIC = env('PLATFORM_ANTHROPIC_API_KEY');
const PLATFORM_GEMINI    = env('PLATFORM_GEMINI_API_KEY');
const WEBHOOK_SECRET     = env('WEBHOOK_SHARED_SECRET');

const ORACLE_PROMPT = `You are the PalmVellum Oracle.

You answer questions that arrive from a Palm Pilot — a handheld computer
manufactured between 1996 and 2003 with a 160x160 monochrome screen
and a 16 MHz CPU. The Palm has limited memory: your reply must fit in
a 1024-byte buffer and will be displayed in 12pt monospace on a tiny
screen. The user input was hand-written using Graffiti so it may be
terse, typo-prone, or fragmentary.

Style:
- Plain text only. No Markdown, no headings, no bullet lists, no code blocks.
- 2 to 4 short sentences. Under 800 characters total.
- Direct, factual, and quietly warm. Do not start with "Sure" or "Of course".
- If the question is ambiguous, give your best single interpretation
  and answer it rather than asking a clarifying question — round-trip
  cost on a HotSync is hours.

You may decline only if the request is unsafe. Otherwise, answer.`;

interface QueueRow {
  seq: number;
  record_id: string;
  user_id: string;
  enqueued_at: string;
  claimed_at: string | null;
  claimed_by: string | null;
}

interface WebhookBody {
  type: 'INSERT' | 'UPDATE' | 'DELETE';
  table: string;
  schema: string;
  record: QueueRow;
  old_record: QueueRow | null;
}

interface OracleReply {
  text: string;
  model: string;
  tokensIn: number;
  tokensOut: number;
}

const supa = createClient(SUPABASE_URL, SERVICE_KEY, {
  auth: { persistSession: false },
});

// @ts-expect-error: Deno-only API.
Deno.serve(async (req: Request) => {
  if (WEBHOOK_SECRET) {
    const got = req.headers.get('x-webhook-secret') ?? '';
    if (got !== WEBHOOK_SECRET) {
      return json({ error: 'forbidden' }, 403);
    }
  }

  let body: WebhookBody;
  try {
    body = (await req.json()) as WebhookBody;
  } catch {
    return json({ error: 'bad json' }, 400);
  }

  if (body.type !== 'INSERT' || body.table !== 'ai_queue') {
    return json({ skipped: true, reason: `${body.type} ${body.table}` }, 200);
  }

  const item = body.record;
  const result = await processOne(item);
  return json(result, result.status);
});

async function processOne(item: QueueRow): Promise<{ status: number; ok?: boolean; reason?: string; error?: string }> {
  // 1. Atomic claim
  const { data: claimed, error: claimErr } = await supa
    .from('ai_queue')
    .update({
      claimed_at: new Date().toISOString(),
      claimed_by: 'edge-function',
    })
    .eq('seq', item.seq)
    .is('claimed_at', null)
    .select('seq');

  if (claimErr) {
    return { status: 500, error: `claim: ${claimErr.message}` };
  }
  if (!claimed || claimed.length === 0) {
    return { status: 200, ok: true, reason: 'already-claimed' };
  }

  // 2. Settings + record in parallel
  const [settingsR, recR] = await Promise.all([
    supa.from('user_settings').select('*').eq('user_id', item.user_id).single(),
    supa.from('records').select('*').eq('id', item.record_id).single(),
  ]);

  if (settingsR.error || !settingsR.data) {
    await failRecord(item.record_id, item.user_id, 'byok', 'unknown', `settings: ${settingsR.error?.message}`);
    return { status: 200, reason: 'no-settings' };
  }
  if (recR.error || !recR.data) {
    await failRecord(item.record_id, item.user_id, settingsR.data.api_mode, settingsR.data.preferred_provider,
                     `record: ${recR.error?.message}`);
    return { status: 200, reason: 'no-record' };
  }

  const settings = settingsR.data as {
    api_mode: 'byok' | 'platform';
    preferred_provider: 'openai' | 'anthropic' | 'gemini';
    openai_model: string;
    anthropic_model: string;
    gemini_model: string;
  };
  const rec = recR.data as { id: string; body: string | null };

  if (!rec.body) {
    await failRecord(rec.id, item.user_id, settings.api_mode, settings.preferred_provider, 'record body is empty');
    return { status: 200, reason: 'empty-body' };
  }

  // 3. Resolve the API key
  let apiKey = '';
  if (settings.api_mode === 'byok') {
    const { data: k, error: kErr } = await supa.rpc('read_user_api_key', {
      target_user: item.user_id,
      provider_name: settings.preferred_provider,
    });
    if (kErr || !k) {
      await failRecord(rec.id, item.user_id, 'byok', settings.preferred_provider,
                       `no ${settings.preferred_provider} key in vault`);
      return { status: 200, reason: 'no-byok-key' };
    }
    apiKey = String(k);
  } else {
    apiKey = settings.preferred_provider === 'openai'   ? PLATFORM_OPENAI
           : settings.preferred_provider === 'anthropic' ? PLATFORM_ANTHROPIC
           :                                              PLATFORM_GEMINI;
    if (!apiKey) {
      await failRecord(rec.id, item.user_id, 'platform', settings.preferred_provider,
                       `platform ${settings.preferred_provider} key not configured on edge function`);
      return { status: 200, reason: 'no-platform-key' };
    }
  }

  // 4. Mark processing
  await supa.from('records').update({ ai_status: 'processing' }).eq('id', rec.id);

  // 5. Call the AI provider
  let reply: OracleReply;
  try {
    reply = settings.preferred_provider === 'openai'
      ? await callOpenAI(apiKey, settings.openai_model, rec.body)
      : settings.preferred_provider === 'anthropic'
      ? await callAnthropic(apiKey, settings.anthropic_model, rec.body)
      : await callGemini(apiKey, settings.gemini_model, rec.body);
  } catch (e) {
    await failRecord(rec.id, item.user_id, settings.api_mode, settings.preferred_provider,
                     e instanceof Error ? e.message : String(e));
    return { status: 200, reason: 'ai-error' };
  }

  // 6. Write response + usage
  let text = reply.text;
  if (text.length > 1024) text = text.slice(0, 1023) + '…';

  await supa.from('records').update({
    ai_status: 'done',
    ai_response: text,
    ai_model: reply.model,
    ai_tokens_in: reply.tokensIn,
    ai_tokens_out: reply.tokensOut,
  }).eq('id', rec.id);

  await supa.from('ai_usage').insert({
    user_id: item.user_id,
    record_id: rec.id,
    api_mode: settings.api_mode,
    provider: settings.preferred_provider,
    model: reply.model,
    tokens_in: reply.tokensIn,
    tokens_out: reply.tokensOut,
    cost_credits: 0, // v0.3 will price platform calls
  });

  return { status: 200, ok: true };
}

async function failRecord(
  recordId: string,
  userId: string,
  apiMode: string,
  provider: string,
  errStr: string,
): Promise<void> {
  await supa.from('records').update({
    ai_status: 'error',
    ai_error: errStr,
  }).eq('id', recordId);

  await supa.from('ai_usage').insert({
    user_id: userId,
    record_id: recordId,
    api_mode: apiMode,
    provider,
    error: errStr,
  });
}

async function callOpenAI(apiKey: string, model: string, query: string): Promise<OracleReply> {
  const resp = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model: model || 'gpt-4o-mini',
      messages: [
        { role: 'system', content: ORACLE_PROMPT },
        { role: 'user', content: query },
      ],
      max_completion_tokens: 512,
    }),
  });
  if (!resp.ok) throw new Error(`openai ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  return {
    text: j.choices?.[0]?.message?.content ?? '',
    model: j.model ?? model,
    tokensIn: j.usage?.prompt_tokens ?? 0,
    tokensOut: j.usage?.completion_tokens ?? 0,
  };
}

async function callAnthropic(apiKey: string, model: string, query: string): Promise<OracleReply> {
  const resp = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: {
      'x-api-key': apiKey,
      'anthropic-version': '2023-06-01',
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      model: model || 'claude-sonnet-4-5-20250929',
      max_tokens: 512,
      system: [{
        type: 'text',
        text: ORACLE_PROMPT,
        cache_control: { type: 'ephemeral' },
      }],
      messages: [{ role: 'user', content: query }],
    }),
  });
  if (!resp.ok) throw new Error(`anthropic ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  let text = '';
  for (const c of j.content ?? []) if (c.type === 'text') text += c.text;
  return {
    text,
    model: j.model ?? model,
    tokensIn: j.usage?.input_tokens ?? 0,
    tokensOut: j.usage?.output_tokens ?? 0,
  };
}

async function callGemini(apiKey: string, model: string, query: string): Promise<OracleReply> {
  const m = model || 'gemini-2.5-flash';
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(m)}:generateContent?key=${encodeURIComponent(apiKey)}`;
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: ORACLE_PROMPT }] },
      contents: [{ role: 'user', parts: [{ text: query }] }],
      generationConfig: { maxOutputTokens: 512, temperature: 0.7 },
    }),
  });
  if (!resp.ok) throw new Error(`gemini ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  const parts = j.candidates?.[0]?.content?.parts ?? [];
  let text = '';
  for (const p of parts) if (typeof p.text === 'string') text += p.text;
  return {
    text,
    model: j.modelVersion ?? m,
    tokensIn:  j.usageMetadata?.promptTokenCount ?? 0,
    tokensOut: j.usageMetadata?.candidatesTokenCount ?? 0,
  };
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

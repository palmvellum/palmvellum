/**
 * process-event-draft — Supabase Edge Function (Deno).
 *
 * Database Webhook fires this on every INSERT into public.event_drafts
 * with status='pending'. The function:
 *   1. Loads the user's settings + decrypts their BYOK API key from
 *      Vault (same pattern as process-ai-queue).
 *   2. Calls the configured AI provider with the user's raw_input
 *      and a strict JSON schema asking for an array of structured
 *      calendar events.
 *   3. Resolves relative phrases like "next Friday" by passing the
 *      user's current local datetime + timezone in the prompt.
 *   4. Writes the parsed events back to event_drafts.parsed_events
 *      with status='parsed', plus token + model accounting.
 *
 * The PWA's Realtime subscription on event_drafts catches the row
 * update and shows the user the draft list for accept/edit/reject.
 *
 * Deploy: same Management-API mechanism as process-ai-queue.
 */

// @ts-expect-error Deno runtime
import { createClient } from 'jsr:@supabase/supabase-js@2';

// @ts-expect-error Deno globals
const env = (k: string): string => Deno.env.get(k) ?? '';

const SUPABASE_URL = env('SUPABASE_URL');
const SERVICE_KEY = env('SUPABASE_SERVICE_ROLE_KEY');
const PLATFORM_OPENAI = env('PLATFORM_OPENAI_API_KEY');
const PLATFORM_ANTHROPIC = env('PLATFORM_ANTHROPIC_API_KEY');
const WEBHOOK_SECRET = env('WEBHOOK_SHARED_SECRET');

const supa = createClient(SUPABASE_URL, SERVICE_KEY, {
  auth: { persistSession: false },
});

interface DraftRow {
  id: string;
  user_id: string;
  raw_input: string;
  user_tz: string;
  status: string;
}

interface WebhookBody {
  type: string;
  table: string;
  record: DraftRow;
}

interface ParsedEvent {
  title: string;
  start_at: string;
  end_at: string | null;
  all_day: boolean;
  location: string | null;
  notes: string | null;
  alarm_minutes: number | null;
}

const EVENT_SCHEMA = {
  type: 'object',
  properties: {
    events: {
      type: 'array',
      description: 'One entry per distinct calendar event extracted from the input.',
      items: {
        type: 'object',
        properties: {
          title: { type: 'string', description: 'Short event title (under 80 chars).' },
          start_at: {
            type: 'string',
            description:
              'ISO 8601 datetime with timezone offset, e.g. 2026-06-07T07:00:00+08:00. Resolve relative phrases using the user_now hint.',
          },
          end_at: {
            type: ['string', 'null'],
            description: 'ISO 8601 datetime, or null if no specific end time was given.',
          },
          all_day: { type: 'boolean' },
          location: { type: ['string', 'null'] },
          notes: { type: ['string', 'null'] },
          alarm_minutes: {
            type: ['integer', 'null'],
            description: 'Minutes before start_at to alarm. Null if none mentioned.',
          },
        },
        required: ['title', 'start_at', 'end_at', 'all_day', 'location', 'notes', 'alarm_minutes'],
        additionalProperties: false,
      },
    },
  },
  required: ['events'],
  additionalProperties: false,
} as const;

function systemPrompt(): string {
  return `You extract calendar events from messy human input.

Rules:
- Return ONLY events the user clearly wants scheduled. If the input is a thought, a question, or unrelated chat, return events: [].
- Resolve relative phrases (today, tomorrow, next Friday, this weekend, in 3 days) using the user_now and user_tz the caller provides.
- Times are ALWAYS in the user's local timezone. Output ISO 8601 strings WITH the correct timezone offset for that local time.
- If the input gives only a date but no time, set all_day=true and use 00:00 as start_at (still in local tz).
- If the input gives a start time but no end, set end_at=null. Do not invent an end time.
- alarm_minutes: only set if the user explicitly mentions a reminder (e.g. "remind me 30 min before").
- For recurring phrases like "every Mon Wed Fri", emit one event per occurrence within the next 4 weeks.
- Multi-event input: emit each as a separate item in the array.
- Keep titles short and direct ("Lunch with May" not "I will be having lunch with May").
- The user typed the input on a Palm Pilot or in a hurry — be forgiving with typos and shorthand.

You may not return more than 20 events.`;
}

// @ts-expect-error Deno-only API
Deno.serve(async (req: Request) => {
  if (WEBHOOK_SECRET) {
    const got = req.headers.get('x-webhook-secret') ?? '';
    if (got !== WEBHOOK_SECRET) return jsonResp({ error: 'forbidden' }, 403);
  }

  let body: WebhookBody;
  try {
    body = (await req.json()) as WebhookBody;
  } catch {
    return jsonResp({ error: 'bad json' }, 400);
  }
  if (body.type !== 'INSERT' || body.table !== 'event_drafts') {
    return jsonResp({ skipped: true }, 200);
  }

  const item = body.record;
  if (item.status !== 'pending') {
    return jsonResp({ skipped: 'not-pending' }, 200);
  }

  // Race guard: claim the draft by flipping pending → parsing
  const { data: claimed, error: claimErr } = await supa
    .from('event_drafts')
    .update({ status: 'parsing' })
    .eq('id', item.id)
    .eq('status', 'pending')
    .select('id');
  if (claimErr || !claimed || claimed.length === 0) {
    return jsonResp({ skipped: 'already-parsing' }, 200);
  }

  // Settings + Vault key
  const { data: settings } = await supa
    .from('user_settings')
    .select('api_mode, preferred_provider, openai_model, anthropic_model, timezone')
    .eq('user_id', item.user_id)
    .single();
  if (!settings) {
    await failDraft(item.id, 'no user_settings');
    return jsonResp({ error: 'no-settings' }, 200);
  }

  let apiKey = '';
  if (settings.api_mode === 'byok') {
    const { data: k } = await supa.rpc('read_user_api_key', {
      target_user: item.user_id,
      provider_name: settings.preferred_provider,
    });
    if (!k) {
      await failDraft(item.id, `no ${settings.preferred_provider} key in vault`);
      return jsonResp({ error: 'no-byok-key' }, 200);
    }
    apiKey = String(k);
  } else {
    apiKey = settings.preferred_provider === 'openai' ? PLATFORM_OPENAI : PLATFORM_ANTHROPIC;
    if (!apiKey) {
      await failDraft(item.id, `platform ${settings.preferred_provider} key not configured`);
      return jsonResp({ error: 'no-platform-key' }, 200);
    }
  }

  const tz = item.user_tz || settings.timezone || 'UTC';
  const now = new Date();
  const userNow = formatLocal(now, tz);

  try {
    const result =
      settings.preferred_provider === 'openai'
        ? await callOpenAIStructured(apiKey, settings.openai_model, item.raw_input, userNow, tz)
        : await callAnthropicTool(apiKey, settings.anthropic_model, item.raw_input, userNow, tz);

    await supa
      .from('event_drafts')
      .update({
        status: 'parsed',
        parsed_events: result.events,
        ai_provider: settings.preferred_provider,
        ai_model: result.model,
        ai_tokens_in: result.tokensIn,
        ai_tokens_out: result.tokensOut,
        processed_at: new Date().toISOString(),
      })
      .eq('id', item.id);

    await supa.from('ai_usage').insert({
      user_id: item.user_id,
      api_mode: settings.api_mode,
      provider: settings.preferred_provider,
      model: result.model,
      tokens_in: result.tokensIn,
      tokens_out: result.tokensOut,
      cost_credits: 0,
    });

    return jsonResp({ ok: true, events: result.events.length }, 200);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    await failDraft(item.id, msg);
    return jsonResp({ error: msg }, 200);
  }
});

async function failDraft(id: string, errMsg: string) {
  await supa
    .from('event_drafts')
    .update({
      status: 'error',
      ai_error: errMsg,
      processed_at: new Date().toISOString(),
    })
    .eq('id', id);
}

function jsonResp(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

/** "2026-06-04 22:15 Asia/Hong_Kong" */
function formatLocal(d: Date, tz: string): string {
  try {
    const fmt = new Intl.DateTimeFormat('en-CA', {
      timeZone: tz,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
      weekday: 'long',
    });
    return fmt.format(d) + ' ' + tz;
  } catch {
    return d.toISOString() + ' UTC';
  }
}

async function callOpenAIStructured(
  apiKey: string,
  model: string,
  raw: string,
  userNow: string,
  tz: string,
): Promise<{ events: ParsedEvent[]; model: string; tokensIn: number; tokensOut: number }> {
  const resp = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model: model || 'gpt-4o-mini',
      messages: [
        { role: 'system', content: systemPrompt() },
        {
          role: 'user',
          content:
            `user_now: ${userNow}\nuser_tz: ${tz}\n\nINPUT:\n${raw}`,
        },
      ],
      response_format: {
        type: 'json_schema',
        json_schema: {
          name: 'extract_events',
          strict: true,
          schema: EVENT_SCHEMA,
        },
      },
      max_completion_tokens: 2048,
    }),
  });
  if (!resp.ok) throw new Error(`openai ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  const txt = j.choices?.[0]?.message?.content ?? '{"events":[]}';
  const parsed = JSON.parse(txt) as { events: ParsedEvent[] };
  return {
    events: parsed.events ?? [],
    model: j.model ?? model,
    tokensIn: j.usage?.prompt_tokens ?? 0,
    tokensOut: j.usage?.completion_tokens ?? 0,
  };
}

async function callAnthropicTool(
  apiKey: string,
  model: string,
  raw: string,
  userNow: string,
  tz: string,
): Promise<{ events: ParsedEvent[]; model: string; tokensIn: number; tokensOut: number }> {
  // Use Anthropic tool_use to force structured output.
  const resp = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: {
      'x-api-key': apiKey,
      'anthropic-version': '2023-06-01',
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      model: model || 'claude-sonnet-4-5-20250929',
      max_tokens: 2048,
      system: [
        {
          type: 'text',
          text: systemPrompt(),
          cache_control: { type: 'ephemeral' },
        },
      ],
      tools: [
        {
          name: 'submit_events',
          description: 'Emit the structured list of extracted events.',
          input_schema: EVENT_SCHEMA,
        },
      ],
      tool_choice: { type: 'tool', name: 'submit_events' },
      messages: [
        {
          role: 'user',
          content: `user_now: ${userNow}\nuser_tz: ${tz}\n\nINPUT:\n${raw}`,
        },
      ],
    }),
  });
  if (!resp.ok) throw new Error(`anthropic ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  let events: ParsedEvent[] = [];
  for (const block of j.content ?? []) {
    if (block.type === 'tool_use' && block.name === 'submit_events') {
      events = (block.input?.events ?? []) as ParsedEvent[];
      break;
    }
  }
  return {
    events,
    model: j.model ?? model,
    tokensIn: j.usage?.input_tokens ?? 0,
    tokensOut: j.usage?.output_tokens ?? 0,
  };
}

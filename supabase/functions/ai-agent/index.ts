/**
 * ai-agent — Supabase Edge Function (Deno).
 *
 * Fires from records INSERT when type IN ('thought','todo') AND
 * body matches /^\s*\(ai\)/i. Runs a tool-use loop:
 *
 *   create_event   → INSERT into events
 *   create_todo    → INSERT into records type='todo'
 *   create_memo    → INSERT into records type='thought'
 *   finish         → exit loop with a 1-2 line summary
 *
 * Exit handling:
 *   • memo source → append "\n— AI agent —\n<summary>" to body
 *   • todo source → create a memo titled "AI Result: <prompt>" with
 *                   summary as body, mark original todo
 *                   metadata.palm_completed = true
 *
 * Both providers supported:
 *   • OpenAI chat completions with `tools` (function calling)
 *   • Anthropic Messages API with tool_use blocks
 *
 * Max iterations 5 to bound token cost. metadata.agent_processed
 * is stamped to prevent re-triggering on UPDATE.
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

const MAX_ITERATIONS = 5;

const AGENT_SEPARATOR = '\n\n-- AI agent --\n';

interface RecordRow {
  id: string;
  user_id: string;
  type: 'thought' | 'todo' | string;
  body: string;
  ai_status: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
}

interface WebhookBody {
  type: string;
  table: string;
  record: RecordRow;
}

interface ToolCall {
  id: string;
  name: string;
  args: Record<string, unknown>;
}

interface ToolResult {
  id: string;
  name: string;
  args: Record<string, unknown>;
  result: Record<string, unknown> | { error: string };
}

// ─── Tool schemas (shared shape; both providers use them) ───────

const TOOL_CREATE_EVENT = {
  type: 'object',
  properties: {
    title: { type: 'string', description: 'Short event title (≤ 80 chars).' },
    start_at: {
      type: 'string',
      description: 'ISO 8601 datetime WITH timezone offset, e.g. 2026-06-08T14:30:00+08:00.',
    },
    end_at: { type: ['string', 'null'], description: 'ISO 8601 or null.' },
    all_day: { type: 'boolean' },
    location: { type: ['string', 'null'] },
    notes: { type: ['string', 'null'] },
    alarm_minutes: {
      type: ['integer', 'null'],
      description: 'Minutes before to alarm, or null.',
    },
  },
  required: ['title', 'start_at', 'end_at', 'all_day', 'location', 'notes', 'alarm_minutes'],
  additionalProperties: false,
} as const;

const TOOL_CREATE_TODO = {
  type: 'object',
  properties: {
    description: { type: 'string', description: 'Task description (≤ 256 chars).' },
    due_date: {
      type: ['string', 'null'],
      description: 'YYYY-MM-DD in user TZ, or null.',
    },
    priority: { type: 'integer', description: '1 (top) … 5 (low).' },
    notes: { type: ['string', 'null'] },
  },
  required: ['description', 'due_date', 'priority', 'notes'],
  additionalProperties: false,
} as const;

const TOOL_CREATE_MEMO = {
  type: 'object',
  properties: {
    title: { type: 'string', description: 'Short title; first line of memo.' },
    body: { type: 'string', description: 'Memo body content.' },
  },
  required: ['title', 'body'],
  additionalProperties: false,
} as const;

const TOOL_FINISH = {
  type: 'object',
  properties: {
    summary: {
      type: 'string',
      description:
        '1–2 line summary of what you accomplished. ≤ 400 chars. Plain text only.',
    },
  },
  required: ['summary'],
  additionalProperties: false,
} as const;

const TOOL_LIST = [
  { name: 'create_event', description: 'Create a Date Book event.', schema: TOOL_CREATE_EVENT },
  { name: 'create_todo', description: 'Create a To Do task.', schema: TOOL_CREATE_TODO },
  {
    name: 'create_memo',
    description: 'Create a new Memo Pad entry separate from the original.',
    schema: TOOL_CREATE_MEMO,
  },
  {
    name: 'finish',
    description: 'Conclude the task with a short summary. MUST call this last.',
    schema: TOOL_FINISH,
  },
];

// ─── HTTP entrypoint ────────────────────────────────────────────

// @ts-expect-error Deno-only API
Deno.serve(async (req: Request) => {
  let body: WebhookBody;
  try {
    body = (await req.json()) as WebhookBody;
  } catch {
    return jsonResp({ error: 'bad json' }, 400);
  }
  if (body.type !== 'INSERT' || body.table !== 'records') {
    return jsonResp({ skipped: true }, 200);
  }
  const r = body.record;
  if (r.type !== 'thought' && r.type !== 'todo') {
    return jsonResp({ skipped: 'wrong-type' }, 200);
  }
  if (!/^\s*\(ai\)/i.test(r.body ?? '')) {
    return jsonResp({ skipped: 'no-prefix' }, 200);
  }

  // Claim — flip ai_status to processing. `.in()` doesn't match true
  // NULL, so use a string OR filter that covers both NULL and 'pending'.
  const { data: claimed, error: claimErr } = await supa
    .from('records')
    .update({ ai_status: 'processing' })
    .eq('id', r.id)
    .or('ai_status.is.null,ai_status.eq.pending')
    .select('id');
  if (claimErr || !claimed || claimed.length === 0) {
    return jsonResp({ skipped: 'already-processing' }, 200);
  }

  // Resolve API key
  const { data: settings } = await supa
    .from('user_settings')
    .select('api_mode, preferred_provider, openai_model, anthropic_model, timezone')
    .eq('user_id', r.user_id)
    .single();
  if (!settings) {
    await markError(r, 'no user_settings');
    return jsonResp({ error: 'no-settings' }, 200);
  }

  let apiKey = '';
  if (settings.api_mode === 'byok') {
    const { data: k } = await supa.rpc('read_user_api_key', {
      target_user: r.user_id,
      provider_name: settings.preferred_provider,
    });
    if (!k) {
      await markError(r, `no ${settings.preferred_provider} key in vault`);
      return jsonResp({ error: 'no-key' }, 200);
    }
    apiKey = String(k);
  } else {
    apiKey = settings.preferred_provider === 'openai' ? PLATFORM_OPENAI : PLATFORM_ANTHROPIC;
    if (!apiKey) {
      await markError(r, 'platform key not configured');
      return jsonResp({ error: 'no-platform-key' }, 200);
    }
  }

  // Strip (AI) prefix once
  const prompt = (r.body ?? '').replace(/^\s*\(ai\)\s*/i, '').trim();
  const userTz = (settings.timezone as string) || 'UTC';
  const nowLocal = formatLocal(new Date(), userTz);
  const sourceKind = r.type as 'thought' | 'todo';

  const actions: ToolResult[] = [];
  let totalIn = 0;
  let totalOut = 0;
  let summary = '';

  try {
    const result =
      settings.preferred_provider === 'openai'
        ? await runOpenAIAgent(apiKey, settings.openai_model, r, sourceKind, prompt, userTz, nowLocal, actions)
        : await runAnthropicAgent(apiKey, settings.anthropic_model, r, sourceKind, prompt, userTz, nowLocal, actions);
    summary = result.summary;
    totalIn = result.tokensIn;
    totalOut = result.tokensOut;
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    await markError(r, `agent failed: ${msg}`, actions);
    return jsonResp({ error: msg }, 200);
  }

  // Finalize per source type
  if (sourceKind === 'thought') {
    const newBody = (r.body ?? '') + AGENT_SEPARATOR + (summary || '(no summary)');
    await supa
      .from('records')
      .update({
        body: newBody,
        ai_status: 'done',
        ai_tokens_in: totalIn,
        ai_tokens_out: totalOut,
        metadata: {
          ...(r.metadata ?? {}),
          agent_processed: true,
          agent_summary: summary,
          ai_actions: actions,
        },
      })
      .eq('id', r.id);
  } else {
    // Todo — create AI Result memo + mark complete
    const resultMemoId = newUlid();
    await supa.from('records').insert({
      id: resultMemoId,
      user_id: r.user_id,
      type: 'thought',
      posture: 'open',
      body: `AI Result: ${truncate(prompt, 80)}\n\n${summary}`,
      source: 'agent',
      metadata: {
        palm_category_name: 'AI',
        agent_for_todo: r.id,
      },
    });
    await supa
      .from('records')
      .update({
        ai_status: 'done',
        ai_tokens_in: totalIn,
        ai_tokens_out: totalOut,
        metadata: {
          ...(r.metadata ?? {}),
          agent_processed: true,
          agent_summary: summary,
          agent_result_memo: resultMemoId,
          palm_completed: true,
          ai_actions: actions,
        },
      })
      .eq('id', r.id);
  }

  await supa.from('ai_usage').insert({
    user_id: r.user_id,
    api_mode: settings.api_mode,
    provider: settings.preferred_provider,
    model: settings.preferred_provider === 'openai' ? settings.openai_model : settings.anthropic_model,
    tokens_in: totalIn,
    tokens_out: totalOut,
    cost_credits: 0,
  });

  return jsonResp({ ok: true, summary, actions: actions.length }, 200);
});

// ─── helpers ────────────────────────────────────────────────────

async function markError(
  r: RecordRow,
  errMsg: string,
  actions: ToolResult[] = [],
): Promise<void> {
  await supa
    .from('records')
    .update({
      ai_status: 'error',
      ai_error: errMsg,
      metadata: {
        ...(r.metadata ?? {}),
        agent_processed: true,
        ai_actions: actions,
      },
    })
    .eq('id', r.id);
}

function jsonResp(b: unknown, status: number = 200): Response {
  return new Response(JSON.stringify(b), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n) + '…' : s;
}

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

function systemPromptFor(
  sourceKind: 'thought' | 'todo',
  prompt: string,
  userTz: string,
  nowLocal: string,
): string {
  const common = `now: ${nowLocal} (${userTz})

Tools available:
- create_event(title, start_at, end_at, all_day, location, notes, alarm_minutes)
- create_todo(description, due_date, priority, notes)
- create_memo(title, body)
- finish(summary)

PALM CHARACTER SET CONSTRAINT — content you write into tools (title, body, summary, etc.) is shown on a Palm Pilot:
- ASCII or Mac Roman / Palm Roman characters ONLY.
- NO emoji whatsoever.
- NO arrow / checkmark / star / etc. symbols (use ASCII like -> [done] [!] ).
- ASCII quotes only.

When done, you MUST call finish(summary). Summary is the user-facing recap of what you did.
Keep tool calls under five total. Resolve relative dates ("tomorrow", "next Friday") using the now timestamp above.`;

  if (sourceKind === 'thought') {
    return `You are an agent processing a Palm Memo Pad note that the user prefixed with "(AI)". Read the memo content below and extract every actionable item. Schedule events, file tasks, or write new memos as appropriate. Don't ask the user — just do it. Then call finish(summary).

Memo content (after stripping "(AI)"):
${prompt}

${common}`;
  }

  return `You are an agent executing a Palm To-Do task the user prefixed with "(AI)". Treat the task as an instruction — research, compute, or plan, then deliver an answer. Use create_event/create_todo if the answer naturally produces calendar entries or follow-up tasks. Call finish(summary) with the actual answer text; the system delivers it to the user as a new Memo titled "AI Result: <task>".

Task (after stripping "(AI)"):
${prompt}

${common}`;
}

// ─── Tool execution (provider-agnostic) ─────────────────────────

async function executeTool(
  userId: string,
  call: ToolCall,
): Promise<Record<string, unknown> | { error: string }> {
  try {
    switch (call.name) {
      case 'create_event': {
        const a = call.args as {
          title: string;
          start_at: string;
          end_at: string | null;
          all_day: boolean;
          location: string | null;
          notes: string | null;
          alarm_minutes: number | null;
        };
        const eventId = newUlid();
        const { error } = await supa.from('events').insert({
          id: eventId,
          user_id: userId,
          source: 'agent',
          title: a.title,
          start_at: a.start_at,
          end_at: a.end_at,
          all_day: a.all_day,
          location: a.location,
          notes: a.notes,
          alarm_minutes: a.alarm_minutes,
        });
        if (error) return { error: error.message };
        return { ok: true, event_id: eventId };
      }
      case 'create_todo': {
        const a = call.args as {
          description: string;
          due_date: string | null;
          priority: number;
          notes: string | null;
        };
        const todoId = newUlid();
        const { error } = await supa.from('records').insert({
          id: todoId,
          user_id: userId,
          type: 'todo',
          posture: 'open',
          body: a.description,
          source: 'agent',
          metadata: {
            palm_due_date: a.due_date ?? '',
            palm_priority: a.priority,
            palm_completed: false,
            palm_notes: a.notes ?? '',
            palm_category_name: 'Unfiled',
          },
        });
        if (error) return { error: error.message };
        return { ok: true, todo_id: todoId };
      }
      case 'create_memo': {
        const a = call.args as { title: string; body: string };
        const memoId = newUlid();
        const composedBody = a.title ? `${a.title}\n\n${a.body}` : a.body;
        const { error } = await supa.from('records').insert({
          id: memoId,
          user_id: userId,
          type: 'thought',
          posture: 'open',
          body: composedBody,
          source: 'agent',
          metadata: { palm_category_name: 'Unfiled' },
        });
        if (error) return { error: error.message };
        return { ok: true, memo_id: memoId };
      }
      default:
        return { error: `unknown tool: ${call.name}` };
    }
  } catch (e) {
    return { error: e instanceof Error ? e.message : String(e) };
  }
}

// ─── OpenAI agent ───────────────────────────────────────────────

async function runOpenAIAgent(
  apiKey: string,
  model: string,
  record: RecordRow,
  sourceKind: 'thought' | 'todo',
  prompt: string,
  userTz: string,
  nowLocal: string,
  actions: ToolResult[],
): Promise<{ summary: string; tokensIn: number; tokensOut: number }> {
  const sysPrompt = systemPromptFor(sourceKind, prompt, userTz, nowLocal);
  const tools = TOOL_LIST.map((t) => ({
    type: 'function',
    function: { name: t.name, description: t.description, parameters: t.schema, strict: true },
  }));
  const messages: any[] = [
    { role: 'system', content: sysPrompt },
    { role: 'user', content: 'Begin. Use tools, then call finish.' },
  ];
  let totalIn = 0;
  let totalOut = 0;

  for (let i = 0; i < MAX_ITERATIONS; i++) {
    const resp = await fetch('https://api.openai.com/v1/chat/completions', {
      method: 'POST',
      headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: model || 'gpt-4o-mini',
        messages,
        tools,
        tool_choice: 'auto',
        max_completion_tokens: 1024,
      }),
    });
    if (!resp.ok) throw new Error(`openai ${resp.status}: ${await resp.text()}`);
    const j = await resp.json();
    totalIn += j.usage?.prompt_tokens ?? 0;
    totalOut += j.usage?.completion_tokens ?? 0;

    const msg = j.choices?.[0]?.message;
    const toolCalls = msg?.tool_calls ?? [];
    if (toolCalls.length === 0) {
      // Bare text ending without finish — accept as summary
      return {
        summary: (msg?.content ?? '').trim() || '(agent ended without summary)',
        tokensIn: totalIn,
        tokensOut: totalOut,
      };
    }

    // Echo the assistant turn back into history
    messages.push(msg);

    let finishedSummary: string | null = null;
    for (const tc of toolCalls) {
      const args = safeJsonParse(tc.function?.arguments ?? '{}');
      const call: ToolCall = { id: tc.id, name: tc.function?.name ?? '', args };
      if (call.name === 'finish') {
        finishedSummary = (args.summary as string) ?? '(no summary)';
        messages.push({
          role: 'tool',
          tool_call_id: tc.id,
          content: JSON.stringify({ ok: true }),
        });
      } else {
        const result = await executeTool(record.user_id, call);
        actions.push({ id: tc.id, name: call.name, args, result });
        messages.push({
          role: 'tool',
          tool_call_id: tc.id,
          content: JSON.stringify(result),
        });
      }
    }

    if (finishedSummary !== null) {
      return { summary: finishedSummary, tokensIn: totalIn, tokensOut: totalOut };
    }
  }

  return {
    summary: '(agent stopped — reached max iterations without calling finish)',
    tokensIn: totalIn,
    tokensOut: totalOut,
  };
}

// ─── Anthropic agent ────────────────────────────────────────────

async function runAnthropicAgent(
  apiKey: string,
  model: string,
  record: RecordRow,
  sourceKind: 'thought' | 'todo',
  prompt: string,
  userTz: string,
  nowLocal: string,
  actions: ToolResult[],
): Promise<{ summary: string; tokensIn: number; tokensOut: number }> {
  const sysPrompt = systemPromptFor(sourceKind, prompt, userTz, nowLocal);
  const tools = TOOL_LIST.map((t) => ({
    name: t.name,
    description: t.description,
    input_schema: t.schema,
  }));
  const messages: any[] = [
    { role: 'user', content: 'Begin. Use tools, then call finish.' },
  ];
  let totalIn = 0;
  let totalOut = 0;

  for (let i = 0; i < MAX_ITERATIONS; i++) {
    const resp = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      headers: {
        'x-api-key': apiKey,
        'anthropic-version': '2023-06-01',
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        model: model || 'claude-sonnet-4-5-20250929',
        max_tokens: 1024,
        system: [
          { type: 'text', text: sysPrompt, cache_control: { type: 'ephemeral' } },
        ],
        tools,
        messages,
      }),
    });
    if (!resp.ok) throw new Error(`anthropic ${resp.status}: ${await resp.text()}`);
    const j = await resp.json();
    totalIn += j.usage?.input_tokens ?? 0;
    totalOut += j.usage?.output_tokens ?? 0;

    const content = j.content ?? [];
    messages.push({ role: 'assistant', content });

    const toolUses = content.filter((b: any) => b.type === 'tool_use');
    if (toolUses.length === 0) {
      const text = content
        .filter((b: any) => b.type === 'text')
        .map((b: any) => b.text)
        .join('\n')
        .trim();
      return {
        summary: text || '(agent ended without summary)',
        tokensIn: totalIn,
        tokensOut: totalOut,
      };
    }

    const toolResults: any[] = [];
    let finishedSummary: string | null = null;
    for (const tu of toolUses) {
      const call: ToolCall = { id: tu.id, name: tu.name, args: tu.input ?? {} };
      if (call.name === 'finish') {
        finishedSummary = (call.args.summary as string) ?? '(no summary)';
        toolResults.push({
          type: 'tool_result',
          tool_use_id: tu.id,
          content: JSON.stringify({ ok: true }),
        });
      } else {
        const result = await executeTool(record.user_id, call);
        actions.push({ id: tu.id, name: call.name, args: call.args, result });
        toolResults.push({
          type: 'tool_result',
          tool_use_id: tu.id,
          content: JSON.stringify(result),
        });
      }
    }
    messages.push({ role: 'user', content: toolResults });

    if (finishedSummary !== null) {
      return { summary: finishedSummary, tokensIn: totalIn, tokensOut: totalOut };
    }
  }

  return {
    summary: '(agent stopped — reached max iterations without calling finish)',
    tokensIn: totalIn,
    tokensOut: totalOut,
  };
}

function safeJsonParse(s: string): Record<string, unknown> {
  try {
    return JSON.parse(s) as Record<string, unknown>;
  } catch {
    return {};
  }
}

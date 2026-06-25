/**
 * process-sketch — Supabase Edge Function (Deno).
 *
 * Database Webhook fires this whenever a records row of type='sketch'
 * is inserted. The function:
 *   1. Claims the row by flipping ai_status → 'processing'.
 *   2. Loads the user's BYOK API key from Vault (or the platform key
 *      when api_mode='platform').
 *   3. Constructs the public Storage URL of the uploaded image.
 *   4. Calls the configured vision model (OpenAI or Anthropic) with
 *      a prompt asking it to extract text from the Palm Note Pad
 *      sketch and describe any non-text drawings briefly.
 *   5. Writes the result to records.body and flips ai_status='done'.
 *
 * The PWA's Realtime subscription on records catches the update and
 * shows the extracted text inline below the sketch image.
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

interface SketchRow {
  id: string;
  user_id: string;
  type: string;
  body: string | null;
  ai_status: string | null;
  metadata: {
    image_path?: string;
    palm_title?: string;
  } | null;
}

interface WebhookBody {
  type: string;
  table: string;
  record: SketchRow;
}

const SYSTEM_PROMPT = `You are transcribing a Palm Pilot Note Pad sketch: black ink strokes on a white background. The drawing canvas is large, so wide white margins around the strokes are NORMAL and do NOT mean the sketch is empty. Look carefully at every stroke, including thin or faint lines, and at small drawings near the centre.

PALM CHARACTER SET CONSTRAINT — your output is displayed on a Palm Pilot:
- ASCII or Mac Roman / Palm Roman characters ONLY.
- NO emoji whatsoever.
- NO arrow / star / checkmark / etc. symbols.
- ASCII quotes only.

What to output:
- If there is handwritten text, transcribe it EXACTLY. Preserve line breaks.
- If there are drawings/diagrams/shapes/marks WITHOUT text, describe what is drawn in one short sentence prefixed with "[drawing] " (e.g. "[drawing] a hand-drawn heart", "[drawing] a simple smiley face", "[drawing] rough map of two streets crossing").
- If the sketch contains BOTH text and drawings, output the text first, then the "[drawing] ..." line.
- If you can see strokes but cannot tell what they depict, do NOT give up: describe the strokes literally on a "[drawing] " line — the shapes you see and roughly where (e.g. "[drawing] two short vertical lines above a downward curve", "[drawing] a few scribbled loops in the centre").
- No preamble, no commentary — just the transcription/description.
- Output exactly "(blank)" ONLY when there is genuinely no ink at all (a completely empty white image). If you can see ANY strokes, transcribe or describe them — never call a sketch that has visible strokes blank.`;

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
  if (r.type !== 'sketch') return jsonResp({ skipped: 'not-sketch' }, 200);
  if (!r.metadata?.image_path) {
    await failSketch(r.id, 'no image_path in metadata');
    return jsonResp({ error: 'no-image' }, 200);
  }

  // Race guard: claim by flipping ai_status NULL/pending → processing.
  // .in() doesn't match true NULL — use an OR filter.
  const { data: claimed, error: claimErr } = await supa
    .from('records')
    .update({ ai_status: 'processing' })
    .eq('id', r.id)
    .or('ai_status.is.null,ai_status.eq.pending')
    .select('id');
  if (claimErr || !claimed || claimed.length === 0) {
    return jsonResp({ skipped: 'already-processing' }, 200);
  }

  // BYOK key resolution
  const { data: settings } = await supa
    .from('user_settings')
    .select('api_mode, preferred_provider, openai_model, anthropic_model, gemini_model, balance_micro_usd, low_balance_threshold_micro')
    .eq('user_id', r.user_id)
    .single();
  if (!settings) {
    await failSketch(r.id, 'no user_settings');
    return jsonResp({ error: 'no-settings' }, 200);
  }

  let apiKey = '';
  if (settings.api_mode === 'byok') {
    const { data: k } = await supa.rpc('read_user_api_key', {
      target_user: r.user_id,
      provider_name: settings.preferred_provider,
    });
    if (!k) {
      await failSketch(r.id, `no ${settings.preferred_provider} key in vault`);
      return jsonResp({ error: 'no-byok-key' }, 200);
    }
    apiKey = String(k);
  } else {
    apiKey = settings.preferred_provider === 'openai'    ? PLATFORM_OPENAI
           : settings.preferred_provider === 'anthropic'  ? PLATFORM_ANTHROPIC
           :                                                PLATFORM_GEMINI;
    if (!apiKey) {
      await failSketch(r.id, `platform ${settings.preferred_provider} key not configured`);
      return jsonResp({ error: 'no-platform-key' }, 200);
    }
    // Pay-as-you-go: refuse a metered call when the balance is exhausted.
    if ((settings.balance_micro_usd ?? 0) <= 0) {
      await failSketch(r.id, 'insufficient credit — please top up');
      return jsonResp({ error: 'no-credit' }, 200);
    }
  }

  const imgUrl = `${SUPABASE_URL}/storage/v1/object/public/notepad/${r.metadata.image_path}`;

  try {
    const result =
      settings.preferred_provider === 'openai'
        ? await callOpenAIVision(apiKey, settings.openai_model, imgUrl)
        : settings.preferred_provider === 'anthropic'
        ? await callAnthropicVision(apiKey, settings.anthropic_model, imgUrl)
        : await callGeminiVision(apiKey, settings.gemini_model, imgUrl);

    await supa
      .from('records')
      .update({
        body: result.text,
        ai_status: 'done',
        ai_model: result.model,
        ai_tokens_in: result.tokensIn,
        ai_tokens_out: result.tokensOut,
        updated_at: new Date().toISOString(),
      })
      .eq('id', r.id);

    // Deduct platform credit (no-op for BYOK). Keyed on the record id so a
    // retry cannot double-charge.
    const costMicro = await chargeUsage(
      supa, settings.api_mode, r.user_id, result.model, result.tokensIn, result.tokensOut, r.id,
    );
    await supa.from('ai_usage').insert({
      user_id: r.user_id,
      api_mode: settings.api_mode,
      provider: settings.preferred_provider,
      model: result.model,
      tokens_in: result.tokensIn,
      tokens_out: result.tokensOut,
      cost_credits: 0,
      cost_micro_usd: costMicro,
    });

    return jsonResp({ ok: true, text_len: result.text.length }, 200);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    await failSketch(r.id, msg);
    return jsonResp({ error: msg }, 200);
  }
});

async function failSketch(id: string, errMsg: string) {
  await supa
    .from('records')
    .update({
      ai_status: 'error',
      ai_error: errMsg,
      updated_at: new Date().toISOString(),
    })
    .eq('id', id);
}

function jsonResp(body: unknown, status: number = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

interface VisionResult {
  text: string;
  model: string;
  tokensIn: number;
  tokensOut: number;
}

async function callOpenAIVision(
  apiKey: string,
  model: string,
  imgUrl: string,
): Promise<VisionResult> {
  const resp = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model: model || 'gpt-4o-mini',
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        {
          role: 'user',
          content: [
            { type: 'text', text: 'Transcribe / describe this sketch:' },
            { type: 'image_url', image_url: { url: imgUrl, detail: 'high' } },
          ],
        },
      ],
      max_completion_tokens: 1024,
    }),
  });
  if (!resp.ok) throw new Error(`openai ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  const txt: string = j.choices?.[0]?.message?.content ?? '';
  return {
    text: txt.trim(),
    model: j.model ?? model,
    tokensIn: j.usage?.prompt_tokens ?? 0,
    tokensOut: j.usage?.completion_tokens ?? 0,
  };
}

async function callAnthropicVision(
  apiKey: string,
  model: string,
  imgUrl: string,
): Promise<VisionResult> {
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
      system: SYSTEM_PROMPT,
      messages: [
        {
          role: 'user',
          content: [
            { type: 'image', source: { type: 'url', url: imgUrl } },
            { type: 'text', text: 'Transcribe / describe this sketch.' },
          ],
        },
      ],
    }),
  });
  if (!resp.ok) throw new Error(`anthropic ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  let text = '';
  for (const block of j.content ?? []) {
    if (block.type === 'text') {
      text += block.text;
    }
  }
  return {
    text: text.trim(),
    model: j.model ?? model,
    tokensIn: j.usage?.input_tokens ?? 0,
    tokensOut: j.usage?.output_tokens ?? 0,
  };
}

async function callGeminiVision(
  apiKey: string,
  model: string,
  imgUrl: string,
): Promise<VisionResult> {
  // Gemini's generateContent expects image bytes inline as base64.
  // The sketch is a PNG stored in the public 'notepad' bucket.
  const imgResp = await fetch(imgUrl);
  if (!imgResp.ok) throw new Error(`fetch sketch ${imgResp.status}`);
  const buf = new Uint8Array(await imgResp.arrayBuffer());
  let bin = '';
  for (let i = 0; i < buf.length; i++) bin += String.fromCharCode(buf[i]);
  const b64 = btoa(bin);

  const m = model || 'gemini-2.5-flash';
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(m)}:generateContent?key=${encodeURIComponent(apiKey)}`;
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: SYSTEM_PROMPT }] },
      contents: [{
        role: 'user',
        parts: [
          { inlineData: { mimeType: 'image/png', data: b64 } },
          { text: 'Transcribe / describe this sketch.' },
        ],
      }],
      generationConfig: { maxOutputTokens: 1024, temperature: 0.2 },
    }),
  });
  if (!resp.ok) throw new Error(`gemini ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  const parts = j.candidates?.[0]?.content?.parts ?? [];
  let text = '';
  for (const p of parts) if (typeof p.text === 'string') text += p.text;
  return {
    text: text.trim(),
    model: j.modelVersion ?? m,
    tokensIn:  j.usageMetadata?.promptTokenCount ?? 0,
    tokensOut: j.usageMetadata?.candidatesTokenCount ?? 0,
  };
}

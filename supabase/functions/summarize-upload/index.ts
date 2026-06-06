/**
 * summarize-upload — Supabase Edge Function (Deno).
 *
 * Triggered by records INSERT when metadata.upload_path is set
 * (Memo Pad file upload). Pipeline by file type:
 *
 *   image/*           → vision API summarises directly
 *   application/pdf   → unpdf extracts text → text LLM summarises
 *   *wordprocessing*  → mammoth extracts text → text LLM summarises
 *
 * Output goes into records.body as a Palm-friendly plain-text memo.
 * The filename header is preserved at the top so the user knows
 * which file this memo represents.
 */

// @ts-expect-error Deno runtime
import { createClient } from 'jsr:@supabase/supabase-js@2';
// @ts-expect-error Deno npm
import { extractText as extractPdfText } from 'npm:unpdf@0.12.1';
// @ts-expect-error Deno npm
import mammoth from 'npm:mammoth@1.8.0';

// @ts-expect-error Deno globals
const env = (k: string): string => Deno.env.get(k) ?? '';

const SUPABASE_URL = env('SUPABASE_URL');
const SERVICE_KEY = env('SUPABASE_SERVICE_ROLE_KEY');
const PLATFORM_OPENAI = env('PLATFORM_OPENAI_API_KEY');
const PLATFORM_ANTHROPIC = env('PLATFORM_ANTHROPIC_API_KEY');

const supa = createClient(SUPABASE_URL, SERVICE_KEY, {
  auth: { persistSession: false },
});

const MAX_TEXT_CHARS = 60_000;
const SIGNED_URL_TTL = 600; // seconds

const SYSTEM_PROMPT_TEXT =
  `You read uploaded documents for a Palm Pilot user and produce a Palm-friendly memo summary they can read on a 160x160 monochrome screen.

PALM CHARACTER SET CONSTRAINT — this is critical:
- Output ASCII or Mac Roman / Palm Roman characters ONLY.
- NO emoji whatsoever (no document/folder/checkmark/star icons).
- NO arrow symbols (use -> instead).
- NO bullet glyphs (use plain "- " hyphen or just paragraphs).
- ASCII quotes ' " only (not curly quotes).
- Em dash (--) and middle dot are OK, but prefer plain hyphens.

Content rules:
- Plain text. Short paragraphs. No markdown.
- First line: a tight headline (max 80 chars).
- Body: 150-500 words depending on content density. Capture the gist, key facts, dates / numbers / names, and any actionable items mentioned.
- Match the source language. Chinese source -> Traditional Chinese. English source -> English.
- If the document is essentially empty, output exactly: (no readable content)`;

const SYSTEM_PROMPT_IMAGE =
  `You analyse uploaded images for a Palm Pilot user and produce a Palm-friendly memo summary they can read on a 160x160 monochrome screen.

If the image contains text (a receipt, document scan, screenshot, handwritten note): transcribe the text. If it's a photo / chart / diagram: describe what it shows and the key information.

PALM CHARACTER SET CONSTRAINT — this is critical:
- ASCII or Mac Roman / Palm Roman characters ONLY.
- NO emoji at all.
- NO arrow symbols (use -> instead).
- NO bullet glyphs.
- ASCII quotes only.

Content rules:
- Plain text. Short paragraphs. No markdown.
- First line: a tight headline (max 80 chars).
- Body: 100-400 words depending on content density.
- Match the source language.
- If image is blank / unrecognisable, output exactly: (no readable content)`;

interface RecordRow {
  id: string;
  user_id: string;
  body: string | null;
  metadata: {
    upload_path?: string;
    upload_filename?: string;
    upload_mimetype?: string;
    upload_size?: number;
    palm_category_name?: string;
  } | null;
}

interface WebhookBody {
  type: string;
  table: string;
  record: RecordRow;
}

// @ts-expect-error Deno API
Deno.serve(async (req: Request) => {
  let body: WebhookBody;
  try {
    body = (await req.json()) as WebhookBody;
  } catch {
    return jsonResp({ error: 'bad json' }, 400);
  }
  if (body.type !== 'INSERT' || body.table !== 'records') {
    return jsonResp({ skipped: true });
  }
  const r = body.record;
  if (!r.metadata?.upload_path) return jsonResp({ skipped: 'no-upload' });

  // Claim — race-safe against the sweeper
  const { data: claimed } = await supa
    .from('records')
    .update({ ai_status: 'processing' })
    .eq('id', r.id)
    .or('ai_status.is.null,ai_status.eq.pending')
    .select('id');
  if (!claimed || claimed.length === 0) {
    return jsonResp({ skipped: 'already-processing' });
  }

  // Resolve BYOK key
  const { data: settings } = await supa
    .from('user_settings')
    .select('api_mode, preferred_provider, openai_model, anthropic_model')
    .eq('user_id', r.user_id)
    .single();
  if (!settings) {
    await markError(r, 'no user_settings');
    return jsonResp({ error: 'no-settings' });
  }

  let apiKey = '';
  if (settings.api_mode === 'byok') {
    const { data: k } = await supa.rpc('read_user_api_key', {
      target_user: r.user_id,
      provider_name: settings.preferred_provider,
    });
    if (!k) {
      await markError(r, `no ${settings.preferred_provider} key in vault`);
      return jsonResp({ error: 'no-key' });
    }
    apiKey = String(k);
  } else {
    apiKey = settings.preferred_provider === 'openai' ? PLATFORM_OPENAI : PLATFORM_ANTHROPIC;
    if (!apiKey) {
      await markError(r, 'platform key not configured');
      return jsonResp({ error: 'no-platform-key' });
    }
  }

  const path = r.metadata.upload_path!;
  const filename = r.metadata.upload_filename ?? '(unnamed)';
  const mime = (r.metadata.upload_mimetype ?? '').toLowerCase();

  let summary: string;
  let model = '';
  let tokensIn = 0;
  let tokensOut = 0;

  try {
    if (mime.startsWith('image/')) {
      // Vision path
      const { data: signed, error: signErr } = await supa.storage
        .from('memo-uploads')
        .createSignedUrl(path, SIGNED_URL_TTL);
      if (signErr || !signed?.signedUrl) {
        throw new Error(`signed URL: ${signErr?.message ?? 'unknown'}`);
      }
      const result = settings.preferred_provider === 'openai'
        ? await callOpenAIVision(apiKey, settings.openai_model, signed.signedUrl)
        : await callAnthropicVision(apiKey, settings.anthropic_model, signed.signedUrl);
      summary = result.text;
      model = result.model;
      tokensIn = result.tokensIn;
      tokensOut = result.tokensOut;
    } else if (mime === 'application/pdf' || filename.toLowerCase().endsWith('.pdf')) {
      const { data: blob, error: dlErr } = await supa.storage.from('memo-uploads').download(path);
      if (dlErr || !blob) throw new Error(`download: ${dlErr?.message ?? 'unknown'}`);
      const buf = new Uint8Array(await blob.arrayBuffer());
      const pdfResult = await extractPdfText(buf);
      const text = Array.isArray(pdfResult.text) ? pdfResult.text.join('\n\n') : String(pdfResult.text ?? '');
      const trimmed = text.slice(0, MAX_TEXT_CHARS);
      if (!trimmed.trim()) {
        summary = '(no readable content)';
      } else {
        const result = settings.preferred_provider === 'openai'
          ? await callOpenAIText(apiKey, settings.openai_model, trimmed, filename)
          : await callAnthropicText(apiKey, settings.anthropic_model, trimmed, filename);
        summary = result.text;
        model = result.model;
        tokensIn = result.tokensIn;
        tokensOut = result.tokensOut;
      }
    } else if (
      mime === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' ||
      filename.toLowerCase().endsWith('.docx')
    ) {
      const { data: blob, error: dlErr } = await supa.storage.from('memo-uploads').download(path);
      if (dlErr || !blob) throw new Error(`download: ${dlErr?.message ?? 'unknown'}`);
      const buf = await blob.arrayBuffer();
      const docxResult = await mammoth.extractRawText({ arrayBuffer: buf });
      const text = (docxResult.value as string).slice(0, MAX_TEXT_CHARS);
      if (!text.trim()) {
        summary = '(no readable content)';
      } else {
        const result = settings.preferred_provider === 'openai'
          ? await callOpenAIText(apiKey, settings.openai_model, text, filename)
          : await callAnthropicText(apiKey, settings.anthropic_model, text, filename);
        summary = result.text;
        model = result.model;
        tokensIn = result.tokensIn;
        tokensOut = result.tokensOut;
      }
    } else {
      throw new Error(`unsupported file type: ${mime || filename}`);
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    await markError(r, msg);
    return jsonResp({ error: msg });
  }

  const newBody = `${filename}\n\n${summary}`;
  await supa
    .from('records')
    .update({
      body: newBody,
      ai_status: 'done',
      ai_model: model,
      ai_tokens_in: tokensIn,
      ai_tokens_out: tokensOut,
      updated_at: new Date().toISOString(),
      metadata: {
        ...(r.metadata ?? {}),
        upload_processed: true,
        upload_summary: summary,
      },
    })
    .eq('id', r.id);

  await supa.from('ai_usage').insert({
    user_id: r.user_id,
    api_mode: settings.api_mode,
    provider: settings.preferred_provider,
    model,
    tokens_in: tokensIn,
    tokens_out: tokensOut,
    cost_credits: 0,
  });

  return jsonResp({ ok: true, len: summary.length });
});

async function markError(r: RecordRow, msg: string): Promise<void> {
  await supa
    .from('records')
    .update({
      ai_status: 'error',
      ai_error: msg,
      metadata: { ...(r.metadata ?? {}), upload_processed: true },
    })
    .eq('id', r.id);
}

function jsonResp(b: unknown, status: number = 200): Response {
  return new Response(JSON.stringify(b), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

interface AIResult {
  text: string;
  model: string;
  tokensIn: number;
  tokensOut: number;
}

async function callOpenAIVision(
  apiKey: string,
  model: string,
  imageUrl: string,
): Promise<AIResult> {
  const resp = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: model || 'gpt-4o-mini',
      messages: [
        { role: 'system', content: SYSTEM_PROMPT_IMAGE },
        {
          role: 'user',
          content: [
            { type: 'text', text: 'Read / describe the image, then summarise for the Palm:' },
            { type: 'image_url', image_url: { url: imageUrl, detail: 'high' } },
          ],
        },
      ],
      max_completion_tokens: 2048,
    }),
  });
  if (!resp.ok) throw new Error(`openai vision ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  return {
    text: (j.choices?.[0]?.message?.content ?? '').trim(),
    model: j.model ?? model,
    tokensIn: j.usage?.prompt_tokens ?? 0,
    tokensOut: j.usage?.completion_tokens ?? 0,
  };
}

async function callAnthropicVision(
  apiKey: string,
  model: string,
  imageUrl: string,
): Promise<AIResult> {
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
      system: SYSTEM_PROMPT_IMAGE,
      messages: [
        {
          role: 'user',
          content: [
            { type: 'image', source: { type: 'url', url: imageUrl } },
            { type: 'text', text: 'Read / describe the image, then summarise.' },
          ],
        },
      ],
    }),
  });
  if (!resp.ok) throw new Error(`anthropic vision ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  let text = '';
  for (const block of j.content ?? []) {
    if (block.type === 'text') text += block.text;
  }
  return {
    text: text.trim(),
    model: j.model ?? model,
    tokensIn: j.usage?.input_tokens ?? 0,
    tokensOut: j.usage?.output_tokens ?? 0,
  };
}

async function callOpenAIText(
  apiKey: string,
  model: string,
  text: string,
  filename: string,
): Promise<AIResult> {
  const resp = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: model || 'gpt-4o-mini',
      messages: [
        { role: 'system', content: SYSTEM_PROMPT_TEXT },
        {
          role: 'user',
          content: `Document: ${filename}\n\n${text}`,
        },
      ],
      max_completion_tokens: 2048,
    }),
  });
  if (!resp.ok) throw new Error(`openai text ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  return {
    text: (j.choices?.[0]?.message?.content ?? '').trim(),
    model: j.model ?? model,
    tokensIn: j.usage?.prompt_tokens ?? 0,
    tokensOut: j.usage?.completion_tokens ?? 0,
  };
}

async function callAnthropicText(
  apiKey: string,
  model: string,
  text: string,
  filename: string,
): Promise<AIResult> {
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
          text: SYSTEM_PROMPT_TEXT,
          cache_control: { type: 'ephemeral' },
        },
      ],
      messages: [
        { role: 'user', content: `Document: ${filename}\n\n${text}` },
      ],
    }),
  });
  if (!resp.ok) throw new Error(`anthropic text ${resp.status}: ${await resp.text()}`);
  const j = await resp.json();
  let out = '';
  for (const block of j.content ?? []) {
    if (block.type === 'text') out += block.text;
  }
  return {
    text: out.trim(),
    model: j.model ?? model,
    tokensIn: j.usage?.input_tokens ?? 0,
    tokensOut: j.usage?.output_tokens ?? 0,
  };
}

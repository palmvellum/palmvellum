/**
 * airwallex-webhook — Supabase Edge Function (Deno).
 *
 * Airwallex calls this on payment events. We verify the HMAC signature,
 * and on a succeeded payment_intent credit the user's balance via the
 * idempotent apply_topup RPC (keyed on the intent id, so a replayed
 * webhook is a no-op). This is the ONLY place a top-up adds credit.
 */

// @ts-expect-error Deno runtime
import { createClient } from "jsr:@supabase/supabase-js@2";
import { awxVerifyWebhook } from "../_shared/airwallex.ts";

// @ts-expect-error Deno globals
const env = (k: string): string => Deno.env.get(k) ?? "";
const supa = createClient(env("SUPABASE_URL"), env("SUPABASE_SERVICE_ROLE_KEY"), {
  auth: { persistSession: false },
});

// @ts-expect-error Deno serve
Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response("POST only", { status: 405 });

  const raw = await req.text();
  const ok = await awxVerifyWebhook(
    raw,
    req.headers.get("x-signature"),
    req.headers.get("x-timestamp"),
  );
  if (!ok) return new Response("bad signature", { status: 401 });

  let evt: { name?: string; data?: { object?: Record<string, unknown> } };
  try {
    evt = JSON.parse(raw);
  } catch {
    return new Response("bad json", { status: 400 });
  }

  // Only act on a successful payment. Other events are acknowledged 200
  // so Airwallex stops retrying.
  const name = evt.name ?? "";
  const obj = evt.data?.object ?? {};
  const succeeded = name === "payment_intent.succeeded" ||
    (name.startsWith("payment_intent") && obj.status === "SUCCEEDED");
  if (!succeeded) return new Response("ignored", { status: 200 });

  const intentId = String(obj.id ?? "");
  if (!intentId) return new Response("no intent id", { status: 200 });

  // Look up the pending intent we recorded at creation — this binds the
  // payment to a user and the exact amount we expected.
  const { data: row, error } = await supa
    .from("payment_intents")
    .select("user_id, amount_micro_usd, status")
    .eq("id", intentId)
    .maybeSingle();
  if (error || !row) return new Response("unknown intent", { status: 200 });

  const { error: rpcErr } = await supa.rpc("apply_topup", {
    p_user: row.user_id,
    p_amount_micro: row.amount_micro_usd,
    p_intent_id: intentId,
  });
  if (rpcErr) {
    console.error("apply_topup failed", rpcErr);
    return new Response("credit failed", { status: 500 }); // let Airwallex retry
  }
  return new Response("ok", { status: 200 });
});

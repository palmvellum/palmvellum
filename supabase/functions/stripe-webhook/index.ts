/**
 * stripe-webhook — Supabase Edge Function (Deno).
 *
 * Stripe calls this on Checkout events. We verify the signature, and on a
 * paid PalmVellum top-up session credit the user's balance via the
 * idempotent apply_topup RPC (keyed on the session id, so a replayed event
 * is a no-op). This is the ONLY place a top-up adds credit.
 *
 * The Stripe account is shared with other TAT Living products, so every
 * Checkout event on the account lands here too: anything without
 * metadata.pv_topup === "1" (or without a payment_intents row we created)
 * is acknowledged and ignored.
 *
 * Deploy with --no-verify-jwt (Stripe sends no Supabase JWT).
 */

// @ts-expect-error Deno runtime
import { createClient } from "jsr:@supabase/supabase-js@2";
import { stripeVerifyWebhook } from "../_shared/stripe.ts";

// @ts-expect-error Deno globals
const env = (k: string): string => Deno.env.get(k) ?? "";
const supa = createClient(env("SUPABASE_URL"), env("SUPABASE_SERVICE_ROLE_KEY"), {
  auth: { persistSession: false },
});

// @ts-expect-error Deno serve
Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response("POST only", { status: 405 });

  const raw = await req.text();
  if (!(await stripeVerifyWebhook(raw, req.headers.get("stripe-signature")))) {
    return new Response("bad signature", { status: 401 });
  }

  let evt: { type?: string; data?: { object?: Record<string, unknown> } };
  try {
    evt = JSON.parse(raw);
  } catch {
    return new Response("bad json", { status: 400 });
  }

  const type = evt.type ?? "";
  const obj = evt.data?.object ?? {};
  const meta = (obj.metadata ?? {}) as Record<string, string>;
  if (meta.pv_topup !== "1") return new Response("not a palmvellum topup", { status: 200 });

  const sessionId = String(obj.id ?? "");
  if (!sessionId) return new Response("no session id", { status: 200 });

  // An abandoned session: mark our pending row failed so it never credits.
  if (type === "checkout.session.expired") {
    await supa.from("payment_intents").update({ status: "failed", updated_at: new Date().toISOString() })
      .eq("id", sessionId).eq("status", "pending");
    return new Response("expired", { status: 200 });
  }

  // Only act when the money actually arrived. Other events are acknowledged
  // 200 so Stripe stops retrying.
  const paidEvent = type === "checkout.session.completed" || type === "checkout.session.async_payment_succeeded";
  if (!paidEvent) return new Response("ignored", { status: 200 });
  if (String(obj.payment_status ?? "").toLowerCase() !== "paid") return new Response("not paid yet", { status: 200 });

  // Look up the pending session we recorded at creation — this binds the
  // payment to a user and the exact amount we expected.
  const { data: row, error } = await supa
    .from("payment_intents")
    .select("user_id, amount_micro_usd, status")
    .eq("id", sessionId)
    .maybeSingle();
  if (error || !row) return new Response("unknown session", { status: 200 });

  const { error: rpcErr } = await supa.rpc("apply_topup", {
    p_user: row.user_id,
    p_amount_micro: row.amount_micro_usd,
    p_intent_id: sessionId,
  });
  if (rpcErr) {
    console.error("apply_topup failed", rpcErr);
    return new Response("credit failed", { status: 500 }); // let Stripe retry
  }
  return new Response("ok", { status: 200 });
});

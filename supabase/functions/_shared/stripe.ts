// Minimal Stripe client for credit top-ups (replaces _shared/airwallex.ts —
// the Airwallex account closed 2026-09-16). Reads all credentials from env;
// nothing is hard-coded.
//
// Env (Supabase Function secrets):
//   STRIPE_SECRET_KEY       server-side secret key (sk_live_… / sk_test_…)
//   STRIPE_WEBHOOK_SECRET   signing secret of the stripe-webhook endpoint (whsec_…)

const STRIPE_API = "https://api.stripe.com/v1";

function secretKey(): string {
  const k = Deno.env.get("STRIPE_SECRET_KEY");
  if (!k) throw new Error("Stripe not configured (STRIPE_SECRET_KEY)");
  return k;
}

/** Flatten a nested object into Stripe's form-encoded `a[b][c]=v` style. */
function encodeForm(obj: Record<string, unknown>, prefix = "", out = new URLSearchParams()): URLSearchParams {
  for (const [k, v] of Object.entries(obj)) {
    if (v === undefined || v === null) continue;
    const key = prefix ? `${prefix}[${k}]` : k;
    if (Array.isArray(v)) {
      v.forEach((item, i) => {
        if (typeof item === "object" && item !== null) encodeForm(item as Record<string, unknown>, `${key}[${i}]`, out);
        else out.append(`${key}[${i}]`, String(item));
      });
    } else if (typeof v === "object") {
      encodeForm(v as Record<string, unknown>, key, out);
    } else {
      out.append(key, String(v));
    }
  }
  return out;
}

export interface StripeSession {
  id: string;
  url: string;
}

/**
 * Create a hosted Checkout Session for a USD top-up. The session id (cs_…)
 * is our payment_intents row id and the webhook's idempotency key. Metadata
 * `pv_topup=1` lets the webhook (which sees every event on the shared Stripe
 * account) tell a PalmVellum top-up from other products' sessions.
 */
export async function stripeCreateTopupSession(input: {
  amountUsd: number;
  userId: string;
  email?: string | null;
  successUrl: string;
  cancelUrl: string;
}): Promise<StripeSession> {
  const cents = Math.round(input.amountUsd * 100);
  const body = encodeForm({
    mode: "payment",
    client_reference_id: input.userId,
    customer_email: input.email || undefined,
    success_url: input.successUrl,
    cancel_url: input.cancelUrl,
    line_items: [{
      quantity: 1,
      price_data: {
        currency: "usd",
        unit_amount: cents,
        product_data: {
          name: `PalmVellum credits (US$${input.amountUsd.toFixed(2)})`,
          description: "Pay-as-you-go AI credits for PalmVellum. 1 credit = US$0.01.",
        },
      },
    }],
    metadata: { pv_topup: "1", user_id: input.userId, amount_usd: input.amountUsd.toFixed(2) },
    payment_intent_data: {
      description: "PalmVellum credits",
      metadata: { reference: "palmvellum-credits", brand: "palmvellum", user_id: input.userId },
    },
  });
  const r = await fetch(`${STRIPE_API}/checkout/sessions`, {
    method: "POST",
    headers: { Authorization: `Bearer ${secretKey()}`, "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  const j = await r.json();
  if (!r.ok) throw new Error(`stripe checkout: HTTP ${r.status} ${j?.error?.message ?? ""}`);
  return { id: j.id as string, url: j.url as string };
}

/**
 * Verify a Stripe webhook signature. Header `stripe-signature` is
 * `t=<unix>,v1=<hex>[,v1=<hex>…]`; the signed payload is `${t}.${rawBody}`
 * HMAC-SHA256'd with the endpoint's signing secret. Rejects payloads older
 * than 5 minutes (replay guard). Returns true if valid.
 */
export async function stripeVerifyWebhook(rawBody: string, header: string | null): Promise<boolean> {
  const secret = Deno.env.get("STRIPE_WEBHOOK_SECRET");
  if (!secret || !header) return false;
  const parts: Record<string, string[]> = {};
  for (const kv of header.split(",")) {
    const i = kv.indexOf("=");
    if (i < 0) continue;
    const k = kv.slice(0, i).trim();
    (parts[k] ??= []).push(kv.slice(i + 1).trim());
  }
  const ts = parts.t?.[0];
  const sigs = parts.v1 ?? [];
  if (!ts || !sigs.length) return false;
  if (Math.abs(Date.now() / 1000 - Number(ts)) > 5 * 60) return false;
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`${ts}.${rawBody}`));
  const hex = [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, "0")).join("");
  for (const sig of sigs) {
    if (hex.length !== sig.length) continue;
    let diff = 0;
    for (let i = 0; i < hex.length; i++) diff |= hex.charCodeAt(i) ^ sig.charCodeAt(i);
    if (diff === 0) return true;
  }
  return false;
}

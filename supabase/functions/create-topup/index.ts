/**
 * create-topup — Supabase Edge Function (Deno).
 *
 * Called by the PWA (with the user's JWT) to start a credit top-up.
 * Creates a Stripe Checkout Session for `amount_usd` (>= MIN_TOPUP_USD),
 * records a pending payment_intents row keyed on the session id, and
 * returns the hosted checkout URL for the browser to navigate to. The
 * balance is only credited later by stripe-webhook on payment success —
 * never here.
 *
 * Body: { amount_usd: number, return_base?: string }
 *   return_base = absolute URL of the settings page the customer should
 *   come back to (Stripe appends nothing; we add #topup=ok / #topup=fail).
 *   Only https://tatliving.dev/… and localhost origins are accepted.
 */

// @ts-expect-error Deno runtime
import { createClient } from "jsr:@supabase/supabase-js@2";
import { MIN_TOPUP_USD, usdToMicro } from "../_shared/pricing.ts";
import { stripeCreateTopupSession } from "../_shared/stripe.ts";

// @ts-expect-error Deno globals
const env = (k: string): string => Deno.env.get(k) ?? "";
const SUPABASE_URL = env("SUPABASE_URL");
const SERVICE_KEY = env("SUPABASE_SERVICE_ROLE_KEY");
const ANON_KEY = env("SUPABASE_ANON_KEY");

const DEFAULT_RETURN = "https://tatliving.dev/palmvellum/app/settings";
const ALLOWED_RETURN = [/^https:\/\/tatliving\.dev\//, /^http:\/\/localhost(:\d+)?\//, /^http:\/\/127\.0\.0\.1(:\d+)?\//];

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json" },
  });
}

// @ts-expect-error Deno serve
Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: cors });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  // Identify the caller from their JWT.
  const authHeader = req.headers.get("Authorization") ?? "";
  const userClient = createClient(SUPABASE_URL, ANON_KEY, {
    global: { headers: { Authorization: authHeader } },
    auth: { persistSession: false },
  });
  const { data: userData, error: userErr } = await userClient.auth.getUser();
  if (userErr || !userData?.user) return json({ error: "unauthorized" }, 401);
  const userId = userData.user.id;
  const email = userData.user.email ?? null;

  let amountUsd = 0;
  let returnBase = DEFAULT_RETURN;
  try {
    const body = await req.json();
    amountUsd = Number(body.amount_usd);
    const rb = String(body.return_base ?? "");
    if (rb && ALLOWED_RETURN.some((re) => re.test(rb))) returnBase = rb.split("#")[0];
  } catch {
    return json({ error: "bad body" }, 400);
  }
  if (!Number.isFinite(amountUsd) || amountUsd < MIN_TOPUP_USD) {
    return json({ error: `minimum top-up is $${MIN_TOPUP_USD}` }, 400);
  }
  amountUsd = Math.round(amountUsd * 100) / 100;

  try {
    // Return to a hash, not a query string: a query on the boot URL stalls
    // Supabase's detectSessionInUrl and leaves the app stuck on the loading
    // screen. A hash is invisible to it.
    const session = await stripeCreateTopupSession({
      amountUsd,
      userId,
      email,
      successUrl: `${returnBase}#topup=ok`,
      cancelUrl: `${returnBase}#topup=fail`,
    });

    const supa = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });
    const { error: insErr } = await supa.from("payment_intents").insert({
      id: session.id,
      user_id: userId,
      amount_micro_usd: usdToMicro(amountUsd),
      currency: "USD",
      status: "pending",
    });
    if (insErr) return json({ error: `record intent: ${insErr.message}` }, 500);

    return json({
      session_id: session.id,
      checkout_url: session.url,
      amount_usd: amountUsd,
      provider: "stripe",
    });
  } catch (e) {
    return json({ error: String(e) }, 502);
  }
});

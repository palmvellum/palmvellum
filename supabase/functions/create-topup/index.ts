/**
 * create-topup — Supabase Edge Function (Deno).
 *
 * Called by the PWA (with the user's JWT) to start a credit top-up.
 * Creates an Airwallex PaymentIntent for `amount_usd` (>= MIN_TOPUP_USD),
 * records a pending payment_intents row, and returns the client_secret so
 * the browser can confirm the card. The balance is only credited later by
 * the airwallex-webhook on payment success — never here.
 */

// @ts-expect-error Deno runtime
import { createClient } from "jsr:@supabase/supabase-js@2";
import { MIN_TOPUP_USD, usdToMicro } from "../_shared/pricing.ts";
import { awxBaseUrl, awxCreateIntent, awxLogin } from "../_shared/airwallex.ts";

// @ts-expect-error Deno globals
const env = (k: string): string => Deno.env.get(k) ?? "";
const SUPABASE_URL = env("SUPABASE_URL");
const SERVICE_KEY = env("SUPABASE_SERVICE_ROLE_KEY");
const ANON_KEY = env("SUPABASE_ANON_KEY");

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

  let amountUsd = 0;
  try {
    amountUsd = Number((await req.json()).amount_usd);
  } catch {
    return json({ error: "bad body" }, 400);
  }
  if (!Number.isFinite(amountUsd) || amountUsd < MIN_TOPUP_USD) {
    return json({ error: `minimum top-up is $${MIN_TOPUP_USD}` }, 400);
  }

  try {
    const token = await awxLogin();
    const requestId = crypto.randomUUID();
    const intent = await awxCreateIntent(token, amountUsd, requestId, userId);

    const supa = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });
    const { error: insErr } = await supa.from("payment_intents").insert({
      id: intent.id,
      user_id: userId,
      amount_micro_usd: usdToMicro(amountUsd),
      currency: "USD",
      status: "pending",
    });
    if (insErr) return json({ error: `record intent: ${insErr.message}` }, 500);

    return json({
      intent_id: intent.id,
      client_secret: intent.client_secret,
      amount_usd: amountUsd,
      env: awxBaseUrl().includes("api-demo") ? "demo" : "live",
    });
  } catch (e) {
    return json({ error: String(e) }, 502);
  }
});

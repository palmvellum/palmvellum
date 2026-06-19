// Minimal Airwallex client for top-ups. Reads all credentials from env —
// nothing is hard-coded. Defaults to the DEMO environment; set
// AIRWALLEX_BASE_URL=https://api.airwallex.com to go live.
//
// Env:
//   AIRWALLEX_BASE_URL       (default https://api-demo.airwallex.com)
//   AIRWALLEX_API_KEY        (server-side only)
//   AIRWALLEX_CLIENT_ID
//   AIRWALLEX_WEBHOOK_SECRET (for verifying webhook signatures)

export function awxBaseUrl(): string {
  return Deno.env.get("AIRWALLEX_BASE_URL") ?? "https://api-demo.airwallex.com";
}

/** Exchange API key + client id for a short-lived bearer token. */
export async function awxLogin(): Promise<string> {
  const apiKey = Deno.env.get("AIRWALLEX_API_KEY");
  const clientId = Deno.env.get("AIRWALLEX_CLIENT_ID");
  if (!apiKey || !clientId) throw new Error("Airwallex creds not configured");
  const r = await fetch(`${awxBaseUrl()}/api/v1/authentication/login`, {
    method: "POST",
    headers: { "x-api-key": apiKey, "x-client-id": clientId, "Content-Type": "application/json" },
  });
  if (!r.ok) throw new Error(`awx login: HTTP ${r.status} ${await r.text()}`);
  const j = await r.json();
  return j.token as string;
}

export interface AwxIntent {
  id: string;
  client_secret: string;
}

/**
 * Create a PaymentIntent for a USD top-up. amountUsd is a decimal (e.g.
 * 10.00). requestId makes creation idempotent; merchantOrderId carries our
 * own payment_intents row id back through the webhook.
 */
export async function awxCreateIntent(
  token: string,
  amountUsd: number,
  requestId: string,
  merchantOrderId: string,
): Promise<AwxIntent> {
  const r = await fetch(`${awxBaseUrl()}/api/v1/pa/payment_intents/create`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      request_id: requestId,
      amount: Number(amountUsd.toFixed(2)),
      currency: "USD",
      merchant_order_id: merchantOrderId,
      descriptor: "PalmVellum credits",
    }),
  });
  if (!r.ok) throw new Error(`awx create intent: HTTP ${r.status} ${await r.text()}`);
  const j = await r.json();
  return { id: j.id as string, client_secret: j.client_secret as string };
}

/**
 * Verify an Airwallex webhook signature. Airwallex signs
 * HMAC-SHA256( timestamp + rawBody ) with the webhook secret, sent as the
 * `x-signature` header (hex) with `x-timestamp`. Returns true if valid.
 */
export async function awxVerifyWebhook(
  rawBody: string,
  signature: string | null,
  timestamp: string | null,
): Promise<boolean> {
  const secret = Deno.env.get("AIRWALLEX_WEBHOOK_SECRET");
  if (!secret || !signature || !timestamp) return false;
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(timestamp + rawBody));
  const hex = [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, "0")).join("");
  // constant-time-ish compare
  if (hex.length !== signature.length) return false;
  let diff = 0;
  for (let i = 0; i < hex.length; i++) diff |= hex.charCodeAt(i) ^ signature.charCodeAt(i);
  return diff === 0;
}

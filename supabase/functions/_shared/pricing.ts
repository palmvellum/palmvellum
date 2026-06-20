// Pricing for platform (pay-as-you-go) AI usage.
//
// We bill the user the underlying OpenAI token cost plus a fixed retail
// markup. Balances are integer micro-USD (1 USD = 1_000_000) to avoid float
// drift.

/**
 * Retail markup over raw token cost. The multiplier is a commercial value and
 * is NOT stored in the repo — it is read from the PRICE_MARKUP function secret
 * at runtime. costMicroUsd() fails closed if it is unset/invalid, so a call is
 * never billed at the wrong rate.
 */
export const MARKUP = Number(Deno.env.get("PRICE_MARKUP"));

/** OpenAI list prices in USD per 1,000,000 tokens (input / output).
 *  These are OpenAI's own published list prices (public information). */
const OPENAI_USD_PER_1M: Record<string, { in: number; out: number }> = {
  "gpt-4o-mini": { in: 0.15, out: 0.60 },
  "gpt-4o": { in: 2.50, out: 10.00 },
  "gpt-4.1": { in: 2.00, out: 8.00 },
  "gpt-4.1-mini": { in: 0.40, out: 1.60 },
  "gpt-4.1-nano": { in: 0.10, out: 0.40 },
  "o4-mini": { in: 1.10, out: 4.40 },
};

// Unknown models fall back to gpt-4o-mini (the project default). Logged
// by the caller so the table can be kept current.
const FALLBACK = OPENAI_USD_PER_1M["gpt-4o-mini"];

export function isKnownModel(model: string): boolean {
  return model in OPENAI_USD_PER_1M;
}

/**
 * Cost charged to the user for one call, in micro-USD (already including the
 * markup). tokensIn/Out are exact counts from the OpenAI response's `usage`.
 * Because list prices are per-1M tokens, micro-USD before markup is simply
 * tokens × pricePer1M:
 *   micro_usd = round( (tin*in + tout*out) * MARKUP )
 * Throws if PRICE_MARKUP is not configured (fail closed — never bill wrong).
 */
export function costMicroUsd(model: string, tokensIn: number, tokensOut: number): number {
  if (!Number.isFinite(MARKUP) || MARKUP <= 0) {
    throw new Error("PRICE_MARKUP function secret is not configured");
  }
  const p = OPENAI_USD_PER_1M[model] ?? FALLBACK;
  const raw = tokensIn * p.in + tokensOut * p.out; // = micro-USD before markup
  return Math.round(raw * MARKUP);
}

/** Helpers for the $-facing UI / minimums. */
export const USD = 1_000_000; // micro-USD per USD
export const MIN_TOPUP_USD = 10; // US$10 minimum top-up
export function usdToMicro(usd: number): number {
  return Math.round(usd * USD);
}
export function microToUsd(micro: number): number {
  return micro / USD;
}

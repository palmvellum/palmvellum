// Billing helpers for platform (pay-as-you-go) AI usage. Used by the AI
// workers to gate calls on balance and to deduct after each call.
//
// All money is integer micro-USD. The Postgres RPCs (apply_topup,
// charge_usage) are the only writers of a balance and are idempotent.

import { costMicroUsd, isKnownModel } from "./pricing.ts";

export interface BillingSettings {
  api_mode: "byok" | "platform";
  balance_micro_usd: number;
  low_balance_threshold_micro: number;
}

/** Platform users must have a positive balance before a metered call. */
export function canSpend(s: BillingSettings): boolean {
  return s.api_mode !== "platform" || s.balance_micro_usd > 0;
}

export function isLowBalance(s: BillingSettings): boolean {
  return s.api_mode === "platform" && s.balance_micro_usd <= s.low_balance_threshold_micro;
}

/**
 * Deduct the cost of one completed AI call. No-op for BYOK. `ref` must be
 * a stable unique id for the call (e.g. the ai_usage row id) so a retry
 * does not double-charge. Returns the cost charged in micro-USD.
 *
 * `supa` is a service-role Supabase client (only it may call charge_usage).
 */
export async function chargeUsage(
  supa: { rpc: (fn: string, args: Record<string, unknown>) => Promise<{ error: unknown }> },
  apiMode: string,
  userId: string,
  model: string,
  tokensIn: number,
  tokensOut: number,
  ref: string,
): Promise<number> {
  if (apiMode !== "platform") return 0;
  const micro = costMicroUsd(model, tokensIn, tokensOut);
  if (!isKnownModel(model)) {
    console.warn(`billing: unknown model "${model}" — charged at fallback rate`);
  }
  if (micro <= 0) return 0;
  const { error } = await supa.rpc("charge_usage", {
    p_user: userId,
    p_amount_micro: micro,
    p_ref: ref,
  });
  if (error) {
    console.error("billing: charge_usage failed", error);
  }
  return micro;
}

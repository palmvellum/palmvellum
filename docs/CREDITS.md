# Platform credits (pay-as-you-go AI)

Users top up a USD balance via Stripe Checkout and spend it on platform AI calls.
(Airwallex was the original processor; its account closed 2026-09-16 and the
flow was ported to Stripe — same ledger, same RPCs, only the hosted checkout
and the webhook changed.)
Each call is billed at the underlying token cost plus a fixed retail markup.
The markup multiplier is a commercial value kept out of the repo — it is read
from the `PRICE_MARKUP` function secret at runtime (see Secrets below).
Balances are integer **micro-USD** (1 USD = 1,000,000) — no floats. The UI
shows the balance as **credits** where **1 credit = US$0.01** (US$10 = 1,000
credits).

- BYOK users are unaffected (`api_mode='byok'`, `cost_micro_usd=0`).
- Platform users (`api_mode='platform'`) need `balance_micro_usd > 0`; the AI
  worker refuses metered calls when out of credit.
- Minimum top-up: **$10**. v1 is **manual top-up + low-balance reminder**
  (no auto-charge — that needs a saved-card consent, deferred to v2).

## Pieces

| Piece | Where |
|---|---|
| Balance, ledger, RPCs (`apply_topup`, `charge_usage`) | `supabase/migrations/20260619120000_v07_credits.sql` |
| Pricing table + markup (markup from `PRICE_MARKUP` secret) | `supabase/functions/_shared/pricing.ts` (unit-tested) |
| Per-call metering | `supabase/functions/_shared/billing.ts` → wired in `ai-agent` |
| Start a top-up | `supabase/functions/create-topup` (auth'd; creates a Stripe Checkout Session, returns its URL) |
| Credit on payment | `supabase/functions/stripe-webhook` (verifies Stripe signature, idempotent on the session id) |
| Buy UI + balance | PWA `/settings` (credits section) |

## ⚠️ Secrets — never commit; never paste in chat

Set these as **Supabase Function secrets** (and rotate any key ever exposed):

```sh
supabase secrets set \
  PRICE_MARKUP=...                          # retail markup multiplier (commercial — keep out of git) \
  PLATFORM_OPENAI_API_KEY=sk-...           # the platform's OpenAI key (rotated) \
  STRIPE_SECRET_KEY=sk_...                  # Stripe secret key (shared TAT Living Stripe account) \
  STRIPE_WEBHOOK_SECRET=whsec_...           # signing secret of the stripe-webhook endpoint
# SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY / SUPABASE_ANON_KEY are provided by the platform.
```

## Deploy

```sh
supabase link --project-ref jrkwncplngmznfzzqwee
supabase db push                                   # applies the v07 migration
supabase functions deploy create-topup
supabase functions deploy stripe-webhook --no-verify-jwt      # called by Stripe, not a user
supabase functions deploy ai-agent                 # now meters platform usage
```

In the Stripe dashboard → Developers → Webhooks, add the endpoint
`https://jrkwncplngmznfzzqwee.supabase.co/functions/v1/stripe-webhook`,
subscribe to `checkout.session.completed`, `checkout.session.async_payment_succeeded`
and `checkout.session.expired`, and copy the signing secret into
`STRIPE_WEBHOOK_SECRET`. The Stripe account is shared with other TAT Living
products, so the webhook ignores any session without `metadata.pv_topup=1`.

## Test

1. Use a Stripe **test** key (`sk_test_…`) + a test-mode webhook endpoint for a
   dry run, or go straight to a real US$10 top-up on the live key.
2. In the PWA, set your account to platform mode and buy $10 of credits; you
   land on Stripe Checkout and come back to `/settings#topup=ok`.
3. Confirm: the webhook fires → `credit_ledger` gets a `topup` row (ref =
   the `cs_…` session id) → `user_settings.balance_micro_usd` increases by
   10,000,000.
4. Trigger an `(AI)` memo. Confirm `ai_usage.cost_micro_usd > 0`, a `usage`
   ledger row appears, and the balance drops by the configured markup.
5. Spend to zero → the next `(AI)` call is refused with "insufficient credit".

## Pricing maintenance

`_shared/pricing.ts` carries OpenAI list prices per model. Update it when
OpenAI changes prices; unknown models fall back to the gpt-4o-mini rate and log
a warning (so usage is never free, but keep the table current).

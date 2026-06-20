# Platform credits (pay-as-you-go AI)

Users top up a USD balance via Airwallex and spend it on platform AI calls.
Each call is billed at the underlying **OpenAI cost × 15** (retail markup — the
raw token cost is tiny, so credits are priced as a product, not cost-plus).
Balances are integer **micro-USD** (1 USD = 1,000,000) — no floats. The UI
shows the balance as **credits** where **1 credit = US$0.01** (US$10 = 1,000
credits ≈ ~3,000 typical AI Date Book records).

- BYOK users are unaffected (`api_mode='byok'`, `cost_micro_usd=0`).
- Platform users (`api_mode='platform'`) need `balance_micro_usd > 0`; the AI
  worker refuses metered calls when out of credit.
- Minimum top-up: **$10**. v1 is **manual top-up + low-balance reminder**
  (no auto-charge — that needs a saved-card consent, deferred to v2).

## Pieces

| Piece | Where |
|---|---|
| Balance, ledger, RPCs (`apply_topup`, `charge_usage`) | `supabase/migrations/20260619120000_v07_credits.sql` |
| Pricing table + markup | `supabase/functions/_shared/pricing.ts` (unit-tested) |
| Per-call metering | `supabase/functions/_shared/billing.ts` → wired in `ai-agent` |
| Start a top-up | `supabase/functions/create-topup` (auth'd; creates Airwallex intent) |
| Credit on payment | `supabase/functions/airwallex-webhook` (verifies signature, idempotent) |
| Buy UI + balance | PWA `/settings` (credits section) |

## ⚠️ Secrets — never commit; never paste in chat

Set these as **Supabase Function secrets** (and rotate any key ever exposed):

```sh
supabase secrets set \
  PLATFORM_OPENAI_API_KEY=sk-...           # the platform's OpenAI key (rotated) \
  AIRWALLEX_API_KEY=...                     # Airwallex DEMO key first \
  AIRWALLEX_CLIENT_ID=... \
  AIRWALLEX_WEBHOOK_SECRET=... \
  AIRWALLEX_BASE_URL=https://api-demo.airwallex.com   # DEMO; live = https://api.airwallex.com
# SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY / SUPABASE_ANON_KEY are provided by the platform.
```

## Deploy

```sh
supabase link --project-ref jrkwncplngmznfzzqwee
supabase db push                                   # applies the v07 migration
supabase functions deploy create-topup
supabase functions deploy airwallex-webhook --no-verify-jwt   # called by Airwallex, not a user
supabase functions deploy ai-agent                 # now meters platform usage
```

In the Airwallex **Demo** dashboard → Webhooks, add the endpoint
`https://jrkwncplngmznfzzqwee.supabase.co/functions/v1/airwallex-webhook`,
subscribe to `payment_intent.succeeded`, and copy the signing secret into
`AIRWALLEX_WEBHOOK_SECRET`.

## Test (DEMO — no real money)

1. Set `AIRWALLEX_BASE_URL` to the demo host and use **demo** API creds.
2. In the PWA, set your account to platform mode and buy $10 of credits using
   an Airwallex [test card](https://www.airwallex.com/docs/payments__test-card-numbers).
3. Confirm: the webhook fires → `credit_ledger` gets a `topup` row →
   `user_settings.balance_micro_usd` increases by 10,000,000.
4. Trigger an `(AI)` memo. Confirm `ai_usage.cost_micro_usd > 0`, a `usage`
   ledger row appears, and the balance drops by ≈ OpenAI cost × 15.
5. Spend to zero → the next `(AI)` call is refused with "insufficient credit".

## Go live

Only after the DEMO flow is verified: rotate to **live** Airwallex creds +
`AIRWALLEX_BASE_URL=https://api.airwallex.com`, re-point the webhook to the
live dashboard, set the live (rotated) `PLATFORM_OPENAI_API_KEY`, and redeploy.

## Pricing maintenance

`_shared/pricing.ts` carries OpenAI list prices per model. Update it when
OpenAI changes prices; unknown models fall back to the gpt-4o-mini rate and log
a warning (so usage is never free, but keep the table current).

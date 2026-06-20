-- v0.7 — Platform credits (pay-as-you-go AI via Airwallex top-ups).
--
-- Money model: balances are stored as INTEGER **micro-USD** (1 USD =
-- 1,000,000) to avoid floating-point drift. A top-up adds micro-USD; an
-- AI call deducts the token cost plus a configured retail markup (the
-- multiplier lives in the PRICE_MARKUP function secret, not the repo). Every
-- change is an immutable credit_ledger row; the balance lives on user_settings.
--
-- Idempotency is the spine of a billing system:
--   * apply_topup keys on the Airwallex payment-intent id (a webhook may
--     fire more than once).
--   * charge_usage keys on a caller-supplied ref (the ai_usage row id).
-- Both are SECURITY DEFINER and only callable by service_role (the Edge
-- Functions); clients can never move their own balance.

-- ── balance + auto-topup prefs on user_settings ──────────────────
ALTER TABLE public.user_settings
    ADD COLUMN IF NOT EXISTS balance_micro_usd            BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS low_balance_threshold_micro  BIGINT  NOT NULL DEFAULT 2000000,  -- $2
    ADD COLUMN IF NOT EXISTS low_balance_notified_at      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS auto_topup_enabled           BOOLEAN NOT NULL DEFAULT FALSE;     -- reserved (v2 MIT)

-- ai_usage gains a precise micro-USD cost alongside the legacy
-- cost_credits column (kept for back-compat; new code writes both).
ALTER TABLE public.ai_usage
    ADD COLUMN IF NOT EXISTS cost_micro_usd BIGINT NOT NULL DEFAULT 0;

-- ── credit_ledger — append-only money journal ────────────────────
CREATE TABLE IF NOT EXISTS public.credit_ledger (
    id               TEXT        PRIMARY KEY,           -- ULID
    user_id          UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    kind             TEXT        NOT NULL CHECK (kind IN ('topup','usage','refund','adjust')),
    amount_micro_usd BIGINT      NOT NULL,              -- signed: +topup / -usage
    balance_after    BIGINT      NOT NULL,
    ref              TEXT,                              -- airwallex intent id / ai_usage id
    idempotency_key  TEXT        NOT NULL UNIQUE,
    note             TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ledger_user_time ON public.credit_ledger (user_id, created_at DESC);

-- ── payment_intents — maps Airwallex intents back to a user ──────
CREATE TABLE IF NOT EXISTS public.payment_intents (
    id            TEXT        PRIMARY KEY,              -- Airwallex payment_intent id
    user_id       UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    amount_micro_usd BIGINT   NOT NULL,
    currency      TEXT        NOT NULL DEFAULT 'USD',
    status        TEXT        NOT NULL DEFAULT 'pending', -- pending|succeeded|failed
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_intents_user ON public.payment_intents (user_id, created_at DESC);

ALTER TABLE public.credit_ledger    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.payment_intents  ENABLE ROW LEVEL SECURITY;

-- Owners read their own ledger / intents; only service_role writes.
CREATE POLICY ledger_owner_select  ON public.credit_ledger
    FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY intents_owner_select ON public.payment_intents
    FOR SELECT USING (auth.uid() = user_id);

-- ── apply_topup — credit a balance from a paid intent (idempotent) ─
CREATE OR REPLACE FUNCTION public.apply_topup(
    p_user UUID, p_amount_micro BIGINT, p_intent_id TEXT
) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE new_balance BIGINT;
BEGIN
    IF p_amount_micro <= 0 THEN
        RAISE EXCEPTION 'topup amount must be positive';
    END IF;
    -- Idempotent on the intent id: a replayed webhook is a no-op that
    -- returns the current balance.
    IF EXISTS (SELECT 1 FROM public.credit_ledger WHERE idempotency_key = 'topup:' || p_intent_id) THEN
        SELECT balance_micro_usd INTO new_balance FROM public.user_settings WHERE user_id = p_user;
        RETURN new_balance;
    END IF;

    UPDATE public.user_settings
       SET balance_micro_usd = balance_micro_usd + p_amount_micro,
           low_balance_notified_at = NULL
     WHERE user_id = p_user
     RETURNING balance_micro_usd INTO new_balance;

    INSERT INTO public.credit_ledger (id, user_id, kind, amount_micro_usd, balance_after, ref, idempotency_key)
    VALUES (encode(extensions.gen_random_bytes(16),'hex'), p_user, 'topup', p_amount_micro, new_balance,
            p_intent_id, 'topup:' || p_intent_id);

    UPDATE public.payment_intents SET status='succeeded', updated_at=NOW() WHERE id = p_intent_id;
    RETURN new_balance;
END;
$$;

-- ── charge_usage — deduct for one AI call (idempotent on ref) ─────
-- Allows the balance to go (slightly) negative on the final call rather
-- than rejecting mid-response; the caller gates new calls on balance > 0.
CREATE OR REPLACE FUNCTION public.charge_usage(
    p_user UUID, p_amount_micro BIGINT, p_ref TEXT
) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE new_balance BIGINT;
BEGIN
    IF p_amount_micro < 0 THEN
        RAISE EXCEPTION 'usage amount must be non-negative';
    END IF;
    IF EXISTS (SELECT 1 FROM public.credit_ledger WHERE idempotency_key = 'usage:' || p_ref) THEN
        SELECT balance_micro_usd INTO new_balance FROM public.user_settings WHERE user_id = p_user;
        RETURN new_balance;
    END IF;

    UPDATE public.user_settings
       SET balance_micro_usd = balance_micro_usd - p_amount_micro
     WHERE user_id = p_user
     RETURNING balance_micro_usd INTO new_balance;

    INSERT INTO public.credit_ledger (id, user_id, kind, amount_micro_usd, balance_after, ref, idempotency_key)
    VALUES (encode(extensions.gen_random_bytes(16),'hex'), p_user, 'usage', -p_amount_micro, new_balance,
            p_ref, 'usage:' || p_ref);
    RETURN new_balance;
END;
$$;

REVOKE ALL ON FUNCTION public.apply_topup(UUID, BIGINT, TEXT)  FROM PUBLIC;
REVOKE ALL ON FUNCTION public.charge_usage(UUID, BIGINT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.apply_topup(UUID, BIGINT, TEXT)  TO service_role;
GRANT EXECUTE ON FUNCTION public.charge_usage(UUID, BIGINT, TEXT) TO service_role;

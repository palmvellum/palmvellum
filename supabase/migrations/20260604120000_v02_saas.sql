-- v0.2 — SaaS data model: waitlist, user_settings (with Supabase Vault
-- for BYOK keys), ai_usage tracking, palm enrollment token.
--
-- Key design decisions:
--
--   * Sign-up is gated by a waitlist. auth.users still gets created on
--     magic-link signup (we can't block at the auth layer cheaply), but
--     RLS on records / user_settings / etc. checks invitations.
--
--   * BYOK keys never sit in plaintext columns. We use Supabase Vault
--     (vault.secrets / vault.decrypted_secrets) so the master encryption
--     key is managed by Supabase, not by us. user_settings just holds
--     the secret UUID — knowing the UUID is not enough to read the key.
--
--   * ai_usage tracks every call so credit deduction (v0.3) can run
--     against historical data. BYOK calls write cost_credits=0.
--
--   * hotsync_token is a 32-byte random string the Mac daemon presents
--     to identify the owning user. We store its sha256 so a DB leak
--     does not reveal the live token (same pattern as API tokens
--     anywhere).

------------------------------------------------------------
-- Extensions
------------------------------------------------------------

CREATE EXTENSION IF NOT EXISTS "pgcrypto" WITH SCHEMA extensions;
-- Supabase Vault — should already be enabled on Pro projects. The
-- IF NOT EXISTS guard means re-running this migration is safe.
CREATE EXTENSION IF NOT EXISTS "supabase_vault";

------------------------------------------------------------
-- Waitlist
------------------------------------------------------------

CREATE TABLE public.waitlist (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    email        TEXT        NOT NULL UNIQUE,
    referrer     TEXT,                                 -- where they came from
    note         TEXT,                                 -- optional "tell us about yourself"
    enqueued_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    invited_at   TIMESTAMPTZ,                          -- set when we open the gate
    user_id      UUID        REFERENCES auth.users(id) ON DELETE SET NULL
);

CREATE INDEX idx_waitlist_invited ON public.waitlist (invited_at)
    WHERE invited_at IS NULL;

------------------------------------------------------------
-- User settings (per-user SaaS configuration)
------------------------------------------------------------

CREATE TABLE public.user_settings (
    user_id              UUID        PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,

    -- API selection
    api_mode             TEXT        NOT NULL DEFAULT 'byok'
                                      CHECK (api_mode IN ('byok', 'platform')),
    preferred_provider   TEXT        NOT NULL DEFAULT 'openai'
                                      CHECK (preferred_provider IN ('openai', 'anthropic')),

    -- BYOK — UUIDs pointing into vault.secrets. NULL = not set.
    openai_secret_id     UUID,
    openai_model         TEXT        NOT NULL DEFAULT 'gpt-4o-mini',
    anthropic_secret_id  UUID,
    anthropic_model      TEXT        NOT NULL DEFAULT 'claude-sonnet-4-5-20250929',

    -- Platform billing (used in v0.3)
    subscription_status  TEXT        NOT NULL DEFAULT 'free'
                                      CHECK (subscription_status IN ('free','active','past_due','cancelled')),
    credits_remaining    INTEGER     NOT NULL DEFAULT 0,
    credits_used_month   INTEGER     NOT NULL DEFAULT 0,
    credits_reset_at     TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '1 month'),

    -- Palm enrollment
    palm_enrolled        BOOLEAN     NOT NULL DEFAULT FALSE,
    hotsync_token_hash   TEXT,                          -- sha256 of the live token; raw never stored
    hotsync_token_issued_at TIMESTAMPTZ,
    palm_serial          TEXT,                          -- learned on first successful sync
    palm_model           TEXT,                          -- "Palm IIIe", "Sony PEG-SL10", etc.

    -- Gating
    invited              BOOLEAN     NOT NULL DEFAULT FALSE,

    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TRIGGER user_settings_touch
    BEFORE UPDATE ON public.user_settings
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();

-- Auto-create a settings row whenever an auth user is created.
-- The user starts uninvited; an admin (or future Airwallex webhook)
-- flips `invited = true` after they're off the waitlist.
CREATE OR REPLACE FUNCTION public.init_user_settings()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    INSERT INTO public.user_settings (user_id, invited)
    VALUES (
        NEW.id,
        -- Auto-invite if the email is already in waitlist with invited_at set
        EXISTS (
            SELECT 1 FROM public.waitlist
            WHERE email = NEW.email AND invited_at IS NOT NULL
        )
    )
    ON CONFLICT (user_id) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER auth_users_init_settings
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.init_user_settings();

------------------------------------------------------------
-- AI usage (token + credit accounting)
------------------------------------------------------------

CREATE TABLE public.ai_usage (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    record_id     TEXT         REFERENCES public.records(id) ON DELETE SET NULL,
    api_mode      TEXT         NOT NULL CHECK (api_mode IN ('byok','platform')),
    provider      TEXT         NOT NULL,
    model         TEXT,
    tokens_in     INTEGER      NOT NULL DEFAULT 0,
    tokens_out    INTEGER      NOT NULL DEFAULT 0,
    cost_credits  INTEGER      NOT NULL DEFAULT 0,    -- 0 for BYOK; positive for platform
    error         TEXT,
    happened_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ai_usage_user_time ON public.ai_usage (user_id, happened_at DESC);

------------------------------------------------------------
-- RLS
------------------------------------------------------------

ALTER TABLE public.waitlist      ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ai_usage      ENABLE ROW LEVEL SECURITY;

-- waitlist — anyone may insert (anonymous sign-up); only the owning
-- user (if their auth.users row exists) may select their own row.
CREATE POLICY waitlist_anon_insert ON public.waitlist
    FOR INSERT TO anon, authenticated WITH CHECK (true);
CREATE POLICY waitlist_owner_select ON public.waitlist
    FOR SELECT TO authenticated
    USING (
        user_id = auth.uid()
        OR email = (SELECT email FROM auth.users WHERE id = auth.uid())
    );

-- user_settings — owner-only.
CREATE POLICY settings_owner_select ON public.user_settings
    FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY settings_owner_update ON public.user_settings
    FOR UPDATE USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
-- Inserts come from the trigger (security definer); no direct insert
-- policy is granted to clients.

-- ai_usage — owner read-only; writes come from the worker as service role.
CREATE POLICY ai_usage_owner_select ON public.ai_usage
    FOR SELECT USING (auth.uid() = user_id);

------------------------------------------------------------
-- Vault helpers — store / read user API keys without ever
-- letting plaintext into a regular SQL column.
------------------------------------------------------------
--
-- store_user_api_key:   called by the PWA (as the authenticated user)
--                       to put a fresh key into Vault and link the
--                       returned UUID to user_settings.
--
-- read_user_api_key:    called by the worker (as service role) to
--                       decrypt the live key just before an Oracle
--                       call. Service-role-only via REVOKE.

CREATE OR REPLACE FUNCTION public.store_user_api_key(
    provider_name TEXT,
    plaintext     TEXT
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = vault, public
AS $$
DECLARE
    secret_id   UUID;
    secret_name TEXT;
BEGIN
    IF provider_name NOT IN ('openai','anthropic') THEN
        RAISE EXCEPTION 'invalid provider %', provider_name;
    END IF;
    IF plaintext IS NULL OR length(plaintext) < 20 THEN
        RAISE EXCEPTION 'api key looks invalid';
    END IF;

    secret_name := 'palmvellum:' || provider_name || ':' || auth.uid()::text;

    -- Wipe any existing secret for this user+provider so we don't
    -- accumulate stale rows.
    DELETE FROM vault.secrets WHERE name = secret_name;

    secret_id := vault.create_secret(plaintext, secret_name);

    -- Link in user_settings.
    IF provider_name = 'openai' THEN
        UPDATE public.user_settings
           SET openai_secret_id = secret_id
         WHERE user_id = auth.uid();
    ELSE
        UPDATE public.user_settings
           SET anthropic_secret_id = secret_id
         WHERE user_id = auth.uid();
    END IF;

    RETURN secret_id;
END;
$$;

REVOKE ALL ON FUNCTION public.store_user_api_key(TEXT, TEXT) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION public.store_user_api_key(TEXT, TEXT) TO authenticated;


-- Worker reads. service_role only. We pass the user_id explicitly
-- (rather than relying on auth.uid()) because the worker runs as the
-- service role on behalf of many users.
CREATE OR REPLACE FUNCTION public.read_user_api_key(
    target_user   UUID,
    provider_name TEXT
)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = vault, public
AS $$
DECLARE
    secret_uuid UUID;
    plaintext   TEXT;
BEGIN
    IF provider_name = 'openai' THEN
        SELECT openai_secret_id INTO secret_uuid
          FROM public.user_settings WHERE user_id = target_user;
    ELSIF provider_name = 'anthropic' THEN
        SELECT anthropic_secret_id INTO secret_uuid
          FROM public.user_settings WHERE user_id = target_user;
    ELSE
        RAISE EXCEPTION 'invalid provider %', provider_name;
    END IF;

    IF secret_uuid IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT decrypted_secret INTO plaintext
      FROM vault.decrypted_secrets
     WHERE id = secret_uuid;

    RETURN plaintext;
END;
$$;

REVOKE ALL ON FUNCTION public.read_user_api_key(UUID, TEXT) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION public.read_user_api_key(UUID, TEXT) TO service_role;

------------------------------------------------------------
-- Palm enrollment — issue a hotsync_token
------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.enroll_palm()
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    raw_token TEXT;
BEGIN
    -- 32 random bytes, hex-encoded → 64-char token
    raw_token := encode(extensions.gen_random_bytes(32), 'hex');

    UPDATE public.user_settings
       SET hotsync_token_hash      = encode(extensions.digest(raw_token, 'sha256'), 'hex'),
           hotsync_token_issued_at = NOW(),
           palm_enrolled           = TRUE
     WHERE user_id = auth.uid();

    -- Return the raw token to the caller exactly once. We never store it.
    RETURN raw_token;
END;
$$;

REVOKE ALL ON FUNCTION public.enroll_palm() FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION public.enroll_palm() TO authenticated;


-- Daemon-side validation. Called by the worker (service role) at
-- startup with the daemon's local PALMVELLUM_HOTSYNC_TOKEN. Returns
-- the user_id if the token is valid, NULL otherwise.
CREATE OR REPLACE FUNCTION public.resolve_hotsync_token(raw_token TEXT)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    target UUID;
BEGIN
    SELECT user_id INTO target
      FROM public.user_settings
     WHERE hotsync_token_hash = encode(extensions.digest(raw_token, 'sha256'), 'hex')
     LIMIT 1;
    RETURN target;
END;
$$;

REVOKE ALL ON FUNCTION public.resolve_hotsync_token(TEXT) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION public.resolve_hotsync_token(TEXT) TO service_role;

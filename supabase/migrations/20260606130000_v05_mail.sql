-- v0.5 Phase 5 — Mail: per-user website subscriptions delivered as
-- AI-summarized "morning paper" mail records.
--
-- Architecture:
--   1. mail_sources holds each user's subscriptions (url + local
--      fetch_time + timezone + enabled flag + bookkeeping).
--   2. pg_cron runs run_due_mail_fetches() every 5 minutes — it
--      scans for sources whose local "today" hasn't been fetched
--      yet AND whose fetch_time has passed in their timezone, and
--      POSTs to the fetch-mail-source Edge Function via pg_net.
--   3. The Edge Function downloads the page, calls the user's
--      preferred AI with a digest prompt, and writes a records row
--      type='mail' with subject + body. The PWA inbox subscribes to
--      Realtime changes on records and renders them.

-- ── Allow type='mail' on records ─────────────────────────────────
ALTER TABLE public.records DROP CONSTRAINT type_valid;
ALTER TABLE public.records ADD CONSTRAINT type_valid CHECK (
    type = ANY (ARRAY[
        'password'::text, 'totp'::text, 'ed25519_key'::text,
        'secp256k1_key'::text, 'seed'::text,
        'journal'::text, 'shard'::text,
        'thought'::text, 'todo'::text, 'aiquery'::text,
        'reading'::text, 'contact'::text, 'expense'::text,
        'sketch'::text, 'mail'::text
    ])
);

-- ── mail_sources table ───────────────────────────────────────────
CREATE TABLE public.mail_sources (
    id              text PRIMARY KEY,
    user_id         uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    name            text NOT NULL,
    url             text NOT NULL,
    fetch_time      time NOT NULL DEFAULT '07:00:00',
    timezone        text NOT NULL DEFAULT 'UTC',
    enabled         boolean NOT NULL DEFAULT true,
    last_fetched_at timestamptz,
    last_error      text,
    -- Optional per-source customisation of the digest prompt
    digest_hint     text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_mail_sources_user ON public.mail_sources(user_id, created_at DESC);
CREATE INDEX idx_mail_sources_due
    ON public.mail_sources(timezone, fetch_time)
    WHERE enabled = true;

ALTER TABLE public.mail_sources ENABLE ROW LEVEL SECURITY;

CREATE POLICY mail_sources_owner_select ON public.mail_sources
    FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY mail_sources_owner_insert ON public.mail_sources
    FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY mail_sources_owner_update ON public.mail_sources
    FOR UPDATE USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE POLICY mail_sources_owner_delete ON public.mail_sources
    FOR DELETE USING (auth.uid() = user_id);

CREATE TRIGGER mail_sources_touch_updated_at
    BEFORE UPDATE ON public.mail_sources
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

ALTER PUBLICATION supabase_realtime ADD TABLE public.mail_sources;

-- ── Sweeper function ─────────────────────────────────────────────
-- Scans for due sources and fires the Edge Function for each.
-- "Due" means: enabled, fetch_time has passed today in the source's
-- timezone, and we haven't already fetched today.
CREATE OR REPLACE FUNCTION public.run_due_mail_fetches()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, net, extensions
AS $$
DECLARE
    r       RECORD;
    fired   integer := 0;
BEGIN
    FOR r IN
        SELECT id
          FROM mail_sources
         WHERE enabled = true
           AND ((now() AT TIME ZONE timezone)::time) >= fetch_time
           AND (
               last_fetched_at IS NULL
               OR (last_fetched_at AT TIME ZONE timezone)::date
                    < (now() AT TIME ZONE timezone)::date
           )
    LOOP
        PERFORM net.http_post(
            url     := 'https://jrkwncplngmznfzzqwee.supabase.co/functions/v1/fetch-mail-source',
            headers := jsonb_build_object('Content-Type', 'application/json'),
            body    := jsonb_build_object('source_id', r.id)
        );
        fired := fired + 1;
    END LOOP;
    RETURN fired;
END;
$$;

-- ── pg_cron schedule ─────────────────────────────────────────────
-- Every 5 minutes. Supabase enables pg_cron on Pro; the cron schema
-- lives in the `cron` namespace.
CREATE EXTENSION IF NOT EXISTS pg_cron;

-- Unschedule any existing job with this name (idempotent re-runs).
SELECT cron.unschedule('mail-sweeper') WHERE EXISTS (
    SELECT 1 FROM cron.job WHERE jobname = 'mail-sweeper'
);

SELECT cron.schedule(
    'mail-sweeper',
    '*/5 * * * *',
    $$SELECT public.run_due_mail_fetches()$$
);

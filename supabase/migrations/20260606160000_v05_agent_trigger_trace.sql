-- v0.5 — instrument fire_agent_webhook for root-cause investigation.
--
-- Symptom: ~10% of (AI)-prefixed memo/todo INSERTs done via the PWA
-- Supabase JS client don't surface a webhook request through pg_net.
-- The trigger is enabled, conditions match, and identical psql
-- INSERTs always fire correctly — yet `net._http_response` shows no
-- entry, not even a timeout. We need to know whether (1) the trigger
-- function isn't running at all for those rows, (2) it runs but the
-- condition is silently false because of some metadata quirk, or (3)
-- net.http_post itself raises an exception that the trigger swallows
-- before the AFTER INSERT completes.
--
-- Adds a small log table and rewrites the trigger so every call —
-- regardless of whether it ends up posting — leaves a paper trail.

CREATE TABLE IF NOT EXISTS public.agent_webhook_log (
    id            bigserial PRIMARY KEY,
    record_id     text,
    record_type   text,
    body_excerpt  text,
    ai_status     text,
    metadata_keys text[],
    cond_type     boolean,
    cond_prefix   boolean,
    cond_status   boolean,
    cond_not_proc boolean,
    matched       boolean,
    fired         boolean,
    error_msg     text,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_webhook_log_created
    ON public.agent_webhook_log(created_at DESC);

CREATE OR REPLACE FUNCTION public.fire_agent_webhook()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, net, extensions
AS $$
DECLARE
    payload   jsonb;
    cond_type     boolean;
    cond_prefix   boolean;
    cond_status   boolean;
    cond_not_proc boolean;
    matched       boolean;
    fired         boolean := false;
    err           text;
BEGIN
    cond_type     := NEW.type IN ('thought', 'todo');
    cond_prefix   := NEW.body ~* '^\s*\(ai\)';
    cond_status   := (NEW.ai_status IS NULL OR NEW.ai_status = 'pending');
    cond_not_proc := (NEW.metadata->>'agent_processed') IS NULL;
    matched       := cond_type AND cond_prefix AND cond_status AND cond_not_proc;

    IF matched THEN
        payload := jsonb_build_object(
            'type',       'INSERT',
            'table',      'records',
            'schema',     'public',
            'record',     row_to_json(NEW)::jsonb,
            'old_record', NULL
        );
        BEGIN
            PERFORM net.http_post(
                url     := 'https://jrkwncplngmznfzzqwee.supabase.co/functions/v1/ai-agent',
                headers := jsonb_build_object('Content-Type', 'application/json'),
                body    := payload
            );
            fired := true;
        EXCEPTION WHEN OTHERS THEN
            err := SQLERRM;
        END;
    END IF;

    BEGIN
        INSERT INTO public.agent_webhook_log
            (record_id, record_type, body_excerpt, ai_status, metadata_keys,
             cond_type, cond_prefix, cond_status, cond_not_proc,
             matched, fired, error_msg)
        VALUES
            (NEW.id, NEW.type, LEFT(COALESCE(NEW.body, ''), 80),
             NEW.ai_status,
             CASE WHEN NEW.metadata IS NULL THEN ARRAY[]::text[]
                  ELSE ARRAY(SELECT jsonb_object_keys(NEW.metadata)) END,
             cond_type, cond_prefix, cond_status, cond_not_proc,
             matched, fired, err);
    EXCEPTION WHEN OTHERS THEN
        -- never let logging failure break the INSERT itself
        NULL;
    END;

    RETURN NEW;
END;
$$;

-- The trigger itself doesn't change.

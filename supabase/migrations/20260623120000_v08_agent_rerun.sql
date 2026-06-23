-- v0.8 — re-run the AI agent when an (AI) memo / task is EDITED.
--
-- The agent webhook (records_agent_webhook) fired AFTER INSERT only, so
-- editing an existing "(AI)" memo on the web never re-ran the agent — the
-- answer stayed stale. It now fires on INSERT OR UPDATE.
--
-- Loop-safety: the gate is unchanged — body starts with "(AI)",
-- ai_status = 'pending', and no metadata.agent_processed flag. The agent's
-- own write-backs set ai_status to 'processing' then 'done' and stamp
-- agent_processed = true, so they never re-fire. The web edit flow re-arms
-- a run by clearing agent_processed and setting ai_status = 'pending'
-- (see packages/pwa MemoPad saveEdit), which is the only path that passes
-- the gate on UPDATE.

CREATE OR REPLACE FUNCTION public.fire_agent_webhook()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, net, extensions
AS $$
DECLARE
    payload jsonb;
BEGIN
    IF NEW.type IN ('thought', 'todo')
       AND NEW.body ~* '^\s*\(ai\)'
       AND (NEW.ai_status IS NULL OR NEW.ai_status = 'pending')
       AND (NEW.metadata->>'agent_processed') IS NULL
    THEN
        payload := jsonb_build_object(
            'type',       'INSERT',
            'table',      'records',
            'schema',     'public',
            'record',     row_to_json(NEW)::jsonb,
            'old_record', NULL
        );
        PERFORM net.http_post(
            url     := 'https://jrkwncplngmznfzzqwee.supabase.co/functions/v1/ai-agent',
            headers := jsonb_build_object('Content-Type', 'application/json'),
            body    := payload
        );
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS records_agent_webhook ON public.records;
CREATE TRIGGER records_agent_webhook
    AFTER INSERT OR UPDATE ON public.records
    FOR EACH ROW EXECUTE FUNCTION public.fire_agent_webhook();

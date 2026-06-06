-- v0.5 Phase 4 — Agentic AI worker triggered by (AI) body prefix.
--
-- When a user creates a memo (records.type='thought') or a task
-- (records.type='todo') whose body starts with "(AI)", a webhook
-- fires the ai-agent Edge Function. The agent runs a tool-use loop:
--   create_event    → INSERT into events
--   create_todo     → INSERT into records type='todo'
--   create_memo     → INSERT into records type='thought'
--   finish(summary) → exit loop
-- After exit:
--   • Memo source: the summary is appended to the original memo body
--     (separator "\n— AI agent —\n") so the user sees it inline.
--   • Todo source: a new memo titled "AI Result: <prompt>" is created
--     with the summary as body; the original todo is marked
--     palm_completed=true in its metadata.
--
-- Existing aiquery / sketch / event_drafts pipelines are unchanged.

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
    AFTER INSERT ON public.records
    FOR EACH ROW EXECUTE FUNCTION public.fire_agent_webhook();

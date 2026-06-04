-- Hook the event_drafts INSERT trigger to the process-event-draft
-- Edge Function so the AI parser runs sub-second after a user
-- submits free-form text.
--
-- Mirrors the ai_queue webhook in migration 20260604140000 but uses
-- a different target URL and only fires for status='pending' rows
-- to avoid re-triggering when the function itself flips the row to
-- 'parsing'.

CREATE OR REPLACE FUNCTION public.fire_event_draft_webhook()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, net, extensions
AS $$
DECLARE
    payload jsonb;
BEGIN
    IF NEW.status = 'pending' THEN
        payload := jsonb_build_object(
            'type',       'INSERT',
            'table',      'event_drafts',
            'schema',     'public',
            'record',     row_to_json(NEW)::jsonb,
            'old_record', NULL
        );

        PERFORM net.http_post(
            url     := 'https://jrkwncplngmznfzzqwee.supabase.co/functions/v1/process-event-draft',
            headers := jsonb_build_object('Content-Type', 'application/json'),
            body    := payload
        );
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS event_drafts_webhook ON public.event_drafts;
CREATE TRIGGER event_drafts_webhook
    AFTER INSERT ON public.event_drafts
    FOR EACH ROW EXECUTE FUNCTION public.fire_event_draft_webhook();

-- Trigger the process-sketch Edge Function whenever a sketch
-- record lands with ai_status NULL or 'pending'. Mirrors the
-- ai_queue / event_drafts webhook pattern.

CREATE OR REPLACE FUNCTION public.fire_sketch_webhook()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, net, extensions
AS $$
DECLARE
    payload jsonb;
BEGIN
    -- Only fire for sketch inserts that haven't been processed yet
    IF NEW.type = 'sketch' AND (NEW.ai_status IS NULL OR NEW.ai_status = 'pending') THEN
        payload := jsonb_build_object(
            'type',       'INSERT',
            'table',      'records',
            'schema',     'public',
            'record',     row_to_json(NEW)::jsonb,
            'old_record', NULL
        );

        PERFORM net.http_post(
            url     := 'https://jrkwncplngmznfzzqwee.supabase.co/functions/v1/process-sketch',
            headers := jsonb_build_object('Content-Type', 'application/json'),
            body    := payload
        );
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS records_sketch_webhook ON public.records;
CREATE TRIGGER records_sketch_webhook
    AFTER INSERT ON public.records
    FOR EACH ROW EXECUTE FUNCTION public.fire_sketch_webhook();

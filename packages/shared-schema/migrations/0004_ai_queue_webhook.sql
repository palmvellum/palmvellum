-- Hook the ai_queue INSERT trigger to the process-ai-queue Edge Function
-- so a hosted worker drains the queue with sub-second latency.
--
-- This replaces the polling Mac daemon for production users — the
-- daemon now runs only when a developer is iterating locally.
--
-- We use pg_net for the HTTP call (no extra infra), and a plain
-- SECURITY DEFINER trigger function so the call fires under the
-- daemon's identity rather than the inserting user's, which keeps
-- the X-Webhook-Secret check stateless.

CREATE EXTENSION IF NOT EXISTS pg_net WITH SCHEMA extensions;

CREATE OR REPLACE FUNCTION public.fire_ai_queue_webhook()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, net, extensions
AS $$
DECLARE
    payload jsonb;
BEGIN
    payload := jsonb_build_object(
        'type',       'INSERT',
        'table',      'ai_queue',
        'schema',     'public',
        'record',     row_to_json(NEW)::jsonb,
        'old_record', NULL
    );

    -- pg_net exposes its callable functions in the `net` schema even
    -- though the extension is installed under `extensions`.
    PERFORM net.http_post(
        url     := 'https://jrkwncplngmznfzzqwee.supabase.co/functions/v1/process-ai-queue',
        headers := jsonb_build_object('Content-Type', 'application/json'),
        body    := payload
    );

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS ai_queue_webhook ON public.ai_queue;
CREATE TRIGGER ai_queue_webhook
    AFTER INSERT ON public.ai_queue
    FOR EACH ROW EXECUTE FUNCTION public.fire_ai_queue_webhook();

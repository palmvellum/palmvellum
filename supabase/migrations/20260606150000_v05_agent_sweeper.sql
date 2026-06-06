-- v0.5 Phase 4 follow-up — agent sweeper cron.
--
-- The records_agent_webhook trigger occasionally (~10% of the time
-- in observed manual testing) doesn't surface a request through
-- pg_net for rows created via the PWA Supabase JS client, even
-- though the trigger condition matches and the trigger is enabled.
-- Cause not yet pinned down — looks like an intermittent pg_net
-- DNS / socket / internal-queue issue. While we chase it, ship a
-- safety-net sweeper that retries any (AI)-prefixed memo/todo row
-- that's been stuck unprocessed for more than 30 seconds.
--
-- The sweeper is idempotent: ai-agent's claim filter is
-- `.or(ai_status.is.null, ai_status.eq.pending)`, so racing the
-- sweeper against the original webhook can't double-process — only
-- one claim wins.

CREATE OR REPLACE FUNCTION public.run_stuck_agent_retries()
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
        SELECT id, row_to_json(records.*)::jsonb AS row_json
          FROM records
         WHERE type IN ('thought', 'todo')
           AND body ~* '^\s*\(ai\)'
           AND (ai_status IS NULL OR ai_status = 'pending')
           AND (metadata->>'agent_processed') IS NULL
           AND deleted_at IS NULL
           AND created_at < now() - interval '30 seconds'
           AND created_at > now() - interval '24 hours'
    LOOP
        PERFORM net.http_post(
            url     := 'https://jrkwncplngmznfzzqwee.supabase.co/functions/v1/ai-agent',
            headers := jsonb_build_object('Content-Type', 'application/json'),
            body    := jsonb_build_object(
                'type',       'INSERT',
                'table',      'records',
                'schema',     'public',
                'record',     r.row_json,
                'old_record', NULL
            )
        );
        fired := fired + 1;
    END LOOP;
    RETURN fired;
END;
$$;

-- pg_cron is already enabled by the mail migration. Schedule the
-- sweeper every minute.
SELECT cron.unschedule('agent-sweeper') WHERE EXISTS (
    SELECT 1 FROM cron.job WHERE jobname = 'agent-sweeper'
);

SELECT cron.schedule(
    'agent-sweeper',
    '* * * * *',
    $$SELECT public.run_stuck_agent_retries()$$
);

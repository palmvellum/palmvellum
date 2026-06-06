-- v0.5 Phase 4.5 — Memo Pad uploads (PDF / DOCX / image → AI summary).
--
-- User uploads a file via the Memo Pad page. PWA:
--   1. Uploads the file bytes to `memo-uploads/<user_id>/<record_id>.<ext>`
--   2. INSERTs a records row type='thought' with metadata.upload_path,
--      ai_status='pending', body = "📎 <filename>\n\n⏳ Processing…"
--
-- The webhook below fires the summarize-upload Edge Function, which:
--   • downloads the file via service-role
--   • for images: calls user BYOK vision API with a summary prompt
--   • for PDF: extracts text with unpdf, summarizes via text LLM
--   • for DOCX: extracts text with mammoth, summarizes via text LLM
--   • writes the digest to records.body and flips ai_status='done'
--
-- The bucket is PRIVATE — content might be sensitive. RLS keyed on
-- the first folder segment = auth.uid().

INSERT INTO storage.buckets (id, name, public, file_size_limit)
VALUES ('memo-uploads', 'memo-uploads', false, 20971520)  -- 20 MB
ON CONFLICT (id) DO NOTHING;

DROP POLICY IF EXISTS "memo_uploads_owner_insert" ON storage.objects;
DROP POLICY IF EXISTS "memo_uploads_owner_select" ON storage.objects;
DROP POLICY IF EXISTS "memo_uploads_owner_delete" ON storage.objects;

CREATE POLICY "memo_uploads_owner_insert" ON storage.objects
    FOR INSERT TO authenticated
    WITH CHECK (
        bucket_id = 'memo-uploads'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );

CREATE POLICY "memo_uploads_owner_select" ON storage.objects
    FOR SELECT TO authenticated
    USING (
        bucket_id = 'memo-uploads'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );

CREATE POLICY "memo_uploads_owner_delete" ON storage.objects
    FOR DELETE TO authenticated
    USING (
        bucket_id = 'memo-uploads'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );

-- ── Webhook trigger ──────────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.fire_upload_webhook()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, net, extensions
AS $$
DECLARE
    payload jsonb;
BEGIN
    IF NEW.type = 'thought'
       AND NEW.metadata ? 'upload_path'
       AND (NEW.ai_status IS NULL OR NEW.ai_status = 'pending')
       AND (NEW.metadata->>'upload_processed') IS NULL
    THEN
        payload := jsonb_build_object(
            'type',       'INSERT',
            'table',      'records',
            'schema',     'public',
            'record',     row_to_json(NEW)::jsonb,
            'old_record', NULL
        );
        PERFORM net.http_post(
            url     := 'https://jrkwncplngmznfzzqwee.supabase.co/functions/v1/summarize-upload',
            headers := jsonb_build_object('Content-Type', 'application/json'),
            body    := payload
        );
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS records_upload_webhook ON public.records;
CREATE TRIGGER records_upload_webhook
    AFTER INSERT ON public.records
    FOR EACH ROW EXECUTE FUNCTION public.fire_upload_webhook();

-- ── Sweeper cron — retries stuck uploads ─────────────────────────
CREATE OR REPLACE FUNCTION public.run_stuck_upload_retries()
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
         WHERE type = 'thought'
           AND metadata ? 'upload_path'
           AND (ai_status IS NULL OR ai_status = 'pending')
           AND (metadata->>'upload_processed') IS NULL
           AND deleted_at IS NULL
           AND created_at < now() - interval '45 seconds'
           AND created_at > now() - interval '24 hours'
    LOOP
        PERFORM net.http_post(
            url     := 'https://jrkwncplngmznfzzqwee.supabase.co/functions/v1/summarize-upload',
            headers := jsonb_build_object('Content-Type', 'application/json'),
            body    := jsonb_build_object(
                'type', 'INSERT', 'table', 'records',
                'schema', 'public', 'record', r.row_json, 'old_record', NULL
            )
        );
        fired := fired + 1;
    END LOOP;
    RETURN fired;
END;
$$;

SELECT cron.unschedule('upload-sweeper') WHERE EXISTS (
    SELECT 1 FROM cron.job WHERE jobname = 'upload-sweeper'
);
SELECT cron.schedule(
    'upload-sweeper',
    '* * * * *',
    $$SELECT public.run_stuck_upload_retries()$$
);

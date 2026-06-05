-- v0.5 Phase 3 — Note Pad: sketch record type + Storage bucket + RLS.
--
-- Each Palm Note Pad scribble lands as records.type='sketch':
--   body         = AI-extracted text (filled in by process-sketch
--                  Edge Function via vision API)
--   ai_status    = pending → processing → done | error
--   ai_response  = unused (text goes to body so list views work)
--   metadata     = { image_path: "<user_id>/<record_id>.png",
--                    palm_title:  string,
--                    palm_modified_at: ISO string,
--                    palm_alarm_at: ISO string | null,
--                    palm_record_uid: hex string (when synced from Palm) }
--
-- The Storage bucket is public-read by ULID path: anyone with the
-- 26-char user_id ULID + 26-char record_id ULID can fetch (44+ bits
-- entropy makes guessing impractical), while RLS on storage.objects
-- still restricts UPLOAD / DELETE to the owner.

ALTER TABLE public.records DROP CONSTRAINT type_valid;
ALTER TABLE public.records ADD CONSTRAINT type_valid CHECK (
    type = ANY (ARRAY[
        'password'::text, 'totp'::text, 'ed25519_key'::text,
        'secp256k1_key'::text, 'seed'::text,
        'journal'::text, 'shard'::text,
        'thought'::text, 'todo'::text, 'aiquery'::text,
        'reading'::text, 'contact'::text, 'expense'::text,
        'sketch'::text
    ])
);

-- posture_type_matches: sketch is posture='open' (catches via ELSE).
-- The body for a sketch may be NULL until AI fills it in.
ALTER TABLE public.records DROP CONSTRAINT posture_type_matches;
ALTER TABLE public.records ADD CONSTRAINT posture_type_matches CHECK (
    CASE
        WHEN type = ANY (ARRAY[
            'password'::text, 'totp'::text, 'ed25519_key'::text,
            'secp256k1_key'::text, 'seed'::text]) THEN
                posture = 'vault'::text AND body IS NULL
        WHEN type = ANY (ARRAY['journal'::text, 'shard'::text]) THEN
                posture = 'sealed'::text AND body IS NOT NULL
        ELSE posture = 'open'::text
    END
);

-- Create the Storage bucket. Public read so direct image URLs work
-- without per-request signing, but uploads/deletes still go through
-- the owner-only RLS below.
INSERT INTO storage.buckets (id, name, public, file_size_limit)
VALUES ('notepad', 'notepad', true, 5242880)  -- 5 MB cap
ON CONFLICT (id) DO NOTHING;

-- Storage.objects policies: path must be "<auth.uid()>/<filename>"
-- so we use storage.foldername(name)[1] to extract the user folder.
DROP POLICY IF EXISTS "notepad_owner_insert" ON storage.objects;
DROP POLICY IF EXISTS "notepad_owner_select" ON storage.objects;
DROP POLICY IF EXISTS "notepad_owner_delete" ON storage.objects;

CREATE POLICY "notepad_owner_insert" ON storage.objects
    FOR INSERT TO authenticated
    WITH CHECK (
        bucket_id = 'notepad'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );

CREATE POLICY "notepad_owner_select" ON storage.objects
    FOR SELECT TO authenticated
    USING (
        bucket_id = 'notepad'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );

CREATE POLICY "notepad_owner_delete" ON storage.objects
    FOR DELETE TO authenticated
    USING (
        bucket_id = 'notepad'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );

-- ai_status enum already allows 'pending', 'processing', 'done',
-- 'error' from the v0.2 migration — no change needed there.

-- Realtime: records is already in supabase_realtime publication,
-- so sketch row inserts/updates surface to PWA subscribers.

-- Add 'sketch' to the records_enqueue_ai trigger so newly inserted
-- sketches get queued. Existing trigger only fires for type='aiquery';
-- sketches go via a dedicated webhook below (no ai_queue row needed
-- because vision processing is single-step).

-- Hotfix: enqueue_ai_request must be SECURITY DEFINER.
--
-- Symptom: inserting a record with type='aiquery' as an authenticated
-- user fails with
--
--   new row violates row-level security policy for table "ai_queue"
--
-- Cause: in migration 0001 the trigger function ran as the calling
-- user (default SECURITY INVOKER). ai_queue's RLS only grants
-- authenticated users SELECT, so the trigger's INSERT was rejected.
--
-- Fix: rewrite the function as SECURITY DEFINER. The function still
-- only inserts the record's own id and user_id, so the security
-- envelope is preserved — a user cannot enqueue work for another
-- user because the trigger is fired BY their own INSERT and copies
-- NEW.user_id which RLS on records already pins to auth.uid().

CREATE OR REPLACE FUNCTION public.enqueue_ai_request()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF NEW.type = 'aiquery' AND NEW.ai_status = 'pending' THEN
        INSERT INTO public.ai_queue (record_id, user_id)
        VALUES (NEW.id, NEW.user_id);
    END IF;
    RETURN NEW;
END;
$$;

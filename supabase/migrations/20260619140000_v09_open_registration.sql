-- v0.9 — Open registration.
--
-- Anyone can now sign up with an email code and use the app immediately,
-- rather than waiting on the invite/waitlist gate. New accounts are
-- created invited = TRUE (and default to platform credits via v08).
CREATE OR REPLACE FUNCTION public.init_user_settings()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    INSERT INTO public.user_settings (user_id, invited)
    VALUES (NEW.id, TRUE)
    ON CONFLICT (user_id) DO NOTHING;
    RETURN NEW;
END;
$$;

ALTER TABLE public.user_settings ALTER COLUMN invited SET DEFAULT TRUE;

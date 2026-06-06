-- iCal subscription feed support for Date Book.
--
-- Lets the PWA mint a per-user, opaque token (160 bits, hex) that is
-- pasted into Apple Calendar / Google Calendar / etc. as a subscription
-- URL. The Edge Function ical-feed resolves the token -> user_id via
-- the service-role-only resolve_ical_token() RPC and serves the user's
-- events as RFC 5545 VCALENDAR/VEVENT text. Apple Calendar respects
-- Cache-Control: public, max-age=3600 on the response, giving an
-- hourly refresh cadence without us running a cron.

ALTER TABLE public.user_settings
  ADD COLUMN IF NOT EXISTS ical_token TEXT UNIQUE;

CREATE OR REPLACE FUNCTION public.mint_ical_token()
RETURNS TEXT LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE tok TEXT;
BEGIN
    tok := encode(extensions.gen_random_bytes(20), 'hex');
    UPDATE public.user_settings SET ical_token = tok WHERE user_id = auth.uid();
    RETURN tok;
END;
$$;
REVOKE ALL ON FUNCTION public.mint_ical_token() FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION public.mint_ical_token() TO authenticated;

CREATE OR REPLACE FUNCTION public.revoke_ical_token()
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    UPDATE public.user_settings SET ical_token = NULL WHERE user_id = auth.uid();
END;
$$;
REVOKE ALL ON FUNCTION public.revoke_ical_token() FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION public.revoke_ical_token() TO authenticated;

CREATE OR REPLACE FUNCTION public.resolve_ical_token(tok TEXT)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE uid UUID;
BEGIN
    SELECT user_id INTO uid FROM public.user_settings WHERE ical_token = tok LIMIT 1;
    RETURN uid;
END;
$$;
REVOKE ALL ON FUNCTION public.resolve_ical_token(TEXT) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION public.resolve_ical_token(TEXT) TO service_role;

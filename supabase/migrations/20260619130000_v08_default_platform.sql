-- v0.8 — Default new accounts to platform credits (pay-as-you-go).
--
-- New sign-ups now start in 'platform' mode so they top up and use the
-- shared key, rather than needing their own. Existing users keep whatever
-- mode they're already on (BYOK users are not touched).
ALTER TABLE public.user_settings ALTER COLUMN api_mode SET DEFAULT 'platform';

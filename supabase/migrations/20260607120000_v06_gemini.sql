-- Add Gemini as a third BYOK provider alongside OpenAI and Anthropic.
--
-- 1. Relax preferred_provider CHECK to include 'gemini'.
-- 2. Add gemini_secret_id + gemini_model columns to user_settings.
-- 3. Extend store_user_api_key / read_user_api_key to handle the
--    new provider key alongside the existing two.
-- 4. Default Gemini model: gemini-2.5-flash (cheap, fast, vision).

ALTER TABLE public.user_settings DROP CONSTRAINT IF EXISTS user_settings_preferred_provider_check;
ALTER TABLE public.user_settings
  ADD CONSTRAINT user_settings_preferred_provider_check
  CHECK (preferred_provider IN ('openai','anthropic','gemini'));

ALTER TABLE public.user_settings
  ADD COLUMN IF NOT EXISTS gemini_secret_id UUID,
  ADD COLUMN IF NOT EXISTS gemini_model TEXT NOT NULL DEFAULT 'gemini-2.5-flash';

CREATE OR REPLACE FUNCTION public.store_user_api_key(
    provider_name TEXT,
    plaintext     TEXT
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = vault, public
AS $$
DECLARE
    secret_id   UUID;
    secret_name TEXT;
BEGIN
    IF provider_name NOT IN ('openai','anthropic','gemini') THEN
        RAISE EXCEPTION 'invalid provider %', provider_name;
    END IF;
    IF plaintext IS NULL OR length(plaintext) < 20 THEN
        RAISE EXCEPTION 'api key looks invalid';
    END IF;

    secret_name := 'palmvellum:' || provider_name || ':' || auth.uid()::text;
    DELETE FROM vault.secrets WHERE name = secret_name;
    secret_id := vault.create_secret(plaintext, secret_name);

    IF provider_name = 'openai' THEN
        UPDATE public.user_settings
           SET openai_secret_id = secret_id
         WHERE user_id = auth.uid();
    ELSIF provider_name = 'anthropic' THEN
        UPDATE public.user_settings
           SET anthropic_secret_id = secret_id
         WHERE user_id = auth.uid();
    ELSE
        UPDATE public.user_settings
           SET gemini_secret_id = secret_id
         WHERE user_id = auth.uid();
    END IF;

    RETURN secret_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.read_user_api_key(
    target_user   UUID,
    provider_name TEXT
)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = vault, public
AS $$
DECLARE
    secret_uuid UUID;
    plaintext   TEXT;
BEGIN
    IF provider_name = 'openai' THEN
        SELECT openai_secret_id INTO secret_uuid
          FROM public.user_settings WHERE user_id = target_user;
    ELSIF provider_name = 'anthropic' THEN
        SELECT anthropic_secret_id INTO secret_uuid
          FROM public.user_settings WHERE user_id = target_user;
    ELSIF provider_name = 'gemini' THEN
        SELECT gemini_secret_id INTO secret_uuid
          FROM public.user_settings WHERE user_id = target_user;
    ELSE
        RAISE EXCEPTION 'invalid provider %', provider_name;
    END IF;

    IF secret_uuid IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT decrypted_secret INTO plaintext
      FROM vault.decrypted_secrets
     WHERE id = secret_uuid;

    RETURN plaintext;
END;
$$;

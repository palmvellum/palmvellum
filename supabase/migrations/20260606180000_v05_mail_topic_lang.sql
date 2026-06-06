-- v0.5 Phase 5 follow-up — Mail per-source output language + topic
-- research mode.
--
-- 1. output_language: user-chosen target language for the digest
--    body, overriding the AI's default "match source language"
--    behaviour. NULL = auto (existing behaviour).
--
-- 2. source_type:
--      'url'    (existing) — fetch a specific page and digest it
--      'topic'  (new)      — AI uses its built-in web_search tool to
--                            research a free-form question / interest
--                            and produces a long-form article (5-10
--                            minute read) with cited reference URLs
--
-- When source_type='topic', `url` is unused and `topic` holds the
-- research query / interest description.

ALTER TABLE public.mail_sources
    ADD COLUMN output_language text DEFAULT NULL,
    ADD COLUMN source_type     text NOT NULL DEFAULT 'url',
    ADD COLUMN topic           text;

ALTER TABLE public.mail_sources
    ALTER COLUMN url DROP NOT NULL;

ALTER TABLE public.mail_sources
    ADD CONSTRAINT mail_sources_type_valid
        CHECK (source_type IN ('url', 'topic')),
    ADD CONSTRAINT mail_sources_type_data_match
        CHECK (
            (source_type = 'url'   AND url   IS NOT NULL AND url   <> '')
         OR (source_type = 'topic' AND topic IS NOT NULL AND topic <> '')
        );

-- Backfill: any existing rows are 'url' (default already does this).

-- v0.4 — Calendar foundation.
--
-- Two new tables plus a timezone column on user_settings.
--
--   events         the canonical scheduled-item store. Bi-directional
--                  sync target for Palm OS DatebookDB.pdb (task #26).
--   event_drafts   queue of free-form text the AI parses into one or
--                  more candidate events. The user accepts/rejects
--                  drafts before they materialise into events
--                  (task #25). Created here so the AI worker has
--                  a target to write to from day one.
--
-- We keep events independent of records — different shape, different
-- lifecycle, different sync conduit on the Palm side (DatebookDB vs
-- the open/sealed PDBs).

------------------------------------------------------------
-- User-level timezone (for AI parsing relative dates)
------------------------------------------------------------

ALTER TABLE public.user_settings
    ADD COLUMN IF NOT EXISTS timezone TEXT NOT NULL DEFAULT 'UTC';
COMMENT ON COLUMN public.user_settings.timezone IS
    'IANA timezone (e.g. Asia/Hong_Kong). Used by the calendar AI to
     resolve relative phrases like "next Friday" and to render times
     consistently across devices.';

------------------------------------------------------------
-- events
------------------------------------------------------------

CREATE TABLE public.events (
    id              TEXT        PRIMARY KEY,           -- ULID
    user_id         UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,

    title           TEXT        NOT NULL CHECK (length(title) BETWEEN 1 AND 256),
    start_at        TIMESTAMPTZ NOT NULL,
    end_at          TIMESTAMPTZ,                       -- NULL = open-ended / untimed
    all_day         BOOLEAN     NOT NULL DEFAULT FALSE,
    location        TEXT,
    notes           TEXT,

    alarm_minutes   INTEGER,                            -- minutes before start_at; NULL = no alarm
    repeat_rule     TEXT,                               -- iCalendar RRULE; NULL = single occurrence

    source          TEXT        NOT NULL DEFAULT 'web',
    device_id       TEXT,

    -- Provenance for the Palm sync direction
    palm_record_uid INTEGER,                            -- Palm internal record id when imported from DatebookDB

    -- Audit + soft delete
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT end_after_start CHECK (end_at IS NULL OR end_at >= start_at),
    CONSTRAINT alarm_nonneg    CHECK (alarm_minutes IS NULL OR alarm_minutes >= 0)
);

CREATE INDEX idx_events_user_start    ON public.events (user_id, start_at);
CREATE INDEX idx_events_user_updated  ON public.events (user_id, updated_at DESC);
CREATE INDEX idx_events_active        ON public.events (user_id, start_at) WHERE deleted_at IS NULL;

CREATE TRIGGER events_touch_updated_at
    BEFORE UPDATE ON public.events
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();

ALTER TABLE public.events ENABLE ROW LEVEL SECURITY;

CREATE POLICY events_owner_select ON public.events
    FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY events_owner_insert ON public.events
    FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY events_owner_update ON public.events
    FOR UPDATE USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE POLICY events_owner_delete ON public.events
    FOR DELETE USING (auth.uid() = user_id);

------------------------------------------------------------
-- event_drafts  (AI parser staging)
------------------------------------------------------------

CREATE TABLE public.event_drafts (
    id             TEXT        PRIMARY KEY,             -- ULID
    user_id        UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,

    raw_input      TEXT        NOT NULL CHECK (length(raw_input) BETWEEN 1 AND 8000),
    user_tz        TEXT        NOT NULL,                -- snapshot of user_settings.timezone at submission time
    parsed_events  JSONB       NOT NULL DEFAULT '[]'::jsonb,

    status         TEXT        NOT NULL DEFAULT 'pending'
                                CHECK (status IN ('pending','parsing','parsed','confirmed','rejected','error')),

    ai_provider    TEXT,
    ai_model       TEXT,
    ai_tokens_in   INTEGER,
    ai_tokens_out  INTEGER,
    ai_error       TEXT,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at   TIMESTAMPTZ,
    confirmed_at   TIMESTAMPTZ
);

CREATE INDEX idx_drafts_user_created ON public.event_drafts (user_id, created_at DESC);
CREATE INDEX idx_drafts_pending      ON public.event_drafts (user_id, status)
    WHERE status IN ('pending','parsed');

ALTER TABLE public.event_drafts ENABLE ROW LEVEL SECURITY;

CREATE POLICY drafts_owner_select ON public.event_drafts
    FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY drafts_owner_insert ON public.event_drafts
    FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY drafts_owner_update ON public.event_drafts
    FOR UPDATE USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE POLICY drafts_owner_delete ON public.event_drafts
    FOR DELETE USING (auth.uid() = user_id);

------------------------------------------------------------
-- Realtime — let the PWA stream live updates as the AI parses
------------------------------------------------------------

ALTER PUBLICATION supabase_realtime ADD TABLE public.event_drafts;
ALTER PUBLICATION supabase_realtime ADD TABLE public.events;

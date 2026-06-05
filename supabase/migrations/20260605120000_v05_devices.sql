-- v0.5 Phase 0 — multi-device architecture.
--
-- A user can own one or more Palms. Each Palm gets its own page in
-- the PWA showing the seven native apps (Date Book / To Do List /
-- Address / Memo Pad / Note Pad / Mail / Expense). Records are tied
-- to a specific device via `palm_device_id`, with `palm_record_uid`
-- carrying the 24-bit per-database PalmOS ID. Records originating
-- from the platform (PWA / AI worker output) leave palm_device_id
-- NULL until first push assigns one.

CREATE TABLE public.devices (
    id              text PRIMARY KEY,                                       -- ULID
    user_id         uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    name            text NOT NULL,                                          -- user-given, e.g. "Office IIIe"
    model           text NOT NULL,                                          -- e.g. "Palm IIIe", "Sony PEG-SL10"
    serial          text,                                                   -- HotSync user / serial number (optional)
    last_sync_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_devices_user ON public.devices(user_id, created_at DESC);

ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;

CREATE POLICY devices_owner_select ON public.devices
    FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY devices_owner_insert ON public.devices
    FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY devices_owner_update ON public.devices
    FOR UPDATE USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE POLICY devices_owner_delete ON public.devices
    FOR DELETE USING (auth.uid() = user_id);

CREATE TRIGGER devices_touch_updated_at
    BEFORE UPDATE ON public.devices
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- Records: link to a specific Palm and preserve its per-database UID.
-- Existing rows stay NULL — they originated from the PWA and haven't
-- been pushed to any device yet.
ALTER TABLE public.records
    ADD COLUMN palm_device_id   text REFERENCES public.devices(id) ON DELETE SET NULL,
    ADD COLUMN palm_record_uid  text;

CREATE INDEX idx_records_palm_device
    ON public.records(palm_device_id)
    WHERE palm_device_id IS NOT NULL;

-- (device, uid) within a single Palm DB type is unique — index keyed
-- on the legacy text device_id which already encodes the DB ("memo:"
-- / "todo:") plus the 24-bit hex UID, so a single uniqueness index
-- across (palm_device_id, device_id) catches duplicate pushes once
-- the daemon migrates to the new column scheme. Skipping a hard
-- UNIQUE constraint for now to keep the manual CLI working during
-- the transition.

-- Calendar events: same link.
ALTER TABLE public.events
    ADD COLUMN palm_device_id text REFERENCES public.devices(id) ON DELETE SET NULL;

CREATE INDEX idx_events_palm_device
    ON public.events(palm_device_id)
    WHERE palm_device_id IS NOT NULL;

-- Add devices to the Realtime publication so the PWA can subscribe.
ALTER PUBLICATION supabase_realtime ADD TABLE public.devices;

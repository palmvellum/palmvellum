-- v0.10 — Per-event timezone + all-day "wrong day in Apple Calendar" fix.
--
-- Two problems this migration addresses:
--
--   1. All-day events were stored at *local* midnight converted to a
--      UTC instant (e.g. Asia/Hong_Kong 2026-06-25 00:00 → stored as
--      2026-06-24T16:00:00Z). The iCal feed then read the *UTC* date
--      off that instant and emitted DTSTART;VALUE=DATE:20260624 — one
--      day early. We re-pin every existing all-day event to UTC
--      midnight of its intended local date so the date is now
--      timezone-independent (the app + feed read all-day dates via UTC
--      from here on).
--
--   2. Timed events had no record of *which* timezone the user's
--      wall-clock was anchored to. A new nullable `tz` column stores
--      the IANA zone (e.g. 'Asia/Hong_Kong') chosen in the Date Book
--      when the event was created. `start_at`/`end_at` remain true UTC
--      instants; `tz` only governs how the wall-clock is displayed.

------------------------------------------------------------
-- 1. Per-event timezone column
------------------------------------------------------------

ALTER TABLE public.events
    ADD COLUMN IF NOT EXISTS tz TEXT;
COMMENT ON COLUMN public.events.tz IS
    'IANA timezone the timed event''s wall-clock is anchored to
     (e.g. Asia/Hong_Kong). NULL = legacy/floating (render in the
     viewer''s zone). Ignored for all-day events, which are
     timezone-independent dates pinned to UTC midnight.';

------------------------------------------------------------
-- 2. Adopt Asia/Hong_Kong as the Date Book default zone
--    (the old 'UTC' default was never surfaced in any UI; it only
--    fed the AI relative-date parser).
------------------------------------------------------------

ALTER TABLE public.user_settings
    ALTER COLUMN timezone SET DEFAULT 'Asia/Hong_Kong';

------------------------------------------------------------
-- 3. Re-pin existing all-day events to UTC midnight of their
--    intended local date.
--
--    The intended local date = the wall-clock date the row currently
--    decodes to in the owner's Date Book zone. We resolve that zone
--    per user from user_settings.timezone, treating the never-surfaced
--    'UTC'/empty default as Asia/Hong_Kong (the zone the app shipped
--    with). DST-correct because the AT TIME ZONE conversion uses the
--    offset in force at each event's own instant.
------------------------------------------------------------

UPDATE public.events e
SET
    start_at = (date_trunc('day', e.start_at AT TIME ZONE z.zone)) AT TIME ZONE 'UTC',
    end_at   = CASE
                 WHEN e.end_at IS NULL THEN NULL
                 ELSE (date_trunc('day', e.end_at AT TIME ZONE z.zone)) AT TIME ZONE 'UTC'
               END
FROM (
    SELECT
        u.user_id,
        CASE
            WHEN COALESCE(NULLIF(us.timezone, ''), 'UTC') = 'UTC'
                THEN 'Asia/Hong_Kong'
            ELSE us.timezone
        END AS zone
    FROM (SELECT DISTINCT user_id FROM public.events WHERE all_day) u
    LEFT JOIN public.user_settings us ON us.user_id = u.user_id
) z
WHERE e.all_day
  AND e.user_id = z.user_id
  -- Idempotency guard: skip rows already pinned to UTC midnight, so
  -- re-running this migration can never re-break a corrected row.
  AND e.start_at <> (date_trunc('day', e.start_at AT TIME ZONE 'UTC')) AT TIME ZONE 'UTC';

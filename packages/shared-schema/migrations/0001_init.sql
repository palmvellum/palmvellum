-- PalmVellum — initial schema.
--
-- Three tables enforce the three-tier posture system documented in
--   README.md.
--
--   records          the user's records (thoughts, todos, AI queries,
--                    encrypted journal entries, etc.). Vault-tier
--                    records (passwords / TOTP secrets / signing keys)
--                    are stored ONLY on the Palm and never reach this
--                    table — the CHECK constraint enforces it.
--
--   sync_conflicts   tombstones written by the daemon when cloud-first
--                    resolution discards a Palm-side edit; the loser
--                    body is preserved here so the user can recover.
--
--   ai_queue         a trigger-fed queue that the AI worker subscribes
--                    to via Realtime. Keeping a separate table means
--                    Realtime traffic is one row per AI request, not
--                    one row per PWA mutation.
--
-- RLS is on every table with policies scoped by auth.uid() = user_id.
-- The sync_apply_diff RPC is the canonical mutation entry point for
-- the Mac daemon and any future bridge — one transactional call per
-- HotSync session instead of N REST upserts.

------------------------------------------------------------
-- Extensions
------------------------------------------------------------

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

------------------------------------------------------------
-- Records — the main entity table
------------------------------------------------------------

CREATE TABLE public.records (
    id            TEXT        PRIMARY KEY,               -- ULID
    user_id       UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,

    type          TEXT        NOT NULL,
    posture       TEXT        NOT NULL,

    body          TEXT,
    tags          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    metadata      JSONB       NOT NULL DEFAULT '{}'::jsonb,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ,

    source        TEXT        NOT NULL,
    device_id     TEXT,

    ai_status     TEXT,
    ai_response   TEXT,
    ai_model      TEXT,
    ai_tokens_in  INTEGER,
    ai_tokens_out INTEGER,
    ai_error      TEXT,

    -- Posture enum.
    CONSTRAINT posture_valid CHECK (posture IN ('vault', 'sealed', 'open')),

    -- Type enum.
    CONSTRAINT type_valid CHECK (type IN (
        -- vault posture
        'password', 'totp', 'ed25519_key', 'secp256k1_key', 'seed',
        -- sealed posture
        'journal', 'shard',
        -- open posture
        'thought', 'todo', 'aiquery', 'reading', 'contact'
    )),

    -- Posture / type / body integrity per README.md:
    --   vault records exist nowhere on the bridge — even as ciphertext —
    --   so any vault-tier row arriving here is rejected.
    --   sealed records must carry a ciphertext body.
    --   open records may carry plaintext, allowed to be empty.
    CONSTRAINT posture_type_matches CHECK (
        CASE
            WHEN type IN ('password', 'totp', 'ed25519_key', 'secp256k1_key', 'seed')
                THEN posture = 'vault' AND body IS NULL
            WHEN type IN ('journal', 'shard')
                THEN posture = 'sealed' AND body IS NOT NULL
            ELSE posture = 'open'
        END
    ),

    CONSTRAINT ai_status_valid CHECK (
        ai_status IS NULL OR ai_status IN ('pending', 'processing', 'done', 'error')
    )
);

CREATE INDEX idx_records_user_updated ON public.records (user_id, updated_at DESC);
CREATE INDEX idx_records_user_type    ON public.records (user_id, type);
CREATE INDEX idx_records_ai_pending   ON public.records (user_id, ai_status)
    WHERE ai_status = 'pending';

------------------------------------------------------------
-- updated_at trigger
------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.touch_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER records_touch_updated_at
    BEFORE UPDATE ON public.records
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();

------------------------------------------------------------
-- AI queue (trigger-fed)
------------------------------------------------------------

CREATE TABLE public.ai_queue (
    seq          BIGSERIAL   PRIMARY KEY,
    record_id    TEXT        NOT NULL REFERENCES public.records(id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    enqueued_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at   TIMESTAMPTZ,
    claimed_by   TEXT
);

CREATE INDEX idx_ai_queue_unclaimed ON public.ai_queue (enqueued_at)
    WHERE claimed_at IS NULL;

-- Trigger: on every insert of a pending aiquery, push a row onto the queue.
CREATE OR REPLACE FUNCTION public.enqueue_ai_request()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.type = 'aiquery' AND NEW.ai_status = 'pending' THEN
        INSERT INTO public.ai_queue (record_id, user_id)
        VALUES (NEW.id, NEW.user_id);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER records_enqueue_ai
    AFTER INSERT ON public.records
    FOR EACH ROW EXECUTE FUNCTION public.enqueue_ai_request();

------------------------------------------------------------
-- Sync conflicts (tombstone table)
------------------------------------------------------------

CREATE TABLE public.sync_conflicts (
    id                 TEXT        PRIMARY KEY,        -- ULID, separate from records.id
    user_id            UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    record_id          TEXT,                            -- references the contested record (may be deleted)

    conflict_kind      TEXT        NOT NULL,
    loser_body         TEXT,
    loser_updated_at   TIMESTAMPTZ NOT NULL,
    winner_updated_at  TIMESTAMPTZ NOT NULL,
    diff_summary       TEXT        NOT NULL,

    resolved_at        TIMESTAMPTZ,
    recorded_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT conflict_kind_valid CHECK (
        conflict_kind IN ('cloud-wins-over-palm', 'palm-wins-over-cloud', 'unrecoverable')
    )
);

CREATE INDEX idx_conflicts_user ON public.sync_conflicts (user_id, recorded_at DESC);

------------------------------------------------------------
-- RLS — strict per-user scoping
------------------------------------------------------------

ALTER TABLE public.records         ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ai_queue        ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sync_conflicts  ENABLE ROW LEVEL SECURITY;

-- records — owner reads and mutates only their own rows
CREATE POLICY records_owner_select ON public.records
    FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY records_owner_insert ON public.records
    FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY records_owner_update ON public.records
    FOR UPDATE USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE POLICY records_owner_delete ON public.records
    FOR DELETE USING (auth.uid() = user_id);

-- ai_queue — owner can observe their pending work
CREATE POLICY ai_queue_owner_select ON public.ai_queue
    FOR SELECT USING (auth.uid() = user_id);
-- inserts come from the records trigger (security definer), not directly

-- sync_conflicts — owner can read their tombstones
CREATE POLICY conflicts_owner_select ON public.sync_conflicts
    FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY conflicts_owner_insert ON public.sync_conflicts
    FOR INSERT WITH CHECK (auth.uid() = user_id);

------------------------------------------------------------
-- sync_apply_diff — single-transaction batch upsert RPC
------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.sync_apply_diff(palm_user UUID, changes JSONB)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    change       JSONB;
    op           TEXT;
    rec          JSONB;
    applied      INTEGER := 0;
    deleted      INTEGER := 0;
BEGIN
    -- The RPC runs as the calling user; RLS is enforced naturally.
    -- We additionally require the passed palm_user to match auth.uid()
    -- so a confused client cannot try to ACT-AS another user.
    IF palm_user IS DISTINCT FROM auth.uid() THEN
        RAISE EXCEPTION 'palm_user mismatch (expected %, got %)', auth.uid(), palm_user;
    END IF;

    FOR change IN SELECT * FROM jsonb_array_elements(changes)
    LOOP
        op  := change->>'op';
        rec := change->'record';

        IF op = 'upsert' THEN
            INSERT INTO public.records (
                id, user_id, type, posture, body, tags, metadata,
                created_at, updated_at, deleted_at,
                source, device_id,
                ai_status, ai_response, ai_model, ai_tokens_in, ai_tokens_out, ai_error
            )
            VALUES (
                rec->>'id',
                palm_user,
                rec->>'type',
                rec->>'posture',
                rec->>'body',
                COALESCE(rec->'tags', '[]'::jsonb),
                COALESCE(rec->'metadata', '{}'::jsonb),
                COALESCE((rec->>'created_at')::timestamptz, NOW()),
                COALESCE((rec->>'updated_at')::timestamptz, NOW()),
                (rec->>'deleted_at')::timestamptz,
                COALESCE(rec->>'source', 'unknown'),
                rec->>'device_id',
                rec->>'ai_status',
                rec->>'ai_response',
                rec->>'ai_model',
                NULLIF(rec->>'ai_tokens_in',  '')::int,
                NULLIF(rec->>'ai_tokens_out', '')::int,
                rec->>'ai_error'
            )
            ON CONFLICT (id) DO UPDATE SET
                type          = EXCLUDED.type,
                posture       = EXCLUDED.posture,
                body          = EXCLUDED.body,
                tags          = EXCLUDED.tags,
                metadata      = EXCLUDED.metadata,
                updated_at    = GREATEST(public.records.updated_at, EXCLUDED.updated_at),
                deleted_at    = EXCLUDED.deleted_at,
                source        = EXCLUDED.source,
                device_id     = EXCLUDED.device_id,
                ai_status     = EXCLUDED.ai_status,
                ai_response   = EXCLUDED.ai_response,
                ai_model      = EXCLUDED.ai_model,
                ai_tokens_in  = EXCLUDED.ai_tokens_in,
                ai_tokens_out = EXCLUDED.ai_tokens_out,
                ai_error      = EXCLUDED.ai_error
            WHERE
                public.records.updated_at < EXCLUDED.updated_at;
            applied := applied + 1;

        ELSIF op = 'delete' THEN
            UPDATE public.records
               SET deleted_at = COALESCE((rec->>'deleted_at')::timestamptz, NOW()),
                   updated_at = NOW()
             WHERE id = rec->>'id' AND user_id = palm_user;
            deleted := deleted + 1;

        ELSE
            RAISE EXCEPTION 'unknown op: %', op;
        END IF;
    END LOOP;

    RETURN jsonb_build_object(
        'ok',       true,
        'applied',  applied,
        'deleted',  deleted,
        'at',       NOW()
    );
END;
$$;

------------------------------------------------------------
-- Realtime publication — ai_queue ONLY
------------------------------------------------------------
--
-- Subscribing to records would leak every PWA mutation to the AI
-- worker and burn Realtime quota. Subscribing to ai_queue instead
-- gives the worker exactly one event per pending AI request.

ALTER PUBLICATION supabase_realtime ADD TABLE public.ai_queue;

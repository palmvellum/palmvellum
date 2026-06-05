-- v0.5 Phase 1.5 — allow records.type = 'expense'.
--
-- The Palm Expense app stores entries with rich structured fields
-- (amount, currency, vendor, payment type, date, attendees, notes).
-- We follow the same pattern as `todo`: store the description in
-- records.body, structured fields in metadata, posture='open'.

ALTER TABLE public.records DROP CONSTRAINT type_valid;

ALTER TABLE public.records ADD CONSTRAINT type_valid CHECK (
    type = ANY (ARRAY[
        'password'::text, 'totp'::text, 'ed25519_key'::text,
        'secp256k1_key'::text, 'seed'::text,
        'journal'::text, 'shard'::text,
        'thought'::text, 'todo'::text, 'aiquery'::text,
        'reading'::text, 'contact'::text, 'expense'::text
    ])
);

ALTER TABLE public.records DROP CONSTRAINT posture_type_matches;

ALTER TABLE public.records ADD CONSTRAINT posture_type_matches CHECK (
    CASE
        WHEN type = ANY (ARRAY[
            'password'::text, 'totp'::text, 'ed25519_key'::text,
            'secp256k1_key'::text, 'seed'::text]) THEN
                posture = 'vault'::text AND body IS NULL
        WHEN type = ANY (ARRAY['journal'::text, 'shard'::text]) THEN
                posture = 'sealed'::text AND body IS NOT NULL
        ELSE posture = 'open'::text
    END
);

-- v0.6 — allow records.type = 'calsub'.
--
-- Calendar subscriptions (a read-only iCal feed: name + URL) were stored
-- device-locally on each client (SharedPreferences on Android, localStorage
-- on the web), so a feed you subscribed to on one device never appeared on
-- the other. Promote the subscription LIST to a synced record so it rides the
-- existing records pipeline and converges across devices.
--
--   body      = the iCal feed URL
--   metadata  = { "name": <display name> }
--   id        = "calsub" + abs(hashCode(url))  (deterministic → same URL on
--               any device is one row, so subscribing on both de-dupes)
--   posture   = 'open'  (covered by the existing ELSE branch below)

ALTER TABLE public.records DROP CONSTRAINT type_valid;
ALTER TABLE public.records ADD CONSTRAINT type_valid CHECK (
    type = ANY (ARRAY[
        'password'::text, 'totp'::text, 'ed25519_key'::text,
        'secp256k1_key'::text, 'seed'::text,
        'journal'::text, 'shard'::text,
        'thought'::text, 'todo'::text, 'aiquery'::text,
        'reading'::text, 'contact'::text, 'expense'::text,
        'sketch'::text, 'mail'::text, 'calsub'::text
    ])
);

-- posture_type_matches already forces posture='open' for any type outside the
-- vault/sealed sets, so 'calsub' (posture='open') needs no change there.

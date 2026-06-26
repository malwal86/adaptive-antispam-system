-- Story 14.05: the audit trail for privileged, unredacted access to email PII.
--
-- The default reads are redacted (14.01) and most surfaces never see raw content.
-- The escape hatches — ?reveal=true, /emails/{id}/raw, and erasure — are now gated
-- by server-side authorization, and every authorized access is recorded HERE: who
-- accessed which email, in what way, and when. Without this log, "reveal requires
-- authz" would be unprovable after the fact; with it, every unmasked access is
-- attributable (the story's "every reveal/raw access has a corresponding audit entry").

create table reveal_access_audit (
    id          uuid        primary key,

    -- The email whose unredacted view was accessed. Deliberately not an FK to emails:
    -- this is an append-only log, and it must survive independently of the row it
    -- references (and record an attempt even for an id that turns out absent).
    email_id    uuid        not null,

    -- Who accessed it — the actor presented by the authorized caller (a human/service
    -- name), or the default 'operator' when the caller named no one.
    actor       text        not null,

    -- How: a redaction-bypassing read ('reveal' / 'raw') or an erasure ('erasure').
    access_type text        not null,

    at          timestamptz not null default now(),

    constraint reveal_access_audit_type_valid
        check (access_type in ('reveal', 'raw', 'erasure'))
);

-- The audit's natural reads: recent activity first, and "everything done to one email".
create index reveal_access_audit_at_idx on reveal_access_audit (at desc);
create index reveal_access_audit_email_idx on reveal_access_audit (email_id);

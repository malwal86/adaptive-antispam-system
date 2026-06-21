-- Append-only log of every reputation-affecting signal for a sender (story 03.01).
-- This is the SOURCE OF TRUTH for reputation and is fully auditable: the Beta score
-- and its uncertainty are a pure function of these rows, so the senders cache (V6)
-- and the Redis cache (story 03.04) are both rebuildable by replaying this log
-- (PRD §Subsystem 3 — append-only, auditable; §Data Model — reputation_events).

create table reputation_events (
    -- Surrogate key; the log is ordered by it for deterministic replay/audit. An
    -- identity column, not the business key, because one sender has many events.
    id            bigint           generated always as identity primary key,

    -- The sender this signal is about (com.antispam.event.SenderKey). Deliberately
    -- NOT a foreign key to senders: the truth log must not depend on the cache it
    -- feeds — the dependency runs the other way (senders is derived from these rows),
    -- so the log stays valid even if the cache is dropped and rebuilt.
    sender_key    text             not null,

    -- The signal: 'GOOD' (ham / positive engagement) or 'BAD' (spam / abuse),
    -- matching com.antispam.reputation.ReputationSignal. Summed into the Beta
    -- good/bad buckets.
    signal        text             not null,

    -- How much this signal counts toward its bucket. 1.0 today; story 03.03 (soft
    -- auth-gating) accrues unauthenticated mail at a reduced weight so it cannot
    -- inherit a domain's trust.
    weight        double precision not null default 1.0,

    -- Time-decay factor for this event. 1.0 today; story 03.02 applies an exponential
    -- ~7-day half-life lazily at read time. The column exists now so the log shape is
    -- stable when decay lands.
    decay_factor  double precision not null default 1.0,

    -- Provenance of the signal: 'decision' (live enforced verdict), 'feedback'
    -- (Epic 07), 'api' (manual/demo injection), 'seed', ... Audit, and lets later
    -- stages weight or filter by source.
    source        text             not null,

    occurred_at   timestamptz      not null default now()
);

-- Reputation is read and rebuilt per sender, so the whole log for one sender is the
-- hot access pattern; include id so the per-sender scan is already in replay order.
create index reputation_events_sender_idx on reputation_events (sender_key, id);

-- ---------------------------------------------------------------------------
-- Append-only enforcement (mirrors the emails canonical record, V1)
-- ---------------------------------------------------------------------------
-- The audit and rebuild guarantees only hold if a row, once written, is never
-- changed or removed. Reject UPDATE/DELETE/TRUNCATE at the database so no code path
-- -- not even a buggy one -- can rewrite reputation history. tg_op carries the
-- attempted operation, so one function serves both the row and statement triggers.
create or replace function reject_reputation_events_mutation() returns trigger as $$
begin
    raise exception 'reputation_events is append-only: % is not permitted', tg_op
        using errcode = 'restrict_violation';
end;
$$ language plpgsql;

create trigger reputation_events_reject_row_mutation
    before update or delete on reputation_events
    for each row execute function reject_reputation_events_mutation();

-- TRUNCATE bypasses row-level triggers, so block it with a statement trigger.
create trigger reputation_events_reject_truncate
    before truncate on reputation_events
    for each statement execute function reject_reputation_events_mutation();

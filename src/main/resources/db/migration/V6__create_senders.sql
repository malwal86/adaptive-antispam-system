-- Per-sender reputation cache (story 03.01). The authority for reputation is the
-- append-only reputation_events log (V7); this table holds only the *materialized*
-- current score so the common read (and later the console's live chart) need not
-- recompute from the whole event history every time. It is a cache: it can be
-- dropped and fully rebuilt by replaying reputation_events. The Redis cache in
-- story 03.04 follows the same contract (PRD §Subsystem 3, §Data Model:
-- senders.current_reputation_score).

create table senders (
    -- Stable, normalized sender identity (com.antispam.event.SenderKey): the From
    -- address, else the domain, else 'unknown-sender'. The same key Kafka partitions
    -- by, so one sender's updates serialize on one partition (PRD §Subsystem 3).
    sender_key               text             primary key,

    -- Materialized Beta-reputation mean (good+alpha)/(good+bad+alpha+beta). A CACHE
    -- of the value computed from reputation_events, never the authority; nullable
    -- until the sender's first event is recorded.
    current_reputation_score double precision,

    -- When current_reputation_score was last recomputed. Diagnostics/audit only; the
    -- authoritative value is always re-derivable from reputation_events.
    score_updated_at         timestamptz,

    first_seen_at            timestamptz      not null default now()
);

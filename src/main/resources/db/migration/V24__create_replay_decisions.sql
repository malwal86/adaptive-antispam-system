-- Story 09.01: replay decisions — the experimental verdicts produced when the immutable corpus is
-- re-published to emails.replay and scored under a chosen policy (PRD §Subsystem 8). These are
-- deliberately a SEPARATE table from `classifications`: a replay output is never an enforced live
-- decision and must never be confused with one (AC 5). A "run" is a logical batch identified by
-- run_id — every email republished in one replay shares it — mirroring feedback_events.run_id.
--
-- Side-effect isolation (story 09.03): replay scoring writes only here and reads live state without
-- mutating it. This table is one of the experiment-scoped sinks that path is allowed to write.

create table replay_decisions (
    id              uuid                primary key,

    -- Groups every decision produced by one replay, so a run is comparable, inspectable, or
    -- discardable as a unit, and so two runs of the same corpus+policy can be diffed for the
    -- determinism check. Not an FK — a run is a logical batch, not its own table here.
    run_id          uuid                not null,

    -- The policy this email was scored under (com.antispam.decision.policy.Policy#version). The
    -- whole point of replay is to score a CHOSEN policy, not the active one, so it is recorded
    -- on every row rather than inferred from the active regime at read time.
    policy_version  text                not null,

    -- The replayed email. FK to the immutable canonical record (the corpus replay reads from).
    email_id        uuid                not null references emails (id),

    -- The experimental verdict and its justification (com.antispam.decision.Decision / RouteUsed /
    -- ReasonCode / routing.RoutingReason). Stored as text / text[] — small, readable, stable
    -- vocabularies — matching how `classifications` records the same enums.
    decision        text                not null,
    route_used      text                not null,
    reason_codes    text[]              not null,
    routing_reasons text[]              not null,

    -- The fused P(abuse) the policy tiered, in [0,1]; null when the score was not fused (a hard-rule
    -- verdict, or a model score with no calibration installed) — exactly as `classifications.posterior`.
    posterior       double precision,

    created_at      timestamptz         not null default now(),

    -- One decision per (run, email): the scorer is deterministic, so a redelivery of the same
    -- replay message must not mint a second row — the consumer inserts on-conflict-do-nothing.
    constraint replay_decisions_run_email_unique unique (run_id, email_id),
    constraint replay_decisions_posterior_unit check (posterior is null or posterior between 0 and 1)
);

-- Read path: per run (compare/inspect/discard a run, and the 09.04 A/B harness's per-run metrics).
create index replay_decisions_run_id_idx on replay_decisions (run_id);

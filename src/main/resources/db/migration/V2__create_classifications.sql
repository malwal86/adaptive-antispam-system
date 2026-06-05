-- Every decision the pipeline reaches about an email, by any route (hard rule,
-- model, later LLM). Append-only in practice: a re-evaluation writes a new row
-- rather than mutating an old one, so the decision history stays auditable for
-- replay and eval. Unlike `emails`, this table is not DB-immutable — corrections
-- and re-scores are legitimate new rows, not edits.

create table classifications (
    id            uuid        primary key,

    -- The email this decision is about. FK to the immutable canonical record.
    email_id      uuid        not null references emails (id),

    -- Verdict tier: ALLOW / WARN / QUARANTINE / BLOCK (com.antispam.decision.Decision).
    decision      text        not null,

    -- All reason codes that justified the decision, for explainability. Stored as
    -- a text[] of com.antispam.decision.ReasonCode names; empty for a plain allow.
    reason_codes  text[]      not null default '{}',

    -- Which stage produced the decision: HARD_RULE / MODEL (com.antispam.decision.RouteUsed).
    -- A HARD_RULE row means the model and LLM were skipped entirely.
    route_used    text        not null,

    -- Time the producing route spent deciding. Hard rules target < 5ms.
    latency_ms    bigint      not null,

    created_at    timestamptz not null default now()
);

-- Decisions are looked up by the email they concern (e.g. the analyzer UI in 01.05).
create index classifications_email_id_idx on classifications (email_id);

-- model_version, policy_version, and llm_cost_usd (PRD §Data Model) are added by
-- the epics that populate them (04 model, 04.05 policies, 05 LLM) as additive,
-- non-destructive migrations — kept out of this story to avoid speculative columns.

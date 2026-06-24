-- Story 08.02: adversarial runs — the bounded, budgeted iterative attack loop (PRD §Subsystem 6,
-- §Data Model adversarial_runs). One row is one red-team campaign: the attacker mutates a real seed
-- spam, scores each variant against a FIXED defender, feeds back which variants bypassed, and has
-- the next generation target those gaps — for a bounded number of generations (3–5) under a hard
-- spend ceiling ("even my red-team has a budget"). The crucial invariant the schema records is that
-- the defender is fixed for the duration: defender_policy_version is captured once at run start and
-- every generation is scored under it; the defender adapts only BETWEEN runs via retrain (Epic 10),
-- never within one.
--
-- Like the rest of the arena this is an experiment-scoped path: it reads live reputation/policies but
-- never enforces a decision (variants are scored read-only, story 09.03). The run is the parent of
-- the per-generation adversarial_emails it mints (V27, extended below), so a campaign is fully
-- reconstructable: which seeds, which generations, which variants bypassed.

create table adversarial_runs (
    id                      uuid             primary key,

    -- The configured regimes this run pits against each other (PRD attacker_model / defender_model).
    -- attacker_model is the red-team's model (antispam.arena.attacker-model); defender_model is the
    -- model artifact the defender's active policy is calibrated for. Both recorded per row so a run is
    -- traceable to the regime that produced it even after config or the active policy changes.
    attacker_model          text             not null,
    defender_model          text             not null,

    -- The defender is FIXED for the run (AC 4): the active policy is captured once at start and every
    -- generation is scored under exactly this version. Stored so "no within-run retrain" is an
    -- auditable fact, not a code-only promise — adaptation happens only between runs (Epic 10).
    defender_policy_version text             not null,

    -- The attacker's goal bypass rate (the share of variants it aims to slip past the defender) and
    -- the rate it actually achieved. target is set at start; actual is null until the run terminates,
    -- then = bypassing variants / variants scored across the whole run. Both fractions in [0,1].
    target_bypass_rate      double precision not null,
    actual_bypass_rate      double precision,

    -- The two bounds that make the loop terminate by construction (AC 3): a hard generation cap
    -- (3–5) and a hard USD spend ceiling on attacker calls. generations_run and spent_usd record how
    -- much of each bound was consumed — a budget-exhausted run stops early with both below the cap.
    generation_cap          integer          not null,
    budget_usd              numeric(12, 4)   not null,
    generations_run         integer          not null default 0,
    spent_usd               numeric(12, 4)   not null default 0,

    -- Lifecycle (com.antispam.arena.RunStatus). running while the loop is live; completed when it
    -- terminates cleanly (cap reached, target met, or no gap left to attack); budget_exhausted when
    -- the hard spend ceiling stops it mid-run (partial results still recorded, AC 5); failed when an
    -- infrastructure error (e.g. the attacker model unreachable) aborts it, so no run is left dangling.
    status                  text             not null default 'running',

    created_at              timestamptz      not null default now(),
    completed_at            timestamptz,

    constraint adversarial_runs_target_unit check (target_bypass_rate between 0 and 1),
    constraint adversarial_runs_actual_unit
        check (actual_bypass_rate is null or actual_bypass_rate between 0 and 1),
    -- Bounded by construction: 1–5 generations (PRD "3–5"), a positive budget, non-negative consumption.
    constraint adversarial_runs_gen_cap_chk check (generation_cap between 1 and 5),
    constraint adversarial_runs_generations_chk check (generations_run between 0 and generation_cap),
    constraint adversarial_runs_budget_chk check (budget_usd > 0 and spent_usd >= 0),
    constraint adversarial_runs_status_chk
        check (status in ('running', 'completed', 'budget_exhausted', 'failed'))
);

-- Tie every minted variant to its run and the generation that produced it. Both nullable: a
-- standalone mutation (story 08.01, POST /arena/mutations) has no run, so run_id and generation stay
-- null; a variant minted inside the loop carries its run_id and a 1-based generation. The generation
-- is what lets a read show the attack concentrating on previously-successful strategies over time
-- (the targeting-works metric).
alter table adversarial_emails
    add column run_id     uuid    references adversarial_runs (id),
    add column generation integer,
    add constraint adversarial_emails_generation_chk
        check (generation is null or generation between 1 and 5),
    -- run_id and generation travel together: a loop variant has both, a standalone mutation has neither.
    add constraint adversarial_emails_run_generation_chk
        check ((run_id is null) = (generation is null));

-- Read path: every variant a run produced, in generation then mint order — replays the campaign.
create index adversarial_emails_run_idx on adversarial_emails (run_id, generation);

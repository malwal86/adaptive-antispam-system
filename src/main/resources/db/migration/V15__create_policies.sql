-- Story 04.05: the policies entity (PRD §Data Model) — the versioned decision regime that
-- turns a fused posterior into a tier. Bundling the tier thresholds and the LLM-routing
-- threshold into one versioned, flag-toggled row is what makes decisioning policy-driven:
-- shadow/replay (Epic 09) compares regimes and the retrain loop (Epic 10) promotes one by
-- flipping the active flag, all without a code change.

create table policies (
    -- Human-readable regime identifier, recorded as classifications.policy_version.
    version              text primary key,

    -- The single enforcing regime. The partial unique index below allows at most one
    -- active policy at a time; switching is an atomic clear-then-set (PolicyRepository).
    active               boolean not null default false,

    -- Posterior cut-points of the allow < warn < quarantine < block ladder, each the
    -- inclusive floor of its tier. A CHECK keeps them a non-decreasing ladder in [0,1]
    -- so the mapping is always well-defined (mirrors Policy's constructor invariant).
    warn_threshold       double precision not null,
    quarantine_threshold double precision not null,
    block_threshold      double precision not null,

    -- Routing threshold consumed by Epic 05; carried here so the whole regime is one bundle.
    llm_threshold        double precision not null,

    -- The model artifact this regime is calibrated for (OnnxModel.MODEL_VERSION).
    model_version        text not null,

    created_at           timestamptz not null default now(),

    constraint policies_thresholds_ladder check (
        warn_threshold >= 0 and block_threshold <= 1
        and warn_threshold <= quarantine_threshold
        and quarantine_threshold <= block_threshold
    )
);

-- At most one active policy: a partial unique index over the constant TRUE.
create unique index policies_one_active on policies (active) where active;

-- Seed the bootstrap regime as active so the decision path has a regime from first boot.
-- Defaults: warn at 0.50 (the low-FP "deliver + banner" lever), quarantine at 0.80, block
-- at 0.95; tune by inserting a new version and activating it.
insert into policies (
    version, active, warn_threshold, quarantine_threshold, block_threshold, llm_threshold, model_version)
values ('bootstrap-v1', true, 0.50, 0.80, 0.95, 0.40, 'bootstrap-v1');

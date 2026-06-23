-- Story 09.02: shadow decisions — for every live email, the verdict the ACTIVE (enforced) policy
-- assigns and the verdict a SHADOW (logged-only) policy would assign, plus their diff (PRD
-- §Subsystem 8, §Data Model shadow_decisions). This is the live evidence the promotion gate (Epic
-- 10) consumes: "if we switched from active to shadow, what would change, and in which direction?"
--
-- Both verdicts are scored from the SAME model output under the two policies (the model and the
-- fused posterior are policy-independent), so the diff isolates the policy effect — it is not
-- confounded by content variation. Zero user impact: the shadow path is logged-only and never
-- enforces, and side-effect isolation (story 09.03) keeps it read-only on live state but for this
-- experiment-scoped table.

create table shadow_decisions (
    id                    uuid              primary key,

    -- The live email both policies scored. FK to the immutable canonical record.
    email_id              uuid              not null references emails (id),

    -- The two regimes compared (policies.version). active_* is the enforced verdict's policy;
    -- shadow_* is the candidate's. Recorded per row so a diff is interpretable without re-joining
    -- to the policies table or assuming which regime was active/shadow at read time.
    active_policy_version text              not null,
    shadow_policy_version text              not null,

    -- The verdicts each policy assigned (com.antispam.decision.Decision) and the route each took
    -- (com.antispam.decision.RouteUsed) — LLM here means "would have escalated", never an actual call.
    active_decision       text              not null,
    shadow_decision       text              not null,
    active_route          text              not null,
    shadow_route          text              not null,

    -- The fused P(abuse) under each policy, in [0,1]; null when not fused. The posterior is in fact
    -- policy-independent, so these are equal whenever both are non-null — kept per-side for symmetry
    -- and so a future policy that alters fusion inputs remains representable.
    active_posterior      double precision,
    shadow_posterior      double precision,

    -- The diff, denormalized for cheap aggregation (com.antispam.experiment.shadow.ShadowDiff):
    --   agreement: AGREE when the two tiers match, else DISAGREE.
    --   direction: SAME, SHADOW_MORE_SEVERE, or SHADOW_LESS_SEVERE — which way the shadow would move
    --              the verdict, the signal the promotion gate weighs (a stricter candidate that only
    --              ever escalates is a different risk than one that softens).
    agreement             text              not null,
    direction             text              not null,

    created_at            timestamptz       not null default now(),

    constraint shadow_decisions_active_posterior_unit
        check (active_posterior is null or active_posterior between 0 and 1),
    constraint shadow_decisions_shadow_posterior_unit
        check (shadow_posterior is null or shadow_posterior between 0 and 1)
);

-- Read paths: per email (what the shadow would have done to this mail) and the promotion-evidence
-- aggregation (agreement/disagreement rates for a given active-vs-shadow pairing).
create index shadow_decisions_email_id_idx on shadow_decisions (email_id);
create index shadow_decisions_pairing_idx
    on shadow_decisions (active_policy_version, shadow_policy_version);

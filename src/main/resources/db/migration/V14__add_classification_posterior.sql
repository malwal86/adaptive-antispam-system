-- Story 04.04: record the Bayesian-fused posterior on the decision row, beside the
-- calibrated confidence that V12 added. The calibrated confidence is content evidence
-- alone; the posterior fuses it with the sender's reputation prior in log-odds with the
-- training base rate subtracted once (PRD §Subsystem 2). The uncertainty band is the
-- probability-space half-width propagated from the sender's Beta variance — the signal
-- Epic 05 routes the shakiest cases to the LLM on.
--
-- Both nullable, and absent on more rows than the V9/V12 model columns: a HARD_RULE
-- decision carries no model score at all, and a MODEL-route decision is fused only once
-- a calibration is installed (fusion requires a calibrated probability — story 04.04
-- AC 3). A model-route row scored before calibration legitimately has scores but no
-- posterior.

alter table classifications
    -- Fused P(abuse) in [0,1]: sigmoid(logit(reputation_prior) + logit(P_model) − logit(π_train)).
    add column posterior        double precision,

    -- Non-negative half-width around the posterior attributable to reputation uncertainty;
    -- wider for new/uncertain senders. Consumed by LLM routing (Epic 05).
    add column uncertainty_band double precision;

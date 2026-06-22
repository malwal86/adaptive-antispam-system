-- Story 04.01: record the in-process ONNX classifier's output on the decision row.
-- The V2 migration deliberately left model columns out as "added by the epics that
-- populate them"; this is that additive, non-destructive step for the model.
--
-- All three are nullable: a HARD_RULE decision short-circuits before the model runs,
-- so its row legitimately has no scores. A MODEL-route row carries all three.

alter table classifications
    -- Raw, uncalibrated P(spam) and P(phish) from the 3-class model, in [0,1].
    -- Calibrated confidence (Epic 04.02) and the fused, tiered verdict (04.04/04.05)
    -- are derived from these in later stories.
    add column spam_score      double precision,
    add column phishing_score  double precision,

    -- Identifier of the served model artifact (com.antispam.decision.model.OnnxModel
    -- MODEL_VERSION), so a decision is traceable to the exact model that produced it.
    -- The retrain loop (Epic 10) writes a new version here when it promotes a model.
    add column model_version    text;

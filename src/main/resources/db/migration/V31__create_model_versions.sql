-- Story 10.04: the model registry + promotion audit. When a gated retrain candidate is promoted
-- (PRD §Subsystem 9 steps 4–5), its model_version is registered here with its provenance, and the
-- flip of the active flag in `policies` (promote) — plus every rollback — is recorded in an audit
-- log. Serving never reads these tables: the active model is resolved from `policies.model_version`
-- and the artifact is fetched from storage. These exist purely so a promotion/rollback is auditable
-- (who/when/which version, AC 5) and the trained-model provenance is durable.

-- The registry of promoted model artifacts. One row per model_version ever promoted live. The
-- bootstrap model is NOT seeded here — it serves from the classpath and was never "promoted".
create table model_versions (
    -- The artifact identifier, matching `policies.model_version` and the ONNX filename.
    version        text                primary key,

    -- Where the artifact lives, mirroring scripts/stage-candidate.sh's upload layout:
    -- candidates/<version>/spam-classifier-<version>.onnx. Audit/provenance; serving derives the
    -- path from the version, it does not read it back from here.
    artifact_uri   text                not null,

    -- The precision the candidate earned on the golden set at promotion time (the gate's verdict
    -- number). Nullable because a future promotion path may register without a precision figure.
    gate_precision double precision,

    -- The replay run the precision gate (10.03) graded to clear this candidate. Audit trail back to
    -- the evidence that justified the promotion.
    source_run     uuid,

    -- Who promoted it (an actor string; there is no auth subject in this system) and when.
    promoted_by    text                not null,
    promoted_at    timestamptz         not null default now()
);

-- The promotion/rollback audit log: every activation change driven by the retrain loop. Append-only
-- in spirit (one row per action); a rollback to a prior version is a row here, not an edit of the
-- model_versions row it points at.
create table model_activation_audit (
    id             uuid                primary key,

    -- 'promote' (a gated candidate flipped live) or 'rollback' (the flag flipped back to a prior
    -- policy). Constrained so the log can only carry the two real actions.
    action         text                not null,

    -- The policy whose active flag this action set, and the model that policy is calibrated for.
    -- model_version is denormalized onto the row so the audit reads standalone even if a policy is
    -- later re-pointed.
    policy_version text                not null,
    model_version  text                not null,

    -- Who performed it and when.
    actor          text                not null,
    at             timestamptz         not null default now(),

    constraint model_activation_audit_action_valid check (action in ('promote', 'rollback'))
);

-- The audit's natural read is "the recent activation history", newest first.
create index model_activation_audit_at_idx on model_activation_audit (at desc);

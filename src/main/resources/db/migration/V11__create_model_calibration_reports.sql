-- Story 04.02: the reliability evidence for a calibration run, one row per run, kept
-- per model_version. It records the raw-vs-calibrated ECE (so the improvement
-- calibration bought is visible), the gate outcome (passed against max_ece_threshold),
-- and the calibrated reliability curve as supporting detail. This is the durable
-- "the score is a true probability" evidence the PRD's hard dependency demands
-- (§Subsystem 2), and the calibration check the promotion gate (Epic 10) will reuse.
--
-- Append-only history: a new run is a new row, never an update, so the calibration of
-- a model over time is auditable. The latest row for a model_version is the current one.
--
-- Note: this table is the evidence, not the serving state. The fitted calibrator itself
-- lives in memory on the serving path (ActiveCalibrator) and is re-installed by re-running
-- a calibration; durable hot-load of a fitted calibrator is the model-registry story (10.04).

create table model_calibration_reports (
    id                  uuid        primary key,

    -- The served artifact these scores came from (OnnxModel.MODEL_VERSION).
    model_version       text        not null,

    -- The calibration method fit (e.g. 'isotonic').
    method              text        not null,

    -- Number of held-out (eval-side) predictions the measurement is over.
    sample_count        integer     not null,

    -- Equal-width bins used for the reliability curve and ECE.
    bin_count           integer     not null,

    -- Expected calibration error of the raw scores and of the calibrated scores, in [0,1].
    ece_raw             double precision not null,
    ece_calibrated      double precision not null,

    -- The gate: the ceiling this run was judged against, and whether it passed.
    max_ece_threshold   double precision not null,
    passed              boolean     not null,

    -- The calibrated reliability curve: an array of {lowerEdge, upperEdge, count,
    -- meanPredicted, observedFrequency} objects.
    reliability_bins    jsonb       not null,

    created_at          timestamptz not null default now()
);

-- "Latest report for a model" is the common read, so index it descending by time.
create index model_calibration_reports_version_idx
    on model_calibration_reports (model_version, created_at desc);

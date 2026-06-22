-- Story 04.02: record the calibrated abuse probability on the decision row, beside
-- the raw model scores that V9 added. The raw spam/phish scores are the model's
-- uncalibrated outputs; this is P(abuse) after the active calibrator corrects it into
-- a true frequency — the value the fusion stage (04.04) will consume.
--
-- Nullable for the same reason as the V9 columns: a HARD_RULE decision short-circuits
-- before the model runs, so its row carries no scores and no confidence. A MODEL-route
-- row always has it (equal to the raw abuse score until a calibration is fit, then the
-- corrected probability).

alter table classifications
    add column calibrated_confidence double precision;

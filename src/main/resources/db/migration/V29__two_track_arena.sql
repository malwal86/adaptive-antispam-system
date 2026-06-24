-- Story 08.02b: two-track arena — mutate spam AND legit mail (PRD §Subsystem 6). Track A mutates
-- abuse seeds and measures bypass (recall stress); Track B mutates legit (ham) mail, preserving the
-- ham ground truth, and measures false-positive escalation (precision stress). Without Track B,
-- hardening recall could silently wreck precision. A variant's track is its ground-truth class
-- (ham => Track B, spam/phish => Track A), so the track is derived, never stored redundantly.

-- 1) Track B variants are legit mail, so the lineage table must accept the ham class. (V27 had
--    constrained the label to the abuse classes because story 08.01 mutated abuse seeds only.)
alter table adversarial_emails drop constraint adversarial_emails_label_chk;
alter table adversarial_emails add constraint adversarial_emails_label_chk
    check (ground_truth_label in ('spam', 'phish', 'ham'));

-- 2) The fixed defender's verdict on each scored variant: whether it was delivered to the inbox.
--    Null for a standalone mutation (story 08.01) that no run scored. For a loop variant it makes the
--    outcome durable and queryable: a Track A bypass is a spam/phish variant the defender delivered
--    (true); a Track B false positive is a ham variant the defender withheld (false) — the
--    wrongly-blocked good mail captured for the precision-floor retrain corpus (Epic 10/11).
alter table adversarial_emails add column defender_delivered boolean;

-- Read path for the precision-floor corpus: the legit variants the fixed defender wrongly blocked.
create index adversarial_emails_blocked_ham_idx on adversarial_emails (run_id)
    where ground_truth_label = 'ham' and defender_delivered = false;

-- 3) Per-track run metrics (AC 3: recall and precision pressure reported separately). actual_bypass_rate
--    (V28) stays the Track A recall number — the share of abuse variants that reached the inbox;
--    precision_fp_rate is the Track B precision number — the share of legit variants the defender
--    wrongly blocked. Each is null when its track did not run, so a single-track run reports only the
--    dimension it stressed. (actual_bypass_rate was already nullable in V28.)
alter table adversarial_runs add column precision_fp_rate double precision;
alter table adversarial_runs add constraint adversarial_runs_precision_unit
    check (precision_fp_rate is null or precision_fp_rate between 0 and 1);

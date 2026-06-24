-- Story 08.04: bypass-rate measurement vs a fixed baseline + corpus feedback (PRD §Subsystem 6,
-- §Demo "danger missed by baseline"). actual_bypass_rate (V28) is already the run's Track A bypass
-- under the CURRENT defender — the share of abuse variants that reached the inbox. This migration
-- adds the comparison against a FIXED baseline defender: the same variants scored under the genesis
-- (or a configured) policy, so a run can report "this is what the baseline would have missed" next to
-- "this is what the current defender missed". The gap, and its downward trend across successive runs
-- (as the defender retrains BETWEEN runs, Epic 10), is the headline number the arena exists to prove.
--
-- The baseline is measured after the loop terminates, scoring the persisted variants read-only under
-- the captured baseline policy — no live state is touched (story 09.03). Both columns are null until
-- that post-step records them, and baseline_bypass_rate stays null when a run had no Track A variants
-- to score (a legit-only run), mirroring how actual_bypass_rate is null with no Track A.

alter table adversarial_runs
    -- The fixed reference defender the run's variants were ALSO scored against: the genesis policy by
    -- default, or antispam.arena.baseline-policy-version when configured. Captured per row so the
    -- comparison is traceable even after policies are promoted (Epic 10). Null if none was resolvable.
    add column baseline_policy_version text,
    -- The Track A bypass rate under that baseline: baseline-delivered abuse variants / abuse variants
    -- scored, in [0,1]. Null until measured, and null after if the run had no Track A. Compared against
    -- actual_bypass_rate to surface "danger missed by baseline".
    add column baseline_bypass_rate    double precision,
    add constraint adversarial_runs_baseline_unit
        check (baseline_bypass_rate is null or baseline_bypass_rate between 0 and 1);

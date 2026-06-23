-- Story 06.01: the burst/campaign velocity threshold. The runtime burst detector counts a
-- sender's messages in a trailing sliding window (Redis); when the count exceeds this threshold
-- the decision is escalated regardless of posterior (PRD §Subsystem 1 step 4, §Subsystem 4), so
-- a warmed-up sender launching a sudden blast is caught by velocity even when content alone looks
-- borderline. Carried in the policy so the trigger is one tunable per versioned regime —
-- comparable under shadow/replay (Epic 09) and promotable by the retrain loop (Epic 10) — exactly
-- like the tier and routing thresholds already bundled here.
--
-- NOT NULL with a default backfills the existing bootstrap-v1 row in place; the CHECK keeps it a
-- positive count (>= 1), mirroring Policy's constructor invariant. The default 20 is a velocity a
-- normal sender does not reach inside the window but a blast does — tune by inserting a new
-- version and activating it.

alter table policies
    add column burst_threshold integer not null default 20;

alter table policies
    add constraint policies_burst_threshold_positive check (burst_threshold >= 1);

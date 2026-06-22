-- Story 04.05: record which decision regime produced each decision. policy_version ties a
-- classifications row to the policies row that was active when it was decided, so a verdict
-- is reproducible and shadow/replay (Epic 09) can attribute outcomes to a regime.
--
-- Nullable because decisions recorded before this story carry no policy; every decision
-- made from 04.05 onward stamps the active version (PolicyDecisionService), whatever its
-- route — a hard-rule verdict records the regime it was made under even though it did not
-- consult the thresholds.

alter table classifications
    add column policy_version text;

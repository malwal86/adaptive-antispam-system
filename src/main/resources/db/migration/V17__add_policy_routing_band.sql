-- Story 05.01: the second routing knob. llm_threshold (V15) governs the low-model-confidence
-- predicate; this column governs the boundary-proximity predicate — the half-width of the band
-- around each tier cut-point within which a posterior is "near a boundary" and escalates to the
-- LLM. The sender's Beta-variance uncertainty widens this band further at decide time (PRD
-- §Subsystem 2), so a new/uncertain sender routes even when its posterior is not especially close
-- to a boundary. Bundling it into the regime keeps the whole routing policy in one versioned row.
--
-- NOT NULL with a default so the existing bootstrap-v1 row is backfilled in place; the CHECK keeps
-- it a probability-space width in [0,1], mirroring Policy's constructor invariant.

alter table policies
    add column routing_band_width double precision not null default 0.05;

alter table policies
    add constraint policies_routing_band_unit check (routing_band_width >= 0 and routing_band_width <= 1);

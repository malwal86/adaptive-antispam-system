-- Story 08.01: adversarial emails — the variants the red-team mints by perturbing a REAL seed spam
-- (PRD §Subsystem 6, §Data Model adversarial_emails). Every row is generated, never invented: it
-- records the perturbation applied (mutation_strategy), the ground-truth class it still belongs to
-- (preserved from the seed by construction), the attacker model that produced it, and its lineage
-- back to the real seed (and, for an iterative variant, to the parent variant it was mutated from).
--
-- The mutated content itself lives in `emails` as an ordinary canonical record (variant_email_id),
-- so a variant is scored through the exact same decision pipeline as real mail (AC 5). This table is
-- the lineage/metadata over those rows — it is what makes a variant traceable and what later feeds
-- bypassing variants into the retrain corpus (story 08.04). It is written by the arena (an
-- experiment-scoped path); it never enforces a live decision.

create table adversarial_emails (
    id                 uuid          primary key,

    -- The ingested mutated email: an ordinary `emails` row (ingest_source = 'adversarial'), which is
    -- what lets the variant be scored by the same pipeline as real mail. One adversarial record per
    -- variant email — a variant is minted once and logged once.
    variant_email_id   uuid          not null references emails (id),

    -- Lineage. seed_email_id is the REAL spam this variant ultimately descends from (the root of the
    -- attack family); it is never null because seed-grounding is the whole point. parent_variant_id
    -- is the immediate parent in an iterative attack (story 08.02): null here means the variant was
    -- mutated directly from the seed, which is the only case story 08.01 produces.
    seed_email_id      uuid          not null references emails (id),
    parent_variant_id  uuid          references adversarial_emails (id),

    -- The perturbation applied (com.antispam.arena.MutationStrategy) and the ground-truth class the
    -- variant still belongs to (com.antispam.seed.GroundTruthLabel). Both are small, stable lowercase
    -- vocabularies stored as text, matching how the rest of the schema records the same enums. The
    -- label is constrained to the abuse classes: a mutation of spam remains spam (AC 3); mutating
    -- legit mail to stress precision is the two-track story (08.03), not this one.
    mutation_strategy  text          not null,
    ground_truth_label text          not null,

    -- The configured attacker model that produced the variant (PRD's attacker_model), recorded per
    -- row so a variant is traceable to the regime that minted it even after the config changes.
    attacker_model     text          not null,

    created_at         timestamptz   not null default now(),

    -- A given canonical email is logged as a variant at most once: re-minting identical bytes (the
    -- idempotent `emails` insert returns the existing id) must not create a second lineage row.
    constraint adversarial_emails_variant_unique unique (variant_email_id),
    constraint adversarial_emails_strategy_chk
        check (mutation_strategy in ('synonym', 'homoglyph', 'structure', 'reframe')),
    constraint adversarial_emails_label_chk
        check (ground_truth_label in ('spam', 'phish'))
);

-- Read paths: every variant descended from a given seed (an attack family), and the iterative
-- children of a given parent variant (story 08.02's bounded loop walks lineage forward).
create index adversarial_emails_seed_idx on adversarial_emails (seed_email_id);
create index adversarial_emails_parent_idx on adversarial_emails (parent_variant_id);

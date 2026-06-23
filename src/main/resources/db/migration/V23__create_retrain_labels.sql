-- Story 07.03: the retrain label sink — one of the two sinks the weighted/corroborated feedback
-- gate writes (the other is reputation_events). Epic 10 trains the next classifier from these rows
-- alongside the seed corpus and arena output, so a label here is a weighted training example: the
-- label is the email's class, and the weight is how much the gate trusts it given the (possibly
-- adversarial) feedback that corroborated it (PRD §Subsystem 7 — two sinks, weighted; Epic 10).
--
-- Like reputation_events (V7), this is an append-only, auditable log: a label is written once with
-- its full provenance and never mutated. Feedback is an attack surface, so AC 3 requires that the
-- only path into this table is the gate — nothing writes a label without passing weighting and
-- corroboration first.

create table retrain_labels (
    -- Canonical id, assigned by the gate. Not derived from the email, because one email can earn
    -- several labels over time (different runs, later seed/arena sources in Epic 10).
    id            uuid                primary key,

    -- The email this label is about. FK to the immutable canonical record.
    email_id      uuid                not null references emails (id),

    -- The training label: the email's true class (com.antispam.seed.GroundTruthLabel) as ham/spam/
    -- phish. The feedback corroborates *how much to trust* this label; it is not itself the label.
    label         text                not null,

    -- How much this example counts in training: the corroborated, capped weight from the gate.
    -- Strictly positive — a zero-weight example carries no signal and is never written.
    weight        double precision    not null,

    -- Provenance of the label: 'feedback' today (the gate); 'seed'/'arena' once Epic 10 adds those
    -- sources. Audit, and lets the trainer weight or filter by source.
    source        text                not null,

    -- Per-item audit trail (AC 4/AC 5): the run, corroboration key, distinct-reporter count and
    -- aggregate weight, and this item's persona/action/confidence/trust. JSONB so the shape can
    -- grow (Epic 10 sources carry different provenance) without a migration.
    provenance    jsonb               not null,

    created_at    timestamptz         not null default now(),

    constraint retrain_labels_weight_positive check (weight > 0)
);

-- Read paths Epic 10 needs: build a training set over all labels (scan), and inspect/ dedupe the
-- labels an email has earned.
create index retrain_labels_email_id_idx on retrain_labels (email_id);
create index retrain_labels_source_idx on retrain_labels (source);

-- ---------------------------------------------------------------------------
-- Append-only enforcement (mirrors reputation_events, V7, and the emails record, V1)
-- ---------------------------------------------------------------------------
-- The audit guarantee only holds if a row, once written, is never changed or removed. Reject
-- UPDATE/DELETE/TRUNCATE at the database so no code path -- not even a buggy one -- can rewrite a
-- training label after the fact. tg_op carries the attempted operation, so one function serves both
-- the row and statement triggers.
create or replace function reject_retrain_labels_mutation() returns trigger as $$
begin
    raise exception 'retrain_labels is append-only: % is not permitted', tg_op
        using errcode = 'restrict_violation';
end;
$$ language plpgsql;

create trigger retrain_labels_reject_row_mutation
    before update or delete on retrain_labels
    for each row execute function reject_retrain_labels_mutation();

-- TRUNCATE bypasses row-level triggers, so block it with a statement trigger.
create trigger retrain_labels_reject_truncate
    before truncate on retrain_labels
    for each statement execute function reject_retrain_labels_mutation();

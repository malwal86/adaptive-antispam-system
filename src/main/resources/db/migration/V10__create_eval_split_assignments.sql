-- The current train/eval split over the labeled corpus (stories 11.01 / 11.03,
-- bootstrap). One row per labeled email records which side it was assigned and the
-- family it moved with, so the leakage-free split is a durable, queryable artifact:
-- calibration (04.02) draws its held-out reliability set from `split_side = 'eval'`,
-- and the labeled-data export (10.01) trains only on `split_side = 'train'`.
--
-- Only ONE split is materialized at a time — a rebuild replaces every row — because
-- the split is a deterministic function of the corpus plus (eval_fraction, seed),
-- not a history to accumulate. The PK on email_id makes a stale second split
-- impossible.
--
-- Distinct from `ground_truth_labels`: that table says what an email *is*; this one
-- says which side of the measurement it sits on. The eval side is sourced only from
-- ground-truth (high-confidence) labels — never simulator feedback (story 11.03).

create table eval_split_assignments (
    -- The email assigned. FK to the immutable canonical record; one side per email.
    email_id    uuid        primary key references emails (id),

    -- Which side of the split: whole families share a side, so this is uniform
    -- across every row with the same group_key.
    split_side  text        not null check (split_side in ('train', 'eval')),

    -- The effective family key the email grouped under (sender domain in the
    -- bootstrap, or a per-email singleton key). Kept so an audit can confirm no
    -- family spans the boundary directly from the table.
    group_key   text        not null,

    assigned_at timestamptz not null default now()
);

-- Calibration and export both filter by side, so index it.
create index eval_split_assignments_side_idx on eval_split_assignments (split_side);

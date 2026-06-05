-- High-confidence ground-truth labels for seed-corpus mail (story 01.03). These
-- are the trusted labels the eval golden set (Epic 11) and the labeled-data
-- export (Epic 10) draw on, so they are kept separate from any model decision in
-- `classifications` — provenance matters: a label here came from a public corpus,
-- not from our own classifier.
--
-- One label per email (PK on email_id): a re-seed of identical bytes dedupes to
-- the same email and the label insert is a no-op, so seeding is idempotent.
--
-- Version V3 (not V2): story 01.04 claims V2 on a parallel branch; keeping these
-- on distinct versions lets the two stories merge without a Flyway collision.

create table ground_truth_labels (
    -- The email this label is about. FK to the immutable canonical record.
    email_id       uuid        primary key references emails (id),

    -- The corpus-given truth: one of ham / spam / phish.
    label          text        not null check (label in ('ham', 'spam', 'phish')),

    -- Which public corpus this label came from (enron, spamassassin, phishtank, ...).
    -- The ingest provenance ('seed') lives on emails.ingest_source; this is the
    -- finer-grained dataset provenance.
    dataset_source text        not null,

    labeled_at     timestamptz not null default now()
);

-- Per-class counts and golden-set sampling query by label.
create index ground_truth_labels_label_idx on ground_truth_labels (label);

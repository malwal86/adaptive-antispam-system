-- Story 11.02: the two eval sets the gate and the demo read — a FROZEN golden benchmark and a ROLLING
-- fresh challenge set (PRD §Subsystem 10). The golden set is the gate's fixed yardstick: a versioned,
-- immutable snapshot of the held-out eval side (high-confidence labels only), so a retrain candidate's
-- precision is comparable across model_versions because it is measured against the exact same emails
-- every time (Epic 10.03). The fresh set is the secondary, sensitive read on the latest reported
-- attacks — it grows over time and is deliberately kept OUT of the golden set so adding new attacks can
-- never move the comparable baseline.
--
-- The golden set is sourced from the eval side of eval_split_assignments (V10), which is itself sourced
-- only from ground_truth_labels — so simulator feedback can never enter a judging set (story 11.03,
-- "feedback trains but never judges"). Freezing is a snapshot: a version copies the current eval side
-- and then never changes, exactly like the calibration of emails (V1) — re-running the split mutates
-- eval_split_assignments, but a frozen golden version is unaffected.

-- A golden version: the provenance of one frozen snapshot. The split configuration it was frozen from
-- (eval_fraction, seed) is recorded so the snapshot is reproducible-by-construction and auditable.
create table golden_set_versions (
    -- The stable label the gate pins to (e.g. 'golden-2026-06'). Caller-supplied so a human names the
    -- benchmark; the primary key makes re-freezing an existing version impossible at the database.
    version       text             primary key,

    -- The split knobs the eval side was produced under, copied for provenance.
    eval_fraction double precision  not null,
    seed          bigint            not null,

    -- How many emails the snapshot froze, denormalized for cheap reads and to assert the gate's
    -- min-samples honesty without a count over the members.
    member_count  integer           not null,

    created_at    timestamptz       not null default now(),

    constraint golden_set_versions_member_count_non_negative check (member_count >= 0)
);

create table golden_set_members (
    -- The frozen version this membership belongs to. Cascade is deliberately ABSENT: a version is
    -- immutable (see the triggers below), so its members are never cascaded away.
    version    text  not null references golden_set_versions (version),

    -- The frozen email and its high-confidence class, copied at freeze time. The label is duplicated
    -- from ground_truth_labels (not re-joined) so a frozen snapshot is self-contained and stays exactly
    -- as it was even if anything upstream were ever to change.
    email_id   uuid  not null references emails (id),
    label      text  not null check (label in ('ham', 'spam', 'phish')),

    -- One row per email per version; the snapshot is a set.
    primary key (version, email_id)
);

-- The gate joins golden membership to a run's replay decisions per email, so index the email side;
-- (version, email_id) is already covered by the primary key for whole-version reads.
create index golden_set_members_email_idx on golden_set_members (email_id);

-- ---------------------------------------------------------------------------
-- Immutability of the frozen golden set (mirrors retrain_labels, V23, and emails, V1)
-- ---------------------------------------------------------------------------
-- "Frozen" is only real if the database refuses to change a version once written. Reject UPDATE/DELETE
-- (row triggers) and TRUNCATE (statement trigger) on both golden tables, so no code path — not even a
-- buggy one — can rewrite a benchmark after the fact and silently break version comparability. INSERT
-- is still permitted: that is how a NEW version is frozen (versioning), which is additive, never a
-- mutation of an existing one.
create or replace function reject_golden_set_mutation() returns trigger as $$
begin
    raise exception 'the golden set is frozen: % is not permitted', tg_op
        using errcode = 'restrict_violation';
end;
$$ language plpgsql;

create trigger golden_set_versions_reject_row_mutation
    before update or delete on golden_set_versions
    for each row execute function reject_golden_set_mutation();
create trigger golden_set_versions_reject_truncate
    before truncate on golden_set_versions
    for each statement execute function reject_golden_set_mutation();

create trigger golden_set_members_reject_row_mutation
    before update or delete on golden_set_members
    for each row execute function reject_golden_set_mutation();
create trigger golden_set_members_reject_truncate
    before truncate on golden_set_members
    for each statement execute function reject_golden_set_mutation();

-- ---------------------------------------------------------------------------
-- The rolling fresh challenge set (secondary, appendable)
-- ---------------------------------------------------------------------------
-- The latest reported attacks, kept separate from the frozen golden set so it can grow without
-- touching the comparable baseline. One row per email (a set); appended as attacks are reported, with
-- the source recorded for audit. No immutability triggers — this is the mutable set by design.
create table fresh_challenge_members (
    email_id   uuid         primary key references emails (id),
    label      text         not null check (label in ('ham', 'spam', 'phish')),

    -- Where the report came from (e.g. 'arena', 'reported'); audit and later filtering.
    source     text         not null,

    added_at   timestamptz  not null default now()
);

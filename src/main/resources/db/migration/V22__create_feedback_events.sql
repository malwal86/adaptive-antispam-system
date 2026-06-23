-- Story 07.02: truth-conditioned feedback events — what synthetic users (07.01 personas) did with
-- mail the filter decided (PRD §Subsystem 7, §Data Model feedback_events). Each row is one persona's
-- sampled action on one decided email, with the action conditioned on the email's ground truth and
-- the verdict the user was shown. Feedback is an attack surface, so these events are NOT applied to
-- reputation/retrain labels directly — the weighting/corroboration gate (07.03) consumes them. This
-- table is the raw, auditable record of who did what.

create table feedback_events (
    id                  uuid                primary key,

    -- The decided email this action is about. FK to the immutable canonical record.
    email_id            uuid                not null references emails (id),

    -- The persona that acted (07.01). FK so an action is always traceable to a defined behavior.
    persona_id          uuid                not null references user_personas (id),

    -- Groups every event produced by one simulation run, so runs are comparable and a run can be
    -- read back or discarded as a unit. Not an FK — a run is a logical batch, not its own table here.
    run_id              uuid                not null,

    -- The sampled action (com.antispam.feedback.FeedbackAction): CLICK/REPORT on delivered mail,
    -- RESCUE on withheld mail, IGNORE always. Stored as text — a small, readable, stable vocabulary.
    action              text                not null,

    -- How confident the persona was in the action: the probability mass the conditioned sampler put
    -- on it, in [0,1]. The corroboration gate (07.03) weights reports/rescues by this.
    action_confidence   double precision    not null,

    -- Seconds the persona waited before acting — a coarse behavioral signal for later timing analysis.
    delay_seconds       bigint              not null,

    -- The verdict the user was shown (com.antispam.decision.Decision) and the email's true class
    -- (com.antispam.seed.GroundTruthLabel). Both are recorded so the conditioning is auditable and so
    -- the two downstream sinks (reputation events, retrain labels) need no re-join to interpret a row.
    decision_shown      text                not null,
    ground_truth        text                not null,

    -- Which persona/run produced the event, denormalized to a human-readable token (the persona name)
    -- per the PRD data model's `source` column; persona_id/run_id above are the structured keys.
    source              text                not null,

    created_at          timestamptz         not null default now(),

    constraint feedback_events_confidence_unit check (action_confidence between 0 and 1),
    constraint feedback_events_delay_non_negative check (delay_seconds >= 0)
);

-- Read paths: per run (compare/inspect/discard a run) and per email (what feedback an email drew).
create index feedback_events_run_id_idx on feedback_events (run_id);
create index feedback_events_email_id_idx on feedback_events (email_id);

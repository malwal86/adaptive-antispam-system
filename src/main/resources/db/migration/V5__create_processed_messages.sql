-- Processed-message ledger for idempotent at-least-once consumers (story 02.03).
-- Kafka delivers at-least-once: retries, consumer-group rebalances, and replays
-- (Epic 09) can hand the same message to a consumer more than once. Rather than pay
-- for exactly-once, each consumer records the natural key of every message it has
-- durably processed here and skips any key it has already recorded. The guarded
-- side effect and this insert commit in the same transaction, so a crash between
-- them rolls back both and the message is cleanly reprocessed, never lost
-- (PRD §Subsystem 3 — at-least-once + idempotent, not exactly-once).

create table processed_messages (
    -- The consuming group (the Kafka groupId, or any stable dedupe scope). Part of
    -- the key so each consumer dedupes independently: the feature extractor and,
    -- later, reputation accrual (Epic 03) each process every message exactly once
    -- without one blocking or shadowing the other.
    consumer_group text        not null,

    -- The natural (business) key of the processed message — e.g. the email id — NOT
    -- the Kafka topic/partition/offset. Offsets are reassigned on replay; the
    -- business key is not, so keying on it is exactly what lets a replayed message
    -- be recognized as already-processed.
    message_key    text        not null,

    -- When this consumer first recorded the message as processed. Audit/diagnostics
    -- only; the dedupe decision is the existence of the row, not its timestamp.
    processed_at   timestamptz not null default now(),

    -- The dedupe key. A claim is `insert ... on conflict (consumer_group,
    -- message_key) do nothing`: exactly one delivery wins the insert, every
    -- redelivery observes the conflict. The primary key index is also the lookup.
    primary key (consumer_group, message_key)
);

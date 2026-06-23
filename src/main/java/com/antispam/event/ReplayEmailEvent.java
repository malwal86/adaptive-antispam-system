package com.antispam.event;

import java.util.UUID;

/**
 * The value envelope published to {@code emails.replay} when the immutable corpus is replayed for
 * an experiment (story 09.01). Like {@link RawEmailEvent} it carries identity and routing only —
 * not the body, which stays in Postgres and is loaded by {@code emailId} — and is keyed by
 * {@link #senderKey} so the replay topic partitions exactly as {@code emails.raw} does.
 *
 * <p>Beyond identity it carries the two things that make a replay self-describing on the wire: the
 * {@link #runId} that groups every message of one replay, and the {@link #policyVersion} the
 * experimental consumer must score this email under. Baking the policy into the message (rather
 * than reading the active policy at consume time) is what makes a replay reproducible — re-running
 * the same run scores the same policy regardless of which regime is enforcing now.
 *
 * <p>It lives in this package alongside {@link RawEmailEvent} so the spine's JSON serde
 * configuration ({@code spring.json.trusted.packages = com.antispam.event}) covers it with no
 * change; the producer's type header lets the shared consumer deserializer pick the right type.
 *
 * @param schemaVersion envelope version; {@link #CURRENT_SCHEMA_VERSION} today
 * @param runId         groups every message of one replay; recorded on each replay decision
 * @param policyVersion the policy the consumer scores this email under (need not be active)
 * @param emailId       canonical id of the email to replay (the join key to Postgres)
 * @param senderKey     the partition key — normalized sender identity (see {@link SenderKey})
 */
public record ReplayEmailEvent(
        int schemaVersion,
        UUID runId,
        String policyVersion,
        UUID emailId,
        String senderKey) {

    /** Current envelope schema version. Bump when the shape changes. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Builds the event for one email of a replay run scoped to {@code policyVersion}. */
    public static ReplayEmailEvent of(UUID runId, String policyVersion, UUID emailId, String senderKey) {
        return new ReplayEmailEvent(CURRENT_SCHEMA_VERSION, runId, policyVersion, emailId, senderKey);
    }
}

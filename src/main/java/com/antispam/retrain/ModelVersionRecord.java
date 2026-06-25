package com.antispam.retrain;

import java.time.Instant;
import java.util.UUID;

/**
 * A registered model artifact in the registry (story 10.04): one row per model_version promoted live,
 * with the provenance that justified the promotion. The bootstrap model is never registered here — it
 * serves from the classpath and was never promoted — so a row here means "a retrain candidate that was
 * gated and flipped live". Serving never reads this; it exists for audit and durable provenance (AC 5).
 *
 * @param version       the artifact identifier, matching {@code policies.model_version}
 * @param artifactUri   where the artifact was staged ({@code candidates/<version>/...})
 * @param gatePrecision the precision the candidate earned on the golden set at promotion, or null
 * @param sourceRun     the replay run the precision gate graded to clear it, or null
 * @param promotedBy    the actor that promoted it
 * @param promotedAt    when it was promoted (assigned by the database)
 */
public record ModelVersionRecord(
        String version,
        String artifactUri,
        Double gatePrecision,
        UUID sourceRun,
        String promotedBy,
        Instant promotedAt) {
}

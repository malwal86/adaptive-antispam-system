package com.antispam.analyze;

import com.antispam.decision.Classification;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code POST /analyze} (and {@code GET /analyze/{emailId}}) response: the verdict
 * a viewer sees on the result card.
 *
 * <p>This is the analyzer contract the Abuse Lab Console (Epic 12) builds on, so
 * it carries everything a card renders: the colour-coding {@code tier}, the
 * reason chips, the route that decided, the latency, and a grounded one-line
 * {@code explanation}. The {@code tier} and {@code routeUsed} are emitted as the
 * lowercase tokens the UI keys its colours/labels off
 * ({@code allow|warn|quarantine|block}, {@code hard_rule|model}); the reason
 * codes stay as their canonical enum names (the closed machine vocabulary).
 *
 * <p>JSON field names follow the codebase's established camelCase convention
 * (story 01.02's {@code IngestResponse}/{@code EmailResponse}); the story spec's
 * {@code reason_codes}/{@code route_used} are illustrative of the fields, not the
 * casing.
 *
 * <p>The model scores ({@code spamScore}, {@code phishingScore}, {@code modelVersion})
 * are present only on a {@code model}-route verdict (story 04.01); a hard-rule verdict
 * short-circuits before the model runs, so they are {@code null} and omitted from the
 * JSON. They are the raw model outputs — the calibrated confidence and tier policy
 * that consume them arrive in later stories.
 *
 * @param emailId          the canonical email this verdict is about
 * @param classificationId the persisted {@code classifications} row id
 * @param tier             verdict tier, lowercase: {@code allow|warn|quarantine|block}
 * @param reasonCodes      canonical reason-code names justifying it (may be empty)
 * @param routeUsed        deciding route, lowercase: {@code hard_rule|model}
 * @param latencyMs        milliseconds that route spent deciding
 * @param explanation      one grounded human-readable sentence
 * @param spamScore        raw model P(spam) in {@code [0,1]}, or {@code null} on a hard-rule verdict
 * @param phishingScore    raw model P(phish) in {@code [0,1]}, or {@code null} on a hard-rule verdict
 * @param modelVersion     served model identifier, or {@code null} on a hard-rule verdict
 * @param decidedAt        when the decision was recorded
 * @param duplicate        true when the submitted email was already ingested
 *                         (identical bytes, or analysed by id) — no new canonical
 *                         row was created
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record AnalyzeResponse(
        UUID emailId,
        UUID classificationId,
        String tier,
        List<String> reasonCodes,
        String routeUsed,
        long latencyMs,
        String explanation,
        Double spamScore,
        Double phishingScore,
        String modelVersion,
        Instant decidedAt,
        boolean duplicate) {

    /** Maps a persisted {@link Classification} to the response a card renders. */
    public static AnalyzeResponse from(Classification classification, boolean duplicate) {
        var scores = classification.scores();
        return new AnalyzeResponse(
                classification.emailId(),
                classification.id(),
                classification.decision().name().toLowerCase(Locale.ROOT),
                classification.reasonCodes().stream().map(Enum::name).toList(),
                classification.route().name().toLowerCase(Locale.ROOT),
                classification.latencyMs(),
                AnalysisExplainer.explain(classification.decision(), classification.reasonCodes()),
                scores == null ? null : scores.spamScore(),
                scores == null ? null : scores.phishingScore(),
                scores == null ? null : scores.modelVersion(),
                classification.createdAt(),
                duplicate);
    }
}

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
 * <p>The model scores ({@code spamScore}, {@code phishingScore}, {@code modelVersion},
 * {@code calibratedConfidence}) are present only on a {@code model}-route verdict; a
 * hard-rule verdict short-circuits before the model runs, so they are {@code null} and
 * omitted from the JSON. {@code spamScore}/{@code phishingScore} are the raw model
 * outputs (story 04.01); {@code calibratedConfidence} is the corrected P(abuse) the
 * active calibrator produced (story 04.02). {@code posterior} fuses that calibrated
 * confidence with the sender's reputation prior (story 04.04); it and {@code uncertaintyBand}
 * are present only when the model score was fused (a calibration is installed), and
 * {@code null} otherwise — the value the tier policy (04.05) will consume.
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
 * @param calibratedConfidence calibrated P(abuse) in {@code [0,1]}, or {@code null} on a hard-rule verdict
 * @param posterior        reputation-fused P(abuse) in {@code [0,1]}, or {@code null} when not fused
 * @param uncertaintyBand  half-width around the posterior from reputation uncertainty, or {@code null}
 * @param delivered        whether the mail is delivered to the inbox: {@code true} for
 *                         {@code allow} and {@code warn} (deliver + banner), {@code false}
 *                         for {@code quarantine}/{@code block} — the deliver-vs-withhold
 *                         signal that makes {@code warn} distinct (story 04.05)
 * @param policyVersion    the active policy the decision was made under, or {@code null} for a
 *                         pre-04.05 decision
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
        Double calibratedConfidence,
        Double posterior,
        Double uncertaintyBand,
        boolean delivered,
        String policyVersion,
        Instant decidedAt,
        boolean duplicate) {

    /** Maps a persisted {@link Classification} to the response a card renders. */
    public static AnalyzeResponse from(Classification classification, boolean duplicate) {
        var scores = classification.scores();
        var fused = classification.fused();
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
                scores == null ? null : scores.calibratedConfidence(),
                fused == null ? null : fused.posterior(),
                fused == null ? null : fused.uncertaintyBand(),
                classification.decision().delivers(),
                classification.policyVersion(),
                classification.createdAt(),
                duplicate);
    }
}

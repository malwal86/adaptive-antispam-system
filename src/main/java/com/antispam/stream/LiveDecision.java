package com.antispam.stream;

import com.antispam.analyze.AnalyzeResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * The live-stream projection of a decision: the analyzer's {@link AnalyzeResponse verdict} plus the
 * human-readable "envelope" a demo viewer reads — who it's from, its subject, and a one-line preview.
 *
 * <p>The console's cards need to look like <em>email</em> (from · subject · preview), not telemetry,
 * but {@code AnalyzeResponse} is deliberately PII-free — {@code POST /analyze} must never echo a
 * pasted message's sender or body back onto a shared feed. So the envelope fields live only here, on
 * the stream projection, and are populated <em>only</em> for synthetic scenario/demo mail (gated by
 * the email's ingest source in {@link DecisionStream}). For ordinary traffic they stay {@code null}
 * and are omitted from the JSON, so the card renders exactly as before.
 *
 * <p>The verdict is {@link JsonUnwrapped}, so on the wire this is a single flat object — every
 * {@code AnalyzeResponse} field at the top level, alongside {@code sender}/{@code subject}/
 * {@code preview} — and the console parses it as the same shape it always has, with three new
 * optional fields.
 *
 * @param verdict the decision the analyzer card renders (flattened into this object)
 * @param sender  the friendly "from" (display name, else address) for scenario mail, else null
 * @param subject the message subject for scenario mail, else null
 * @param preview a short, plain-text body preview for scenario mail, else null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LiveDecision(
        @JsonUnwrapped AnalyzeResponse verdict,
        String sender,
        String subject,
        String preview) {

    /** The verdict with no envelope — for ordinary (non-scenario) traffic, which stays PII-free. */
    public static LiveDecision of(AnalyzeResponse verdict) {
        return new LiveDecision(verdict, null, null, null);
    }
}

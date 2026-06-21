package com.antispam.analyze;

import com.antispam.decision.Classification;
import com.antispam.decision.ClassificationRepository;
import com.antispam.decision.DecisionService;
import com.antispam.ingest.Email;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a single-email analysis for the analyzer surface: get the email
 * into the canonical store (ingest pasted bytes, or load an existing id), run the
 * decision pipeline, and return the persisted verdict. It owns no decision logic
 * of its own — it composes {@link IngestService} and {@link DecisionService} —
 * which keeps the analyzer a thin entry point over the same pipeline every other
 * surface uses.
 */
@Service
public class AnalyzeService {

    private final IngestService ingestService;
    private final DecisionService decisionService;
    private final ClassificationRepository classifications;

    @Autowired
    public AnalyzeService(
            IngestService ingestService,
            DecisionService decisionService,
            ClassificationRepository classifications) {
        this.ingestService = ingestService;
        this.decisionService = decisionService;
        this.classifications = classifications;
    }

    /**
     * Ingests pasted raw bytes (idempotently) and decides them.
     *
     * @throws IllegalArgumentException if {@code raw} is null/empty (from ingest)
     */
    public Analysis analyzeRaw(byte[] raw, String source) {
        IngestResult ingest = ingestService.ingest(raw, source);
        Email email = ingestService.findById(ingest.emailId())
                .orElseThrow(() -> new IllegalStateException(
                        "ingested email not retrievable: " + ingest.emailId()));
        Classification classification = decisionService.decide(email);
        return new Analysis(classification, ingest.duplicate());
    }

    /**
     * Decides an already-ingested email (e.g. a seed sample chosen in the picker).
     *
     * @throws EmailNotFoundException if no email has {@code emailId}
     */
    public Analysis analyzeExisting(UUID emailId) {
        Email email = ingestService.findById(emailId)
                .orElseThrow(() -> new EmailNotFoundException(emailId));
        Classification classification = decisionService.decide(email);
        return new Analysis(classification, true);
    }

    /**
     * The most recent decision recorded for {@code emailId}, for refetch — proving
     * the verdict is durable, not merely rendered. Empty if the email has never
     * been decided (or does not exist).
     */
    public Optional<Classification> latestDecision(UUID emailId) {
        List<Classification> history = classifications.findByEmailId(emailId);
        return history.isEmpty() ? Optional.empty() : Optional.of(history.getLast());
    }
}

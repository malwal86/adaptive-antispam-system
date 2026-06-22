package com.antispam.decision.embedding;

import com.antispam.features.EmailFeatureExtractor;
import com.antispam.idempotency.ProcessedMessageLedger;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Embeds an email's text with the in-process ONNX embedder and stores the vector
 * in pgvector (story 04.03). This is the seam between the event consumer (which
 * knows only an email id) and the embedder + repository: it loads the canonical
 * record, builds the embedding text, embeds it, and persists the row.
 *
 * <p><b>What text is embedded.</b> Subject plus decoded body — the subject carries
 * strong topical signal, and the body text is taken from
 * {@link EmailFeatureExtractor#displayText} so the embedder sees exactly the text
 * the feature pipeline already decodes (no second MIME parser).
 *
 * <p><b>Idempotency (story 02.03).</b> Mirrors {@code EmailFeaturesService}: under
 * at-least-once delivery the same email can arrive more than once, so the service
 * claims the email in the processed-message ledger under its own
 * {@link #CONSUMER_GROUP} scope before embedding; a redelivery loses the claim and
 * is skipped. The claim and the embedding write share one transaction, so a crash
 * between them rolls back the claim and the email is reprocessed rather than
 * recorded as done without its vector. The dedupe scope is independent of the
 * feature extractor's, so the two consumers never block each other.
 */
@Service
public class EmailEmbeddingService {

    /** This consumer's dedupe scope in the {@link ProcessedMessageLedger}; stable, not the Kafka group id. */
    static final String CONSUMER_GROUP = "embedding-extractor";

    private static final Logger log = LoggerFactory.getLogger(EmailEmbeddingService.class);

    private final EmailRepository emails;
    private final EmailEmbeddingRepository embeddings;
    private final OnnxEmbeddingModel model;
    private final ProcessedMessageLedger ledger;

    @Autowired
    public EmailEmbeddingService(EmailRepository emails, EmailEmbeddingRepository embeddings,
            OnnxEmbeddingModel model, ProcessedMessageLedger ledger) {
        this.emails = emails;
        this.embeddings = embeddings;
        this.model = model;
        this.ledger = ledger;
    }

    /**
     * Embeds and persists the vector for the given email id, exactly once per email
     * under at-least-once delivery.
     *
     * <p>If the email is not found (an event seen before its row is visible, or a
     * since-purged id) this logs and returns empty <em>without claiming the
     * ledger</em>, so a single unresolvable event never stalls the partition and a
     * later valid redelivery is still processed. Otherwise the ledger is claimed; a
     * lost claim (a redelivery this consumer already handled) returns empty and
     * writes nothing.
     *
     * @return the stored embedding on first delivery, or empty if the email could
     *     not be loaded or the delivery was a duplicate
     */
    @Transactional
    public Optional<float[]> embedAndStore(UUID emailId) {
        Optional<Email> email = emails.findById(emailId);
        if (email.isEmpty()) {
            log.warn("no email found for id={}; skipping embedding", emailId);
            return Optional.empty();
        }
        if (!ledger.claim(CONSUMER_GROUP, emailId.toString())) {
            log.debug("id={} already embedded by {}; skipping duplicate delivery", emailId, CONSUMER_GROUP);
            return Optional.empty();
        }
        float[] embedding = model.embed(embeddingText(email.get()));
        embeddings.save(emailId, OnnxEmbeddingModel.EMBEDDING_VERSION, embedding);
        log.info("embedded id={} version={}", emailId, OnnxEmbeddingModel.EMBEDDING_VERSION);
        return Optional.of(embedding);
    }

    /** Subject + decoded body, the text the embedding represents. */
    private static String embeddingText(Email email) {
        String subject = email.metadata().subject();
        String body = EmailFeatureExtractor.displayText(email.rawContent());
        return (subject == null ? "" : subject) + "\n" + body;
    }
}

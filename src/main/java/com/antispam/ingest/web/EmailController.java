package com.antispam.ingest.web;

import com.antispam.ingest.Email;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.privacy.crypto.ErasureOutcome;
import com.antispam.privacy.crypto.ErasureService;
import com.antispam.privacy.reveal.RevealAccessService;
import com.antispam.privacy.reveal.RevealAccessType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingest and retrieval of canonical emails.
 *
 * <p>{@code POST /emails} accepts either a raw RFC-822 body (text/plain,
 * message/rfc822, or octet-stream) or a JSON envelope carrying the raw message.
 * A brand-new record returns 201 with a Location header; re-ingesting identical
 * bytes returns 200 with the existing id (idempotent).
 */
@RestController
@RequestMapping("/emails")
public class EmailController {

    /** Media type for a raw email retrieved verbatim. */
    private static final MediaType MESSAGE_RFC822 = MediaType.parseMediaType("message/rfc822");

    private final IngestService ingestService;
    private final ErasureService erasureService;
    private final RevealAccessService revealAccess;

    @Autowired
    public EmailController(
            IngestService ingestService,
            ErasureService erasureService,
            RevealAccessService revealAccess) {
        this.ingestService = ingestService;
        this.erasureService = erasureService;
        this.revealAccess = revealAccess;
    }

    @PostMapping(
            consumes = {"text/plain", "message/rfc822", MediaType.APPLICATION_OCTET_STREAM_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestResponse> ingestRaw(
            @RequestBody byte[] rawContent,
            @RequestHeader(value = "X-Ingest-Source", required = false) String source) {
        return toResponse(ingestService.ingest(rawContent, source));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestResponse> ingestJson(@RequestBody JsonIngestRequest request) {
        byte[] rawContent = request.raw() == null ? null : request.raw().getBytes(StandardCharsets.UTF_8);
        return toResponse(ingestService.ingest(rawContent, request.source()));
    }

    /**
     * Returns the email, redacted by default (sender/recipients masked, raw body
     * omitted) — no authorization needed for the masked view. Pass {@code ?reveal=true}
     * for the full unredacted record: that is privileged and access-controlled (story
     * 14.05) — it requires a valid {@code Authorization: Bearer <secret>} and every such
     * access is audited (who revealed which email).
     */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EmailResponse> get(
            @PathVariable("id") UUID id,
            @RequestParam(name = "reveal", defaultValue = "false") boolean reveal,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Reveal-Actor", required = false) String actorHeader) {
        // The masked default is open; only the unredacted reveal is gated and audited.
        String actor = reveal ? revealAccess.authorize(authorization, actorHeader) : null;
        Optional<Email> found = ingestService.findById(id);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (reveal) {
            revealAccess.record(id, actor, RevealAccessType.REVEAL);
        }
        return ResponseEntity.ok(EmailResponse.from(found.get(), reveal));
    }

    /**
     * Returns the raw message bytes verbatim — a privileged, unredacted accessor gated
     * by authz (story 14.05): it requires a valid bearer secret and is audited. A
     * crypto-shredded record (story 14.02) has no recoverable body, so it returns 410
     * Gone rather than bytes.
     */
    @GetMapping(value = "/{id}/raw", produces = "message/rfc822")
    public ResponseEntity<byte[]> getRaw(
            @PathVariable("id") UUID id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Reveal-Actor", required = false) String actorHeader) {
        String actor = revealAccess.authorize(authorization, actorHeader);
        Optional<Email> found = ingestService.findById(id);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        revealAccess.record(id, actor, RevealAccessType.RAW);
        return rawBytes(found.get());
    }

    /**
     * Erases an email's body by crypto-shredding (story 14.02): its data key is
     * destroyed, leaving the immutable row intact but the content unrecoverable —
     * an Art. 17 right-to-erasure path that does not mutate the canonical record.
     * Privileged and access-controlled (story 14.05): requires a valid bearer secret,
     * and a performed erasure is audited.
     */
    @PostMapping(value = "/{id}/erasure", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EmailErasureResponse> erase(
            @PathVariable("id") UUID id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Reveal-Actor", required = false) String actorHeader) {
        String actor = revealAccess.authorize(authorization, actorHeader);
        ErasureOutcome outcome = erasureService.erase(id);
        if (outcome != ErasureOutcome.NOT_FOUND) {
            // The email existed and we acted on it (erased / already-erased / unencrypted) —
            // record the privileged action. A 404 leaves no audit (nothing was acted on).
            revealAccess.record(id, actor, RevealAccessType.ERASURE);
        }
        EmailErasureResponse body = new EmailErasureResponse(id, outcome);
        return switch (outcome) {
            case ERASED, ALREADY_ERASED -> ResponseEntity.ok(body);
            case NOT_FOUND -> ResponseEntity.notFound().build();
            // The email exists but was stored unencrypted, so there is no key to destroy:
            // 409, said plainly, beats pretending the content was erased.
            case NO_KEY -> ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        };
    }

    private static ResponseEntity<byte[]> rawBytes(Email email) {
        if (email.contentErased()) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body("content erased".getBytes(StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok().contentType(MESSAGE_RFC822).body(email.rawContent());
    }

    private static ResponseEntity<IngestResponse> toResponse(IngestResult result) {
        IngestResponse body = IngestResponse.from(result);
        if (result.duplicate()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/emails/" + result.emailId())).body(body);
    }
}

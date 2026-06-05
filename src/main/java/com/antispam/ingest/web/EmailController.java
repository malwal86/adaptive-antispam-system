package com.antispam.ingest.web;

import com.antispam.ingest.Email;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    public EmailController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping(consumes = {"text/plain", "message/rfc822", MediaType.APPLICATION_OCTET_STREAM_VALUE})
    public ResponseEntity<IngestResponse> ingestRaw(
            @RequestBody byte[] rawContent,
            @RequestHeader(value = "X-Ingest-Source", required = false) String source) {
        return toResponse(ingestService.ingest(rawContent, source));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestResponse> ingestJson(@RequestBody JsonIngestRequest request) {
        byte[] rawContent = request.raw() == null ? null : request.raw().getBytes(StandardCharsets.UTF_8);
        return toResponse(ingestService.ingest(rawContent, request.source()));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EmailResponse> get(@PathVariable UUID id) {
        return ingestService.findById(id)
                .map(email -> ResponseEntity.ok(EmailResponse.from(email)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/raw")
    public ResponseEntity<byte[]> getRaw(@PathVariable UUID id) {
        return ingestService.findById(id)
                .map(EmailController::rawBytes)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<byte[]> rawBytes(Email email) {
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

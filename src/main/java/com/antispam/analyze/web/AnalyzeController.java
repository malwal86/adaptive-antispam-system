package com.antispam.analyze.web;

import com.antispam.analyze.Analysis;
import com.antispam.analyze.AnalyzeRequest;
import com.antispam.analyze.AnalyzeResponse;
import com.antispam.analyze.AnalyzeService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
 * The single-email analyzer (story 01.05): the first user-facing decision surface
 * and the seed of the Living Spam Classifier Lab Console (Epic 12).
 *
 * <p>{@code POST /analyze} takes either a pasted raw message (JSON {@code raw}, or
 * a {@code text/plain}/{@code message/rfc822} body) or an existing email id (JSON
 * {@code emailId}, the seed-picker path), runs the decision pipeline, and returns
 * the verdict the result card renders. {@code GET /analyze/{emailId}} returns the
 * latest persisted decision, so a refetch proves the verdict is durable rather
 * than merely rendered.
 */
@RestController
@RequestMapping("/analyze")
public class AnalyzeController {

    private final AnalyzeService analyzeService;

    @Autowired
    public AnalyzeController(AnalyzeService analyzeService) {
        this.analyzeService = analyzeService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalyzeResponse> analyzeJson(@RequestBody AnalyzeRequest request) {
        Analysis analysis = request.emailId() != null
                ? analyzeService.analyzeExisting(request.emailId())
                : analyzeService.analyzeRaw(rawBytes(request), request.source());
        return ResponseEntity.ok(AnalyzeResponse.from(analysis.classification(), analysis.duplicate()));
    }

    @PostMapping(
            consumes = {"text/plain", "message/rfc822", MediaType.APPLICATION_OCTET_STREAM_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalyzeResponse> analyzeRaw(
            @RequestBody byte[] rawContent,
            @RequestHeader(value = "X-Ingest-Source", required = false) String source) {
        Analysis analysis = analyzeService.analyzeRaw(rawContent, source);
        return ResponseEntity.ok(AnalyzeResponse.from(analysis.classification(), analysis.duplicate()));
    }

    @GetMapping(value = "/{emailId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalyzeResponse> latest(@PathVariable("emailId") UUID emailId) {
        return analyzeService.latestDecision(emailId)
                .map(classification -> ResponseEntity.ok(AnalyzeResponse.from(classification, true)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static byte[] rawBytes(AnalyzeRequest request) {
        return request.raw() == null ? null : request.raw().getBytes(StandardCharsets.UTF_8);
    }
}

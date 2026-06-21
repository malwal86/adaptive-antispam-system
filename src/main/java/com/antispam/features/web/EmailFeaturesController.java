package com.antispam.features.web;

import com.antispam.features.EmailFeaturesService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read access to an email's extracted features.
 *
 * <p>{@code GET /emails/{id}/features} returns the current-version
 * {@code email_features} record, or 404 if features have not been extracted for
 * that email yet (extraction is asynchronous off the event spine, so a brand-new
 * email may briefly have none).
 */
@RestController
public class EmailFeaturesController {

    private final EmailFeaturesService service;

    @Autowired
    public EmailFeaturesController(EmailFeaturesService service) {
        this.service = service;
    }

    @GetMapping(value = "/emails/{id}/features", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EmailFeaturesResponse> get(@PathVariable("id") UUID id) {
        return service.findCurrent(id)
                .map(features -> ResponseEntity.ok(EmailFeaturesResponse.from(features)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

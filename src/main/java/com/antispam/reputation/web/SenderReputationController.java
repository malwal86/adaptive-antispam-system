package com.antispam.reputation.web;

import com.antispam.reputation.GatedReputation;
import com.antispam.reputation.ReputationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read and write access to a sender's reputation (story 03.01).
 *
 * <ul>
 *   <li>{@code GET /senders/{id}/reputation} — the sender's gated reputation: both the
 *       full authenticated view and the neutral-capped unauthenticated view (story
 *       03.03). An unseen sender is not an error: it returns the wide prior in both
 *       views, so callers never have to special-case "no history" (no 404).</li>
 *   <li>{@code POST /senders/{id}/reputation/events} — record one signal and return the
 *       updated gated reputation. The request's auth tokens decide the accrual bucket
 *       via {@link com.antispam.reputation.AuthGate}. This is the demo/manual seam onto
 *       {@link ReputationService#record}; in production the live decision path and the
 *       feedback simulator (Epic 07) call the same service method off the event
 *       spine.</li>
 * </ul>
 *
 * <p>The {@code id} is the sender key (com.antispam.event.SenderKey) — an address or
 * domain — passed straight through as the reputation identity.
 */
@RestController
public class SenderReputationController {

    private final ReputationService service;

    @Autowired
    public SenderReputationController(ReputationService service) {
        this.service = service;
    }

    @GetMapping(value = "/senders/{id}/reputation", produces = MediaType.APPLICATION_JSON_VALUE)
    public GatedReputationResponse get(@PathVariable("id") String senderKey) {
        return GatedReputationResponse.from(senderKey, service.gatedReputation(senderKey));
    }

    @PostMapping(
            value = "/senders/{id}/reputation/events",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public GatedReputationResponse record(
            @PathVariable("id") String senderKey,
            @Valid @RequestBody RecordSignalRequest request) {
        GatedReputation reputation = service.record(
                senderKey, request.signal(), request.weightOrDefault(), request.sourceOrDefault(), request.bucket());
        return GatedReputationResponse.from(senderKey, reputation);
    }
}

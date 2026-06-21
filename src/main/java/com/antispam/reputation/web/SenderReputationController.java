package com.antispam.reputation.web;

import com.antispam.reputation.BetaReputation;
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
 *   <li>{@code GET /senders/{id}/reputation} — the sender's current Beta reputation.
 *       An unseen sender is not an error: it returns the wide prior, so callers never
 *       have to special-case "no history" (no 404).</li>
 *   <li>{@code POST /senders/{id}/reputation/events} — record one signal and return
 *       the updated reputation. This is the demo/manual seam onto
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
    public ReputationResponse get(@PathVariable("id") String senderKey) {
        return ReputationResponse.from(senderKey, service.currentReputation(senderKey));
    }

    @PostMapping(
            value = "/senders/{id}/reputation/events",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ReputationResponse record(
            @PathVariable("id") String senderKey,
            @Valid @RequestBody RecordSignalRequest request) {
        BetaReputation reputation = service.record(
                senderKey, request.signal(), request.weightOrDefault(), request.sourceOrDefault());
        return ReputationResponse.from(senderKey, reputation);
    }
}

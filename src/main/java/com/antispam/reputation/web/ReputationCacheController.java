package com.antispam.reputation.web;

import com.antispam.reputation.ReputationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operational control of the reputation read cache (story 03.04). The full rebuild job
 * is exposed as an endpoint so the cache can be warmed or repaired after a flush without
 * a redeploy — the cache is derived, so this only ever reconstructs it from the Postgres
 * event log, never mutates truth.
 *
 * <p>{@code POST /admin/reputation/cache/rebuild} replays every sender's events and
 * repopulates Redis, returning how many senders were rebuilt.
 */
@RestController
public class ReputationCacheController {

    private final ReputationService service;

    @Autowired
    public ReputationCacheController(ReputationService service) {
        this.service = service;
    }

    @PostMapping(value = "/admin/reputation/cache/rebuild", produces = MediaType.APPLICATION_JSON_VALUE)
    public RebuildResult rebuild() {
        return new RebuildResult(service.rebuildCacheFromEvents());
    }

    /** How many senders' snapshots were reconstructed. */
    public record RebuildResult(int rebuilt) {
    }
}

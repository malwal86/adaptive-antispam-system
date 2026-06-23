package com.antispam.experiment.replay.web;

import com.antispam.experiment.replay.ComparisonReport;
import com.antispam.experiment.replay.ReplayAbRun;
import com.antispam.experiment.replay.ReplayAbService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The replay A/B endpoints (story 09.04).
 *
 * <p>{@code POST /replays/ab} starts an A/B — replays one fixed corpus through policy A and policy B —
 * returning {@code 202 Accepted} with the two run ids; the runs score asynchronously through the real
 * replay path. {@code GET /replays/ab/compare} grades two completed runs into a {@link ComparisonReport}:
 * per-policy precision/recall/cost/latency/bypass and the {@code B − A} deltas.
 *
 * <p>The comparison is returned as the domain {@link ComparisonReport} unchanged — it is the
 * programmatic form the promotion gate (Epic 10) consumes (AC 4), so the REST surface and the gate
 * read one shape rather than two that could drift.
 */
@RestController
@RequestMapping("/replays/ab")
public class ReplayAbController {

    private final ReplayAbService abService;

    @Autowired
    public ReplayAbController(ReplayAbService abService) {
        this.abService = abService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReplayAbRun> start(@RequestBody StartAbRequest request) {
        ReplayAbRun run = abService.startAb(request.policyVersionA(), request.policyVersionB());
        return ResponseEntity.accepted().body(run);
    }

    @GetMapping(path = "/compare", produces = MediaType.APPLICATION_JSON_VALUE)
    public ComparisonReport compare(
            @RequestParam("runA") UUID runA, @RequestParam("runB") UUID runB) {
        return abService.compare(runA, runB);
    }
}

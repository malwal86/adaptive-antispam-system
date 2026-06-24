package com.antispam.arena.web;

import com.antispam.arena.AdversarialEmailRepository;
import com.antispam.arena.AdversarialRun;
import com.antispam.arena.AdversarialRunRepository;
import com.antispam.arena.ArenaProperties;
import com.antispam.arena.AttackLoopService;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The adversarial arena's bounded-loop endpoints (story 08.02).
 *
 * <p>{@code POST /arena/runs} starts a run to completion and returns {@code 201 Created} with its
 * summary — the config, the fixed defender, how it terminated, and the achieved bypass rate.
 * {@code GET /arena/runs/{id}} reads a run back, and {@code GET /arena/runs/{id}/variants} lists every
 * variant the run minted in generation order — the campaign, replayed.
 */
@RestController
@RequestMapping("/arena")
public class AttackRunController {

    private final AttackLoopService loop;
    private final AdversarialRunRepository runs;
    private final AdversarialEmailRepository variants;
    private final ArenaProperties properties;

    @Autowired
    public AttackRunController(AttackLoopService loop, AdversarialRunRepository runs,
            AdversarialEmailRepository variants, ArenaProperties properties) {
        this.loop = loop;
        this.runs = runs;
        this.variants = variants;
        this.properties = properties;
    }

    @PostMapping(path = "/runs",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AttackRunResponse> start(@RequestBody StartRunRequest request) {
        AdversarialRun run = loop.run(request.toConfig(properties));
        return ResponseEntity.status(201).body(AttackRunResponse.from(run));
    }

    @GetMapping(path = "/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public AttackRunResponse byId(@PathVariable("runId") UUID runId) {
        return runs.findById(runId).map(AttackRunResponse::from)
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "no such run: " + runId));
    }

    @GetMapping(path = "/runs/{runId}/variants", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<AdversarialEmailResponse> variants(@PathVariable("runId") UUID runId) {
        return variants.findByRun(runId).stream().map(AdversarialEmailResponse::from).toList();
    }
}

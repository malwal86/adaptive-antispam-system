package com.antispam.arena.web;

import com.antispam.arena.AdversarialEmailRepository;
import com.antispam.arena.MutationService;
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

/**
 * The adversarial arena's mutation endpoints (story 08.01).
 *
 * <p>{@code POST /arena/mutations} mints one seed-grounded variant: perturb a real seed spam under a
 * chosen strategy and log it, returning {@code 201 Created} with the variant's lineage.
 * {@code GET /arena/seeds/{seedId}/mutations} returns every variant descended from a seed — one
 * attack family.
 */
@RestController
@RequestMapping("/arena")
public class MutationController {

    private final MutationService mutationService;
    private final AdversarialEmailRepository variants;

    @Autowired
    public MutationController(MutationService mutationService, AdversarialEmailRepository variants) {
        this.mutationService = mutationService;
        this.variants = variants;
    }

    @PostMapping(path = "/mutations",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdversarialEmailResponse> mutate(@RequestBody MutateRequest request) {
        AdversarialEmailResponse response = AdversarialEmailResponse.from(
                mutationService.mutate(request.seedEmailId(), request.strategy()));
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping(path = "/seeds/{seedId}/mutations", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<AdversarialEmailResponse> bySeed(@PathVariable("seedId") UUID seedId) {
        return variants.findBySeed(seedId).stream().map(AdversarialEmailResponse::from).toList();
    }
}
